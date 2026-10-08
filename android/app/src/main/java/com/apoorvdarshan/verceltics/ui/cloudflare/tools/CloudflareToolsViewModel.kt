package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountDetail
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountOperationsSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareApiPreset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareBodyEncoding
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerRequestBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLDataset
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartBody
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartFieldSpec
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalog
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOperationRequestBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareRawResponse
import java.util.Base64
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One screen in the Cloudflare tools stack. Encoded into saved state as plain strings. */
sealed interface CloudflareToolRoute {
    val accountId: String?

    data class Account(override val accountId: String) : CloudflareToolRoute
    data class AccountOperations(override val accountId: String) : CloudflareToolRoute
    data class Catalog(override val accountId: String) : CloudflareToolRoute
    data class CatalogTag(override val accountId: String, val tag: String) : CloudflareToolRoute
    data class CatalogOperation(override val accountId: String, val operationId: String) : CloudflareToolRoute
    data class GraphQL(override val accountId: String) : CloudflareToolRoute
    data class GraphQLDataset(override val accountId: String, val datasetName: String) : CloudflareToolRoute
    data class ProductCenter(override val accountId: String) : CloudflareToolRoute
    data class Explorer(override val accountId: String?) : CloudflareToolRoute

    companion object {
        private const val SEPARATOR = '\u001F'

        fun encode(route: CloudflareToolRoute): String = when (route) {
            is Account -> join("account", route.accountId)
            is AccountOperations -> join("accountOperations", route.accountId)
            is Catalog -> join("catalog", route.accountId)
            is CatalogTag -> join("catalogTag", route.accountId, route.tag)
            is CatalogOperation -> join("catalogOperation", route.accountId, route.operationId)
            is GraphQL -> join("graphql", route.accountId)
            is GraphQLDataset -> join("graphqlDataset", route.accountId, route.datasetName)
            is ProductCenter -> join("productCenter", route.accountId)
            is Explorer -> join("explorer", route.accountId.orEmpty())
        }

        fun decode(value: String): CloudflareToolRoute? {
            val parts = value.split(SEPARATOR)
            val kind = parts.getOrNull(0) ?: return null
            val first = parts.getOrNull(1)
            val second = parts.getOrNull(2)
            return when (kind) {
                "account" -> first?.takeIf(String::isNotEmpty)?.let(::Account)
                "accountOperations" -> first?.takeIf(String::isNotEmpty)?.let(::AccountOperations)
                "catalog" -> first?.takeIf(String::isNotEmpty)?.let(::Catalog)
                "catalogTag" -> if (!first.isNullOrEmpty() && second != null) CatalogTag(first, second) else null
                "catalogOperation" -> if (!first.isNullOrEmpty() && !second.isNullOrEmpty()) CatalogOperation(first, second) else null
                "graphql" -> first?.takeIf(String::isNotEmpty)?.let(::GraphQL)
                "graphqlDataset" -> if (!first.isNullOrEmpty() && !second.isNullOrEmpty()) GraphQLDataset(first, second) else null
                "productCenter" -> first?.takeIf(String::isNotEmpty)?.let(::ProductCenter)
                "explorer" -> Explorer(first?.takeIf(String::isNotEmpty))
                else -> null
            }
        }

        private fun join(vararg parts: String): String = parts.joinToString(SEPARATOR.toString())
    }
}

sealed interface CloudflareToolLoad<out T> {
    data object Idle : CloudflareToolLoad<Nothing>
    data object Loading : CloudflareToolLoad<Nothing>
    data class Loaded<T>(val value: T) : CloudflareToolLoad<T>
    data class Failed(val message: String) : CloudflareToolLoad<Nothing>
}

