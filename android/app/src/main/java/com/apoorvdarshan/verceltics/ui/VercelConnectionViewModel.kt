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
) {
    val isBusy: Boolean
        get() = status == VercelConnectionStatus.RESTORING || isRefreshing || mutation != null

    val isSearchAvailable: Boolean
        get() = status == VercelConnectionStatus.CONNECTED && dashboard != null
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
 * mutation. Connect and disconnect may cancel a read-only refresh before taking the mutex.
 *
 * Projects, analytics, project context and build events use stale-while-revalidate memory caches
 * (see [VercelRefreshPolicy]); disconnecting or connecting another token drops all of them.
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
    private var selectedDeployment: VercelDeploymentUi? = null
    private var analyticsGeneration = 0L
    private var contextGeneration = 0L
    private var eventsGeneration = 0L
    private var longHistoryUnlocked = false
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
                gateway.refresh().fold(
                    onSuccess = { dashboard ->
                        _uiState.value = connectedState(dashboard)
                    },
                    onFailure = { error ->
                        _uiState.update { current ->
                            if (current.dashboard != null) {
                                current.copy(
                                    status = VercelConnectionStatus.CONNECTED,
                                    isRefreshing = false,
                                    error = messageOf(error),
                                )
                            } else {
                                current.copy(
                                    status = VercelConnectionStatus.SAVED_UNAVAILABLE,
                                    isRefreshing = false,
                                    error = messageOf(error),
                                )
                            }
                        }
                    },
                )
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

    fun connect(personalToken: String) {
        if (personalToken.isBlank()) {
            _uiState.update { it.copy(error = "Enter a Vercel personal access token.") }
            return
        }
        launchMutation(VercelConnectionMutation.CONNECTING) {
            gateway.connect(personalToken).fold(
                onSuccess = { dashboard ->
                    // A different token may see different projects: never reuse another account's data.
                    clearProjectCaches()
                    _uiState.value = connectedState(
                        dashboard = dashboard,
                        mutation = VercelConnectionMutation.CONNECTING,
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = messageOf(error)) }
                },
            )
        }
    }

    fun disconnect() {
        closeProjectAnalytics()
        clearProjectCaches()
        launchMutation(VercelConnectionMutation.DISCONNECTING) {
            gateway.disconnect().fold(
                onSuccess = {
                    _uiState.value = VercelConnectionUiState(
                        status = VercelConnectionStatus.DISCONNECTED,
                        mutation = VercelConnectionMutation.DISCONNECTING,
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(error = messageOf(error)) }
                },
            )
        }
    }

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
        val key = EventsCacheKey(project.teamId, identifier)
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
        val key = ProjectCacheKey(project.id, project.teamId)
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
        val selection = _analyticsState.value
        val range = selection.selectedRange
        val environment = selection.selectedEnvironment
        val key = AnalyticsCacheKey(project.id, project.teamId, range, environment)
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
                        unlockLongAnalyticsHistory()
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

    private fun unlockLongAnalyticsHistory() {
        _analyticsState.update { it.copy(hasLongAnalyticsHistory = true) }
        if (hasLongAnalyticsHistory()) return
        longHistoryUnlocked = true
        viewModelScope.launch { gateway.markLongAnalyticsHistoryAvailable() }
    }

    private fun hasLongAnalyticsHistory(): Boolean =
        longHistoryUnlocked || _uiState.value.dashboard?.account?.hasLongAnalyticsHistory == true

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

    private fun clearProjectCaches() {
        analyticsCache.clear()
        contextCache.clear()
        eventsCache.clear()
        longHistoryUnlocked = false
    }

    private fun launchMutation(
        mutation: VercelConnectionMutation,
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
                        error = null,
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
        _uiState.value = when (restored) {
            VercelRestoreUi.NoSavedAccount -> VercelConnectionUiState(
                status = VercelConnectionStatus.DISCONNECTED,
            )

            is VercelRestoreUi.Available -> connectedState(restored.dashboard)
            is VercelRestoreUi.DashboardUnavailable -> VercelConnectionUiState(
                status = VercelConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                error = messageOf(restored.error),
            )
        }
    }

    private fun connectedState(
        dashboard: VercelDashboardUi,
        mutation: VercelConnectionMutation? = null,
    ): VercelConnectionUiState = VercelConnectionUiState(
        status = VercelConnectionStatus.CONNECTED,
        dashboard = dashboard,
        savedAccount = dashboard.account,
        mutation = mutation,
        lastUpdatedMillis = nowMillis(),
    )

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
        val projectId: String,
        val teamId: String?,
        val range: VercelAnalyticsRange,
        val environment: VercelAnalyticsEnvironment,
    )

    private data class ProjectCacheKey(
        val projectId: String,
        val teamId: String?,
    )

    private data class EventsCacheKey(
        val teamId: String?,
        val deploymentIdentifier: String,
    )

    private companion object {
        const val ANALYTICS_SELECTION_DEBOUNCE_MILLIS = 250L
    }
}
