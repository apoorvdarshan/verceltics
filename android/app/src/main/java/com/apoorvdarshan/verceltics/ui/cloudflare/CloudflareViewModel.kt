package com.apoorvdarshan.verceltics.ui.cloudflare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareToolsTokenSource
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsNavigator
import com.apoorvdarshan.verceltics.ui.cloudflare.storage.CloudflareStorageRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CloudflareConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class CloudflareOperation {
    RESTORING,
    CONNECTING,
    REFRESHING,
    SWITCHING_ACCOUNT,
    DISCONNECTING,
}

data class CloudflareUiState(
    val status: CloudflareConnectionStatus = CloudflareConnectionStatus.RESTORING,
    val dashboard: CloudflareDashboardUi? = null,
    val savedProfile: CloudflareProfileUi? = null,
    val operation: CloudflareOperation? = CloudflareOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val showDisconnectConfirmation: Boolean = false,
    val selectedResource: CloudflareResourceSelection? = null,
    val routeVisible: Boolean = false,
) {
    val isBusy: Boolean get() = operation != null

    val isConnected: Boolean
        get() = status == CloudflareConnectionStatus.CONNECTED ||
            status == CloudflareConnectionStatus.SAVED_UNAVAILABLE

    val requiresSecureWindow: Boolean
        get() = routeVisible && (
            status == CloudflareConnectionStatus.DISCONNECTED ||
                operation == CloudflareOperation.CONNECTING
            )
}

