package com.apoorvdarshan.verceltics.ui.searchconsole

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchConsoleConnectionStatus {
    RESTORING,
    DISCONNECTED,
    CONNECTED,
    SAVED_UNAVAILABLE,
}

enum class SearchConsoleOperation {
    RESTORING,
    AUTHORIZING,
    REFRESHING,
    DISCONNECTING,
}

enum class SearchConsoleDetailSection {
    PERFORMANCE,
    SITEMAPS,
    INSPECT,
}

data class SearchConsoleUiState(
    val oauthReadiness: SearchConsoleOAuthReadinessUi,
    val status: SearchConsoleConnectionStatus = SearchConsoleConnectionStatus.RESTORING,
    val dashboard: SearchConsoleDashboardUi? = null,
    val savedAccount: SearchConsoleAccountUi? = null,
    val operation: SearchConsoleOperation? = SearchConsoleOperation.RESTORING,
    val error: String? = null,
    val notice: String? = null,
    val propertySearch: String = "",
    val showPropertySwitcher: Boolean = false,
    val shouldFocusPropertySearch: Boolean = false,
    val selectedPropertyUrl: String? = null,
    val selectedSection: SearchConsoleDetailSection = SearchConsoleDetailSection.PERFORMANCE,
    val propertyWorkspace: SearchConsolePropertyWorkspaceUi? = null,
    val isLoadingProperty: Boolean = false,
    val propertyError: String? = null,
    val performanceQuery: SearchConsolePerformanceQueryUi = SearchConsolePerformanceQueryUi.default(),
    val selectedPerformanceMetric: SearchConsoleMetricUi = SearchConsoleMetricUi.CLICKS,
    val isLoadingPerformance: Boolean = false,
    val performanceError: String? = null,
    val inspectionUrl: String = "",
    val inspection: SearchConsoleInspectionUi? = null,
    val isInspecting: Boolean = false,
    val inspectionError: String? = null,
    val showDisconnectConfirmation: Boolean = false,
    val routeVisible: Boolean = false,
    val propertySummaries: Map<String, SearchConsolePropertySummaryUi> = emptyMap(),
    val isLoadingPropertySummaries: Boolean = false,
    val propertySummaryError: String? = null,
) {
    val isBusy: Boolean get() = operation != null

    /** iOS overview totals across the verified properties whose summaries have loaded. */
    val overviewTotals: SearchConsoleOverviewTotalsUi?
        get() {
            val properties = dashboard?.properties ?: return null
            if (propertySummaries.isEmpty()) return null
            val visible = properties.mapNotNull { propertySummaries[it.siteUrl] }
            return aggregateSearchConsoleSummaries(properties.size, visible)
        }

    val performance: SearchConsolePerformanceUi?
        get() = (propertyWorkspace?.performance as? SearchConsoleResourceUi.Available)?.value

    val isConnected: Boolean
        get() = status == SearchConsoleConnectionStatus.CONNECTED ||
            status == SearchConsoleConnectionStatus.SAVED_UNAVAILABLE

    val requiresSecureWindow: Boolean
        get() = routeVisible && operation == SearchConsoleOperation.AUTHORIZING

    val visibleProperties: List<SearchConsolePropertyUi>
        get() {
            val query = propertySearch.trim()
            val properties = dashboard?.properties.orEmpty()
            if (query.isEmpty()) return properties
            return properties.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.siteUrl.contains(query, ignoreCase = true) ||
                    it.permission.contains(query, ignoreCase = true)
            }
        }
}

