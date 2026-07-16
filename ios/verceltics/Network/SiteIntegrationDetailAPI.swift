import Foundation

nonisolated enum SiteIntegrationDetailUmamiAuthentication: Equatable, Sendable {
    case cloudAPIKey(String)
    case bearerToken(String)
}

nonisolated enum SiteIntegrationDetailRequest: Equatable, Sendable {
    case googleAnalytics(
        propertyID: String,
        accessToken: String,
        range: SiteIntegrationDetailRange
    )
    case pageSpeed(url: URL, apiKey: String)
    case bingWebmaster(siteURL: String, apiKey: String)
    case clarity(apiToken: String, days: Int, dimensions: [String])
    case plausible(siteID: String, apiKey: String, range: SiteIntegrationDetailRange)
    case umami(
        websiteID: String,
        baseURL: URL,
        authentication: SiteIntegrationDetailUmamiAuthentication,
        range: SiteIntegrationDetailRange
    )
    case uptimeRobot(
        monitorID: String,
        readOnlyAPIKey: String,
        range: SiteIntegrationDetailRange
    )
    case betterStack(monitorID: String, token: String, range: SiteIntegrationDetailRange)
}

nonisolated enum SiteIntegrationDetailAPIError: LocalizedError, Equatable {
    case invalidConfiguration(String)
    case invalidResponse
    case requestFailed(Int)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let message): message
        case .invalidResponse: "The provider returned an invalid response."
        case .requestFailed(let status): "The provider request failed (HTTP \(status))."
        case .decoding(let message): message
        }
    }
}

