import Charts
import SwiftUI

// MARK: - Search Console detail state

@Observable
@MainActor
private final class SearchConsoleDetailViewModel {
    private struct CachedValue<Value> {
        let value: Value
        let updatedAt: Date
    }

    fileprivate struct PerformanceTotals {
        let clicks: Double
        let impressions: Double
        let ctr: Double
        let position: Double

        static let zero = PerformanceTotals(clicks: 0, impressions: 0, ctr: 0, position: 0)
    }

    @ResettableMemoryCache(limit: 12)
    private static var sitesCache: [String: CachedValue<[SearchConsoleSite]>] = [:]
    @ResettableMemoryCache(limit: 12)
    private static var selectedSiteCache: [String: String] = [:]
    @ResettableMemoryCache(limit: 16)
    private static var timelineCache: [String: CachedValue<SearchConsoleAnalyticsResponse>] = [:]
    @ResettableMemoryCache(limit: 16)
    private static var breakdownCache: [String: CachedValue<SearchConsoleAnalyticsResponse>] = [:]
    @ResettableMemoryCache(limit: 12)
    private static var sitemapCache: [String: CachedValue<[SearchConsoleSitemap]>] = [:]
    @ResettableMemoryCache(limit: 16)
    private static var inspectionCache: [String: CachedValue<SearchConsoleURLInspectionResult>] = [:]

    private static let performanceCacheLifetime = DashboardRefreshPolicy.reportFreshness
    private static let supportingDataCacheLifetime = DashboardRefreshPolicy.inventoryFreshness
    private static let maximumBreakdownRows = 100_000

    let account: SiteIntegrationAccount
    private var api: SearchConsoleDetailAPI
    private let refreshAccount: (@MainActor @Sendable () async throws -> SiteIntegrationAccount)?
    private let accountScope: String
    private let preferredSiteURL: String?

    var sites: [SearchConsoleSite] = []
    var selectedSiteURL: String?
    var selectedDatePreset: SearchConsoleDatePreset = .days28
    var customStartDate: Date
    var customEndDate: Date
    var selectedSearchType: SearchConsoleSearchType = .web
    var selectedDimensions: [SearchConsoleDimension] = [.query]
    var selectedAggregationType: SearchConsoleAggregationType = .auto
    var selectedDataState: SearchConsoleDataState = .all
    var filters: [SearchConsoleDimensionFilter] = []
    var chartMetric: SearchConsoleMetricKind = .clicks
    var sortField: SearchConsoleSortField = .clicks
    var sortAscending = false
    var currentPage = 0
    let pageSize = 25

    var timelineRows: [SearchConsoleAnalyticsRow] = []
    var timelineMetadata: SearchConsoleAnalyticsMetadata?
    var timelineAggregationType: String?
    var breakdownRows: [SearchConsoleAnalyticsRow] = []
    var breakdownAggregationType: String?
    var sitemaps: [SearchConsoleSitemap] = []
    var inspectionInput = ""
    var inspectionResult: SearchConsoleURLInspectionResult?
    var inspectedURL: String?

    var isLoadingSites = false
    var isLoadingTimeline = false
    var isLoadingBreakdown = false
    var isLoadingSitemaps = false
    var isInspecting = false

    var sitesError: String?
    var timelineError: String?
    var breakdownError: String?
    var sitemapsError: String?
    var inspectionError: String?

    private var sitesGeneration = 0
    private var timelineGeneration = 0
    private var breakdownGeneration = 0
    private var sitemapGeneration = 0
    private var inspectionGeneration = 0
    private var sitesTask: Task<Result<[SearchConsoleSite], Error>, Never>?
    private var timelineTask: Task<Result<SearchConsoleAnalyticsResponse, Error>, Never>?
    private var timelineInFlightKey: String?
    private var breakdownTask: Task<Result<SearchConsoleAnalyticsResponse, Error>, Never>?
    private var breakdownInFlightKey: String?
    private var sitemapTask: Task<Result<[SearchConsoleSitemap], Error>, Never>?
    private var sitemapInFlightKey: String?
    private var inspectionTask: Task<Result<SearchConsoleURLInspectionResult, Error>, Never>?
    private var inspectionInFlightKey: String?
    private var loadedTimelineKey: String?
    private var loadedBreakdownKey: String?
    private var credentialRefreshTask: Task<SiteIntegrationAccount, Error>?

    init(
        account: SiteIntegrationAccount,
        preferredSiteURL: String?,
        refreshAccount: (@MainActor @Sendable () async throws -> SiteIntegrationAccount)? = nil
    ) {
        self.account = account
        self.preferredSiteURL = preferredSiteURL
        self.refreshAccount = refreshAccount
        api = SearchConsoleDetailAPI(account: account)

        let metadataScope = account.metadata.keys.sorted().map { key in
            "\(key)=\(account.metadata[key] ?? "")"
        }.joined(separator: "&")
        accountScope = CredentialCacheScope.fingerprint(fields: [
            "search-console-detail",
            account.id.uuidString.lowercased(),
            account.provider.rawValue,
            "stable-google-account",
            metadataScope,
        ])

        let end = Date.now
        customEndDate = end
        customStartDate = Calendar.current.date(byAdding: .day, value: -27, to: end) ?? end

        if let cached = Self.sitesCache[accountScope] {
            sites = cached.value
        }
        selectedSiteURL = Self.resolveSelectedSite(
            preferred: preferredSiteURL,
            cached: Self.selectedSiteCache[accountScope],
            sites: sites
        )
        if let selectedSiteURL {
            inspectionInput = Self.defaultInspectionURL(for: selectedSiteURL)
            restoreSelectedSiteCaches(clearWhenMissing: true)
        }
    }

    var selectedSite: SearchConsoleSite? {
        guard let selectedSiteURL else { return nil }
        return sites.first { $0.siteUrl == selectedSiteURL }
    }

    var selectedDimension: SearchConsoleDimension {
        selectedDimensions.first ?? .query
    }

    func canSelectAggregation(_ aggregation: SearchConsoleAggregationType) -> Bool {
        let groupsByPage = selectedDimensions.contains(.page)
            || filters.contains { $0.dimension == .page }
        switch aggregation {
        case .auto, .byPage:
            return true
        case .byProperty:
            return !groupsByPage && selectedSearchType != .discover && selectedSearchType != .googleNews
        case .byNewsShowcasePanel:
            let hasNewsShowcaseFilter = filters.contains {
                $0.dimension == .searchAppearance
                    && $0.expression.caseInsensitiveCompare("NEWS_SHOWCASE") == .orderedSame
            }
            return !groupsByPage
                && (selectedSearchType == .discover || selectedSearchType == .googleNews)
                && hasNewsShowcaseFilter
        }
    }

    var selectedDateRange: SearchConsoleDateRange {
        let dates = selectedDatePreset.dates(customStart: customStartDate, customEnd: customEndDate)
        return SearchConsoleDateRange(startDate: dates.start, endDate: dates.end)
    }

    var dateRangeLabel: String {
        let dates = selectedDatePreset.dates(customStart: customStartDate, customEnd: customEndDate)
        return "\(dates.start.formatted(.dateTime.month(.abbreviated).day())) – \(dates.end.formatted(.dateTime.month(.abbreviated).day().year()))"
    }

    var totals: PerformanceTotals {
        guard !timelineRows.isEmpty else { return .zero }
        let clicks = timelineRows.reduce(0) { $0 + $1.clicks }
        let impressions = timelineRows.reduce(0) { $0 + $1.impressions }
        let ctr = impressions > 0 ? clicks / impressions : 0
        let weightedPosition = timelineRows.reduce(0) { $0 + ($1.position * $1.impressions) }
        let position = impressions > 0 ? weightedPosition / impressions : 0
        return PerformanceTotals(
            clicks: clicks,
            impressions: impressions,
            ctr: ctr,
            position: position
        )
    }

    var sortedBreakdownRows: [SearchConsoleAnalyticsRow] {
        breakdownRows.sorted { lhs, rhs in
            let comparison: ComparisonResult
            switch sortField {
            case .dimension:
                comparison = dimensionValue(for: lhs).localizedStandardCompare(dimensionValue(for: rhs))
            case .clicks:
                comparison = Self.compare(lhs.clicks, rhs.clicks)
            case .impressions:
                comparison = Self.compare(lhs.impressions, rhs.impressions)
            case .ctr:
                comparison = Self.compare(lhs.ctr, rhs.ctr)
            case .position:
                comparison = Self.compare(lhs.position, rhs.position)
            }
            if comparison == .orderedSame {
                return dimensionValue(for: lhs).localizedStandardCompare(dimensionValue(for: rhs)) == .orderedAscending
            }
            return sortAscending ? comparison == .orderedAscending : comparison == .orderedDescending
        }
    }

    var totalPages: Int {
        guard !breakdownRows.isEmpty else { return 0 }
        return Int(ceil(Double(breakdownRows.count) / Double(pageSize)))
    }

    var pageRows: [SearchConsoleAnalyticsRow] {
        let rows = sortedBreakdownRows
        let lower = min(currentPage * pageSize, rows.count)
        let upper = min(lower + pageSize, rows.count)
        return Array(rows[lower..<upper])
    }

    var pageRangeLabel: String {
        guard !breakdownRows.isEmpty else { return "No rows" }
        let start = currentPage * pageSize + 1
        let end = min(start + pageSize - 1, breakdownRows.count)
        return "\(start)–\(end) of \(breakdownRows.count.formatted())"
    }

    var sitemapIssueCount: Int64 {
        sitemaps.reduce(0) { $0 + $1.errors + $1.warnings }
    }

    var submittedURLCount: Int64 {
        sitemaps.flatMap(\.contents).reduce(0) { $0 + $1.submitted }
    }

    var indexedURLCount: Int64 {
        sitemaps.flatMap(\.contents).reduce(0) { $0 + ($1.indexed ?? 0) }
    }

    var isRefreshingPerformance: Bool { isLoadingTimeline || isLoadingBreakdown }
    var hasRetainedTimeline: Bool { isLoadingTimeline && !timelineRows.isEmpty }
    var hasRetainedBreakdown: Bool { isLoadingBreakdown && !breakdownRows.isEmpty }
    var reachedBreakdownLimit: Bool { breakdownRows.count >= Self.maximumBreakdownRows }

    func loadInitial() async {
        await loadSites(forceRefresh: false)
        guard selectedSiteURL != nil else { return }
        async let performance: Void = loadPerformance(forceRefresh: false)
        async let sitemapLoad: Void = loadSitemaps(forceRefresh: false)
        _ = await (performance, sitemapLoad)
    }

    func refresh() async {
        await loadSites(forceRefresh: true)
        guard selectedSiteURL != nil else { return }
        async let performance: Void = loadPerformance(forceRefresh: true)
        async let sitemapLoad: Void = loadSitemaps(forceRefresh: true)
        async let inspectionLoad: Void = refreshInspectionIfPresent()
        _ = await (performance, sitemapLoad, inspectionLoad)
    }

    func selectSite(_ site: SearchConsoleSite) async {
        guard selectedSiteURL != site.siteUrl else { return }
        cancelPerformanceLoads()
        cancelSitemapLoad()
        selectedSiteURL = site.siteUrl
        Self.selectedSiteCache[accountScope] = site.siteUrl
        currentPage = 0
        inspectionInput = Self.defaultInspectionURL(for: site.siteUrl)
        inspectionResult = nil
        inspectedURL = nil
        inspectionError = nil
        restoreSelectedSiteCaches(clearWhenMissing: true)
        async let performance: Void = loadPerformance(forceRefresh: false)
        async let sitemapLoad: Void = loadSitemaps(forceRefresh: false)
        _ = await (performance, sitemapLoad)
    }

