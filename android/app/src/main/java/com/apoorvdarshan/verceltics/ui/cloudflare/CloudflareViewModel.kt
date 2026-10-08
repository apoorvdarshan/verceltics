package com.apoorvdarshan.verceltics.ui.cloudflare

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareMutationAffectsDashboard
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareToolsCredentialSource
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
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
    /** Switching between saved Cloudflare logins (the toolbar account menu). */
    SWITCHING_LOGIN,
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
    /** Every saved Cloudflare login (token or Global API Key), for the toolbar account menu. */
    val savedLogins: List<ProviderAccountUi> = emptyList(),
    /** True while the credential form is shown to add another login without disconnecting. */
    val isAddingAccount: Boolean = false,
    /** iOS "Remove All Accounts" confirmation. */
    val showRemoveAllConfirmation: Boolean = false,
) {
    val isBusy: Boolean get() = operation != null

    val isConnected: Boolean
        get() = status == CloudflareConnectionStatus.CONNECTED ||
            status == CloudflareConnectionStatus.SAVED_UNAVAILABLE

    /** The credential form is on screen: first connection, or adding another login. */
    val showsConnectionForm: Boolean
        get() = status == CloudflareConnectionStatus.DISCONNECTED || (isConnected && isAddingAccount)

    /** The saved (storage) id of the active login, when known. */
    val activeLoginId: String?
        get() = savedLogins.firstOrNull { it.isActive }?.id
            ?: dashboard?.profile?.savedAccountId
            ?: savedProfile?.savedAccountId

    val requiresSecureWindow: Boolean
        get() = routeVisible && (showsConnectionForm || operation == CloudflareOperation.CONNECTING)
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
    private var loginJob: Job? = null
    private var loginGeneration = 0L
    private var profileRefreshStarted = false

    init {
        restore()
        viewModelScope.launch {
            gateway.mutationEvents().collect(::onExternalMutation)
        }
    }

    /** Cloudflare tools reuse this dashboard's saved credential; offline sample gateways have none. */
    internal val toolsCredentialSource: CloudflareToolsCredentialSource?
        get() = (gateway as? NativeCloudflareUiGateway)?.let { native ->
            CloudflareToolsCredentialSource { native.loadSavedCredentialForTools() }
        }

    /** Forwards a successful API explorer, Complete API or Product Center write to every listener. */
    internal fun publishToolsMutation(event: CloudflareMutationEvent) {
        (gateway as? NativeCloudflareUiGateway)?.publishToolsMutation(event)
    }

    /**
     * iOS `onReceive(.cloudflareDataDidChange)`: a write that changes the dashboard summary (zones,
     * accounts, Pages projects or Worker scripts) refreshes the live inventory in place.
     */
    private fun onExternalMutation(event: CloudflareMutationEvent) {
        if (!cloudflareMutationAffectsDashboard(event.apiPath)) return
        val state = _uiState.value
        if (state.status != CloudflareConnectionStatus.CONNECTED || state.dashboard == null) return
        refresh()
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
                loadSavedLogins()
                startRestoredCacheRefreshIfReady()
                refreshLoginProfilesOnce()
            }
        }
    }

    fun connect(apiToken: SecretValue) = connect(CloudflareCredential.ApiToken(apiToken))

    /** Validates and saves a scoped API token or an email + Global API Key (iOS `loginCloudflare`). */
    fun connect(credential: CloudflareCredential) {
        if (_uiState.value.isBusy) return
        val baseline = _uiState.value.copy(
            error = null,
            notice = null,
            showDisconnectConfirmation = false,
        )
        launchRootOperation(CloudflareOperation.CONNECTING, baseline) { generation ->
            gateway.connect(credential).fold(
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
        refreshLoginProfilesOnce()
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
                            // While adding a login the previous login restores as before; the
                            // connect only completed when a different (or a first) login is active.
                            val restoredId = restored.savedLoginId()
                            val completed = restored is CloudflareRestoreUi.Available &&
                                (!baseline.isConnected || (restoredId != null && restoredId != baseline.activeLoginId))
                            if (completed) savedStateHandle[ADDING_ACCOUNT] = null
                            applyRestore(restored, keepLogins = false)
                            loadSavedLogins()
                            _uiState.update {
                                it.copy(
                                    notice = if (completed) {
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

    /**
     * iOS "Remove Current Account": removes only the active login and opens the next saved one
     * (or the credential form when none remain). Without a known login id it removes everything.
     */
    fun confirmDisconnect() {
        val current = _uiState.value
        if (!current.isConnected || current.isBusy) return
        val loginId = current.activeLoginId
        closeResource()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(CloudflareOperation.DISCONNECTING, baseline) { generation ->
            // Without a listed login id the data layer resolves the active one; this never turns
            // "Remove current account" into removing every login.
            val result = if (loginId == null) gateway.removeActiveLogin() else gateway.removeLogin(loginId)
            result.fold(
                onSuccess = { restored ->
                    if (isCurrent(generation)) {
                        applyRestore(restored, keepLogins = false)
                        loadSavedLogins()
                        restoredCacheNeedsRefresh = restored is CloudflareRestoreUi.Available &&
                            restored.dashboard.cacheState == CloudflareCacheState.CACHED_STALE
                        restoredCacheRefreshStarted = false
                        startRestoredCacheRefreshIfReady(afterOperation = true)
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

    fun requestRemoveAllConfirmation() {
        if (_uiState.value.isConnected && !_uiState.value.isBusy) {
            _uiState.update { it.copy(showRemoveAllConfirmation = true) }
        }
    }

    fun dismissRemoveAllConfirmation() {
        _uiState.update { it.copy(showRemoveAllConfirmation = false) }
    }

    /** iOS "Remove All Accounts": erases every saved Cloudflare login. */
    fun confirmRemoveAll() {
        if (!_uiState.value.isConnected || _uiState.value.isBusy) return
        closeResource()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(CloudflareOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect().fold(
                onSuccess = {
                    if (isCurrent(generation)) {
                        loginGeneration += 1
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
                            showRemoveAllConfirmation = false,
                            error = safeMessage(error),
                        )
                    }
                },
            )
        }
    }

    /** Opens the credential form on top of the dashboard to add another login (no disconnect). */
    fun startAddingAccount() {
        val state = _uiState.value
        if (!state.isConnected || state.isBusy) return
        closeResource()
        setAddingAccount(true)
    }

    fun cancelAddingAccount() {
        if (_uiState.value.operation == CloudflareOperation.CONNECTING) return
        setAddingAccount(false)
        _uiState.update { it.copy(error = null) }
        startRestoredCacheRefreshIfReady()
    }

    /**
     * Makes another saved login active (iOS `switchAccount`). Operations, storage and every
     * Cloudflare tool read the active login's credential per request, so they follow at once.
     */
    fun switchLogin(savedAccountId: String) {
        val state = _uiState.value
        if (!state.isConnected || state.isBusy || state.activeLoginId == savedAccountId) return
        if (state.savedLogins.none { it.id == savedAccountId }) return
        closeResource()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(CloudflareOperation.SWITCHING_LOGIN, baseline) { generation ->
            gateway.switchLogin(savedAccountId).fold(
                onSuccess = { restored ->
                    if (!isCurrent(generation)) return@fold
                    val marked = baseline.savedLogins.map { it.copy(isActive = it.id == savedAccountId) }
                    applyRestore(restored, keepLogins = false)
                    _uiState.update { it.copy(savedLogins = marked) }
                    loadSavedLogins()
                    restoredCacheNeedsRefresh = restored is CloudflareRestoreUi.Available &&
                        restored.dashboard.cacheState == CloudflareCacheState.CACHED_STALE
                    restoredCacheRefreshStarted = false
                    startRestoredCacheRefreshIfReady(afterOperation = true)
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(operation = null, error = safeMessage(error))
                        loadSavedLogins()
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
        _uiState.value.showRemoveAllConfirmation -> {
            dismissRemoveAllConfirmation()
            true
        }
        _uiState.value.isConnected && _uiState.value.isAddingAccount &&
            _uiState.value.operation != CloudflareOperation.CONNECTING -> {
            cancelAddingAccount()
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
                onSuccess = { dashboard -> if (isCurrent(generation)) applyDashboard(dashboard, fromConnect = false) },
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

    private fun applyRestore(restored: CloudflareRestoreUi, keepLogins: Boolean = true) {
        val visible = _uiState.value.routeVisible
        val logins = if (keepLogins) _uiState.value.savedLogins else emptyList()
        // Saved-state guard: "adding a login" only survives recreation over a saved login.
        val addingAccount = restored !is CloudflareRestoreUi.NotConnected &&
            savedStateHandle.get<Boolean>(ADDING_ACCOUNT) == true
        if (!addingAccount) savedStateHandle[ADDING_ACCOUNT] = null
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
                savedLogins = logins,
                isAddingAccount = addingAccount,
            )
            is CloudflareRestoreUi.SavedWithoutInventory -> CloudflareUiState(
                status = CloudflareConnectionStatus.SAVED_UNAVAILABLE,
                savedProfile = restored.profile,
                operation = null,
                notice = "This connection has no saved inventory. Refresh when you are online.",
                routeVisible = visible,
                savedLogins = logins,
                isAddingAccount = addingAccount,
            )
            is CloudflareRestoreUi.SavedUnavailable -> CloudflareUiState(
                status = CloudflareConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                routeVisible = visible,
                savedLogins = logins,
                isAddingAccount = addingAccount,
            )
        }
    }

    /** Reloads the account menu entries from encrypted storage (offline). */
    private fun loadSavedLogins() {
        val generation = ++loginGeneration
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            gateway.savedLogins().onSuccess { logins ->
                if (loginGeneration == generation) applySavedLogins(logins)
            }
        }
    }

    private fun applySavedLogins(logins: List<ProviderAccountUi>) {
        _uiState.update { state ->
            if (state.status == CloudflareConnectionStatus.DISCONNECTED && logins.isNotEmpty()) return@update state
            val active = logins.firstOrNull { it.isActive }
            val dashboard = state.dashboard?.let { dashboard ->
                if (active != null && active.isReadable && dashboard.profile.savedAccountId == active.id) {
                    dashboard.copy(profile = dashboard.profile.copy(displayName = active.displayName))
                } else {
                    dashboard
                }
            }
            state.copy(savedLogins = logins, dashboard = dashboard)
        }
    }

    /** iOS refreshes every saved login's profile once per launch; Android once per process. */
    private fun refreshLoginProfilesOnce() {
        if (!isForeground || profileRefreshStarted || !_uiState.value.isConnected) return
        profileRefreshStarted = true
        viewModelScope.launch {
            // Let the restored-cache refresh and the offline login list finish first, so the two
            // never race on one record and the menu never waits for the network.
            operationJob?.join()
            loginJob?.join()
            val generation = loginGeneration
            gateway.refreshLoginProfiles().fold(
                onSuccess = { logins ->
                    if (loginGeneration == generation && _uiState.value.isConnected) applySavedLogins(logins)
                },
                onFailure = { if (loginGeneration == generation) loadSavedLogins() },
            )
        }
    }

    private fun setAddingAccount(adding: Boolean) {
        savedStateHandle[ADDING_ACCOUNT] = if (adding) true else null
        _uiState.update { it.copy(isAddingAccount = adding) }
    }

    private fun CloudflareRestoreUi.savedLoginId(): String? = when (this) {
        is CloudflareRestoreUi.Available -> dashboard.profile.savedAccountId
        is CloudflareRestoreUi.SavedWithoutInventory -> profile.savedAccountId
        else -> null
    }

    private fun applyDashboard(dashboard: CloudflareDashboardUi, fromConnect: Boolean = true) {
        val current = _uiState.value
        val activeId = dashboard.profile.savedAccountId
        val switchedLogin = current.dashboard?.profile?.savedAccountId != activeId
        if (switchedLogin && current.dashboard != null) operationsNavigator.clear()
        val keepsAddForm = !fromConnect && current.isAddingAccount
        if (!keepsAddForm) savedStateHandle[ADDING_ACCOUNT] = null
        _uiState.value = CloudflareUiState(
            status = CloudflareConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedProfile = dashboard.profile,
            operation = null,
            selectedResource = validatedSelection(dashboard),
            routeVisible = current.routeVisible,
            savedLogins = current.savedLogins.map { it.copy(isActive = it.id == activeId) },
            isAddingAccount = keepsAddForm,
        )
        if (switchedLogin || current.savedLogins.none { it.id == activeId }) loadSavedLogins()
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
            showRemoveAllConfirmation = false,
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

    private fun startRestoredCacheRefreshIfReady(afterOperation: Boolean = false) {
        if (!isForeground || !restoredCacheNeedsRefresh || restoredCacheRefreshStarted) return
        if (afterOperation) {
            // Called from inside the finishing operation: start once it has released the lock.
            viewModelScope.launch {
                operationJob?.join()
                startRestoredCacheRefreshIfReady()
            }
            return
        }
        if (operationJob?.isActive == true || !_uiState.value.isConnected) return
        // Never refresh the form away while the user is adding a login; it runs on cancel.
        if (_uiState.value.isAddingAccount) return
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
        internal const val ADDING_ACCOUNT = "cloudflare.addingAccount"
    }
}
