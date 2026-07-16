import Foundation

// MARK: - Search Analytics request and response

nonisolated struct SearchConsoleDateRange: Codable, Equatable, Sendable {
    let startDate: String
    let endDate: String

    init(startDate: String, endDate: String) {
        self.startDate = startDate
        self.endDate = endDate
    }

    init(startDate: Date, endDate: Date, calendar: Calendar = .autoupdatingCurrent) {
        self.startDate = Self.googleDateString(startDate, calendar: calendar)
        self.endDate = Self.googleDateString(endDate, calendar: calendar)
    }

    nonisolated static func googleDateString(
        _ date: Date,
        calendar suppliedCalendar: Calendar = Calendar(identifier: .gregorian)
    ) -> String {
        let calendar = suppliedCalendar
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
    }

    /// Search Console date dimensions are calendar days, not instants. Build them at local noon
    /// so formatting in the user's time zone cannot shift a Pacific-midnight value to yesterday.
    nonisolated static func localDisplayDate(
        _ value: String,
        calendar suppliedCalendar: Calendar = .autoupdatingCurrent
    ) -> Date? {
        let pieces = value.split(separator: "-", omittingEmptySubsequences: false)
        guard pieces.count == 3,
              pieces[0].count == 4,
              pieces[1].count == 2,
              pieces[2].count == 2,
              let year = Int(pieces[0]),
              let month = Int(pieces[1]),
              let day = Int(pieces[2]) else {
            return nil
        }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = suppliedCalendar.timeZone
        guard let date = calendar.date(from: DateComponents(
            year: year,
            month: month,
            day: day,
            hour: 12
        )) else {
            return nil
        }
        let roundTrip = calendar.dateComponents([.year, .month, .day], from: date)
        guard roundTrip.year == year, roundTrip.month == month, roundTrip.day == day else {
            return nil
        }
        return date
    }
}

nonisolated enum SearchConsoleSearchType: String, Codable, CaseIterable, Sendable {
    case web
    case image
    case video
    case news
    case discover
    case googleNews
}

nonisolated enum SearchConsoleDimension: String, Codable, CaseIterable, Hashable, Sendable {
    case date
    case hour
    case query
    case page
    case country
    case device
    case searchAppearance
}

nonisolated enum SearchConsoleFilterDimension: String, Codable, CaseIterable, Sendable {
    case query
    case page
    case country
    case device
    case searchAppearance
}

nonisolated enum SearchConsoleFilterOperator: String, Codable, CaseIterable, Sendable {
    case contains
    case equals
    case notContains
    case notEquals
    case includingRegex
    case excludingRegex
}

nonisolated enum SearchConsoleFilterGroupType: String, Codable, CaseIterable, Sendable {
    // Search Console currently documents only AND groups.
    case and
}

nonisolated struct SearchConsoleDimensionFilter: Codable, Equatable, Sendable {
    let dimension: SearchConsoleFilterDimension
    let `operator`: SearchConsoleFilterOperator
    let expression: String

    init(
        dimension: SearchConsoleFilterDimension,
        operator: SearchConsoleFilterOperator = .equals,
        expression: String
    ) {
        self.dimension = dimension
        self.operator = `operator`
        self.expression = expression
    }
}

nonisolated struct SearchConsoleDimensionFilterGroup: Codable, Equatable, Sendable {
    let groupType: SearchConsoleFilterGroupType
    let filters: [SearchConsoleDimensionFilter]

    init(
        groupType: SearchConsoleFilterGroupType = .and,
        filters: [SearchConsoleDimensionFilter]
    ) {
        self.groupType = groupType
        self.filters = filters
    }
}

nonisolated enum SearchConsoleAggregationType: String, Codable, CaseIterable, Sendable {
    case auto
    case byPage
    case byProperty
    case byNewsShowcasePanel
}

nonisolated enum SearchConsoleDataState: String, Codable, CaseIterable, Sendable {
    case final
    case all
    case hourlyAll = "hourly_all"
}