    func selectPreset(_ preset: SearchConsoleDatePreset) async {
        guard preset != .custom, selectedDatePreset != preset else { return }
        selectedDatePreset = preset
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func selectCustomRange(start: Date, end: Date) async {
        customStartDate = min(start, end)
        customEndDate = max(start, end)
        selectedDatePreset = .custom
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func selectSearchType(_ type: SearchConsoleSearchType) async {
        guard selectedSearchType != type else { return }
        selectedSearchType = type
        normalizeAggregationSelection()
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func selectDimension(_ dimension: SearchConsoleDimension) async {
        guard selectedDimensions != [dimension] else { return }
        selectedDimensions = [dimension]
        if dimension == .hour {
            selectedDataState = .hourlyAll
        }
        normalizeAggregationSelection()
        currentPage = 0
        await loadBreakdown(forceRefresh: false)
    }

    func toggleDimension(_ dimension: SearchConsoleDimension) async {
        if selectedDimensions.contains(dimension) {
            guard selectedDimensions.count > 1 else { return }
            selectedDimensions.removeAll { $0 == dimension }
        } else {
            selectedDimensions.append(dimension)
        }
        if selectedDimensions.contains(.hour) {
            selectedDataState = .hourlyAll
        }
        normalizeAggregationSelection()
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func selectAggregationType(_ aggregationType: SearchConsoleAggregationType) async {
        guard canSelectAggregation(aggregationType), selectedAggregationType != aggregationType else { return }
        selectedAggregationType = aggregationType
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func selectDataState(_ dataState: SearchConsoleDataState) async {
        guard selectedDataState != dataState else { return }
        selectedDataState = dataState
        if dataState == .hourlyAll {
            if !selectedDimensions.contains(.hour) {
                selectedDimensions.insert(.hour, at: 0)
            }
        } else if selectedDimensions.contains(.hour) {
            selectedDimensions.removeAll { $0 == .hour }
            if selectedDimensions.isEmpty { selectedDimensions = [.query] }
        }
        normalizeAggregationSelection()
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func applyFilters(_ newFilters: [SearchConsoleDimensionFilter]) async {
        guard filters != newFilters else { return }
        filters = newFilters
        normalizeAggregationSelection()
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func removeFilter(at index: Int) async {
        guard filters.indices.contains(index) else { return }
        filters.remove(at: index)
        normalizeAggregationSelection()
        currentPage = 0
        await loadPerformance(forceRefresh: false)
    }

    func toggleSort(_ field: SearchConsoleSortField) {
        if sortField == field {
            sortAscending.toggle()
        } else {
            sortField = field
            sortAscending = field == .dimension || field == .position
        }
        currentPage = 0
    }

    func previousPage() {
        currentPage = max(0, currentPage - 1)
    }

    func nextPage() {
        currentPage = min(max(0, totalPages - 1), currentPage + 1)
    }

    func dimensionValue(for row: SearchConsoleAnalyticsRow) -> String {
        guard !row.keys.isEmpty else { return "Property total" }
        return row.keys.enumerated().map { index, raw in
            guard selectedDimensions.indices.contains(index) else { return raw }
            switch selectedDimensions[index] {
            case .date:
                return SearchConsoleFormatting.googleDate(raw)?.formatted(
                    .dateTime.month(.abbreviated).day().year()
                ) ?? raw
            case .hour:
                return SearchConsoleFormatting.googleDateOrHour(raw)?.formatted(
                    .dateTime.month(.abbreviated).day().hour()
                ) ?? raw
            case .device, .searchAppearance:
                return SearchConsoleFormatting.humanized(raw)
            case .country:
                return raw.uppercased()
            case .query, .page:
                return raw
            }
        }.joined(separator: " · ")
    }

    func inspectURL(forceRefresh: Bool = false) async {
        let raw = inspectionInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(),
              (scheme == "https" || scheme == "http"),
              url.host?.isEmpty == false else {
            inspectionError = "Enter a complete HTTP or HTTPS URL to inspect."
            return
        }
        guard let siteURL = selectedSiteURL else { return }

        let key = inspectionCacheKey(url: url.absoluteString, siteURL: siteURL)
        if let cached = Self.inspectionCache[key] {
            inspectionResult = cached.value
            inspectedURL = url.absoluteString
            inspectionError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt, lifetime: Self.supportingDataCacheLifetime) {
                return
            }
        }

        if inspectionInFlightKey == key, let task = inspectionTask {
            let generation = inspectionGeneration
            let result = await task.value
            applyInspectionResult(result, key: key, url: url.absoluteString, generation: generation)
            return
        }

        inspectionTask?.cancel()
        inspectionGeneration += 1
        let generation = inspectionGeneration
        inspectionInFlightKey = key
        inspectionError = nil
        isInspecting = true
        let task = Task { [weak self] in
            await Self.capture {
                guard let self else { throw CancellationError() }
                return try await self.performWithCredentialRefresh { api in
                    try await api.inspectURL(url, siteURL: siteURL)
                }
            }
        }
        inspectionTask = task
        let result = await task.value
        applyInspectionResult(result, key: key, url: url.absoluteString, generation: generation)
    }

    private func loadSites(forceRefresh: Bool) async {
        if let cached = Self.sitesCache[accountScope] {
            sites = cached.value
            resolveSelectedSiteAfterSitesLoad()
            sitesError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt, lifetime: Self.supportingDataCacheLifetime) {
                return
            }
        }

        if let sitesTask {
            let result = await sitesTask.value
            applySitesResult(result, generation: sitesGeneration)
            return
        }

        sitesGeneration += 1
        let generation = sitesGeneration
        isLoadingSites = sites.isEmpty
        sitesError = nil
        let task = Task { [weak self] in
            await Self.capture {
                guard let self else { throw CancellationError() }
                return try await self.performWithCredentialRefresh { api in
                    try await api.listSites()
                }
            }
        }
        sitesTask = task
        let result = await task.value
        applySitesResult(result, generation: generation)
    }

    private func applySitesResult(
        _ result: Result<[SearchConsoleSite], Error>,
        generation: Int
    ) {
        guard generation == sitesGeneration else { return }
        sitesTask = nil
        isLoadingSites = false
        switch result {
        case .success(let loaded):
            sites = loaded.sorted {
                $0.siteUrl.localizedCaseInsensitiveCompare($1.siteUrl) == .orderedAscending
            }
            Self.sitesCache[accountScope] = CachedValue(value: sites, updatedAt: .now)
            resolveSelectedSiteAfterSitesLoad()
        case .failure(let error):
            guard !Self.isCancellation(error) else { return }
            sitesError = error.localizedDescription
        }
    }

    private func resolveSelectedSiteAfterSitesLoad() {
        let previous = selectedSiteURL
        selectedSiteURL = Self.resolveSelectedSite(
            preferred: preferredSiteURL,
            cached: previous ?? Self.selectedSiteCache[accountScope],
            sites: sites
        )
        if let selectedSiteURL {
            Self.selectedSiteCache[accountScope] = selectedSiteURL
            if inspectionInput.isEmpty || previous != selectedSiteURL {
                inspectionInput = Self.defaultInspectionURL(for: selectedSiteURL)
            }
            if previous != selectedSiteURL {
                restoreSelectedSiteCaches(clearWhenMissing: true)
            }
        }
    }

    private func loadPerformance(forceRefresh: Bool) async {
        async let timeline: Void = loadTimeline(forceRefresh: forceRefresh)
        async let breakdown: Void = loadBreakdown(forceRefresh: forceRefresh)
        _ = await (timeline, breakdown)
    }

    private func loadTimeline(forceRefresh: Bool) async {
        guard let siteURL = selectedSiteURL else { return }
        let key = timelineCacheKey(siteURL: siteURL)
        if let cached = Self.timelineCache[key] {
            applyTimeline(cached.value, key: key)
            timelineError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt, lifetime: Self.performanceCacheLifetime) {
                return
            }
        }

        if timelineInFlightKey == key, let task = timelineTask {
            let generation = timelineGeneration
            let result = await task.value
            applyTimelineResult(result, key: key, generation: generation)
            return
        }

        timelineTask?.cancel()
        timelineGeneration += 1
        let generation = timelineGeneration
        timelineInFlightKey = key
        timelineError = nil
        isLoadingTimeline = true
        let timelineDimension: SearchConsoleDimension = selectedDataState == .hourlyAll ? .hour : .date
        let query = analyticsQuery(dimensions: [timelineDimension], rowLimit: 25_000)
        let task = Task { [weak self] in
            await Self.capture {
                guard let self else { throw CancellationError() }
                return try await self.performWithCredentialRefresh { api in
                    try await api.querySearchAnalytics(siteURL: siteURL, query: query)
                }
            }
        }
        timelineTask = task
        let result = await task.value
        applyTimelineResult(result, key: key, generation: generation)
    }

    private func loadBreakdown(forceRefresh: Bool) async {
        guard let siteURL = selectedSiteURL else { return }
        let key = breakdownCacheKey(siteURL: siteURL)
        if let cached = Self.breakdownCache[key] {
            applyBreakdown(cached.value, key: key)
            breakdownError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt, lifetime: Self.performanceCacheLifetime) {
                return
            }
        }

        if breakdownInFlightKey == key, let task = breakdownTask {
            let generation = breakdownGeneration
            let result = await task.value
            applyBreakdownResult(result, key: key, generation: generation)
            return
        }

        breakdownTask?.cancel()
        breakdownGeneration += 1
        let generation = breakdownGeneration
        breakdownInFlightKey = key
        breakdownError = nil
        isLoadingBreakdown = true
        let query = analyticsQuery(dimensions: selectedDimensions, rowLimit: 25_000)
        let task = Task { [weak self] in
            await Self.capture {
                guard let self else { throw CancellationError() }
                return try await self.performWithCredentialRefresh { api in
                    try await api.queryAllSearchAnalytics(
                        siteURL: siteURL,
                        query: query,
                        maximumRows: Self.maximumBreakdownRows
                    )
                }
            }
        }
        breakdownTask = task
        let result = await task.value
        applyBreakdownResult(result, key: key, generation: generation)
    }

    private func loadSitemaps(forceRefresh: Bool) async {
        guard let siteURL = selectedSiteURL else { return }
        let key = sitemapCacheKey(siteURL: siteURL)
        if let cached = Self.sitemapCache[key] {
            sitemaps = cached.value
            sitemapsError = nil
            if !forceRefresh, Self.isFresh(cached.updatedAt, lifetime: Self.supportingDataCacheLifetime) {
                return
            }
        }

        if sitemapInFlightKey == key, let task = sitemapTask {
            let generation = sitemapGeneration
            let result = await task.value
            applySitemapResult(result, key: key, generation: generation)
            return
        }

        sitemapTask?.cancel()
        sitemapGeneration += 1
        let generation = sitemapGeneration
        sitemapInFlightKey = key
        sitemapsError = nil
        isLoadingSitemaps = true
        let task = Task { [weak self] in
            await Self.capture {
                guard let self else { throw CancellationError() }
                return try await self.performWithCredentialRefresh { api in
                    try await api.listSitemaps(siteURL: siteURL)
                }
            }
        }
        sitemapTask = task
        let result = await task.value
        applySitemapResult(result, key: key, generation: generation)
    }

    private func refreshInspectionIfPresent() async {
        guard inspectionResult != nil, inspectedURL != nil else { return }
        await inspectURL(forceRefresh: true)
    }

    private func analyticsQuery(
        dimensions: [SearchConsoleDimension],
        rowLimit: Int
    ) -> SearchConsoleAnalyticsQuery {
        SearchConsoleAnalyticsQuery(
            dateRange: selectedDateRange,
            dimensions: dimensions,
            searchType: selectedSearchType,
            dimensionFilterGroups: filters.isEmpty
                ? []
                : [SearchConsoleDimensionFilterGroup(filters: filters)],
            aggregationType: selectedAggregationType,
            rowLimit: rowLimit,
            startRow: 0,
            dataState: selectedDataState
        )
    }

    private func normalizeAggregationSelection() {
        if !canSelectAggregation(selectedAggregationType) {
            selectedAggregationType = .auto
        }
    }

    private func performWithCredentialRefresh<Value>(
        _ operation: (SearchConsoleDetailAPI) async throws -> Value
    ) async throws -> Value {
        do {
            return try await operation(api)
        } catch {
            guard Self.isCredentialFailure(error), refreshAccount != nil else { throw error }
            let refreshedAPI = try await refreshAPI()
            return try await operation(refreshedAPI)
        }
    }

    private func refreshAPI() async throws -> SearchConsoleDetailAPI {
        guard let refreshAccount else { throw SearchConsoleDetailAPIError.expiredCredential }
        let task: Task<SiteIntegrationAccount, Error>
        if let inFlight = credentialRefreshTask {
            task = inFlight
        } else {
            task = Task { try await refreshAccount() }
            credentialRefreshTask = task
        }

        do {
            let refreshedAccount = try await task.value
            credentialRefreshTask = nil
            let refreshedAPI = SearchConsoleDetailAPI(account: refreshedAccount)
            api = refreshedAPI
            return refreshedAPI
        } catch {
            credentialRefreshTask = nil
            throw error
        }
    }

    private func applyTimelineResult(
        _ result: Result<SearchConsoleAnalyticsResponse, Error>,
        key: String,
        generation: Int
    ) {
        guard generation == timelineGeneration, timelineInFlightKey == key else { return }
        timelineTask = nil
        timelineInFlightKey = nil
        isLoadingTimeline = false
        switch result {
        case .success(let response):
            applyTimeline(response, key: key)
            Self.timelineCache[key] = CachedValue(value: response, updatedAt: .now)
        case .failure(let error):
            guard !Self.isCancellation(error) else { return }
            timelineError = error.localizedDescription
        }
    }

    private func applyTimeline(_ response: SearchConsoleAnalyticsResponse, key: String) {
        timelineRows = response.rows
        timelineMetadata = response.metadata
        timelineAggregationType = response.responseAggregationType
        loadedTimelineKey = key
    }

    private func applyBreakdownResult(
        _ result: Result<SearchConsoleAnalyticsResponse, Error>,
        key: String,
        generation: Int
    ) {
        guard generation == breakdownGeneration, breakdownInFlightKey == key else { return }
        breakdownTask = nil
        breakdownInFlightKey = nil
        isLoadingBreakdown = false
        switch result {
        case .success(let response):
            applyBreakdown(response, key: key)
            Self.breakdownCache[key] = CachedValue(value: response, updatedAt: .now)
        case .failure(let error):
            guard !Self.isCancellation(error) else { return }
            breakdownError = error.localizedDescription
        }
    }

    private func applyBreakdown(_ response: SearchConsoleAnalyticsResponse, key: String) {
        breakdownRows = response.rows
        breakdownAggregationType = response.responseAggregationType
        currentPage = min(currentPage, max(0, Int(ceil(Double(response.rows.count) / Double(pageSize))) - 1))
        loadedBreakdownKey = key
    }

    private func applySitemapResult(
        _ result: Result<[SearchConsoleSitemap], Error>,
        key: String,
        generation: Int
    ) {
        guard generation == sitemapGeneration, sitemapInFlightKey == key else { return }
        sitemapTask = nil
        sitemapInFlightKey = nil
        isLoadingSitemaps = false
        switch result {
        case .success(let loaded):
            sitemaps = loaded.sorted {
                $0.path.localizedCaseInsensitiveCompare($1.path) == .orderedAscending
            }
            Self.sitemapCache[key] = CachedValue(value: sitemaps, updatedAt: .now)
        case .failure(let error):
            guard !Self.isCancellation(error) else { return }
            sitemapsError = error.localizedDescription
        }
    }

    private func applyInspectionResult(
        _ result: Result<SearchConsoleURLInspectionResult, Error>,
        key: String,
        url: String,
        generation: Int
    ) {
        guard generation == inspectionGeneration, inspectionInFlightKey == key else { return }
        inspectionTask = nil
        inspectionInFlightKey = nil
        isInspecting = false
        switch result {
        case .success(let response):
            inspectionResult = response
            inspectedURL = url
            inspectionError = nil
            Self.inspectionCache[key] = CachedValue(value: response, updatedAt: .now)
        case .failure(let error):
            guard !Self.isCancellation(error) else { return }
            inspectionError = error.localizedDescription
        }
    }

    private func restoreSelectedSiteCaches(clearWhenMissing: Bool) {
        guard let siteURL = selectedSiteURL else { return }
        let timelineKey = timelineCacheKey(siteURL: siteURL)
        if let cached = Self.timelineCache[timelineKey] {
            applyTimeline(cached.value, key: timelineKey)
        } else if clearWhenMissing {
            timelineRows = []
            timelineMetadata = nil
            timelineAggregationType = nil
            loadedTimelineKey = nil
        }

        let breakdownKey = breakdownCacheKey(siteURL: siteURL)
        if let cached = Self.breakdownCache[breakdownKey] {
            applyBreakdown(cached.value, key: breakdownKey)
        } else if clearWhenMissing {
            breakdownRows = []
            breakdownAggregationType = nil
            loadedBreakdownKey = nil
        }

        let sitemapKey = sitemapCacheKey(siteURL: siteURL)
        if let cached = Self.sitemapCache[sitemapKey] {
            sitemaps = cached.value
        } else if clearWhenMissing {
            sitemaps = []
        }
    }

    private func cancelPerformanceLoads() {
        timelineTask?.cancel()
        timelineTask = nil
        timelineInFlightKey = nil
        timelineGeneration += 1
        isLoadingTimeline = false
        breakdownTask?.cancel()
        breakdownTask = nil
        breakdownInFlightKey = nil
        breakdownGeneration += 1
        isLoadingBreakdown = false
    }

    private func cancelSitemapLoad() {
        sitemapTask?.cancel()
        sitemapTask = nil
        sitemapInFlightKey = nil
        sitemapGeneration += 1
        isLoadingSitemaps = false
    }

    private func timelineCacheKey(siteURL: String) -> String {
        Self.cacheKey(fields: [
            accountScope,
            "timeline",
            siteURL,
            selectedDateRange.startDate,
            selectedDateRange.endDate,
            selectedSearchType.rawValue,
            selectedAggregationType.rawValue,
            selectedDataState.rawValue,
            Self.filterScope(filters),
        ])
    }

    private func breakdownCacheKey(siteURL: String) -> String {
        Self.cacheKey(fields: [
            accountScope,
            "breakdown",
            siteURL,
            selectedDateRange.startDate,
            selectedDateRange.endDate,
            selectedSearchType.rawValue,
            selectedDimensions.map(\.rawValue).joined(separator: ","),
            selectedAggregationType.rawValue,
            selectedDataState.rawValue,
            Self.filterScope(filters),
        ])
    }

    private func sitemapCacheKey(siteURL: String) -> String {
        Self.cacheKey(fields: [accountScope, "sitemaps", siteURL])
    }

    private func inspectionCacheKey(url: String, siteURL: String) -> String {
        Self.cacheKey(fields: [accountScope, "inspection", siteURL, url])
    }

    private static func cacheKey(fields: [String]) -> String {
        CredentialCacheScope.fingerprint(fields: fields)
    }

    private static func filterScope(_ filters: [SearchConsoleDimensionFilter]) -> String {
        filters.map { "\($0.dimension.rawValue)|\($0.operator.rawValue)|\($0.expression)" }
            .joined(separator: "\u{1E}")
    }

    private static func resolveSelectedSite(
        preferred: String?,
        cached: String?,
        sites: [SearchConsoleSite]
    ) -> String? {
        if let preferred, sites.contains(where: { $0.siteUrl == preferred }) { return preferred }
        if let cached, sites.contains(where: { $0.siteUrl == cached }) { return cached }
        return sites.first?.siteUrl
    }

    private static func defaultInspectionURL(for siteURL: String) -> String {
        if siteURL.hasPrefix("sc-domain:") {
            return "https://\(siteURL.dropFirst("sc-domain:".count))/"
        }
        return siteURL
    }

    private static func compare(_ lhs: Double, _ rhs: Double) -> ComparisonResult {
        if lhs == rhs { return .orderedSame }
        return lhs < rhs ? .orderedAscending : .orderedDescending
    }

    private static func isFresh(_ date: Date, lifetime: TimeInterval) -> Bool {
        Date.now.timeIntervalSince(date) < lifetime
    }

    private static func capture<T>(_ operation: () async throws -> T) async -> Result<T, Error> {
        do { return .success(try await operation()) }
        catch { return .failure(error) }
    }

    private static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private static func isCredentialFailure(_ error: Error) -> Bool {
        guard let error = error as? SearchConsoleDetailAPIError else { return false }
        switch error {
        case .expiredCredential, .invalidCredential:
            return true
        case .requestFailed(let status, _):
            return status == 401
        default:
            return false
        }
    }
}

// MARK: - Screen

struct SearchConsoleDetailView: View {
    let account: SiteIntegrationAccount
    let initialSiteURL: String?

    @State private var viewModel: SearchConsoleDetailViewModel
    @State private var selectedSection: SearchConsoleDetailSection = .performance
    @State private var showsDatePicker = false
    @State private var showsFilterEditor = false
    @State private var showsAdvancedQuery = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    init(
        account: SiteIntegrationAccount,
        initialSiteURL: String? = nil,
        refreshAccount: (@MainActor @Sendable () async throws -> SiteIntegrationAccount)? = nil
    ) {
        self.account = account
        self.initialSiteURL = initialSiteURL
        _viewModel = State(
            initialValue: SearchConsoleDetailViewModel(
                account: account,
                preferredSiteURL: initialSiteURL,
                refreshAccount: refreshAccount
            )
        )
    }

    var body: some View {
        ZStack {
            AppTheme.canvas.ignoresSafeArea()

            if viewModel.isLoadingSites, viewModel.sites.isEmpty {
                AppDashboardLoadingView(accent: SiteIntegrationProvider.googleSearchConsole.accentColor)
            } else {
                content
            }
        }
        .navigationTitle("Search Console")
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.loadInitial() }
        .sheet(isPresented: $showsDatePicker) {
            SearchConsoleDateRangeSheet(
                startDate: viewModel.customStartDate,
                endDate: viewModel.customEndDate
            ) { start, end in
                Task { await viewModel.selectCustomRange(start: start, end: end) }
            }
        }
        .sheet(isPresented: $showsFilterEditor) {
            SearchConsoleFilterEditor(filters: viewModel.filters) { filters in
                Task { await viewModel.applyFilters(filters) }
            }
        }
    }

    private var content: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                if let sitesError = viewModel.sitesError {
                    AppFeedbackBanner(
                        title: "Couldn’t load properties",
                        message: sitesError,
                        tint: AppTheme.danger,
                        actionTitle: "Try again"
                    ) {
                        Task { await viewModel.loadInitial() }
                    }
                }

                if viewModel.sites.isEmpty {
                    AppEmptyState(
                        icon: "magnifyingglass.circle.fill",
                        title: "No Search Console properties",
                        message: "This Google account did not return any verified Search Console properties."
                    )
                    .frame(maxWidth: .infinity)
                    .appSurface()
                } else {
                    propertyHeader
                    sectionPicker

                    switch selectedSection {
                    case .performance:
                        performanceContent
                    case .sitemaps:
                        sitemapsContent
                    case .inspection:
                        inspectionContent
                    }
                }
            }
            .padding(.horizontal, AppLayout.pagePadding(for: horizontalSizeClass))
            .padding(.top, 16)
            .padding(.bottom, 32)
            .appContentWidth(AppLayout.dashboardMaxWidth, horizontalSizeClass: horizontalSizeClass)
        }
        .refreshable { await viewModel.refresh() }
    }

    private var propertyHeader: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 16) {
                propertyIdentity
                Spacer(minLength: 18)
                propertyPicker
            }
            VStack(alignment: .leading, spacing: 16) {
                propertyIdentity
                propertyPicker
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(18)
        .providerSurface(accent: SiteIntegrationProvider.googleSearchConsole.accentColor)
    }

    private var propertyIdentity: some View {
        HStack(spacing: 14) {
            SiteProviderMark(provider: .googleSearchConsole, size: 30)
                .frame(width: 52, height: 52)
                .background(
                    SiteIntegrationProvider.googleSearchConsole.accentColor.opacity(0.105),
                    in: RoundedRectangle(cornerRadius: AppTheme.iconRadius, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 5) {
                Text(SearchConsoleFormatting.propertyName(viewModel.selectedSiteURL ?? "Search Console"))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .lineLimit(2)
                    .textSelection(.enabled)

                HStack(spacing: 8) {
                    Text(viewModel.selectedSiteURL?.hasPrefix("sc-domain:") == true ? "Domain property" : "URL-prefix property")
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                    if let site = viewModel.selectedSite {
                        AppStatusBadge(
                            text: SearchConsoleFormatting.permission(site.permissionLevel),
                            tone: SearchConsoleFormatting.permissionTone(site.permissionLevel)
                        )
                    }
                }
            }
        }
    }

    private var propertyPicker: some View {
        Menu {
            ForEach(viewModel.sites) { site in
                Button {
                    Task { await viewModel.selectSite(site) }
                } label: {
                    Label {
                        VStack(alignment: .leading) {
                            Text(SearchConsoleFormatting.propertyName(site.siteUrl))
                            Text(SearchConsoleFormatting.permission(site.permissionLevel))
                        }
                    } icon: {
                        Image(systemName: site.siteUrl == viewModel.selectedSiteURL ? "checkmark.circle.fill" : "globe")
                    }
                }
            }
        } label: {
            HStack(spacing: 9) {
                Image(systemName: "rectangle.2.swap")
                Text("Switch property")
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.semibold))
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(AppTheme.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                    .strokeBorder(AppTheme.stroke, lineWidth: 0.5)
            }
        }
        .accessibilityLabel("Select Search Console property")
    }

    private var sectionPicker: some View {
        Picker("Search Console section", selection: $selectedSection) {
            ForEach(SearchConsoleDetailSection.allCases) { section in
                Label(section.title, systemImage: section.icon).tag(section)
            }
        }
        .pickerStyle(.segmented)
    }

    // MARK: Performance

    @ViewBuilder
    private var performanceContent: some View {
        performanceControls

        if let error = viewModel.timelineError {
            AppFeedbackBanner(
                title: "Performance timeline unavailable",
                message: error,
                tint: AppTheme.danger
            )
        }

        performanceMetrics
        performanceChart

        if let incompleteHour = viewModel.timelineMetadata?.firstIncompleteHour {
            AppFeedbackBanner(
                title: "Recent hourly data is still settling",
                message: "Google marks results from \(SearchConsoleFormatting.timestamp(incompleteHour)) onward as incomplete. They can change as processing finishes.",
                icon: "clock.badge.exclamationmark",
                tint: AppTheme.warning
            )
        } else if let incompleteDate = viewModel.timelineMetadata?.firstIncompleteDate {
            AppFeedbackBanner(
                title: "Fresh data is still settling",
                message: "Google marks results from \(incompleteDate) onward as incomplete. They can change as processing finishes.",
                icon: "clock.badge.exclamationmark",
                tint: AppTheme.warning
            )
        }

        breakdownSection
    }

    private var performanceControls: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("PERFORMANCE WINDOW")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.1)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text(viewModel.dateRangeLabel)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                }
                Spacer()
                if viewModel.isRefreshingPerformance {
                    ProgressView()
                        .controlSize(.small)
                        .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                        .accessibilityLabel("Updating performance")
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SearchConsoleDatePreset.allCases) { preset in
                        Button {
                            if preset == .custom {
                                showsDatePicker = true
                            } else {
                                Task { await viewModel.selectPreset(preset) }
                            }
                        } label: {
                            Text(preset.shortLabel)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(
                                    viewModel.selectedDatePreset == preset
                                        ? Color.white
                                        : AppTheme.textSecondary
                                )
                                .padding(.horizontal, 12)
                                .frame(minHeight: 36)
                                .background(
                                    viewModel.selectedDatePreset == preset
                                        ? SiteIntegrationProvider.googleSearchConsole.accentColor
                                        : AppTheme.surfaceRaised,
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 9) {
                Text("SEARCH SURFACE")
                    .font(.caption2.weight(.semibold))
                    .tracking(1.1)
                    .foregroundStyle(AppTheme.textSecondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(SearchConsoleSearchType.allCases, id: \.self) { type in
                            Button {
                                Task { await viewModel.selectSearchType(type) }
                            } label: {
                                Label(type.displayName, systemImage: type.icon)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(
                                        viewModel.selectedSearchType == type
                                            ? AppTheme.textPrimary
                                            : AppTheme.textSecondary
                                    )
                                    .padding(.horizontal, 12)
                                    .frame(minHeight: 38)
                                    .background(
                                        viewModel.selectedSearchType == type
                                            ? SiteIntegrationProvider.googleSearchConsole.accentColor.opacity(0.14)
                                            : AppTheme.surfaceRaised,
                                        in: Capsule()
                                    )
                                    .overlay {
                                        Capsule().strokeBorder(
                                            viewModel.selectedSearchType == type
                                                ? SiteIntegrationProvider.googleSearchConsole.accentColor.opacity(0.38)
                                                : AppTheme.stroke,
                                            lineWidth: 0.6
                                        )
                                    }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            DisclosureGroup(isExpanded: $showsAdvancedQuery) {
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("DATA FRESHNESS")
                                .font(.caption2.weight(.semibold))
                                .tracking(0.8)
                                .foregroundStyle(AppTheme.textSecondary)
                            Text(viewModel.selectedDataState.explanation)
                                .font(.caption)
                                .foregroundStyle(AppTheme.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 8)
                        Menu {
                            ForEach(SearchConsoleDataState.allCases, id: \.self) { state in
                                Button {
                                    Task { await viewModel.selectDataState(state) }
                                } label: {
                                    Label(
                                        state.displayName,
                                        systemImage: viewModel.selectedDataState == state
                                            ? "checkmark.circle.fill"
                                            : "circle"
                                    )
                                }
                            }
                        } label: {
                            SearchConsoleQueryControlLabel(
                                title: viewModel.selectedDataState.displayName,
                                icon: "clock.arrow.circlepath"
                            )
                        }
                    }

                    Divider().overlay(AppTheme.divider)

                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("AGGREGATION")
                                .font(.caption2.weight(.semibold))
                                .tracking(0.8)
                                .foregroundStyle(AppTheme.textSecondary)
                            Text("Choose how Google groups result rows.")
                                .font(.caption)
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                        Spacer(minLength: 8)
                        Menu {
                            ForEach(SearchConsoleAggregationType.allCases, id: \.self) { aggregation in
                                Button {
                                    Task { await viewModel.selectAggregationType(aggregation) }
                                } label: {
                                    Label(
                                        aggregation.displayName,
                                        systemImage: viewModel.selectedAggregationType == aggregation
                                            ? "checkmark.circle.fill"
                                            : "circle"
                                    )
                                }
                                .disabled(!viewModel.canSelectAggregation(aggregation))
                            }
                        } label: {
                            SearchConsoleQueryControlLabel(
                                title: viewModel.selectedAggregationType.displayName,
                                icon: "square.stack.3d.up"
                            )
                        }
                    }

                    if let returnedAggregation = viewModel.timelineAggregationType
                        ?? viewModel.breakdownAggregationType {
                        Label(
                            "Google returned \(SearchConsoleFormatting.humanized(returnedAggregation)) aggregation",
                            systemImage: "checkmark.seal"
                        )
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.top, 12)
            } label: {
                Label("Advanced query", systemImage: "slider.horizontal.3")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
            }
        }
        .padding(18)
        .appSurface()
    }

    private var performanceMetrics: some View {
        LazyVGrid(
            columns: horizontalSizeClass == .regular
                ? Array(repeating: GridItem(.flexible(), spacing: 10), count: 4)
                : Array(repeating: GridItem(.flexible(), spacing: 10), count: 2),
            spacing: 10
        ) {
            SearchConsoleMetricCard(
                metric: .clicks,
                value: viewModel.totals.clicks,
                selected: viewModel.chartMetric == .clicks
            ) { viewModel.chartMetric = .clicks }
            SearchConsoleMetricCard(
                metric: .impressions,
                value: viewModel.totals.impressions,
                selected: viewModel.chartMetric == .impressions
            ) { viewModel.chartMetric = .impressions }
            SearchConsoleMetricCard(
                metric: .ctr,
                value: viewModel.totals.ctr,
                selected: viewModel.chartMetric == .ctr
            ) { viewModel.chartMetric = .ctr }
            SearchConsoleMetricCard(
                metric: .position,
                value: viewModel.totals.position,
                selected: viewModel.chartMetric == .position
            ) { viewModel.chartMetric = .position }
        }
    }

    private var performanceChart: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("SEARCH PULSE")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.2)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text("\(viewModel.selectedDataState == .hourlyAll ? "Hourly" : "Daily") \(viewModel.chartMetric.title.lowercased())")
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                }
                Spacer()
                if viewModel.hasRetainedTimeline {
                    Label("Updating", systemImage: "arrow.triangle.2.circlepath")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }

            if viewModel.isLoadingTimeline, viewModel.timelineRows.isEmpty {
                ProgressView("Loading \(viewModel.selectedDataState == .hourlyAll ? "hourly" : "daily") performance")
                    .font(.footnote)
                    .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                    .foregroundStyle(AppTheme.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 250)
            } else if viewModel.timelineRows.isEmpty {
                AppEmptyState(
                    icon: "chart.xyaxis.line",
                    title: "No performance data",
                    message: "Google returned no \(viewModel.selectedDataState == .hourlyAll ? "hourly" : "daily") rows for this property, surface and date range."
                )
                .frame(maxWidth: .infinity, minHeight: 250)
            } else {
                SearchConsolePerformanceChart(
                    rows: viewModel.timelineRows,
                    metric: viewModel.chartMetric
                )
                .frame(height: horizontalSizeClass == .regular ? 310 : 250)
            }
        }
        .padding(18)
        .providerSurface(accent: viewModel.chartMetric.color)
    }

    private var breakdownSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                AppSectionHeader(
                    title: "Breakdown",
                    count: viewModel.breakdownRows.count,
                    accent: SiteIntegrationProvider.googleSearchConsole.accentColor
                )
                Spacer(minLength: 0)
                Button {
                    showsFilterEditor = true
                } label: {
                    Label(
                        viewModel.filters.isEmpty ? "Filter" : "Filters \(viewModel.filters.count)",
                        systemImage: "line.3.horizontal.decrease"
                    )
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .padding(.horizontal, 11)
                    .frame(minHeight: 36)
                    .background(AppTheme.surfaceRaised, in: Capsule())
                    .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
                .buttonStyle(.plain)

                Menu {
                    ForEach(SearchConsoleDimension.breakdownCases, id: \.self) { dimension in
                        Button {
                            Task { await viewModel.toggleDimension(dimension) }
                        } label: {
                            Label(
                                dimension.displayName,
                                systemImage: viewModel.selectedDimensions.contains(dimension)
                                    ? "checkmark.circle.fill"
                                    : "circle"
                            )
                        }
                    }
                } label: {
                    Label(
                        "Dimensions \(viewModel.selectedDimensions.count)",
                        systemImage: "square.grid.2x2"
                    )
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppTheme.textPrimary)
                    .padding(.horizontal, 11)
                    .frame(minHeight: 36)
                    .background(AppTheme.surfaceRaised, in: Capsule())
                    .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(SearchConsoleDimension.breakdownCases, id: \.self) { dimension in
                        Button {
                            Task { await viewModel.selectDimension(dimension) }
                        } label: {
                            Text(dimension.displayName)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(
                                    viewModel.selectedDimensions == [dimension]
                                        ? Color.white
                                        : AppTheme.textSecondary
                                )
                                .padding(.horizontal, 12)
                                .frame(minHeight: 36)
                                .background(
                                    viewModel.selectedDimensions == [dimension]
                                        ? SiteIntegrationProvider.googleSearchConsole.accentColor
                                        : AppTheme.surfaceRaised,
                                    in: Capsule()
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            if !viewModel.filters.isEmpty {
                SearchConsoleAppliedFilters(
                    filters: viewModel.filters,
                    remove: { index in
                        Task { await viewModel.removeFilter(at: index) }
                    }
                )
            }

            if let error = viewModel.breakdownError {
                AppFeedbackBanner(
                    title: "Breakdown unavailable",
                    message: error,
                    tint: AppTheme.danger
                )
            }

            if viewModel.reachedBreakdownLimit {
                AppFeedbackBanner(
                    title: "Showing the first 100,000 rows",
                    message: "Narrow the date window or add a filter to inspect a more specific result set.",
                    icon: "line.3.horizontal.decrease.circle.fill",
                    tint: AppTheme.warning
                )
            }

            SearchConsoleBreakdownTable(viewModel: viewModel)
        }
    }

    // MARK: Sitemaps

    @ViewBuilder
    private var sitemapsContent: some View {
        sitemapSummary

        if let error = viewModel.sitemapsError {
            AppFeedbackBanner(
                title: "Couldn’t load sitemaps",
                message: error,
                tint: AppTheme.danger
            )
        }

        if viewModel.isLoadingSitemaps, viewModel.sitemaps.isEmpty {
            ProgressView("Loading sitemaps")
                .font(.footnote)
                .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                .foregroundStyle(AppTheme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 44)
                .appSurface()
        } else if viewModel.sitemaps.isEmpty {
            AppEmptyState(
                icon: "doc.text.magnifyingglass",
                title: "No submitted sitemaps",
                message: "Search Console did not return any submitted sitemaps for this property."
            )
            .frame(maxWidth: .infinity)
            .appSurface()
        } else {
            LazyVGrid(columns: sitemapColumns, spacing: 14) {
                ForEach(viewModel.sitemaps) { sitemap in
                    SearchConsoleSitemapCard(sitemap: sitemap)
                }
            }
        }
    }

    private var sitemapSummary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("SITEMAP COVERAGE")
                        .font(.caption2.weight(.semibold))
                        .tracking(1.1)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text("Submitted discovery files")
                        .font(.headline)
                        .foregroundStyle(AppTheme.textPrimary)
                }
                Spacer()
                if viewModel.isLoadingSitemaps {
                    ProgressView()
                        .controlSize(.small)
                        .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                }
            }

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 118), spacing: 10)],
                spacing: 10
            ) {
                SearchConsoleCompactStat(label: "Sitemaps", value: Double(viewModel.sitemaps.count), metric: .count)
                SearchConsoleCompactStat(label: "Submitted URLs", value: Double(viewModel.submittedURLCount), metric: .count)
                SearchConsoleCompactStat(label: "Indexed URLs", value: Double(viewModel.indexedURLCount), metric: .count)
                SearchConsoleCompactStat(
                    label: "Issues",
                    value: Double(viewModel.sitemapIssueCount),
                    metric: .count,
                    tint: viewModel.sitemapIssueCount > 0 ? AppTheme.warning : AppTheme.success
                )
            }
        }
        .padding(18)
        .providerSurface(accent: SiteIntegrationProvider.googleSearchConsole.accentColor)
    }

    private var sitemapColumns: [GridItem] {
        AppLayout.adaptiveColumns(
            for: horizontalSizeClass,
            regularMinimum: 380,
            regularMaximum: 560,
            spacing: 14
        )
    }

    // MARK: URL inspection

    @ViewBuilder
    private var inspectionContent: some View {
        inspectionInputCard

        if let error = viewModel.inspectionError {
            AppFeedbackBanner(
                title: "URL inspection failed",
                message: error,
                tint: AppTheme.danger
            )
        }

        if let result = viewModel.inspectionResult {
            SearchConsoleInspectionResultView(
                result: result,
                inspectedURL: viewModel.inspectedURL ?? viewModel.inspectionInput
            )
        } else if !viewModel.isInspecting {
            AppEmptyState(
                icon: "doc.text.magnifyingglass",
                title: "Inspect any URL",
                message: "Enter a URL inside the selected property to see Google’s index, AMP, mobile usability and rich-result findings."
            )
            .frame(maxWidth: .infinity)
            .appSurface()
        }
    }

    private var inspectionInputCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("URL INSPECTION")
                    .font(.caption2.weight(.semibold))
                    .tracking(1.1)
                    .foregroundStyle(AppTheme.textSecondary)
                Text("Check Google’s latest indexed view")
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    inspectionField
                    inspectButton
                }
                VStack(spacing: 10) {
                    inspectionField
                    inspectButton.frame(maxWidth: .infinity)
                }
            }
        }
        .padding(18)
        .providerSurface(accent: SiteIntegrationProvider.googleSearchConsole.accentColor)
    }

    private var inspectionField: some View {
        HStack(spacing: 10) {
            Image(systemName: "link")
                .foregroundStyle(AppTheme.textSecondary)
            TextField("https://example.com/page", text: $viewModel.inspectionInput)
                .font(.subheadline.monospaced())
                .foregroundStyle(AppTheme.textPrimary)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { Task { await viewModel.inspectURL() } }
        }
        .padding(.horizontal, 13)
        .frame(minHeight: 48)
        .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                .strokeBorder(AppTheme.stroke, lineWidth: 0.6)
        }
    }

    private var inspectButton: some View {
        Button {
            Task { await viewModel.inspectURL(forceRefresh: true) }
        } label: {
            HStack(spacing: 8) {
                if viewModel.isInspecting {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                } else {
                    Image(systemName: "magnifyingglass")
                }
                Text(viewModel.isInspecting ? "Inspecting" : "Inspect URL")
            }
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .frame(minHeight: 48)
            .frame(maxWidth: horizontalSizeClass == .compact ? .infinity : nil)
            .background(
                SiteIntegrationProvider.googleSearchConsole.accentColor,
                in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
            )
        }
        .buttonStyle(.plain)
        .disabled(viewModel.isInspecting)
    }
}

