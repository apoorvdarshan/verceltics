package com.apoorvdarshan.verceltics.ui.netlify

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.HostingApiDefaults
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiBackend
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiProfile
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiWorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NetlifyConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class NetlifyOperation {
    RESTORING,
    CONNECTING,
    REFRESHING,
    DISCONNECTING,
}

data class NetlifyUiState(
    val status: NetlifyConnectionStatus = NetlifyConnectionStatus.RESTORING,
    val dashboard: NetlifyDashboardUi? = null,
    val savedAccount: NetlifyAccountUi? = null,
    val operation: NetlifyOperation? = NetlifyOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val showDisconnectConfirmation: Boolean = false,
    val selectedSiteId: String? = null,
    val selectedSiteWorkspace: NetlifySiteWorkspaceUi? = null,
    val isLoadingSite: Boolean = false,
    val siteError: String? = null,
    val routeVisible: Boolean = false,
) {
    val isBusy: Boolean
        get() = operation != null

    val isConnected: Boolean
        get() = status == NetlifyConnectionStatus.CONNECTED ||
            status == NetlifyConnectionStatus.SAVED_UNAVAILABLE

    /** The activity owns FLAG_SECURE only while a Netlify credential can be visible/in flight. */
    val requiresSecureWindow: Boolean
        get() = routeVisible && (
            status == NetlifyConnectionStatus.DISCONNECTED ||
                operation == NetlifyOperation.CONNECTING
            )
}