class CloudflareViewModel(
    private val gateway: CloudflareUiGateway,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        CloudflareUiState(selectedResource = restoredSelection()),
    )
    val uiState: StateFlow<CloudflareUiState> = _uiState.asStateFlow()

    /** Back stack of pushed zone, Pages, Worker and storage operations screens. */
    val operationsNavigator = CloudflareOperationsNavigator(savedStateHandle)

    /** Authenticated operations client, or null for sample data (screens stay read-only). */
    val operationsClient: CloudflareRestClient? = gateway.operationsClient()

    private var operationJob: Job? = null
    private var operationGeneration = 0L
    private var operationBaseline: CloudflareUiState? = null
    private var isForeground = false
    private var restoredCacheNeedsRefresh = false
    private var restoredCacheRefreshStarted = false

    init {
        restore()
    }

    /** Cloudflare tools reuse this dashboard's saved token; offline sample gateways have none. */
    internal val toolsTokenSource: CloudflareToolsTokenSource?
        get() = (gateway as? NativeCloudflareUiGateway)?.let { native ->
            CloudflareToolsTokenSource { native.loadSavedApiTokenForTools() }
        }

    fun setRouteVisible(visible: Boolean) {
        _uiState.update { if (it.routeVisible == visible) it else it.copy(routeVisible = visible) }
    }

    fun restore() {
        cancelRootOperation()
        restoredCacheNeedsRefresh = false
        restoredCacheRefreshStarted = false
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            _uiState.update {
                CloudflareUiState(
                    selectedResource = restoredSelection(),
                    routeVisible = it.routeVisible,
                )
            }
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (isCurrent(generation)) {
                        applyRestore(restored)
                        restoredCacheNeedsRefresh = restored is CloudflareRestoreUi.Available
                    }
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.update {
                            it.copy(
                                status = CloudflareConnectionStatus.SAVED_UNAVAILABLE,
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

    fun connect(apiToken: SecretValue) {
        if (_uiState.value.isBusy) return
        val baseline = _uiState.value.copy(
            error = null,
            notice = null,
            showDisconnectConfirmation = false,
        )
        launchRootOperation(CloudflareOperation.CONNECTING, baseline) { generation ->
            gateway.connect(apiToken).fold(
                onSuccess = { dashboard -> if (isCurrent(generation)) applyDashboard(dashboard) },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(operation = null, error = safeMessage(error))
                    }
                },
            )
        }
    }

    // Keep the selected account: a manual refresh must not jump back to the first account.
    fun refresh() = refreshInternal(_uiState.value.dashboard?.selectedAccountId, CloudflareOperation.REFRESHING)

    fun selectAccount(accountId: String) {
        val current = _uiState.value
        if (current.dashboard?.accounts?.none { it.id == accountId } != false) return
        if (current.dashboard.selectedAccountId == accountId || current.isBusy) return
        closeResource()
        refreshInternal(accountId, CloudflareOperation.SWITCHING_ACCOUNT)
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
        if (operation != CloudflareOperation.CONNECTING &&
            operation != CloudflareOperation.REFRESHING &&
            operation != CloudflareOperation.SWITCHING_ACCOUNT
        ) {
            return
        }
        val visible = _uiState.value.routeVisible
        val baseline = operationBaseline
        operationGeneration += 1
        val cancelledJob = operationJob
        cancelledJob?.cancel()
        operationJob = null
        operationBaseline = null
        if (operation == CloudflareOperation.CONNECTING && baseline != null) {
            val generation = operationGeneration
            _uiState.value = baseline.copy(
                operation = CloudflareOperation.RESTORING,
                notice = "Cancelling request…",
                routeVisible = visible,
            )
            operationJob = viewModelScope.launch {
                cancelledJob?.join()
                gateway.restore().fold(
                    onSuccess = { restored ->
                        if (isCurrent(generation)) {
                            applyRestore(restored)
                            _uiState.update {
                                it.copy(
                                    notice = if (restored is CloudflareRestoreUi.Available) {
                                        "The connection completed before cancellation and remains saved."
                                    } else {
                                        "Request cancelled."
                                    },
                                )
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
        } else if (baseline != null) {
            _uiState.value = baseline.copy(
                operation = null,
                notice = "Request cancelled.",
                routeVisible = visible,
            )
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
        closeResource()
        launchRootOperation(CloudflareOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect().fold(
                onSuccess = {
                    if (isCurrent(generation)) {
                        _uiState.value = CloudflareUiState(
                            status = CloudflareConnectionStatus.DISCONNECTED,
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

    fun openResource(kind: CloudflareResourceKind, id: String) {
        if (!resourceExists(kind, id)) return
        val selection = CloudflareResourceSelection(kind, id)
        savedStateHandle[SELECTED_RESOURCE_KIND] = kind.name
        savedStateHandle[SELECTED_RESOURCE_ID] = id
        _uiState.update { it.copy(selectedResource = selection) }
    }

    /** Opens Storage & databases for the selected account. Callers gate this behind Pro. */
    fun openStorage(): Boolean {
        val dashboard = _uiState.value.dashboard ?: return false
        val accountId = dashboard.inventory?.accountId ?: dashboard.selectedAccountId ?: return false
        if (!_uiState.value.isConnected) return false
        operationsNavigator.push(CloudflareStorageRoutes.dashboard(accountId, dashboard.selectedAccount?.name))
        return true
    }

    fun closeResource() {
        operationsNavigator.clear()
        savedStateHandle[SELECTED_RESOURCE_KIND] = null
        savedStateHandle[SELECTED_RESOURCE_ID] = null
        _uiState.update { it.copy(selectedResource = null) }
    }

    fun handleBack(): Boolean = when {
        _uiState.value.showDisconnectConfirmation -> {
            dismissDisconnectConfirmation()
            true
        }
        operationsNavigator.pop() -> true
        _uiState.value.selectedResource != null -> {
            closeResource()
            true
        }
        else -> false
    }

    private fun refreshInternal(preferredAccountId: String?, operation: CloudflareOperation) {
        val baseline = _uiState.value
        if (!baseline.isConnected || baseline.isBusy) return
        restoredCacheNeedsRefresh = false
        restoredCacheRefreshStarted = true
        launchRootOperation(operation, baseline) { generation ->
            gateway.refresh(preferredAccountId).fold(
                onSuccess = { dashboard -> if (isCurrent(generation)) applyDashboard(dashboard) },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = if (baseline.dashboard != null) {
                                "Showing the last saved Cloudflare inventory."
                            } else {
                                null
                            },
                        )
                    }
                },
            )
        }
    }

    private fun applyRestore(restored: CloudflareRestoreUi) {
        val visible = _uiState.value.routeVisible
        if (restored !is CloudflareRestoreUi.Available) operationsNavigator.clear()
        _uiState.value = when (restored) {
            CloudflareRestoreUi.NotConnected -> CloudflareUiState(
                status = CloudflareConnectionStatus.DISCONNECTED,
                operation = null,
                routeVisible = visible,
            )
            is CloudflareRestoreUi.Available -> CloudflareUiState(
                status = CloudflareConnectionStatus.CONNECTED,
                dashboard = restored.dashboard,
                savedProfile = restored.dashboard.profile,
                operation = null,
                selectedResource = validatedSelection(restored.dashboard),
                routeVisible = visible,
            )
            is CloudflareRestoreUi.SavedWithoutInventory -> CloudflareUiState(
                status = CloudflareConnectionStatus.SAVED_UNAVAILABLE,
                savedProfile = restored.profile,
                operation = null,
                notice = "This connection has no saved inventory. Refresh when you are online.",
                routeVisible = visible,
            )
            is CloudflareRestoreUi.SavedUnavailable -> CloudflareUiState(
                status = CloudflareConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                routeVisible = visible,
            )
        }
    }

    private fun applyDashboard(dashboard: CloudflareDashboardUi) {
        _uiState.value = CloudflareUiState(
            status = CloudflareConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedProfile = dashboard.profile,
            operation = null,
            selectedResource = validatedSelection(dashboard),
            routeVisible = _uiState.value.routeVisible,
        )
    }

    private fun validatedSelection(dashboard: CloudflareDashboardUi): CloudflareResourceSelection? {
        val selection = restoredSelection() ?: _uiState.value.selectedResource ?: return null
        val exists = when (selection.kind) {
            CloudflareResourceKind.ZONE -> dashboard.inventory?.zones?.any { it.id == selection.id }
            CloudflareResourceKind.PAGES -> dashboard.inventory?.pagesProjects?.any { it.id == selection.id }
            CloudflareResourceKind.WORKER -> dashboard.inventory?.workers?.any { it.id == selection.id }
        } == true
        if (!exists) {
            savedStateHandle[SELECTED_RESOURCE_KIND] = null
            savedStateHandle[SELECTED_RESOURCE_ID] = null
        }
        return selection.takeIf { exists }
    }

    private fun restoredSelection(): CloudflareResourceSelection? {
        val kindName: String = savedStateHandle[SELECTED_RESOURCE_KIND] ?: return null
        val id: String = savedStateHandle[SELECTED_RESOURCE_ID] ?: return null
        val kind = runCatching { CloudflareResourceKind.valueOf(kindName) }.getOrNull() ?: return null
        return CloudflareResourceSelection(kind, id)
    }

    private fun resourceExists(kind: CloudflareResourceKind, id: String): Boolean {
        val inventory = _uiState.value.dashboard?.inventory ?: return false
        return when (kind) {
            CloudflareResourceKind.ZONE -> inventory.zones.any { it.id == id }
            CloudflareResourceKind.PAGES -> inventory.pagesProjects.any { it.id == id }
            CloudflareResourceKind.WORKER -> inventory.workers.any { it.id == id }
        }
    }

    private fun launchRootOperation(
        operation: CloudflareOperation,
        baseline: CloudflareUiState,
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
                    _uiState.value = baseline.copy(operation = null, error = safeMessage(error))
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

    private fun cancelRootOperation() {
        operationGeneration += 1
        operationJob?.cancel()
        operationJob = null
        operationBaseline = null
    }

    private fun isCurrent(generation: Long): Boolean = operationGeneration == generation

    private fun safeMessage(error: Throwable): String =
        (error as? CloudflareUiException)?.message ?: "Cloudflare could not complete this request."

    override fun onCleared() {
        operationsNavigator.clear()
        operationGeneration += 1
        operationJob?.cancel()
        super.onCleared()
    }

    class Factory(private val gateway: CloudflareUiGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(CloudflareViewModel::class.java)) {
                "Unsupported Cloudflare ViewModel class."
            }
            return CloudflareViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val SELECTED_RESOURCE_KIND = "cloudflare.selectedResourceKind"
        internal const val SELECTED_RESOURCE_ID = "cloudflare.selectedResourceId"
    }
}