// MARK: - Search Console presentation vocabulary

private enum SearchConsoleDetailSection: String, CaseIterable, Identifiable {
    case performance
    case sitemaps
    case inspection

    var id: Self { self }

    var title: String {
        switch self {
        case .performance: "Performance"
        case .sitemaps: "Sitemaps"
        case .inspection: "Inspect"
        }
    }

    var icon: String {
        switch self {
        case .performance: "chart.xyaxis.line"
        case .sitemaps: "doc.on.doc"
        case .inspection: "doc.text.magnifyingglass"
        }
    }
}

private enum SearchConsoleDatePreset: String, CaseIterable, Identifiable {
    case days7
    case days28
    case months3
    case months6
    case months12
    case months16
    case custom

    var id: Self { self }

    var shortLabel: String {
        switch self {
        case .days7: "7 days"
        case .days28: "28 days"
        case .months3: "3 months"
        case .months6: "6 months"
        case .months12: "12 months"
        case .months16: "16 months"
        case .custom: "Custom"
        }
    }

    func dates(customStart: Date, customEnd: Date) -> (start: Date, end: Date) {
        guard self != .custom else { return (min(customStart, customEnd), max(customStart, customEnd)) }
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: .now)
        let start: Date
        switch self {
        case .days7:
            start = calendar.date(byAdding: .day, value: -6, to: end) ?? end
        case .days28:
            start = calendar.date(byAdding: .day, value: -27, to: end) ?? end
        case .months3:
            start = calendar.date(byAdding: .month, value: -3, to: end) ?? end
        case .months6:
            start = calendar.date(byAdding: .month, value: -6, to: end) ?? end
        case .months12:
            start = calendar.date(byAdding: .month, value: -12, to: end) ?? end
        case .months16:
            start = calendar.date(byAdding: .month, value: -16, to: end) ?? end
        case .custom:
            start = customStart
        }
        return (start, end)
    }
}

