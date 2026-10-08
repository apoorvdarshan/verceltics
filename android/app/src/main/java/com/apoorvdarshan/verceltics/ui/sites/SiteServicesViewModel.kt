package com.apoorvdarshan.verceltics.ui.sites

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.apoorvdarshan.verceltics.data.sites.ClarityDimensionOptions
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SiteServiceConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class SiteServiceOperation {
    RESTORING,
    CONNECTING,
    AUTHORIZING,
    REFRESHING,
    DISCONNECTING,
}

data class SiteServiceState(
    val providerId: String,
    val status: SiteServiceConnectionStatus = SiteServiceConnectionStatus.RESTORING,
    val dashboard: SiteServiceDashboardUi? = null,
    val savedAccountName: String? = null,
    val operation: SiteServiceOperation? = SiteServiceOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val showDisconnectConfirmation: Boolean = false,
) {
    val isBusy: Boolean get() = operation != null

    val isConnected: Boolean
        get() = status == SiteServiceConnectionStatus.CONNECTED || status == SiteServiceConnectionStatus.SAVED_UNAVAILABLE
}

data class SiteServiceDetailState(
    val providerId: String,
    val resourceId: String?,
    val query: SiteServiceDetailQueryUi = SiteServiceDetailQueryUi(),
    val payload: SiteServiceDetailUi? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val showRawResponses: Boolean = false,
)

data class SiteServicesUiState(
    val googleOAuthReadiness: SiteGoogleOAuthReadinessUi,
    val services: Map<String, SiteServiceState> = SiteServiceProviderIds.associateWith { SiteServiceState(it) },
    /** The provider whose route is currently on screen, if any. */
    val activeProviderId: String? = null,
    val resourceSearch: String = "",
    val detail: SiteServiceDetailState? = null,
) {
    fun service(providerId: String): SiteServiceState = services[providerId]
        ?: SiteServiceState(providerId, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null)

    val isRestoring: Boolean
        get() = services.values.any { it.status == SiteServiceConnectionStatus.RESTORING }
}

/** Services the Sites tab should show as connected (including saved-but-unavailable records). */
val SiteServicesUiState.connectedProviderIds: Set<String>
    get() = SiteServiceProviderIds.filterTo(LinkedHashSet()) { service(it).isConnected }

/**
 * The activity applies FLAG_SECURE while a credential can be typed or is in flight: the visible
 * API-key form of a disconnected service, or any connect / Google authorization in progress.
 */
val SiteServicesUiState.requiresSecureWindow: Boolean
    get() {
        val providerId = activeProviderId ?: return false
        val service = service(providerId)
        val usesOAuth = SiteProvider.fromId(providerId)?.usesGoogleOAuth == true
        return (service.status == SiteServiceConnectionStatus.DISCONNECTED && !usesOAuth) ||
            service.operation == SiteServiceOperation.CONNECTING ||
            service.operation == SiteServiceOperation.AUTHORIZING
    }

