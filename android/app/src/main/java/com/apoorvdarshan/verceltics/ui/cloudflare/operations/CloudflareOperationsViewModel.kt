package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A confirmation the user must accept before a Cloudflare mutation is sent. [message] states exactly
 * what will change; [destructive] selects destructive styling for the confirm button.
 */
data class CloudflareConfirmationPrompt(
    val title: String,
    val message: String,
    val confirmLabel: String,
    /** The resource the API call verifies the confirmation against (iOS `confirmingResourceID`). */
    val resourceId: String,
    val destructive: Boolean = false,
    /** Busy marker shown while the confirmed mutation runs (defaults to [resourceId]). */
    val workingId: String = resourceId,
)

/** Result banner after an action (iOS `CloudflareActionResultBanner`). */
data class CloudflareActionBanner(val message: String, val isError: Boolean)

/**
 * Shared behavior for Cloudflare operations ViewModels: mutation confirmation gating, busy markers
 * and result banners. No mutation can run without [confirmPendingMutation] being invoked from the
 * confirmation dialog, which is the only place a [CloudflareMutationConfirmation] is created.
 */
abstract class CloudflareOperationsViewModel : ViewModel() {
    private val _confirmation = MutableStateFlow<CloudflareConfirmationPrompt?>(null)
    val confirmation: StateFlow<CloudflareConfirmationPrompt?> = _confirmation.asStateFlow()

    private val _banner = MutableStateFlow<CloudflareActionBanner?>(null)
    val banner: StateFlow<CloudflareActionBanner?> = _banner.asStateFlow()

    private val _working = MutableStateFlow<Set<String>>(emptySet())

    /** Busy markers of mutations currently running. */
    val working: StateFlow<Set<String>> = _working.asStateFlow()

    private var pendingAction: (suspend (CloudflareMutationConfirmation) -> Unit)? = null

    /** Shows [prompt]; [action] runs only after the user confirms it. */
    protected fun requestConfirmation(
        prompt: CloudflareConfirmationPrompt,
        action: suspend (CloudflareMutationConfirmation) -> Unit,
    ) {
        if (_working.value.contains(prompt.workingId)) return
        pendingAction = action
        _confirmation.value = prompt
    }

    /** Called only by the confirmation dialog's confirm button. */
    fun confirmPendingMutation(): Job? {
        val prompt = _confirmation.value ?: return null
        val action = pendingAction ?: return null
        _confirmation.value = null
        pendingAction = null
        return runMutation(prompt.workingId) { action(CloudflareMutationConfirmation(prompt.resourceId)) }
    }

    fun dismissPendingMutation() {
        _confirmation.value = null
        pendingAction = null
    }

    fun dismissBanner() {
        _banner.value = null
    }

    fun isWorking(id: String): Boolean = _working.value.contains(id)

    /** Runs [block] with a busy marker; failures become an error banner. */
    protected fun runMutation(workingId: String, block: suspend () -> Unit): Job = viewModelScope.launch {
        _working.update { it + workingId }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showError(error)
        } finally {
            _working.update { it - workingId }
        }
    }

    protected fun showSuccess(message: String) {
        _banner.value = CloudflareActionBanner(message, isError = false)
    }

    protected fun showError(error: Throwable) {
        _banner.value = CloudflareActionBanner(cloudflareUserMessage(error), isError = true)
    }

    protected fun showError(message: String) {
        _banner.value = CloudflareActionBanner(message, isError = true)
    }
}

/** User-facing text for any operations failure; unexpected exceptions never leak their text. */
fun cloudflareUserMessage(error: Throwable): String = when (error) {
    is CloudflareOperationException -> error.userMessage
    is CloudflareInputException -> error.message ?: "Check the entered values and try again."
    else -> "Cloudflare could not complete this request."
}

/** Validation failure raised by an editor before any request is built. */
class CloudflareInputException(message: String) : IllegalArgumentException(message)