private enum SearchConsoleMetricKind: String, CaseIterable, Identifiable {
    case clicks
    case impressions
    case ctr
    case position

    var id: Self { self }

    var title: String {
        switch self {
        case .clicks: "Clicks"
        case .impressions: "Impressions"
        case .ctr: "CTR"
        case .position: "Average position"
        }
    }

    var shortTitle: String {
        switch self {
        case .position: "Position"
        default: title
        }
    }

    var icon: String {
        switch self {
        case .clicks: "cursorarrow.click.2"
        case .impressions: "eye.fill"
        case .ctr: "percent"
        case .position: "list.number"
        }
    }

    var color: Color {
        switch self {
        case .clicks: SiteIntegrationProvider.googleSearchConsole.accentColor
        case .impressions: Color(red: 0.55, green: 0.39, blue: 0.93)
        case .ctr: Color(red: 0.10, green: 0.63, blue: 0.55)
        case .position: Color(red: 0.91, green: 0.57, blue: 0.12)
        }
    }

    func value(in row: SearchConsoleAnalyticsRow) -> Double {
        switch self {
        case .clicks: row.clicks
        case .impressions: row.impressions
        case .ctr: row.ctr
        case .position: row.position
        }
    }

    func formatted(_ value: Double, compact: Bool = false) -> String {
        switch self {
        case .clicks, .impressions:
            if compact {
                return value.formatted(.number.notation(.compactName).precision(.fractionLength(0...1)))
            }
            return value.formatted(.number.precision(.fractionLength(0)))
        case .ctr:
            return value.formatted(.percent.precision(.fractionLength(1)))
        case .position:
            return value.formatted(.number.precision(.fractionLength(1)))
        }
    }
}

