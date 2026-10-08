package com.apoorvdarshan.verceltics.ui.apiexplorer

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiAccessFilter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogSource
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipart
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipartField
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipartPart
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestPreset
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiResponseFormatter
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRequestSecurity
import com.apoorvdarshan.verceltics.data.apicatalog.buildPreset
import com.apoorvdarshan.verceltics.data.apicatalog.hasMissingRequiredParameters
import com.apoorvdarshan.verceltics.data.apicatalog.initialParameterValues
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One screen in the Complete API stack (iOS navigation destinations). */
sealed interface ProviderApiDestination {
    data object Catalog : ProviderApiDestination

    data class Operation(val operationId: String) : ProviderApiDestination

    data object Explorer : ProviderApiDestination

    data object Multipart : ProviderApiDestination
}

sealed interface ProviderApiCatalogState {
    data object Loading : ProviderApiCatalogState

    data class Loaded(val catalog: ProviderApiCatalog) : ProviderApiCatalogState

    data class Failed(val message: String) : ProviderApiCatalogState
}

/** Operation-detail editor state (iOS `ProviderAPIOperationView`). */
data class ProviderApiOperationDraft(
    val operationId: String,
    val values: Map<String, String>,
    val body: String,
    val contentType: String,
)

/** A binary body chosen from a file or composed as multipart; the bytes stay in the controller. */
data class ProviderApiAttachmentUi(val name: String, val byteCount: Int, val isMultipart: Boolean)

/** Raw explorer editor state (iOS `HostingAPIExplorerView` / `RegistrarAPIExplorerView`). */
data class ProviderApiExplorerDraft(
    val title: String,
    val method: String,
    val path: String,
    val body: String,
    val headersText: String,
    val contentType: String,
    val showOptionalBody: Boolean = false,
    val attachment: ProviderApiAttachmentUi? = null,
    val multipartFields: List<ProviderApiMultipartField> = emptyList(),
)

/** A response ready for display: formatted off the main thread and bounded for Compose. */
data class ProviderApiResponseUi(
    val statusCode: Int,
    val bodyText: String,
    val bodyTruncated: Boolean,
    val headersText: String,
    val headerCount: Int,
    val isBinary: Boolean,
    val byteCount: Int,
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299
}

data class ProviderApiMultipartDraft(
    val parts: List<ProviderApiMultipartPart>,
    val error: String? = null,
)

data class ProviderApiWorkspaceState(
    val isOpen: Boolean = false,
    val stack: List<ProviderApiDestination> = listOf(ProviderApiDestination.Catalog),
    val catalog: ProviderApiCatalogState = ProviderApiCatalogState.Loading,
    val isRefreshingCatalog: Boolean = false,
    val query: String = "",
    /** Null means the "All" tag chip. */
    val selectedTag: String? = null,
    val access: ProviderApiAccessFilter = ProviderApiAccessFilter.ALL,
    val operationDraft: ProviderApiOperationDraft? = null,
    val explorer: ProviderApiExplorerDraft? = null,
    val isSending: Boolean = false,
    val showWriteConfirmation: Boolean = false,
    val response: ProviderApiResponseUi? = null,
    val requestError: String? = null,
    val multipart: ProviderApiMultipartDraft? = null,
) {
    val destination: ProviderApiDestination
        get() = stack.last()

    val loadedCatalog: ProviderApiCatalog?
        get() = (catalog as? ProviderApiCatalogState.Loaded)?.catalog
}

/**
 * State holder for one provider's Complete API workspace: catalog browsing, operation detail,
 * the raw explorer and the multipart composer. It lives inside the provider's ViewModel (so sample
 * and live data never share it), runs requests on that ViewModel's [scope] (navigating away never
 * cancels a write that may already have been applied), and persists navigation and small drafts in
 * [savedStateHandle].
 */