/// Fetches the high-detail, read-only surfaces behind the Sites provider cards. Every selected
/// endpoint is represented twice: normalized sections/series/tables for UI and a recursively
/// sanitized response tree that keeps non-secret fields available for future UI additions.
nonisolated struct SiteIntegrationDetailClient: Sendable {
    private static let maximumResponseBytes = 32 * 1_024 * 1_024
    /// A provider workspace is cached and rendered as one value. Keep the aggregate value small
    /// enough for older iPhones instead of applying independent limits to every endpoint.
    private static let maximumPayloadRows = 20_000
    private static let maximumPayloadBytes = 8 * 1_024 * 1_024
    private static let maximumRawResponseBytes = 1 * 1_024 * 1_024
    private static let maximumRetainedRawPagesPerEndpoint = 2
    /// Keeps a single high-cardinality report bounded on-device while still paging well beyond
    /// the Data API's default 10,000 rows. A warning is emitted only when this cap truncates data.
    private static let maximumGAReportRows = 100_000
    private static let gaReportPageSize = 10_000
    private static let plausiblePageSize = 10_000
    private static let maximumPlausibleRows = 100_000
    private static let umamiMetricPageSize = 500
    private static let maximumUmamiMetricRows = 10_000
    /// Linked and token-based APIs do not expose a response-size budget across pages. Bound both
    /// the number of responses retained and their normalized rows so a broken next cursor cannot
    /// exhaust an iPhone's memory.
    private static let maximumPaginationPages = 20
    private static let maximumPagedCollectionRows = 100_000

    private let session: URLSession?

    init(session: URLSession? = nil) {
        self.session = session
    }

    func fetch(
        _ request: SiteIntegrationDetailRequest,
        onPartial: (@Sendable (SiteIntegrationDetailPayload) async -> Void)? = nil
    ) async throws -> SiteIntegrationDetailPayload {
        let payload = switch request {
        case .googleAnalytics(let propertyID, let token, let range):
            try await fetchGoogleAnalytics(
                propertyID: propertyID,
                token: token,
                range: range,
                onPartial: onPartial
            )
        case .pageSpeed(let url, let apiKey):
            try await fetchPageSpeed(siteURL: url, apiKey: apiKey)
        case .bingWebmaster(let siteURL, let apiKey):
            try await fetchBing(siteURL: siteURL, apiKey: apiKey)
        case .clarity(let token, let days, let dimensions):
            try await fetchClarity(token: token, days: days, dimensions: dimensions)
        case .plausible(let siteID, let apiKey, let range):
            try await fetchPlausible(siteID: siteID, apiKey: apiKey, range: range)
        case .umami(let websiteID, let baseURL, let authentication, let range):
            try await fetchUmami(
                websiteID: websiteID,
                baseURL: baseURL,
                authentication: authentication,
                range: range
            )
        case .uptimeRobot(let monitorID, let apiKey, let range):
            try await fetchUptimeRobot(monitorID: monitorID, apiKey: apiKey, range: range)
        case .betterStack(let monitorID, let token, let range):
            try await fetchBetterStack(monitorID: monitorID, token: token, range: range)
        }
        return Self.boundedForDevice(payload)
    }

    // MARK: Google Analytics 4

    private struct GAQuery: Sendable {
        let id: String
        let title: String
        let dimensions: [String]
        let metrics: [String]
        let isTimeline: Bool
    }

    private struct GAReportWork: Sendable {
        let query: GAQuery
        let maximumRows: Int
    }

    private struct GAReportResult: Sendable {
        let query: GAQuery
        let report: GAPagedReport
    }

    private struct GAReportRefillWork: Sendable {
        let index: Int
        let work: GAReportWork
    }

    private struct GAReportRefillResult: Sendable {
        let index: Int
        let result: GAReportResult
    }

    private struct GAPagedReport: Sendable {
        let merged: SiteIntegrationJSONValue
        let pages: [SiteIntegrationJSONValue]
        let totalRows: Int
        let truncated: Bool
    }

    private struct PagedCollection: Sendable {
        let pages: [SiteIntegrationJSONValue]
        let items: [SiteIntegrationJSONValue]
        let truncated: Bool
    }

    private struct GAAuxiliarySurfaces: Sendable {
        var title: String?
        var raw: [String: SiteIntegrationJSONValue] = [:]
        var sections: [SiteIntegrationDetailSection] = []
        var series: [SiteIntegrationDetailSeries] = []
        var tables: [SiteIntegrationDetailTable] = []
        var warnings: [String] = []

        mutating func merge(_ other: Self) {
            if let title = other.title { self.title = title }
            raw.merge(other.raw) { _, new in new }
            sections.append(contentsOf: other.sections)
            series.append(contentsOf: other.series)
            tables.append(contentsOf: other.tables)
            warnings.append(contentsOf: other.warnings)
        }
    }

    private struct GASetting: Sendable {
        let key: String
        let title: String
        let version: String
        let suffix: String
    }

    private enum GAAuxiliaryRequest: Sendable {
        case realtimeOverview
        case realtimeTimeline
        case property
        case dataStreams
        case dataAPIMetadata
        case setting(GASetting)
    }

    private func fetchGoogleAnalytics(
        propertyID: String,
        token: String,
        range: SiteIntegrationDetailRange,
        onPartial: (@Sendable (SiteIntegrationDetailPayload) async -> Void)?
    ) async throws -> SiteIntegrationDetailPayload {
        guard !propertyID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("A GA4 property ID is required.")
        }
        let encodedProperty = try pathComponent(propertyID.replacingOccurrences(of: "properties/", with: ""))
        guard let url = URL(string: "https://analyticsdata.googleapis.com/v1beta/properties/\(encodedProperty):runReport") else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("The GA4 property ID is invalid.")
        }
        let queries = [
            GAQuery(
                id: "overview", title: "Overview", dimensions: [],
                metrics: ["activeUsers", "sessions", "screenPageViews", "engagementRate", "eventCount", "averageSessionDuration"],
                isTimeline: false
            ),
            GAQuery(
                id: "timeline", title: "Traffic over time", dimensions: ["date"],
                metrics: ["activeUsers", "sessions", "screenPageViews", "engagementRate", "eventCount", "averageSessionDuration"],
                isTimeline: true
            ),
            GAQuery(
                id: "acquisition", title: "Acquisition", dimensions: ["sessionDefaultChannelGroup", "sessionSource", "sessionMedium"],
                metrics: ["sessions", "activeUsers", "engagementRate"], isTimeline: false
            ),
            GAQuery(
                id: "pages", title: "Pages", dimensions: ["pagePath", "pageTitle"],
                metrics: ["screenPageViews", "activeUsers", "averageSessionDuration"], isTimeline: false
            ),
            GAQuery(
                id: "events", title: "Events", dimensions: ["eventName"],
                metrics: ["eventCount", "activeUsers"], isTimeline: false
            ),
            GAQuery(
                id: "geography", title: "Geography", dimensions: ["country", "city"],
                metrics: ["activeUsers", "sessions"], isTimeline: false
            ),
            GAQuery(
                id: "technology", title: "Technology", dimensions: ["deviceCategory", "browser", "operatingSystem"],
                metrics: ["activeUsers", "sessions"], isTimeline: false
            )
        ]

        let coreQueries = Array(queries.prefix(2))
        let breakdownQueries = Array(queries.dropFirst(2))
        let timelineRows = min(
            Self.maximumPayloadRows / 4,
            max(
                1,
                (Calendar.autoupdatingCurrent.dateComponents(
                    [.day],
                    from: range.start,
                    to: range.end
                ).day ?? 0) + 1
            )
        )
        let coreWork = [
            GAReportWork(query: coreQueries[0], maximumRows: 1),
            GAReportWork(query: coreQueries[1], maximumRows: timelineRows)
        ]
        let coreResults = try await boundedConcurrentMap(
            coreWork,
            maximumConcurrent: coreWork.count
        ) { work in
            try await fetchGAReport(
                work,
                url: url,
                token: token,
                range: range
            )
        }

        let fetchedAt = Date.now
        var surfaces = googleAnalyticsSurfaces(from: coreResults, range: range)
        let partialPayload = SiteIntegrationDetailPayload(
            provider: .googleAnalytics,
            resourceID: propertyID,
            title: "Google Analytics · \(propertyID)",
            sections: surfaces.sections,
            series: surfaces.series,
            tables: surfaces.tables,
            rawResponses: surfaces.raw,
            warnings: surfaces.warnings,
            fetchedAt: fetchedAt
        )
        if let onPartial {
            await onPartial(Self.boundedForDevice(partialPayload))
        }

        let usedCoreRows = coreResults.reduce(0) {
            $0 + ($1.report.merged["rows"]?.arrayValue?.count ?? 0)
        }
        let remainingRows = max(
            breakdownQueries.count,
            Self.maximumPayloadRows - usedCoreRows
        )
        let breakdownLimits = distributedLimits(
            total: remainingRows,
            count: breakdownQueries.count
        )
        let breakdownWork = zip(breakdownQueries, breakdownLimits).map {
            GAReportWork(query: $0.0, maximumRows: $0.1)
        }

        async let breakdownResults = boundedConcurrentMap(
            breakdownWork,
            maximumConcurrent: 3
        ) { work in
            try await fetchGAReport(
                work,
                url: url,
                token: token,
                range: range
            )
        }
        async let auxiliary = fetchGoogleAnalyticsRealtimeAndConfiguration(
            propertyID: encodedProperty,
            token: token
        )
        let (initialBreakdowns, loadedAuxiliary) = try await (breakdownResults, auxiliary)
        let loadedBreakdowns = try await refillGAReportBudget(
            initialBreakdowns,
            totalBudget: remainingRows,
            url: url,
            token: token,
            range: range
        )
        surfaces.merge(googleAnalyticsSurfaces(from: loadedBreakdowns, range: range))
        surfaces.merge(loadedAuxiliary)

        return SiteIntegrationDetailPayload(
            provider: .googleAnalytics,
            resourceID: propertyID,
            title: surfaces.title ?? "Google Analytics · \(propertyID)",
            sections: surfaces.sections,
            series: surfaces.series,
            tables: surfaces.tables,
            rawResponses: surfaces.raw,
            warnings: surfaces.warnings,
            fetchedAt: fetchedAt
        )
    }

    private func fetchGAReport(
        _ work: GAReportWork,
        url: URL,
        token: String,
        range: SiteIntegrationDetailRange
    ) async throws -> GAReportResult {
        var body: [String: Any] = [
            "dateRanges": [["startDate": range.startDate, "endDate": range.endDate]],
            "dimensions": work.query.dimensions.map { ["name": $0] },
            "metrics": work.query.metrics.map { ["name": $0] }
        ]
        if !work.query.dimensions.isEmpty {
            // Stable dimension ordering is required when offset-paging: without it, rows can
            // move between pages while Google evaluates the report.
            body["orderBys"] = work.query.dimensions.map {
                ["dimension": ["dimensionName": $0], "desc": false] as [String: Any]
            }
        }
        let report = try await fetchGAReportPages(
            url: url,
            body: body,
            token: token,
            maximumRows: min(Self.maximumGAReportRows, max(1, work.maximumRows))
        )
        return GAReportResult(query: work.query, report: report)
    }

    private func googleAnalyticsSurfaces(
        from results: [GAReportResult],
        range: SiteIntegrationDetailRange
    ) -> GAAuxiliarySurfaces {
        var output = GAAuxiliarySurfaces()
        for result in results {
            let query = result.query
            let report = result.report
            output.raw[query.id] = .array(report.pages)
            if report.truncated {
                let retainedCount = report.merged["rows"]?.arrayValue?.count ?? 0
                output.warnings.append(
                    "Google Analytics \(query.title.lowercased()) has \(report.totalRows) rows; "
                    + "Verceltics kept the first \(retainedCount) rows "
                    + "within the shared device budget."
                )
            }
            let normalized = normalizeGAReport(report.merged, id: query.id, title: query.title)
            if query.id == "overview", let first = normalized.rows.first {
                output.sections.append(SiteIntegrationDetailSection(
                    id: "ga4.overview",
                    title: "Overview · \(range.startDate) – \(range.endDate)",
                    fields: first.keys.sorted().map {
                        SiteIntegrationDetailField(
                            key: $0,
                            label: humanized($0),
                            value: first[$0] ?? .null
                        )
                    }
                ))
            } else if query.isTimeline {
                output.series.append(SiteIntegrationDetailSeries(
                    id: "ga4.timeline",
                    title: query.title,
                    metricLabels: Dictionary(
                        uniqueKeysWithValues: normalized.metricNames.map { ($0, humanized($0)) }
                    ),
                    points: normalized.rows.compactMap { row in
                        guard let rawDate = row["date"]?.stringValue else { return nil }
                        return SiteIntegrationDetailSeriesPoint(
                            x: normalizedGADate(rawDate),
                            values: Dictionary(
                                uniqueKeysWithValues: normalized.metricNames.compactMap { metric in
                                    row[metric]?.numberValue.map { (metric, $0) }
                                }
                            )
                        )
                    }
                ))
            } else {
                output.tables.append(SiteIntegrationDetailTable(
                    id: "ga4.\(query.id)",
                    title: query.title,
                    columns: normalized.columns,
                    rows: normalized.rows,
                    nextCursor: normalized.nextCursor
                ))
            }
        }
        return output
    }

    /// The first concurrent pass gives every breakdown a fair share of the aggregate row budget.
    /// Sparse reports often leave most of that share unused, so refill only the truncated reports
    /// until the shared budget is actually consumed. Refills remain bounded and deterministic.
    private func refillGAReportBudget(
        _ initialResults: [GAReportResult],
        totalBudget: Int,
        url: URL,
        token: String,
        range: SiteIntegrationDetailRange
    ) async throws -> [GAReportResult] {
        var results = initialResults
        var remaining = max(0, totalBudget - retainedRowCount(in: results))
        var refillRound = 0

        while remaining > 0, refillRound < results.count {
            let candidateIndices = results.indices.filter { results[$0].report.truncated }
            guard !candidateIndices.isEmpty else { break }

            let increments = distributedLimits(total: remaining, count: candidateIndices.count)
            let work: [GAReportRefillWork] = zip(candidateIndices, increments).compactMap {
                pair -> GAReportRefillWork? in
                let (index, increment) = pair
                guard increment > 0 else { return nil }
                let currentRows = results[index].report.merged["rows"]?.arrayValue?.count ?? 0
                let expandedLimit = min(
                    Self.maximumGAReportRows,
                    results[index].report.totalRows,
                    currentRows + increment
                )
                guard expandedLimit > currentRows else { return nil }
                return GAReportRefillWork(
                    index: index,
                    work: GAReportWork(
                        query: results[index].query,
                        maximumRows: expandedLimit
                    )
                )
            }
            guard !work.isEmpty else { break }

            let refilled = try await boundedConcurrentMap(work, maximumConcurrent: 3) { item in
                GAReportRefillResult(
                    index: item.index,
                    result: try await fetchGAReport(
                        item.work,
                        url: url,
                        token: token,
                        range: range
                    )
                )
            }
            var addedRows = 0
            for refill in refilled {
                let oldCount = results[refill.index].report.merged["rows"]?.arrayValue?.count ?? 0
                let newCount = refill.result.report.merged["rows"]?.arrayValue?.count ?? 0
                guard newCount > oldCount else { continue }
                results[refill.index] = refill.result
                addedRows += newCount - oldCount
            }
            guard addedRows > 0 else { break }
            remaining = max(0, remaining - addedRows)
            refillRound += 1
        }
        return results
    }

    private func retainedRowCount(in results: [GAReportResult]) -> Int {
        results.reduce(0) {
            $0 + ($1.report.merged["rows"]?.arrayValue?.count ?? 0)
        }
    }

    private func distributedLimits(total: Int, count: Int) -> [Int] {
        guard count > 0 else { return [] }
        let boundedTotal = max(0, total)
        let base = boundedTotal / count
        let remainder = boundedTotal % count
        return (0..<count).map { base + ($0 < remainder ? 1 : 0) }
    }

    private func fetchGAReportPages(
        url: URL,
        body: [String: Any],
        token: String,
        maximumRows: Int
    ) async throws -> GAPagedReport {
        var offset = 0
        var totalRows: Int?
        var pages: [SiteIntegrationJSONValue] = []
        var combinedRows: [SiteIntegrationJSONValue] = []
        var firstResponse: SiteIntegrationJSONValue?

        while offset < min(totalRows ?? Int.max, maximumRows) {
            try Task.checkCancellation()
            let remaining = maximumRows - offset
            guard remaining > 0 else { break }
            var pageBody = body
            pageBody["limit"] = String(min(Self.gaReportPageSize, remaining))
            pageBody["offset"] = String(offset)
            let response = try await requestJSON(
                method: "POST",
                url: url,
                jsonBody: pageBody,
                headers: ["Authorization": "Bearer \(token)"]
            )
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            if firstResponse == nil { firstResponse = response }
            let pageRows = response["rows"]?.arrayValue ?? []
            let reportedTotal = max(0, Int(response["rowCount"]?.numberValue ?? Double(pageRows.count)))
            totalRows = totalRows.map { max($0, reportedTotal) } ?? reportedTotal
            combinedRows.append(contentsOf: pageRows.prefix(remaining))

            if combinedRows.count >= min(totalRows ?? 0, maximumRows) { break }
            guard !pageRows.isEmpty else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "Google Analytics stopped returning rows before the reported row count was reached."
                )
            }
            offset += pageRows.count
        }

        guard let firstResponse else {
            throw SiteIntegrationDetailAPIError.invalidResponse
        }
        var mergedObject = firstResponse.objectValue ?? [:]
        mergedObject["rows"] = .array(combinedRows)
        mergedObject["rowCount"] = .number(Double(totalRows ?? combinedRows.count))
        return GAPagedReport(
            merged: .object(mergedObject),
            pages: pages,
            totalRows: totalRows ?? combinedRows.count,
            truncated: (totalRows ?? combinedRows.count) > combinedRows.count
                && combinedRows.count == maximumRows
        )
    }

    private func fetchGoogleAnalyticsRealtimeAndConfiguration(
        propertyID: String,
        token: String
    ) async throws -> GAAuxiliarySurfaces {
        let settings = [
            GASetting(
                key: "dataRetentionSettings",
                title: "Data retention",
                version: "v1beta",
                suffix: "dataRetentionSettings"
            ),
            GASetting(
                key: "googleSignalsSettings",
                title: "Google Signals",
                version: "v1alpha",
                suffix: "googleSignalsSettings"
            ),
            GASetting(
                key: "attributionSettings",
                title: "Attribution",
                version: "v1alpha",
                suffix: "attributionSettings"
            ),
            GASetting(
                key: "reportingIdentitySettings",
                title: "Reporting identity",
                version: "v1alpha",
                suffix: "reportingIdentitySettings"
            ),
            GASetting(
                key: "userProvidedDataSettings",
                title: "User-provided data",
                version: "v1alpha",
                suffix: "userProvidedDataSettings"
            )
        ]
        let requests: [GAAuxiliaryRequest] = [
            .realtimeOverview,
            .realtimeTimeline,
            .property,
            .dataStreams,
            .dataAPIMetadata
        ] + settings.map(GAAuxiliaryRequest.setting)
        let fragments = try await boundedConcurrentMap(
            requests,
            maximumConcurrent: 4
        ) { request in
            try await fetchGoogleAnalyticsAuxiliaryFragment(
                request,
                propertyID: propertyID,
                token: token
            )
        }
        return fragments.reduce(into: GAAuxiliarySurfaces()) { result, fragment in
            result.merge(fragment)
        }
    }

    private func fetchGoogleAnalyticsAuxiliaryFragment(
        _ request: GAAuxiliaryRequest,
        propertyID: String,
        token: String
    ) async throws -> GAAuxiliarySurfaces {
        var output = GAAuxiliarySurfaces()
        let headers = ["Authorization": "Bearer \(token)"]
        do {
            switch request {
            case .realtimeOverview:
                let url = URL(
                    string: "https://analyticsdata.googleapis.com/v1beta/properties/\(propertyID):runRealtimeReport"
                )!
                let response = try await requestJSON(
                    method: "POST",
                    url: url,
                    jsonBody: [
                        "metrics": ["activeUsers", "eventCount", "screenPageViews"].map { ["name": $0] },
                        "returnPropertyQuota": true
                    ],
                    headers: headers
                )
                output.raw["realtime.overview"] = response
                let normalized = normalizeGAReport(
                    response,
                    id: "realtime.overview",
                    title: "Realtime overview"
                )
                if let row = normalized.rows.first {
                    output.sections.append(SiteIntegrationDetailSection(
                        id: "ga4.realtime.overview",
                        title: "Realtime · last 30 minutes",
                        fields: normalized.metricNames.map {
                            SiteIntegrationDetailField(
                                key: $0,
                                label: humanized($0),
                                value: row[$0] ?? .null
                            )
                        }
                    ))
                }

            case .realtimeTimeline:
                let url = URL(
                    string: "https://analyticsdata.googleapis.com/v1beta/properties/\(propertyID):runRealtimeReport"
                )!
                let response = try await requestJSON(
                    method: "POST",
                    url: url,
                    jsonBody: [
                        "dimensions": [["name": "minutesAgo"]],
                        "metrics": ["activeUsers", "eventCount", "screenPageViews"].map { ["name": $0] },
                        "orderBys": [[
                            "dimension": ["dimensionName": "minutesAgo"],
                            "desc": false
                        ]],
                        "limit": "60",
                        "returnPropertyQuota": true
                    ],
                    headers: headers
                )
                output.raw["realtime.report"] = response
                let normalized = normalizeGAReport(
                    response,
                    id: "realtime.report",
                    title: "Realtime activity"
                )
                output.tables.append(SiteIntegrationDetailTable(
                    id: "ga4.realtime.report",
                    title: "Realtime activity by minute",
                    columns: normalized.columns,
                    rows: normalized.rows,
                    nextCursor: normalized.nextCursor
                ))
                output.series.append(SiteIntegrationDetailSeries(
                    id: "ga4.realtime.timeline",
                    title: "Realtime activity",
                    metricLabels: Dictionary(
                        uniqueKeysWithValues: normalized.metricNames.map { ($0, humanized($0)) }
                    ),
                    points: normalized.rows.compactMap { row in
                        guard let minute = row["minutesAgo"]?.stringValue else { return nil }
                        return SiteIntegrationDetailSeriesPoint(
                            x: minute,
                            values: Dictionary(
                                uniqueKeysWithValues: normalized.metricNames.compactMap { metric in
                                    row[metric]?.numberValue.map { (metric, $0) }
                                }
                            )
                        )
                    }
                ))

            case .property:
                let url = URL(
                    string: "https://analyticsadmin.googleapis.com/v1beta/properties/\(propertyID)"
                )!
                let response = try await requestJSON(url: url, headers: headers)
                output.raw["property"] = response
                output.title = response["displayName"]?.stringValue
                    ?? "Google Analytics · \(propertyID)"
                output.sections.append(SiteIntegrationDetailSection(
                    id: "ga4.property",
                    title: "Property metadata",
                    fields: flattenedFields(response, maximumDepth: 3)
                ))

            case .dataStreams:
                let initialURL = URL(
                    string: "https://analyticsadmin.googleapis.com/v1beta/properties/\(propertyID)/dataStreams?pageSize=200"
                )!
                let result = try await fetchGoogleTokenPages(
                    initialURL: initialURL,
                    itemKey: "dataStreams",
                    token: token
                )
                output.raw["dataStreams"] = .array(result.pages)
                if result.truncated {
                    output.warnings.append(
                        "Google Analytics data streams reached the on-device pagination limit; additional streams may be available."
                    )
                }
                let rows = result.items.map(flattenedObject)
                output.tables.append(SiteIntegrationDetailTable(
                    id: "ga4.dataStreams",
                    title: "Data streams",
                    columns: orderedColumns(rows, preferred: [
                        "name", "type", "displayName", "webStreamData.measurementId",
                        "webStreamData.defaultUri", "androidAppStreamData.packageName",
                        "iosAppStreamData.bundleId"
                    ]),
                    rows: rows
                ))

            case .dataAPIMetadata:
                let url = URL(
                    string: "https://analyticsdata.googleapis.com/v1beta/properties/\(propertyID)/metadata"
                )!
                let response = try await requestJSON(url: url, headers: headers)
                output.raw["dataAPI.metadata"] = response
                for (key, title) in [
                    ("dimensions", "Available dimensions"),
                    ("metrics", "Available metrics")
                ] {
                    let rows = (response[key]?.arrayValue ?? []).map(flattenedObject)
                    output.tables.append(SiteIntegrationDetailTable(
                        id: "ga4.metadata.\(key)",
                        title: title,
                        columns: orderedColumns(rows, preferred: [
                            "apiName", "uiName", "description", "category", "deprecatedApiNames"
                        ]),
                        rows: rows
                    ))
                }

            case .setting(let setting):
                let url = URL(
                    string: "https://analyticsadmin.googleapis.com/\(setting.version)/properties/\(propertyID)/\(setting.suffix)"
                )!
                let response = try await requestJSON(url: url, headers: headers)
                output.raw[setting.key] = response
                output.sections.append(SiteIntegrationDetailSection(
                    id: "ga4.\(setting.key)",
                    title: setting.title,
                    fields: flattenedFields(response, maximumDepth: 3)
                ))
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch {
            output.warnings.append(
                "Google Analytics \(auxiliaryDescription(request)) could not load: \(error.localizedDescription)"
            )
        }
        return output
    }

    private func auxiliaryDescription(_ request: GAAuxiliaryRequest) -> String {
        switch request {
        case .realtimeOverview: "realtime overview"
        case .realtimeTimeline: "realtime report"
        case .property: "property metadata"
        case .dataStreams: "data streams"
        case .dataAPIMetadata: "Data API metadata"
        case .setting(let setting): setting.title.lowercased()
        }
    }

    private func boundedConcurrentMap<Input: Sendable, Output: Sendable>(
        _ values: [Input],
        maximumConcurrent: Int,
        operation: @escaping @Sendable (Input) async throws -> Output
    ) async throws -> [Output] {
        guard !values.isEmpty else { return [] }
        let batchSize = max(1, maximumConcurrent)
        var orderedResults: [(index: Int, value: Output)] = []
        orderedResults.reserveCapacity(values.count)

        for batchStart in stride(from: 0, to: values.count, by: batchSize) {
            try Task.checkCancellation()
            let batchEnd = min(values.count, batchStart + batchSize)
            let batch = (batchStart..<batchEnd).map { ($0, values[$0]) }
            let completed = try await withThrowingTaskGroup(
                of: (Int, Output).self,
                returning: [(Int, Output)].self
            ) { group in
                for (index, value) in batch {
                    group.addTask {
                        (index, try await operation(value))
                    }
                }
                var results: [(Int, Output)] = []
                results.reserveCapacity(batch.count)
                for try await result in group {
                    results.append(result)
                }
                return results
            }
            orderedResults.append(contentsOf: completed)
        }
        return orderedResults.sorted { $0.index < $1.index }.map(\.value)
    }

    private func fetchGoogleTokenPages(
        initialURL: URL,
        itemKey: String,
        token: String
    ) async throws -> PagedCollection {
        var nextURL: URL? = initialURL
        var pages: [SiteIntegrationJSONValue] = []
        var pageCount = 0
        var items: [SiteIntegrationJSONValue] = []
        var seenTokens: Set<String> = []
        var truncated = false
        while let url = nextURL {
            try Task.checkCancellation()
            let response = try await requestJSON(
                url: url,
                headers: ["Authorization": "Bearer \(token)"]
            )
            pageCount += 1
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            let pageItems = response[itemKey]?.arrayValue ?? []
            let remaining = max(0, Self.maximumPagedCollectionRows - items.count)
            items.append(contentsOf: pageItems.prefix(remaining))
            if pageItems.count > remaining { truncated = true }

            guard let token = response["nextPageToken"]?.stringValue, !token.isEmpty else {
                nextURL = nil
                continue
            }
            if pageCount >= Self.maximumPaginationPages
                || items.count >= Self.maximumPagedCollectionRows {
                truncated = true
                break
            }
            guard seenTokens.insert(token).inserted else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "Google Analytics returned a repeated data-stream page token."
                )
            }
            guard var components = URLComponents(url: initialURL, resolvingAgainstBaseURL: false) else {
                throw SiteIntegrationDetailAPIError.invalidResponse
            }
            var queryItems = components.queryItems ?? []
            queryItems.removeAll { $0.name == "pageToken" }
            queryItems.append(URLQueryItem(name: "pageToken", value: token))
            components.queryItems = queryItems
            nextURL = components.url
        }
        return PagedCollection(pages: pages, items: items, truncated: truncated)
    }

    private func normalizeGAReport(
        _ response: SiteIntegrationJSONValue,
        id: String,
        title: String
    ) -> (columns: [String], metricNames: [String], rows: [[String: SiteIntegrationJSONValue]], nextCursor: String?) {
        let dimensionNames = response["dimensionHeaders"]?.arrayValue?.compactMap { $0["name"]?.stringValue } ?? []
        let metricNames = response["metricHeaders"]?.arrayValue?.compactMap { $0["name"]?.stringValue } ?? []
        let rows: [[String: SiteIntegrationJSONValue]] = response["rows"]?.arrayValue?.map { rawRow in
            var row: [String: SiteIntegrationJSONValue] = [:]
            let dimensions = rawRow["dimensionValues"]?.arrayValue ?? []
            for index in dimensionNames.indices where dimensions.indices.contains(index) {
                row[dimensionNames[index]] = dimensions[index]["value"] ?? .null
            }
            let metrics = rawRow["metricValues"]?.arrayValue ?? []
            for index in metricNames.indices where metrics.indices.contains(index) {
                let value = metrics[index]["value"] ?? .null
                row[metricNames[index]] = value.numberValue.map(SiteIntegrationJSONValue.number) ?? value
            }
            return row
        } ?? []
        let total = Int(response["rowCount"]?.numberValue ?? Double(rows.count))
        let nextCursor = total > rows.count ? "\(rows.count)/\(total) rows returned" : nil
        return (dimensionNames + metricNames, metricNames, rows, nextCursor)
    }

    private func normalizedGADate(_ value: String) -> String {
        guard value.count == 8 else { return value }
        return "\(value.prefix(4))-\(value.dropFirst(4).prefix(2))-\(value.suffix(2))"
    }

    // MARK: PageSpeed Insights and Chrome UX Report

    private func fetchPageSpeed(siteURL: URL, apiKey: String) async throws -> SiteIntegrationDetailPayload {
        guard siteURL.scheme?.lowercased() == "https", siteURL.host != nil else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("PageSpeed requires a valid HTTPS URL.")
        }
        var raw: [String: SiteIntegrationJSONValue] = [:]
        var sections: [SiteIntegrationDetailSection] = []
        var tables: [SiteIntegrationDetailTable] = []
        var series: [SiteIntegrationDetailSeries] = []
        var warnings: [String] = []

        for strategy in ["mobile", "desktop"] {
            do {
                let response = try await pageSpeedReport(siteURL: siteURL, apiKey: apiKey, strategy: strategy)
                raw["pagespeed.\(strategy)"] = response
                sections.append(contentsOf: pageSpeedSections(response, strategy: strategy))
                tables.append(pageSpeedAuditsTable(response, strategy: strategy))
            } catch {
                if strategy == "mobile" { throw error }
                warnings.append("Desktop Lighthouse report is unavailable: \(error.localizedDescription)")
            }
        }

        do {
            let current = try await cruxReport(siteURL: siteURL, apiKey: apiKey, history: false)
            raw["crux.current"] = current
            sections.append(cruxCurrentSection(current))
        } catch {
            warnings.append("Current Chrome UX field data is unavailable: \(error.localizedDescription)")
        }
        do {
            let history = try await cruxReport(siteURL: siteURL, apiKey: apiKey, history: true)
            raw["crux.history"] = history
            if let timeline = cruxHistorySeries(history) { series.append(timeline) }
        } catch {
            warnings.append("Chrome UX history is unavailable: \(error.localizedDescription)")
        }

        return SiteIntegrationDetailPayload(
            provider: .pageSpeed,
            resourceID: siteURL.absoluteString,
            title: siteURL.host ?? siteURL.absoluteString,
            sections: sections,
            series: series,
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func pageSpeedReport(
        siteURL: URL,
        apiKey: String,
        strategy: String
    ) async throws -> SiteIntegrationJSONValue {
        var components = URLComponents(string: "https://www.googleapis.com/pagespeedonline/v5/runPagespeed")!
        components.queryItems = [
            URLQueryItem(name: "url", value: siteURL.absoluteString),
            URLQueryItem(name: "strategy", value: strategy),
            URLQueryItem(name: "category", value: "performance"),
            URLQueryItem(name: "category", value: "accessibility"),
            URLQueryItem(name: "category", value: "best-practices"),
            URLQueryItem(name: "category", value: "seo")
        ]
        if !apiKey.isEmpty { components.queryItems?.append(URLQueryItem(name: "key", value: apiKey)) }
        guard let url = components.url else { throw SiteIntegrationDetailAPIError.invalidResponse }
        return try await requestJSON(url: url)
    }

    private func pageSpeedSections(
        _ response: SiteIntegrationJSONValue,
        strategy: String
    ) -> [SiteIntegrationDetailSection] {
        let lighthouse = response["lighthouseResult"]
        let categories = lighthouse?["categories"]?.objectValue ?? [:]
        let categoryFields = categories.keys.sorted().flatMap { key -> [SiteIntegrationDetailField] in
            guard let value = categories[key] else { return [] }
            return [
                SiteIntegrationDetailField(
                    key: "\(key).score", label: value["title"]?.stringValue ?? humanized(key),
                    value: value["score"] ?? .null
                )
            ]
        }
        let metadataKeys = ["requestedUrl", "finalUrl", "finalDisplayedUrl", "fetchTime", "lighthouseVersion", "userAgent"]
        let metadata = metadataKeys.compactMap { key -> SiteIntegrationDetailField? in
            guard let value = lighthouse?[key] else { return nil }
            return SiteIntegrationDetailField(key: key, label: humanized(key), value: value)
        }
        return [
            SiteIntegrationDetailSection(
                id: "pagespeed.\(strategy).scores",
                title: "\(strategy.capitalized) scores",
                fields: categoryFields
            ),
            SiteIntegrationDetailSection(
                id: "pagespeed.\(strategy).report",
                title: "\(strategy.capitalized) report",
                fields: metadata
            )
        ]
    }

    private func pageSpeedAuditsTable(
        _ response: SiteIntegrationJSONValue,
        strategy: String
    ) -> SiteIntegrationDetailTable {
        let audits = response["lighthouseResult"]?["audits"]?.objectValue ?? [:]
        let rows = audits.keys.sorted().map { key -> [String: SiteIntegrationJSONValue] in
            var row = audits[key]?.objectValue ?? [:]
            row["id"] = .string(key)
            return row
        }
        return SiteIntegrationDetailTable(
            id: "pagespeed.\(strategy).audits",
            title: "\(strategy.capitalized) Lighthouse audits",
            columns: orderedColumns(rows, preferred: [
                "id", "title", "score", "scoreDisplayMode", "displayValue", "numericValue",
                "numericUnit", "description", "errorMessage", "warnings", "details"
            ]),
            rows: rows
        )
    }

    private func cruxReport(
        siteURL: URL,
        apiKey: String,
        history: Bool
    ) async throws -> SiteIntegrationJSONValue {
        let method = history ? "records:queryHistoryRecord" : "records:queryRecord"
        var components = URLComponents(string: "https://chromeuxreport.googleapis.com/v1/\(method)")!
        if !apiKey.isEmpty { components.queryItems = [URLQueryItem(name: "key", value: apiKey)] }
        guard let url = components.url else { throw SiteIntegrationDetailAPIError.invalidResponse }
        var body: [String: Any] = ["url": siteURL.absoluteString]
        if history { body["collectionPeriodCount"] = 40 }
        return try await requestJSON(method: "POST", url: url, jsonBody: body)
    }

    private func cruxCurrentSection(_ response: SiteIntegrationJSONValue) -> SiteIntegrationDetailSection {
        let metrics = response["record"]?["metrics"]?.objectValue ?? [:]
        var fields: [SiteIntegrationDetailField] = []
        for key in metrics.keys.sorted() {
            let metric = metrics[key]
            if let percentiles = metric?["percentiles"]?.objectValue {
                for percentile in percentiles.keys.sorted() {
                    fields.append(SiteIntegrationDetailField(
                        key: "\(key).\(percentile)",
                        label: "\(humanized(key)) \(percentile.uppercased())",
                        value: percentiles[percentile] ?? .null
                    ))
                }
            }
            if let fractions = metric?["fractions"]?.objectValue {
                for fraction in fractions.keys.sorted() {
                    fields.append(SiteIntegrationDetailField(
                        key: "\(key).\(fraction)",
                        label: "\(humanized(key)) · \(humanized(fraction))",
                        value: fractions[fraction] ?? .null
                    ))
                }
            }
        }
        return SiteIntegrationDetailSection(id: "crux.current", title: "Chrome UX field data", fields: fields)
    }

    private func cruxHistorySeries(_ response: SiteIntegrationJSONValue) -> SiteIntegrationDetailSeries? {
        guard let record = response["record"],
              let periods = record["collectionPeriods"]?.arrayValue,
              let metrics = record["metrics"]?.objectValue,
              !periods.isEmpty else { return nil }
        var points = periods.map { period -> SiteIntegrationDetailSeriesPoint in
            let lastDate = period["lastDate"]
            let date = [lastDate?["year"]?.stringValue, lastDate?["month"]?.stringValue, lastDate?["day"]?.stringValue]
                .compactMap { $0 }
                .joined(separator: "-")
            return SiteIntegrationDetailSeriesPoint(x: date, values: [:])
        }
        var labels: [String: String] = [:]
        for key in metrics.keys.sorted() {
            let values = metrics[key]?["percentilesTimeseries"]?["p75s"]?.arrayValue ?? []
            labels[key] = "\(humanized(key)) P75"
            for index in points.indices where values.indices.contains(index) {
                guard let value = values[index].numberValue else { continue }
                points[index] = SiteIntegrationDetailSeriesPoint(
                    x: points[index].x,
                    values: points[index].values.merging([key: value]) { _, new in new }
                )
            }
        }
        return SiteIntegrationDetailSeries(id: "crux.history", title: "Chrome UX history", metricLabels: labels, points: points)
    }

    // MARK: Bing Webmaster

    private func fetchBing(siteURL: String, apiKey: String) async throws -> SiteIntegrationDetailPayload {
        guard URL(string: siteURL)?.scheme?.lowercased() == "https" else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("Bing requires a valid HTTPS site URL.")
        }
        let methods: [(id: String, title: String)] = [
            ("GetRankAndTrafficStats", "Rank and traffic"),
            ("GetCrawlStats", "Crawl statistics"),
            ("GetQueryStats", "Search queries"),
            ("GetPageStats", "Pages"),
            ("GetCrawlIssues", "Crawl issues"),
            ("GetFeeds", "Sitemaps and feeds"),
            ("GetLinkCounts", "Link counts")
        ]
        var raw: [String: SiteIntegrationJSONValue] = [:]
        var tables: [SiteIntegrationDetailTable] = []
        var series: [SiteIntegrationDetailSeries] = []
        var warnings: [String] = []
        for method in methods {
            do {
                var components = URLComponents(string: "https://ssl.bing.com/webmaster/api.svc/json/\(method.id)")!
                components.queryItems = [
                    URLQueryItem(name: "siteUrl", value: siteURL),
                    URLQueryItem(name: "apikey", value: apiKey)
                ]
                guard let url = components.url else { throw SiteIntegrationDetailAPIError.invalidResponse }
                let response = try await requestJSON(url: url)
                raw[method.id] = response
                let rows = rows(from: response["d"] ?? .null)
                tables.append(SiteIntegrationDetailTable(
                    id: "bing.\(method.id)", title: method.title,
                    columns: orderedColumns(rows, preferred: ["Date"]), rows: rows
                ))
                if method.id == "GetRankAndTrafficStats" || method.id == "GetCrawlStats" {
                    series.append(bingSeries(id: method.id, title: method.title, rows: rows))
                }
            } catch {
                warnings.append("\(method.title) could not load: \(error.localizedDescription)")
            }
        }
        guard !raw.isEmpty else { throw SiteIntegrationDetailAPIError.invalidResponse }
        return SiteIntegrationDetailPayload(
            provider: .bingWebmaster,
            resourceID: siteURL,
            title: URL(string: siteURL)?.host ?? siteURL,
            series: series,
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func bingSeries(
        id: String,
        title: String,
        rows: [[String: SiteIntegrationJSONValue]]
    ) -> SiteIntegrationDetailSeries {
        let numericKeys = Set(rows.flatMap(\.keys)).filter { key in
            key.lowercased() != "date" && rows.contains { $0[key]?.numberValue != nil }
        }.sorted()
        let points = rows.compactMap { row -> SiteIntegrationDetailSeriesPoint? in
            guard let rawDate = row["Date"]?.stringValue ?? row["date"]?.stringValue else { return nil }
            return SiteIntegrationDetailSeriesPoint(
                x: normalizedBingDate(rawDate),
                values: Dictionary(uniqueKeysWithValues: numericKeys.compactMap { key in
                    row[key]?.numberValue.map { (key, $0) }
                })
            )
        }
        return SiteIntegrationDetailSeries(
            id: "bing.\(id).timeline", title: title,
            metricLabels: Dictionary(uniqueKeysWithValues: numericKeys.map { ($0, humanized($0)) }),
            points: points
        )
    }

    private func normalizedBingDate(_ value: String) -> String {
        guard value.hasPrefix("/Date(") else { return value }
        let milliseconds = value.dropFirst(6).prefix(while: { $0.isNumber || $0 == "-" })
        guard let number = Double(milliseconds) else { return value }
        return ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: number / 1_000))
    }

    // MARK: Microsoft Clarity

    private func fetchClarity(
        token: String,
        days: Int,
        dimensions: [String]
    ) async throws -> SiteIntegrationDetailPayload {
        let allowedDimensions = Set(["Browser", "Device", "Country/Region", "OS", "Source", "Medium", "Campaign", "Channel", "URL"])
        let selected = Array(dimensions.prefix(3))
        guard selected.allSatisfy(allowedDimensions.contains) else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration(
                "Clarity dimensions must be Browser, Device, Country/Region, OS, Source, Medium, Campaign, Channel, or URL."
            )
        }
        let safeDays = min(max(days, 1), 3)
        var components = URLComponents(string: "https://www.clarity.ms/export-data/api/v1/project-live-insights")!
        components.queryItems = [URLQueryItem(name: "numOfDays", value: String(safeDays))]
        for (index, dimension) in selected.enumerated() {
            components.queryItems?.append(URLQueryItem(name: "dimension\(index + 1)", value: dimension))
        }
        guard let url = components.url else { throw SiteIntegrationDetailAPIError.invalidResponse }
        let response = try await requestJSON(url: url, headers: ["Authorization": "Bearer \(token)"])
        guard let groups = response.arrayValue else {
            throw SiteIntegrationDetailAPIError.decoding("Clarity did not return a live-insights list.")
        }
        let tables = groups.enumerated().map { index, group -> SiteIntegrationDetailTable in
            let name = group["metricName"]?.stringValue ?? "Insight \(index + 1)"
            let rows = group["information"]?.arrayValue?.compactMap(\.objectValue) ?? []
            return SiteIntegrationDetailTable(
                id: "clarity.\(slug(name)).\(index)", title: name,
                columns: orderedColumns(rows), rows: rows
            )
        }
        let section = SiteIntegrationDetailSection(
            id: "clarity.request",
            title: "Live insights",
            fields: [
                SiteIntegrationDetailField(key: "days", label: "Days", value: .number(Double(safeDays))),
                SiteIntegrationDetailField(
                    key: "dimensions", label: "Dimensions",
                    value: .array(selected.map(SiteIntegrationJSONValue.string))
                )
            ]
        )
        return SiteIntegrationDetailPayload(
            provider: .clarity,
            resourceID: "clarity.live",
            title: "Microsoft Clarity",
            sections: [section],
            tables: tables,
            rawResponses: ["liveInsights": response]
        )
    }

    // MARK: Plausible

    private struct PlausibleQuery {
        let id: String
        let title: String
        let dimensions: [String]
        let metrics: [String]
        let timeline: Bool
    }

    private struct PlausiblePagedReport {
        let merged: SiteIntegrationJSONValue
        let pages: [SiteIntegrationJSONValue]
        let totalRows: Int
        let truncated: Bool
    }

    private func fetchPlausible(
        siteID: String,
        apiKey: String,
        range: SiteIntegrationDetailRange
    ) async throws -> SiteIntegrationDetailPayload {
        guard !siteID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("A Plausible site ID is required.")
        }
        let endpoint = URL(string: "https://plausible.io/api/v2/query")!
        let queries = [
            PlausibleQuery(
                id: "overview", title: "Overview", dimensions: [],
                metrics: ["visitors", "visits", "pageviews", "views_per_visit", "bounce_rate", "visit_duration", "events"],
                timeline: false
            ),
            PlausibleQuery(
                id: "timeline", title: "Traffic over time", dimensions: ["time:day"],
                metrics: ["visitors", "visits", "pageviews"], timeline: true
            ),
            PlausibleQuery(
                id: "sources", title: "Sources", dimensions: ["visit:source"],
                metrics: ["visitors", "visits", "bounce_rate"], timeline: false
            ),
            PlausibleQuery(
                id: "pages", title: "Pages", dimensions: ["event:page"],
                // Page breakdowns cannot use visit-level bounce/duration metrics in Stats API v2.
                metrics: ["visitors", "pageviews", "time_on_page"], timeline: false
            ),
            PlausibleQuery(
                id: "goals", title: "Goals", dimensions: ["event:goal"],
                metrics: ["visitors", "events", "conversion_rate"], timeline: false
            ),
            PlausibleQuery(
                id: "countries", title: "Countries", dimensions: ["visit:country"],
                metrics: ["visitors", "visits"], timeline: false
            ),
            PlausibleQuery(
                id: "devices", title: "Devices", dimensions: ["visit:device"],
                metrics: ["visitors", "visits"], timeline: false
            )
        ]
        var raw: [String: SiteIntegrationJSONValue] = [:]
        var sections: [SiteIntegrationDetailSection] = []
        var tables: [SiteIntegrationDetailTable] = []
        var series: [SiteIntegrationDetailSeries] = []
        var warnings: [String] = []

        var remainingReportRows = Self.maximumPayloadRows
        for (queryIndex, query) in queries.enumerated() {
            do {
                let body: [String: Any] = [
                    "site_id": siteID,
                    "date_range": [range.startDate, range.endDate],
                    "dimensions": query.dimensions,
                    "metrics": query.metrics,
                    "filters": [],
                    // Plausible only emits meta.total_rows when this flag is set. Without it,
                    // a full first page is indistinguishable from the last page.
                    "include": ["total_rows": true]
                ]
                let remainingQueries = max(1, queries.count - queryIndex)
                let result = try await fetchPlausiblePages(
                    endpoint: endpoint,
                    body: body,
                    apiKey: apiKey,
                    maximumRows: min(
                        Self.maximumPlausibleRows,
                        max(1, remainingReportRows / remainingQueries)
                    )
                )
                remainingReportRows = max(
                    0,
                    remainingReportRows - (result.merged["results"]?.arrayValue?.count ?? 0)
                )
                raw[query.id] = .array(result.pages)
                if result.truncated {
                    let keptRows = result.merged["results"]?.arrayValue?.count ?? 0
                    warnings.append(
                        "Plausible \(query.title.lowercased()) has \(result.totalRows) rows; "
                        + "Verceltics kept the first \(keptRows) rows to protect device memory."
                    )
                }
                let normalized = normalizePlausible(result.merged, query: query)
                if query.id == "overview", let first = normalized.rows.first {
                    sections.append(SiteIntegrationDetailSection(
                        id: "plausible.overview", title: "Overview · \(range.startDate) – \(range.endDate)",
                        fields: first.keys.sorted().map {
                            SiteIntegrationDetailField(key: $0, label: humanized($0), value: first[$0] ?? .null)
                        }
                    ))
                } else if query.timeline {
                    series.append(SiteIntegrationDetailSeries(
                        id: "plausible.timeline", title: query.title,
                        metricLabels: Dictionary(uniqueKeysWithValues: query.metrics.map { ($0, humanized($0)) }),
                        points: normalized.rows.compactMap { row in
                            guard let x = row[query.dimensions[0]]?.stringValue else { return nil }
                            return SiteIntegrationDetailSeriesPoint(
                                x: x,
                                values: Dictionary(uniqueKeysWithValues: query.metrics.compactMap { metric in
                                    row[metric]?.numberValue.map { (metric, $0) }
                                })
                            )
                        }
                    ))
                } else {
                    tables.append(SiteIntegrationDetailTable(
                        id: "plausible.\(query.id)", title: query.title,
                        columns: query.dimensions + query.metrics,
                        rows: normalized.rows,
                        nextCursor: normalized.nextCursor
                    ))
                }
                if let warning = result.merged["meta"]?["imports_warning"]?.stringValue, !warning.isEmpty {
                    warnings.append(warning)
                }
            } catch {
                if query.id == "overview" { throw error }
                warnings.append("Plausible \(query.title.lowercased()) could not load: \(error.localizedDescription)")
            }
        }
        return SiteIntegrationDetailPayload(
            provider: .plausible,
            resourceID: siteID,
            title: siteID,
            sections: sections,
            series: series,
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func fetchPlausiblePages(
        endpoint: URL,
        body: [String: Any],
        apiKey: String,
        maximumRows: Int
    ) async throws -> PlausiblePagedReport {
        var offset = 0
        var totalRows: Int?
        var pages: [SiteIntegrationJSONValue] = []
        var pageCount = 0
        var results: [SiteIntegrationJSONValue] = []
        var firstResponse: SiteIntegrationJSONValue?

        while offset < min(totalRows ?? Int.max, maximumRows) {
            try Task.checkCancellation()
            let remaining = maximumRows - results.count
            guard remaining > 0 else { break }
            var pageBody = body
            pageBody["pagination"] = [
                "limit": min(Self.plausiblePageSize, remaining),
                "offset": offset
            ]
            let response = try await requestJSON(
                method: "POST",
                url: endpoint,
                jsonBody: pageBody,
                headers: ["Authorization": "Bearer \(apiKey)"]
            )
            pageCount += 1
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            if firstResponse == nil { firstResponse = response }
            let pageResults = response["results"]?.arrayValue ?? []
            results.append(contentsOf: pageResults.prefix(remaining))
            totalRows = max(
                totalRows ?? 0,
                max(results.count, Int(response["meta"]?["total_rows"]?.numberValue ?? Double(results.count)))
            )
            if results.count >= (totalRows ?? results.count) { break }
            if results.count >= maximumRows
                || pageCount >= Self.maximumPaginationPages {
                break
            }
            guard !pageResults.isEmpty else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "Plausible stopped returning rows before the reported total was reached."
                )
            }
            offset += pageResults.count
        }

        guard let firstResponse else { throw SiteIntegrationDetailAPIError.invalidResponse }
        var merged = firstResponse.objectValue ?? [:]
        merged["results"] = .array(results)
        if var meta = merged["meta"]?.objectValue {
            meta["total_rows"] = .number(Double(totalRows ?? results.count))
            merged["meta"] = .object(meta)
        }
        let finalTotal = totalRows ?? results.count
        return PlausiblePagedReport(
            merged: .object(merged),
            pages: pages,
            totalRows: finalTotal,
            truncated: finalTotal > results.count
        )
    }

    private func normalizePlausible(
        _ response: SiteIntegrationJSONValue,
        query: PlausibleQuery
    ) -> (rows: [[String: SiteIntegrationJSONValue]], nextCursor: String?) {
        let rows = response["results"]?.arrayValue?.map { result -> [String: SiteIntegrationJSONValue] in
            var row: [String: SiteIntegrationJSONValue] = [:]
            let dimensions = result["dimensions"]?.arrayValue ?? []
            for index in query.dimensions.indices where dimensions.indices.contains(index) {
                row[query.dimensions[index]] = dimensions[index]
            }
            let metrics = result["metrics"]?.arrayValue ?? []
            for index in query.metrics.indices where metrics.indices.contains(index) {
                row[query.metrics[index]] = metrics[index]
            }
            return row
        } ?? []
        let total = Int(response["meta"]?["total_rows"]?.numberValue ?? Double(rows.count))
        return (rows, total > rows.count ? "\(rows.count)/\(total) rows returned" : nil)
    }

    // MARK: Umami

    private func fetchUmami(
        websiteID: String,
        baseURL: URL,
        authentication: SiteIntegrationDetailUmamiAuthentication,
        range: SiteIntegrationDetailRange
    ) async throws -> SiteIntegrationDetailPayload {
        guard baseURL.scheme?.lowercased() == "https", baseURL.host != nil else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("Umami requires a valid HTTPS API base URL.")
        }
        let headers: [String: String]
        switch authentication {
        case .cloudAPIKey(let key): headers = ["x-umami-api-key": key]
        case .bearerToken(let token): headers = ["Authorization": "Bearer \(token)"]
        }
        let pathID = try pathComponent(websiteID)
        let commonItems = [
            URLQueryItem(name: "startAt", value: String(range.startMilliseconds)),
            URLQueryItem(name: "endAt", value: String(range.endMilliseconds))
        ]
        var raw: [String: SiteIntegrationJSONValue] = [:]
        var sections: [SiteIntegrationDetailSection] = []
        var tables: [SiteIntegrationDetailTable] = []
        var series: [SiteIntegrationDetailSeries] = []
        var warnings: [String] = []

        let statsURL = try endpoint(base: baseURL, path: "websites/\(pathID)/stats", queryItems: commonItems)
        let stats = try await requestJSON(url: statsURL, headers: headers)
        raw["stats"] = stats
        sections.append(SiteIntegrationDetailSection(
            id: "umami.stats", title: "Traffic overview",
            fields: flattenedFields(stats, maximumDepth: 2)
        ))

        do {
            let pageviewsURL = try endpoint(
                base: baseURL,
                path: "websites/\(pathID)/pageviews",
                queryItems: commonItems + [URLQueryItem(name: "unit", value: "day")]
            )
            let pageviews = try await requestJSON(url: pageviewsURL, headers: headers)
            raw["pageviews"] = pageviews
            if let timeline = umamiPageviewSeries(pageviews) { series.append(timeline) }
        } catch {
            warnings.append("Umami pageview history could not load: \(error.localizedDescription)")
        }

        let metricTypes: [(id: String, value: String, title: String)] = [
            ("paths", "path", "Paths"), ("entry-pages", "entry", "Entry pages"),
            ("exit-pages", "exit", "Exit pages"), ("titles", "title", "Page titles"),
            ("queries", "query", "Query parameters"), ("referrers", "referrer", "Referrers"),
            ("channels", "channel", "Channels"), ("domains", "domain", "Referrer domains"),
            ("countries", "country", "Countries"), ("regions", "region", "Regions"),
            ("cities", "city", "Cities"), ("browsers", "browser", "Browsers"),
            ("systems", "os", "Operating systems"), ("devices", "device", "Devices"),
            ("languages", "language", "Languages"), ("screens", "screen", "Screen sizes"),
            ("events", "event", "Events"), ("hostnames", "hostname", "Hostnames"),
            ("tags", "tag", "Tags"), ("distinct-ids", "distinctId", "Distinct IDs")
        ]
        var remainingMetricRows = Self.maximumPayloadRows
        for (metricIndex, metric) in metricTypes.enumerated() {
            do {
                let remainingMetrics = max(1, metricTypes.count - metricIndex)
                let metricRowLimit = min(
                    Self.maximumUmamiMetricRows,
                    max(1, remainingMetricRows / remainingMetrics)
                )
                let result = try await fetchUmamiMetricPages(
                    base: baseURL,
                    websiteID: pathID,
                    type: metric.value,
                    commonItems: commonItems,
                    headers: headers,
                    maximumRows: metricRowLimit
                )
                remainingMetricRows = max(0, remainingMetricRows - result.items.count)
                raw["metrics.\(metric.id)"] = .array(result.pages)
                let rows = result.items.map(flattenedObject)
                tables.append(SiteIntegrationDetailTable(
                    id: "umami.\(metric.id)", title: metric.title,
                    columns: orderedColumns(rows, preferred: ["x", "name", "value", "y"]), rows: rows
                ))
                if result.capReached {
                    warnings.append(
                        "Umami \(metric.title.lowercased()) reached the \(metricRowLimit)-row "
                        + "per-report limit within the shared device budget; additional rows may be available."
                    )
                }
            } catch {
                warnings.append("Umami \(metric.title.lowercased()) could not load: \(error.localizedDescription)")
            }
        }

        do {
            let activeURL = try endpoint(base: baseURL, path: "websites/\(pathID)/active", queryItems: [])
            let active = try await requestJSON(url: activeURL, headers: headers)
            raw["active"] = active
            sections.append(SiteIntegrationDetailSection(
                id: "umami.active", title: "Active visitors", fields: flattenedFields(active, maximumDepth: 2)
            ))
        } catch {
            warnings.append("Umami active visitors could not load: \(error.localizedDescription)")
        }

        do {
            let eventsURL = try endpoint(
                base: baseURL,
                path: "websites/\(pathID)/events/series",
                queryItems: commonItems + [URLQueryItem(name: "unit", value: "day")]
            )
            let events = try await requestJSON(url: eventsURL, headers: headers)
            raw["events.series"] = events
            let eventRows = rows(from: events)
            if let eventSeries = simpleXYSeries(
                id: "umami.events.timeline", title: "Events over time", rows: eventRows,
                xCandidates: ["x", "date", "timestamp"], yCandidates: ["y", "value", "events"]
            ) {
                series.append(eventSeries)
            }
        } catch {
            warnings.append("Umami event history could not load: \(error.localizedDescription)")
        }

        return SiteIntegrationDetailPayload(
            provider: .umami,
            resourceID: websiteID,
            title: "Umami · \(websiteID)",
            sections: sections,
            series: series,
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func fetchUmamiMetricPages(
        base: URL,
        websiteID: String,
        type: String,
        commonItems: [URLQueryItem],
        headers: [String: String],
        maximumRows: Int
    ) async throws -> (
        pages: [SiteIntegrationJSONValue],
        items: [SiteIntegrationJSONValue],
        capReached: Bool
    ) {
        var offset = 0
        var pages: [SiteIntegrationJSONValue] = []
        var items: [SiteIntegrationJSONValue] = []
        var lastPageFilledRequest = false

        while items.count < maximumRows {
            try Task.checkCancellation()
            let limit = min(
                Self.umamiMetricPageSize,
                maximumRows - items.count
            )
            let url = try endpoint(
                base: base,
                path: "websites/\(websiteID)/metrics",
                queryItems: commonItems + [
                    URLQueryItem(name: "type", value: type),
                    URLQueryItem(name: "limit", value: String(limit)),
                    URLQueryItem(name: "offset", value: String(offset))
                ]
            )
            let response = try await requestJSON(url: url, headers: headers)
            guard let pageItems = response.arrayValue else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "Umami did not return a metrics list for \(type)."
                )
            }
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            items.append(contentsOf: pageItems.prefix(limit))
            lastPageFilledRequest = pageItems.count >= limit
            if pageItems.count < limit { break }
            offset += pageItems.count
        }

        return (
            pages,
            items,
            items.count == maximumRows && lastPageFilledRequest
        )
    }

    private func umamiPageviewSeries(_ response: SiteIntegrationJSONValue) -> SiteIntegrationDetailSeries? {
        var byDate: [String: [String: Double]] = [:]
        let sources = [("pageviews", response["pageviews"]), ("sessions", response["sessions"])]
        for (metric, value) in sources {
            for row in rows(from: value ?? .null) {
                guard let x = row["x"]?.stringValue ?? row["date"]?.stringValue,
                      let y = row["y"]?.numberValue ?? row["value"]?.numberValue else { continue }
                byDate[x, default: [:]][metric] = y
            }
        }
        guard !byDate.isEmpty else { return nil }
        return SiteIntegrationDetailSeries(
            id: "umami.pageviews.timeline", title: "Pageviews and sessions",
            metricLabels: ["pageviews": "Pageviews", "sessions": "Sessions"],
            points: byDate.keys.sorted().map { SiteIntegrationDetailSeriesPoint(x: $0, values: byDate[$0] ?? [:]) }
        )
    }

    // MARK: UptimeRobot

    private func fetchUptimeRobot(
        monitorID: String,
        apiKey: String,
        range: SiteIntegrationDetailRange
    ) async throws -> SiteIntegrationDetailPayload {
        let historyStart = max(range.start, range.end.addingTimeInterval(-7 * 86_400))
        let historyRange = SiteIntegrationDetailRange(start: historyStart, end: range.end)
        let monitor = try await uptimeRobotRequest(
            method: "getMonitors",
            apiKey: apiKey,
            fields: [
                "monitors": monitorID,
                "logs": "1",
                "logs_start_date": String(range.startSeconds),
                "logs_end_date": String(range.endSeconds),
                "response_times": "1",
                "response_times_start_date": String(historyRange.startSeconds),
                "response_times_end_date": String(historyRange.endSeconds),
                "response_times_average": "30",
                "alert_contacts": "1",
                "mwindows": "1",
                "ssl": "1",
                "custom_http_headers": "1",
                "custom_http_statuses": "1",
                "http_request_details": "true",
                "auth_type": "true",
                "timezone": "1",
                "custom_uptime_ratios": "1-7-30"
            ]
        )
        guard let selectedMonitor = monitor["monitors"]?.arrayValue?.first else {
            throw SiteIntegrationDetailAPIError.decoding("UptimeRobot did not return the selected monitor.")
        }
        var raw: [String: SiteIntegrationJSONValue] = ["monitor": monitor]
        var tables: [SiteIntegrationDetailTable] = []
        var warnings: [String] = []

        let logs = rows(from: selectedMonitor["logs"] ?? .null)
        tables.append(SiteIntegrationDetailTable(
            id: "uptimerobot.logs", title: "Monitor logs",
            columns: orderedColumns(logs, preferred: ["datetime", "type", "duration", "reason"]), rows: logs
        ))
        let responseTimes = rows(from: selectedMonitor["response_times"] ?? .null)
        let responseSeries = simpleXYSeries(
            id: "uptimerobot.response-times", title: "Response time",
            rows: responseTimes, xCandidates: ["datetime", "x", "date"],
            yCandidates: ["value", "y", "response_time"], metricKey: "responseTime",
            metricLabel: "Response time (ms)"
        )
        tables.append(SiteIntegrationDetailTable(
            id: "uptimerobot.response-times.table", title: "Response-time samples",
            columns: orderedColumns(responseTimes, preferred: ["datetime", "value"]), rows: responseTimes
        ))

        do {
            let response = try await uptimeRobotRequest(
                method: "getAccountDetails",
                apiKey: apiKey,
                fields: [:]
            )
            raw["getAccountDetails"] = response
            let accountRows = rows(from: response["account"] ?? .null)
            if !accountRows.isEmpty {
                tables.append(SiteIntegrationDetailTable(
                    id: "uptimerobot.get-account-details",
                    title: "Account details",
                    columns: orderedColumns(accountRows),
                    rows: accountRows
                ))
            }
        } catch {
            warnings.append("UptimeRobot account details could not load: \(error.localizedDescription)")
        }

        let companionMethods: [(method: String, id: String, title: String, itemKey: String, paginated: Bool)] = [
            ("getAlertContacts", "get-alert-contacts", "Alert contacts", "alert_contacts", true),
            ("getMWindows", "get-maintenance-windows", "Maintenance windows", "mwindows", true),
            ("getPSPs", "get-public-status-pages", "Public status pages", "psps", true)
        ]
        for item in companionMethods {
            do {
                let result: PagedCollection
                if item.paginated {
                    result = try await fetchUptimeRobotPages(
                        method: item.method,
                        apiKey: apiKey,
                        itemKey: item.itemKey
                    )
                } else {
                    let response = try await uptimeRobotRequest(
                        method: item.method,
                        apiKey: apiKey,
                        fields: [:]
                    )
                    result = PagedCollection(
                        pages: [response],
                        items: response[item.itemKey]?.arrayValue ?? [],
                        truncated: false
                    )
                }
                raw[item.method] = .array(result.pages)
                if result.truncated {
                    warnings.append(
                        "UptimeRobot \(item.title.lowercased()) reached the on-device pagination limit; additional rows may be available."
                    )
                }
                let itemRows = result.items.map(flattenedObject)
                if !itemRows.isEmpty {
                    tables.append(SiteIntegrationDetailTable(
                        id: "uptimerobot.\(item.id)", title: item.title,
                        columns: orderedColumns(itemRows), rows: itemRows
                    ))
                }
            } catch {
                warnings.append("UptimeRobot \(item.title.lowercased()) could not load: \(error.localizedDescription)")
            }
        }
        if historyRange.start > range.start {
            warnings.append("UptimeRobot response-time samples are limited to the latest seven days; logs use the full requested range.")
        }
        return SiteIntegrationDetailPayload(
            provider: .uptimeRobot,
            resourceID: monitorID,
            title: selectedMonitor["friendly_name"]?.stringValue ?? "UptimeRobot monitor \(monitorID)",
            sections: [SiteIntegrationDetailSection(
                id: "uptimerobot.monitor", title: "Monitor configuration and status",
                fields: flattenedFields(selectedMonitor, maximumDepth: 2)
            )],
            series: responseSeries.map { [$0] } ?? [],
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func uptimeRobotRequest(
        method: String,
        apiKey: String,
        fields: [String: String]
    ) async throws -> SiteIntegrationJSONValue {
        guard let url = URL(string: "https://api.uptimerobot.com/v2/\(method)") else {
            throw SiteIntegrationDetailAPIError.invalidResponse
        }
        var values = fields
        values["api_key"] = apiKey
        values["format"] = "json"
        let response = try await requestJSON(
            method: "POST", url: url,
            body: formData(values),
            headers: [:],
            contentType: "application/x-www-form-urlencoded"
        )
        if response["stat"]?.stringValue?.lowercased() == "fail" {
            throw SiteIntegrationDetailAPIError.invalidResponse
        }
        return response
    }

    private func fetchUptimeRobotPages(
        method: String,
        apiKey: String,
        itemKey: String
    ) async throws -> PagedCollection {
        var offset = 0
        var pages: [SiteIntegrationJSONValue] = []
        var pageCount = 0
        var items: [SiteIntegrationJSONValue] = []
        var seenOffsets: Set<Int> = []
        var truncated = false

        while true {
            try Task.checkCancellation()
            guard seenOffsets.insert(offset).inserted else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "UptimeRobot returned a repeated pagination offset for \(method)."
                )
            }
            let response = try await uptimeRobotRequest(
                method: method,
                apiKey: apiKey,
                fields: ["limit": "50", "offset": String(offset)]
            )
            pageCount += 1
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            let pageItems = response[itemKey]?.arrayValue ?? []
            let remaining = max(0, Self.maximumPagedCollectionRows - items.count)
            items.append(contentsOf: pageItems.prefix(remaining))
            if pageItems.count > remaining { truncated = true }

            // Legacy v2 methods are inconsistent: some nest pagination and others expose
            // limit/offset/total at the response root.
            let pagination = response["pagination"] ?? response
            let total = max(0, Int(pagination["total"]?.numberValue ?? Double(items.count)))
            if items.count >= total { break }
            if items.count >= Self.maximumPagedCollectionRows
                || pageCount >= Self.maximumPaginationPages {
                truncated = true
                break
            }
            guard !pageItems.isEmpty else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "UptimeRobot stopped returning \(itemKey) before the reported total was reached."
                )
            }
            let pageOffset = max(0, Int(pagination["offset"]?.numberValue ?? Double(offset)))
            offset = pageOffset + pageItems.count
        }
        return PagedCollection(pages: pages, items: items, truncated: truncated)
    }

    // MARK: Better Stack

    private func fetchBetterStack(
        monitorID: String,
        token: String,
        range: SiteIntegrationDetailRange
    ) async throws -> SiteIntegrationDetailPayload {
        let id = try pathComponent(monitorID)
        let headers = ["Authorization": "Bearer \(token)"]
        let base = URL(string: "https://uptime.betterstack.com/")!
        let monitorURL = try endpoint(base: base, path: "api/v2/monitors/\(id)", queryItems: [])
        let monitor = try await requestJSON(url: monitorURL, headers: headers)
        var raw: [String: SiteIntegrationJSONValue] = ["monitor": monitor]
        var sections: [SiteIntegrationDetailSection] = []
        var tables: [SiteIntegrationDetailTable] = []
        var series: [SiteIntegrationDetailSeries] = []
        var warnings: [String] = []
        let monitorData = monitor["data"] ?? monitor
        sections.append(SiteIntegrationDetailSection(
            id: "betterstack.monitor", title: "Monitor configuration and status",
            fields: flattenedFields(monitorData, maximumDepth: 3)
        ))

        do {
            let url = try endpoint(
                base: base, path: "api/v2/monitors/\(id)/response-times",
                queryItems: [
                    URLQueryItem(name: "from", value: range.startDate),
                    URLQueryItem(name: "to", value: range.endDate)
                ]
            )
            let response = try await requestJSON(url: url, headers: headers)
            raw["responseTimes"] = response
            let items = betterStackResponseTimeItems(response)
            let rows = items.map(flattenedObject)
            tables.append(SiteIntegrationDetailTable(
                id: "betterstack.response-times", title: "Response-time samples",
                columns: orderedColumns(rows, preferred: [
                    "attributes.at", "attributes.region", "attributes.response_time",
                    "attributes.name_lookup_time", "attributes.connection_time",
                    "attributes.tls_handshake_time", "attributes.data_transfer_time"
                ]), rows: rows
            ))
            if let timeline = betterStackResponseSeries(items) { series.append(timeline) }
        } catch {
            warnings.append("Better Stack response-time history could not load: \(error.localizedDescription)")
        }

        do {
            let url = try endpoint(
                base: base, path: "api/v2/monitors/\(id)/sla",
                queryItems: [
                    URLQueryItem(name: "from", value: range.startDate),
                    URLQueryItem(name: "to", value: range.endDate)
                ]
            )
            let response = try await requestJSON(url: url, headers: headers)
            raw["sla"] = response
            sections.append(SiteIntegrationDetailSection(
                id: "betterstack.sla", title: "SLA",
                fields: flattenedFields(response["data"] ?? response, maximumDepth: 4)
            ))
        } catch {
            warnings.append("Better Stack SLA could not load: \(error.localizedDescription)")
        }

        do {
            let url = try endpoint(
                base: base, path: "api/v3/incidents",
                queryItems: [
                    URLQueryItem(name: "monitor_id", value: monitorID),
                    URLQueryItem(name: "from", value: range.startDate),
                    URLQueryItem(name: "to", value: range.endDate),
                    URLQueryItem(name: "per_page", value: "50")
                ]
            )
            let result = try await fetchSameOriginLinkedPages(
                initialURL: url,
                headers: headers,
                itemKey: "data"
            )
            raw["incidents"] = .array(result.pages)
            if result.truncated {
                warnings.append(
                    "Better Stack incidents reached the on-device pagination limit; additional incidents may be available."
                )
            }
            let rows = result.items.map(flattenedObject)
            tables.append(SiteIntegrationDetailTable(
                id: "betterstack.incidents", title: "Incidents",
                columns: orderedColumns(rows, preferred: [
                    "id", "attributes.name", "attributes.status", "attributes.started_at",
                    "attributes.resolved_at", "attributes.cause"
                ]),
                rows: rows
            ))
        } catch {
            warnings.append("Better Stack incidents could not load: \(error.localizedDescription)")
        }

        let title = monitorData["attributes"]?["pronounceable_name"]?.stringValue
            ?? monitorData["attributes"]?["url"]?.stringValue
            ?? "Better Stack monitor \(monitorID)"
        return SiteIntegrationDetailPayload(
            provider: .betterStack,
            resourceID: monitorID,
            title: title,
            sections: sections,
            series: series,
            tables: tables,
            rawResponses: raw,
            warnings: warnings
        )
    }

    private func betterStackResponseSeries(
        _ items: [SiteIntegrationJSONValue]
    ) -> SiteIntegrationDetailSeries? {
        var byDate: [String: [String: Double]] = [:]
        var labels: [String: String] = [:]
        for item in items {
            let attributes = item["attributes"] ?? item
            guard let x = attributes["at"]?.stringValue
                    ?? attributes["checked_at"]?.stringValue
                    ?? attributes["created_at"]?.stringValue else { continue }
            let region = attributes["region"]?.stringValue ?? "default"
            let definitions = [
                ("response_time", "Response time"), ("name_lookup_time", "DNS lookup"),
                ("connection_time", "Connection"), ("tls_handshake_time", "TLS handshake"),
                ("data_transfer_time", "Data transfer")
            ]
            for (key, label) in definitions {
                guard let value = attributes[key]?.numberValue else { continue }
                let metric = "\(region).\(key)"
                byDate[x, default: [:]][metric] = value
                labels[metric] = region == "default" ? label : "\(region) · \(label)"
            }
        }
        guard !byDate.isEmpty else { return nil }
        return SiteIntegrationDetailSeries(
            id: "betterstack.response-times.timeline", title: "Response time by region",
            metricLabels: labels,
            points: byDate.keys.sorted().map { SiteIntegrationDetailSeriesPoint(x: $0, values: byDate[$0] ?? [:]) }
        )
    }

    /// Better Stack groups response-time samples under `data.attributes.regions[]`; retain the
    /// legacy flat-array fallback so older/self-hosted compatible responses still render.
    private func betterStackResponseTimeItems(
        _ response: SiteIntegrationJSONValue
    ) -> [SiteIntegrationJSONValue] {
        if let items = response["data"]?.arrayValue ?? response.arrayValue {
            return items
        }
        let data = response["data"] ?? response
        let attributes = data["attributes"] ?? data
        let regions = attributes["regions"]?.arrayValue ?? []
        var items: [SiteIntegrationJSONValue] = []
        for region in regions {
            let samples = region["response_times"]?.arrayValue
                ?? region["responseTimes"]?.arrayValue
                ?? []
            let regionFields = region.objectValue?.filter {
                $0.key != "response_times" && $0.key != "responseTimes"
            } ?? [:]
            for sample in samples {
                var fields = sample.objectValue ?? [:]
                for (key, value) in regionFields where fields[key] == nil {
                    fields[key] = value
                }
                items.append(.object(["attributes": .object(fields)]))
            }
        }
        return items
    }

    private func fetchSameOriginLinkedPages(
        initialURL: URL,
        headers: [String: String],
        itemKey: String
    ) async throws -> PagedCollection {
        var nextURL: URL? = initialURL
        var pages: [SiteIntegrationJSONValue] = []
        var pageCount = 0
        var items: [SiteIntegrationJSONValue] = []
        var visited: Set<String> = []
        var truncated = false

        while let url = nextURL {
            try Task.checkCancellation()
            guard visited.insert(url.absoluteString).inserted else {
                throw SiteIntegrationDetailAPIError.decoding(
                    "The provider returned a repeated pagination link."
                )
            }
            let response = try await requestJSON(url: url, headers: headers)
            pageCount += 1
            if pages.count < Self.maximumRetainedRawPagesPerEndpoint {
                pages.append(response)
            }
            let pageItems = response[itemKey]?.arrayValue ?? []
            let remaining = max(0, Self.maximumPagedCollectionRows - items.count)
            items.append(contentsOf: pageItems.prefix(remaining))
            if pageItems.count > remaining { truncated = true }

            guard let link = response["pagination"]?["next"]?.stringValue
                    ?? response["links"]?["next"]?.stringValue,
                  !link.isEmpty else {
                nextURL = nil
                continue
            }
            if items.count >= Self.maximumPagedCollectionRows
                || pageCount >= Self.maximumPaginationPages {
                truncated = true
                break
            }
            guard let candidate = URL(string: link, relativeTo: url)?.absoluteURL,
                  candidate.scheme?.lowercased() == "https",
                  candidate.host?.lowercased() == initialURL.host?.lowercased(),
                  effectivePort(candidate) == effectivePort(initialURL),
                  candidate.user == nil,
                  candidate.password == nil else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration(
                    "The provider returned an unsafe pagination URL."
                )
            }
            nextURL = candidate
        }
        return PagedCollection(pages: pages, items: items, truncated: truncated)
    }

    private func effectivePort(_ url: URL) -> Int {
        url.port ?? (url.scheme?.lowercased() == "https" ? 443 : -1)
    }

    // MARK: Aggregate device budget

    /// Applies one budget to the complete workspace value. This is intentionally done before the
    /// payload reaches the view model cache, so the cache cannot multiply several endpoint-level
    /// limits into hundreds of megabytes.
    private static func boundedForDevice(
        _ payload: SiteIntegrationDetailPayload
    ) -> SiteIntegrationDetailPayload {
        var remainingRows = maximumPayloadRows
        // Reserve room for model keys, titles, warnings and Codable container overhead.
        var remainingBytes = max(0, maximumPayloadBytes - 32 * 1_024)
        var didTruncate = false

        let sections = payload.sections.map { section in
            var fields: [SiteIntegrationDetailField] = []
            for field in section.fields {
                let cost = estimatedByteCount(field.value)
                    + estimatedStringByteCount(field.key)
                    + estimatedStringByteCount(field.label)
                guard cost <= remainingBytes else {
                    didTruncate = true
                    continue
                }
                remainingBytes -= cost
                fields.append(field)
            }
            return SiteIntegrationDetailSection(id: section.id, title: section.title, fields: fields)
        }

        let series = payload.series.map { item in
            var points: [SiteIntegrationDetailSeriesPoint] = []
            for point in item.points {
                let cost = estimatedByteCount(point)
                guard remainingRows > 0, cost <= remainingBytes else {
                    didTruncate = true
                    break
                }
                remainingRows -= 1
                remainingBytes -= cost
                points.append(point)
            }
            if points.count < item.points.count { didTruncate = true }
            return SiteIntegrationDetailSeries(
                id: item.id,
                title: item.title,
                metricLabels: item.metricLabels,
                points: points
            )
        }

        let tables = payload.tables.map { table in
            var retainedRows: [[String: SiteIntegrationJSONValue]] = []
            for row in table.rows {
                let cost = estimatedByteCount(row)
                guard remainingRows > 0, cost <= remainingBytes else {
                    didTruncate = true
                    break
                }
                remainingRows -= 1
                remainingBytes -= cost
                retainedRows.append(row)
            }
            let cursor: String?
            if retainedRows.count < table.rows.count {
                cursor = "Showing \(retainedRows.count.formatted()) of \(table.rows.count.formatted()) retained rows (device limit)"
            } else {
                cursor = table.nextCursor
            }
            return SiteIntegrationDetailTable(
                id: table.id,
                title: table.title,
                columns: table.columns,
                rows: retainedRows,
                nextCursor: cursor
            )
        }

        var rawResponses: [String: SiteIntegrationJSONValue] = [:]
        var remainingRawBytes = min(maximumRawResponseBytes, remainingBytes)
        for key in payload.rawResponses.keys.sorted() where remainingRawBytes > 0 {
            guard let value = payload.rawResponses[key] else { continue }
            let keyCost = estimatedStringByteCount(key) + 4
            guard keyCost < remainingRawBytes else {
                didTruncate = true
                break
            }
            var entryBudget = remainingRawBytes - keyCost
            let initialBudget = entryBudget
            var rawWasTruncated = false
            guard let retained = boundedJSONValue(
                value,
                remainingBytes: &entryBudget,
                didTruncate: &rawWasTruncated
            ) else {
                didTruncate = true
                continue
            }
            let used = keyCost + (initialBudget - entryBudget)
            remainingRawBytes -= used
            remainingBytes -= min(remainingBytes, used)
            rawResponses[key] = retained
            didTruncate = didTruncate || rawWasTruncated
        }
        if rawResponses.count < payload.rawResponses.count { didTruncate = true }

        var warnings = payload.warnings
        if didTruncate {
            warnings.append(
                "Some high-volume rows or raw response fields were omitted to keep this workspace within the on-device memory limit."
            )
        }
        return SiteIntegrationDetailPayload(
            provider: payload.provider,
            resourceID: payload.resourceID,
            title: payload.title,
            sections: sections,
            series: series,
            tables: tables,
            rawResponses: rawResponses,
            warnings: warnings,
            fetchedAt: payload.fetchedAt
        )
    }

    private static func boundedJSONValue(
        _ value: SiteIntegrationJSONValue,
        remainingBytes: inout Int,
        didTruncate: inout Bool
    ) -> SiteIntegrationJSONValue? {
        switch value {
        case .object(let object):
            guard remainingBytes >= 2 else { didTruncate = true; return nil }
            remainingBytes -= 2
            var retained: [String: SiteIntegrationJSONValue] = [:]
            for key in object.keys.sorted() {
                guard let child = object[key] else { continue }
                let keyCost = estimatedStringByteCount(key) + 2
                guard keyCost <= remainingBytes else { didTruncate = true; break }
                var childBudget = remainingBytes - keyCost
                guard let boundedChild = boundedJSONValue(
                    child,
                    remainingBytes: &childBudget,
                    didTruncate: &didTruncate
                ) else {
                    didTruncate = true
                    break
                }
                remainingBytes = childBudget
                retained[key] = boundedChild
            }
            if retained.count < object.count { didTruncate = true }
            return .object(retained)

        case .array(let values):
            guard remainingBytes >= 2 else { didTruncate = true; return nil }
            remainingBytes -= 2
            var retained: [SiteIntegrationJSONValue] = []
            for child in values {
                var childBudget = remainingBytes
                guard let boundedChild = boundedJSONValue(
                    child,
                    remainingBytes: &childBudget,
                    didTruncate: &didTruncate
                ) else {
                    didTruncate = true
                    break
                }
                remainingBytes = childBudget
                retained.append(boundedChild)
            }
            if retained.count < values.count { didTruncate = true }
            return .array(retained)

        case .string(let string):
            let cost = estimatedStringByteCount(string)
            guard cost <= remainingBytes else { didTruncate = true; return nil }
            remainingBytes -= cost
            return value

        case .integer, .unsignedInteger, .decimal, .number:
            guard remainingBytes >= 32 else { didTruncate = true; return nil }
            remainingBytes -= 32
            return value

        case .bool:
            guard remainingBytes >= 5 else { didTruncate = true; return nil }
            remainingBytes -= 5
            return value

        case .null:
            guard remainingBytes >= 4 else { didTruncate = true; return nil }
            remainingBytes -= 4
            return value
        }
    }

    private static func estimatedByteCount(_ point: SiteIntegrationDetailSeriesPoint) -> Int {
        estimatedStringByteCount(point.x)
            + point.values.reduce(16) { partial, pair in
                partial + estimatedStringByteCount(pair.key) + 32
            }
    }

    private static func estimatedByteCount(
        _ row: [String: SiteIntegrationJSONValue]
    ) -> Int {
        row.reduce(16) { partial, pair in
            partial + estimatedStringByteCount(pair.key) + estimatedByteCount(pair.value) + 4
        }
    }

    private static func estimatedByteCount(_ value: SiteIntegrationJSONValue) -> Int {
        switch value {
        case .object(let object): estimatedByteCount(object)
        case .array(let values): values.reduce(16) { $0 + estimatedByteCount($1) + 1 }
        case .string(let string): estimatedStringByteCount(string)
        case .integer, .unsignedInteger, .decimal, .number: 32
        case .bool: 5
        case .null: 4
        }
    }

    /// Six bytes per UTF-8 byte is a conservative bound for JSON escaping (for example `\\u0000`).
    private static func estimatedStringByteCount(_ value: String) -> Int {
        value.utf8.count * 6 + 2
    }

    // MARK: Transport and normalization

    private func requestJSON(
        method: String = "GET",
        url: URL,
        jsonBody: [String: Any]? = nil,
        body: Data? = nil,
        headers: [String: String] = [:],
        contentType: String = "application/json"
    ) async throws -> SiteIntegrationJSONValue {
        guard url.scheme?.lowercased() == "https", url.host != nil else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("Provider requests must use HTTPS.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let encodedBody: Data?
        if let jsonBody {
            guard JSONSerialization.isValidJSONObject(jsonBody) else {
                throw SiteIntegrationDetailAPIError.invalidConfiguration("The provider request body is invalid.")
            }
            encodedBody = try JSONSerialization.data(withJSONObject: jsonBody)
        } else {
            encodedBody = body
        }
        request.httpBody = encodedBody
        if encodedBody != nil { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        for (key, value) in headers { request.setValue(value, forHTTPHeaderField: key) }
        let (data, response) = try await ProviderRequestSecurity.data(
            for: request,
            using: session,
            maximumResponseBytes: Self.maximumResponseBytes
        )
        guard let http = response as? HTTPURLResponse else {
            throw SiteIntegrationDetailAPIError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw SiteIntegrationDetailAPIError.requestFailed(http.statusCode)
        }
        guard !data.isEmpty else { return .object([:]) }
        do {
            let value = try JSONDecoder()
                .decode(SiteIntegrationJSONValue.self, from: data)
                .sanitizingSecrets()
            return Self.sanitizingProviderConfigurationSecrets(value)
        } catch {
            throw SiteIntegrationDetailAPIError.decoding(error.localizedDescription)
        }
    }

    /// Redacts provider-specific request configuration that can carry credentials even though its
    /// field name is not a generic `token`/`password` name.
    private static func sanitizingProviderConfigurationSecrets(
        _ value: SiteIntegrationJSONValue
    ) -> SiteIntegrationJSONValue {
        switch value {
        case .object(let object):
            var sanitized: [String: SiteIntegrationJSONValue] = [:]
            for (key, child) in object {
                switch normalizedProviderKey(key) {
                case "postvalue", "customhttpheaders":
                    sanitized[key] = .string("[REDACTED]")
                case "proxyhost":
                    if case .string(let host) = child,
                       let separator = host.lastIndex(of: "@") {
                        sanitized[key] = .string(String(host[host.index(after: separator)...]))
                    } else {
                        sanitized[key] = child
                    }
                default:
                    sanitized[key] = sanitizingProviderConfigurationSecrets(child)
                }
            }
            return .object(sanitized)
        case .array(let values):
            return .array(values.map(sanitizingProviderConfigurationSecrets))
        case .string, .integer, .unsignedInteger, .decimal, .number, .bool, .null:
            return value
        }
    }

    private static func normalizedProviderKey(_ key: String) -> String {
        key.lowercased().unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
    }

    private func endpoint(
        base: URL,
        path: String,
        queryItems: [URLQueryItem]
    ) throws -> URL {
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https", components.host != nil else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("The provider API URL is invalid.")
        }
        var basePath = components.path
        if !basePath.hasSuffix("/") { basePath += "/" }
        components.path = basePath + path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let url = components.url else { throw SiteIntegrationDetailAPIError.invalidResponse }
        return url
    }

    private func pathComponent(_ value: String) throws -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        guard !value.isEmpty, let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) else {
            throw SiteIntegrationDetailAPIError.invalidConfiguration("A provider resource ID is required.")
        }
        return encoded
    }

    private func formData(_ values: [String: String]) -> Data {
        var components = URLComponents()
        components.queryItems = values.keys.sorted().map { URLQueryItem(name: $0, value: values[$0]) }
        return Data((components.percentEncodedQuery ?? "").utf8)
    }

    private func rows(from value: SiteIntegrationJSONValue) -> [[String: SiteIntegrationJSONValue]] {
        if let values = value.arrayValue {
            return values.enumerated().map { index, item in
                item.objectValue ?? ["index": .number(Double(index)), "value": item]
            }
        }
        if let object = value.objectValue { return [object] }
        if case .null = value { return [] }
        return [["value": value]]
    }

    private func orderedColumns(
        _ rows: [[String: SiteIntegrationJSONValue]],
        preferred: [String] = []
    ) -> [String] {
        let all = Set(rows.flatMap(\.keys))
        return preferred.filter(all.contains) + all.subtracting(preferred).sorted()
    }

    private func flattenedFields(
        _ value: SiteIntegrationJSONValue,
        maximumDepth: Int
    ) -> [SiteIntegrationDetailField] {
        var fields: [SiteIntegrationDetailField] = []
        func visit(_ value: SiteIntegrationJSONValue, path: String, depth: Int) {
            switch value {
            case .object(let object) where depth < maximumDepth:
                for key in object.keys.sorted() {
                    visit(object[key] ?? .null, path: path.isEmpty ? key : "\(path).\(key)", depth: depth + 1)
                }
            case .array where depth < maximumDepth:
                fields.append(SiteIntegrationDetailField(key: path, label: humanized(path), value: value))
            default:
                fields.append(SiteIntegrationDetailField(key: path, label: humanized(path), value: value))
            }
        }
        visit(value, path: "", depth: 0)
        return fields.filter { !$0.key.isEmpty }
    }

    private func flattenedObject(_ value: SiteIntegrationJSONValue) -> [String: SiteIntegrationJSONValue] {
        var result: [String: SiteIntegrationJSONValue] = [:]
        func visit(_ value: SiteIntegrationJSONValue, path: String) {
            switch value {
            case .object(let object):
                for key in object.keys.sorted() {
                    visit(object[key] ?? .null, path: path.isEmpty ? key : "\(path).\(key)")
                }
            default:
                result[path.isEmpty ? "value" : path] = value
            }
        }
        visit(value, path: "")
        return result
    }

    private func simpleXYSeries(
        id: String,
        title: String,
        rows: [[String: SiteIntegrationJSONValue]],
        xCandidates: [String],
        yCandidates: [String],
        metricKey: String = "value",
        metricLabel: String = "Value"
    ) -> SiteIntegrationDetailSeries? {
        let points = rows.compactMap { row -> SiteIntegrationDetailSeriesPoint? in
            guard let x = xCandidates.lazy.compactMap({ row[$0]?.stringValue }).first,
                  let y = yCandidates.lazy.compactMap({ row[$0]?.numberValue }).first else { return nil }
            return SiteIntegrationDetailSeriesPoint(x: x, values: [metricKey: y])
        }
        guard !points.isEmpty else { return nil }
        return SiteIntegrationDetailSeries(
            id: id, title: title, metricLabels: [metricKey: metricLabel], points: points
        )
    }

    private func humanized(_ value: String) -> String {
        let dotted = value.split(separator: ".").last.map(String.init) ?? value
        let underscored = dotted.replacingOccurrences(of: "_", with: " ")
        let withSpaces = underscored.replacingOccurrences(
            of: "([a-z0-9])([A-Z])", with: "$1 $2", options: .regularExpression
        )
        return withSpaces.capitalized
    }

    private func slug(_ value: String) -> String {
        let lowercase = value.lowercased()
        let replaced = lowercase.replacingOccurrences(
            of: "[^a-z0-9]+", with: "-", options: .regularExpression
        )
        return replaced.trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }
}
