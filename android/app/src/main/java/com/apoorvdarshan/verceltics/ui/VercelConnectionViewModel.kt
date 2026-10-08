package com.apoorvdarshan.verceltics.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.ui.vercel.VercelRefreshPolicy
import com.apoorvdarshan.verceltics.ui.vercel.VercelTimedCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VercelConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class VercelConnectionMutation {
    CONNECTING,
    DISCONNECTING,
    SWITCHING,
}

data class VercelConnectionUiState(
    val status: VercelConnectionStatus = VercelConnectionStatus.RESTORING,
    val dashboard: VercelDashboardUi? = null,
    val savedAccount: VercelAccountUi? = null,
    val error: String? = null,
    val isRefreshing: Boolean = false,
    val mutation: VercelConnectionMutation? = null,
    /** When the visible project list was last loaded successfully. */
    val lastUpdatedMillis: Long? = null,
    /** Every saved Vercel account in connection order (iOS `AuthManager.accounts`). */
    val accounts: List<VercelAccountUi> = emptyList(),
    /** The connect form is open to add an account while the active one stays connected. */
    val isAddingAccount: Boolean = false,
    /** Why the last add-account attempt failed; shown on that form only. */
    val connectError: String? = null,
    /** A just-activated account whose projects have not loaded yet in this session. */
    val isLoadingAccount: Boolean = false,
) {
    val isBusy: Boolean
        get() = status == VercelConnectionStatus.RESTORING || isRefreshing || mutation != null

    val isSearchAvailable: Boolean
        get() = status == VercelConnectionStatus.CONNECTED && dashboard != null

    /** The account the workspace shows, whether or not its dashboard loaded. */
    val activeAccount: VercelAccountUi?
        get() = dashboard?.account ?: savedAccount

    val activeAccountId: String?
        get() = activeAccount?.id

    /** Saved accounts for menus; single-account sources report just the active one. */
    val savedAccounts: List<VercelAccountUi>
        get() = accounts.ifEmpty { listOfNotNull(activeAccount) }
}

data class VercelAnalyticsUiState(
    val projectId: String? = null,
    val selectedRange: VercelAnalyticsRange = VercelAnalyticsRange.WEEK,
    val selectedEnvironment: VercelAnalyticsEnvironment = VercelAnalyticsEnvironment.PRODUCTION,
    val displayedRange: VercelAnalyticsRange? = null,
    val displayedEnvironment: VercelAnalyticsEnvironment? = null,
    val data: VercelAnalyticsDataUi? = null,
    val unavailableMessage: String? = null,
    val error: String? = null,
    val isLoading: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    /** False until a 3- or 12-month report has loaded for this account; drives lock markers. */
    val hasLongAnalyticsHistory: Boolean = false,
    /** `/v9/projects/{id}` when it has loaded; the listed project otherwise. */
    val projectDetails: VercelProjectUi? = null,
    val domains: List<String> = emptyList(),
    val recentDeployments: List<VercelDeploymentUi> = emptyList(),
    val isContextLoading: Boolean = false,
    val hasLoadedContext: Boolean = false,
) {
    val hasVisibleContent: Boolean
        get() = displayedRange != null

    val isWorking: Boolean
        get() = isLoading || isContextLoading
}

data class VercelDeploymentDetailUiState(
    val deploymentId: String? = null,
    val events: List<VercelDeploymentEventUi> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
)

/**
 * Activity-scoped owner for Vercel connection state and provider operations.
 *
 * The ViewModel survives navigation and configuration changes. A single mutex serializes gateway
 * work; refresh may be skipped while a credential mutation is active, but it never cancels that
 * mutation. Connect, switch and remove may cancel a read-only refresh before taking the mutex.
 *
 * Several Vercel accounts can be saved (iOS `AuthManager`); one is active. Projects, analytics,
 * project context and build events use stale-while-revalidate memory caches (see
 * [VercelRefreshPolicy]) keyed by account, so switching back to an account within the window
 * shows its projects without a reload, and one account's data is never shown for another.
 * Favicons are public and shared by every account.
 */