private enum SearchConsoleSortField: String, CaseIterable {
    case dimension
    case clicks
    case impressions
    case ctr
    case position

    var title: String {
        switch self {
        case .dimension: "Dimension"
        case .clicks: "Clicks"
        case .impressions: "Impressions"
        case .ctr: "CTR"
        case .position: "Position"
        }
    }
}

private enum SearchConsoleCompactMetric {
    case count
}

private extension SearchConsoleSearchType {
    var displayName: String {
        switch self {
        case .web: "Web"
        case .image: "Image"
        case .video: "Video"
        case .news: "News"
        case .discover: "Discover"
        case .googleNews: "Google News"
        }
    }

    var icon: String {
        switch self {
        case .web: "globe"
        case .image: "photo"
        case .video: "play.rectangle"
        case .news: "newspaper"
        case .discover: "sparkles.rectangle.stack"
        case .googleNews: "rectangle.stack.badge.play"
        }
    }
}

private extension SearchConsoleDimension {
    static let breakdownCases: [Self] = [.date, .hour, .query, .page, .country, .device, .searchAppearance]

    var displayName: String {
        switch self {
        case .date: "Date"
        case .hour: "Hour"
        case .query: "Query"
        case .page: "Page"
        case .country: "Country"
        case .device: "Device"
        case .searchAppearance: "Search appearance"
        }
    }
}

private extension SearchConsoleAggregationType {
    var displayName: String {
        switch self {
        case .auto: "Automatic"
        case .byPage: "By page"
        case .byProperty: "By property"
        case .byNewsShowcasePanel: "News Showcase panel"
        }
    }
}

private extension SearchConsoleDataState {
    var displayName: String {
        switch self {
        case .final: "Final only"
        case .all: "All available"
        case .hourlyAll: "Hourly"
        }
    }

    var explanation: String {
        switch self {
        case .final: "Only fully processed Search Console data."
        case .all: "Includes fresh rows that Google may still update."
        case .hourlyAll: "Fresh hourly rows, including incomplete data."
        }
    }
}

private extension SearchConsoleFilterDimension {
    var displayName: String {
        switch self {
        case .query: "Query"
        case .page: "Page"
        case .country: "Country"
        case .device: "Device"
        case .searchAppearance: "Search appearance"
        }
    }
}

private extension SearchConsoleFilterOperator {
    var displayName: String {
        switch self {
        case .contains: "Contains"
        case .equals: "Exactly matches"
        case .notContains: "Does not contain"
        case .notEquals: "Does not match"
        case .includingRegex: "Matches regex"
        case .excludingRegex: "Does not match regex"
        }
    }
}

private enum SearchConsoleFormatting {
    static func propertyName(_ siteURL: String) -> String {
        if siteURL.hasPrefix("sc-domain:") {
            return String(siteURL.dropFirst("sc-domain:".count))
        }
        guard let components = URLComponents(string: siteURL), let host = components.host else {
            return siteURL
        }
        let path = components.path == "/" ? "" : components.path
        return host + path
    }