/** Dashboard context the tools need: the selected account and its zones. */
data class CloudflareToolsContext(
    val accountId: String,
    val accountName: String,
    val accountType: String?,
    val zones: List<CloudflareToolsZone>,
    val zoneCount: Int,
    val pagesCount: Int,
    val workerCount: Int,
    val credentialLabel: String = "Scoped API token",
    val authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN,
) {
    val firstZoneId: String? get() = zones.firstOrNull()?.id
}

data class CloudflareToolsZone(val id: String, val name: String)

data class CloudflareExplorerAttachmentUi(val label: String, val sizeBytes: Int)

data class CloudflareExplorerUiState(
    val title: String? = null,
    val summary: String? = null,
    val draft: CloudflareExplorerDraft = CloudflareExplorerDraft(),
    val multipartFields: List<CloudflareMultipartFieldSpec> = emptyList(),
    val permissions: List<String> = emptyList(),
    val attachment: CloudflareExplorerAttachmentUi? = null,
    val isExecuting: Boolean = false,
    val response: CloudflareRawResponse? = null,
    val error: String? = null,
    val notice: String? = null,
    val showConfirmation: Boolean = false,
) {
    val canExecute: Boolean get() = !isExecuting && draft.path.isNotBlank()

    val canClear: Boolean get() = response != null || error != null
}

data class CloudflareOperationEditorUi(
    val operationId: String,
    val values: Map<String, String>,
    val body: String,
    val contentType: String,
)