/** Activity-scoped owner for every site service; detail selection survives recreation. */
class SiteServicesViewModel(
    private val gateway: SiteServicesUiGateway,
    private val savedStateHandle: SavedStateHandle,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SiteServicesUiState(gateway.googleOAuthReadiness))
    val uiState: StateFlow<SiteServicesUiState> = _uiState.asStateFlow()

    private var restoreJob: Job? = null
    private var restoreGeneration = 0L
    private val operationJobs = HashMap<String, Job>()
    private val operationGenerations = HashMap<String, Long>()
    private val operationBaselines = HashMap<String, SiteServiceState>()
    private val lastAutomaticRefresh = HashMap<String, Long>()
    private var detailJob: Job? = null
    private var detailGeneration = 0L
    private var isForeground = false
    private var lastRouteProviderId: String? = null

    init {
        restore()
    }

    // MARK: Restore and lifecycle

    fun restore() {
        restoreJob?.cancel()
        operationJobs.values.forEach(Job::cancel)
        operationJobs.clear()
        operationBaselines.clear()
        SiteServiceProviderIds.forEach(::nextGeneration)
        val generation = ++restoreGeneration
        _uiState.update {
            SiteServicesUiState(
                googleOAuthReadiness = gateway.googleOAuthReadiness,
                activeProviderId = it.activeProviderId,
            )
        }
        restoreJob = viewModelScope.launch {
            val result = gateway.restore()
            if (generation != restoreGeneration) return@launch
            result.fold(
                onSuccess = { restored ->
                    _uiState.update { state ->
                        state.copy(
                            services = SiteServiceProviderIds.associateWith { id ->
                                restoredState(id, restored.services[id] ?: SiteServiceRestoreUi.NotConnected)
                            },
                        )
                    }
                },
                onFailure = { error ->
                    // A failed restore does not prove a saved connection exists.
                    _uiState.update { state ->
                        state.copy(
                            services = SiteServiceProviderIds.associateWith { id ->
                                SiteServiceState(
                                    providerId = id,
                                    status = SiteServiceConnectionStatus.DISCONNECTED,
                                    operation = null,
                                    error = safeMessage(error, id),
                                )
                            },
                        )
                    }
                },
            )
            restoreJob = null
            reopenSavedDetail()
            refreshStaleServicesIfForeground()
        }
    }

    fun onForeground() {
        isForeground = true
        refreshStaleServicesIfForeground()
    }

    fun onBackground() {
        isForeground = false
    }

    /** Called by [SiteServiceRoute] while a provider route is on screen (null when it leaves). */
    fun setActiveRoute(providerId: String?) {
        val state = _uiState.value
        if (state.activeProviderId == providerId) return
        // Another provider's route replaces a detail workspace; leaving (null) keeps it so a
        // configuration change or a return to the same provider restores it.
        val closesDetail = providerId != null && state.detail != null && state.detail.providerId != providerId
        if (closesDetail) {
            cancelDetail()
            clearSavedDetail()
        }
        val resetSearch = providerId != null && providerId != lastRouteProviderId
        if (providerId != null) lastRouteProviderId = providerId
        _uiState.update {
            it.copy(
                activeProviderId = providerId,
                resourceSearch = if (resetSearch) "" else it.resourceSearch,
                detail = if (closesDetail) null else it.detail,
            )
        }
    }

    // MARK: Connection operations

    fun connect(providerId: String, input: SiteServiceConnectionInputUi) {
        val baseline = service(providerId)
        if (baseline.isBusy || baseline.status != SiteServiceConnectionStatus.DISCONNECTED) return
        launchOperation(providerId, SiteServiceOperation.CONNECTING, baseline.cleared()) { generation ->
            gateway.connect(providerId, input).fold(
                onSuccess = { dashboard -> if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard) },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setService(providerId, baseline.cleared().copy(error = safeMessage(error, providerId)))
                    }
                },
            )
        }
    }

    fun connectGoogle(providerId: String) {
        val baseline = service(providerId)
        if (baseline.isBusy || baseline.status != SiteServiceConnectionStatus.DISCONNECTED) return
        if (_uiState.value.googleOAuthReadiness !is SiteGoogleOAuthReadinessUi.Ready) return
        launchOperation(providerId, SiteServiceOperation.AUTHORIZING, baseline.cleared()) { generation ->
            gateway.connectGoogle(providerId).fold(
                onSuccess = { dashboard -> if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard) },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setService(providerId, baseline.cleared().copy(error = safeMessage(error, providerId)))
                    }
                },
            )
        }
    }

    fun refresh(providerId: String) {
        val baseline = service(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        launchOperation(providerId, SiteServiceOperation.REFRESHING, baseline.cleared()) { generation ->
            gateway.refresh(providerId).fold(
                onSuccess = { dashboard -> if (isCurrent(providerId, generation)) applyDashboard(providerId, dashboard) },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setService(
                            providerId,
                            baseline.cleared().copy(
                                error = safeMessage(error, providerId),
                                notice = baseline.dashboard?.let {
                                    "Showing the last saved ${displayName(providerId)} data."
                                },
                            ),
                        )
                    }
                },
            )
        }
    }

    fun cancelOperation(providerId: String) {
        val current = service(providerId)
        val operation = current.operation
        if (operation != SiteServiceOperation.CONNECTING &&
            operation != SiteServiceOperation.AUTHORIZING &&
            operation != SiteServiceOperation.REFRESHING
        ) {
            return
        }
        val baseline = operationBaselines.remove(providerId) ?: current.copy(operation = null)
        val generation = nextGeneration(providerId)
        val cancelled = operationJobs.remove(providerId)
        cancelled?.cancel()
        if (operation == SiteServiceOperation.REFRESHING) {
            setService(providerId, baseline.copy(operation = null, notice = "Refresh cancelled."))
            return
        }
        val authorizing = operation == SiteServiceOperation.AUTHORIZING
        setService(
            providerId,
            baseline.copy(
                operation = SiteServiceOperation.RESTORING,
                notice = if (authorizing) "Cancelling Google authorization…" else "Cancelling request…",
            ),
        )
        operationJobs[providerId] = viewModelScope.launch {
            cancelled?.join()
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (!isCurrent(providerId, generation)) return@fold
                    val result = restored.services[providerId] ?: SiteServiceRestoreUi.NotConnected
                    setService(
                        providerId,
                        restoredState(providerId, result).copy(
                            notice = when {
                                result is SiteServiceRestoreUi.Available ->
                                    "The connection completed before cancellation and remains saved."
                                authorizing -> "Google authorization cancelled."
                                else -> "Request cancelled."
                            },
                        ),
                    )
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setService(
                            providerId,
                            baseline.copy(
                                operation = null,
                                error = safeMessage(error, providerId),
                                notice = "Request cancelled; saved connection status could not be verified.",
                            ),
                        )
                    }
                },
            )
            if (isCurrent(providerId, generation)) operationJobs.remove(providerId)
        }
    }

    fun requestDisconnectConfirmation(providerId: String) {
        val current = service(providerId)
        if (current.isConnected && !current.isBusy) setService(providerId, current.copy(showDisconnectConfirmation = true))
    }

    fun dismissDisconnectConfirmation(providerId: String) {
        setService(providerId, service(providerId).copy(showDisconnectConfirmation = false))
    }

    fun confirmDisconnect(providerId: String) {
        val baseline = service(providerId)
        if (!baseline.isConnected || baseline.isBusy) return
        if (_uiState.value.detail?.providerId == providerId) closeDetail()
        launchOperation(providerId, SiteServiceOperation.DISCONNECTING, baseline.cleared()) { generation ->
            gateway.disconnect(providerId).fold(
                onSuccess = {
                    if (isCurrent(providerId, generation)) {
                        setService(
                            providerId,
                            SiteServiceState(providerId, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null),
                        )
                    }
                },
                onFailure = { error ->
                    if (isCurrent(providerId, generation)) {
                        setService(providerId, baseline.cleared().copy(error = safeMessage(error, providerId)))
                    }
                },
            )
        }
    }

    fun updateResourceSearch(text: String) {
        _uiState.update { it.copy(resourceSearch = text.take(MAXIMUM_SEARCH_CHARACTERS)) }
    }

    fun clearFeedback(providerId: String) {
        setService(providerId, service(providerId).copy(error = null, notice = null))
    }

    // MARK: Detail workspace

    /** Opens a service (null [resourceId]) or a specific resource. The route gates this behind Pro. */
    fun openDetail(providerId: String, resourceId: String? = null) {
        val dashboard = service(providerId).dashboard ?: return
        val resolved = resourceId?.takeIf { id -> dashboard.resources.any { it.id == id } }
            ?: dashboard.resources.firstOrNull()?.id
        val previous = _uiState.value.detail?.takeIf { it.providerId == providerId }
        _uiState.update {
            it.copy(
                detail = SiteServiceDetailState(
                    providerId = providerId,
                    resourceId = resolved,
                    query = previous?.query ?: SiteServiceDetailQueryUi(),
                ),
            )
        }
        persistDetail()
        loadDetail(forceRefresh = false, debounceMillis = 0)
    }

    fun closeDetail() {
        cancelDetail()
        _uiState.update { it.copy(detail = null) }
        clearSavedDetail()
    }

    fun refreshDetail() {
        if (_uiState.value.detail != null) loadDetail(forceRefresh = true, debounceMillis = 0)
    }

    fun selectDetailResource(resourceId: String) {
        val detail = _uiState.value.detail ?: return
        if (detail.resourceId == resourceId) return
        if (service(detail.providerId).dashboard?.resources?.none { it.id == resourceId } != false) return
        updateDetailQuery(detail.copy(resourceId = resourceId))
    }

    fun selectDetailRange(preset: SiteDetailRangePresetUi) {
        val detail = _uiState.value.detail ?: return
        if (detail.query.preset == preset) return
        updateDetailQuery(detail.copy(query = detail.query.copy(preset = preset)))
    }

    fun setCustomDetailRange(start: LocalDate, end: LocalDate, today: LocalDate = LocalDate.now()) {
        val detail = _uiState.value.detail ?: return
        val boundedEnd = minOf(end, today)
        val boundedStart = minOf(start, boundedEnd)
        updateDetailQuery(
            detail.copy(
                query = detail.query.copy(
                    preset = SiteDetailRangePresetUi.CUSTOM,
                    customStartDate = boundedStart.toString(),
                    customEndDate = boundedEnd.toString(),
                ),
            ),
        )
    }

    fun setClarityDays(days: Int) {
        val detail = _uiState.value.detail ?: return
        val bounded = days.coerceIn(1, 3)
        if (detail.query.clarityDays == bounded) return
        updateDetailQuery(detail.copy(query = detail.query.copy(clarityDays = bounded)))
    }

    fun toggleClarityDimension(dimension: String) {
        val detail = _uiState.value.detail ?: return
        if (dimension !in ClarityDimensionOptions) return
        val current = detail.query.clarityDimensions
        val updated = when {
            dimension in current -> current - dimension
            current.size < 3 -> current + dimension
            else -> return
        }
        updateDetailQuery(detail.copy(query = detail.query.copy(clarityDimensions = updated)))
    }

    fun showRawResponses(show: Boolean) {
        _uiState.update { state -> state.copy(detail = state.detail?.copy(showRawResponses = show)) }
    }

    /** Returns true when the route consumed back instead of asking the app shell to close it. */
    fun handleBack(): Boolean {
        val state = _uiState.value
        val active = state.activeProviderId
        state.detail?.takeIf { it.showRawResponses }?.let {
            showRawResponses(false)
            return true
        }
        if (active != null && state.service(active).showDisconnectConfirmation) {
            dismissDisconnectConfirmation(active)
            return true
        }
        if (state.detail != null && (active == null || state.detail.providerId == active)) {
            closeDetail()
            return true
        }
        return false
    }

    private fun updateDetailQuery(updated: SiteServiceDetailState) {
        _uiState.update { it.copy(detail = updated.copy(payload = null, error = null)) }
        persistDetail()
        loadDetail(forceRefresh = false, debounceMillis = QUERY_DEBOUNCE_MILLIS)
    }

    private fun loadDetail(forceRefresh: Boolean, debounceMillis: Long) {
        val detail = _uiState.value.detail ?: return
        val generation = ++detailGeneration
        detailJob?.cancel()
        _uiState.update { state ->
            state.copy(
                detail = state.detail?.copy(
                    isLoading = state.detail.payload == null,
                    isRefreshing = state.detail.payload != null,
                    error = null,
                ),
            )
        }
        val request = SiteServiceDetailRequestUi(detail.providerId, detail.resourceId, detail.query)
        detailJob = viewModelScope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            val result = gateway.loadDetail(request, forceRefresh) { partial ->
                if (generation == detailGeneration) {
                    _uiState.update { state ->
                        state.copy(detail = state.detail?.copy(payload = partial, isLoading = false, isRefreshing = true))
                    }
                }
            }
            if (generation != detailGeneration) return@launch
            result.fold(
                onSuccess = { payload ->
                    _uiState.update { state ->
                        state.copy(
                            detail = state.detail?.copy(payload = payload, isLoading = false, isRefreshing = false, error = null),
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { state ->
                        state.copy(
                            detail = state.detail?.copy(
                                isLoading = false,
                                isRefreshing = false,
                                error = safeMessage(error, detail.providerId),
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun cancelDetail() {
        detailGeneration += 1
        detailJob?.cancel()
        detailJob = null
    }

    private fun persistDetail() {
        val detail = _uiState.value.detail ?: return clearSavedDetail()
        savedStateHandle[DETAIL_PROVIDER] = detail.providerId
        savedStateHandle[DETAIL_RESOURCE] = detail.resourceId
        savedStateHandle[DETAIL_PRESET] = detail.query.preset.name
        savedStateHandle[DETAIL_CUSTOM_START] = detail.query.customStartDate
        savedStateHandle[DETAIL_CUSTOM_END] = detail.query.customEndDate
        savedStateHandle[DETAIL_CLARITY_DAYS] = detail.query.clarityDays
        savedStateHandle[DETAIL_CLARITY_DIMENSIONS] = ArrayList(detail.query.clarityDimensions)
    }

    private fun clearSavedDetail() {
        listOf(
            DETAIL_PROVIDER, DETAIL_RESOURCE, DETAIL_PRESET, DETAIL_CUSTOM_START,
            DETAIL_CUSTOM_END, DETAIL_CLARITY_DAYS, DETAIL_CLARITY_DIMENSIONS,
        ).forEach { savedStateHandle.remove<Any>(it) }
    }

    /** Reopens a detail workspace restored from saved state once its service has a dashboard. */
    private fun reopenSavedDetail() {
        val providerId: String = savedStateHandle[DETAIL_PROVIDER] ?: return
        val dashboard = service(providerId).dashboard ?: return clearSavedDetail()
        val query = runCatching {
            SiteServiceDetailQueryUi(
                preset = savedStateHandle.get<String>(DETAIL_PRESET)
                    ?.let { name -> SiteDetailRangePresetUi.entries.firstOrNull { it.name == name } }
                    ?: SiteDetailRangePresetUi.DAYS_30,
                customStartDate = savedStateHandle[DETAIL_CUSTOM_START] ?: LocalDate.now().minusDays(29).toString(),
                customEndDate = savedStateHandle[DETAIL_CUSTOM_END] ?: LocalDate.now().toString(),
                clarityDays = savedStateHandle[DETAIL_CLARITY_DAYS] ?: 3,
                clarityDimensions = savedStateHandle.get<ArrayList<String>>(DETAIL_CLARITY_DIMENSIONS)
                    ?.filter { it in ClarityDimensionOptions }?.distinct()?.take(3).orEmpty(),
            )
        }.getOrDefault(SiteServiceDetailQueryUi())
        val resourceId = savedStateHandle.get<String>(DETAIL_RESOURCE)
            ?.takeIf { id -> dashboard.resources.any { it.id == id } }
            ?: dashboard.resources.firstOrNull()?.id
        _uiState.update { it.copy(detail = SiteServiceDetailState(providerId, resourceId, query)) }
        persistDetail()
        loadDetail(forceRefresh = false, debounceMillis = 0)
    }

    // MARK: Helpers

    private fun refreshStaleServicesIfForeground() {
        if (!isForeground || restoreJob?.isActive == true) return
        val now = nowMillis()
        SiteServiceProviderIds.forEach { providerId ->
            val service = service(providerId)
            if (service.isBusy) return@forEach
            val provider = SiteProvider.fromId(providerId) ?: return@forEach
            val dashboard = service.dashboard
            val stale = when {
                service.status == SiteServiceConnectionStatus.CONNECTED && dashboard != null ->
                    dashboard.cacheState == SiteServiceCacheState.CACHED_STALE ||
                        now - dashboard.fetchedAtMillis >= provider.snapshotCacheLifetimeMillis
                service.status == SiteServiceConnectionStatus.SAVED_UNAVAILABLE ->
                    dashboard == null && service.savedAccountName != null && service.error == null
                else -> false
            }
            val lastAttempt = lastAutomaticRefresh[providerId]
            if (stale && (lastAttempt == null || now - lastAttempt >= MINIMUM_AUTOMATIC_REFRESH_SPACING_MILLIS)) {
                lastAutomaticRefresh[providerId] = now
                refresh(providerId)
            }
        }
    }

    private fun restoredState(providerId: String, restored: SiteServiceRestoreUi): SiteServiceState = when (restored) {
        SiteServiceRestoreUi.NotConnected ->
            SiteServiceState(providerId, status = SiteServiceConnectionStatus.DISCONNECTED, operation = null)
        is SiteServiceRestoreUi.Available -> SiteServiceState(
            providerId = providerId,
            status = SiteServiceConnectionStatus.CONNECTED,
            dashboard = restored.dashboard,
            savedAccountName = restored.dashboard.accountName,
            operation = null,
        )
        is SiteServiceRestoreUi.SavedWithoutInventory -> SiteServiceState(
            providerId = providerId,
            status = SiteServiceConnectionStatus.SAVED_UNAVAILABLE,
            savedAccountName = restored.accountName,
            operation = null,
            notice = "This connection has no saved data yet. Refresh when you are online.",
        )
        is SiteServiceRestoreUi.SavedUnavailable -> SiteServiceState(
            providerId = providerId,
            status = SiteServiceConnectionStatus.SAVED_UNAVAILABLE,
            operation = null,
            error = restored.message,
        )
    }

    private fun applyDashboard(providerId: String, dashboard: SiteServiceDashboardUi) {
        setService(
            providerId,
            SiteServiceState(
                providerId = providerId,
                status = SiteServiceConnectionStatus.CONNECTED,
                dashboard = dashboard,
                savedAccountName = dashboard.accountName,
                operation = null,
            ),
        )
        val detail = _uiState.value.detail
        if (detail?.providerId == providerId && detail.resourceId != null &&
            dashboard.resources.none { it.id == detail.resourceId }
        ) {
            closeDetail()
        }
    }

    private fun launchOperation(
        providerId: String,
        operation: SiteServiceOperation,
        baseline: SiteServiceState,
        block: suspend (generation: Long) -> Unit,
    ) {
        if (operationJobs[providerId]?.isActive == true) return
        val generation = nextGeneration(providerId)
        operationBaselines[providerId] = baseline
        setService(providerId, baseline.copy(operation = operation, error = null, notice = null, showDisconnectConfirmation = false))
        operationJobs[providerId] = viewModelScope.launch {
            try {
                block(generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrent(providerId, generation)) {
                    setService(providerId, baseline.copy(operation = null, error = safeMessage(error, providerId)))
                }
            } finally {
                if (isCurrent(providerId, generation)) {
                    operationJobs.remove(providerId)
                    operationBaselines.remove(providerId)
                }
            }
        }
    }

    private fun nextGeneration(providerId: String): Long =
        ((operationGenerations[providerId] ?: 0L) + 1).also { operationGenerations[providerId] = it }

    private fun isCurrent(providerId: String, generation: Long): Boolean = operationGenerations[providerId] == generation

    private fun service(providerId: String): SiteServiceState = _uiState.value.service(providerId)

    private fun setService(providerId: String, service: SiteServiceState) {
        _uiState.update { state -> state.copy(services = state.services + (providerId to service)) }
    }

    private fun SiteServiceState.cleared(): SiteServiceState =
        copy(operation = null, error = null, notice = null, showDisconnectConfirmation = false)

    private fun displayName(providerId: String): String = SiteProvider.fromId(providerId)?.displayName ?: "site service"

    private fun safeMessage(error: Throwable, providerId: String?): String =
        (error as? SiteServicesUiException)?.message
            ?: "${providerId?.let(::displayName) ?: "The site service"} could not complete this request."

    override fun onCleared() {
        restoreGeneration += 1
        cancelDetail()
        operationJobs.values.forEach(Job::cancel)
        super.onCleared()
    }

    class Factory(
        private val gateway: SiteServicesUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SiteServicesViewModel::class.java)) {
                "Unsupported site services ViewModel class."
            }
            return SiteServicesViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val DETAIL_PROVIDER = "sites.detail.providerId"
        internal const val DETAIL_RESOURCE = "sites.detail.resourceId"
        internal const val DETAIL_PRESET = "sites.detail.preset"
        internal const val DETAIL_CUSTOM_START = "sites.detail.customStart"
        internal const val DETAIL_CUSTOM_END = "sites.detail.customEnd"
        internal const val DETAIL_CLARITY_DAYS = "sites.detail.clarityDays"
        internal const val DETAIL_CLARITY_DIMENSIONS = "sites.detail.clarityDimensions"
        internal const val QUERY_DEBOUNCE_MILLIS = 180L
        private const val MINIMUM_AUTOMATIC_REFRESH_SPACING_MILLIS = 60_000L
        private const val MAXIMUM_SEARCH_CHARACTERS = 256
    }
}
