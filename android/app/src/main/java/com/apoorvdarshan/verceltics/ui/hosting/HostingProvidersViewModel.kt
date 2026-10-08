package com.apoorvdarshan.verceltics.ui.hosting

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.data.hosting.HostingApiDefaults
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingLinkContext
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingResource
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiBackend
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiProfile
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiWorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HostingConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class HostingOperation {
    RESTORING,
    CONNECTING,
    REFRESHING,
    DISCONNECTING,
}

/** A one-shot request for the app shell to run Google sign-in with [scopes]. */
data class HostingGoogleSignInRequest(val id: Long, val scopes: Set<String>)

data class HostingProviderUiState(
    val providerId: String,
    val status: HostingConnectionStatus = HostingConnectionStatus.RESTORING,
    val dashboard: HostingDashboardUi? = null,
    val savedAccount: HostingAccountUi? = null,
    val savedDashboardUrl: String? = null,
    val operation: HostingOperation? = HostingOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val showDisconnectConfirmation: Boolean = false,
    val selectedResourceId: String? = null,
    val resourceWorkspace: HostingResourceWorkspaceUi? = null,
    val isLoadingResource: Boolean = false,
    val resourceError: String? = null,
    val showActionConfirmation: Boolean = false,
    val isPerformingAction: Boolean = false,
    val actionMessage: String? = null,
    val actionError: String? = null,
    /** Set when Firebase needs Google sign-in; the route forwards it to the shell exactly once. */
    val googleSignInRequest: HostingGoogleSignInRequest? = null,
    /** True after sign-in was requested, until the pending Firebase request is retried. */
    val awaitingGoogleSignIn: Boolean = false,
    /** True when the last Firebase failure can only be fixed by signing in with Google again. */
    val googleSignInRequired: Boolean = false,
) {
    val isBusy: Boolean
        get() = operation != null

    val isConnected: Boolean
        get() = status == HostingConnectionStatus.CONNECTED || status == HostingConnectionStatus.SAVED_UNAVAILABLE

    val selectedResource: HostingResourceUi?
        get() = selectedResourceId?.let { id -> dashboard?.resources?.firstOrNull { it.id == id } }

    val dashboardUrl: String?
        get() = dashboard?.dashboardUrl ?: savedDashboardUrl
}

data class HostingProvidersUiState(
    val providers: Map<String, HostingProviderUiState> =
        HostingProvider.entries.associate { it.id to HostingProviderUiState(it.id) },
    /** The provider whose route is on screen, if any. */
    val visibleProviderId: String? = null,
) {
    fun provider(providerId: String): HostingProviderUiState = providers[providerId]
        ?: HostingProviderUiState(providerId, status = HostingConnectionStatus.DISCONNECTED, operation = null)
}

/** Connected (or saved-but-unavailable) providers, in catalog order. */
val HostingProvidersUiState.connectedProviderIds: Set<String>
    get() = HostingProvider.entries.mapNotNull { provider ->
        provider.id.takeIf { providers[it]?.isConnected == true }
    }.toCollection(linkedSetOf())

/** FLAG_SECURE is needed only while a credential form (or its in-flight submission) is visible. */
val HostingProvidersUiState.requiresSecureWindow: Boolean
    get() {
        val id = visibleProviderId ?: return false
        // Firebase uses Google sign-in; its form holds only a non-secret project ID.
        if (id == HostingProvider.FIREBASE.id) return false
        val provider = providers[id] ?: return false
        return provider.status == HostingConnectionStatus.DISCONNECTED ||
            provider.operation == HostingOperation.CONNECTING
    }

/**
 * Activity-scoped owner for every generic hosting provider. Operations are tracked per provider,
 * so one provider can refresh while another connects; the selected resource survives recreation.
 */