class SearchConsoleViewModel(
    private val gateway: SearchConsoleUiGateway,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SearchConsoleUiState(
            oauthReadiness = gateway.oauthReadiness,
            selectedPropertyUrl = savedStateHandle[SELECTED_PROPERTY_URL],
            selectedSection = restoredSection(),
        ),
    )
    val uiState: StateFlow<SearchConsoleUiState> = _uiState.asStateFlow()

    private var operationJob: Job? = null
    private var operationGeneration = 0L
    private var operationBaseline: SearchConsoleUiState? = null
    private var propertyJob: Job? = null
    private var propertyGeneration = 0L
    private var performanceJob: Job? = null
    private var performanceGeneration = 0L
    private var inspectionJob: Job? = null
    private var inspectionGeneration = 0L
    private var summaryJob: Job? = null
    private var summaryGeneration = 0L
    private var isForeground = false
    private var restoredCacheNeedsRefresh = false
    private var restoredCacheRefreshStarted = false

    init {
        restore()
    }

    fun setRouteVisible(visible: Boolean) {
        _uiState.update { if (it.routeVisible == visible) it else it.copy(routeVisible = visible) }
    }

    fun restore() {
        cancelRootOperation(resetState = false)
        cancelPropertySummaries()
        restoredCacheNeedsRefresh = false
        restoredCacheRefreshStarted = false
        val generation = ++operationGeneration
        operationJob = viewModelScope.launch {
            _uiState.update {
                SearchConsoleUiState(
                    oauthReadiness = gateway.oauthReadiness,
                    selectedPropertyUrl = savedStateHandle[SELECTED_PROPERTY_URL],
                    selectedSection = restoredSection(),
                    routeVisible = it.routeVisible,
                )
            }
            gateway.restore().fold(
                onSuccess = { restored ->
                    if (isCurrent(generation)) {
                        applyRestore(restored)
                        restoredCacheNeedsRefresh = restored is SearchConsoleRestoreUi.Available
                    }
                },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.update {
                            it.copy(
                                // A failed restore does not prove that a durable connection exists.
                                // Known saved-but-unavailable states cross the gateway as
                                // SearchConsoleRestoreUi.SavedUnavailable instead.
                                status = SearchConsoleConnectionStatus.DISCONNECTED,
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

    fun connect() {
        val baseline = _uiState.value
        if (baseline.isBusy || baseline.oauthReadiness !is SearchConsoleOAuthReadinessUi.Ready) return
        launchRootOperation(SearchConsoleOperation.AUTHORIZING, baseline) { generation ->
            gateway.connect().fold(
                onSuccess = { dashboard -> if (isCurrent(generation)) applyDashboard(dashboard) },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(operation = null, error = safeMessage(error))
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
        launchRootOperation(SearchConsoleOperation.REFRESHING, baseline) { generation ->
            gateway.refresh().fold(
                onSuccess = { dashboard -> if (isCurrent(generation)) applyDashboard(dashboard) },
                onFailure = { error ->
                    if (isCurrent(generation)) {
                        _uiState.value = baseline.copy(
                            operation = null,
                            error = safeMessage(error),
                            notice = if (baseline.dashboard != null) {
                                "Showing the last saved Search Console property list."
                            } else {
                                null
                            },
                        )
                    }
                },
            )
        }
    }

    fun cancelOperation() {
        val operation = _uiState.value.operation
        if (operation != SearchConsoleOperation.AUTHORIZING &&
            operation != SearchConsoleOperation.REFRESHING
        ) {
            return
        }
        val baseline = operationBaseline ?: return
        val visible = _uiState.value.routeVisible
        operationGeneration += 1
        val cancelled = operationJob
        cancelled?.cancel()
        operationJob = null
        operationBaseline = null
        if (operation == SearchConsoleOperation.AUTHORIZING) {
            val generation = operationGeneration
            _uiState.value = baseline.copy(
                operation = SearchConsoleOperation.RESTORING,
                notice = "Cancelling Google authorization…",
                routeVisible = visible,
            )
            operationJob = viewModelScope.launch {
                cancelled?.join()
                gateway.restore().fold(
                    onSuccess = { restored ->
                        if (isCurrent(generation)) {
                            applyRestore(restored)
                            _uiState.update {
                                it.copy(
                                    notice = if (restored is SearchConsoleRestoreUi.Available) {
                                        "Authorization completed before cancellation and remains saved."
                                    } else {
                                        "Google authorization cancelled."
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
                                notice = "Authorization cancelled; saved connection status could not be verified.",
                                routeVisible = visible,
                            )
                        }
                    },
                )
                if (isCurrent(generation)) operationJob = null
            }
        } else {
            _uiState.value = baseline.copy(
                operation = null,
                notice = "Refresh cancelled.",
                routeVisible = visible,
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

    fun updatePropertySearch(value: String) {
        _uiState.update { it.copy(propertySearch = value.take(256)) }
    }

    fun handleSearchRequest(requestId: Int) {
        if (requestId <= 0 || savedStateHandle.get<Int>(HANDLED_SEARCH_REQUEST_ID) == requestId) return
        savedStateHandle[HANDLED_SEARCH_REQUEST_ID] = requestId
        if (_uiState.value.selectedPropertyUrl != null) closeProperty()
        _uiState.update {
            when {
                it.dashboard != null -> it.copy(
                    showPropertySwitcher = false,
                    shouldFocusPropertySearch = true,
                )
                it.status == SearchConsoleConnectionStatus.SAVED_UNAVAILABLE -> it.copy(
                    showPropertySwitcher = false,
                    // Keep the search intent pending. The screen waits for a dashboard before
                    // requesting focus, so a successful recovery lands directly in property search.
                    shouldFocusPropertySearch = true,
                    notice = "Refresh the saved Google connection to search verified properties.",
                )
                it.status == SearchConsoleConnectionStatus.DISCONNECTED -> it.copy(
                    showPropertySwitcher = false,
                    shouldFocusPropertySearch = false,
                    notice = "Connect Google Search Console to search verified properties.",
                )
                else -> it.copy(
                    showPropertySwitcher = false,
                    shouldFocusPropertySearch = false,
                    notice = "Property search will be available after Search Console finishes restoring.",
                )
            }
        }
    }

    fun acknowledgePropertySearchFocus() {
        _uiState.update { it.copy(shouldFocusPropertySearch = false) }
    }

    fun requestPropertySwitcher() {
        if (_uiState.value.selectedPropertyUrl != null) {
            _uiState.update {
                it.copy(showPropertySwitcher = true, shouldFocusPropertySearch = true)
            }
        }
    }

    fun dismissPropertySwitcher() {
        _uiState.update {
            it.copy(showPropertySwitcher = false, shouldFocusPropertySearch = false)
        }
    }

    fun openProperty(siteUrl: String) {
        val current = _uiState.value
        val property = current.dashboard?.properties?.firstOrNull { it.siteUrl == siteUrl }
            ?: return
        val switchingFromDetail = current.selectedPropertyUrl != null
        propertyGeneration += 1
        performanceGeneration += 1
        inspectionGeneration += 1
        propertyJob?.cancel()
        performanceJob?.cancel()
        inspectionJob?.cancel()
        savedStateHandle[SELECTED_PROPERTY_URL] = property.siteUrl
        _uiState.update {
            it.copy(
                selectedPropertyUrl = property.siteUrl,
                showPropertySwitcher = false,
                shouldFocusPropertySearch = false,
                selectedSection = if (switchingFromDetail) {
                    current.selectedSection
                } else {
                    SearchConsoleDetailSection.PERFORMANCE
                },
                propertyWorkspace = null,
                propertyError = null,
                performanceQuery = if (switchingFromDetail) {
                    current.performanceQuery.copy(page = 0)
                } else {
                    SearchConsolePerformanceQueryUi.default()
                },
                isLoadingPerformance = false,
                performanceError = null,
                inspectionUrl = suggestedInspectionUrl(property.siteUrl),
                inspection = null,
                inspectionError = null,
            )
        }
        savedStateHandle[SELECTED_SECTION] = _uiState.value.selectedSection.name
        loadSelectedProperty(property)
    }

    fun refreshSelectedProperty() {
        val property = selectedProperty() ?: return
        if (!_uiState.value.isLoadingProperty) loadSelectedProperty(property)
    }

    fun applyPerformanceQuery(query: SearchConsolePerformanceQueryUi) {
        val property = selectedProperty() ?: return
        val normalized = query.normalizedForGoogle()
        val current = _uiState.value
        // Sorting and paging reorder rows already on device, exactly like iOS; only a change to
        // what Google is asked for starts a new request.
        val sameRowsAvailableOrComing = current.performance != null ||
            current.isLoadingPerformance || current.isLoadingProperty
        if (sameRowsAvailableOrComing && current.performanceQuery.requestsSameData(normalized)) {
            _uiState.update { it.copy(performanceQuery = normalized) }
            return
        }
        if (current.isLoadingPerformance) performanceJob?.cancel()
        performanceGeneration += 1
        val generation = performanceGeneration
        _uiState.update {
            it.copy(
                performanceQuery = normalized,
                isLoadingPerformance = true,
                performanceError = null,
            )
        }
        performanceJob = viewModelScope.launch {
            gateway.loadPerformance(property.siteUrl, normalized).fold(
                onSuccess = { performance ->
                    if (performanceGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update { state ->
                            state.copy(
                                propertyWorkspace = state.propertyWorkspace?.copy(
                                    performance = performance,
                                ),
                                isLoadingPerformance = false,
                                performanceError = null,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (performanceGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update {
                            it.copy(
                                isLoadingPerformance = false,
                                performanceError = safeMessage(error),
                            )
                        }
                    }
                },
            )
        }
    }

    fun nextPerformancePage() {
        val state = _uiState.value
        val rows = state.performance?.breakdownRows?.size ?: return
        val query = state.performanceQuery
        val lastPage = ((rows + query.pageSize - 1) / query.pageSize - 1).coerceAtLeast(0)
        if (query.page < lastPage) _uiState.update { it.copy(performanceQuery = query.copy(page = query.page + 1)) }
    }

    fun previousPerformancePage() {
        val query = _uiState.value.performanceQuery
        if (query.page > 0) _uiState.update { it.copy(performanceQuery = query.copy(page = query.page - 1)) }
    }

    /** Column header / sort menu (iOS `toggleSort`); reorders loaded rows without a request. */
    fun toggleBreakdownSort(field: SearchConsoleSortFieldUi) {
        _uiState.update { it.copy(performanceQuery = it.performanceQuery.withSortToggled(field)) }
    }

    fun selectDatePreset(preset: SearchConsoleDatePresetUi) {
        val query = _uiState.value.performanceQuery
        if (preset == SearchConsoleDatePresetUi.CUSTOM || query.preset == preset) return
        applyPerformanceQuery(query.withPreset(preset))
    }

    fun selectSearchType(type: SearchConsoleSearchTypeUi) {
        val query = _uiState.value.performanceQuery
        if (query.searchType != type) applyPerformanceQuery(query.withSearchType(type))
    }

    fun selectBreakdownDimension(dimension: SearchConsoleDimensionUi) {
        val query = _uiState.value.performanceQuery
        if (query.dimensions != listOf(dimension)) applyPerformanceQuery(query.withSingleDimension(dimension))
    }

    fun toggleBreakdownDimension(dimension: SearchConsoleDimensionUi) {
        val query = _uiState.value.performanceQuery
        val updated = query.withToggledDimension(dimension)
        if (updated != query) applyPerformanceQuery(updated)
    }

    fun selectDataState(state: SearchConsoleDataStateUi) {
        val query = _uiState.value.performanceQuery
        if (query.dataState != state) applyPerformanceQuery(query.withDataState(state))
    }

    fun selectAggregation(aggregation: SearchConsoleAggregationUi) {
        val query = _uiState.value.performanceQuery
        val updated = query.withAggregation(aggregation)
        if (updated.aggregation != query.aggregation) applyPerformanceQuery(updated)
    }

    fun applyPerformanceFilters(filters: List<SearchConsoleFilterUi>) {
        val query = _uiState.value.performanceQuery
        if (query.filters != filters) applyPerformanceQuery(query.withFilters(filters))
    }

    fun removePerformanceFilter(index: Int) {
        val query = _uiState.value.performanceQuery
        if (index in query.filters.indices) {
            applyPerformanceQuery(query.withFilters(query.filters.filterIndexed { item, _ -> item != index }))
        }
    }

    fun retryPropertySummaries() {
        _uiState.value.dashboard?.let(::loadPropertySummaries)
    }

    fun selectPerformanceMetric(metric: SearchConsoleMetricUi) {
        _uiState.update { it.copy(selectedPerformanceMetric = metric) }
    }

    fun selectSection(section: SearchConsoleDetailSection) {
        savedStateHandle[SELECTED_SECTION] = section.name
        _uiState.update { it.copy(selectedSection = section) }
    }

    fun updateInspectionUrl(value: String) {
        _uiState.update {
            it.copy(
                inspectionUrl = value.take(8_192),
                inspectionError = null,
            )
        }
    }

    fun inspectUrl() {
        val property = selectedProperty() ?: return
        val inspectionUrl = _uiState.value.inspectionUrl.trim()
        if (inspectionUrl.isEmpty() || _uiState.value.isInspecting) return
        inspectionGeneration += 1
        val generation = inspectionGeneration
        inspectionJob?.cancel()
        _uiState.update {
            it.copy(isInspecting = true, inspection = null, inspectionError = null)
        }
        inspectionJob = viewModelScope.launch {
            gateway.inspect(property.siteUrl, inspectionUrl).fold(
                onSuccess = { inspection ->
                    if (inspectionGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update {
                            it.copy(isInspecting = false, inspection = inspection, inspectionError = null)
                        }
                    }
                },
                onFailure = { error ->
                    if (inspectionGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update {
                            it.copy(isInspecting = false, inspectionError = safeMessage(error))
                        }
                    }
                },
            )
        }
    }

    fun closeProperty() {
        propertyGeneration += 1
        performanceGeneration += 1
        inspectionGeneration += 1
        propertyJob?.cancel()
        performanceJob?.cancel()
        inspectionJob?.cancel()
        propertyJob = null
        performanceJob = null
        inspectionJob = null
        savedStateHandle[SELECTED_PROPERTY_URL] = null
        savedStateHandle[SELECTED_SECTION] = null
        _uiState.update {
            it.copy(
                selectedPropertyUrl = null,
                showPropertySwitcher = false,
                shouldFocusPropertySearch = false,
                selectedSection = SearchConsoleDetailSection.PERFORMANCE,
                propertyWorkspace = null,
                isLoadingProperty = false,
                propertyError = null,
                performanceQuery = SearchConsolePerformanceQueryUi.default(),
                isLoadingPerformance = false,
                performanceError = null,
                inspectionUrl = "",
                inspection = null,
                isInspecting = false,
                inspectionError = null,
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
        closeProperty()
        cancelPropertySummaries()
        launchRootOperation(SearchConsoleOperation.DISCONNECTING, baseline) { generation ->
            gateway.disconnect().fold(
                onSuccess = {
                    if (isCurrent(generation)) {
                        _uiState.value = SearchConsoleUiState(
                            oauthReadiness = gateway.oauthReadiness,
                            status = SearchConsoleConnectionStatus.DISCONNECTED,
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

    fun handleBack(): Boolean = when {
        _uiState.value.showDisconnectConfirmation -> {
            dismissDisconnectConfirmation()
            true
        }
        _uiState.value.showPropertySwitcher -> {
            dismissPropertySwitcher()
            true
        }
        _uiState.value.selectedPropertyUrl != null -> {
            closeProperty()
            true
        }
        else -> false
    }

    fun clearFeedback() {
        _uiState.update {
            it.copy(
                error = null,
                notice = null,
                propertyError = null,
                performanceError = null,
                inspectionError = null,
            )
        }
    }

    private fun applyRestore(restored: SearchConsoleRestoreUi) {
        val current = _uiState.value
        _uiState.value = when (restored) {
            SearchConsoleRestoreUi.NotConnected -> SearchConsoleUiState(
                oauthReadiness = gateway.oauthReadiness,
                status = SearchConsoleConnectionStatus.DISCONNECTED,
                operation = null,
                routeVisible = current.routeVisible,
            )
            is SearchConsoleRestoreUi.Available -> SearchConsoleUiState(
                oauthReadiness = gateway.oauthReadiness,
                status = SearchConsoleConnectionStatus.CONNECTED,
                dashboard = restored.dashboard,
                savedAccount = restored.dashboard.account,
                operation = null,
                selectedPropertyUrl = restoredSelectedProperty(restored.dashboard),
                selectedSection = restoredSection(),
                routeVisible = current.routeVisible,
            )
            is SearchConsoleRestoreUi.SavedWithoutInventory -> SearchConsoleUiState(
                oauthReadiness = gateway.oauthReadiness,
                status = SearchConsoleConnectionStatus.SAVED_UNAVAILABLE,
                savedAccount = restored.account,
                operation = null,
                notice = "This connection has no saved property list. Refresh when you are online.",
                routeVisible = current.routeVisible,
            )
            is SearchConsoleRestoreUi.SavedUnavailable -> SearchConsoleUiState(
                oauthReadiness = gateway.oauthReadiness,
                status = SearchConsoleConnectionStatus.SAVED_UNAVAILABLE,
                operation = null,
                error = restored.message,
                routeVisible = current.routeVisible,
            )
        }
        selectedProperty()?.let(::loadSelectedProperty)
    }

    private fun applyDashboard(dashboard: SearchConsoleDashboardUi) {
        val current = _uiState.value
        val selected = current.selectedPropertyUrl?.takeIf { siteUrl ->
            dashboard.properties.any { it.siteUrl == siteUrl }
        }
        val selectionWasRemoved = selected == null && current.selectedPropertyUrl != null
        if (selectionWasRemoved) {
            propertyGeneration += 1
            performanceGeneration += 1
            inspectionGeneration += 1
            propertyJob?.cancel()
            performanceJob?.cancel()
            inspectionJob?.cancel()
            propertyJob = null
            performanceJob = null
            inspectionJob = null
            savedStateHandle[SELECTED_PROPERTY_URL] = null
            savedStateHandle[SELECTED_SECTION] = null
        }
        _uiState.value = current.copy(
            status = SearchConsoleConnectionStatus.CONNECTED,
            dashboard = dashboard,
            savedAccount = dashboard.account,
            operation = null,
            error = null,
            notice = null,
            selectedPropertyUrl = selected,
            selectedSection = if (selected == null) {
                SearchConsoleDetailSection.PERFORMANCE
            } else {
                current.selectedSection
            },
            propertyWorkspace = current.propertyWorkspace?.takeIf {
                it.property.siteUrl == selected
            },
            isLoadingProperty = current.isLoadingProperty && selected != null,
            propertyError = current.propertyError?.takeIf { selected != null },
            performanceError = current.performanceError?.takeIf { selected != null },
            isLoadingPerformance = current.isLoadingPerformance && selected != null,
            performanceQuery = if (selected == null) {
                SearchConsolePerformanceQueryUi.default()
            } else {
                current.performanceQuery
            },
            inspectionUrl = current.inspectionUrl.takeIf { selected != null }.orEmpty(),
            inspection = current.inspection?.takeIf { selected != null },
            isInspecting = current.isInspecting && selected != null,
            inspectionError = current.inspectionError?.takeIf { selected != null },
            showDisconnectConfirmation = false,
        )
        // Saved property lists are refreshed on foreground, so overview metrics follow live data.
        if (dashboard.cacheState == SearchConsoleCacheState.LIVE) loadPropertySummaries(dashboard)
    }

    private fun loadPropertySummaries(dashboard: SearchConsoleDashboardUi) {
        summaryGeneration += 1
        val generation = summaryGeneration
        summaryJob?.cancel()
        val siteUrls = dashboard.properties.map(SearchConsolePropertyUi::siteUrl)
        val current = siteUrls.toSet()
        _uiState.update { state ->
            state.copy(
                propertySummaries = state.propertySummaries.filterKeys { it in current },
                isLoadingPropertySummaries = siteUrls.isNotEmpty(),
                propertySummaryError = null,
            )
        }
        if (siteUrls.isEmpty()) return
        summaryJob = viewModelScope.launch {
            val result = gateway.loadPropertySummaries(siteUrls) { summary ->
                if (summaryGeneration == generation && summary.siteUrl in current) {
                    _uiState.update {
                        it.copy(propertySummaries = it.propertySummaries + (summary.siteUrl to summary))
                    }
                }
            }
            if (summaryGeneration == generation) {
                _uiState.update {
                    it.copy(
                        isLoadingPropertySummaries = false,
                        propertySummaryError = result.exceptionOrNull()?.let(::safeMessage),
                    )
                }
            }
        }
    }

    private fun cancelPropertySummaries() {
        summaryGeneration += 1
        summaryJob?.cancel()
        summaryJob = null
    }

    private fun loadSelectedProperty(property: SearchConsolePropertyUi) {
        propertyGeneration += 1
        val generation = propertyGeneration
        propertyJob?.cancel()
        _uiState.update { it.copy(isLoadingProperty = true, propertyError = null) }
        propertyJob = viewModelScope.launch {
            gateway.loadProperty(property, _uiState.value.performanceQuery).fold(
                onSuccess = { workspace ->
                    if (propertyGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update {
                            it.copy(
                                propertyWorkspace = workspace,
                                isLoadingProperty = false,
                                propertyError = null,
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (propertyGeneration == generation &&
                        _uiState.value.selectedPropertyUrl == property.siteUrl
                    ) {
                        _uiState.update {
                            it.copy(isLoadingProperty = false, propertyError = safeMessage(error))
                        }
                    }
                },
            )
        }
    }

    private fun selectedProperty(): SearchConsolePropertyUi? {
        val siteUrl = _uiState.value.selectedPropertyUrl ?: return null
        return _uiState.value.dashboard?.properties?.firstOrNull { it.siteUrl == siteUrl }
    }

    private fun restoredSelectedProperty(dashboard: SearchConsoleDashboardUi): String? {
        val restored: String = savedStateHandle[SELECTED_PROPERTY_URL] ?: return null
        return restored.takeIf { siteUrl -> dashboard.properties.any { it.siteUrl == siteUrl } }
            .also { if (it == null) savedStateHandle[SELECTED_PROPERTY_URL] = null }
    }

    private fun restoredSection(): SearchConsoleDetailSection =
        savedStateHandle.get<String>(SELECTED_SECTION)
            ?.let { runCatching { SearchConsoleDetailSection.valueOf(it) }.getOrNull() }
            ?: SearchConsoleDetailSection.PERFORMANCE

    private fun suggestedInspectionUrl(siteUrl: String): String =
        if (siteUrl.startsWith("http://") || siteUrl.startsWith("https://")) siteUrl else ""

    private fun launchRootOperation(
        operation: SearchConsoleOperation,
        baseline: SearchConsoleUiState,
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

    private fun cancelRootOperation(resetState: Boolean) {
        operationGeneration += 1
        operationJob?.cancel()
        operationJob = null
        operationBaseline = null
        if (resetState) _uiState.update { it.copy(operation = null) }
    }

    private fun isCurrent(generation: Long): Boolean = operationGeneration == generation

    private fun safeMessage(error: Throwable): String =
        (error as? SearchConsoleUiException)?.message
            ?: "Google Search Console could not complete this request."

    override fun onCleared() {
        operationGeneration += 1
        propertyGeneration += 1
        performanceGeneration += 1
        inspectionGeneration += 1
        operationJob?.cancel()
        propertyJob?.cancel()
        performanceJob?.cancel()
        inspectionJob?.cancel()
        cancelPropertySummaries()
        super.onCleared()
    }

    class Factory(
        private val gateway: SearchConsoleUiGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(SearchConsoleViewModel::class.java)) {
                "Unsupported Search Console ViewModel class."
            }
            return SearchConsoleViewModel(gateway, extras.createSavedStateHandle()) as T
        }
    }

    companion object {
        internal const val SELECTED_PROPERTY_URL = "searchConsole.selectedPropertyUrl"
        internal const val SELECTED_SECTION = "searchConsole.selectedSection"
        internal const val HANDLED_SEARCH_REQUEST_ID = "searchConsole.handledSearchRequestId"
    }
}