nonisolated struct SearchConsoleAnalyticsQuery: Codable, Equatable, Sendable {
    let dateRange: SearchConsoleDateRange
    let dimensions: [SearchConsoleDimension]
    let searchType: SearchConsoleSearchType
    let dimensionFilterGroups: [SearchConsoleDimensionFilterGroup]
    let aggregationType: SearchConsoleAggregationType
    let rowLimit: Int
    let startRow: Int
    let dataState: SearchConsoleDataState

    init(
        dateRange: SearchConsoleDateRange,
        dimensions: [SearchConsoleDimension] = [],
        searchType: SearchConsoleSearchType = .web,
        dimensionFilterGroups: [SearchConsoleDimensionFilterGroup] = [],
        aggregationType: SearchConsoleAggregationType = .auto,
        rowLimit: Int = 1_000,
        startRow: Int = 0,
        dataState: SearchConsoleDataState = .final
    ) {
        self.dateRange = dateRange
        self.dimensions = dimensions
        self.searchType = searchType
        self.dimensionFilterGroups = dimensionFilterGroups
        self.aggregationType = aggregationType
        self.rowLimit = rowLimit
        self.startRow = startRow
        self.dataState = dataState
    }

    private enum CodingKeys: String, CodingKey {
        case startDate
        case endDate
        case dimensions
        case type
        case dimensionFilterGroups
        case aggregationType
        case rowLimit
        case startRow
        case dataState
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(dateRange.startDate, forKey: .startDate)
        try container.encode(dateRange.endDate, forKey: .endDate)
        if !dimensions.isEmpty {
            try container.encode(dimensions, forKey: .dimensions)
        }
        try container.encode(searchType, forKey: .type)
        if !dimensionFilterGroups.isEmpty {
            try container.encode(dimensionFilterGroups, forKey: .dimensionFilterGroups)
        }
        try container.encode(aggregationType, forKey: .aggregationType)
        try container.encode(rowLimit, forKey: .rowLimit)
        try container.encode(startRow, forKey: .startRow)
        try container.encode(dataState, forKey: .dataState)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        dateRange = SearchConsoleDateRange(
            startDate: try container.decode(String.self, forKey: .startDate),
            endDate: try container.decode(String.self, forKey: .endDate)
        )
        dimensions = try container.decodeIfPresent([SearchConsoleDimension].self, forKey: .dimensions) ?? []
        searchType = try container.decodeIfPresent(SearchConsoleSearchType.self, forKey: .type) ?? .web
        dimensionFilterGroups = try container.decodeIfPresent(
            [SearchConsoleDimensionFilterGroup].self,
            forKey: .dimensionFilterGroups
        ) ?? []
        aggregationType = try container.decodeIfPresent(
            SearchConsoleAggregationType.self,
            forKey: .aggregationType
        ) ?? .auto
        rowLimit = try container.decodeIfPresent(Int.self, forKey: .rowLimit) ?? 1_000
        startRow = try container.decodeIfPresent(Int.self, forKey: .startRow) ?? 0
        dataState = try container.decodeIfPresent(SearchConsoleDataState.self, forKey: .dataState) ?? .final
    }

    func page(startingAt startRow: Int) -> Self {
        Self(
            dateRange: dateRange,
            dimensions: dimensions,
            searchType: searchType,
            dimensionFilterGroups: dimensionFilterGroups,
            aggregationType: aggregationType,
            rowLimit: rowLimit,
            startRow: startRow,
            dataState: dataState
        )
    }
}

nonisolated struct SearchConsoleAnalyticsRow: Codable, Equatable, Sendable, Identifiable {
    let keys: [String]
    let clicks: Double
    let impressions: Double
    let ctr: Double
    let position: Double

    var id: String { keys.joined(separator: "\u{1F}") }

    init(
        keys: [String] = [],
        clicks: Double,
        impressions: Double,
        ctr: Double,
        position: Double
    ) {
        self.keys = keys
        self.clicks = clicks
        self.impressions = impressions
        self.ctr = ctr
        self.position = position
    }

    private enum CodingKeys: String, CodingKey {
        case keys
        case clicks
        case impressions
        case ctr
        case position
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        keys = try container.decodeIfPresent([String].self, forKey: .keys) ?? []
        clicks = try container.decode(Double.self, forKey: .clicks)
        impressions = try container.decode(Double.self, forKey: .impressions)
        ctr = try container.decode(Double.self, forKey: .ctr)
        position = try container.decode(Double.self, forKey: .position)
    }
}

