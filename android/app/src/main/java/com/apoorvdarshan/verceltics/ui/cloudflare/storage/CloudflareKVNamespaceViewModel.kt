package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVKey
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVNamespace
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** iOS `CloudflareKVEditorEncoding`. */
enum class CloudflareKVEncoding(val label: String) { TEXT("Text"), BASE64("Base64") }

/** The KV value editor sheet (iOS `CloudflareKVValueEditor`). */
data class CloudflareKVEditorState(
    val existingKey: CloudflareKVKey?,
    val keyName: String,
    val value: String = "",
    val contentType: String = "text/plain; charset=utf-8",
    val expirationTtl: String = "",
    val encoding: CloudflareKVEncoding = CloudflareKVEncoding.TEXT,
    val isLoadingValue: Boolean = false,
    val hasLoadedExistingValue: Boolean = existingKey == null,
    val error: String? = null,
) {
    val canSave: Boolean get() = !isLoadingValue && keyName.isNotEmpty() && hasLoadedExistingValue

    override fun toString(): String = "CloudflareKVEditorState(key=$keyName, valueChars=${value.length}, encoding=$encoding)"
}

data class CloudflareKVNamespaceState(
    val namespace: CloudflareKVNamespace,
    val keys: List<CloudflareKVKey> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val editor: CloudflareKVEditorState? = null,
    val isRenaming: Boolean = false,
    val renameError: String? = null,
    val didDelete: Boolean = false,
)