class HostingProvidersViewModel(
    private val gateway: HostingProviderUiGateway,
    private val savedStateHandle: SavedStateHandle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val actionRefreshDelayMillis: Long = ACTION_REFRESH_DELAY_MILLIS,
    /** iOS 180 s deployment-history cache, scoped to provider, account and resource. */
    private val historyCache: ResourceHistoryCache<HostingResourceWorkspaceUi> = ResourceHistoryCache(nowMillis),
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialState(visibleProviderId = null))
    val uiState: StateFlow<HostingProvidersUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private val operationJobs = mutableMapOf<String, Job>()
    private val operationGenerations = mutableMapOf<String, Long>()
    private val operationBaselines = mutableMapOf<String, HostingProviderUiState>()
    private val resourceJobs = mutableMapOf<String, Job>()
    private val resourceGenerations = mutableMapOf<String, Long>()
    private val actionJobs = mutableMapOf<String, Job>()
    private var signInRequestCounter = 0L
    private var pendingGoogleRetry: GoogleRetry? = null
    private var isForeground = false
    private val apiWorkspaces = mutableMapOf<String, ProviderApiWorkspaceController>()

    private sealed interface GoogleRetry {
        data class Connect(val projectId: String) : GoogleRetry

        data object Refresh : GoogleRetry
    }

    init {
        restore()
    }

    // region Lifecycle

    /** Offline restore of every provider slot (also used when entering sample mode). */
    fun restore() {
        restoreJob?.cancel()
        HostingProvider.ids.forEach(::cancelProviderWork)
        pendingGoogleRetry = null
        val generation = ++restoreGeneration
        _uiState.update { initialState(it.visibleProviderId) }
        restoreJob = viewModelScope.launch {
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (generation != restoreGeneration) return@fold
                    HostingProvider.ids.forEach { id -> applyRestore(id, restored[id] ?: HostingRestoreUi.NotConnected) }
                },
                onFailure = { error ->
                    if (generation != restoreGeneration) return@fold
                    HostingProvider.ids.forEach { id ->
                        replaceProvider(
                            HostingProviderUiState(
                                providerId = id,
                                status = HostingConnectionStatus.DISCONNECTED,
                                operation = null,
                                error = safeMessage(id, error),
                            ),
                        )
                    }
                },
            )
            if (generation == restoreGeneration) {
                restoreJob = null
                refreshStaleInventories()
            }
        }
    }

    fun onForeground() {
        isForeground = true
        refreshStaleInventories()
        retryAfterGoogleSignIn()
    }

    fun onBackground() {
        isForeground = false
    }

    /** Lets the app shell report a finished Google sign-in without waiting for a resume. */
    fun onGoogleSignInCompleted() = retryAfterGoogleSignIn()

    /** The route reports which provider is on screen; stale inventories refresh when opened. */
    fun setRouteVisible(providerId: String, visible: Boolean) {
        _uiState.update { state ->
            val next = when {
                visible -> providerId
                state.visibleProviderId == providerId -> null
                else -> state.visibleProviderId
            }
            if (next == state.visibleProviderId) state else state.copy(visibleProviderId = next)
        }
        if (visible) refreshIfStale(providerId)
    }

    // endregion

    // region Connection

    fun connect(credentials: HostingCredentials) = connect(credentials, interactive = true)

    /** Asks the shell to run Google sign-in for Firebase, then retries [retry] afterwards. */
    fun requestGoogleSignIn(providerId: String) {
        if (providerId != HostingProvider.FIREBASE.id) return
        val state = provider(providerId)
        if (state.isBusy) return
        pendingGoogleRetry = if (state.isConnected) GoogleRetry.Refresh else pendingGoogleRetry
        if (pendingGoogleRetry == null) return
        emitGoogleSignInRequest(providerId)
    }

    fun onGoogleSignInRequestHandled(providerId: String, requestId: Long) {
        updateProvider(providerId) { state ->
            if (state.googleSignInRequest?.id == requestId) state.copy(googleSignInRequest = null) else state
        }
    }

    fun refresh(providerId: String) {
        val baseline = provider(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        launchOperation(providerId, HostingOperation.REFRESHING, baseline.cleared()) { generation ->
            gateway.refresh(providerId).fold(
                onSuccess = { dashboard -> if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard) },
                onFailure = { error ->
                    if (!isCurrent(providerId, generation)) return@fold
                    // Keep any resource the user opened meanwhile; only the inventory call failed.
                    updateProvider(providerId) {
                        it.copy(
                            operation = null,
                            error = safeMessage(providerId, error),
                            notice = if (it.dashboard != null) {
                                "Showing the last saved ${displayName(providerId)} inventory."
                            } else {
                                null
                            },
                            googleSignInRequired = error.requiresGoogleSignIn(),
                        )
                    }
                },
            )
        }
    }

    fun cancelOperation(providerId: String) {
        val state = provider(providerId)
        val operation = state.operation
        if (operation != HostingOperation.CONNECTING && operation != HostingOperation.REFRESHING) return
        val baseline = operationBaselines.remove(providerId)
        val cancelled = operationJobs.remove(providerId)
        val generation = nextGeneration(providerId)
        cancelled?.cancel()
        if (operation == HostingOperation.REFRESHING || baseline == null) {
            replaceProvider((baseline ?: state).copy(operation = null, notice = "Request cancelled."))
            return
        }
        // A cancelled connect may already have committed; reconcile with the saved slot.
        replaceProvider(baseline.copy(operation = HostingOperation.RESTORING, notice = "Cancelling request…"))
        operationJobs[providerId] = viewModelScope.launch {
            cancelled?.join()
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (!isCurrent(providerId, generation)) return@fold
                    val slot = restored[providerId] ?: HostingRestoreUi.NotConnected
                    applyRestore(providerId, slot)
                    updateProvider(providerId) {
                        it.copy(
                            notice = if (slot is HostingRestoreUi.Available) {
                                "The connection completed before cancellation and remains saved."
                            } else {
                                "Request cancelled."
                            },
                        )
                    }
                },
                onFailure = { error ->
                    if (!isCurrent(providerId, generation)) return@fold
                    replaceProvider(
                        baseline.copy(
                            operation = null,
                            error = safeMessage(providerId, error),
                            notice = "Request cancelled; saved connection status could not be verified.",
                        ),
                    )
                },
            )
            if (isCurrent(providerId, generation)) operationJobs.remove(providerId)
        }
    }

    fun requestDisconnectConfirmation(providerId: String) {
        val state = provider(providerId)
        if (state.isConnected && !state.isBusy) updateProvider(providerId) { it.copy(showDisconnectConfirmation = true) }
    }

    fun dismissDisconnectConfirmation(providerId: String) {
        updateProvider(providerId) { it.copy(showDisconnectConfirmation = false) }
    }

    fun confirmDisconnect(providerId: String) {
        val baseline = provider(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        closeApiWorkspace(providerId)
        closeResource(providerId)
        val closed = provider(providerId)
        launchOperation(providerId, HostingOperation.DISCONNECTING, closed.cleared()) { generation ->
            gateway.disconnect(providerId).fold(
                onSuccess = {
                    historyCache.invalidateScope(ResourceHistoryCache.scope(providerId))
                    if (!isCurrent(providerId, generation)) return@fold
                    if (pendingGoogleRetry != null && providerId == HostingProvider.FIREBASE.id) pendingGoogleRetry = null
                    replaceProvider(
                        HostingProviderUiState(providerId, status = HostingConnectionStatus.DISCONNECTED, operation = null),
                    )
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        replaceProvider(closed.cleared().copy(operation = null, error = safeMessage(providerId, error)))
                    }
                },
            )
        }
    }

    // endregion

    // region Resource detail

    fun openResource(providerId: String, resourceId: String) {
        val resource = provider(providerId).dashboard?.resources?.firstOrNull { it.id == resourceId } ?: return
        savedStateHandle[selectedResourceKey(providerId)] = resource.id
        updateProvider(providerId) {
            it.copy(
                selectedResourceId = resource.id,
                resourceWorkspace = null,
                resourceError = null,
                showActionConfirmation = false,
                actionMessage = null,
                actionError = null,
            )
        }
        loadSelectedResource(providerId, forceRefresh = false)
    }

    /** The toolbar refresh always reloads the history, bypassing the 180 s cache (iOS pull-to-refresh). */
    fun refreshSelectedResource(providerId: String) {
        val state = provider(providerId)
        if (state.selectedResourceId != null && !state.isLoadingResource) loadSelectedResource(providerId, forceRefresh = true)
    }

    fun closeResource(providerId: String) {
        nextResourceGeneration(providerId)
        resourceJobs.remove(providerId)?.cancel()
        savedStateHandle[selectedResourceKey(providerId)] = null
        updateProvider(providerId) {
            it.copy(
                selectedResourceId = null,
                resourceWorkspace = null,
                isLoadingResource = false,
                resourceError = null,
                showActionConfirmation = false,
                actionMessage = null,
                actionError = null,
            )
        }
    }

    /** Opens the iOS confirmation for the provider's write action (redeploy/restart/release). */
    fun requestPrimaryAction(providerId: String) {
        val state = provider(providerId)
        val provider = HostingProvider.fromId(providerId) ?: return
        if (provider.primaryActionLabel == null || state.selectedResource == null || state.isPerformingAction) return
        updateProvider(providerId) { it.copy(showActionConfirmation = true, actionError = null) }
    }

    fun dismissPrimaryAction(providerId: String) {
        updateProvider(providerId) { it.copy(showActionConfirmation = false) }
    }

    /** Runs the write request only from a visible confirmation, never as a side effect. */
    fun confirmPrimaryAction(providerId: String) {
        val state = provider(providerId)
        val resource = state.selectedResource ?: return
        if (!state.showActionConfirmation) return
        if (state.isPerformingAction || actionJobs[providerId]?.isActive == true) return
        val latestDeploymentId = state.resourceWorkspace
            ?.takeIf { it.resourceId == resource.id }
            ?.deployments?.firstOrNull()?.id
        updateProvider(providerId) {
            it.copy(showActionConfirmation = false, isPerformingAction = true, actionMessage = null, actionError = null)
        }
        actionJobs[providerId] = viewModelScope.launch {
            // Write requests are never cancelled by navigation: the provider may already have
            // applied them, so the outcome is reported if this resource is still on screen.
            val result = gateway.performPrimaryAction(providerId, resource, latestDeploymentId)
            val stillSelected = provider(providerId).selectedResourceId == resource.id
            result.fold(
                onSuccess = { message ->
                    updateProvider(providerId) {
                        it.copy(isPerformingAction = false, actionMessage = message.takeIf { stillSelected })
                    }
                    // The write changed the history: never serve the cached copy again.
                    historyCacheKey(providerId, resource.id)?.let(historyCache::invalidate)
                    if (stillSelected) {
                        delay(actionRefreshDelayMillis)
                        if (provider(providerId).selectedResourceId == resource.id) {
                            loadSelectedResource(providerId, forceRefresh = true)
                        }
                    }
                },
                onFailure = { error ->
                    updateProvider(providerId) {
                        it.copy(
                            isPerformingAction = false,
                            actionError = safeMessage(providerId, error).takeIf { stillSelected },
                        )
                    }
                },
            )
            actionJobs.remove(providerId)
        }
    }

    // endregion

    // region Complete API

    /**
     * The provider's Complete API workspace (iOS `ProviderFullAPICatalogView` and the raw
     * explorer). Created on first use; it restores an open workspace from saved state.
     */
    fun apiWorkspace(providerId: String): ProviderApiWorkspaceController? {
        val provider = HostingProvider.fromId(providerId) ?: return null
        return apiWorkspaces.getOrPut(providerId) {
            ProviderApiWorkspaceController(
                profile = ProviderApiProfile.hosting(provider),
                scope = viewModelScope,
                backend = object : ProviderApiBackend {
                    override suspend fun loadCatalog(
                        bundled: suspend () -> ProviderApiCatalog,
                        forceRefresh: Boolean,
                    ): Result<ProviderApiCatalog> = gateway.loadApiCatalog(providerId, bundled, forceRefresh)

                    override suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse> =
                        gateway.sendApiRequest(providerId, request)
                },
                savedStateHandle = savedStateHandle,
                keyPrefix = apiWorkspaceKey(providerId),
            )
        }
    }

    /** Opens Complete API from the dashboard or (with [resourceId]) a resource detail. Pro-gated by the route. */
    fun openApiWorkspace(providerId: String, resourceId: String? = null) {
        val provider = HostingProvider.fromId(providerId) ?: return
        val state = provider(providerId)
        if (state.status != HostingConnectionStatus.CONNECTED) return
        val resource = resourceId?.let { id -> state.dashboard?.resources?.firstOrNull { it.id == id } }
        val path = resource?.apiExplorerPath
            ?: resource?.let { HostingApiDefaults.explorerPath(HostingLinkContext(provider), it.toApiModel()) }
            ?: state.dashboard?.apiExplorerPath
            ?: HostingApiDefaults.explorerPath(HostingLinkContext(provider))
        apiWorkspace(providerId)?.open(path)
    }

    fun closeApiWorkspace(providerId: String) {
        apiWorkspaces[providerId]?.close()
    }

    // endregion

    /** Returns true when the route consumed back instead of asking the app shell to close it. */
    fun handleBack(providerId: String): Boolean {
        val state = provider(providerId)
        apiWorkspaces[providerId]?.takeIf { it.state.value.isOpen }?.let { workspace ->
            workspace.back()
            return true
        }
        return when {
            state.showActionConfirmation -> {
                dismissPrimaryAction(providerId)
                true
            }
            state.showDisconnectConfirmation -> {
                dismissDisconnectConfirmation(providerId)
                true
            }
            state.selectedResourceId != null -> {
                closeResource(providerId)
                true
            }
            else -> false
        }
    }

    fun clearFeedback(providerId: String) {
        updateProvider(providerId) {
            it.copy(error = null, notice = null, resourceError = null, actionMessage = null, actionError = null)
        }
    }

    // region Internals

    private fun connect(credentials: HostingCredentials, interactive: Boolean) {
        val providerId = credentials.provider.id
        val current = provider(providerId)
        if (current.isBusy || current.status == HostingConnectionStatus.RESTORING) return
        if (credentials is HostingCredentials.Firebase) pendingGoogleRetry = null
        val baseline = current.cleared().copy(awaitingGoogleSignIn = false)
        launchOperation(providerId, HostingOperation.CONNECTING, baseline) { generation ->
            gateway.connect(credentials).fold(
                onSuccess = { dashboard -> if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard) },
                onFailure = { error ->
                    if (!isCurrent(providerId, generation)) return@fold
                    val needsSignIn = credentials is HostingCredentials.Firebase && error.requiresGoogleSignIn()
                    when {
                        needsSignIn && interactive -> {
                            pendingGoogleRetry = GoogleRetry.Connect(credentials.projectId)
                            replaceProvider(baseline.copy(operation = null))
                            emitGoogleSignInRequest(providerId)
                        }
                        needsSignIn -> replaceProvider(
                            baseline.copy(
                                operation = null,
                                error = "Google sign-in did not complete. Continue with Google to try again.",
                            ),
                        )
                        else -> replaceProvider(baseline.copy(operation = null, error = safeMessage(providerId, error)))
                    }
                },
            )
        }
    }

    private fun emitGoogleSignInRequest(providerId: String) {
        val request = HostingGoogleSignInRequest(++signInRequestCounter, GoogleAccessTokenSource.FIREBASE_HOSTING_SCOPES)
        updateProvider(providerId) {
            it.copy(
                googleSignInRequest = request,
                awaitingGoogleSignIn = true,
                googleSignInRequired = false,
                error = null,
                notice = "Finish signing in with Google to continue.",
            )
        }
    }

    /** Retries once after sign-in; a second sign-in prompt always needs a user tap. */
    private fun retryAfterGoogleSignIn() {
        val providerId = HostingProvider.FIREBASE.id
        val state = provider(providerId)
        val retry = pendingGoogleRetry ?: return
        if (state.googleSignInRequest != null || state.isBusy || !state.awaitingGoogleSignIn) return
        pendingGoogleRetry = null
        updateProvider(providerId) { it.copy(awaitingGoogleSignIn = false, notice = null) }
        when (retry) {
            is GoogleRetry.Connect -> connect(HostingCredentials.Firebase(retry.projectId), interactive = false)
            GoogleRetry.Refresh -> refresh(providerId)
        }
    }

    private fun applyRestore(providerId: String, restored: HostingRestoreUi) {
        when (restored) {
            HostingRestoreUi.NotConnected -> replaceProvider(
                HostingProviderUiState(providerId, status = HostingConnectionStatus.DISCONNECTED, operation = null),
            )
            is HostingRestoreUi.Available -> replaceProvider(
                HostingProviderUiState(
                    providerId = providerId,
                    status = HostingConnectionStatus.CONNECTED,
                    dashboard = restored.dashboard,
                    savedAccount = restored.dashboard.account,
                    operation = null,
                    selectedResourceId = restoredSelection(providerId, restored.dashboard),
                ),
            )
            is HostingRestoreUi.SavedWithoutInventory -> replaceProvider(
                HostingProviderUiState(
                    providerId = providerId,
                    status = HostingConnectionStatus.SAVED_UNAVAILABLE,
                    savedAccount = restored.account,
                    savedDashboardUrl = restored.dashboardUrl,
                    operation = null,
                    notice = "This connection has no saved inventory. Refresh when you are online.",
                ),
            )
            is HostingRestoreUi.SavedUnavailable -> replaceProvider(
                HostingProviderUiState(
                    providerId = providerId,
                    status = HostingConnectionStatus.SAVED_UNAVAILABLE,
                    operation = null,
                    error = restored.message,
                ),
            )
        }
        if (provider(providerId).selectedResourceId != null) loadSelectedResource(providerId, forceRefresh = false)
    }

    private fun applyDashboard(providerId: String, dashboard: HostingDashboardUi) {
        val current = provider(providerId)
        val selected = current.selectedResourceId?.takeIf { id -> dashboard.resources.any { it.id == id } }
        if (selected == null && current.selectedResourceId != null) {
            nextResourceGeneration(providerId)
            resourceJobs.remove(providerId)?.cancel()
            savedStateHandle[selectedResourceKey(providerId)] = null
        }
        replaceProvider(
            HostingProviderUiState(
                providerId = providerId,
                status = HostingConnectionStatus.CONNECTED,
                dashboard = dashboard,
                savedAccount = dashboard.account,
                operation = null,
                selectedResourceId = selected,
                resourceWorkspace = current.resourceWorkspace?.takeIf { selected != null && it.resourceId == selected },
                isLoadingResource = current.isLoadingResource && selected != null,
                resourceError = current.resourceError?.takeIf { selected != null },
                isPerformingAction = current.isPerformingAction && selected != null,
                actionMessage = current.actionMessage?.takeIf { selected != null },
                actionError = current.actionError?.takeIf { selected != null },
            ),
        )
    }

    private fun restoredSelection(providerId: String, dashboard: HostingDashboardUi): String? {
        val restored: String = savedStateHandle[selectedResourceKey(providerId)] ?: return null
        return restored.takeIf { id -> dashboard.resources.any { it.id == id } }
            .also { if (it == null) savedStateHandle[selectedResourceKey(providerId)] = null }
    }

    /**
     * iOS `HostingResourceDetailViewModel.load(resource:forceRefresh:)`: a cached history is shown
     * at once; a fresh one (under 180 s) skips the network unless [forceRefresh] is set.
     */
    private fun loadSelectedResource(providerId: String, forceRefresh: Boolean) {
        val resource = provider(providerId).selectedResource ?: return
        val cacheKey = historyCacheKey(providerId, resource.id)
        val cached = cacheKey?.let(historyCache::get)?.takeIf { it.value.resourceId == resource.id }
        val generation = nextResourceGeneration(providerId)
        resourceJobs.remove(providerId)?.cancel()
        if (cached != null && !forceRefresh && cached.isFresh) {
            updateProvider(providerId) {
                it.copy(resourceWorkspace = cached.value, isLoadingResource = false, resourceError = null)
            }
            return
        }
        updateProvider(providerId) {
            it.copy(
                resourceWorkspace = cached?.value ?: it.resourceWorkspace?.takeIf { workspace -> workspace.resourceId == resource.id },
                isLoadingResource = true,
                resourceError = null,
            )
        }
        resourceJobs[providerId] = viewModelScope.launch {
            gateway.loadResource(providerId, resource).fold(
                onSuccess = { workspace ->
                    if (resourceGenerations[providerId] == generation && provider(providerId).selectedResourceId == resource.id) {
                        cacheKey?.let { historyCache.put(it, workspace) }
                        updateProvider(providerId) {
                            it.copy(resourceWorkspace = workspace, isLoadingResource = false, resourceError = null)
                        }
                    }
                },
                onFailure = { error ->
                    if (resourceGenerations[providerId] == generation && provider(providerId).selectedResourceId == resource.id) {
                        updateProvider(providerId) {
                            it.copy(isLoadingResource = false, resourceError = safeMessage(providerId, error))
                        }
                    }
                },
            )
        }
    }

    private fun historyCacheKey(providerId: String, resourceId: String): String? {
        val state = provider(providerId)
        val accountId = state.dashboard?.account?.id ?: state.savedAccount?.id ?: return null
        return ResourceHistoryCache.key(providerId, accountId, resourceId)
    }

    private fun refreshStaleInventories() {
        if (!isForeground || restoreJob?.isActive == true) return
        HostingProvider.ids.forEach(::refreshIfStale)
    }

    private fun refreshIfStale(providerId: String) {
        if (restoreJob?.isActive == true) return
        val state = provider(providerId)
        val dashboard = state.dashboard ?: return
        if (state.status != HostingConnectionStatus.CONNECTED || state.isBusy) return
        val stale = dashboard.cacheState == HostingCacheState.CACHED_STALE ||
            nowMillis() - dashboard.fetchedAtMillis >= HostingConnectionStore.CACHE_LIFETIME_MILLIS
        if (stale) refresh(providerId)
    }

    private fun launchOperation(
        providerId: String,
        operation: HostingOperation,
        baseline: HostingProviderUiState,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (operationJobs[providerId]?.isActive == true) return
        val generation = nextGeneration(providerId)
        operationBaselines[providerId] = baseline
        replaceProvider(baseline.copy(operation = operation, showDisconnectConfirmation = false))
        operationJobs[providerId] = viewModelScope.launch {
            try {
                block(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(providerId, generation)) {
                    replaceProvider(baseline.copy(operation = null, error = safeMessage(providerId, error)))
                }
            } finally {
                if (isCurrent(providerId, generation)) {
                    operationJobs.remove(providerId)
                    operationBaselines.remove(providerId)
                }
            }
        }
    }

    private fun cancelProviderWork(providerId: String) {
        nextGeneration(providerId)
        nextResourceGeneration(providerId)
        operationJobs.remove(providerId)?.cancel()
        operationBaselines.remove(providerId)
        resourceJobs.remove(providerId)?.cancel()
    }

    private fun nextGeneration(providerId: String): Long =
        ((operationGenerations[providerId] ?: 0L) + 1L).also { operationGenerations[providerId] = it }

    private fun nextResourceGeneration(providerId: String): Long =
        ((resourceGenerations[providerId] ?: 0L) + 1L).also { resourceGenerations[providerId] = it }

    private fun isCurrent(providerId: String, generation: Long): Boolean = operationGenerations[providerId] == generation

    private fun provider(providerId: String): HostingProviderUiState = _uiState.value.provider(providerId)

    private fun replaceProvider(state: HostingProviderUiState) {
        _uiState.update { it.copy(providers = it.providers + (state.providerId to state)) }
    }

    private fun updateProvider(providerId: String, transform: (HostingProviderUiState) -> HostingProviderUiState) {
        _uiState.update { state ->
            val current = state.provider(providerId)
            val next = transform(current)
            if (next == current) state else state.copy(providers = state.providers + (providerId to next))
        }
    }

    private fun HostingProviderUiState.cleared(): HostingProviderUiState = copy(
        error = null,
        notice = null,
        showDisconnectConfirmation = false,
        googleSignInRequired = false,
    )

    private fun initialState(visibleProviderId: String?) = HostingProvidersUiState(
        providers = HostingProvider.entries.associate { provider ->
            provider.id to HostingProviderUiState(
                providerId = provider.id,
                selectedResourceId = savedStateHandle[selectedResourceKey(provider.id)],
            )
        },
        visibleProviderId = visibleProviderId,
    )

    private fun Throwable.requiresGoogleSignIn(): Boolean = (this as? HostingUiException)?.requiresGoogleSignIn == true

    private fun safeMessage(providerId: String, error: Throwable): String =
        (error as? HostingUiException)?.message ?: "${displayName(providerId)} could not complete this request."

    private fun displayName(providerId: String): String = HostingProvider.fromId(providerId)?.displayName ?: "The provider"

    // endregion

    override fun onCleared() {
        restoreGeneration += 1
        restoreJob?.cancel()
        HostingProvider.ids.forEach(::cancelProviderWork)
        super.onCleared()
    }

    class Factory(
        private val gateway: HostingProviderUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(HostingProvidersViewModel::class.java)) {
                "Unsupported hosting ViewModel class."
            }
            return HostingProvidersViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val ACTION_REFRESH_DELAY_MILLIS = 1_000L

        internal fun selectedResourceKey(providerId: String) = "hosting.$providerId.selectedResourceId"

        internal fun apiWorkspaceKey(providerId: String) = "hosting.$providerId.completeApi"

        private fun HostingResourceUi.toApiModel(): HostingResource? = runCatching {
            HostingResource(
                id = id,
                name = name,
                subtitle = subtitle,
                url = url,
                status = status,
                region = region,
                kind = kind,
                updatedAtMillis = updatedAtMillis,
                metadata = metadata,
            )
        }.getOrNull()
    }
}