    static func permission(_ rawValue: String) -> String {
        switch rawValue.lowercased() {
        case "siteowner": "Owner"
        case "sitefulluser": "Full access"
        case "siterestricteduser": "Restricted"
        case "siteunverifieduser": "Unverified"
        default: humanized(rawValue)
        }
    }

    static func permissionTone(_ rawValue: String) -> AppStatusTone {
        switch rawValue.lowercased() {
        case "siteowner", "sitefulluser": .success
        case "siterestricteduser": .warning
        case "siteunverifieduser": .danger
        default: .neutral
        }
    }

    nonisolated static func humanized(_ value: String) -> String {
        let withSpaces = value
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
        var result = ""
        for character in withSpaces {
            if character.isUppercase,
               let previous = result.last,
               !previous.isWhitespace,
               !previous.isUppercase {
                result.append(" ")
            }
            result.append(character)
        }
        return result.split(separator: " ").map { word in
            let lower = word.lowercased()
            return lower.prefix(1).uppercased() + lower.dropFirst()
        }.joined(separator: " ")
    }

    static func googleDate(_ value: String) -> Date? {
        SearchConsoleDateRange.localDisplayDate(value)
    }

    static func googleDateOrHour(_ value: String) -> Date? {
        if let date = googleDate(value) { return date }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let standard = ISO8601DateFormatter()
        return fractional.date(from: value) ?? standard.date(from: value)
    }

    static func timestamp(_ value: String?) -> String {
        guard let value, !value.isEmpty else { return "Not reported" }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let standard = ISO8601DateFormatter()
        let date = fractional.date(from: value) ?? standard.date(from: value)
        return date?.formatted(.dateTime.month(.abbreviated).day().year().hour().minute()) ?? value
    }

    static func verdictTone(_ value: String?) -> AppStatusTone {
        guard let value else { return .neutral }
        let normalized = value.lowercased()
        if normalized.contains("fail") || normalized.contains("blocked")
            || normalized.contains("not") || normalized.contains("error") {
            return .danger
        }
        if normalized.contains("pass") || normalized.contains("allowed")
            || normalized.contains("success") || normalized.contains("indexed") {
            return .success
        }
        if normalized.contains("partial") || normalized.contains("neutral")
            || normalized.contains("unknown") || normalized.contains("warning") {
            return .warning
        }
        return .neutral
    }
}

// MARK: - Performance components

private struct SearchConsoleQueryControlLabel: View {
    let title: String
    let icon: String

    var body: some View {
        Label(title, systemImage: icon)
            .font(.caption.weight(.semibold))
            .foregroundStyle(AppTheme.textPrimary)
            .padding(.horizontal, 11)
            .frame(minHeight: 38)
            .background(AppTheme.surfaceRaised, in: Capsule())
            .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
    }
}

private struct SearchConsoleMetricCard: View {
    let metric: SearchConsoleMetricKind
    let value: Double
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Image(systemName: metric.icon)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(metric.color)
                    Spacer()
                    if selected {
                        Circle()
                            .fill(metric.color)
                            .frame(width: 6, height: 6)
                            .shadow(color: metric.color.opacity(0.65), radius: 3)
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text(metric.formatted(value, compact: true))
                        .font(.title2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppTheme.textPrimary)
                        .contentTransition(.numericText())
                    Text(metric.shortTitle.uppercased())
                        .font(.caption2.weight(.semibold))
                        .tracking(0.85)
                        .foregroundStyle(AppTheme.textSecondary)
                        .lineLimit(1)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 102, alignment: .leading)
            .background(
                selected ? metric.color.opacity(0.085) : AppTheme.surface,
                in: RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: AppTheme.panelRadius, style: .continuous)
                    .strokeBorder(selected ? metric.color.opacity(0.45) : AppTheme.stroke, lineWidth: selected ? 1 : 0.6)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(metric.title), \(metric.formatted(value))")
        .accessibilityHint("Shows \(metric.title.lowercased()) in the timeline")
    }
}

private struct SearchConsoleChartPoint: Identifiable {
    let date: Date
    let row: SearchConsoleAnalyticsRow

    var id: Date { date }
}

private struct SearchConsolePerformanceChart: View {
    let rows: [SearchConsoleAnalyticsRow]
    let metric: SearchConsoleMetricKind

    @State private var selectedDate: Date?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var isHourly: Bool {
        rows.first?.keys.first?.contains("T") == true
    }

    private var points: [SearchConsoleChartPoint] {
        rows.compactMap { row in
            guard let raw = row.keys.first,
                  let date = SearchConsoleFormatting.googleDateOrHour(raw) else { return nil }
            return SearchConsoleChartPoint(date: date, row: row)
        }
        .sorted { $0.date < $1.date }
    }

    private var selectedPoint: SearchConsoleChartPoint? {
        guard let selectedDate else { return nil }
        return points.min {
            abs($0.date.timeIntervalSince(selectedDate)) < abs($1.date.timeIntervalSince(selectedDate))
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 9) {
                Text(metric.formatted(selectedPoint.map { metric.value(in: $0.row) } ?? headlineValue, compact: true))
                    .font(.title3.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.textPrimary)
                    .contentTransition(.numericText())

                if let selectedPoint {
                    Text(
                        selectedPoint.date,
                        format: isHourly
                            ? .dateTime.month(.abbreviated).day().hour()
                            : .dateTime.month(.abbreviated).day()
                    )
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(metric.color)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(metric.color.opacity(0.11), in: Capsule())
                } else {
                    Text("Drag across the chart")
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }
            .animation(reduceMotion ? nil : .snappy(duration: 0.16), value: selectedPoint?.date)

            Chart {
                if metric == .clicks || metric == .impressions {
                    ForEach(points) { point in
                        AreaMark(
                            x: .value("Date", point.date),
                            y: .value(metric.title, metric.value(in: point.row))
                        )
                        .foregroundStyle(
                            LinearGradient(
                                colors: [metric.color.opacity(0.26), metric.color.opacity(0.02)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .interpolationMethod(.catmullRom)
                    }
                }

                ForEach(points) { point in
                    LineMark(
                        x: .value("Date", point.date),
                        y: .value(metric.title, metric.value(in: point.row))
                    )
                    .foregroundStyle(metric.color)
                    .interpolationMethod(.catmullRom)
                    .lineStyle(StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
                }

                if let selectedPoint {
                    RuleMark(x: .value("Selected date", selectedPoint.date))
                        .foregroundStyle(AppTheme.textPrimary.opacity(0.22))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))

                    PointMark(
                        x: .value("Selected date", selectedPoint.date),
                        y: .value(metric.title, metric.value(in: selectedPoint.row))
                    )
                    .symbolSize(64)
                    .foregroundStyle(metric.color)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 5)) {
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                        .foregroundStyle(AppTheme.divider)
                    AxisValueLabel(format: .dateTime.month(.abbreviated).day())
                        .font(.caption2)
                        .foregroundStyle(AppTheme.textSecondary)
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5))
                        .foregroundStyle(AppTheme.divider)
                    AxisValueLabel {
                        if let number = value.as(Double.self) {
                            Text(metric.formatted(number, compact: true))
                                .font(.caption2)
                                .foregroundStyle(AppTheme.textSecondary)
                        }
                    }
                }
            }
            .chartXSelection(value: $selectedDate)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(isHourly ? "Hourly" : "Daily") \(metric.title) chart")
            .accessibilityValue("\(points.count) \(isHourly ? "hourly" : "daily") points. Total \(metric.formatted(headlineValue)).")

            if metric == .position {
                Text("A lower average position is better.")
                    .font(.caption2)
                    .foregroundStyle(AppTheme.textTertiary)
            }
        }
    }

    private var headlineValue: Double {
        switch metric {
        case .clicks, .impressions:
            return points.reduce(0) { $0 + metric.value(in: $1.row) }
        case .ctr:
            let clicks = points.reduce(0) { $0 + $1.row.clicks }
            let impressions = points.reduce(0) { $0 + $1.row.impressions }
            return impressions > 0 ? clicks / impressions : 0
        case .position:
            let impressions = points.reduce(0) { $0 + $1.row.impressions }
            let weighted = points.reduce(0) { $0 + ($1.row.position * $1.row.impressions) }
            return impressions > 0 ? weighted / impressions : 0
        }
    }
}

private struct SearchConsoleAppliedFilters: View {
    let filters: [SearchConsoleDimensionFilter]
    let remove: (Int) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Array(filters.enumerated()), id: \.offset) { index, filter in
                    HStack(spacing: 7) {
                        Text("\(filter.dimension.displayName) \(filter.operator.displayName.lowercased()) “\(filter.expression)”")
                            .font(.caption.weight(.medium))
                            .lineLimit(1)
                        Button {
                            remove(index)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(AppTheme.textTertiary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Remove filter")
                    }
                    .foregroundStyle(AppTheme.textSecondary)
                    .padding(.leading, 11)
                    .padding(.trailing, 8)
                    .frame(minHeight: 34)
                    .background(AppTheme.surfaceRaised, in: Capsule())
                    .overlay(Capsule().strokeBorder(AppTheme.stroke, lineWidth: 0.5))
                }
            }
        }
        .accessibilityLabel("Applied filters")
    }
}

