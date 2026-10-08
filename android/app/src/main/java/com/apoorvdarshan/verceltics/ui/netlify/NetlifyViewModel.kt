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
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
import com.apoorvdarshan.verceltics.ui.hosting.ResourceHistoryCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    SWITCHING_ACCOUNT,
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
    /** iOS "Redeploy" confirmation for the selected site. */
    val showRedeployConfirmation: Boolean = false,
    val isRedeploying: Boolean = false,
    val redeployMessage: String? = null,
    val redeployError: String? = null,
    val routeVisible: Boolean = false,
    /** Every saved Netlify account, for the toolbar account menu. */
    val accounts: List<ProviderAccountUi> = emptyList(),
    /** True while the token form is shown to add another account without disconnecting. */
    val isAddingAccount: Boolean = false,
    /** iOS "Remove All Accounts" confirmation. */
    val showRemoveAllConfirmation: Boolean = false,
) {
    val selectedSite: NetlifySiteUi?
        get() = selectedSiteId?.let { id -> dashboard?.sites?.firstOrNull { it.id == id } }

    val isBusy: Boolean
        get() = operation != null

    val isConnected: Boolean
        get() = status == NetlifyConnectionStatus.CONNECTED ||
            status == NetlifyConnectionStatus.SAVED_UNAVAILABLE

    /** The token form is on screen: first connection, or adding another account. */
    val showsConnectionForm: Boolean
        get() = status == NetlifyConnectionStatus.DISCONNECTED || (isConnected && isAddingAccount)

    /** The saved (storage) id of the active account, when known. */
    val activeAccountId: String?
        get() = accounts.firstOrNull { it.isActive }?.id
            ?: dashboard?.account?.savedAccountId
            ?: savedAccount?.savedAccountId

    /** The activity owns FLAG_SECURE only while a Netlify credential can be visible/in flight. */
    val requiresSecureWindow: Boolean
        get() = routeVisible && (showsConnectionForm || operation == NetlifyOperation.CONNECTING)
}