class ProviderApiWorkspaceController(
    val profile: ProviderApiProfile,
    private val scope: CoroutineScope,
    private val backend: ProviderApiBackend,
    private val savedStateHandle: SavedStateHandle? = null,
    private val keyPrefix: String = "providerApi.${profile.kind.name.lowercase(Locale.ROOT)}.${profile.providerId}",
    private val formatDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(restoredState())
    val state: StateFlow<ProviderApiWorkspaceState> = _state.asStateFlow()

    private var manualPath: String = savedStateHandle?.get<String>(key(MANUAL_PATH_KEY)) ?: "/"
    private var catalogJob: Job? = null
    private var catalogGeneration = 0L
    private var sendGeneration = 0L
    private var attachmentBytes: ByteArray? = null
    private val multipartFiles = mutableMapOf<String, ByteArray>()

    // region Lifecycle and navigation

    /** Opens the catalog (iOS "Complete API"). [defaultPath] seeds the manual raw request. */
    fun open(defaultPath: String) {
        catalogGeneration += 1
        sendGeneration += 1
        catalogJob?.cancel()
        clearBinaryState()
        manualPath = defaultPath
        _state.value = ProviderApiWorkspaceState(isOpen = true)
        persist()
    }

    fun close() {
        if (!_state.value.isOpen && savedStateHandle?.get<Boolean>(key(OPEN_KEY)) != true) return
        catalogGeneration += 1
        sendGeneration += 1
        catalogJob?.cancel()
        clearBinaryState()
        _state.value = ProviderApiWorkspaceState(isOpen = false)
        persist()
    }

    /** Back within the workspace; closes it from the catalog. Always consumes the gesture. */
    fun back(): Boolean {
        val current = _state.value
        if (!current.isOpen) return false
        when {
            current.showWriteConfirmation -> dismissWriteConfirmation()
            current.stack.size > 1 -> pop()
            else -> close()
        }
        return true
    }

    private fun pop() {
        val current = _state.value
        when (current.destination) {
            ProviderApiDestination.Multipart -> {
                multipartFiles.values.forEach { it.fill(0) }
                multipartFiles.clear()
                _state.update { it.copy(stack = it.stack.dropLast(1), multipart = null) }
            }
            ProviderApiDestination.Explorer -> {
                sendGeneration += 1
                attachmentBytes?.fill(0)
                attachmentBytes = null
                _state.update {
                    it.copy(
                        stack = it.stack.dropLast(1),
                        explorer = null,
                        response = null,
                        requestError = null,
                        isSending = false,
                        showWriteConfirmation = false,
                    )
                }
            }
            is ProviderApiDestination.Operation -> _state.update { it.copy(stack = it.stack.dropLast(1), operationDraft = null) }
            ProviderApiDestination.Catalog -> Unit
        }
        persist()
    }

    // endregion

    // region Catalog

    /** Loads the catalog once per session; [forceRefresh] re-discovers Railway's live schema. */
    fun loadCatalog(source: ProviderApiCatalogSource, forceRefresh: Boolean = false) {
        val current = _state.value
        if (!current.isOpen) return
        if (current.catalog is ProviderApiCatalogState.Loaded && !forceRefresh) return
        if (catalogJob?.isActive == true) {
            if (!forceRefresh) return
            catalogJob?.cancel()
        }
        val generation = ++catalogGeneration
        _state.update {
            if (it.catalog is ProviderApiCatalogState.Loaded) {
                it.copy(isRefreshingCatalog = true)
            } else {
                it.copy(catalog = ProviderApiCatalogState.Loading, isRefreshingCatalog = true)
            }
        }
        catalogJob = scope.launch {
            val result = try {
                backend.loadCatalog(bundled = { source.catalog(profile.catalogId) }, forceRefresh = forceRefresh)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (generation != catalogGeneration) return@launch
            result.fold(
                onSuccess = { catalog -> applyCatalog(catalog) },
                onFailure = { error ->
                    _state.update {
                        if (it.catalog is ProviderApiCatalogState.Loaded) {
                            it.copy(isRefreshingCatalog = false)
                        } else {
                            it.copy(catalog = ProviderApiCatalogState.Failed(safeMessage(error, CATALOG_FAILURE)), isRefreshingCatalog = false)
                        }
                    }
                },
            )
        }
    }

    private fun applyCatalog(catalog: ProviderApiCatalog) {
        _state.update { current ->
            // Drop restored destinations whose operation no longer exists (e.g. Railway schema changes).
            val stack = current.stack.filter { destination ->
                destination !is ProviderApiDestination.Operation || catalog.operation(destination.operationId) != null
            }.ifEmpty { listOf(ProviderApiDestination.Catalog) }
            val operationId = (stack.lastOrNull { it is ProviderApiDestination.Operation } as? ProviderApiDestination.Operation)?.operationId
            val draft = current.operationDraft?.takeIf { it.operationId == operationId }
                ?: operationId?.let { id -> catalog.operation(id)?.let(::initialDraft) }
            current.copy(
                catalog = ProviderApiCatalogState.Loaded(catalog),
                isRefreshingCatalog = false,
                stack = stack,
                operationDraft = draft,
                selectedTag = current.selectedTag?.takeIf { tag -> catalog.operations.any { tag in it.tags } },
            )
        }
        persist()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        persist()
    }

    fun selectTag(tag: String?) {
        _state.update { it.copy(selectedTag = tag) }
        persist()
    }

    fun setAccess(access: ProviderApiAccessFilter) {
        _state.update { it.copy(access = access) }
        persist()
    }

    // endregion

    // region Operation detail

    fun openOperation(operationId: String) {
        val operation = _state.value.loadedCatalog?.operation(operationId) ?: return
        _state.update {
            it.copy(
                stack = it.stack.filterNot { destination -> destination is ProviderApiDestination.Operation } +
                    ProviderApiDestination.Operation(operationId),
                operationDraft = initialDraft(operation),
            )
        }
        persist()
    }

    fun updateParameter(parameterId: String, value: String) = updateOperationDraft { it.copy(values = it.values + (parameterId to value)) }

    fun updateOperationBody(body: String) = updateOperationDraft { it.copy(body = body) }

    fun updateOperationContentType(contentType: String) = updateOperationDraft { it.copy(contentType = contentType) }

    /** True while a required (non-managed) parameter is still empty: "Fill required fields". */
    fun operationHasMissingRequiredParameters(): Boolean {
        val draft = _state.value.operationDraft ?: return true
        val operation = _state.value.loadedCatalog?.operation(draft.operationId) ?: return true
        return operation.hasMissingRequiredParameters(draft.values, profile.managedHeaders)
    }

    /** iOS "Review request": carries the filled operation into the raw explorer. */
    fun reviewOperation() {
        val draft = _state.value.operationDraft ?: return
        val operation = _state.value.loadedCatalog?.operation(draft.operationId) ?: return
        if (operation.hasMissingRequiredParameters(draft.values, profile.managedHeaders)) return
        openExplorer(operation.buildPreset(draft.values, draft.body, draft.contentType, profile.managedHeaders))
    }

    private fun updateOperationDraft(transform: (ProviderApiOperationDraft) -> ProviderApiOperationDraft) {
        _state.update { current -> current.copy(operationDraft = current.operationDraft?.let(transform)) }
        persist()
    }

    private fun initialDraft(operation: com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiOperation) = ProviderApiOperationDraft(
        operationId = operation.id,
        values = operation.initialParameterValues(),
        body = operation.bodyTemplate,
        contentType = operation.contentTypes.firstOrNull() ?: "application/json",
    )

    // endregion

    // region Explorer

    /** iOS "Manual raw request": the provider's default path, method and body. */
    fun openManualExplorer() = openExplorer(null)

    fun openExplorer(preset: ProviderApiRequestPreset?) {
        sendGeneration += 1
        attachmentBytes?.fill(0)
        attachmentBytes = null
        val draft = ProviderApiExplorerDraft(
            title = preset?.title ?: "${profile.displayName} API",
            method = profile.forcedMethod ?: preset?.method?.uppercase(Locale.ROOT) ?: profile.defaultMethod,
            path = preset?.path ?: manualPath,
            body = preset?.body ?: profile.defaultBody,
            headersText = ProviderRequestSecurity.headerJson(preset?.headers.orEmpty()),
            contentType = preset?.contentType ?: "application/json",
            multipartFields = preset?.multipartFields.orEmpty(),
        )
        _state.update {
            it.copy(
                stack = it.stack.filterNot { destination ->
                    destination == ProviderApiDestination.Explorer || destination == ProviderApiDestination.Multipart
                } + ProviderApiDestination.Explorer,
                explorer = draft,
                response = null,
                requestError = null,
                isSending = false,
                showWriteConfirmation = false,
                multipart = null,
            )
        }
        persist()
    }

    fun setMethod(method: String) {
        if (profile.forcedMethod != null) return
        updateExplorer { it.copy(method = method.uppercase(Locale.ROOT)) }
    }

    fun setPath(path: String) = updateExplorer { it.copy(path = path) }

    fun setBody(body: String) = updateExplorer { it.copy(body = body) }

    fun setHeadersText(text: String) = updateExplorer { it.copy(headersText = text) }

    /** iOS: leaving multipart/octet-stream drops the encoded binary body. */
    fun setContentType(contentType: String) {
        val keepsBinary = isBinaryContentType(contentType)
        if (!keepsBinary) {
            attachmentBytes?.fill(0)
            attachmentBytes = null
        }
        updateExplorer { it.copy(contentType = contentType, attachment = it.attachment.takeIf { keepsBinary }) }
    }

    fun setShowOptionalBody(show: Boolean) = updateExplorer { it.copy(showOptionalBody = show) }

    /** Attaches a chosen file as the binary (`application/octet-stream`) body. */
    fun attachBinaryFile(name: String, bytes: ByteArray) {
        if (bytes.size > ProviderApiMultipart.UPLOAD_LIMIT_BYTES) {
            reportRequestError("Choose a file smaller than 25 MB.")
            return
        }
        attachmentBytes?.fill(0)
        attachmentBytes = bytes.copyOf()
        updateExplorer { it.copy(attachment = ProviderApiAttachmentUi(name, bytes.size, isMultipart = false)) }
        _state.update { it.copy(requestError = null) }
    }

    fun removeAttachment() {
        attachmentBytes?.fill(0)
        attachmentBytes = null
        updateExplorer { it.copy(attachment = null) }
    }

    fun reportRequestError(message: String) {
        _state.update { it.copy(requestError = message) }
    }

    /** True when the explorer would ask for confirmation before sending (iOS "Review … request"). */
    fun explorerRequiresConfirmation(): Boolean {
        val explorer = _state.value.explorer ?: return false
        return profile.requiresConfirmation(explorer.method, explorer.path, explorer.body)
    }

    /** The send button: confirms likely writes first, sends reads immediately. */
    fun requestSend() {
        val current = _state.value
        val explorer = current.explorer ?: return
        if (current.isSending || explorer.path.isBlank()) return
        if (profile.requiresConfirmation(explorer.method, explorer.path, explorer.body)) {
            _state.update { it.copy(showWriteConfirmation = true) }
        } else {
            send()
        }
    }

    /** Only a visible confirmation can send a write request. */
    fun confirmSend() {
        if (!_state.value.showWriteConfirmation) return
        _state.update { it.copy(showWriteConfirmation = false) }
        send()
    }

    fun dismissWriteConfirmation() {
        _state.update { it.copy(showWriteConfirmation = false) }
    }

    private fun send() {
        val current = _state.value
        val explorer = current.explorer ?: return
        if (current.isSending || explorer.path.isBlank()) return
        val request = try {
            val headers = ProviderRequestSecurity.parseHeaderJson(explorer.headersText)
            val binary = attachmentBytes?.takeIf { explorer.attachment != null }
            ProviderRawRequest(
                method = explorer.method,
                path = explorer.path.trim(),
                body = if (binary == null) explorer.body.takeIf { it.isNotBlank() } else null,
                binaryBody = binary,
                headers = headers,
                contentType = explorer.contentType.trim().ifEmpty { null },
            )
        } catch (error: ProviderApiRequestException) {
            _state.update { it.copy(requestError = error.message, response = null) }
            return
        }
        val generation = ++sendGeneration
        _state.update { it.copy(isSending = true, requestError = null, response = null) }
        scope.launch {
            val result = try {
                backend.send(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            val response = result.getOrNull()?.let { formatResponse(it) }
            if (generation != sendGeneration) return@launch
            _state.update {
                if (response != null) {
                    it.copy(isSending = false, response = response, requestError = null)
                } else {
                    it.copy(isSending = false, response = null, requestError = safeMessage(result.exceptionOrNull(), SEND_FAILURE))
                }
            }
        }
    }

    /** Small bodies format inline; large ones are parsed and pretty-printed off the main thread. */
    private suspend fun formatResponse(response: ProviderRawResponse): ProviderApiResponseUi =
        if (response.body.length <= INLINE_FORMAT_CHARACTERS) {
            buildResponseUi(response)
        } else {
            withContext(formatDispatcher) { buildResponseUi(response) }
        }

    private fun buildResponseUi(response: ProviderRawResponse): ProviderApiResponseUi {
        val formatted = if (response.isBinary) response.body else ProviderApiResponseFormatter.formatBody(response.body, response.header("Content-Type"))
        val (display, truncated) = ProviderApiResponseFormatter.displayText(formatted)
        return ProviderApiResponseUi(
            statusCode = response.statusCode,
            bodyText = display,
            bodyTruncated = truncated,
            headersText = ProviderApiResponseFormatter.headerText(response.headers),
            headerCount = response.headers.size,
            isBinary = response.isBinary,
            byteCount = response.byteCount,
        )
    }

    private fun updateExplorer(transform: (ProviderApiExplorerDraft) -> ProviderApiExplorerDraft) {
        _state.update { current -> current.copy(explorer = current.explorer?.let(transform)) }
        persist()
    }

    // endregion

    // region Multipart composer

    /** iOS "Build multipart upload": starts from the operation's schema fields. */
    fun openMultipartComposer() {
        val explorer = _state.value.explorer ?: return
        multipartFiles.values.forEach { it.fill(0) }
        multipartFiles.clear()
        _state.update {
            it.copy(
                stack = it.stack.filterNot { destination -> destination == ProviderApiDestination.Multipart } +
                    ProviderApiDestination.Multipart,
                multipart = ProviderApiMultipartDraft(ProviderApiMultipart.initialParts(explorer.multipartFields)),
            )
        }
    }

    fun updateMultipartName(partId: String, name: String) = updateParts { part -> if (part.id == partId) part.copy(name = name) else part }

    fun updateMultipartValue(partId: String, value: String) = updateParts { part -> if (part.id == partId) part.copy(value = value) else part }

    fun addMultipartField() {
        val draft = _state.value.multipart ?: return
        if (draft.parts.size >= ProviderApiMultipart.PART_LIMIT) {
            _state.update { it.copy(multipart = draft.copy(error = "Multipart requests support up to ${ProviderApiMultipart.PART_LIMIT} fields.")) }
            return
        }
        _state.update { it.copy(multipart = draft.copy(parts = draft.parts + ProviderApiMultipartPart(name = ""), error = null)) }
    }

    fun removeMultipartPart(partId: String) {
        val draft = _state.value.multipart ?: return
        val part = draft.parts.firstOrNull { it.id == partId } ?: return
        if (part.isRequired) return
        multipartFiles.remove(partId)?.fill(0)
        _state.update { it.copy(multipart = draft.copy(parts = draft.parts.filterNot { candidate -> candidate.id == partId })) }
    }

    fun attachMultipartFile(partId: String, fileName: String, mimeType: String?, bytes: ByteArray) {
        val draft = _state.value.multipart ?: return
        if (bytes.size > ProviderApiMultipart.remainingBytes(draft.parts, partId)) {
            _state.update { it.copy(multipart = draft.copy(error = "Could not read that file: ${ProviderApiMultipart.TOO_LARGE_MESSAGE}")) }
            return
        }
        multipartFiles.remove(partId)?.fill(0)
        multipartFiles[partId] = bytes.copyOf()
        _state.update {
            it.copy(
                multipart = draft.copy(
                    parts = draft.parts.map { part ->
                        if (part.id == partId) {
                            part.copy(fileName = fileName, mimeType = mimeType ?: "application/octet-stream", fileByteCount = bytes.size)
                        } else {
                            part
                        }
                    },
                    error = null,
                ),
            )
        }
    }

    fun reportMultipartError(message: String) {
        val draft = _state.value.multipart ?: return
        _state.update { it.copy(multipart = draft.copy(error = message)) }
    }

    /** iOS "Use multipart body": encodes the form and attaches it to the explorer. */
    fun composeMultipart() {
        val draft = _state.value.multipart ?: return
        val body = try {
            ProviderApiMultipart.compose(draft.parts, multipartFiles::get)
        } catch (error: ProviderApiRequestException) {
            _state.update { it.copy(multipart = draft.copy(error = error.message)) }
            return
        }
        attachmentBytes?.fill(0)
        attachmentBytes = body.bytes()
        multipartFiles.values.forEach { it.fill(0) }
        multipartFiles.clear()
        _state.update { current ->
            current.copy(
                stack = current.stack.dropLast(1),
                multipart = null,
                explorer = current.explorer?.copy(
                    contentType = body.contentType,
                    attachment = ProviderApiAttachmentUi("Multipart form · ${draft.parts.size} fields", body.size, isMultipart = true),
                ),
                requestError = null,
            )
        }
        persist()
    }

    private fun updateParts(transform: (ProviderApiMultipartPart) -> ProviderApiMultipartPart) {
        val draft = _state.value.multipart ?: return
        _state.update { it.copy(multipart = draft.copy(parts = draft.parts.map(transform))) }
    }

    // endregion

    // region Saved state

    private fun restoredState(): ProviderApiWorkspaceState {
        val handle = savedStateHandle ?: return ProviderApiWorkspaceState()
        if (handle.get<Boolean>(key(OPEN_KEY)) != true) return ProviderApiWorkspaceState()
        val stack = handle.get<ArrayList<String>>(key(STACK_KEY)).orEmpty().mapNotNull(::decodeDestination)
            .ifEmpty { listOf(ProviderApiDestination.Catalog) }
            .let { if (it.first() == ProviderApiDestination.Catalog) it else listOf(ProviderApiDestination.Catalog) + it }
        val explorer = handle.get<ArrayList<String>>(key(EXPLORER_KEY))?.takeIf { it.size == 7 }?.let { values ->
            ProviderApiExplorerDraft(
                title = values[0],
                method = values[1],
                path = values[2],
                body = values[3],
                headersText = values[4],
                contentType = values[5],
                showOptionalBody = values[6] == "true",
            )
        }
        val operation = handle.get<ArrayList<String>>(key(OPERATION_KEY))?.takeIf { it.size >= 3 && it.size % 2 == 1 }?.let { values ->
            ProviderApiOperationDraft(
                operationId = values[0],
                body = values[1],
                contentType = values[2],
                values = values.drop(3).chunked(2).associate { (name, value) -> name to value },
            )
        }
        val restoredStack = stack.filter { it != ProviderApiDestination.Explorer || explorer != null }
        return ProviderApiWorkspaceState(
            isOpen = true,
            stack = restoredStack,
            query = handle.get<String>(key(QUERY_KEY)).orEmpty(),
            selectedTag = handle.get<String>(key(TAG_KEY)),
            access = handle.get<String>(key(ACCESS_KEY))?.let { name ->
                ProviderApiAccessFilter.entries.firstOrNull { it.name == name }
            } ?: ProviderApiAccessFilter.ALL,
            operationDraft = operation,
            explorer = explorer?.takeIf { ProviderApiDestination.Explorer in restoredStack },
        )
    }

    private fun persist() {
        val handle = savedStateHandle ?: return
        val current = _state.value
        handle[key(OPEN_KEY)] = current.isOpen
        if (!current.isOpen) {
            listOf(STACK_KEY, EXPLORER_KEY, OPERATION_KEY, QUERY_KEY, TAG_KEY, ACCESS_KEY, MANUAL_PATH_KEY).forEach { handle.remove<Any>(key(it)) }
            return
        }
        handle[key(MANUAL_PATH_KEY)] = manualPath
        handle[key(STACK_KEY)] = ArrayList(current.stack.mapNotNull(::encodeDestination))
        handle[key(QUERY_KEY)] = current.query
        handle[key(TAG_KEY)] = current.selectedTag
        handle[key(ACCESS_KEY)] = current.access.name
        val explorer = current.explorer
        if (explorer != null && explorer.title.length + explorer.path.length + explorer.body.length + explorer.headersText.length < MAX_SAVED_DRAFT_CHARACTERS) {
            handle[key(EXPLORER_KEY)] = arrayListOf(
                explorer.title,
                explorer.method,
                explorer.path,
                explorer.body,
                explorer.headersText,
                explorer.contentType,
                explorer.showOptionalBody.toString(),
            )
        } else {
            handle.remove<Any>(key(EXPLORER_KEY))
        }
        val operation = current.operationDraft
        val operationSize = operation?.let { draft -> draft.body.length + draft.values.entries.sumOf { it.key.length + it.value.length } } ?: 0
        if (operation != null && operationSize < MAX_SAVED_DRAFT_CHARACTERS) {
            handle[key(OPERATION_KEY)] = ArrayList(
                listOf(operation.operationId, operation.body, operation.contentType) +
                    operation.values.flatMap { (name, value) -> listOf(name, value) },
            )
        } else {
            handle.remove<Any>(key(OPERATION_KEY))
        }
    }

    private fun encodeDestination(destination: ProviderApiDestination): String? = when (destination) {
        ProviderApiDestination.Catalog -> "catalog"
        is ProviderApiDestination.Operation -> "operation:${destination.operationId}"
        ProviderApiDestination.Explorer -> "explorer"
        // Picked files are never persisted, so a restored composer would be empty: drop it.
        ProviderApiDestination.Multipart -> null
    }

    private fun decodeDestination(token: String): ProviderApiDestination? = when {
        token == "catalog" -> ProviderApiDestination.Catalog
        token == "explorer" -> ProviderApiDestination.Explorer
        token.startsWith("operation:") -> ProviderApiDestination.Operation(token.removePrefix("operation:"))
        else -> null
    }

    private fun key(name: String) = "$keyPrefix.$name"

    // endregion

    private fun clearBinaryState() {
        attachmentBytes?.fill(0)
        attachmentBytes = null
        multipartFiles.values.forEach { it.fill(0) }
        multipartFiles.clear()
    }

    private fun safeMessage(error: Throwable?, fallback: String): String =
        error?.message?.takeIf { it.isNotBlank() && it.length <= 2_000 } ?: fallback

    override fun toString(): String = "ProviderApiWorkspaceController(profile=$profile)"

    companion object {
        private const val OPEN_KEY = "open"
        private const val STACK_KEY = "stack"
        private const val EXPLORER_KEY = "explorer"
        private const val OPERATION_KEY = "operation"
        private const val QUERY_KEY = "query"
        private const val TAG_KEY = "tag"
        private const val ACCESS_KEY = "access"
        private const val MANUAL_PATH_KEY = "manualPath"
        private const val MAX_SAVED_DRAFT_CHARACTERS = 32 * 1_024
        private const val INLINE_FORMAT_CHARACTERS = 32 * 1_024
        private const val CATALOG_FAILURE = "The complete provider API catalog could not be loaded."
        private const val SEND_FAILURE = "The request could not be completed."

        fun isBinaryContentType(contentType: String): Boolean {
            val value = contentType.lowercase(Locale.ROOT)
            return "multipart/form-data" in value || "application/octet-stream" in value
        }
    }
}