private struct SearchConsoleBreakdownTable: View {
    @Bindable var viewModel: SearchConsoleDetailViewModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        VStack(spacing: 0) {
            if viewModel.isLoadingBreakdown, viewModel.breakdownRows.isEmpty {
                ProgressView("Loading \(viewModel.selectedDimension.displayName.lowercased()) rows")
                    .font(.footnote)
                    .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                    .foregroundStyle(AppTheme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 44)
            } else if viewModel.breakdownRows.isEmpty {
                AppEmptyState(
                    icon: "tablecells",
                    title: "No breakdown rows",
                    message: "Google returned no \(viewModel.selectedDimension.displayName.lowercased()) rows for this query."
                )
                .frame(maxWidth: .infinity)
            } else if horizontalSizeClass == .regular, !dynamicTypeSize.isAccessibilitySize {
                regularTable
            } else {
                compactTable
            }

            if !viewModel.breakdownRows.isEmpty {
                pagination
            }
        }
        .appSurface()
        .overlay(alignment: .topTrailing) {
            if viewModel.hasRetainedBreakdown {
                ProgressView()
                    .controlSize(.small)
                    .tint(SiteIntegrationProvider.googleSearchConsole.accentColor)
                    .padding(14)
                    .accessibilityLabel("Updating breakdown")
            }
        }
    }

    private var regularTable: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                sortHeader(.dimension, alignment: .leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                sortHeader(.clicks).frame(width: 90)
                sortHeader(.impressions).frame(width: 105)
                sortHeader(.ctr).frame(width: 78)
                sortHeader(.position).frame(width: 86)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 46)
            .background(AppTheme.surfaceRaised)

            ForEach(Array(viewModel.pageRows.enumerated()), id: \.offset) { index, row in
                HStack(spacing: 12) {
                    Text(viewModel.dimensionValue(for: row))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(2)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    metricText(.clicks, row: row).frame(width: 90)
                    metricText(.impressions, row: row).frame(width: 105)
                    metricText(.ctr, row: row).frame(width: 78)
                    metricText(.position, row: row).frame(width: 86)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(index.isMultiple(of: 2) ? Color.clear : AppTheme.surfaceRaised.opacity(0.34))
                if index < viewModel.pageRows.count - 1 {
                    AppInsetDivider(leading: 16)
                }
            }
        }
    }

    private var compactTable: some View {
        VStack(spacing: 0) {
            Menu {
                ForEach(SearchConsoleSortField.allCases, id: \.self) { field in
                    Button {
                        viewModel.toggleSort(field)
                    } label: {
                        Label(
                            field.title,
                            systemImage: viewModel.sortField == field
                                ? (viewModel.sortAscending ? "arrow.up" : "arrow.down")
                                : "arrow.up.arrow.down"
                        )
                    }
                }
            } label: {
                HStack {
                    Label("Sort by \(viewModel.sortField.title)", systemImage: "arrow.up.arrow.down")
                    Spacer()
                    Text(viewModel.sortAscending ? "Ascending" : "Descending")
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(AppTheme.textSecondary)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(AppTheme.surfaceRaised)
            }

            ForEach(Array(viewModel.pageRows.enumerated()), id: \.offset) { index, row in
                VStack(alignment: .leading, spacing: 12) {
                    Text(viewModel.dimensionValue(for: row))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(3)
                        .textSelection(.enabled)

                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .leading), count: 2), spacing: 10) {
                        compactMetric(.clicks, row: row)
                        compactMetric(.impressions, row: row)
                        compactMetric(.ctr, row: row)
                        compactMetric(.position, row: row)
                    }
                }
                .padding(14)
                if index < viewModel.pageRows.count - 1 {
                    AppInsetDivider(leading: 14)
                }
            }
        }
    }

    private var pagination: some View {
        HStack(spacing: 12) {
            Button {
                viewModel.previousPage()
            } label: {
                Image(systemName: "chevron.left")
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .disabled(viewModel.currentPage == 0)

            Spacer()
            VStack(spacing: 2) {
                Text(viewModel.pageRangeLabel)
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(AppTheme.textPrimary)
                Text("Page \(viewModel.currentPage + 1) of \(max(1, viewModel.totalPages))")
                    .font(.caption2)
                    .foregroundStyle(AppTheme.textSecondary)
            }
            Spacer()

            Button {
                viewModel.nextPage()
            } label: {
                Image(systemName: "chevron.right")
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .disabled(viewModel.currentPage >= viewModel.totalPages - 1)
        }
        .foregroundStyle(AppTheme.textPrimary)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(AppTheme.surfaceRaised.opacity(0.55))
        .overlay(alignment: .top) { Rectangle().fill(AppTheme.divider).frame(height: 0.5) }
    }

    private func sortHeader(
        _ field: SearchConsoleSortField,
        alignment: Alignment = .center
    ) -> some View {
        Button {
            viewModel.toggleSort(field)
        } label: {
            HStack(spacing: 4) {
                Text(field == .dimension ? viewModel.selectedDimension.displayName : field.title)
                    .lineLimit(1)
                if viewModel.sortField == field {
                    Image(systemName: viewModel.sortAscending ? "arrow.up" : "arrow.down")
                        .font(.caption2.weight(.bold))
                }
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(viewModel.sortField == field ? AppTheme.textPrimary : AppTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: alignment)
        }
        .buttonStyle(.plain)
    }

    private func metricText(_ metric: SearchConsoleMetricKind, row: SearchConsoleAnalyticsRow) -> some View {
        Text(metric.formatted(metric.value(in: row), compact: true))
            .font(.caption.weight(.semibold).monospacedDigit())
            .foregroundStyle(AppTheme.textSecondary)
            .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private func compactMetric(_ metric: SearchConsoleMetricKind, row: SearchConsoleAnalyticsRow) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(metric.formatted(metric.value(in: row), compact: true))
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
            Text(metric.shortTitle.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.6)
                .foregroundStyle(AppTheme.textTertiary)
        }
    }
}

private struct SearchConsoleCompactStat: View {
    let label: String
    let value: Double
    let metric: SearchConsoleCompactMetric
    var tint: Color = SiteIntegrationProvider.googleSearchConsole.accentColor

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(value.formatted(.number.notation(.compactName).precision(.fractionLength(0...1))))
                .font(.title3.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
            Text(label.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.7)
                .foregroundStyle(AppTheme.textSecondary)
                .lineLimit(2)
        }
        .padding(13)
        .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        .background(tint.opacity(0.075), in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous)
                .strokeBorder(tint.opacity(0.16), lineWidth: 0.5)
        }
    }
}

// MARK: - Sitemap components

private struct SearchConsoleSitemapCard: View {
    let sitemap: SearchConsoleSitemap
    @State private var isExpanded = false

    private var issueCount: Int64 { sitemap.errors + sitemap.warnings }
    private var statusText: String {
        if sitemap.isPending { return "Pending" }
        if sitemap.errors > 0 { return "Errors" }
        if sitemap.warnings > 0 { return "Warnings" }
        return "Processed"
    }

    private var statusTone: AppStatusTone {
        if sitemap.isPending { return .progress }
        if sitemap.errors > 0 { return .danger }
        if sitemap.warnings > 0 { return .warning }
        return .success
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                AppIconTile(
                    icon: sitemap.isSitemapsIndex ? "doc.on.doc.fill" : "doc.text.fill",
                    tint: SiteIntegrationProvider.googleSearchConsole.accentColor,
                    size: 40
                )

                VStack(alignment: .leading, spacing: 5) {
                    Text(sitemap.path)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(3)
                        .textSelection(.enabled)
                    Text(sitemap.isSitemapsIndex ? "Sitemap index" : SearchConsoleFormatting.humanized(sitemap.type ?? "Sitemap"))
                        .font(.caption)
                        .foregroundStyle(AppTheme.textSecondary)
                }
                Spacer(minLength: 4)
                AppStatusBadge(text: statusText, tone: statusTone)
            }

            HStack(spacing: 0) {
                sitemapStat(label: "Contents", value: Int64(sitemap.contents.count))
                sitemapStat(label: "Warnings", value: sitemap.warnings)
                sitemapStat(label: "Errors", value: sitemap.errors)
            }

            DisclosureGroup(isExpanded: $isExpanded) {
                VStack(alignment: .leading, spacing: 14) {
                    Rectangle().fill(AppTheme.divider).frame(height: 0.5)

                    SearchConsoleLabeledValueGrid(values: [
                        ("Pending", sitemap.isPending ? "Yes" : "No"),
                        ("Sitemap index", sitemap.isSitemapsIndex ? "Yes" : "No"),
                        ("Type", SearchConsoleFormatting.humanized(sitemap.type ?? "Not reported")),
                        ("Last submitted", SearchConsoleFormatting.timestamp(sitemap.lastSubmitted)),
                        ("Last downloaded", SearchConsoleFormatting.timestamp(sitemap.lastDownloaded)),
                        ("Warnings", sitemap.warnings.formatted()),
                        ("Errors", sitemap.errors.formatted()),
                    ])

                    VStack(alignment: .leading, spacing: 8) {
                        Text("CONTENT COUNTS")
                            .font(.caption2.weight(.semibold))
                            .tracking(0.9)
                            .foregroundStyle(AppTheme.textSecondary)

                        if sitemap.contents.isEmpty {
                            Text("Google did not include content counts for this sitemap.")
                                .font(.footnote)
                                .foregroundStyle(AppTheme.textSecondary)
                        } else {
                            ForEach(Array(sitemap.contents.enumerated()), id: \.offset) { index, content in
                                HStack(spacing: 12) {
                                    Text(SearchConsoleFormatting.humanized(content.type))
                                        .font(.footnote.weight(.semibold))
                                        .foregroundStyle(AppTheme.textPrimary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text(content.submitted.formatted())
                                            .font(.footnote.weight(.semibold).monospacedDigit())
                                            .foregroundStyle(AppTheme.textPrimary)
                                        Text("Submitted")
                                            .font(.caption2)
                                            .foregroundStyle(AppTheme.textTertiary)
                                    }
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text(content.indexed?.formatted() ?? "—")
                                            .font(.footnote.weight(.semibold).monospacedDigit())
                                            .foregroundStyle(AppTheme.textPrimary)
                                        Text("Indexed")
                                            .font(.caption2)
                                            .foregroundStyle(AppTheme.textTertiary)
                                    }
                                }
                                .padding(.vertical, 6)
                                if index < sitemap.contents.count - 1 {
                                    Rectangle().fill(AppTheme.divider).frame(height: 0.5)
                                }
                            }
                        }
                    }

                    Text(sitemap.path)
                        .font(.caption.monospaced())
                        .foregroundStyle(AppTheme.textTertiary)
                        .textSelection(.enabled)
                        .accessibilityLabel("Sitemap path: \(sitemap.path)")
                }
                .padding(.top, 10)
            } label: {
                HStack {
                    Text(isExpanded ? "Hide complete details" : "Show complete details")
                        .font(.footnote.weight(.semibold))
                    Spacer()
                    if issueCount > 0 {
                        Text("\(issueCount.formatted()) issues")
                            .font(.caption.weight(.semibold).monospacedDigit())
                            .foregroundStyle(statusTone.color)
                    }
                }
                .foregroundStyle(SiteIntegrationProvider.googleSearchConsole.accentColor)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
        }
        .padding(16)
        .appSurface()
    }

    private func sitemapStat(label: String, value: Int64) -> some View {
        VStack(spacing: 3) {
            Text(value.formatted())
                .font(.subheadline.weight(.semibold).monospacedDigit())
                .foregroundStyle(AppTheme.textPrimary)
            Text(label.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.55)
                .foregroundStyle(AppTheme.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - URL inspection components

private struct SearchConsoleInspectionResultView: View {
    let result: SearchConsoleURLInspectionResult
    let inspectedURL: String
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    var body: some View {
        VStack(spacing: 16) {
            inspectionSummary

            AppAdaptiveTwoPane(primaryMinimumWidth: 430, secondaryMinimumWidth: 340) {
                VStack(spacing: 16) {
                    if let index = result.indexStatusResult {
                        indexCard(index)
                    } else {
                        missingCard(title: "Index status", message: "Google did not return an index-status result.")
                    }

                    if let amp = result.ampResult {
                        ampCard(amp)
                    }
                }
            } secondary: {
                VStack(spacing: 16) {
                    if let mobile = result.mobileUsabilityResult {
                        mobileCard(mobile)
                    }
                    if let rich = result.richResultsResult {
                        richResultsCard(rich)
                    }
                    if result.ampResult == nil,
                       result.mobileUsabilityResult == nil,
                       result.richResultsResult == nil {
                        missingCard(
                            title: "Enhancements",
                            message: "Google did not return AMP, mobile-usability or rich-result findings for this URL."
                        )
                    }
                }
            }
        }
    }

    private var inspectionSummary: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 13) {
                AppIconTile(
                    icon: "doc.text.magnifyingglass",
                    tint: SiteIntegrationProvider.googleSearchConsole.accentColor,
                    size: 42
                )
                VStack(alignment: .leading, spacing: 5) {
                    Text("INSPECTED URL")
                        .font(.caption2.weight(.semibold))
                        .tracking(0.9)
                        .foregroundStyle(AppTheme.textSecondary)
                    Text(inspectedURL)
                        .font(.subheadline.weight(.semibold).monospaced())
                        .foregroundStyle(AppTheme.textPrimary)
                        .lineLimit(4)
                        .textSelection(.enabled)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                if let verdict = result.indexStatusResult?.verdict {
                    AppStatusBadge(
                        text: "Index \(SearchConsoleFormatting.humanized(verdict))",
                        tone: SearchConsoleFormatting.verdictTone(verdict)
                    )
                }
                if let verdict = result.ampResult?.verdict {
                    AppStatusBadge(
                        text: "AMP \(SearchConsoleFormatting.humanized(verdict))",
                        tone: SearchConsoleFormatting.verdictTone(verdict)
                    )
                }
                if let verdict = result.richResultsResult?.verdict {
                    AppStatusBadge(
                        text: "Rich results \(SearchConsoleFormatting.humanized(verdict))",
                        tone: SearchConsoleFormatting.verdictTone(verdict)
                    )
                }
            }

            if let link = result.inspectionResultLink,
               let url = URL(string: link) {
                Link(destination: url) {
                    Label("Open this result in Search Console", systemImage: "arrow.up.right.square")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(SiteIntegrationProvider.googleSearchConsole.accentColor)
                        .frame(minHeight: 44, alignment: .leading)
                }
            }
        }
        .padding(18)
        .providerSurface(accent: SiteIntegrationProvider.googleSearchConsole.accentColor)
    }

    private func indexCard(_ index: SearchConsoleIndexStatusInspectionResult) -> some View {
        SearchConsoleInspectionCard(
            icon: "checkmark.seal.fill",
            title: "Index status",
            verdict: index.verdict
        ) {
            SearchConsoleLabeledValueGrid(values: [
                ("Coverage", index.coverageState ?? "Not reported"),
                ("Indexing", SearchConsoleFormatting.humanized(index.indexingState ?? "Not reported")),
                ("Robots.txt", SearchConsoleFormatting.humanized(index.robotsTxtState ?? "Not reported")),
                ("Page fetch", SearchConsoleFormatting.humanized(index.pageFetchState ?? "Not reported")),
                ("Crawled as", SearchConsoleFormatting.humanized(index.crawledAs ?? "Not reported")),
                ("Last crawl", SearchConsoleFormatting.timestamp(index.lastCrawlTime)),
                ("Google canonical", index.googleCanonical ?? "Not reported"),
                ("User canonical", index.userCanonical ?? "Not reported"),
            ])

            SearchConsoleStringList(title: "Sitemaps", values: index.sitemap)
            SearchConsoleStringList(title: "Referring URLs", values: index.referringUrls)
        }
    }

    private func ampCard(_ amp: SearchConsoleAMPInspectionResult) -> some View {
        SearchConsoleInspectionCard(
            icon: "bolt.fill",
            title: "AMP",
            verdict: amp.verdict
        ) {
            SearchConsoleLabeledValueGrid(values: [
                ("AMP URL", amp.ampUrl ?? "Not reported"),
                ("Index status", SearchConsoleFormatting.humanized(amp.ampIndexStatusVerdict ?? "Not reported")),
                ("Indexing", SearchConsoleFormatting.humanized(amp.indexingState ?? "Not reported")),
                ("Robots.txt", SearchConsoleFormatting.humanized(amp.robotsTxtState ?? "Not reported")),
                ("Page fetch", SearchConsoleFormatting.humanized(amp.pageFetchState ?? "Not reported")),
                ("Last crawl", SearchConsoleFormatting.timestamp(amp.lastCrawlTime)),
            ])

            SearchConsoleIssueList(
                title: "AMP issues",
                issues: amp.issues.map {
                    SearchConsolePresentedIssue(
                        title: $0.issueMessage ?? "AMP issue",
                        severity: $0.severity
                    )
                }
            )
        }
    }

    private func mobileCard(_ mobile: SearchConsoleMobileUsabilityInspectionResult) -> some View {
        SearchConsoleInspectionCard(
            icon: "iphone",
            title: "Mobile usability",
            verdict: mobile.verdict
        ) {
            SearchConsoleIssueList(
                title: "Mobile issues",
                issues: mobile.issues.map {
                    SearchConsolePresentedIssue(
                        title: $0.message ?? SearchConsoleFormatting.humanized($0.issueType ?? "Mobile issue"),
                        severity: $0.severity,
                        detail: $0.issueType.map(SearchConsoleFormatting.humanized)
                    )
                }
            )
        }
    }

    private func richResultsCard(_ rich: SearchConsoleRichResultsInspectionResult) -> some View {
        SearchConsoleInspectionCard(
            icon: "sparkles.rectangle.stack.fill",
            title: "Rich results",
            verdict: rich.verdict
        ) {
            if rich.detectedItems.isEmpty {
                Text("Google did not report any detected rich-result types.")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textSecondary)
            } else {
                ForEach(Array(rich.detectedItems.enumerated()), id: \.offset) { resultIndex, detected in
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text(SearchConsoleFormatting.humanized(detected.richResultType))
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(AppTheme.textPrimary)
                            Spacer()
                            Text("\(detected.items.count.formatted()) items")
                                .font(.caption.weight(.semibold).monospacedDigit())
                                .foregroundStyle(AppTheme.textSecondary)
                        }

                        ForEach(Array(detected.items.enumerated()), id: \.offset) { itemIndex, item in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(item.name ?? "Detected item \(itemIndex + 1)")
                                    .font(.footnote.weight(.semibold))
                                    .foregroundStyle(AppTheme.textPrimary)
                                SearchConsoleIssueList(
                                    title: "Item issues",
                                    issues: item.issues.map {
                                        SearchConsolePresentedIssue(
                                            title: $0.issueMessage ?? "Rich-result issue",
                                            severity: $0.severity
                                        )
                                    }
                                )
                            }
                            .padding(11)
                            .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
                        }
                    }
                    if resultIndex < rich.detectedItems.count - 1 {
                        Rectangle().fill(AppTheme.divider).frame(height: 0.5)
                    }
                }
            }
        }
    }

    private func missingCard(title: String, message: String) -> some View {
        AppFeedbackBanner(
            title: title,
            message: message,
            icon: "minus.circle.fill",
            tint: AppTheme.textSecondary
        )
    }
}