nonisolated struct SearchConsoleAnalyticsMetadata: Codable, Equatable, Sendable {
    let firstIncompleteDate: String?
    let firstIncompleteHour: String?

    private enum CodingKeys: String, CodingKey {
        case firstIncompleteDate = "first_incomplete_date"
        case firstIncompleteHour = "first_incomplete_hour"
    }
}

nonisolated struct SearchConsoleAnalyticsResponse: Codable, Equatable, Sendable {
    let rows: [SearchConsoleAnalyticsRow]
    let responseAggregationType: String?
    let metadata: SearchConsoleAnalyticsMetadata?

    init(
        rows: [SearchConsoleAnalyticsRow] = [],
        responseAggregationType: String? = nil,
        metadata: SearchConsoleAnalyticsMetadata? = nil
    ) {
        self.rows = rows
        self.responseAggregationType = responseAggregationType
        self.metadata = metadata
    }

    private enum CodingKeys: String, CodingKey {
        case rows
        case responseAggregationType
        case metadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rows = try container.decodeIfPresent([SearchConsoleAnalyticsRow].self, forKey: .rows) ?? []
        responseAggregationType = try container.decodeIfPresent(String.self, forKey: .responseAggregationType)
        metadata = try container.decodeIfPresent(SearchConsoleAnalyticsMetadata.self, forKey: .metadata)
    }
}

// MARK: - Sites

nonisolated struct SearchConsoleSite: Codable, Equatable, Sendable, Identifiable {
    let siteUrl: String
    let permissionLevel: String

    var id: String { siteUrl }
}

nonisolated struct SearchConsoleSiteListResponse: Codable, Equatable, Sendable {
    let siteEntry: [SearchConsoleSite]

    init(siteEntry: [SearchConsoleSite] = []) {
        self.siteEntry = siteEntry
    }

    private enum CodingKeys: String, CodingKey { case siteEntry }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        siteEntry = try container.decodeIfPresent([SearchConsoleSite].self, forKey: .siteEntry) ?? []
    }
}

// MARK: - Sitemaps

nonisolated struct SearchConsoleSitemapContent: Codable, Equatable, Sendable, Identifiable {
    let type: String
    let submitted: Int64
    let indexed: Int64?

    var id: String { type }

    private enum CodingKeys: String, CodingKey {
        case type
        case submitted
        case indexed
    }

    init(type: String, submitted: Int64, indexed: Int64? = nil) {
        self.type = type
        self.submitted = submitted
        self.indexed = indexed
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decode(String.self, forKey: .type)
        submitted = try container.decodeFlexibleInt64(forKey: .submitted) ?? 0
        indexed = try container.decodeFlexibleInt64(forKey: .indexed)
    }
}

nonisolated struct SearchConsoleSitemap: Codable, Equatable, Sendable, Identifiable {
    let path: String
    let lastSubmitted: String?
    let isPending: Bool
    let isSitemapsIndex: Bool
    let type: String?
    let lastDownloaded: String?
    let warnings: Int64
    let errors: Int64
    let contents: [SearchConsoleSitemapContent]

    var id: String { path }

    private enum CodingKeys: String, CodingKey {
        case path
        case lastSubmitted
        case isPending
        case isSitemapsIndex
        case type
        case lastDownloaded
        case warnings
        case errors
        case contents
    }

    init(
        path: String,
        lastSubmitted: String? = nil,
        isPending: Bool = false,
        isSitemapsIndex: Bool = false,
        type: String? = nil,
        lastDownloaded: String? = nil,
        warnings: Int64 = 0,
        errors: Int64 = 0,
        contents: [SearchConsoleSitemapContent] = []
    ) {
        self.path = path
        self.lastSubmitted = lastSubmitted
        self.isPending = isPending
        self.isSitemapsIndex = isSitemapsIndex
        self.type = type
        self.lastDownloaded = lastDownloaded
        self.warnings = warnings
        self.errors = errors
        self.contents = contents
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        path = try container.decode(String.self, forKey: .path)
        lastSubmitted = try container.decodeIfPresent(String.self, forKey: .lastSubmitted)
        isPending = try container.decodeIfPresent(Bool.self, forKey: .isPending) ?? false
        isSitemapsIndex = try container.decodeIfPresent(Bool.self, forKey: .isSitemapsIndex) ?? false
        type = try container.decodeIfPresent(String.self, forKey: .type)
        lastDownloaded = try container.decodeIfPresent(String.self, forKey: .lastDownloaded)
        warnings = try container.decodeFlexibleInt64(forKey: .warnings) ?? 0
        errors = try container.decodeFlexibleInt64(forKey: .errors) ?? 0
        contents = try container.decodeIfPresent([SearchConsoleSitemapContent].self, forKey: .contents) ?? []
    }
}