class VercelConnectionViewModel(
    private val gateway: VercelUiGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val operationMutex = Mutex()
    private val _uiState = MutableStateFlow(VercelConnectionUiState())
    val uiState: StateFlow<VercelConnectionUiState> = _uiState.asStateFlow()
    private val _analyticsState = MutableStateFlow(VercelAnalyticsUiState())
    val analyticsState: StateFlow<VercelAnalyticsUiState> = _analyticsState.asStateFlow()
    private val _deploymentState = MutableStateFlow(VercelDeploymentDetailUiState())
    val deploymentState: StateFlow<VercelDeploymentDetailUiState> = _deploymentState.asStateFlow()

    private var restoreJob: Job? = null
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var analyticsJob: Job? = null
    private var contextJob: Job? = null
    private var eventsJob: Job? = null
    private var analyticsProject: VercelProjectUi? = null
    private var analyticsAccountId: String = ""
    private var selectedDeployment: VercelDeploymentUi? = null
    private var analyticsGeneration = 0L
    private var contextGeneration = 0L
    private var eventsGeneration = 0L
    private var hasRefreshedProfiles = false
    private val longHistoryUnlockedAccounts = mutableSetOf<String>()
    private val dashboardCache = VercelTimedCache<String, VercelDashboardUi>(
        lifetimeMillis = VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS,
        limit = VercelAccountCacheLimit,
        nowMillis = nowMillis,
    )
    private val analyticsCache = VercelTimedCache<AnalyticsCacheKey, VercelAnalyticsLoadUi>(
        lifetimeMillis = VercelRefreshPolicy.REPORT_FRESHNESS_MILLIS,
        nowMillis = nowMillis,
    )
    private val contextCache = VercelTimedCache<ProjectCacheKey, VercelProjectContextUi>(
        lifetimeMillis = VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS,
        nowMillis = nowMillis,
    )
    private val eventsCache = VercelTimedCache<EventsCacheKey, List<VercelDeploymentEventUi>>(
        lifetimeMillis = VercelRefreshPolicy.EVENTS_FRESHNESS_MILLIS,
        nowMillis = nowMillis,
    )

    init {
        restore()
    }

    fun restore() {
        if (mutationJob?.isActive == true) return
        refreshJob?.cancel()
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            operationMutex.withLock {
                if (mutationJob?.isActive == true) return@withLock
                _uiState.value = VercelConnectionUiState()
                gateway.restore().fold(
                    onSuccess = ::applyRestore,
                    onFailure = { error ->
                        _uiState.value = VercelConnectionUiState(
                            status = VercelConnectionStatus.SAVED_UNAVAILABLE,
                            error = messageOf(error),
                        )
                    },
                )
            }
            refreshProfilesOnce()
        }
    }

    /** Manual refresh (toolbar, pull-to-refresh, retry): always reloads. */
    fun refresh() {
        if (
            mutationJob?.isActive == true ||
            restoreJob?.isActive == true ||
            refreshJob?.isActive == true ||
            _uiState.value.status == VercelConnectionStatus.DISCONNECTED
        ) {
            return
        }
        refreshJob = viewModelScope.launch {
            operationMutex.withLock {
                if (mutationJob?.isActive == true) return@withLock
                _uiState.update { it.copy(isRefreshing = true, error = null) }
                loadActiveDashboard()
            }
        }
    }

    /**
     * Background refresh (app returning to the foreground): skipped while the visible project list
     * is younger than [VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS], like iOS `ProjectsView`.
     */
    fun refreshIfStale() {
        val state = _uiState.value
        val isFresh = state.status == VercelConnectionStatus.CONNECTED &&
            state.dashboard != null &&
            state.error == null &&
            VercelRefreshPolicy.isFresh(
                updatedAtMillis = state.lastUpdatedMillis,
                nowMillis = nowMillis(),
                lifetimeMillis = VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS,
            )
        if (!isFresh) refresh()
    }

    /** Opens the connect form for another account; the active account stays connected. */
    fun startAddingAccount() {
        val state = _uiState.value
        if (state.mutation != null) return
        if (state.status != VercelConnectionStatus.CONNECTED && state.status != VercelConnectionStatus.SAVED_UNAVAILABLE) {
            return
        }
        _uiState.update { it.copy(isAddingAccount = true, connectError = null) }
    }

    fun cancelAddingAccount() {
        if (_uiState.value.mutation == VercelConnectionMutation.CONNECTING) return
        _uiState.update { it.copy(isAddingAccount = false, connectError = null) }
    }

    /**
     * Validates and saves a token, then shows its account. While another account is connected
     * this adds it (or rotates the token of the same Vercel identity) without removing anything.
     */
    fun connect(personalToken: String) {
        val adding = _uiState.value.isAddingAccount
        if (personalToken.isBlank()) {
            _uiState.update {
                if (adding) {
                    it.copy(connectError = BLANK_TOKEN_MESSAGE)
                } else {
                    it.copy(error = BLANK_TOKEN_MESSAGE)
                }
            }
            return
        }
        launchMutation(VercelConnectionMutation.CONNECTING, clearsError = !adding) {
            if (adding) _uiState.update { it.copy(connectError = null) }
            gateway.connect(personalToken).fold(
                onSuccess = { dashboard ->
                    val connectedId = dashboard.account.id
                    if (_uiState.value.activeAccountId != connectedId) closeProjectAnalytics()
                    // A new or rotated token may see different projects: drop what was cached for it.
                    dropAccountCaches(connectedId)
                    val accounts = gateway.loadAccounts().getOrNull()?.accounts.orEmpty()
                    showDashboard(
                        dashboard = dashboard,
                        accounts = accounts.ifEmpty { withAccount(_uiState.value.accounts, dashboard.account) },
                        mutation = VercelConnectionMutation.CONNECTING,
                        closesAddAccount = true,
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        if (it.isAddingAccount) it.copy(connectError = messageOf(error)) else it.copy(error = messageOf(error))
                    }
                },
            )
        }
    }

    /** Shows another saved account: cached projects when fresh, otherwise they load in place. */
    fun switchAccount(accountId: String) {
        val state = _uiState.value
        if (mutationJob?.isActive == true || state.activeAccountId == accountId) return
        if (state.savedAccounts.none { it.id == accountId }) return
        closeProjectAnalytics()
        launchMutation(VercelConnectionMutation.SWITCHING) {
            gateway.switchAccount(accountId).fold(
                onSuccess = { accounts -> activate(accounts, VercelConnectionMutation.SWITCHING) },
                onFailure = { error -> _uiState.update { it.copy(error = messageOf(error)) } },
            )
        }
    }

    /** iOS "Remove Current Account". */
    fun removeCurrentAccount() {
        val accountId = _uiState.value.activeAccountId
        if (accountId == null) {
            removeAllAccounts()
        } else {
            removeAccount(accountId)
        }
    }

    /**
     * Removes one saved account from this device. Removing the active account shows the next
     * saved one (iOS `removeAccount(id:)`); removing the last one disconnects Vercel.
     */
    fun removeAccount(accountId: String) {
        if (mutationJob?.isActive == true) return
        val wasActive = _uiState.value.activeAccountId == accountId
        if (wasActive) closeProjectAnalytics()
        launchMutation(VercelConnectionMutation.DISCONNECTING) {
            gateway.removeAccount(accountId).fold(
                onSuccess = { remaining ->
                    dropAccountCaches(accountId)
                    val state = _uiState.value
                    when {
                        remaining.activeAccount == null -> showDisconnected()
                        remaining.activeAccountId == state.activeAccountId -> _uiState.update {
                            it.copy(accounts = remaining.accounts)
                        }
                        else -> activate(remaining, VercelConnectionMutation.DISCONNECTING)
                    }
                },
                onFailure = { error -> _uiState.update { it.copy(error = messageOf(error)) } },
            )
        }
    }

    /** iOS "Remove All Accounts": every saved Vercel token leaves this device. */
    fun removeAllAccounts() {
        if (mutationJob?.isActive == true) return
        closeProjectAnalytics()
        clearAllCaches()
        launchMutation(VercelConnectionMutation.DISCONNECTING) {
            gateway.removeAllAccounts().fold(
                onSuccess = { showDisconnected() },
                onFailure = { error -> _uiState.update { it.copy(error = messageOf(error)) } },
            )
        }
    }

    /** Removes the active account; kept for single-account callers. */
    fun disconnect() = removeCurrentAccount()

    /** Favicons come only from the project's own origin; fixture gateways return null. */
    suspend fun loadFavicon(domain: String): ImageBitmap? = try {
        gateway.loadFavicon(domain)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun cachedFavicon(domain: String): ImageBitmap? = try {
        gateway.cachedFavicon(domain)
    } catch (_: Exception) {
        null
    }

    suspend fun loadAvatar(url: String): ImageBitmap? = try {
        gateway.loadAvatar(url)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun cachedAvatar(url: String): ImageBitmap? = try {
        gateway.cachedAvatar(url)
    } catch (_: Exception) {
        null
    }

    fun openProjectAnalytics(project: VercelProjectUi) {
        val current = _analyticsState.value
        if (
            analyticsProject?.id == project.id &&
            current.projectId == project.id &&
            (current.hasVisibleContent || current.isLoading)
        ) {
            return
        }
        closeDeployment()
        analyticsProject = project
        analyticsAccountId = _uiState.value.activeAccountId.orEmpty()
        _analyticsState.value = VercelAnalyticsUiState(
            projectId = project.id,
            selectedRange = current.selectedRange,
            selectedEnvironment = current.selectedEnvironment,
            hasLongAnalyticsHistory = hasLongAnalyticsHistory(),
            projectDetails = project,
            domains = listOfNotNull(project.primaryDomain),
        )
        startAnalyticsLoad(forceRefresh = false, debounce = false)
        startContextLoad(forceRefresh = false)
    }

    fun closeProjectAnalytics() {
        closeDeployment()
        analyticsJob?.cancel()
        analyticsJob = null
        contextJob?.cancel()
        contextJob = null
        analyticsGeneration += 1
        contextGeneration += 1
        analyticsProject = null
        _analyticsState.value = VercelAnalyticsUiState(
            selectedRange = _analyticsState.value.selectedRange,
            selectedEnvironment = _analyticsState.value.selectedEnvironment,
        )
    }

    /** The range stays selectable while a report loads; only the newest selection is applied. */
    fun selectAnalyticsRange(range: VercelAnalyticsRange) {
        if (_analyticsState.value.selectedRange == range) return
        _analyticsState.update { it.copy(selectedRange = range, error = null) }
        startAnalyticsLoad(forceRefresh = false, debounce = true)
    }

    fun selectAnalyticsEnvironment(environment: VercelAnalyticsEnvironment) {
        if (_analyticsState.value.selectedEnvironment == environment) return
        _analyticsState.update { it.copy(selectedEnvironment = environment, error = null) }
        startAnalyticsLoad(forceRefresh = false, debounce = true)
    }

    fun refreshProjectAnalytics() {
        startAnalyticsLoad(forceRefresh = true, debounce = false)
        startContextLoad(forceRefresh = true)
    }

    fun openDeployment(deployment: VercelDeploymentUi) {
        val current = _deploymentState.value
        if (selectedDeployment?.id == deployment.id && current.deploymentId == deployment.id) return
        selectedDeployment = deployment
        loadDeploymentEvents(forceRefresh = false)
    }

    fun refreshDeploymentEvents() {
        loadDeploymentEvents(forceRefresh = true)
    }

    fun closeDeployment() {
        eventsJob?.cancel()
        eventsJob = null
        eventsGeneration += 1
        selectedDeployment = null
        _deploymentState.value = VercelDeploymentDetailUiState()
    }

    private fun loadDeploymentEvents(forceRefresh: Boolean) {
        val project = analyticsProject ?: return
        val deployment = selectedDeployment ?: return
        eventsJob?.cancel()
        eventsJob = null
        eventsGeneration += 1
        val generation = eventsGeneration
        val identifier = deployment.eventsIdentifier
        if (identifier == null) {
            _deploymentState.value = VercelDeploymentDetailUiState(
                deploymentId = deployment.id,
                error = "This deployment does not include an event identifier.",
            )
            return
        }
        val key = EventsCacheKey(analyticsAccountId, project.teamId, identifier)
        val previous = _deploymentState.value.takeIf { it.deploymentId == deployment.id }
        val cached = eventsCache[key]
        val hasLoaded = cached != null || previous?.hasLoaded == true
        _deploymentState.value = VercelDeploymentDetailUiState(
            deploymentId = deployment.id,
            events = cached?.value ?: previous?.events.orEmpty(),
            hasLoaded = hasLoaded,
            isLoading = !hasLoaded,
        )
        if (cached != null && !forceRefresh && eventsCache.isFresh(cached)) return
        _deploymentState.update { it.copy(isLoading = true, error = null) }
        eventsJob = viewModelScope.launch {
            val result = gateway.loadDeploymentEvents(project, deployment)
            if (generation != eventsGeneration || selectedDeployment?.id != deployment.id) return@launch
            result.fold(
                onSuccess = { events ->
                    eventsCache.put(key, events)
                    _deploymentState.value = VercelDeploymentDetailUiState(
                        deploymentId = deployment.id,
                        events = events,
                        hasLoaded = true,
                    )
                },
                onFailure = { error ->
                    _deploymentState.update { it.copy(isLoading = false, error = messageOf(error)) }
                },
            )
        }
    }

    private fun startContextLoad(forceRefresh: Boolean) {
        val project = analyticsProject ?: return
        val key = ProjectCacheKey(analyticsAccountId, project.id, project.teamId)
        contextJob?.cancel()
        contextJob = null
        contextGeneration += 1
        val generation = contextGeneration
        val cached = contextCache[key]
        if (cached != null) {
            applyContext(project, cached.value, loaded = true)
            if (!forceRefresh && contextCache.isFresh(cached)) return
        }
        _analyticsState.update { it.copy(isContextLoading = true) }
        contextJob = viewModelScope.launch {
            val result = gateway.loadProjectContext(project)
            if (generation != contextGeneration || analyticsProject?.id != project.id) return@launch
            result.fold(
                onSuccess = { loaded ->
                    val current = _analyticsState.value
                    val merged = VercelProjectContextUi(
                        project = loaded.project ?: current.projectDetails ?: project,
                        domains = loaded.domains
                            ?.let { domains -> domains.ifEmpty { listOfNotNull(project.primaryDomain) } }
                            ?: current.domains,
                        deployments = loaded.deployments ?: current.recentDeployments,
                    )
                    applyContext(project, merged, loaded = true)
                    if (loaded.isComplete) contextCache.put(key, merged)
                },
                // Like iOS, context failures keep whatever is already visible.
                onFailure = { _analyticsState.update { it.copy(isContextLoading = false, hasLoadedContext = true) } },
            )
        }
    }

    private fun applyContext(project: VercelProjectUi, context: VercelProjectContextUi, loaded: Boolean) {
        _analyticsState.update {
            it.copy(
                projectDetails = context.project ?: project,
                domains = context.domains ?: it.domains,
                recentDeployments = context.deployments ?: it.recentDeployments,
                isContextLoading = false,
                hasLoadedContext = it.hasLoadedContext || loaded,
            )
        }
    }

    private fun startAnalyticsLoad(forceRefresh: Boolean, debounce: Boolean) {
        val project = analyticsProject ?: return
        val accountId = analyticsAccountId
        val selection = _analyticsState.value
        val range = selection.selectedRange
        val environment = selection.selectedEnvironment
        val key = AnalyticsCacheKey(accountId, project.id, project.teamId, range, environment)
        analyticsJob?.cancel()
        analyticsJob = null
        analyticsGeneration += 1
        val generation = analyticsGeneration
        val cached = analyticsCache[key]
        if (cached != null) {
            applyAnalyticsResult(
                projectId = project.id,
                range = range,
                environment = environment,
                result = cached.value,
                updatedAtMillis = cached.updatedAtMillis,
            )
            if (!forceRefresh && analyticsCache.isFresh(cached)) {
                return
            }
        }

        _analyticsState.update {
            it.copy(
                projectId = project.id,
                isLoading = true,
                error = null,
            )
        }
        analyticsJob = viewModelScope.launch {
            if (debounce) delay(ANALYTICS_SELECTION_DEBOUNCE_MILLIS)
            val result = gateway.loadProjectAnalytics(project, range, environment)
            if (
                generation != analyticsGeneration ||
                analyticsProject?.id != project.id ||
                analyticsAccountId != accountId ||
                _analyticsState.value.selectedRange != range ||
                _analyticsState.value.selectedEnvironment != environment
            ) {
                return@launch
            }
            result.fold(
                onSuccess = { loaded ->
                    val updatedAt = nowMillis()
                    analyticsCache.put(key, loaded, updatedAt)
                    applyAnalyticsResult(project.id, range, environment, loaded, updatedAt)
                    if (range.requiresLongHistory && loaded is VercelAnalyticsLoadUi.Available) {
                        unlockLongAnalyticsHistory(accountId)
                    }
                },
                onFailure = { error ->
                    _analyticsState.update {
                        it.copy(
                            isLoading = false,
                            error = messageOf(error),
                        )
                    }
                },
            )
        }
    }

    private fun unlockLongAnalyticsHistory(accountId: String) {
        _analyticsState.update { it.copy(hasLongAnalyticsHistory = true) }
        if (hasLongAnalyticsHistory()) return
        longHistoryUnlockedAccounts += accountId
        viewModelScope.launch { gateway.markLongAnalyticsHistoryAvailable() }
    }

    private fun hasLongAnalyticsHistory(): Boolean {
        val active = _uiState.value.activeAccount ?: return false
        return active.id in longHistoryUnlockedAccounts || active.hasLongAnalyticsHistory
    }

    private fun applyAnalyticsResult(
        projectId: String,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
        result: VercelAnalyticsLoadUi,
        updatedAtMillis: Long,
    ) {
        _analyticsState.value = when (result) {
            is VercelAnalyticsLoadUi.Available -> _analyticsState.value.copy(
                projectId = projectId,
                displayedRange = range,
                displayedEnvironment = environment,
                data = result.data,
                unavailableMessage = null,
                error = null,
                isLoading = false,
                lastUpdatedMillis = updatedAtMillis,
            )

            is VercelAnalyticsLoadUi.Unavailable -> _analyticsState.value.copy(
                projectId = projectId,
                displayedRange = range,
                displayedEnvironment = environment,
                data = null,
                unavailableMessage = result.message,
                error = null,
                isLoading = false,
                lastUpdatedMillis = updatedAtMillis,
            )
        }
    }

    /**
     * Shows the account [accounts] marks active: its cached projects right away when there are
     * any, then a reload unless they are younger than the inventory window. Runs inside a
     * mutation, so a refresh can never overtake it.
     */
    private suspend fun activate(accounts: VercelAccountsUi, mutation: VercelConnectionMutation) {
        val target = accounts.activeAccount ?: return showDisconnected()
        val cached = dashboardCache[target.id]
        if (cached != null) {
            showDashboard(
                dashboard = cached.value.copy(account = target),
                accounts = accounts.accounts,
                mutation = mutation,
                updatedAtMillis = cached.updatedAtMillis,
                cache = false,
            )
            if (dashboardCache.isFresh(cached)) return
            _uiState.update { it.copy(isRefreshing = true) }
        } else {
            _uiState.value = VercelConnectionUiState(
                status = VercelConnectionStatus.CONNECTED,
                dashboard = VercelDashboardUi(account = target, projects = emptyList()),
                savedAccount = target,
                isRefreshing = true,
                mutation = mutation,
                accounts = accounts.accounts,
                isLoadingAccount = true,
            )
        }
        loadActiveDashboard()
    }

    /** Loads the active account's projects, keeping cached ones visible when that fails. */
    private suspend fun loadActiveDashboard() {
        val mutation = _uiState.value.mutation
        gateway.refresh().fold(
            onSuccess = { dashboard ->
                showDashboard(dashboard, accounts = _uiState.value.accounts, mutation = mutation)
            },
            onFailure = { error ->
                _uiState.update { current ->
                    current.copy(
                        status = if (current.dashboard != null) {
                            VercelConnectionStatus.CONNECTED
                        } else {
                            VercelConnectionStatus.SAVED_UNAVAILABLE
                        },
                        isRefreshing = false,
                        isLoadingAccount = false,
                        error = messageOf(error),
                    )
                }
            },
        )
    }

    /**
     * Shows [dashboard] as the connected state. An open add-account form stays open (a background
     * refresh must not dismiss it) unless [closesAddAccount], which a successful connect sets.
     */
    private fun showDashboard(
        dashboard: VercelDashboardUi,
        accounts: List<VercelAccountUi>,
        mutation: VercelConnectionMutation? = null,
        updatedAtMillis: Long? = null,
        cache: Boolean = true,
        closesAddAccount: Boolean = false,
    ) {
        val updatedAt = updatedAtMillis ?: nowMillis()
        if (cache) dashboardCache.put(dashboard.account.id, dashboard, updatedAt)
        val current = _uiState.value
        _uiState.value = VercelConnectionUiState(
            status = VercelConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedAccount = dashboard.account,
            mutation = mutation,
            lastUpdatedMillis = updatedAt,
            accounts = withAccount(accounts, dashboard.account),
            isAddingAccount = current.isAddingAccount && !closesAddAccount,
            connectError = current.connectError.takeUnless { closesAddAccount },
        )
    }

    private fun showDisconnected() {
        clearAllCaches()
        _uiState.value = VercelConnectionUiState(
            status = VercelConnectionStatus.DISCONNECTED,
            mutation = _uiState.value.mutation,
        )
    }

    /**
     * Applies refreshed profiles once per launch (iOS refreshes them when `AuthManager` starts).
     * Only accounts still saved are updated, so a removal made meanwhile is never undone.
     */
    private fun refreshProfilesOnce() {
        if (hasRefreshedProfiles) return
        val status = _uiState.value.status
        if (status != VercelConnectionStatus.CONNECTED && status != VercelConnectionStatus.SAVED_UNAVAILABLE) return
        hasRefreshedProfiles = true
        viewModelScope.launch {
            val refreshed = gateway.refreshAccountProfiles().getOrNull()?.accounts.orEmpty()
            if (refreshed.isEmpty()) return@launch
            operationMutex.withLock { applyProfiles(refreshed) }
        }
    }

    private fun applyProfiles(refreshed: List<VercelAccountUi>) {
        val byId = refreshed.associateBy(VercelAccountUi::id)
        fun merged(existing: VercelAccountUi): VercelAccountUi {
            val profile = byId[existing.id] ?: return existing
            return profile.copy(hasLongAnalyticsHistory = existing.hasLongAnalyticsHistory || profile.hasLongAnalyticsHistory)
        }
        _uiState.update { state ->
            state.copy(
                accounts = state.accounts.map(::merged),
                dashboard = state.dashboard?.let { it.copy(account = merged(it.account)) },
                savedAccount = state.savedAccount?.let(::merged),
            )
        }
    }

    private fun dropAccountCaches(accountId: String) {
        dashboardCache.removeIf { it == accountId }
        analyticsCache.removeIf { it.accountId == accountId }
        contextCache.removeIf { it.accountId == accountId }
        eventsCache.removeIf { it.accountId == accountId }
        longHistoryUnlockedAccounts -= accountId
    }

    private fun clearAllCaches() {
        dashboardCache.clear()
        analyticsCache.clear()
        contextCache.clear()
        eventsCache.clear()
        longHistoryUnlockedAccounts.clear()
    }

    private fun launchMutation(
        mutation: VercelConnectionMutation,
        clearsError: Boolean = true,
        operation: suspend () -> Unit,
    ) {
        if (mutationJob?.isActive == true) return
        refreshJob?.cancel()
        restoreJob?.cancel()
        mutationJob = viewModelScope.launch {
            operationMutex.withLock {
                _uiState.update {
                    it.copy(
                        mutation = mutation,
                        isRefreshing = false,
                        error = if (clearsError) null else it.error,
                    )
                }
                try {
                    operation()
                } finally {
                    _uiState.update { it.copy(mutation = null) }
                }
            }
        }
    }

    private fun applyRestore(restored: VercelRestoreUi) {
        when (restored) {
            VercelRestoreUi.NoSavedAccount -> _uiState.value = VercelConnectionUiState(
                status = VercelConnectionStatus.DISCONNECTED,
            )

            is VercelRestoreUi.Available -> showDashboard(
                dashboard = restored.dashboard,
                accounts = restored.accounts?.accounts.orEmpty(),
            )

            is VercelRestoreUi.DashboardUnavailable -> _uiState.value = VercelConnectionUiState(
                status = VercelConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                error = messageOf(restored.error),
                accounts = withAccount(restored.accounts?.accounts.orEmpty(), restored.account),
            )
        }
    }

    private fun messageOf(error: Throwable): String =
        error.message?.takeIf(String::isNotBlank) ?: "The Vercel request could not be completed."

    class Factory(
        private val gateway: VercelUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VercelConnectionViewModel::class.java)) {
                "Unsupported ViewModel class: ${modelClass.name}"
            }
            return VercelConnectionViewModel(gateway) as T
        }
    }

    private data class AnalyticsCacheKey(
        val accountId: String,
        val projectId: String,
        val teamId: String?,
        val range: VercelAnalyticsRange,
        val environment: VercelAnalyticsEnvironment,
    )

    private data class ProjectCacheKey(
        val accountId: String,
        val projectId: String,
        val teamId: String?,
    )

    private data class EventsCacheKey(
        val accountId: String,
        val teamId: String?,
        val deploymentIdentifier: String,
    )

    private companion object {
        const val ANALYTICS_SELECTION_DEBOUNCE_MILLIS = 250L
        const val BLANK_TOKEN_MESSAGE = "Enter a Vercel personal access token."
    }
}

/** [accounts] with [account] replaced in place (by id), or appended when it is not listed yet. */
internal fun withAccount(accounts: List<VercelAccountUi>, account: VercelAccountUi): List<VercelAccountUi> =
    if (accounts.any { it.id == account.id }) {
        accounts.map { if (it.id == account.id) account else it }
    } else {
        accounts + account
    }

/** Dashboards are kept for every saved account, up to the same cap as saved accounts. */
private const val VercelAccountCacheLimit = 32