private struct SearchConsoleInspectionCard<Content: View>: View {
    let icon: String
    let title: String
    let verdict: String?
    let content: Content

    init(
        icon: String,
        title: String,
        verdict: String?,
        @ViewBuilder content: () -> Content
    ) {
        self.icon = icon
        self.title = title
        self.verdict = verdict
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                AppIconTile(
                    icon: icon,
                    tint: SearchConsoleFormatting.verdictTone(verdict).color,
                    size: 36
                )
                Text(title)
                    .font(.headline)
                    .foregroundStyle(AppTheme.textPrimary)
                Spacer()
                if let verdict {
                    AppStatusBadge(
                        text: SearchConsoleFormatting.humanized(verdict),
                        tone: SearchConsoleFormatting.verdictTone(verdict)
                    )
                }
            }
            content
        }
        .padding(16)
        .appSurface()
    }
}

private struct SearchConsoleLabeledValueGrid: View {
    let values: [(String, String)]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), alignment: .topLeading)], spacing: 10) {
            ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                VStack(alignment: .leading, spacing: 4) {
                    Text(value.0.uppercased())
                        .font(.caption2.weight(.semibold))
                        .tracking(0.55)
                        .foregroundStyle(AppTheme.textTertiary)
                    Text(value.1)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AppTheme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
                .padding(11)
                .frame(maxWidth: .infinity, minHeight: 62, alignment: .topLeading)
                .background(AppTheme.surfaceRaised, in: RoundedRectangle(cornerRadius: AppTheme.controlRadius, style: .continuous))
            }
        }
    }
}

private struct SearchConsoleStringList: View {
    let title: String
    let values: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(title.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.7)
                .foregroundStyle(AppTheme.textSecondary)
            if values.isEmpty {
                Text("None reported")
                    .font(.footnote)
                    .foregroundStyle(AppTheme.textTertiary)
            } else {
                ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(SiteIntegrationProvider.googleSearchConsole.accentColor)
                            .frame(width: 5, height: 5)
                            .padding(.top, 6)
                        Text(value)
                            .font(.footnote.monospaced())
                            .foregroundStyle(AppTheme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                    }
                }
            }
        }
    }
}

private struct SearchConsolePresentedIssue: Identifiable {
    let title: String
    let severity: String?
    var detail: String? = nil

    var id: String { "\(severity ?? "")|\(title)|\(detail ?? "")" }
}

private struct SearchConsoleIssueList: View {
    let title: String
    let issues: [SearchConsolePresentedIssue]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased())
                .font(.caption2.weight(.semibold))
                .tracking(0.7)
                .foregroundStyle(AppTheme.textSecondary)
            if issues.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(AppTheme.success)
                    Text("No issues reported")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(AppTheme.textSecondary)
                }
            } else {
                ForEach(issues) { issue in
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: issueIcon(issue.severity))
                            .font(.caption)
                            .foregroundStyle(SearchConsoleFormatting.verdictTone(issue.severity).color)
                            .padding(.top, 2)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(issue.title)
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(AppTheme.textPrimary)
                            if let detail = issue.detail {
                                Text(detail)
                                    .font(.caption)
                                    .foregroundStyle(AppTheme.textSecondary)
                            }
                            if let severity = issue.severity {
                                Text(SearchConsoleFormatting.humanized(severity))
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(SearchConsoleFormatting.verdictTone(severity).color)
                            }
                        }
                    }
                }
            }
        }
    }

    private func issueIcon(_ severity: String?) -> String {
        let normalized = severity?.lowercased() ?? ""
        if normalized.contains("error") || normalized.contains("fail")
            || normalized.contains("critical") || normalized.contains("invalid") {
            return "xmark.octagon.fill"
        }
        return "exclamationmark.triangle.fill"
    }
}

// MARK: - Query editors

private struct SearchConsoleDateRangeSheet: View {
    @State private var startDate: Date
    @State private var endDate: Date
    let apply: (Date, Date) -> Void
    @Environment(\.dismiss) private var dismiss

    init(startDate: Date, endDate: Date, apply: @escaping (Date, Date) -> Void) {
        _startDate = State(initialValue: startDate)
        _endDate = State(initialValue: endDate)
        self.apply = apply
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker(
                        "Start date",
                        selection: $startDate,
                        in: Self.minimumDate...Date.now,
                        displayedComponents: .date
                    )
                    DatePicker(
                        "End date",
                        selection: $endDate,
                        in: Self.minimumDate...Date.now,
                        displayedComponents: .date
                    )
                } header: {
                    Text("Performance window")
                } footer: {
                    Text("Search Console reports dates in Pacific Time. Both boundary dates are included.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppTheme.canvas)
            .navigationTitle("Custom date range")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        apply(min(startDate, endDate), max(startDate, endDate))
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private static var minimumDate: Date {
        Calendar.current.date(from: DateComponents(year: 2000, month: 1, day: 1)) ?? .distantPast
    }
}

private struct SearchConsoleFilterEditor: View {
    @State private var filters: [SearchConsoleDimensionFilter]
    @State private var draftDimension: SearchConsoleFilterDimension = .query
    @State private var draftOperator: SearchConsoleFilterOperator = .contains
    @State private var draftExpression = ""
    let apply: ([SearchConsoleDimensionFilter]) -> Void
    @Environment(\.dismiss) private var dismiss

    init(
        filters: [SearchConsoleDimensionFilter],
        apply: @escaping ([SearchConsoleDimensionFilter]) -> Void
    ) {
        _filters = State(initialValue: filters)
        self.apply = apply
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Add an AND filter") {
                    Picker("Dimension", selection: $draftDimension) {
                        ForEach(SearchConsoleFilterDimension.allCases, id: \.self) { dimension in
                            Text(dimension.displayName).tag(dimension)
                        }
                    }

                    Picker("Match", selection: $draftOperator) {
                        ForEach(SearchConsoleFilterOperator.allCases, id: \.self) { filterOperator in
                            Text(filterOperator.displayName).tag(filterOperator)
                        }
                    }

                    TextField("Value or regular expression", text: $draftExpression, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(1...4)

                    Button {
                        let expression = draftExpression.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !expression.isEmpty else { return }
                        filters.append(SearchConsoleDimensionFilter(
                            dimension: draftDimension,
                            operator: draftOperator,
                            expression: expression
                        ))
                        draftExpression = ""
                    } label: {
                        Label("Add filter", systemImage: "plus.circle.fill")
                    }
                    .disabled(draftExpression.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                Section {
                    if filters.isEmpty {
                        ContentUnavailableView(
                            "No filters",
                            systemImage: "line.3.horizontal.decrease.circle",
                            description: Text("Results include every matching row.")
                        )
                    } else {
                        ForEach(Array(filters.enumerated()), id: \.offset) { index, filter in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(filter.dimension.displayName)
                                    .font(.subheadline.weight(.semibold))
                                Text("\(filter.operator.displayName): \(filter.expression)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .swipeActions {
                                Button("Delete", role: .destructive) {
                                    filters.remove(at: index)
                                }
                            }
                        }
                        Button("Clear all", role: .destructive) {
                            filters.removeAll()
                        }
                    }
                } header: {
                    Text("Applied filters")
                } footer: {
                    Text("Search Console combines these filters with AND. Changes load only after you tap Apply.")
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppTheme.canvas)
            .navigationTitle("Performance filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        apply(filters)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