/** Activity-scoped owner; connection and selected-site state survive configuration recreation. */
class NetlifyViewModel(
    private val gateway: NetlifyUiGateway,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        NetlifyUiState(selectedSiteId = savedStateHandle[SELECTED_SITE_ID]),
    )
    val uiState: StateFlow<NetlifyUiState> = _uiState.asStateFlow()

    private var operationJob: Job? = null
    private var operationGeneration = 0L
    private var operationBaseline: NetlifyUiState? = null
    private var isForeground = false
    private var restoredCacheNeedsRefresh = false
    private var restoredCacheRefreshStarted = false
    private var siteJob: Job? = null
    private var siteGeneration = 0L

    /**
     * Netlify's Complete API workspace (iOS `ProviderFullAPICatalogView` for the Netlify hosting
     * account and its raw explorer). It restores an open workspace from saved state.
     */
    val apiWorkspace: ProviderApiWorkspaceController = ProviderApiWorkspaceController(
        profile = ProviderApiProfile.netlify(),
        scope = viewModelScope,
        backend = object : ProviderApiBackend {
            override suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse> = gateway.sendApiRequest(request)
        },
        savedStateHandle = savedStateHandle,
        keyPrefix = API_WORKSPACE_KEY,
    )

    init {
        restore()
    }

    /** Opens Complete API from the dashboard or (with [siteId]) a site detail. Pro-gated by the route. */
    fun openApiWorkspace(siteId: String? = null) {
        if (_uiState.value.status != NetlifyConnectionStatus.CONNECTED) return
        val site = siteId?.let { id -> _uiState.value.dashboard?.sites?.firstOrNull { it.id == id } }
        apiWorkspace.open(HostingApiDefaults.netlifyExplorerPath(site?.id))
    }

    fun closeApiWorkspace() = apiWorkspace.close()

    fun setRouteVisible(visible: Boolean) {
        _uiState.update { current ->
            if (current.routeVisible == visible) current else current.copy(routeVisible = visible)
        }
    }

    fun restore() {
        cancelRootOperation(resetState = false)
        restoredCacheNeedsRefresh = false
        restoredCacheRefreshStarted = false
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            _uiState.update {
                NetlifyUiState(
                    selectedSiteId = savedStateHandle[SELECTED_SITE_ID],
                    routeVisible = it.routeVisible,
                )
            }
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (isCurrent(generation)) {
                        applyRestore(restored)
                        restoredCacheNeedsRefresh = restored is NetlifyRestoreUi.Available
                    }
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.update {
                            it.copy(
                                status = NetlifyConnectionStatus.SAVED_UNAVAILABLE,
                                operation = null,
                                error = safeMessage(error),
                            )
                        }
                    }
                },
            )
            if (isCurrent(generation)) {
                operationJob = null
                operationBaseline = null
                startRestoredCacheRefreshIfReady()
            }
        }
    }

    fun connect(personalToken: SecretValue) {
        if (_uiState.value.isBusy) return
        val baseline = _uiState.value.copy(
            error = null,
            notice = null,
            showDisconnectConfirmation = false,
        )
        launchRootOperation(NetlifyOperation.CONNECTING, baseline) { generation ->
            gateway.connect(personalToken).fold(
                onSuccess = { dashboard ->
                    if (isCurrent(generation)) applyDashboard(dashboard)
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(
                            operation = null,
                            error = safeMessage(error),
                        )
                    }
                },
            )
        }
    }

    fun refresh() {
        val baseline = _uiState.value
        if (!baseline.isConnected || baseline.isBusy) return
        restoredCacheNeedsRefresh = false
        restoredCacheRefreshStarted = true
        launchRootOperation(NetlifyOperation.REFRESHING, baseline) { generation ->
            gateway.refresh().fold(
                onSuccess = { dashboard ->
                    if (isCurrent(generation)) applyDashboard(dashboard)
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = if (baseline.dashboard != null) {
                                "Showing the last saved Netlify inventory."
                            } else {
                                null
                            },
                        )
                    }
                },
            )
        }
    }

    fun onForeground() {
        isForeground = true
        startRestoredCacheRefreshIfReady()
    }

    fun onBackground() {
        isForeground = false
    }

    fun cancelOperation() {
        val operation = _uiState.value.operation
        if (operation != NetlifyOperation.CONNECTING && operation != NetlifyOperation.REFRESHING) {
            return
        }
        val visible = _uiState.value.routeVisible
        val baseline = operationBaseline
        operationGeneration += 1
        val cancelledJob = operationJob
        cancelledJob?.cancel()
        operationJob = null
        operationBaseline = null
        if (operation == NetlifyOperation.CONNECTING && baseline != null) {
            val generation = operationGeneration
            _uiState.value = baseline.copy(
                operation = NetlifyOperation.RESTORING,
                notice = "Cancelling request…",
                routeVisible = visible,
            )
            operationJob = viewModelScope.launch {
                cancelledJob?.join()
                gateway.restore().fold(
                    onSuccess = { restored ->
                        if (isCurrent(generation)) {
                            applyRestore(restored)
                            if (restored is NetlifyRestoreUi.Available) {
                                _uiState.update {
                                    it.copy(notice = "The connection completed before cancellation and remains saved.")
                                }
                            } else {
                                _uiState.update { it.copy(notice = "Request cancelled.") }
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isCurrent(generation)) {
                            _uiState.value = baseline.copy(
                                operation = null,
                                error = safeMessage(error),
                                notice = "Request cancelled; saved connection status could not be verified.",
                                routeVisible = visible,
                            )
                        }
                    },
                )
                if (isCurrent(generation)) operationJob = null
            }
            return
        }
        if (baseline != null) {
            _uiState.value = baseline.copy(
                operation = null,
                notice = "Request cancelled.",
                routeVisible = visible,
            )
        } else {
            _uiState.update { it.copy(operation = null, notice = "Request cancelled.") }
        }
    }

    fun requestDisconnectConfirmation() {
        if (_uiState.value.isConnected && !_uiState.value.isBusy) {
            _uiState.update { it.copy(showDisconnectConfirmation = true) }
        }
    }

    fun dismissDisconnectConfirmation() {
        _uiState.update { it.copy(showDisconnectConfirmation = false) }
    }

    fun confirmDisconnect() {
        val baseline = _uiState.value
        if (!baseline.isConnected || baseline.isBusy) return
        closeApiWorkspace()
        closeSite()
        launchRootOperation(NetlifyOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect().fold(
                onSuccess = {
                    if (isCurrent(generation)) {
                        _uiState.value = NetlifyUiState(
                            status = NetlifyConnectionStatus.DISCONNECTED,
                            operation = null,
                            routeVisible = baseline.routeVisible,
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(
                            operation = null,
                            showDisconnectConfirmation = false,
                            error = safeMessage(error),
                        )
                    }
                },
            )
        }
    }

    fun openSite(siteId: String) {
        val site = _uiState.value.dashboard?.sites?.firstOrNull { it.id == siteId } ?: return
        savedStateHandle[SELECTED_SITE_ID] = site.id
        _uiState.update {
            it.copy(
                selectedSiteId = site.id,
                selectedSiteWorkspace = null,
                siteError = null,
            )
        }
        loadSelectedSite()
    }

    fun refreshSelectedSite() {
        if (_uiState.value.selectedSiteId != null && !_uiState.value.isLoadingSite) {
            loadSelectedSite()
        }
    }

    fun closeSite() {
        siteGeneration += 1
        siteJob?.cancel()
        siteJob = null
        savedStateHandle[SELECTED_SITE_ID] = null
        _uiState.update {
            it.copy(
                selectedSiteId = null,
                selectedSiteWorkspace = null,
                isLoadingSite = false,
                siteError = null,
            )
        }
    }

    /** Returns true when the route consumed back instead of asking the app shell to close it. */
    fun handleBack(): Boolean = when {
        apiWorkspace.state.value.isOpen -> apiWorkspace.back()
        _uiState.value.showDisconnectConfirmation -> {
            dismissDisconnectConfirmation()
            true
        }
        _uiState.value.selectedSiteId != null -> {
            closeSite()
            true
        }
        else -> false
    }

    fun clearFeedback() {
        _uiState.update { it.copy(error = null, notice = null, siteError = null) }
    }

    private fun applyRestore(restored: NetlifyRestoreUi) {
        val visible = _uiState.value.routeVisible
        _uiState.value = when (restored) {
            NetlifyRestoreUi.NotConnected -> NetlifyUiState(
                status = NetlifyConnectionStatus.DISCONNECTED,
                operation = null,
                routeVisible = visible,
            )
            is NetlifyRestoreUi.Available -> NetlifyUiState(
                status = NetlifyConnectionStatus.CONNECTED,
                dashboard = restored.dashboard,
                savedAccount = restored.dashboard.account,
                operation = null,
                selectedSiteId = restoredSelectedSite(restored.dashboard),
                routeVisible = visible,
            )
            is NetlifyRestoreUi.SavedWithoutInventory -> NetlifyUiState(
                status = NetlifyConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                operation = null,
                notice = "This connection has no saved site inventory. Refresh when you are online.",
                routeVisible = visible,
            )
            is NetlifyRestoreUi.SavedUnavailable -> NetlifyUiState(
                status = NetlifyConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                routeVisible = visible,
            )
        }
        if (_uiState.value.selectedSiteId != null) loadSelectedSite()
    }

    private fun applyDashboard(dashboard: NetlifyDashboardUi) {
        val current = _uiState.value
        val selected = current.selectedSiteId?.takeIf { id -> dashboard.sites.any { it.id == id } }
        if (selected == null && current.selectedSiteId != null) {
            siteGeneration += 1
            siteJob?.cancel()
            savedStateHandle[SELECTED_SITE_ID] = null
        }
        _uiState.value = NetlifyUiState(
            status = NetlifyConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedAccount = dashboard.account,
            operation = null,
            selectedSiteId = selected,
            selectedSiteWorkspace = current.selectedSiteWorkspace?.takeIf { it.siteId == selected },
            isLoadingSite = current.isLoadingSite && selected != null,
            siteError = current.siteError?.takeIf { selected != null },
            routeVisible = current.routeVisible,
        )
    }

    private fun restoredSelectedSite(dashboard: NetlifyDashboardUi): String? {
        val restored: String = savedStateHandle[SELECTED_SITE_ID] ?: return null
        return restored.takeIf { id -> dashboard.sites.any { it.id == id } }
            .also { if (it == null) savedStateHandle[SELECTED_SITE_ID] = null }
    }

    private fun loadSelectedSite() {
        val siteId = _uiState.value.selectedSiteId ?: return
        siteGeneration += 1
        val generation = siteGeneration
        siteJob?.cancel()
        _uiState.update {
            it.copy(isLoadingSite = true, siteError = null)
        }
        siteJob = viewModelScope.launch {
            gateway.loadSite(siteId).fold(
                onSuccess = { workspace ->
                    if (siteGeneration == generation && _uiState.value.selectedSiteId == siteId) {
                        _uiState.update {
                            it.copy(
                                selectedSiteWorkspace = workspace,
                                isLoadingSite = false,
                                siteError = null,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (siteGeneration == generation && _uiState.value.selectedSiteId == siteId) {
                        _uiState.update {
                            it.copy(isLoadingSite = false, siteError = safeMessage(error))
                        }
                    }
                },
            )
        }
    }

    private fun launchRootOperation(
        operation: NetlifyOperation,
        baseline: NetlifyUiState,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (operationJob?.isActive == true) return
        val generation = ++operationGeneration
        operationBaseline = baseline
        _uiState.value = baseline.copy(
            operation = operation,
            error = null,
            notice = null,
            showDisconnectConfirmation = false,
        )
        operationJob = viewModelScope.launch {
            try {
                block(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(generation)) {
                    _uiState.value = baseline.copy(
                        operation = null,
                        error = safeMessage(error),
                    )
                }
            } finally {
                if (isCurrent(generation)) {
                    operationJob = null
                    operationBaseline = null
                }
            }
        }
    }

    private fun startRestoredCacheRefreshIfReady() {
        if (!isForeground || !restoredCacheNeedsRefresh || restoredCacheRefreshStarted) return
        if (operationJob?.isActive == true || !_uiState.value.isConnected) return
        restoredCacheRefreshStarted = true
        restoredCacheNeedsRefresh = false
        refresh()
    }

    private fun cancelRootOperation(resetState: Boolean) {
        operationGeneration += 1
        operationJob?.cancel()
        operationJob = null
        operationBaseline = null
        if (resetState) _uiState.update { it.copy(operation = null) }
    }

    private fun isCurrent(generation: Long): Boolean = operationGeneration == generation

    private fun safeMessage(error: Throwable): String =
        (error as? NetlifyUiException)?.message ?: "Netlify could not complete this request."

    override fun onCleared() {
        operationGeneration += 1
        siteGeneration += 1
        operationJob?.cancel()
        siteJob?.cancel()
        super.onCleared()
    }

    class Factory(
        private val gateway: NetlifyUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(NetlifyViewModel::class.java)) {
                "Unsupported Netlify ViewModel class."
            }
            return NetlifyViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val SELECTED_SITE_ID = "netlify.selectedSiteId"
        internal const val API_WORKSPACE_KEY = "netlify.completeApi"
    }
}