/** Port of iOS `CloudflareKVNamespaceViewModel`: keys, value editor, rename and deletion. */
class CloudflareKVNamespaceViewModel(
    private val api: CloudflareStorageApi,
    val accountId: String,
    namespaceId: String,
    title: String,
    private val nowSeconds: () -> Double = { System.currentTimeMillis() / 1_000.0 },
) : CloudflareOperationsViewModel() {
    val namespaceId: String = namespaceId

    private val _state = MutableStateFlow(
        CloudflareKVNamespaceState(CloudflareKVNamespace(namespaceId, title.ifBlank { "KV namespace" }, null)),
    )
    val state: StateFlow<CloudflareKVNamespaceState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var valueJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        val hasSnapshot = !_state.value.isLoading
        _state.update { it.copy(isLoading = !hasSnapshot, isRefreshing = hasSnapshot) }
        loadJob = viewModelScope.launch {
            try {
                val keys = api.fetchKVKeys(accountId, namespaceId).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _state.update { it.copy(keys = keys, isLoading = false, isRefreshing = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoading = false, isRefreshing = false) }
                showError(error)
            }
        }
    }

    // MARK: Value editor

    fun openEditor(key: CloudflareKVKey?) {
        valueJob?.cancel()
        val ttl = key?.expiration?.let { maxOf(60, (it - nowSeconds()).toInt()).toString() }.orEmpty()
        _state.update {
            it.copy(editor = CloudflareKVEditorState(existingKey = key, keyName = key?.name.orEmpty(), expirationTtl = ttl, isLoadingValue = key != null))
        }
        key ?: return
        valueJob = viewModelScope.launch {
            try {
                val stored = api.readKVValue(accountId, namespaceId, key.name)
                val text = stored.utf8Text
                updateEditor {
                    it.copy(
                        contentType = stored.contentType ?: it.contentType,
                        value = text ?: stored.base64Text,
                        encoding = if (text != null) CloudflareKVEncoding.TEXT else CloudflareKVEncoding.BASE64,
                        isLoadingValue = false,
                        hasLoadedExistingValue = true,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateEditor { it.copy(isLoadingValue = false, error = cloudflareUserMessage(error)) }
            }
        }
    }

    fun updateEditor(transform: (CloudflareKVEditorState) -> CloudflareKVEditorState) {
        _state.update { current -> current.editor?.let { current.copy(editor = transform(it)) } ?: current }
    }

    fun dismissEditor() {
        valueJob?.cancel()
        _state.update { it.copy(editor = null) }
    }

    /** iOS `validateAndConfirm` then "Save this KV value?". */
    fun requestSaveValue() {
        val editor = _state.value.editor ?: return
        val trimmedKey = editor.keyName.trim()
        if (trimmedKey.isEmpty()) return updateEditor { it.copy(error = "Enter a KV key.") }
        val data = encodedValue(editor) ?: return updateEditor { it.copy(error = "The value is not valid Base64 data.") }
        val trimmedTtl = editor.expirationTtl.trim()
        val ttl = if (trimmedTtl.isEmpty()) {
            null
        } else {
            trimmedTtl.toIntOrNull()?.takeIf { it >= 60 }
                ?: return updateEditor { it.copy(error = "Expiration must be a whole number of at least 60 seconds.") }
        }
        val contentType = editor.contentType.trim().ifEmpty { "application/octet-stream" }
        updateEditor { it.copy(error = null) }
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Save this KV value?",
                message = if (editor.existingKey == null) {
                    "This creates $trimmedKey."
                } else {
                    "This overwrites the current value for $trimmedKey."
                } + ttl?.let { " It will expire in $it seconds." }.orEmpty(),
                confirmLabel = "Save Value",
                resourceId = trimmedKey,
            ),
        ) { confirmation ->
            try {
                api.writeKVValue(accountId, namespaceId, trimmedKey, data, contentType, ttl, confirmation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateEditor { it.copy(error = cloudflareUserMessage(error)) }
                throw error
            }
            _state.update { it.copy(editor = null) }
            showSuccess("KV value saved.")
            load()
        }
    }

    fun requestDeleteKey(key: CloudflareKVKey) {
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this KV key?",
                message = "${key.name} will be permanently removed.",
                confirmLabel = "Delete Key",
                resourceId = key.name,
                destructive = true,
            ),
        ) { confirmation ->
            api.deleteKVValue(accountId, namespaceId, key.name, confirmation)
            _state.update { state -> state.copy(keys = state.keys.filterNot { it.name == key.name }) }
            showSuccess("KV key deleted.")
        }
    }

    // MARK: Namespace

    fun openRename() {
        _state.update { it.copy(isRenaming = true, renameError = null) }
    }

    fun dismissRename() {
        _state.update { it.copy(isRenaming = false, renameError = null) }
    }

    fun requestRename(title: String) {
        val normalized = title.trim()
        val current = _state.value.namespace
        if (normalized.isEmpty()) return _state.update { it.copy(renameError = "Enter a KV namespace title.") }
        if (normalized == current.title) return
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Rename this namespace?",
                message = "${current.title} will be renamed to $normalized.",
                confirmLabel = "Rename Namespace",
                resourceId = namespaceId,
                workingId = RENAME_WORKING_ID,
            ),
        ) { confirmation ->
            val renamed = try {
                api.renameKVNamespace(accountId, namespaceId, normalized, confirmation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(renameError = cloudflareUserMessage(error)) }
                throw error
            }
            _state.update { it.copy(namespace = renamed, isRenaming = false, renameError = null) }
            showSuccess("KV namespace renamed.")
        }
    }

    fun requestDeleteNamespace() {
        val title = _state.value.namespace.title
        requestConfirmation(
            CloudflareConfirmationPrompt(
                title = "Delete this KV namespace?",
                message = "$title and every key in it will be permanently deleted.",
                confirmLabel = "Delete Namespace",
                resourceId = namespaceId,
                destructive = true,
                workingId = DELETE_WORKING_ID,
            ),
        ) { confirmation ->
            loadJob?.cancel()
            api.deleteKVNamespace(accountId, namespaceId, confirmation)
            _state.update { it.copy(didDelete = true, isLoading = false, isRefreshing = false) }
        }
    }

    companion object {
        const val RENAME_WORKING_ID: String = "kv-rename"
        const val DELETE_WORKING_ID: String = "kv-namespace-delete"

        /** Text as UTF-8, or strict Base64 with whitespace ignored (iOS `Data(base64Encoded:)`). */
        fun encodedValue(editor: CloudflareKVEditorState): ByteArray? = when (editor.encoding) {
            CloudflareKVEncoding.TEXT -> editor.value.toByteArray(StandardCharsets.UTF_8)
            CloudflareKVEncoding.BASE64 -> runCatching {
                Base64.getDecoder().decode(editor.value.filterNot(Char::isWhitespace))
            }.getOrNull()
        }

        /** iOS `keySubtitle`. */
        fun keySubtitle(key: CloudflareKVKey): String = buildList {
            add(key.expirationDate?.let { "Expires ${com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates.formatDateTime(it)}" } ?: "No expiration")
            if (key.metadata != null) add("Metadata")
        }.joinToString(" · ")
    }
}