/** Activity-scoped owner; connection and selected-site state survive configuration recreation. */
class NetlifyViewModel(
    private val gateway: NetlifyUiGateway,
    private val savedStateHandle: SavedStateHandle,
    nowMillis: () -> Long = System::currentTimeMillis,
    private val actionRefreshDelayMillis: Long = ACTION_REFRESH_DELAY_MILLIS,
    /** iOS 180 s site-history cache (`HostingResourceDetailViewModel.cachedDeployments`). */
    private val siteCache: ResourceHistoryCache<NetlifySiteWorkspaceUi> = ResourceHistoryCache(nowMillis),
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
    private var redeployJob: Job? = null
    private var accountJob: Job? = null
    private var accountGeneration = 0L
    private var profileRefreshStarted = false

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
                loadAccounts()
                startRestoredCacheRefreshIfReady()
                refreshAccountProfilesOnce()
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
        refreshAccountProfilesOnce()
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

    /**
     * iOS "Remove Current Account": removes only the active account and opens the next saved one
     * (or the token form when none remain). Without a known account id it removes everything.
     */
    fun confirmDisconnect() {
        val current = _uiState.value
        if (!current.isConnected || current.isBusy) return
        val accountId = current.activeAccountId
        closeApiWorkspace()
        closeSite()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(NetlifyOperation.DISCONNECTING, baseline) { generation ->
            val result = if (accountId == null) {
                gateway.disconnect().map { NetlifyRestoreUi.NotConnected }
            } else {
                gateway.removeAccount(accountId)
            }
            result.fold(
                onSuccess = { restored ->
                    siteCache.clear()
                    if (isCurrent(generation)) {
                        applyRestore(restored, keepAccounts = false)
                        loadAccounts()
                        restoredCacheNeedsRefresh = restored is NetlifyRestoreUi.Available
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

    /** iOS "Remove All Accounts": erases every saved Netlify account. */
    fun confirmRemoveAll() {
        if (!_uiState.value.isConnected || _uiState.value.isBusy) return
        closeApiWorkspace()
        closeSite()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(NetlifyOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect().fold(
                onSuccess = {
                    siteCache.clear()
                    if (isCurrent(generation)) {
                        accountGeneration += 1
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
                            showRemoveAllConfirmation = false,
                            error = safeMessage(error),
                        )
                    }
                },
            )
        }
    }

    /** Opens the token form on top of the dashboard to add another account (no disconnect). */
    fun startAddingAccount() {
        val state = _uiState.value
        if (!state.isConnected || state.isBusy) return
        closeApiWorkspace()
        setAddingAccount(true)
    }

    fun cancelAddingAccount() {
        if (_uiState.value.operation == NetlifyOperation.CONNECTING) return
        setAddingAccount(false)
        _uiState.update { it.copy(error = null) }
    }

    /** Makes another saved Netlify account active (iOS `switchAccount`) and refreshes it when stale. */
    fun switchAccount(accountId: String) {
        val state = _uiState.value
        if (!state.isConnected || state.isBusy || state.activeAccountId == accountId) return
        if (state.accounts.none { it.id == accountId }) return
        closeApiWorkspace()
        closeSite()
        setAddingAccount(false)
        val baseline = _uiState.value
        launchRootOperation(NetlifyOperation.SWITCHING_ACCOUNT, baseline) { generation ->
            gateway.switchAccount(accountId).fold(
                onSuccess = { restored ->
                    if (!isCurrent(generation)) return@fold
                    val marked = baseline.accounts.map { it.copy(isActive = it.id == accountId) }
                    applyRestore(restored, keepAccounts = false)
                    _uiState.update { it.copy(accounts = marked) }
                    loadAccounts()
                    restoredCacheNeedsRefresh = restored is NetlifyRestoreUi.Available &&
                        restored.dashboard.cacheState == NetlifyCacheState.CACHED_STALE
                    restoredCacheRefreshStarted = false
                    startRestoredCacheRefreshIfReady(afterOperation = true)
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(operation = null, error = safeMessage(error))
                        loadAccounts()
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
                showRedeployConfirmation = false,
                redeployMessage = null,
                redeployError = null,
            )
        }
        loadSelectedSite(forceRefresh = false)
    }

    /** The toolbar refresh always reloads, bypassing the 180 s cache (iOS pull-to-refresh). */
    fun refreshSelectedSite() {
        if (_uiState.value.selectedSiteId != null && !_uiState.value.isLoadingSite) {
            loadSelectedSite(forceRefresh = true)
        }
    }

    /** Opens the iOS "Redeploy <site>?" confirmation. Nothing is sent until it is confirmed. */
    fun requestRedeployConfirmation() {
        val state = _uiState.value
        if (state.status != NetlifyConnectionStatus.CONNECTED || state.selectedSite == null || state.isRedeploying) return
        _uiState.update { it.copy(showRedeployConfirmation = true, redeployError = null) }
    }

    fun dismissRedeployConfirmation() {
        _uiState.update { it.copy(showRedeployConfirmation = false) }
    }

    /**
     * Sends the real `POST /sites/{id}/builds` only from a visible confirmation. Like iOS, a
     * success reloads the site (bypassing the cache) after a short delay so the new deploy shows.
     */
    fun confirmRedeploy() {
        val state = _uiState.value
        val site = state.selectedSite ?: return
        if (!state.showRedeployConfirmation || state.isRedeploying || redeployJob?.isActive == true) return
        _uiState.update {
            it.copy(showRedeployConfirmation = false, isRedeploying = true, redeployMessage = null, redeployError = null)
        }
        redeployJob = viewModelScope.launch {
            // A write is never cancelled by navigation: Netlify may already have accepted it.
            val result = gateway.redeploySite(site.id)
            siteCacheKey(site.id)?.let(siteCache::invalidate)
            val stillSelected = _uiState.value.selectedSiteId == site.id
            result.fold(
                onSuccess = { message ->
                    _uiState.update {
                        it.copy(isRedeploying = false, redeployMessage = message.takeIf { stillSelected })
                    }
                    if (stillSelected) {
                        delay(actionRefreshDelayMillis)
                        if (_uiState.value.selectedSiteId == site.id) loadSelectedSite(forceRefresh = true)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isRedeploying = false, redeployError = safeMessage(error).takeIf { stillSelected })
                    }
                },
            )
            redeployJob = null
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
                showRedeployConfirmation = false,
                redeployMessage = null,
                redeployError = null,
            )
        }
    }

    /** Returns true when the route consumed back instead of asking the app shell to close it. */
    fun handleBack(): Boolean = when {
        apiWorkspace.state.value.isOpen -> apiWorkspace.back()
        _uiState.value.showRedeployConfirmation -> {
            dismissRedeployConfirmation()
            true
        }
        _uiState.value.showDisconnectConfirmation -> {
            dismissDisconnectConfirmation()
            true
        }
        _uiState.value.showRemoveAllConfirmation -> {
            dismissRemoveAllConfirmation()
            true
        }
        _uiState.value.isConnected && _uiState.value.isAddingAccount &&
            _uiState.value.operation != NetlifyOperation.CONNECTING -> {
            cancelAddingAccount()
            true
        }
        _uiState.value.selectedSiteId != null -> {
            closeSite()
            true
        }
        else -> false
    }

    fun clearFeedback() {
        _uiState.update { it.copy(error = null, notice = null, siteError = null, redeployMessage = null, redeployError = null) }
    }

    private fun applyRestore(restored: NetlifyRestoreUi, keepAccounts: Boolean = true) {
        val visible = _uiState.value.routeVisible
        val accounts = if (keepAccounts) _uiState.value.accounts else emptyList()
        // Saved-state guard: "adding an account" only survives recreation over a saved account.
        val addingAccount = restored !is NetlifyRestoreUi.NotConnected &&
            savedStateHandle.get<Boolean>(ADDING_ACCOUNT) == true
        if (!addingAccount) savedStateHandle[ADDING_ACCOUNT] = null
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
                accounts = accounts,
                isAddingAccount = addingAccount,
            )
            is NetlifyRestoreUi.SavedWithoutInventory -> NetlifyUiState(
                status = NetlifyConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                operation = null,
                notice = "This connection has no saved site inventory. Refresh when you are online.",
                routeVisible = visible,
                accounts = accounts,
                isAddingAccount = addingAccount,
            )
            is NetlifyRestoreUi.SavedUnavailable -> NetlifyUiState(
                status = NetlifyConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                routeVisible = visible,
                accounts = accounts,
                isAddingAccount = addingAccount,
            )
        }
        if (_uiState.value.selectedSiteId != null) loadSelectedSite(forceRefresh = false)
    }

    private fun applyDashboard(dashboard: NetlifyDashboardUi) {
        val current = _uiState.value
        // A different account never inherits the previous account's open site.
        val switchedAccount = current.dashboard != null &&
            current.dashboard.account.savedAccountId != dashboard.account.savedAccountId
        val selected = current.selectedSiteId
            ?.takeIf { !switchedAccount }
            ?.takeIf { id -> dashboard.sites.any { it.id == id } }
        if (selected == null && current.selectedSiteId != null) {
            siteGeneration += 1
            siteJob?.cancel()
            savedStateHandle[SELECTED_SITE_ID] = null
        }
        val activeId = dashboard.account.savedAccountId
        savedStateHandle[ADDING_ACCOUNT] = null
        _uiState.value = NetlifyUiState(
            status = NetlifyConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedAccount = dashboard.account,
            operation = null,
            selectedSiteId = selected,
            selectedSiteWorkspace = current.selectedSiteWorkspace?.takeIf { it.siteId == selected },
            isLoadingSite = current.isLoadingSite && selected != null,
            siteError = current.siteError?.takeIf { selected != null },
            isRedeploying = current.isRedeploying && selected != null,
            redeployMessage = current.redeployMessage?.takeIf { selected != null },
            redeployError = current.redeployError?.takeIf { selected != null },
            routeVisible = current.routeVisible,
            accounts = current.accounts.map { it.copy(isActive = it.id == activeId) },
        )
        if (current.dashboard?.account?.savedAccountId != activeId || current.accounts.none { it.id == activeId }) {
            loadAccounts()
        }
    }

    /** Reloads the account menu entries from encrypted storage (offline). */
    private fun loadAccounts() {
        val generation = ++accountGeneration
        accountJob?.cancel()
        accountJob = viewModelScope.launch {
            gateway.accounts().onSuccess { accounts ->
                if (accountGeneration == generation) applyAccounts(accounts)
            }
        }
    }

    private fun applyAccounts(accounts: List<ProviderAccountUi>) {
        _uiState.update { state ->
            if (state.status == NetlifyConnectionStatus.DISCONNECTED && accounts.isNotEmpty()) return@update state
            val active = accounts.firstOrNull { it.isActive }
            val dashboard = state.dashboard?.let { dashboard ->
                if (active != null && active.isReadable && dashboard.account.savedAccountId == active.id) {
                    dashboard.copy(
                        account = dashboard.account.copy(
                            displayName = active.displayName,
                            email = active.detail,
                            avatarUrl = active.avatarUrl,
                        ),
                    )
                } else {
                    dashboard
                }
            }
            state.copy(accounts = accounts, dashboard = dashboard)
        }
    }

    /** iOS refreshes every saved account's profile once per launch; Android once per process. */
    private fun refreshAccountProfilesOnce() {
        if (!isForeground || profileRefreshStarted || !_uiState.value.isConnected) return
        profileRefreshStarted = true
        viewModelScope.launch {
            // Let the restored-cache refresh finish first so the two never race on one record.
            operationJob?.join()
            val generation = ++accountGeneration
            gateway.refreshAccountProfiles().onSuccess { accounts ->
                if (accountGeneration == generation && _uiState.value.isConnected) applyAccounts(accounts)
            }
        }
    }

    private fun setAddingAccount(adding: Boolean) {
        savedStateHandle[ADDING_ACCOUNT] = if (adding) true else null
        _uiState.update { it.copy(isAddingAccount = adding) }
    }

    private fun restoredSelectedSite(dashboard: NetlifyDashboardUi): String? {
        val restored: String = savedStateHandle[SELECTED_SITE_ID] ?: return null
        return restored.takeIf { id -> dashboard.sites.any { it.id == id } }
            .also { if (it == null) savedStateHandle[SELECTED_SITE_ID] = null }
    }

    /**
     * iOS `HostingResourceDetailViewModel.load(resource:forceRefresh:)`: a cached site is shown at
     * once; a fresh one (under 180 s) skips the network unless [forceRefresh] is set.
     */
    private fun loadSelectedSite(forceRefresh: Boolean) {
        val siteId = _uiState.value.selectedSiteId ?: return
        val cacheKey = siteCacheKey(siteId)
        val cached = cacheKey?.let(siteCache::get)?.takeIf { it.value.siteId == siteId }
        siteGeneration += 1
        val generation = siteGeneration
        siteJob?.cancel()
        if (cached != null && !forceRefresh && cached.isFresh) {
            _uiState.update {
                it.copy(selectedSiteWorkspace = cached.value, isLoadingSite = false, siteError = null)
            }
            return
        }
        _uiState.update {
            it.copy(
                selectedSiteWorkspace = cached?.value ?: it.selectedSiteWorkspace?.takeIf { workspace -> workspace.siteId == siteId },
                isLoadingSite = true,
                siteError = null,
            )
        }
        siteJob = viewModelScope.launch {
            gateway.loadSite(siteId).fold(
                onSuccess = { workspace ->
                    if (siteGeneration == generation && _uiState.value.selectedSiteId == siteId) {
                        cacheKey?.let { siteCache.put(it, workspace) }
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

    private fun siteCacheKey(siteId: String): String? {
        val state = _uiState.value
        val accountId = state.dashboard?.account?.id ?: state.savedAccount?.id ?: return null
        return ResourceHistoryCache.key(NETLIFY_CACHE_SCOPE, accountId, siteId)
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
            showRemoveAllConfirmation = false,
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
        internal const val ACTION_REFRESH_DELAY_MILLIS = 1_000L
        private const val NETLIFY_CACHE_SCOPE = "netlify"
        internal const val SELECTED_SITE_ID = "netlify.selectedSiteId"
        internal const val API_WORKSPACE_KEY = "netlify.completeApi"
        internal const val ADDING_ACCOUNT = "netlify.addingAccount"
    }
}
