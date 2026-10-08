package com.apoorvdarshan.verceltics.ui.pagespeed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.account.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountOptionUi
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi
import com.apoorvdarshan.verceltics.ui.sites.withoutAccount

enum class PageSpeedConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class PageSpeedOperation {
    RESTORING,
    CONNECTING,
    REFRESHING,
    DISCONNECTING,
    SWITCHING,
}

data class PageSpeedUiState(
    val status: PageSpeedConnectionStatus = PageSpeedConnectionStatus.RESTORING,
    val dashboard: PageSpeedDashboardUi? = null,
    val savedSiteUrl: String? = null,
    val operation: PageSpeedOperation? = PageSpeedOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val showDisconnectConfirmation: Boolean = false,
    val canDisconnect: Boolean = false,
    /** Saved audited sites and the active one. */
    val accounts: SiteAccountsUi = SiteAccountsUi.EMPTY,
    /** True while the form adds another site without disconnecting the active one. */
    val isAddingAccount: Boolean = false,
    val showRemoveAllConfirmation: Boolean = false,
) {
    val isBusy: Boolean
        get() = operation != null

    /** The connect form is on screen: no saved site yet, or adding another one. */
    val showsConnectForm: Boolean
        get() = status == PageSpeedConnectionStatus.DISCONNECTED || isAddingAccount

    val isConnected: Boolean
        get() = status == PageSpeedConnectionStatus.CONNECTED ||
            status == PageSpeedConnectionStatus.SAVED_UNAVAILABLE
}

/**
 * Lifecycle owner for PageSpeed state. API keys are method arguments only and are never copied into
 * [uiState], SavedStateHandle, error strings, or logs.
 */