nonisolated struct SearchConsoleSitemapListResponse: Codable, Equatable, Sendable {
    let sitemap: [SearchConsoleSitemap]

    init(sitemap: [SearchConsoleSitemap] = []) {
        self.sitemap = sitemap
    }

    private enum CodingKeys: String, CodingKey { case sitemap }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sitemap = try container.decodeIfPresent([SearchConsoleSitemap].self, forKey: .sitemap) ?? []
    }
}

// MARK: - URL inspection

nonisolated struct SearchConsoleURLInspectionResponse: Codable, Equatable, Sendable {
    let inspectionResult: SearchConsoleURLInspectionResult
}

nonisolated struct SearchConsoleURLInspectionResult: Codable, Equatable, Sendable {
    let inspectionResultLink: String?
    let indexStatusResult: SearchConsoleIndexStatusInspectionResult?
    let ampResult: SearchConsoleAMPInspectionResult?
    let mobileUsabilityResult: SearchConsoleMobileUsabilityInspectionResult?
    let richResultsResult: SearchConsoleRichResultsInspectionResult?
}

nonisolated struct SearchConsoleIndexStatusInspectionResult: Codable, Equatable, Sendable {
    let sitemap: [String]
    let referringUrls: [String]
    let verdict: String?
    let coverageState: String?
    let robotsTxtState: String?
    let indexingState: String?
    let lastCrawlTime: String?
    let pageFetchState: String?
    let googleCanonical: String?
    let userCanonical: String?
    let crawledAs: String?

    private enum CodingKeys: String, CodingKey {
        case sitemap
        case referringUrls
        case verdict
        case coverageState
        case robotsTxtState
        case indexingState
        case lastCrawlTime
        case pageFetchState
        case googleCanonical
        case userCanonical
        case crawledAs
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sitemap = try container.decodeIfPresent([String].self, forKey: .sitemap) ?? []
        referringUrls = try container.decodeIfPresent([String].self, forKey: .referringUrls) ?? []
        verdict = try container.decodeIfPresent(String.self, forKey: .verdict)
        coverageState = try container.decodeIfPresent(String.self, forKey: .coverageState)
        robotsTxtState = try container.decodeIfPresent(String.self, forKey: .robotsTxtState)
        indexingState = try container.decodeIfPresent(String.self, forKey: .indexingState)
        lastCrawlTime = try container.decodeIfPresent(String.self, forKey: .lastCrawlTime)
        pageFetchState = try container.decodeIfPresent(String.self, forKey: .pageFetchState)
        googleCanonical = try container.decodeIfPresent(String.self, forKey: .googleCanonical)
        userCanonical = try container.decodeIfPresent(String.self, forKey: .userCanonical)
        crawledAs = try container.decodeIfPresent(String.self, forKey: .crawledAs)
    }
}

nonisolated struct SearchConsoleAMPIssue: Codable, Equatable, Sendable, Identifiable {
    let issueMessage: String?
    let severity: String?

    var id: String { "\(severity ?? "")|\(issueMessage ?? "")" }
}