data class CloudflareDatasetsUiState(
    val accountId: String? = null,
    val scope: CloudflareGraphQLScope = CloudflareGraphQLScope.ZONE,
    val zoneId: String? = null,
    val datasets: List<CloudflareGraphQLDataset> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class CloudflareAccountOperationsUiState(
    val accountId: String? = null,
    val snapshot: CloudflareAccountOperationsSnapshot? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class CloudflareToolsUiState(
    val stack: List<CloudflareToolRoute> = emptyList(),
    val explorer: CloudflareExplorerUiState = CloudflareExplorerUiState(),
    val catalog: CloudflareToolLoad<CloudflareOpenApiCatalog> = CloudflareToolLoad.Idle,
    val operationEditor: CloudflareOperationEditorUi? = null,
    val datasets: CloudflareDatasetsUiState = CloudflareDatasetsUiState(),
    val accountDetailId: String? = null,
    val accountDetail: CloudflareToolLoad<CloudflareAccountDetail> = CloudflareToolLoad.Idle,
    val accountOperations: CloudflareAccountOperationsUiState = CloudflareAccountOperationsUiState(),
    val productZoneId: String? = null,
    val isOfflineSample: Boolean = false,
) {
    val route: CloudflareToolRoute? get() = stack.lastOrNull()

    val isOpen: Boolean get() = stack.isNotEmpty()
}

class CloudflareToolsViewModel(
    private val gateway: CloudflareToolsGateway,
    private val savedStateHandle: SavedStateHandle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _uiState = MutableStateFlow(restoredState())
    val uiState: StateFlow<CloudflareToolsUiState> = _uiState.asStateFlow()

    private var attachedBody: ByteArray? = null
    private var explorerJob: Job? = null
    private var explorerGeneration = 0L
    private var catalogJob: Job? = null
    private var datasetsJob: Job? = null
    private var datasetsGeneration = 0L
    private val datasetCache = HashMap<String, Pair<List<CloudflareGraphQLDataset>, Long>>()
    private var accountDetailJob: Job? = null
    private var accountOperationsJob: Job? = null
    private var accountOperationsLoadedAt = 0L

    // region Navigation

    /** Opens a root tool from the dashboard, replacing any open tool stack. */
    fun open(route: CloudflareToolRoute) {
        if (route is CloudflareToolRoute.Explorer) {
            closeAll()
            openExplorer(route.accountId, null)
            return
        }
        setStack(listOf(route))
    }

    fun push(route: CloudflareToolRoute) {
        if (route is CloudflareToolRoute.Explorer) {
            openExplorer(route.accountId, null)
            return
        }
        setStack(_uiState.value.stack + route)
    }

    /** Pushes the explorer prefilled from [preset] (iOS `CloudflareAPIExplorerView(preset:)`). */
    fun openExplorer(accountId: String?, preset: CloudflareApiPreset?) {
        cancelExplorerRequest()
        attachedBody = null
        val explorer = if (preset == null) {
            CloudflareExplorerUiState()
        } else {
            CloudflareExplorerUiState(
                title = preset.title,
                summary = preset.summary,
                draft = CloudflareExplorerDraft.from(preset),
                multipartFields = preset.multipartFields,
                permissions = preset.permissions,
            )
        }
        _uiState.update { it.copy(explorer = explorer) }
        persistExplorer(explorer)
        setStack(_uiState.value.stack + CloudflareToolRoute.Explorer(accountId))
    }

    /** Returns false when no tool screen was open. */
    fun pop(): Boolean {
        val current = _uiState.value
        if (current.explorer.showConfirmation) {
            dismissConfirmation()
            return true
        }
        val top = current.stack.lastOrNull() ?: return false
        if (top is CloudflareToolRoute.Explorer) {
            cancelExplorerRequest()
            attachedBody = null
        }
        setStack(current.stack.dropLast(1))
        return true
    }

    fun closeAll() {
        if (_uiState.value.stack.isEmpty()) return
        cancelExplorerRequest()
        attachedBody = null
        _uiState.update { it.copy(explorer = it.explorer.copy(showConfirmation = false, isExecuting = false)) }
        setStack(emptyList())
    }

    /** Closes tools that belong to another account or a disconnected dashboard. */
    fun reconcile(selectedAccountId: String?, connected: Boolean) {
        val stack = _uiState.value.stack
        if (stack.isEmpty()) return
        if (!connected || selectedAccountId == null ||
            stack.any { it.accountId != null && it.accountId != selectedAccountId }
        ) {
            closeAll()
        }
    }

    /** Idempotent loads for the visible route; also restores data after process death. */
    fun ensureLoaded(route: CloudflareToolRoute, context: CloudflareToolsContext) {
        when (route) {
            is CloudflareToolRoute.Account -> if (_uiState.value.accountDetailId != route.accountId ||
                _uiState.value.accountDetail == CloudflareToolLoad.Idle
            ) {
                loadAccountDetail(route.accountId)
            }
            is CloudflareToolRoute.AccountOperations -> {
                val operations = _uiState.value.accountOperations
                val fresh = operations.accountId == route.accountId && operations.snapshot != null &&
                    nowMillis() - accountOperationsLoadedAt < CACHE_LIFETIME_MILLIS
                if (!fresh && !(operations.isLoading && operations.accountId == route.accountId)) {
                    loadAccountOperations(route.accountId, forceRefresh = false)
                }
            }
            is CloudflareToolRoute.Catalog, is CloudflareToolRoute.CatalogTag -> ensureCatalog()
            is CloudflareToolRoute.CatalogOperation -> {
                ensureCatalog()
                val catalog = (_uiState.value.catalog as? CloudflareToolLoad.Loaded)?.value
                if (catalog != null && _uiState.value.operationEditor?.operationId != route.operationId) {
                    catalog.operation(route.operationId)?.let { operation ->
                        _uiState.update {
                            it.copy(
                                operationEditor = CloudflareOperationEditorUi(
                                    operationId = operation.id,
                                    values = CloudflareOperationRequestBuilder.initialValues(
                                        operation,
                                        context.accountId,
                                        context.firstZoneId,
                                    ),
                                    body = operation.bodyTemplate,
                                    contentType = CloudflareOperationRequestBuilder.initialContentType(operation),
                                ),
                            )
                        }
                    }
                }
            }
            is CloudflareToolRoute.GraphQL, is CloudflareToolRoute.GraphQLDataset -> ensureDatasets(context)
            is CloudflareToolRoute.ProductCenter -> {
                val zoneId = _uiState.value.productZoneId
                if (zoneId == null || context.zones.none { it.id == zoneId }) selectProductZone(context.firstZoneId)
            }
            is CloudflareToolRoute.Explorer -> Unit
        }
    }

    private fun setStack(stack: List<CloudflareToolRoute>) {
        _uiState.update { it.copy(stack = stack) }
        savedStateHandle[KEY_STACK] = ArrayList(stack.map(CloudflareToolRoute::encode))
    }

    // endregion

    // region Explorer

    fun updateDraft(transform: (CloudflareExplorerDraft) -> CloudflareExplorerDraft) {
        val explorer = _uiState.value.explorer
        val draft = transform(explorer.draft)
        if (draft == explorer.draft) return
        val updated = explorer.copy(draft = draft)
        _uiState.update { it.copy(explorer = updated) }
        persistExplorer(updated)
    }

    /** iOS method picker: switching method clears the previous response. */
    fun selectMethod(method: CloudflareHttpMethod) {
        updateDraft { it.copy(method = method) }
        clearResponse()
    }

    /** iOS quick paths: `path?query` splits into the path and newline-separated query. */
    fun applyQuickPath(path: String) {
        val separator = path.indexOf('?')
        updateDraft {
            if (separator >= 0) {
                it.copy(
                    method = CloudflareHttpMethod.GET,
                    path = path.substring(0, separator),
                    queryText = path.substring(separator + 1).replace("&", "\n"),
                )
            } else {
                it.copy(method = CloudflareHttpMethod.GET, path = path, queryText = "")
            }
        }
        clearResponse()
    }

    fun requestExecute() {
        val explorer = _uiState.value.explorer
        if (!explorer.canExecute) return
        if (CloudflareExplorerRequestBuilder.requiresWriteConfirmation(explorer.draft, attachedBody)) {
            _uiState.update { it.copy(explorer = it.explorer.copy(showConfirmation = true)) }
        } else {
            execute(confirmed = false)
        }
    }

    fun confirmExecute() {
        if (!_uiState.value.explorer.showConfirmation) return
        _uiState.update { it.copy(explorer = it.explorer.copy(showConfirmation = false)) }
        execute(confirmed = true)
    }

    fun dismissConfirmation() {
        _uiState.update { it.copy(explorer = it.explorer.copy(showConfirmation = false)) }
    }

    fun cancelExecution() {
        if (!_uiState.value.explorer.isExecuting) return
        cancelExplorerRequest()
        _uiState.update {
            it.copy(explorer = it.explorer.copy(isExecuting = false, notice = "Request cancelled."))
        }
    }

    fun clearResponse() {
        _uiState.update {
            it.copy(explorer = it.explorer.copy(response = null, error = null, notice = null))
        }
    }

    fun reportExplorerError(message: String) {
        _uiState.update { it.copy(explorer = it.explorer.copy(error = message)) }
    }

    /** iOS "Import raw body file as Base64". Large files stay attached instead of inline text. */
    fun importBody(bytes: ByteArray, label: String) {
        if (bytes.size > CloudflareExplorerRequestBuilder.MAXIMUM_BODY_BYTES) {
            reportExplorerError("Could not import the request body: Request body files must be 25 MB or smaller.")
            return
        }
        applyBinaryBody(bytes, label, contentType = null)
    }

    /** Applies the multipart composer's output (iOS sets the body, content type and Base64). */
    fun applyMultipartBody(body: CloudflareMultipartBody) {
        applyBinaryBody(body.bytes(), "Multipart form body", body.contentType)
    }

    fun removeAttachment() {
        attachedBody = null
        _uiState.update { it.copy(explorer = it.explorer.copy(attachment = null)) }
    }

    private fun applyBinaryBody(bytes: ByteArray, label: String, contentType: String?) {
        val inline = bytes.size <= INLINE_BASE64_LIMIT_BYTES
        attachedBody = if (inline) null else bytes.copyOf()
        val encoded = if (inline) Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) else ""
        updateDraft { draft ->
            draft.copy(
                bodyText = encoded,
                bodyEncoding = CloudflareBodyEncoding.BASE64,
                contentType = contentType ?: if (draft.contentType == "application/json") {
                    "application/octet-stream"
                } else {
                    draft.contentType
                },
            )
        }
        _uiState.update {
            it.copy(
                explorer = it.explorer.copy(
                    attachment = if (inline) null else CloudflareExplorerAttachmentUi(label, bytes.size),
                    response = null,
                    error = null,
                    notice = null,
                ),
            )
        }
    }

    private fun execute(confirmed: Boolean) {
        val explorer = _uiState.value.explorer
        val draft = explorer.draft
        val confirmation = if (draft.method.isMutation && confirmed) CloudflareMutationConfirmation(draft.path) else null
        val body = attachedBody?.copyOf()
        cancelExplorerRequest()
        val generation = ++explorerGeneration
        _uiState.update {
            it.copy(explorer = it.explorer.copy(isExecuting = true, response = null, error = null, notice = null))
        }
        explorerJob = viewModelScope.launch {
            val result = gateway.execute(draft, confirmation, body)
            if (generation != explorerGeneration) return@launch
            _uiState.update { state ->
                state.copy(
                    explorer = state.explorer.copy(
                        isExecuting = false,
                        response = result.getOrNull(),
                        error = result.exceptionOrNull()?.let(::safeMessage),
                    ),
                )
            }
        }
    }

    private fun cancelExplorerRequest() {
        explorerGeneration += 1
        explorerJob?.cancel()
        explorerJob = null
        if (_uiState.value.explorer.isExecuting) {
            _uiState.update { it.copy(explorer = it.explorer.copy(isExecuting = false)) }
        }
    }

    private fun persistExplorer(explorer: CloudflareExplorerUiState) {
        val draft = explorer.draft
        savedStateHandle[KEY_EXPLORER_TITLE] = explorer.title
        savedStateHandle[KEY_EXPLORER_SUMMARY] = explorer.summary
        savedStateHandle[KEY_EXPLORER_PERMISSIONS] = ArrayList(explorer.permissions)
        savedStateHandle[KEY_EXPLORER_METHOD] = draft.method.name
        savedStateHandle[KEY_EXPLORER_PATH] = draft.path.take(MAXIMUM_SAVED_FIELD_CHARACTERS)
        savedStateHandle[KEY_EXPLORER_QUERY] = draft.queryText.take(MAXIMUM_SAVED_FIELD_CHARACTERS)
        savedStateHandle[KEY_EXPLORER_HEADERS] = draft.headerText.take(MAXIMUM_SAVED_FIELD_CHARACTERS)
        // Large or binary bodies are not written to the saved-state bundle.
        savedStateHandle[KEY_EXPLORER_BODY] = draft.bodyText.takeIf { it.length <= MAXIMUM_SAVED_BODY_CHARACTERS }.orEmpty()
        savedStateHandle[KEY_EXPLORER_CONTENT_TYPE] = draft.contentType.take(MAXIMUM_SAVED_FIELD_CHARACTERS)
        savedStateHandle[KEY_EXPLORER_ENCODING] = draft.bodyEncoding.name
        savedStateHandle[KEY_EXPLORER_READ_ONLY_GRAPHQL] = draft.readOnlyGraphQL
    }

    // endregion

    // region Catalog

    fun ensureCatalog() {
        val catalog = _uiState.value.catalog
        if (catalog is CloudflareToolLoad.Loaded || catalog == CloudflareToolLoad.Loading) return
        if (catalog is CloudflareToolLoad.Failed) return
        reloadCatalog()
    }

    fun reloadCatalog() {
        catalogJob?.cancel()
        _uiState.update { it.copy(catalog = CloudflareToolLoad.Loading) }
        catalogJob = viewModelScope.launch {
            val result = gateway.loadCatalog()
            _uiState.update {
                it.copy(
                    catalog = result.fold(
                        onSuccess = { catalog -> CloudflareToolLoad.Loaded(catalog) },
                        onFailure = { error -> CloudflareToolLoad.Failed(safeMessage(error)) },
                    ),
                )
            }
        }
    }

    fun updateOperationValue(key: String, value: String) {
        _uiState.update { state ->
            val editor = state.operationEditor ?: return@update state
            state.copy(operationEditor = editor.copy(values = editor.values + (key to value)))
        }
    }

    fun updateOperationBody(body: String) {
        _uiState.update { state ->
            state.copy(operationEditor = state.operationEditor?.copy(body = body))
        }
    }

    fun updateOperationContentType(contentType: String) {
        _uiState.update { state ->
            state.copy(operationEditor = state.operationEditor?.copy(contentType = contentType))
        }
    }

    /** iOS "Review and execute request": opens the explorer with the generated request. */
    fun reviewOperation(accountId: String, authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN) {
        val state = _uiState.value
        val catalog = (state.catalog as? CloudflareToolLoad.Loaded)?.value ?: return
        val editor = state.operationEditor ?: return
        val operation = catalog.operation(editor.operationId) ?: return
        if (!operation.supports(authMode)) return
        openExplorer(
            accountId,
            CloudflareOperationRequestBuilder.preset(operation, editor.values, editor.body, editor.contentType),
        )
    }

    // endregion

    // region GraphQL datasets

    private fun ensureDatasets(context: CloudflareToolsContext) {
        val datasets = _uiState.value.datasets
        if (datasets.accountId != context.accountId) {
            val savedScope = savedStateHandle.get<String>(KEY_DATASET_SCOPE)
                ?.let { runCatching { CloudflareGraphQLScope.valueOf(it) }.getOrNull() }
            val savedZone = savedStateHandle.get<String>(KEY_DATASET_ZONE)?.takeIf { id -> context.zones.any { it.id == id } }
            val scope = when {
                context.zones.isEmpty() -> CloudflareGraphQLScope.ACCOUNT
                savedScope != null -> savedScope
                else -> CloudflareGraphQLScope.ZONE
            }
            _uiState.update {
                it.copy(
                    datasets = CloudflareDatasetsUiState(
                        accountId = context.accountId,
                        scope = scope,
                        zoneId = savedZone ?: context.firstZoneId,
                    ),
                )
            }
            loadDatasets(forceRefresh = false)
        } else if (datasets.datasets.isEmpty() && !datasets.isLoading && datasets.error == null) {
            loadDatasets(forceRefresh = false)
        }
    }

    fun selectDatasetScope(scope: CloudflareGraphQLScope) {
        val datasets = _uiState.value.datasets
        if (scope == CloudflareGraphQLScope.ZONE && datasets.zoneId == null) return
        if (datasets.scope == scope) return
        _uiState.update { it.copy(datasets = it.datasets.copy(scope = scope, datasets = emptyList(), error = null)) }
        savedStateHandle[KEY_DATASET_SCOPE] = scope.name
        loadDatasets(forceRefresh = false)
    }

    fun selectDatasetZone(zoneId: String) {
        if (_uiState.value.datasets.zoneId == zoneId) return
        _uiState.update { it.copy(datasets = it.datasets.copy(zoneId = zoneId, datasets = emptyList(), error = null)) }
        savedStateHandle[KEY_DATASET_ZONE] = zoneId
        loadDatasets(forceRefresh = false)
    }

    /** iOS keeps datasets for five minutes per account/scope/zone; refresh bypasses the cache. */
    fun loadDatasets(forceRefresh: Boolean) {
        val datasets = _uiState.value.datasets
        val accountId = datasets.accountId ?: return
        val key = "$accountId|${datasets.scope}|${if (datasets.scope == CloudflareGraphQLScope.ZONE) datasets.zoneId else accountId}"
        datasetCache[key]?.let { (cached, loadedAt) ->
            _uiState.update { it.copy(datasets = it.datasets.copy(datasets = cached, isLoading = false, error = null)) }
            if (!forceRefresh && nowMillis() - loadedAt < CACHE_LIFETIME_MILLIS) return
        }
        datasetsJob?.cancel()
        val generation = ++datasetsGeneration
        val hasCache = datasetCache.containsKey(key)
        _uiState.update { it.copy(datasets = it.datasets.copy(isLoading = !hasCache, error = null)) }
        datasetsJob = viewModelScope.launch {
            val result = gateway.loadDatasets(datasets.scope, accountId, datasets.zoneId)
            if (generation != datasetsGeneration) return@launch
            result.fold(
                onSuccess = { loaded ->
                    datasetCache[key] = loaded to nowMillis()
                    _uiState.update { it.copy(datasets = it.datasets.copy(datasets = loaded, isLoading = false, error = null)) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(datasets = it.datasets.copy(isLoading = false, error = safeMessage(error))) }
                },
            )
        }
    }

    // endregion

    // region Product center

    fun selectProductZone(zoneId: String?) {
        _uiState.update { it.copy(productZoneId = zoneId) }
        savedStateHandle[KEY_PRODUCT_ZONE] = zoneId
    }

    // endregion

    // region Account

    fun loadAccountDetail(accountId: String) {
        accountDetailJob?.cancel()
        _uiState.update { it.copy(accountDetailId = accountId, accountDetail = CloudflareToolLoad.Loading) }
        accountDetailJob = viewModelScope.launch {
            val result = gateway.loadAccountDetail(accountId)
            if (_uiState.value.accountDetailId != accountId) return@launch
            _uiState.update {
                it.copy(
                    accountDetail = result.fold(
                        onSuccess = { detail -> CloudflareToolLoad.Loaded(detail) },
                        onFailure = { error -> CloudflareToolLoad.Failed(safeMessage(error)) },
                    ),
                )
            }
        }
    }

    fun loadAccountOperations(accountId: String, forceRefresh: Boolean) {
        val current = _uiState.value.accountOperations
        if (!forceRefresh && current.isLoading && current.accountId == accountId) return
        accountOperationsJob?.cancel()
        val sameAccount = current.accountId == accountId
        _uiState.update {
            it.copy(
                accountOperations = CloudflareAccountOperationsUiState(
                    accountId = accountId,
                    snapshot = if (sameAccount) current.snapshot else null,
                    isLoading = true,
                ),
            )
        }
        accountOperationsJob = viewModelScope.launch {
            val result = gateway.loadAccountOperations(accountId)
            if (_uiState.value.accountOperations.accountId != accountId) return@launch
            result.fold(
                onSuccess = { snapshot ->
                    accountOperationsLoadedAt = if (snapshot.allSucceeded) nowMillis() else 0L
                    _uiState.update {
                        it.copy(accountOperations = it.accountOperations.copy(snapshot = snapshot, isLoading = false, error = null))
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(accountOperations = it.accountOperations.copy(isLoading = false, error = safeMessage(error)))
                    }
                },
            )
        }
    }

    // endregion

    private fun restoredState(): CloudflareToolsUiState {
        val stack = savedStateHandle.get<ArrayList<String>>(KEY_STACK).orEmpty()
            .mapNotNull(CloudflareToolRoute::decode)
        val explorer = if (stack.any { it is CloudflareToolRoute.Explorer }) {
            CloudflareExplorerUiState(
                title = savedStateHandle[KEY_EXPLORER_TITLE],
                summary = savedStateHandle[KEY_EXPLORER_SUMMARY],
                permissions = savedStateHandle.get<ArrayList<String>>(KEY_EXPLORER_PERMISSIONS).orEmpty(),
                draft = CloudflareExplorerDraft(
                    method = savedStateHandle.get<String>(KEY_EXPLORER_METHOD)
                        ?.let(CloudflareHttpMethod::parse) ?: CloudflareHttpMethod.GET,
                    path = savedStateHandle[KEY_EXPLORER_PATH] ?: "/accounts",
                    queryText = savedStateHandle[KEY_EXPLORER_QUERY] ?: "",
                    headerText = savedStateHandle[KEY_EXPLORER_HEADERS] ?: "",
                    bodyText = savedStateHandle[KEY_EXPLORER_BODY] ?: "",
                    contentType = savedStateHandle[KEY_EXPLORER_CONTENT_TYPE] ?: "application/json",
                    bodyEncoding = savedStateHandle.get<String>(KEY_EXPLORER_ENCODING)
                        ?.let { runCatching { CloudflareBodyEncoding.valueOf(it) }.getOrNull() }
                        ?: CloudflareBodyEncoding.UTF8,
                    readOnlyGraphQL = savedStateHandle[KEY_EXPLORER_READ_ONLY_GRAPHQL] ?: false,
                ),
            )
        } else {
            CloudflareExplorerUiState()
        }
        return CloudflareToolsUiState(
            stack = stack,
            explorer = explorer,
            productZoneId = savedStateHandle[KEY_PRODUCT_ZONE],
            isOfflineSample = gateway.isOfflineSample,
        )
    }

    private fun safeMessage(error: Throwable): String =
        (error as? CloudflareToolsUiException)?.message ?: "Cloudflare could not complete this request."

    override fun onCleared() {
        explorerJob?.cancel()
        super.onCleared()
    }

    class Factory(private val gateway: CloudflareToolsGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(CloudflareToolsViewModel::class.java)) {
                "Unsupported Cloudflare tools ViewModel class."
            }
            return CloudflareToolsViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        const val CACHE_LIFETIME_MILLIS = 5 * 60 * 1_000L
        /** Imported/composed bodies up to this size are shown as editable Base64, like iOS. */
        const val INLINE_BASE64_LIMIT_BYTES = 192 * 1_024
        private const val MAXIMUM_SAVED_FIELD_CHARACTERS = 8_192
        private const val MAXIMUM_SAVED_BODY_CHARACTERS = 64 * 1_024
        internal const val KEY_STACK = "cloudflare.tools.stack"
        private const val KEY_EXPLORER_TITLE = "cloudflare.tools.explorer.title"
        private const val KEY_EXPLORER_SUMMARY = "cloudflare.tools.explorer.summary"
        private const val KEY_EXPLORER_PERMISSIONS = "cloudflare.tools.explorer.permissions"
        private const val KEY_EXPLORER_METHOD = "cloudflare.tools.explorer.method"
        private const val KEY_EXPLORER_PATH = "cloudflare.tools.explorer.path"
        private const val KEY_EXPLORER_QUERY = "cloudflare.tools.explorer.query"
        private const val KEY_EXPLORER_HEADERS = "cloudflare.tools.explorer.headers"
        private const val KEY_EXPLORER_BODY = "cloudflare.tools.explorer.body"
        private const val KEY_EXPLORER_CONTENT_TYPE = "cloudflare.tools.explorer.contentType"
        private const val KEY_EXPLORER_ENCODING = "cloudflare.tools.explorer.encoding"
        private const val KEY_EXPLORER_READ_ONLY_GRAPHQL = "cloudflare.tools.explorer.readOnlyGraphQL"
        private const val KEY_DATASET_SCOPE = "cloudflare.tools.datasets.scope"
        private const val KEY_DATASET_ZONE = "cloudflare.tools.datasets.zone"
        private const val KEY_PRODUCT_ZONE = "cloudflare.tools.product.zone"
    }
}