class PageSpeedViewModel(
    private val gateway: PageSpeedUiGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PageSpeedUiState())
    val uiState: StateFlow<PageSpeedUiState> = _uiState.asStateFlow()

    private var operationJob: Job? = null
    private var generation: Long = 0

    init {
        restore()
    }

    fun restore() {
        launchExclusive(PageSpeedOperation.RESTORING) { currentGeneration ->
            _uiState.value = PageSpeedUiState()
            gateway.restore().fold(
                onSuccess = { restored ->
                    val accounts = savedAccounts(restored)
                    if (isCurrent(currentGeneration)) applyRestore(restored, accounts)
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = PageSpeedUiState(
                            status = PageSpeedConnectionStatus.SAVED_UNAVAILABLE,
                            operation = null,
                            error = safeMessage(error),
                            canDisconnect = true,
                        )
                    }
                },
            )
        }
    }

    fun connect(siteUrl: String, apiKey: SecretValue) {
        if (siteUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter a complete HTTPS site URL.")
            return
        }
        launchExclusive(PageSpeedOperation.CONNECTING) { currentGeneration ->
            val previous = _uiState.value
            _uiState.value = previous.copy(
                operation = PageSpeedOperation.CONNECTING,
                error = null,
                notice = null,
                showDisconnectConfirmation = false,
            )
            gateway.connect(apiKey, siteUrl.trim()).fold(
                onSuccess = { dashboard ->
                    val accounts = gateway.accounts().getOrNull()?.takeIf { it.accounts.isNotEmpty() }
                    if (isCurrent(currentGeneration)) {
                        applyDashboard(dashboard, accounts ?: _uiState.value.accounts.includingSite(dashboard))
                    }
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = previous.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = null,
                            showDisconnectConfirmation = false,
                        )
                    }
                },
            )
        }
    }

    fun refresh() {
        val current = _uiState.value
        if (current.status == PageSpeedConnectionStatus.DISCONNECTED || current.isBusy || current.isAddingAccount) return
        launchExclusive(PageSpeedOperation.REFRESHING) { currentGeneration ->
            val visible = _uiState.value
            _uiState.value = visible.copy(
                operation = PageSpeedOperation.REFRESHING,
                error = null,
                notice = null,
            )
            gateway.refresh().fold(
                onSuccess = { dashboard ->
                    if (isCurrent(currentGeneration)) applyDashboard(dashboard)
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = visible.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = "Showing the last saved result.",
                        )
                    }
                },
            )
        }
    }

    fun cancelOperation() {
        val operation = _uiState.value.operation
        if (operation != PageSpeedOperation.CONNECTING && operation != PageSpeedOperation.REFRESHING) {
            return
        }
        generation += 1
        operationJob?.cancel()
        operationJob = null
        _uiState.value = _uiState.value.copy(
            operation = null,
            error = null,
            notice = "Request cancelled.",
        )
    }

    /** Asks to remove the active site ("Remove current account" / "Disconnect"). */
    fun requestDisconnectConfirmation() {
        if (_uiState.value.canDisconnect && !_uiState.value.isBusy) {
            _uiState.value = _uiState.value.copy(showDisconnectConfirmation = true, showRemoveAllConfirmation = false)
        }
    }

    fun requestRemoveAllConfirmation() {
        if (_uiState.value.canDisconnect && !_uiState.value.isBusy) {
            _uiState.value = _uiState.value.copy(showRemoveAllConfirmation = true, showDisconnectConfirmation = false)
        }
    }

    fun dismissRemoveAllConfirmation() {
        _uiState.value = _uiState.value.copy(showRemoveAllConfirmation = false)
    }

    /** Removes every saved PageSpeed site (iOS "Remove All"). */
    fun confirmRemoveAll() {
        if (!_uiState.value.canDisconnect) return
        launchExclusive(PageSpeedOperation.DISCONNECTING) { currentGeneration ->
            val previous = _uiState.value
            _uiState.value = previous.copy(
                operation = PageSpeedOperation.DISCONNECTING,
                error = null,
                notice = null,
                showRemoveAllConfirmation = false,
                isAddingAccount = false,
            )
            gateway.removeAllAccounts().fold(
                onSuccess = {
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = PageSpeedUiState(status = PageSpeedConnectionStatus.DISCONNECTED, operation = null)
                    }
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = previous.copy(
                            operation = null,
                            error = safeMessage(error),
                            showRemoveAllConfirmation = false,
                        )
                    }
                },
            )
        }
    }

    /** Makes another saved site active offline; an in-flight refresh of the old one is dropped. */
    fun switchAccount(accountId: String) {
        val current = _uiState.value
        if (!current.isConnected || accountId == current.accounts.activeAccountId) return
        if (current.accounts.accounts.none { it.id == accountId }) return
        if (current.isBusy && current.operation != PageSpeedOperation.REFRESHING) return
        launchExclusive(PageSpeedOperation.SWITCHING) { currentGeneration ->
            val previous = _uiState.value.copy(operation = null, isAddingAccount = false)
            _uiState.value = previous.copy(operation = PageSpeedOperation.SWITCHING, error = null, notice = null)
            gateway.switchAccount(accountId).fold(
                onSuccess = { restored ->
                    val accounts = gateway.accounts().getOrNull() ?: previous.accounts.copy(activeAccountId = accountId)
                    if (isCurrent(currentGeneration)) applyRestore(restored, accounts)
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = previous.copy(error = safeMessage(error))
                    }
                },
            )
        }
    }

    /** Shows the connect form to audit another site while the active one stays saved. */
    fun startAddingAccount() {
        val current = _uiState.value
        if (!current.isConnected || current.isBusy || current.isAddingAccount) return
        _uiState.value = current.copy(
            isAddingAccount = true,
            error = null,
            notice = null,
            showDisconnectConfirmation = false,
            showRemoveAllConfirmation = false,
        )
    }

    fun cancelAddingAccount() {
        val current = _uiState.value
        if (!current.isAddingAccount) return
        if (current.operation == PageSpeedOperation.CONNECTING) cancelOperation()
        _uiState.value = _uiState.value.copy(isAddingAccount = false, error = null, notice = null)
    }

    /** Returns true when back closed a dialog or the add-site form instead of leaving the route. */
    fun handleBack(): Boolean {
        val current = _uiState.value
        return when {
            current.showDisconnectConfirmation -> {
                dismissDisconnectConfirmation()
                true
            }
            current.showRemoveAllConfirmation -> {
                dismissRemoveAllConfirmation()
                true
            }
            current.isAddingAccount -> {
                cancelAddingAccount()
                true
            }
            else -> false
        }
    }

    fun dismissDisconnectConfirmation() {
        _uiState.value = _uiState.value.copy(showDisconnectConfirmation = false)
    }

    fun confirmDisconnect() {
        if (!_uiState.value.canDisconnect) return
        launchExclusive(PageSpeedOperation.DISCONNECTING) { currentGeneration ->
            val previous = _uiState.value
            _uiState.value = previous.copy(
                operation = PageSpeedOperation.DISCONNECTING,
                error = null,
                notice = null,
                showDisconnectConfirmation = false,
                isAddingAccount = false,
            )
            val activeId = previous.accounts.activeAccountId
            val result = if (activeId == null) {
                gateway.disconnect().map { PageSpeedRestoreUi.NotConnected }
            } else {
                gateway.removeAccount(activeId)
            }
            result.fold(
                onSuccess = { next ->
                    val accounts = if (next is PageSpeedRestoreUi.NotConnected) {
                        SiteAccountsUi.EMPTY
                    } else {
                        gateway.accounts().getOrNull() ?: previous.accounts.withoutAccount(activeId)
                    }
                    if (isCurrent(currentGeneration)) applyRestore(next, accounts)
                },
                onFailure = { error ->
                    if (isCurrent(currentGeneration)) {
                        _uiState.value = previous.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = null,
                            showDisconnectConfirmation = false,
                        )
                    }
                },
            )
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(error = null, notice = null)
    }

    /** Saved sites after a restore; a restore with no site never needs another read. */
    private suspend fun savedAccounts(restored: PageSpeedRestoreUi): SiteAccountsUi =
        if (restored is PageSpeedRestoreUi.NotConnected) {
            SiteAccountsUi.EMPTY
        } else {
            gateway.accounts().getOrNull() ?: SiteAccountsUi.EMPTY
        }

    private fun applyRestore(restored: PageSpeedRestoreUi, accounts: SiteAccountsUi = SiteAccountsUi.EMPTY) {
        _uiState.value = when (restored) {
            PageSpeedRestoreUi.NotConnected -> PageSpeedUiState(
                status = PageSpeedConnectionStatus.DISCONNECTED,
                operation = null,
            )
            is PageSpeedRestoreUi.Available -> PageSpeedUiState(
                status = PageSpeedConnectionStatus.CONNECTED,
                dashboard = restored.dashboard,
                savedSiteUrl = restored.dashboard.siteUrl,
                operation = null,
                canDisconnect = true,
                accounts = accounts,
            )
            is PageSpeedRestoreUi.SavedWithoutSnapshot -> PageSpeedUiState(
                status = PageSpeedConnectionStatus.SAVED_UNAVAILABLE,
                savedSiteUrl = restored.siteUrl,
                operation = null,
                notice = "This connection has no saved audit yet. Refresh when you are online.",
                canDisconnect = true,
                accounts = accounts,
            )
            is PageSpeedRestoreUi.SavedUnavailable -> PageSpeedUiState(
                status = PageSpeedConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                canDisconnect = restored.canDisconnect,
                accounts = accounts,
            )
        }
    }

    private fun applyDashboard(
        dashboard: PageSpeedDashboardUi,
        accounts: SiteAccountsUi = _uiState.value.accounts.includingSite(dashboard),
    ) {
        _uiState.value = PageSpeedUiState(
            status = PageSpeedConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedSiteUrl = dashboard.siteUrl,
            operation = null,
            canDisconnect = true,
            accounts = accounts,
        )
    }

    private fun launchExclusive(
        operation: PageSpeedOperation,
        block: suspend (generation: Long) -> Unit,
    ) {
        generation += 1
        val currentGeneration = generation
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                block(currentGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(currentGeneration)) {
                    _uiState.value = _uiState.value.copy(
                        operation = null,
                        error = safeMessage(error),
                    )
                }
            }
        }
        if (operation == PageSpeedOperation.RESTORING) {
            _uiState.value = _uiState.value.copy(operation = PageSpeedOperation.RESTORING)
        }
    }

    private fun isCurrent(value: Long): Boolean = generation == value

    private fun safeMessage(error: Throwable): String =
        (error as? PageSpeedUiException)?.message
            ?: "PageSpeed & CrUX could not complete this request."

    override fun onCleared() {
        generation += 1
        operationJob?.cancel()
        operationJob = null
        super.onCleared()
    }

    class Factory(
        private val gateway: PageSpeedUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PageSpeedViewModel::class.java)) {
                "Unsupported PageSpeed ViewModel class."
            }
            return PageSpeedViewModel(gateway) as T
        }
    }
}

/** The list with [dashboard]'s site present and active (used when a fresh list is unavailable). */
internal fun SiteAccountsUi.includingSite(dashboard: PageSpeedDashboardUi): SiteAccountsUi {
    val id = dashboard.accountId ?: return this
    val title = dashboard.siteUrl.removePrefix("https://").removeSuffix("/")
    val option = SiteAccountOptionUi(id, title, dashboard.siteName)
    val position = accounts.indexOfFirst { it.id == id }
    val updated = if (position < 0) accounts + option else accounts.toMutableList().also { it[position] = option }
    return SiteAccountsUi(updated, id)
}