nonisolated struct SearchConsoleAMPInspectionResult: Codable, Equatable, Sendable {
    let issues: [SearchConsoleAMPIssue]
    let verdict: String?
    let ampUrl: String?
    let robotsTxtState: String?
    let indexingState: String?
    let ampIndexStatusVerdict: String?
    let lastCrawlTime: String?
    let pageFetchState: String?

    private enum CodingKeys: String, CodingKey {
        case issues
        case verdict
        case ampUrl
        case robotsTxtState
        case indexingState
        case ampIndexStatusVerdict
        case lastCrawlTime
        case pageFetchState
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        issues = try container.decodeIfPresent([SearchConsoleAMPIssue].self, forKey: .issues) ?? []
        verdict = try container.decodeIfPresent(String.self, forKey: .verdict)
        ampUrl = try container.decodeIfPresent(String.self, forKey: .ampUrl)
        robotsTxtState = try container.decodeIfPresent(String.self, forKey: .robotsTxtState)
        indexingState = try container.decodeIfPresent(String.self, forKey: .indexingState)
        ampIndexStatusVerdict = try container.decodeIfPresent(String.self, forKey: .ampIndexStatusVerdict)
        lastCrawlTime = try container.decodeIfPresent(String.self, forKey: .lastCrawlTime)
        pageFetchState = try container.decodeIfPresent(String.self, forKey: .pageFetchState)
    }
}

nonisolated struct SearchConsoleMobileUsabilityIssue: Codable, Equatable, Sendable, Identifiable {
    let issueType: String?
    let severity: String?
    let message: String?

    var id: String { "\(issueType ?? "")|\(severity ?? "")|\(message ?? "")" }
}

nonisolated struct SearchConsoleMobileUsabilityInspectionResult: Codable, Equatable, Sendable {
    let issues: [SearchConsoleMobileUsabilityIssue]
    let verdict: String?

    private enum CodingKeys: String, CodingKey {
        case issues
        case verdict
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        issues = try container.decodeIfPresent([SearchConsoleMobileUsabilityIssue].self, forKey: .issues) ?? []
        verdict = try container.decodeIfPresent(String.self, forKey: .verdict)
    }
}

nonisolated struct SearchConsoleRichResultsIssue: Codable, Equatable, Sendable, Identifiable {
    let issueMessage: String?
    let severity: String?

    var id: String { "\(severity ?? "")|\(issueMessage ?? "")" }
}

nonisolated struct SearchConsoleRichResultItem: Codable, Equatable, Sendable, Identifiable {
    let issues: [SearchConsoleRichResultsIssue]
    let name: String?

    var id: String {
        "\(name ?? "")|\(issues.map(\.id).joined(separator: ","))"
    }

    private enum CodingKeys: String, CodingKey {
        case issues
        case name
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        issues = try container.decodeIfPresent([SearchConsoleRichResultsIssue].self, forKey: .issues) ?? []
        name = try container.decodeIfPresent(String.self, forKey: .name)
    }
}

nonisolated struct SearchConsoleDetectedRichResult: Codable, Equatable, Sendable, Identifiable {
    let items: [SearchConsoleRichResultItem]
    let richResultType: String

    var id: String { richResultType }

    private enum CodingKeys: String, CodingKey {
        case items
        case richResultType
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([SearchConsoleRichResultItem].self, forKey: .items) ?? []
        richResultType = try container.decode(String.self, forKey: .richResultType)
    }
}

nonisolated struct SearchConsoleRichResultsInspectionResult: Codable, Equatable, Sendable {
    let detectedItems: [SearchConsoleDetectedRichResult]
    let verdict: String?

    private enum CodingKeys: String, CodingKey {
        case detectedItems
        case verdict
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        detectedItems = try container.decodeIfPresent(
            [SearchConsoleDetectedRichResult].self,
            forKey: .detectedItems
        ) ?? []
        verdict = try container.decodeIfPresent(String.self, forKey: .verdict)
    }
}

private nonisolated extension KeyedDecodingContainer {
    func decodeFlexibleInt64(forKey key: Key) throws -> Int64? {
        guard contains(key) else { return nil }
        if try decodeNil(forKey: key) { return nil }
        if let value = try? decode(Int64.self, forKey: key) { return value }
        if let value = try? decode(String.self, forKey: key), let number = Int64(value) { return number }
        if let value = try? decode(Double.self, forKey: key),
           value.isFinite,
           value >= Double(Int64.min),
           value <= Double(Int64.max) {
            return Int64(value)
        }
        throw DecodingError.typeMismatch(
            Int64.self,
            DecodingError.Context(
                codingPath: codingPath + [key],
                debugDescription: "Expected an integer or integer string."
            )
        )
    }
}
