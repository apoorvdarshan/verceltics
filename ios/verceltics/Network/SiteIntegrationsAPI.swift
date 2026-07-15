import Foundation

struct SiteIntegrationOAuthConfiguration: Equatable, Sendable {
    let authorizationEndpoint: URL
    let tokenEndpoint: URL
    let scopes: [String]
}

enum SiteIntegrationsAPIError: LocalizedError, Equatable {
    case invalidConfiguration(String)
    case oauthNotConfigured(provider: SiteIntegrationProvider, scopes: [String])
    case invalidResponse
    case requestFailed(Int, String)
    case decoding(String)

    var isUnauthorized: Bool {
        guard case .requestFailed(let status, _) = self else { return false }
        return status == 401
    }

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let message):
            message
        case .oauthNotConfigured(let provider, let scopes):
            "\(provider.displayName) requires the Verceltics iOS OAuth client to be configured before accounts can connect. Required scope: \(scopes.joined(separator: ", "))."
        case .invalidResponse:
            "The provider returned an invalid response."
        case .requestFailed(let status, let message):
            message.isEmpty ? "Request failed (HTTP \(status))." : "Request failed (HTTP \(status)): \(message)"
        case .decoding(let message):
            "Could not read the provider response: \(message)"
        }
    }
}

struct GoogleAnalyticsDataStreamDescriptor: Equatable, Sendable {
    let name: String
    let type: String
    let displayName: String?
    let measurementID: String?
    let defaultURL: URL?
}

struct GoogleAnalyticsStreamAttribution: Equatable, Sendable {
    let url: URL?
    let subtitle: String
    let metadata: [String: String]
    let isPropertyWide: Bool
}

private struct SiteIntegrationDetailResult: Sendable {
    let resource: SiteIntegrationResource
    let warnings: [String]
    let metricsArePartial: Bool
}

private struct SearchConsolePropertyDescriptor: Sendable {
    let propertyID: String
    let permission: String
    let displayURL: URL?
    let resourceID: String
}

private struct GoogleAnalyticsPropertyDescriptor: Sendable {
    let propertyName: String
    let propertyID: String
    let displayName: String
    let accountName: String
    let propertyType: String
    let canEdit: Bool
}

private struct BingSiteDescriptor: Sendable {
    let value: String
    let url: URL?
    let isVerified: Bool
    let resourceID: String
}

private struct UmamiSiteDescriptor: Sendable {
    let id: String
    let name: String
    let domain: String?
    let url: URL?
    let updatedAt: Date?
}

struct SiteIntegrationsAPI: Sendable {
    let account: SiteIntegrationAccount
    nonisolated private static let maximumConcurrentDetailLoads = 4

    // Shared by the native PKCE flow and token refresh implementation.
    static let googleSearchConsoleOAuth = SiteIntegrationOAuthConfiguration(
        authorizationEndpoint: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
        tokenEndpoint: URL(string: "https://oauth2.googleapis.com/token")!,
        scopes: [
            "openid",
            "email",
            "https://www.googleapis.com/auth/webmasters.readonly",
        ]
    )
    static let googleAnalyticsOAuth = SiteIntegrationOAuthConfiguration(
        authorizationEndpoint: URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!,
        tokenEndpoint: URL(string: "https://oauth2.googleapis.com/token")!,
        scopes: [
            "openid",
            "email",
            "https://www.googleapis.com/auth/analytics.readonly",
        ]
    )

    init(account: SiteIntegrationAccount) {
        self.account = account
    }

    init(
        provider: SiteIntegrationProvider,
        credential: String,
        metadata: [String: String] = [:]
    ) {
        account = SiteIntegrationAccount(
            provider: provider,
            name: provider.displayName,
            credential: credential,
            metadata: metadata
        )
    }

    func validateConnection() async throws -> String {
        try await validatedSnapshot().name
    }

    func validatedSnapshot() async throws -> (
        name: String,
        snapshot: SiteIntegrationSnapshot,
        connectionMetadata: [String: String]
    ) {
        async let discoveredConnectionMetadata = connectionMetadata()
        let snapshot = try await fetchSnapshot(accountID: account.id)
        let connectionMetadata = try await discoveredConnectionMetadata
        let name: String
        switch account.provider {
        case .googleSearchConsole:
            name = "Search Console · \(snapshot.resources.count) \(snapshot.resources.count == 1 ? "property" : "properties")"
        case .googleAnalytics:
            name = "Google Analytics · \(snapshot.resources.count) \(snapshot.resources.count == 1 ? "property" : "properties")"
        case .pageSpeed:
            name = snapshot.resources.first?.name ?? "PageSpeed & CrUX"
        case .bingWebmaster:
            name = "Bing Webmaster · \(snapshot.resources.count) site\(snapshot.resources.count == 1 ? "" : "s")"
        case .clarity:
            name = nonEmpty(account.metadata["projectName"]) ?? "Microsoft Clarity"
        case .plausible:
            name = try requiredMetadata("siteID", label: "Plausible site ID")
        case .umami:
            let host = try umamiAPIBaseURL().host
            name = host == "api.umami.is" ? "Umami Cloud" : "Umami · \(host ?? "Self-hosted")"
        case .uptimeRobot:
            name = "UptimeRobot · \(snapshot.resources.count) monitor\(snapshot.resources.count == 1 ? "" : "s")"
        case .betterStack:
            name = "Better Stack · \(snapshot.resources.count) monitor\(snapshot.resources.count == 1 ? "" : "s")"
        }
        return (name, snapshot, connectionMetadata)
    }

    func connectionMetadata() async throws -> [String: String] {
        guard account.provider == .umami else { return [:] }
        let context = try umamiRequestContext()
        let root = object(try await jsonRequest(
            url: try endpoint(base: context.base, path: "me"),
            headers: context.headers,
            maximumAttempts: 1,
            timeoutInterval: 8
        ))
        return try Self.umamiConnectionMetadata(from: root, endpoint: context.base)
    }

    func fetchSnapshot(accountID: UUID? = nil) async throws -> SiteIntegrationSnapshot {
        let id = accountID ?? account.id
        switch account.provider {
        case .googleSearchConsole:
            return try await fetchGoogleSearchConsoleSnapshot(accountID: id)
        case .googleAnalytics:
            return try await fetchGoogleAnalyticsSnapshot(accountID: id)
        case .pageSpeed:
            return try await fetchPageSpeedSnapshot(accountID: id)
        case .bingWebmaster:
            return try await fetchBingSnapshot(accountID: id)
        case .clarity:
            return try await fetchClaritySnapshot(accountID: id)
        case .plausible:
            return try await fetchPlausibleSnapshot(accountID: id)
        case .umami:
            return try await fetchUmamiSnapshot(accountID: id)
        case .uptimeRobot:
            return try await fetchUptimeRobotSnapshot(accountID: id)
        case .betterStack:
            return try await fetchBetterStackSnapshot(accountID: id)
        }
    }

    nonisolated static func detailIndexBatches(
        itemCount: Int,
        maximumConcurrent: Int = maximumConcurrentDetailLoads
    ) -> [[Int]] {
        guard itemCount > 0 else { return [] }
        let batchSize = max(1, maximumConcurrent)
        return stride(from: 0, to: itemCount, by: batchSize).map { start in
            Array(start..<min(start + batchSize, itemCount))
        }
    }

    private func boundedConcurrentMap<Input: Sendable, Output: Sendable>(
        _ values: [Input],
        operation: @escaping @MainActor @Sendable (Input) async throws -> Output
    ) async throws -> [Output] {
        var indexedResults: [(index: Int, value: Output)] = []
        indexedResults.reserveCapacity(values.count)

        for batch in Self.detailIndexBatches(itemCount: values.count) {
            try Task.checkCancellation()
            let batchResults = try await withThrowingTaskGroup(
                of: (Int, Output).self,
                returning: [(Int, Output)].self
            ) { group in
                for index in batch {
                    let value = values[index]
                    group.addTask { @MainActor in
                        (index, try await operation(value))
                    }
                }
                var completed: [(Int, Output)] = []
                completed.reserveCapacity(batch.count)
                for try await result in group {
                    completed.append(result)
                }
                return completed
            }
            indexedResults.append(contentsOf: batchResults)
        }

        return indexedResults.sorted { $0.index < $1.index }.map(\.value)
    }

    // MARK: - Google Search Console

    private func fetchGoogleSearchConsoleSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let headers = try googleAuthorizationHeaders(provider: .googleSearchConsole)
        let root = object(try await jsonRequest(
            url: URL(string: "https://www.googleapis.com/webmasters/v3/sites")!,
            headers: headers
        ))
        guard root["siteEntry"] != nil || root.isEmpty else {
            throw SiteIntegrationsAPIError.decoding("Search Console did not return a siteEntry list.")
        }
        let descriptors = array(root["siteEntry"]).compactMap { value -> SearchConsolePropertyDescriptor? in
            let entry = object(value)
            guard let propertyID = nonEmpty(string(entry["siteUrl"])) else { return nil }
            return SearchConsolePropertyDescriptor(
                propertyID: propertyID,
                permission: string(entry["permissionLevel"]) ?? "siteUnverifiedUser",
                displayURL: searchConsoleDisplayURL(propertyID),
                resourceID: stableSiteResourceID(propertyID)
            )
        }
        let detailResults = try await boundedConcurrentMap(descriptors) { descriptor in
            try await self.fetchSearchConsoleProperty(descriptor, headers: headers)
        }
        let resources = detailResults.map(\.resource)
        let warnings = detailResults.flatMap(\.warnings)
        let hasPartialMetrics = detailResults.contains(where: \.metricsArePartial)
        let metrics = markPartialMetrics(
            aggregateSearchConsoleMetrics(resources),
            when: hasPartialMetrics,
            excluding: ["searchconsole.properties"]
        )
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .googleSearchConsole,
            resources: resources,
            metrics: metrics,
            status: resources.isEmpty ? "No properties" : (hasPartialMetrics ? "Connected · Partial metrics" : "Connected"),
            warnings: warnings
        )
    }

    private func fetchSearchConsoleProperty(
        _ descriptor: SearchConsolePropertyDescriptor,
        headers: [String: String]
    ) async throws -> SiteIntegrationDetailResult {
        var metrics: [SiteIntegrationMetric] = []
        var warnings: [String] = []
        var metricsArePartial = false
        var metadata: [String: String] = [
            "propertyID": descriptor.propertyID,
            "permissionLevel": descriptor.permission,
            "range": "28 days",
        ]
        var status = descriptor.permission == "siteUnverifiedUser" ? "Unverified" : "Verified"

        do {
            metrics.append(contentsOf: try await fetchSearchConsoleAnalytics(
                propertyID: descriptor.propertyID,
                headers: headers,
                resourceID: descriptor.resourceID
            ))
        } catch {
            try throwIfGoogleUnauthorized(error)
            metricsArePartial = true
            warnings.append(
                "\(descriptor.propertyID) search performance could not load: \(error.localizedDescription)"
            )
        }

        do {
            let sitemapCount = try await fetchSearchConsoleSitemapCount(
                propertyID: descriptor.propertyID,
                headers: headers
            )
            metrics.append(metric(
                key: "searchconsole.sitemaps",
                label: "Sitemaps",
                value: Double(sitemapCount),
                unit: .count,
                resourceID: descriptor.resourceID
            ))
        } catch {
            try throwIfGoogleUnauthorized(error)
            metricsArePartial = true
            warnings.append("\(descriptor.propertyID) sitemaps could not load: \(error.localizedDescription)")
        }

        if let inspectionURL = descriptor.displayURL {
            do {
                let inspection = try await inspectSearchConsoleURL(
                    inspectionURL,
                    propertyID: descriptor.propertyID,
                    headers: headers
                )
                metadata.merge(inspection.metadata) { _, new in new }
                if let inspectionStatus = inspection.status { status = inspectionStatus }
            } catch {
                try throwIfGoogleUnauthorized(error)
                warnings.append("\(descriptor.propertyID) index status could not load: \(error.localizedDescription)")
            }
        }

        return SiteIntegrationDetailResult(
            resource: SiteIntegrationResource(
                id: descriptor.resourceID,
                provider: .googleSearchConsole,
                name: descriptor.displayURL?.host
                    ?? descriptor.propertyID.replacingOccurrences(of: "sc-domain:", with: ""),
                subtitle: descriptor.propertyID,
                url: descriptor.displayURL,
                status: status,
                updatedAt: date(metadata["lastCrawlTime"]),
                metrics: metrics,
                metadata: metadata
            ),
            warnings: warnings,
            metricsArePartial: metricsArePartial
        )
    }

    private func fetchSearchConsoleAnalytics(
        propertyID: String,
        headers: [String: String],
        resourceID: String
    ) async throws -> [SiteIntegrationMetric] {
        let endDate = googleDateString(.now)
        let startDate = googleDateString(Calendar(identifier: .gregorian).date(byAdding: .day, value: -27, to: .now) ?? .now)
        let property = try Self.pathComponent(propertyID)
        let url = URL(string: "https://www.googleapis.com/webmasters/v3/sites/\(property)/searchAnalytics/query")!
        let root = object(try await jsonRequest(
            method: "POST",
            url: url,
            body: try jsonData([
                "startDate": startDate,
                "endDate": endDate,
                "type": "web",
                "aggregationType": "byProperty",
                "rowLimit": 1,
                "dataState": "all",
            ]),
            headers: headers
        ))
        guard let row = array(root["rows"]).first.map(object) else { return [] }
        let definitions: [(String, String, SiteIntegrationMetricUnit, Double)] = [
            ("clicks", "Clicks", .count, 1),
            ("impressions", "Impressions", .count, 1),
            ("ctr", "CTR", .percent, 100),
            ("position", "Average Position", .position, 1),
        ]
        return definitions.compactMap { key, label, unit, multiplier in
            guard let value = number(row[key]) else { return nil }
            return metric(
                key: "searchconsole.\(key)",
                label: label,
                value: value * multiplier,
                unit: unit,
                resourceID: resourceID
            )
        }
    }

    private func fetchSearchConsoleSitemapCount(
        propertyID: String,
        headers: [String: String]
    ) async throws -> Int {
        let property = try Self.pathComponent(propertyID)
        let url = URL(string: "https://www.googleapis.com/webmasters/v3/sites/\(property)/sitemaps")!
        let root = object(try await jsonRequest(url: url, headers: headers))
        return array(root["sitemap"]).count
    }

    private func inspectSearchConsoleURL(
        _ inspectionURL: URL,
        propertyID: String,
        headers: [String: String]
    ) async throws -> (status: String?, metadata: [String: String]) {
        let root = object(try await jsonRequest(
            method: "POST",
            url: URL(string: "https://searchconsole.googleapis.com/v1/urlInspection/index:inspect")!,
            body: try jsonData([
                "inspectionUrl": inspectionURL.absoluteString,
                "siteUrl": propertyID,
                "languageCode": "en-US",
            ]),
            headers: headers
        ))
        let result = object(object(root["inspectionResult"])["indexStatusResult"])
        guard !result.isEmpty else { return (nil, [:]) }
        let verdict = string(result["verdict"])
        let coverageState = string(result["coverageState"])
        var metadata: [String: String] = [:]
        for (key, sourceKey) in [
            ("indexVerdict", "verdict"),
            ("coverageState", "coverageState"),
            ("pageFetchState", "pageFetchState"),
            ("robotsTxtState", "robotsTxtState"),
            ("indexingState", "indexingState"),
            ("lastCrawlTime", "lastCrawlTime"),
            ("googleCanonical", "googleCanonical"),
            ("userCanonical", "userCanonical"),
        ] {
            if let value = string(result[sourceKey]), !value.isEmpty { metadata[key] = value }
        }
        let status: String?
        if verdict == "PASS" {
            status = "Indexed"
        } else if let coverageState, !coverageState.isEmpty {
            status = coverageState
        } else {
            status = verdict?.capitalized
        }
        return (status, metadata)
    }

    private func aggregateSearchConsoleMetrics(_ resources: [SiteIntegrationResource]) -> [SiteIntegrationMetric] {
        let allMetrics = resources.flatMap(\.metrics)
        let clicks = allMetrics.filter { $0.key == "searchconsole.clicks" }.reduce(0) { $0 + $1.value }
        let impressions = allMetrics.filter { $0.key == "searchconsole.impressions" }.reduce(0) { $0 + $1.value }
        let sitemaps = allMetrics.filter { $0.key == "searchconsole.sitemaps" }.reduce(0) { $0 + $1.value }
        let positions = resources.compactMap { resource -> (Double, Double)? in
            guard let position = resource.metrics.first(where: { $0.key == "searchconsole.position" })?.value,
                  let propertyImpressions = resource.metrics.first(where: { $0.key == "searchconsole.impressions" })?.value,
                  propertyImpressions > 0 else { return nil }
            return (position, propertyImpressions)
        }
        let weightedPosition = positions.isEmpty
            ? nil
            : positions.reduce(0) { $0 + $1.0 * $1.1 } / positions.reduce(0) { $0 + $1.1 }
        var metrics = [
            metric(key: "searchconsole.properties", label: "Properties", value: Double(resources.count), unit: .count),
            metric(key: "searchconsole.clicks", label: "Clicks", value: clicks, unit: .count),
            metric(key: "searchconsole.impressions", label: "Impressions", value: impressions, unit: .count),
            metric(
                key: "searchconsole.ctr",
                label: "CTR",
                value: impressions > 0 ? clicks / impressions * 100 : 0,
                unit: .percent
            ),
            metric(key: "searchconsole.sitemaps", label: "Sitemaps", value: sitemaps, unit: .count),
        ]
        if let weightedPosition {
            metrics.append(metric(
                key: "searchconsole.position",
                label: "Average Position",
                value: weightedPosition,
                unit: .position
            ))
        }
        return metrics
    }

    private func searchConsoleDisplayURL(_ propertyID: String) -> URL? {
        if propertyID.hasPrefix("sc-domain:") {
            let domain = propertyID.replacingOccurrences(of: "sc-domain:", with: "")
            return URL(string: "https://\(domain)/")
        }
        return URL(string: propertyID)
    }

    // MARK: - Google Analytics 4

    private func fetchGoogleAnalyticsSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let headers = try googleAuthorizationHeaders(provider: .googleAnalytics)
        var summaries: [[String: Any]] = []
        var pageToken: String?
        var seenTokens = Set<String>()
        var warnings: [String] = []
        var summaryListIsPartial = false

        repeat {
            try Task.checkCancellation()
            var components = URLComponents(string: "https://analyticsadmin.googleapis.com/v1beta/accountSummaries")!
            var query = [URLQueryItem(name: "pageSize", value: "200")]
            if let pageToken { query.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            components.queryItems = query
            let root = object(try await jsonRequest(url: components.url!, headers: headers))
            guard root["accountSummaries"] != nil || root.isEmpty else {
                throw SiteIntegrationsAPIError.decoding("Google Analytics did not return account summaries.")
            }
            summaries.append(contentsOf: array(root["accountSummaries"]).map(object))
            let nextPageToken = nonEmpty(string(root["nextPageToken"]))
            if let nextPageToken, !seenTokens.insert(nextPageToken).inserted {
                warnings.append("Google Analytics repeated a pagination token, so account loading stopped safely.")
                summaryListIsPartial = true
                pageToken = nil
            } else {
                pageToken = nextPageToken
            }
        } while pageToken != nil

        let properties = summaries.flatMap { summary -> [GoogleAnalyticsPropertyDescriptor] in
            let accountName = string(summary["displayName"]) ?? string(summary["account"]) ?? "Google Analytics"
            return array(summary["propertySummaries"]).compactMap { value in
                let property = object(value)
                guard let propertyName = nonEmpty(string(property["property"])),
                      let propertyID = propertyName.split(separator: "/").last.map(String.init),
                      !propertyID.isEmpty,
                      propertyID.allSatisfy(\.isNumber) else { return nil }
                return GoogleAnalyticsPropertyDescriptor(
                    propertyName: propertyName,
                    propertyID: propertyID,
                    displayName: string(property["displayName"]) ?? "GA4 property \(propertyID)",
                    accountName: accountName,
                    propertyType: string(property["propertyType"]) ?? "PROPERTY_TYPE_ORDINARY",
                    canEdit: boolean(property["canEdit"]) ?? false
                )
            }
        }
        let detailResults = try await boundedConcurrentMap(properties) { property in
            try await self.fetchGoogleAnalyticsProperty(property, headers: headers)
        }
        let resources = detailResults.map(\.resource)
        warnings.append(contentsOf: detailResults.flatMap(\.warnings))
        let hasPartialMetrics = summaryListIsPartial
            || detailResults.contains(where: \.metricsArePartial)
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .googleAnalytics,
            resources: resources,
            metrics: markPartialMetrics(
                aggregateGoogleAnalyticsMetrics(resources),
                when: hasPartialMetrics,
                excluding: summaryListIsPartial ? [] : ["ga4.properties"]
            ),
            status: resources.isEmpty ? "No GA4 properties" : (hasPartialMetrics ? "Connected · Partial metrics" : "Connected"),
            warnings: warnings
        )
    }

    private func fetchGoogleAnalyticsProperty(
        _ property: GoogleAnalyticsPropertyDescriptor,
        headers: [String: String]
    ) async throws -> SiteIntegrationDetailResult {
        var metrics: [SiteIntegrationMetric] = []
        var warnings: [String] = []
        var metricsArePartial = false
        var attribution = GoogleAnalyticsStreamAttribution(
            url: nil,
            subtitle: property.accountName,
            metadata: ["metricsScope": "Property-wide"],
            isPropertyWide: true
        )

        do {
            attribution = Self.googleAnalyticsStreamAttribution(
                streams: try await fetchGoogleAnalyticsDataStreams(
                    propertyID: property.propertyID,
                    headers: headers
                ),
                accountName: property.accountName
            )
        } catch {
            try throwIfGoogleUnauthorized(error)
            warnings.append("\(property.displayName) data streams could not load: \(error.localizedDescription)")
        }

        do {
            metrics.append(contentsOf: try await fetchGoogleAnalyticsReport(
                propertyID: property.propertyID,
                headers: headers,
                resourceID: property.propertyName
            ))
        } catch {
            try throwIfGoogleUnauthorized(error)
            metricsArePartial = true
            warnings.append("\(property.displayName) analytics could not load: \(error.localizedDescription)")
        }

        do {
            if let realtime = try await fetchGoogleAnalyticsRealtimeUsers(
                propertyID: property.propertyID,
                headers: headers
            ) {
                metrics.append(metric(
                    key: "ga4.realtime_active_users",
                    label: "Active Now",
                    value: realtime,
                    unit: .count,
                    resourceID: property.propertyName
                ))
            }
        } catch {
            try throwIfGoogleUnauthorized(error)
            metricsArePartial = true
            warnings.append("\(property.displayName) realtime data could not load: \(error.localizedDescription)")
        }

        var metadata: [String: String] = [
            "property": property.propertyName,
            "propertyID": property.propertyID,
            "accountName": property.accountName,
            "propertyType": property.propertyType,
            "canEdit": String(property.canEdit),
            "range": "30 days",
        ]
        metadata.merge(attribution.metadata) { _, new in new }
        let status: String
        if metrics.isEmpty {
            status = "Property found"
        } else {
            status = attribution.isPropertyWide ? "Reporting · Property-wide" : "Reporting"
        }

        return SiteIntegrationDetailResult(
            resource: SiteIntegrationResource(
                id: property.propertyName,
                provider: .googleAnalytics,
                name: property.displayName,
                subtitle: attribution.subtitle,
                url: attribution.url,
                status: status,
                updatedAt: .now,
                metrics: metrics,
                metadata: metadata
            ),
            warnings: warnings,
            metricsArePartial: metricsArePartial
        )
    }

    private func fetchGoogleAnalyticsDataStreams(
        propertyID: String,
        headers: [String: String]
    ) async throws -> [GoogleAnalyticsDataStreamDescriptor] {
        var descriptors: [GoogleAnalyticsDataStreamDescriptor] = []
        var pageToken: String?
        var seenTokens = Set<String>()

        repeat {
            try Task.checkCancellation()
            var components = URLComponents(
                string: "https://analyticsadmin.googleapis.com/v1beta/properties/\(propertyID)/dataStreams"
            )!
            var queryItems = [URLQueryItem(name: "pageSize", value: "200")]
            if let pageToken { queryItems.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            components.queryItems = queryItems
            let root = object(try await jsonRequest(url: components.url!, headers: headers))
            guard root["dataStreams"] != nil || root.isEmpty else {
                throw SiteIntegrationsAPIError.decoding("Google Analytics did not return a data stream list.")
            }
            descriptors.append(contentsOf: array(root["dataStreams"]).compactMap { value in
                let stream = object(value)
                guard let name = nonEmpty(string(stream["name"])),
                      let type = nonEmpty(string(stream["type"])) else { return nil }
                let web = object(stream["webStreamData"])
                return GoogleAnalyticsDataStreamDescriptor(
                    name: name,
                    type: type,
                    displayName: nonEmpty(string(stream["displayName"])),
                    measurementID: nonEmpty(string(web["measurementId"])),
                    defaultURL: nonEmpty(string(web["defaultUri"])).flatMap(URL.init(string:))
                )
            })
            let nextPageToken = nonEmpty(string(root["nextPageToken"]))
            if let nextPageToken, !seenTokens.insert(nextPageToken).inserted {
                throw SiteIntegrationsAPIError.decoding(
                    "Google Analytics data stream pagination repeated a token."
                )
            }
            pageToken = nextPageToken
        } while pageToken != nil

        return descriptors
    }

    nonisolated static func googleAnalyticsStreamAttribution(
        streams: [GoogleAnalyticsDataStreamDescriptor],
        accountName: String
    ) -> GoogleAnalyticsStreamAttribution {
        let webStreams = streams.filter { $0.type == "WEB_DATA_STREAM" }
        let hasSingleWebOnly = streams.count == 1 && webStreams.count == 1
        var metadata: [String: String] = [
            "dataStreamCount": String(streams.count),
            "webStreamCount": String(webStreams.count),
            "metricsScope": hasSingleWebOnly ? "Single data stream" : "Property-wide",
        ]

        let streamNames = streams.map(\.name)
        if !streamNames.isEmpty { metadata["dataStreams"] = streamNames.joined(separator: ", ") }
        let measurementIDs = webStreams.compactMap(\.measurementID)
        if !measurementIDs.isEmpty { metadata["measurementIDs"] = measurementIDs.joined(separator: ", ") }
        let siteURLs = webStreams.compactMap { $0.defaultURL?.absoluteString }
        if !siteURLs.isEmpty { metadata["siteURLs"] = siteURLs.joined(separator: ", ") }

        if hasSingleWebOnly, let stream = webStreams.first {
            metadata["dataStream"] = stream.name
            if let measurementID = stream.measurementID { metadata["measurementID"] = measurementID }
            if let siteURL = stream.defaultURL?.absoluteString { metadata["siteURL"] = siteURL }
            return GoogleAnalyticsStreamAttribution(
                url: stream.defaultURL,
                subtitle: stream.defaultURL?.absoluteString ?? stream.displayName ?? accountName,
                metadata: metadata,
                isPropertyWide: false
            )
        }

        let subtitle: String
        if webStreams.count > 1, webStreams.count == streams.count {
            subtitle = "\(webStreams.count) web streams · \(accountName)"
        } else if !streams.isEmpty {
            subtitle = "\(streams.count) data streams · \(accountName)"
        } else {
            subtitle = accountName
        }
        return GoogleAnalyticsStreamAttribution(
            url: nil,
            subtitle: subtitle,
            metadata: metadata,
            isPropertyWide: true
        )
    }

    private func fetchGoogleAnalyticsReport(
        propertyID: String,
        headers: [String: String],
        resourceID: String
    ) async throws -> [SiteIntegrationMetric] {
        let names: [(String, String, SiteIntegrationMetricUnit)] = [
            ("activeUsers", "Active Users", .count),
            ("sessions", "Sessions", .count),
            ("screenPageViews", "Page Views", .count),
            ("engagementRate", "Engagement Rate", .percent),
            ("eventCount", "Events", .count),
            ("averageSessionDuration", "Avg. Session", .seconds),
        ]
        let root = object(try await jsonRequest(
            method: "POST",
            url: URL(string: "https://analyticsdata.googleapis.com/v1beta/properties/\(propertyID):runReport")!,
            body: try jsonData([
                "dateRanges": [["startDate": "30daysAgo", "endDate": "today"]],
                "metrics": names.map { ["name": $0.0] },
                "keepEmptyRows": true,
            ]),
            headers: headers
        ))
        guard let firstRow = array(root["rows"]).first else { return [] }
        let values = array(object(firstRow)["metricValues"])
        return names.indices.compactMap { index in
            guard values.indices.contains(index),
                  let value = number(object(values[index])["value"]) else { return nil }
            let definition = names[index]
            return metric(
                key: "ga4.\(definition.0)",
                label: definition.1,
                value: definition.2 == .percent ? value * 100 : value,
                unit: definition.2,
                resourceID: resourceID
            )
        }
    }

    private func fetchGoogleAnalyticsRealtimeUsers(
        propertyID: String,
        headers: [String: String]
    ) async throws -> Double? {
        let root = object(try await jsonRequest(
            method: "POST",
            url: URL(string: "https://analyticsdata.googleapis.com/v1beta/properties/\(propertyID):runRealtimeReport")!,
            body: try jsonData(["metrics": [["name": "activeUsers"]]]),
            headers: headers
        ))
        guard let row = array(root["rows"]).first.map(object),
              let value = array(row["metricValues"]).first else { return nil }
        return number(object(value)["value"])
    }

    private func aggregateGoogleAnalyticsMetrics(_ resources: [SiteIntegrationResource]) -> [SiteIntegrationMetric] {
        var metrics = [
            metric(key: "ga4.properties", label: "Properties", value: Double(resources.count), unit: .count)
        ]
        let definitions: [(String, String, SiteIntegrationMetricUnit)] = [
            ("ga4.activeUsers", "Active Users", .count),
            ("ga4.sessions", "Sessions", .count),
            ("ga4.screenPageViews", "Page Views", .count),
            ("ga4.eventCount", "Events", .count),
            ("ga4.realtime_active_users", "Active Now", .count),
        ]
        for definition in definitions {
            let matching = resources.flatMap(\.metrics).filter { $0.key == definition.0 }
            if !matching.isEmpty {
                metrics.append(metric(
                    key: definition.0,
                    label: definition.1,
                    value: matching.reduce(0) { $0 + $1.value },
                    unit: definition.2
                ))
            }
        }
        let weightedEngagement = resources.compactMap { resource -> (rate: Double, sessions: Double)? in
            guard let rate = resource.metrics.first(where: { $0.key == "ga4.engagementRate" })?.value,
                  let sessions = resource.metrics.first(where: { $0.key == "ga4.sessions" })?.value,
                  sessions > 0 else { return nil }
            return (rate, sessions)
        }
        if !weightedEngagement.isEmpty {
            let sessionTotal = weightedEngagement.reduce(0) { $0 + $1.sessions }
            metrics.append(metric(
                key: "ga4.engagementRate",
                label: "Engagement Rate",
                value: weightedEngagement.reduce(0) { $0 + $1.rate * $1.sessions } / sessionTotal,
                unit: .percent
            ))
        }
        return metrics
    }

    private func googleAuthorizationHeaders(provider: SiteIntegrationProvider) throws -> [String: String] {
        guard let value = nonEmpty(account.credential) else {
            let configuration = provider == .googleAnalytics
                ? Self.googleAnalyticsOAuth
                : Self.googleSearchConsoleOAuth
            throw SiteIntegrationsAPIError.oauthNotConfigured(provider: provider, scopes: configuration.scopes)
        }
        let credential: GoogleOAuthCredential
        do {
            credential = try GoogleOAuthCredential.fromKeychainValue(value)
        } catch {
            throw SiteIntegrationsAPIError.invalidConfiguration(error.localizedDescription)
        }
        guard !credential.needsRefresh else {
            throw SiteIntegrationsAPIError.invalidConfiguration(
                "The Google access token needs refresh before this request."
            )
        }
        return ["Authorization": "\(credential.tokenType) \(credential.accessToken)"]
    }

    private func googleDateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "America/Los_Angeles")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    // MARK: - PageSpeed Insights and Chrome UX Report

    private func fetchPageSpeedSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let key = try requiredCredential(label: "Google API key")
        let siteURL = try validatedSiteURL(try requiredMetadata("siteURL", label: "site URL"))
        let resourceID = stableSiteResourceID(siteURL.absoluteString)
        var warnings: [String] = []
        var metrics = try await fetchPageSpeedMetrics(
            siteURL: siteURL,
            apiKey: key,
            strategy: "mobile",
            resourceID: resourceID
        )
        do {
            metrics.append(contentsOf: try await fetchPageSpeedMetrics(
                siteURL: siteURL,
                apiKey: key,
                strategy: "desktop",
                resourceID: resourceID
            ))
        } catch {
            try throwIfCancellation(error)
            warnings.append("Desktop PageSpeed data is unavailable: \(error.localizedDescription)")
        }
        do {
            metrics.append(contentsOf: try await fetchCrUXMetrics(
                siteURL: siteURL,
                apiKey: key,
                resourceID: resourceID
            ))
        } catch {
            try throwIfCancellation(error)
            warnings.append("Chrome UX field data is unavailable: \(error.localizedDescription)")
        }

        let performanceScores = metrics
            .filter { $0.key == "pagespeed.mobile.performance" || $0.key == "pagespeed.desktop.performance" }
            .map(\.value)
        let performance = performanceScores.min()
        let status: String
        switch performance {
        case .some(let value) where value >= 90: status = "Good"
        case .some(let value) where value >= 50: status = "Needs work"
        case .some: status = "Poor"
        case .none: status = "Audited"
        }
        let name = siteURL.host ?? siteURL.absoluteString
        let resource = SiteIntegrationResource(
            id: resourceID,
            provider: .pageSpeed,
            name: name,
            subtitle: siteURL.absoluteString,
            url: siteURL,
            status: status,
            updatedAt: .now,
            metrics: metrics,
            metadata: ["strategy": "mobile + desktop", "source": "PageSpeed Insights + CrUX"]
        )
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .pageSpeed,
            resources: [resource],
            metrics: metrics.map { withoutResourceID($0) },
            status: status,
            warnings: warnings
        )
    }

    private func fetchPageSpeedMetrics(
        siteURL: URL,
        apiKey: String,
        strategy: String,
        resourceID: String
    ) async throws -> [SiteIntegrationMetric] {
        var components = URLComponents(string: "https://www.googleapis.com/pagespeedonline/v5/runPagespeed")!
        components.queryItems = [
            URLQueryItem(name: "url", value: siteURL.absoluteString),
            URLQueryItem(name: "strategy", value: strategy),
            URLQueryItem(name: "category", value: "performance"),
            URLQueryItem(name: "category", value: "accessibility"),
            URLQueryItem(name: "category", value: "best-practices"),
            URLQueryItem(name: "category", value: "seo"),
            URLQueryItem(name: "key", value: apiKey)
        ]
        guard let pageSpeedURL = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The PageSpeed request URL is invalid.")
        }
        let root = object(try await jsonRequest(url: pageSpeedURL))
        let lighthouse = object(root["lighthouseResult"])
        let categories = object(lighthouse["categories"])
        guard !lighthouse.isEmpty, !categories.isEmpty else {
            throw SiteIntegrationsAPIError.decoding(
                "PageSpeed Insights did not return a Lighthouse report."
            )
        }
        let audits = object(lighthouse["audits"])
        var metrics: [SiteIntegrationMetric] = []
        let strategyLabel = strategy.capitalized

        let categoryDefinitions = [
            ("performance", "Performance"),
            ("accessibility", "Accessibility"),
            ("best-practices", "Best Practices"),
            ("seo", "SEO")
        ]
        for (key, label) in categoryDefinitions {
            let category = object(categories[key])
            if let score = number(category["score"]) {
                metrics.append(metric(
                    key: "pagespeed.\(strategy).\(key)",
                    label: "\(strategyLabel) \(label)",
                    value: score * 100,
                    unit: .score,
                    formattedValue: String(format: "%.0f", score * 100),
                    resourceID: resourceID
                ))
            }
        }

        let auditDefinitions: [(String, String, SiteIntegrationMetricUnit)] = [
            ("largest-contentful-paint", "LCP (Lab)", .milliseconds),
            ("interaction-to-next-paint", "INP (Lab)", .milliseconds),
            ("cumulative-layout-shift", "CLS (Lab)", .ratio),
            ("first-contentful-paint", "FCP (Lab)", .milliseconds),
            ("server-response-time", "Server Response", .milliseconds),
            ("total-blocking-time", "Total Blocking Time", .milliseconds),
            ("speed-index", "Speed Index", .milliseconds)
        ]
        for (key, label, unit) in auditDefinitions {
            let audit = object(audits[key])
            guard let value = number(audit["numericValue"]) else { continue }
            metrics.append(metric(
                key: "pagespeed.\(strategy).\(key)",
                label: "\(strategyLabel) \(label)",
                value: value,
                unit: unit,
                formattedValue: string(audit["displayValue"]),
                resourceID: resourceID
            ))
        }

        return metrics
    }

    private func fetchCrUXMetrics(
        siteURL: URL,
        apiKey: String,
        resourceID: String
    ) async throws -> [SiteIntegrationMetric] {
        guard var components = URLComponents(string: "https://chromeuxreport.googleapis.com/v1/records:queryRecord") else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The Chrome UX request URL is invalid.")
        }
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter a valid HTTPS site URL for Chrome UX data.")
        }
        let root = object(try await jsonRequest(
            method: "POST",
            url: url,
            body: try jsonData(["url": siteURL.absoluteString])
        ))
        let record = object(root["record"])
        let values = object(record["metrics"])
        guard !record.isEmpty, !values.isEmpty else {
            throw SiteIntegrationsAPIError.decoding(
                "Chrome UX Report did not return field metrics for this page."
            )
        }
        let definitions: [(String, String, SiteIntegrationMetricUnit)] = [
            ("largest_contentful_paint", "LCP (Page field p75)", .milliseconds),
            ("interaction_to_next_paint", "INP (Page field p75)", .milliseconds),
            ("cumulative_layout_shift", "CLS (Page field p75)", .ratio),
            ("first_contentful_paint", "FCP (Page field p75)", .milliseconds),
            ("experimental_time_to_first_byte", "TTFB (Page field p75)", .milliseconds)
        ]
        return definitions.compactMap { key, label, unit in
            let percentile = object(object(values[key])["percentiles"])
            guard let value = number(percentile["p75"]) else { return nil }
            return metric(
                key: "crux.\(key)",
                label: label,
                value: value,
                unit: unit,
                resourceID: resourceID
            )
        }
    }

    // MARK: - Bing Webmaster

    private func fetchBingSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let key = try requiredCredential(label: "Bing Webmaster API key")
        var components = URLComponents(string: "https://ssl.bing.com/webmaster/api.svc/json/GetUserSites")!
        components.queryItems = [URLQueryItem(name: "apikey", value: key)]
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The Bing Webmaster request URL is invalid.")
        }
        let root = object(try await jsonRequest(url: url))
        guard root["d"] != nil || root["sites"] != nil else {
            throw SiteIntegrationsAPIError.decoding("Bing Webmaster did not return a site list.")
        }
        let descriptors = array(root["d"] ?? root["sites"]).compactMap { item -> BingSiteDescriptor? in
            let site = object(item)
            guard let value = nonEmpty(string(site["Url"] ?? site["url"])) else { return nil }
            return BingSiteDescriptor(
                value: value,
                url: URL(string: value),
                isVerified: boolean(site["IsVerified"] ?? site["isVerified"]) ?? false,
                resourceID: stableSiteResourceID(value)
            )
        }
        let detailResults = try await boundedConcurrentMap(descriptors) { descriptor in
            try await self.fetchBingSite(descriptor, apiKey: key)
        }
        let resources = detailResults.map(\.resource)
        let warnings = detailResults.flatMap(\.warnings)
        let verifiedCount = resources.filter { $0.status == "Verified" }.count
        let hasPartialMetrics = detailResults.contains(where: \.metricsArePartial)
        var metrics = [
            metric(key: "bing.sites", label: "Sites", value: Double(resources.count), unit: .count),
            metric(key: "bing.verified", label: "Verified", value: Double(verifiedCount), unit: .count)
        ]
        for definition in [
            ("bing.clicks", "Clicks"),
            ("bing.impressions", "Impressions"),
            ("bing.crawled_pages", "Crawled Pages"),
            ("bing.crawl_errors", "Crawl Errors"),
            ("bing.in_index", "In Index"),
        ] {
            let matching = resources.flatMap(\.metrics).filter { $0.key == definition.0 }
            if !matching.isEmpty {
                metrics.append(metric(
                    key: definition.0,
                    label: definition.1,
                    value: matching.reduce(0) { $0 + $1.value },
                    unit: .count
                ))
            }
        }
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .bingWebmaster,
            resources: resources,
            metrics: markPartialMetrics(
                metrics,
                when: hasPartialMetrics,
                excluding: ["bing.sites", "bing.verified"]
            ),
            status: resources.isEmpty ? "No sites" : (hasPartialMetrics ? "Connected · Partial metrics" : "Connected"),
            warnings: warnings
        )
    }

    private func fetchBingSite(
        _ descriptor: BingSiteDescriptor,
        apiKey: String
    ) async throws -> SiteIntegrationDetailResult {
        var resourceMetrics: [SiteIntegrationMetric] = []
        var metadata = ["verified": String(descriptor.isVerified)]
        var warnings: [String] = []
        var metricsArePartial = false

        if descriptor.isVerified {
            do {
                resourceMetrics.append(contentsOf: try await fetchBingTrafficMetrics(
                    siteURL: descriptor.value,
                    apiKey: apiKey,
                    resourceID: descriptor.resourceID
                ))
            } catch {
                try throwIfCancellation(error)
                metricsArePartial = true
                warnings.append("\(descriptor.value) Bing traffic could not load: \(error.localizedDescription)")
            }
            do {
                let crawl = try await fetchBingCrawlMetrics(
                    siteURL: descriptor.value,
                    apiKey: apiKey,
                    resourceID: descriptor.resourceID
                )
                resourceMetrics.append(contentsOf: crawl.metrics)
                metadata.merge(crawl.metadata) { _, new in new }
            } catch {
                try throwIfCancellation(error)
                metricsArePartial = true
                warnings.append("\(descriptor.value) Bing crawl data could not load: \(error.localizedDescription)")
            }
        }

        return SiteIntegrationDetailResult(
            resource: SiteIntegrationResource(
                id: descriptor.resourceID,
                provider: .bingWebmaster,
                name: descriptor.url?.host ?? descriptor.value,
                subtitle: descriptor.value,
                url: descriptor.url,
                status: descriptor.isVerified ? "Verified" : "Unverified",
                metrics: resourceMetrics,
                metadata: metadata
            ),
            warnings: warnings,
            metricsArePartial: metricsArePartial
        )
    }

    private func fetchBingTrafficMetrics(
        siteURL: String,
        apiKey: String,
        resourceID: String
    ) async throws -> [SiteIntegrationMetric] {
        let rows = try await bingRows(method: "GetRankAndTrafficStats", siteURL: siteURL, apiKey: apiKey)
        let cutoff = Date.now.addingTimeInterval(-30 * 86_400)
        let datedRows = rows.filter { row in
            guard let value = bingDate(row["Date"] ?? row["date"]) else { return true }
            return value >= cutoff
        }
        let clicks = datedRows.reduce(0) { $0 + (number($1["Clicks"] ?? $1["clicks"]) ?? 0) }
        let impressions = datedRows.reduce(0) { $0 + (number($1["Impressions"] ?? $1["impressions"]) ?? 0) }
        return [
            metric(key: "bing.clicks", label: "Clicks · 30d", value: clicks, unit: .count, resourceID: resourceID),
            metric(
                key: "bing.impressions",
                label: "Impressions · 30d",
                value: impressions,
                unit: .count,
                resourceID: resourceID
            ),
            metric(
                key: "bing.ctr",
                label: "CTR · 30d",
                value: impressions > 0 ? clicks / impressions * 100 : 0,
                unit: .percent,
                resourceID: resourceID
            ),
        ]
    }

    private func fetchBingCrawlMetrics(
        siteURL: String,
        apiKey: String,
        resourceID: String
    ) async throws -> (metrics: [SiteIntegrationMetric], metadata: [String: String]) {
        let rows = try await bingRows(method: "GetCrawlStats", siteURL: siteURL, apiKey: apiKey)
        guard let latest = rows.max(by: {
            (bingDate($0["Date"] ?? $0["date"]) ?? .distantPast)
                < (bingDate($1["Date"] ?? $1["date"]) ?? .distantPast)
        }) else { return ([], [:]) }
        let definitions = [
            ("CrawledPages", "bing.crawled_pages", "Crawled Pages"),
            ("CrawlErrors", "bing.crawl_errors", "Crawl Errors"),
            ("InIndex", "bing.in_index", "In Index"),
            ("InLinks", "bing.in_links", "Inbound Links"),
            ("Code4xx", "bing.code_4xx", "4xx Responses"),
            ("Code5xx", "bing.code_5xx", "5xx Responses"),
            ("BlockedByRobotsTxt", "bing.robots_blocked", "Blocked by robots.txt"),
        ]
        let metrics = definitions.compactMap { sourceKey, key, label -> SiteIntegrationMetric? in
            guard let value = number(latest[sourceKey]) else { return nil }
            return metric(key: key, label: label, value: value, unit: .count, resourceID: resourceID)
        }
        var metadata: [String: String] = [:]
        if let date = bingDate(latest["Date"] ?? latest["date"]) {
            metadata["crawlStatsAt"] = ISO8601DateFormatter().string(from: date)
        }
        return (metrics, metadata)
    }

    private func bingRows(method: String, siteURL: String, apiKey: String) async throws -> [[String: Any]] {
        var components = URLComponents(string: "https://ssl.bing.com/webmaster/api.svc/json/\(method)")!
        components.queryItems = [
            URLQueryItem(name: "siteUrl", value: siteURL),
            URLQueryItem(name: "apikey", value: apiKey),
        ]
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The Bing Webmaster request URL is invalid.")
        }
        let root = object(try await jsonRequest(url: url))
        guard root["d"] != nil else {
            throw SiteIntegrationsAPIError.decoding("Bing Webmaster returned an unexpected \(method) response.")
        }
        return array(root["d"]).map(object)
    }

    private func bingDate(_ value: Any?) -> Date? {
        guard let raw = string(value) else { return nil }
        if raw.hasPrefix("/Date(") {
            let payload = raw.dropFirst(6)
            let millisecondText = payload.prefix(while: { $0.isNumber })
            if let milliseconds = Double(millisecondText), milliseconds.isFinite {
                return Date(timeIntervalSince1970: milliseconds / 1_000)
            }
        }
        return date(raw)
    }

    // MARK: - Microsoft Clarity

    private func fetchClaritySnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let token = try requiredCredential(label: "Clarity API token")
        let requestedDays = Int(account.metadata["days"] ?? "3") ?? 3
        let days = min(max(requestedDays, 1), 3)
        var components = URLComponents(string: "https://www.clarity.ms/export-data/api/v1/project-live-insights")!
        components.queryItems = [URLQueryItem(name: "numOfDays", value: String(days))]
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The Clarity request URL is invalid.")
        }
        let value = try await jsonRequest(
            url: url,
            headers: ["Authorization": "Bearer \(token)"]
        )
        guard let rows = value as? [Any] else {
            throw SiteIntegrationsAPIError.decoding(
                "Microsoft Clarity did not return a live-insights list."
            )
        }
        var accumulated: [String: (label: String, value: Double, unit: SiteIntegrationMetricUnit, samples: Int)] = [:]
        var discoveredURLs = Set<String>()
        var discoveredOrigins: [URL: Int] = [:]

        for item in rows {
            let group = object(item)
            let metricName = string(group["metricName"]) ?? "Insight"
            for rawInformation in array(group["information"]) {
                let information = object(rawInformation)
                if let site = string(information["URL"] ?? information["url"]), !site.isEmpty {
                    discoveredURLs.insert(site)
                    if let url = URL(string: site), let origin = Self.originURL(from: url) {
                        discoveredOrigins[origin, default: 0] += 1
                    }
                }
                for (field, rawValue) in information {
                    guard field.lowercased() != "url", let value = number(rawValue) else { continue }
                    let key = "clarity.\(slug(metricName)).\(slug(field))"
                    let label = humanized(field)
                    let unit = inferredUnit(for: field)
                    let oldValue = accumulated[key]?.value ?? 0
                    let samples = (accumulated[key]?.samples ?? 0) + 1
                    accumulated[key] = (label, oldValue + value, unit, samples)
                }
            }
        }
        let metrics = accumulated.keys.sorted().compactMap { key -> SiteIntegrationMetric? in
            guard let value = accumulated[key] else { return nil }
            let aggregate = value.unit == .count ? value.value : value.value / Double(value.samples)
            return metric(key: key, label: value.label, value: aggregate, unit: value.unit)
        }
        let configuredSite = nonEmpty(account.metadata["siteURL"])
        let projectName = nonEmpty(account.metadata["projectName"]) ?? "Microsoft Clarity"
        let configuredURL = configuredSite
            .flatMap(URL.init(string:))
            .flatMap { Self.originURL(from: $0) }
        let discoveredURL = discoveredOrigins.max { left, right in
            if left.value == right.value {
                return left.key.absoluteString > right.key.absoluteString
            }
            return left.value < right.value
        }?.key
        let resourceURL = configuredURL ?? discoveredURL
        let resourceID = resourceURL
            .map { stableSiteResourceID($0.absoluteString) }
            ?? projectName.lowercased()
        var resourceMetadata = ["days": String(days)]
        if !discoveredURLs.isEmpty { resourceMetadata["reportedURLs"] = String(discoveredURLs.count) }
        if !discoveredOrigins.isEmpty { resourceMetadata["reportedOrigins"] = String(discoveredOrigins.count) }
        if let resourceURL { resourceMetadata["siteURL"] = resourceURL.absoluteString }
        let resource = SiteIntegrationResource(
            id: resourceID,
            provider: .clarity,
            name: projectName,
            subtitle: resourceURL?.absoluteString ?? "Last \(days * 24) hours",
            url: resourceURL,
            status: "Connected",
            updatedAt: .now,
            metrics: metrics.map { withResourceID($0, resourceID: resourceID) },
            metadata: resourceMetadata
        )
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .clarity,
            resources: [resource],
            metrics: metrics,
            status: "Live insights · \(days)d"
        )
    }

    // MARK: - Plausible

    private func fetchPlausibleSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let token = try requiredCredential(label: "Plausible Stats API key")
        let siteID = try requiredMetadata("siteID", label: "Plausible site ID")
        let requestedRange = nonEmpty(account.metadata["dateRange"]) ?? "30d"
        let metricKeys = ["visitors", "visits", "pageviews", "views_per_visit", "bounce_rate", "visit_duration"]
        let payload: [String: Any] = [
            "site_id": siteID,
            "metrics": metricKeys,
            "date_range": requestedRange,
            "filters": []
        ]
        let root = object(try await jsonRequest(
            method: "POST",
            url: URL(string: "https://plausible.io/api/v2/query")!,
            body: try jsonData(payload),
            headers: ["Authorization": "Bearer \(token)"]
        ))
        guard let rawResults = root["results"] as? [Any] else {
            throw SiteIntegrationsAPIError.decoding(
                "Plausible did not return a query result list."
            )
        }
        let first = object(rawResults.first)
        let values = array(first["metrics"])
        let definitions: [(String, String, SiteIntegrationMetricUnit)] = [
            ("visitors", "Visitors", .count),
            ("visits", "Visits", .count),
            ("pageviews", "Page Views", .count),
            ("views_per_visit", "Views / Visit", .ratio),
            ("bounce_rate", "Bounce Rate", .percent),
            ("visit_duration", "Visit Duration", .seconds)
        ]
        let resourceID = stableSiteResourceID(siteID)
        var metrics: [SiteIntegrationMetric] = []
        for (index, definition) in definitions.enumerated() where values.indices.contains(index) {
            guard let value = number(values[index]) else { continue }
            metrics.append(metric(
                key: "plausible.\(definition.0)",
                label: definition.1,
                value: value,
                unit: definition.2,
                resourceID: resourceID
            ))
        }
        let siteURL = URL(string: siteID.contains("://") ? siteID : "https://\(siteID)")
        let resource = SiteIntegrationResource(
            id: resourceID,
            provider: .plausible,
            name: siteID,
            subtitle: requestedRange,
            url: siteURL,
            status: "Connected",
            updatedAt: .now,
            metrics: metrics,
            metadata: ["dateRange": requestedRange]
        )
        var warnings: [String] = []
        let meta = object(root["meta"])
        if let warning = string(meta["imports_warning"]), !warning.isEmpty { warnings.append(warning) }
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .plausible,
            resources: [resource],
            metrics: metrics.map { withoutResourceID($0) },
            status: "Connected",
            warnings: warnings
        )
    }

    // MARK: - Umami

    private func fetchUmamiSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let context = try umamiRequestContext()
        let apiBase = context.base
        let headers = context.headers

        var websites: [[String: Any]] = []
        var page = 1
        var expectedCount: Int?
        var warnings: [String] = []
        var seenPageSignatures = Set<String>()
        var seenWebsiteIDs = Set<String>()
        var siteListIsPartial = false
        repeat {
            try Task.checkCancellation()
            let url = try endpoint(
                base: apiBase,
                path: "websites",
                queryItems: [
                    URLQueryItem(name: "page", value: String(page)),
                    URLQueryItem(name: "pageSize", value: "100"),
                    URLQueryItem(name: "includeTeams", value: "true")
                ]
            )
            let root = object(try await jsonRequest(url: url, headers: headers))
            guard root["data"] is [Any] || root["websites"] is [Any] else {
                throw SiteIntegrationsAPIError.decoding(
                    "Umami did not return a website list."
                )
            }
            let pageItems = array(root["data"] ?? root["websites"]).map(object)
            let pageIDs = pageItems.compactMap { nonEmpty(string($0["id"])) }
            let pageSignature = pageIDs.sorted().joined(separator: "|")
            if !pageSignature.isEmpty, !seenPageSignatures.insert(pageSignature).inserted {
                warnings.append("Umami repeated a results page, so site loading stopped safely.")
                siteListIsPartial = true
                break
            }
            let uniquePageItems = pageItems.filter { website in
                guard let id = nonEmpty(string(website["id"])) else { return false }
                return seenWebsiteIDs.insert(id).inserted
            }
            websites.append(contentsOf: uniquePageItems)
            expectedCount = int(root["count"]) ?? expectedCount
            page += 1
            if !pageItems.isEmpty, uniquePageItems.isEmpty {
                warnings.append("Umami returned no new sites on the next page, so site loading stopped safely.")
                siteListIsPartial = true
                break
            }
            if pageItems.isEmpty
                || pageItems.count < 100
                || expectedCount.map({ seenWebsiteIDs.count >= $0 }) == true {
                break
            }
        } while true

        let endAt = Int(Date.now.timeIntervalSince1970 * 1_000)
        let startAt = Int(Date.now.addingTimeInterval(-30 * 86_400).timeIntervalSince1970 * 1_000)
        let descriptors = websites.compactMap { website -> UmamiSiteDescriptor? in
            guard let id = nonEmpty(string(website["id"])) else { return nil }
            let domain = nonEmpty(string(website["domain"]))
            return UmamiSiteDescriptor(
                id: id,
                name: string(website["name"]) ?? domain ?? "Umami site",
                domain: domain,
                url: domain.flatMap { URL(string: $0.contains("://") ? $0 : "https://\($0)") },
                updatedAt: date(website["updatedAt"] ?? website["createdAt"])
            )
        }
        let detailResults = try await boundedConcurrentMap(descriptors) { descriptor in
            try await self.fetchUmamiSite(
                descriptor,
                apiBase: apiBase,
                headers: headers,
                startAt: startAt,
                endAt: endAt
            )
        }
        let resources = detailResults.map(\.resource)
        warnings.append(contentsOf: detailResults.flatMap(\.warnings))
        let hasPartialMetrics = siteListIsPartial
            || detailResults.contains(where: \.metricsArePartial)
        let aggregate = markPartialMetrics(
            aggregateUmamiMetrics(resources: resources),
            when: hasPartialMetrics
        )
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .umami,
            resources: resources,
            metrics: aggregate,
            status: resources.isEmpty ? "No sites" : (hasPartialMetrics ? "Connected · Partial metrics" : "Connected"),
            warnings: warnings
        )
    }

    private func fetchUmamiSite(
        _ descriptor: UmamiSiteDescriptor,
        apiBase: URL,
        headers: [String: String],
        startAt: Int,
        endAt: Int
    ) async throws -> SiteIntegrationDetailResult {
        var resourceMetrics: [SiteIntegrationMetric] = []
        var warnings: [String] = []
        var metricsArePartial = false
        do {
            let statsURL = try endpoint(
                base: apiBase,
                path: "websites/\(try Self.pathComponent(descriptor.id))/stats",
                queryItems: [
                    URLQueryItem(name: "startAt", value: String(startAt)),
                    URLQueryItem(name: "endAt", value: String(endAt))
                ]
            )
            let stats = object(try await jsonRequest(url: statsURL, headers: headers))
            resourceMetrics = normalizeUmamiStats(stats, resourceID: descriptor.id)
        } catch {
            try throwIfCancellation(error)
            metricsArePartial = true
            warnings.append("\(descriptor.name) stats could not load: \(error.localizedDescription)")
        }

        return SiteIntegrationDetailResult(
            resource: SiteIntegrationResource(
                id: descriptor.id,
                provider: .umami,
                name: descriptor.name,
                subtitle: descriptor.domain,
                url: descriptor.url,
                status: "Connected",
                updatedAt: descriptor.updatedAt,
                metrics: resourceMetrics,
                metadata: ["domain": descriptor.domain ?? ""]
            ),
            warnings: warnings,
            metricsArePartial: metricsArePartial
        )
    }

    private func normalizeUmamiStats(_ stats: [String: Any], resourceID: String) -> [SiteIntegrationMetric] {
        let definitions: [(String, String, SiteIntegrationMetricUnit)] = [
            ("pageviews", "Page Views", .count),
            ("visitors", "Visitors", .count),
            ("visits", "Visits", .count),
            ("bounces", "Bounces", .count),
            ("totaltime", "Time on Site", .seconds)
        ]
        return definitions.compactMap { key, label, unit in
            guard let value = number(stats[key]) else { return nil }
            return metric(
                key: "umami.\(key)",
                label: label,
                value: value,
                unit: unit,
                resourceID: resourceID
            )
        }
    }

    private func aggregateUmamiMetrics(resources: [SiteIntegrationResource]) -> [SiteIntegrationMetric] {
        let keys = ["pageviews", "visitors", "visits", "bounces", "totaltime"]
        let labels = ["Page Views", "Visitors", "Visits", "Bounces", "Time on Site"]
        let units: [SiteIntegrationMetricUnit] = [.count, .count, .count, .count, .seconds]
        return keys.indices.compactMap { index in
            let key = "umami.\(keys[index])"
            let matching = resources.flatMap(\.metrics).filter { $0.key == key }
            guard !matching.isEmpty else { return nil }
            return metric(
                key: key,
                label: labels[index],
                value: matching.reduce(0) { $0 + $1.value },
                unit: units[index]
            )
        }
    }

    private func umamiAPIBaseURL() throws -> URL {
        let mode = nonEmpty(account.metadata["authMode"]) ?? "cloud"
        if mode == "cloud" {
            return URL(string: "https://api.umami.is/v1/")!
        }
        guard mode == "selfHosted" else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Umami authMode must be cloud or selfHosted.")
        }
        let rawBase = try requiredMetadata("baseURL", label: "self-hosted Umami URL")
        let base = try Self.normalizedHTTPSBaseURL(rawBase)
        guard base.host?.lowercased() != "api.umami.is" else {
            throw SiteIntegrationsAPIError.invalidConfiguration(
                "Enter your own self-hosted Umami URL before using a bearer token."
            )
        }
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        var path = components.path
        while path.hasSuffix("/") { path.removeLast() }
        if !path.hasSuffix("/api") { path += "/api" }
        components.path = path + "/"
        guard let result = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The self-hosted Umami URL is invalid.")
        }
        return result
    }

    private func umamiRequestContext() throws -> (base: URL, headers: [String: String]) {
        let credential = try requiredCredential(label: "Umami credential")
        let base = try umamiAPIBaseURL()
        let mode = nonEmpty(account.metadata["authMode"]) ?? "cloud"
        let headers: [String: String]
        if mode == "selfHosted" {
            headers = ["Authorization": "Bearer \(credential)"]
        } else {
            headers = ["x-umami-api-key": credential]
        }
        return (base, headers)
    }

    // MARK: - UptimeRobot

    enum UptimeRobotPaginationAction: Equatable {
        case loadNextPage
        case complete
        case noProgress
    }

    nonisolated static func uptimeRobotPaginationAction(
        pageItemCount: Int,
        newUniqueMonitorCount: Int,
        loadedUniqueMonitorCount: Int,
        reportedTotal: Int?,
        pageSize: Int
    ) -> UptimeRobotPaginationAction {
        if pageItemCount == 0 {
            return .complete
        }
        if newUniqueMonitorCount == 0 {
            return .noProgress
        }
        if reportedTotal.map({ loadedUniqueMonitorCount >= $0 }) == true
            || (reportedTotal == nil && pageItemCount < pageSize) {
            return .complete
        }
        return .loadNextPage
    }

    private func fetchUptimeRobotSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let apiKey = try requiredCredential(label: "UptimeRobot read-only API key")
        let endpoint = URL(string: "https://api.uptimerobot.com/v2/getMonitors")!
        let pageSize = 50
        var offset = 0
        var monitors: [[String: Any]] = []
        var seenMonitorIDs = Set<String>()
        var warnings: [String] = []
        var pageCount = 0
        var accountMetricsArePartial = false
        paginationLoop: while true {
            try Task.checkCancellation()
            let form = formData([
                URLQueryItem(name: "api_key", value: apiKey),
                URLQueryItem(name: "format", value: "json"),
                URLQueryItem(name: "logs", value: "1"),
                URLQueryItem(name: "logs_limit", value: "1"),
                URLQueryItem(name: "response_times", value: "1"),
                URLQueryItem(name: "custom_uptime_ratios", value: "1-7-30"),
                URLQueryItem(name: "limit", value: String(pageSize)),
                URLQueryItem(name: "offset", value: String(offset))
            ])
            let root = object(try await jsonRequest(
                method: "POST",
                url: endpoint,
                body: form,
                headers: [:],
                contentType: "application/x-www-form-urlencoded"
            ))
            if string(root["stat"])?.lowercased() == "fail" {
                let error = object(root["error"])
                throw SiteIntegrationsAPIError.decoding(string(error["message"]) ?? "UptimeRobot rejected the request.")
            }
            guard root["monitors"] is [Any] else {
                throw SiteIntegrationsAPIError.decoding(
                    "UptimeRobot did not return a monitor list."
                )
            }
            let page = array(root["monitors"]).map(object)
            var newMonitorCount = 0
            for monitor in page {
                guard let id = string(monitor["id"]) else { continue }
                if seenMonitorIDs.insert(id).inserted {
                    monitors.append(monitor)
                    newMonitorCount += 1
                }
            }
            let pagination = object(root["pagination"])
            let total = int(pagination["total"])
            offset += page.count
            pageCount += 1
            switch Self.uptimeRobotPaginationAction(
                pageItemCount: page.count,
                newUniqueMonitorCount: newMonitorCount,
                loadedUniqueMonitorCount: seenMonitorIDs.count,
                reportedTotal: total,
                pageSize: pageSize
            ) {
            case .complete:
                break paginationLoop
            case .noProgress:
                warnings.append("UptimeRobot repeated a results page, so loading stopped to avoid an endless request loop.")
                accountMetricsArePartial = true
                break paginationLoop
            case .loadNextPage:
                if pageCount >= 200 {
                    warnings.append("UptimeRobot loading stopped after 10,000 monitors to protect the device.")
                    accountMetricsArePartial = true
                    break paginationLoop
                }
            }
        }

        let resources = monitors.compactMap { monitor -> SiteIntegrationResource? in
            guard let id = string(monitor["id"]) else { return nil }
            let name = string(monitor["friendly_name"]) ?? "Monitor \(id)"
            let urlValue = string(monitor["url"])
            let statusCode = int(monitor["status"])
            let status = uptimeRobotStatus(statusCode)
            var metrics: [SiteIntegrationMetric] = []
            if let uptimeValues = string(monitor["custom_uptime_ratio"] ?? monitor["custom_uptime_ratios"]) {
                let parts = uptimeValues.split(separator: "-").compactMap { value -> Double? in
                    guard let number = Double(value), number.isFinite else { return nil }
                    return number
                }
                let labels = ["24h Uptime", "7d Uptime", "30d Uptime"]
                let keys = ["1d", "7d", "30d"]
                for index in parts.indices where labels.indices.contains(index) {
                    metrics.append(metric(
                        key: "uptimerobot.uptime.\(keys[index])",
                        label: labels[index],
                        value: parts[index],
                        unit: .percent,
                        resourceID: id
                    ))
                }
            }
            if let responseTime = number(monitor["average_response_time"]) {
                metrics.append(metric(
                    key: "uptimerobot.response_time",
                    label: "Response Time",
                    value: responseTime,
                    unit: .milliseconds,
                    resourceID: id
                ))
            }
            return SiteIntegrationResource(
                id: id,
                provider: .uptimeRobot,
                name: name,
                subtitle: urlValue,
                url: urlValue.flatMap(URL.init(string:)),
                status: status,
                updatedAt: latestUptimeRobotLogDate(array(monitor["logs"])),
                metrics: metrics,
                metadata: ["statusCode": statusCode.map(String.init) ?? ""]
            )
        }
        let up = resources.filter { $0.status == "Up" }.count
        let down = resources.filter { $0.status == "Down" || $0.status == "Seems down" }.count
        let paused = resources.filter { $0.status == "Paused" }.count
        let metrics = markPartialMetrics([
            metric(key: "uptimerobot.monitors", label: "Monitors", value: Double(resources.count), unit: .count),
            metric(key: "uptimerobot.up", label: "Up", value: Double(up), unit: .count),
            metric(key: "uptimerobot.down", label: "Down", value: Double(down), unit: .count),
            metric(key: "uptimerobot.paused", label: "Paused", value: Double(paused), unit: .count)
        ], when: accountMetricsArePartial)
        let status: String
        if resources.isEmpty {
            status = "No monitors"
        } else if down > 0 {
            status = "\(down) down"
        } else if up == resources.count {
            status = "All operational"
        } else {
            status = "\(up) up · \(resources.count - up) paused or checking"
        }
        let displayedStatus = accountMetricsArePartial ? "\(status) · Partial metrics" : status
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .uptimeRobot,
            resources: resources,
            metrics: metrics,
            status: displayedStatus,
            warnings: warnings
        )
    }

    private func uptimeRobotStatus(_ code: Int?) -> String {
        switch code {
        case 0: "Paused"
        case 1: "Not checked"
        case 2: "Up"
        case 8: "Seems down"
        case 9: "Down"
        default: "Unknown"
        }
    }

    private func latestUptimeRobotLogDate(_ logs: [Any]) -> Date? {
        logs.compactMap { item -> Date? in
            guard let timestamp = number(object(item)["datetime"]) else { return nil }
            return Date(timeIntervalSince1970: timestamp)
        }.max()
    }

    // MARK: - Better Stack

    private func fetchBetterStackSnapshot(accountID: UUID) async throws -> SiteIntegrationSnapshot {
        let token = try requiredCredential(label: "Better Stack API token")
        let headers = ["Authorization": "Bearer \(token)"]
        var nextURL: URL? = URL(string: "https://uptime.betterstack.com/api/v2/monitors")
        var monitors: [[String: Any]] = []
        var seenPageURLs = Set<String>()
        var seenMonitorIDs = Set<String>()
        var warnings: [String] = []
        let maximumPages = 100
        let maximumMonitors = 10_000
        var pageCount = 0
        var accountMetricsArePartial = false
        while let url = nextURL {
            try Task.checkCancellation()
            guard seenPageURLs.insert(url.absoluteString).inserted else {
                warnings.append("Better Stack repeated a pagination URL, so loading stopped to avoid an endless request loop.")
                accountMetricsArePartial = true
                break
            }
            let root = object(try await jsonRequest(url: url, headers: headers))
            guard root["data"] is [Any] else {
                throw SiteIntegrationsAPIError.decoding(
                    "Better Stack did not return a monitor list."
                )
            }
            for monitor in array(root["data"]).map(object) {
                guard let id = string(monitor["id"]), seenMonitorIDs.insert(id).inserted else { continue }
                monitors.append(monitor)
                if monitors.count >= maximumMonitors { break }
            }
            pageCount += 1
            let next = string(object(root["pagination"])["next"])
            nextURL = next.flatMap(URL.init(string:))
            if let nextURL {
                guard nextURL.scheme?.lowercased() == "https",
                      nextURL.host?.lowercased() == "uptime.betterstack.com" else {
                    throw SiteIntegrationsAPIError.invalidResponse
                }
            }
            if nextURL != nil, monitors.count >= maximumMonitors {
                warnings.append(
                    "Better Stack monitor loading stopped after \(maximumMonitors) monitors to protect the device."
                )
                accountMetricsArePartial = true
                nextURL = nil
            } else if nextURL != nil, pageCount >= maximumPages {
                warnings.append(
                    "Better Stack monitor loading stopped after \(maximumPages) pages to protect the device. Some monitors may not be shown."
                )
                accountMetricsArePartial = true
                nextURL = nil
            }
        }
        let resources = monitors.compactMap { monitor -> SiteIntegrationResource? in
            guard let id = string(monitor["id"]) else { return nil }
            let attributes = object(monitor["attributes"])
            let name = string(attributes["pronounceable_name"]) ?? string(attributes["url"]) ?? "Monitor \(id)"
            let urlValue = string(attributes["url"])
            let status = string(attributes["status"])?.capitalized ?? "Unknown"
            var resourceMetrics: [SiteIntegrationMetric] = []
            if let frequency = number(attributes["check_frequency"]) {
                resourceMetrics.append(metric(
                    key: "betterstack.check_frequency",
                    label: "Check Frequency",
                    value: frequency,
                    unit: .seconds,
                    resourceID: id
                ))
            }
            return SiteIntegrationResource(
                id: id,
                provider: .betterStack,
                name: name,
                subtitle: urlValue,
                url: urlValue.flatMap(URL.init(string:)),
                status: status,
                updatedAt: date(attributes["last_checked_at"] ?? attributes["updated_at"]),
                metrics: resourceMetrics,
                metadata: [
                    "monitorType": string(attributes["monitor_type"]) ?? "",
                    "teamName": string(attributes["team_name"]) ?? ""
                ]
            )
        }
        let up = resources.filter { $0.status?.lowercased() == "up" }.count
        let down = resources.filter { $0.status?.lowercased() == "down" }.count
        let paused = resources.filter { $0.status?.lowercased() == "paused" }.count
        let metrics = markPartialMetrics([
            metric(key: "betterstack.monitors", label: "Monitors", value: Double(resources.count), unit: .count),
            metric(key: "betterstack.up", label: "Up", value: Double(up), unit: .count),
            metric(key: "betterstack.down", label: "Down", value: Double(down), unit: .count),
            metric(key: "betterstack.paused", label: "Paused", value: Double(paused), unit: .count)
        ], when: accountMetricsArePartial)
        let status: String
        if resources.isEmpty {
            status = "No monitors"
        } else if down > 0 {
            status = "\(down) down"
        } else if up == resources.count {
            status = "All operational"
        } else {
            status = "\(up) up · \(resources.count - up) paused or checking"
        }
        let displayedStatus = accountMetricsArePartial ? "\(status) · Partial metrics" : status
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .betterStack,
            resources: resources,
            metrics: metrics,
            status: displayedStatus,
            warnings: warnings
        )
    }

    // MARK: - Request and normalization helpers

    private func jsonRequest(
        method: String = "GET",
        url: URL,
        body: Data? = nil,
        headers: [String: String] = [:],
        contentType: String = "application/json",
        maximumAttempts: Int = 3,
        timeoutInterval: TimeInterval = 30
    ) async throws -> Any {
        guard url.scheme?.lowercased() == "https", url.host != nil else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Provider requests must use HTTPS.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = timeoutInterval
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil {
            request.setValue(
                try ProviderRequestSecurity.validatedContentType(contentType),
                forHTTPHeaderField: "Content-Type"
            )
        }
        let validatedHeaders = try ProviderRequestSecurity.validatedHeaders(headers, protectedHeaders: [])
        for (name, value) in validatedHeaders { request.setValue(value, forHTTPHeaderField: name) }

        var attempt = 0
        while true {
            do {
                let (data, response) = try await ProviderRequestSecurity.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    throw SiteIntegrationsAPIError.invalidResponse
                }
                guard (200...299).contains(http.statusCode) else {
                    if isRetryableStatus(http.statusCode), attempt + 1 < maximumAttempts {
                        try await waitBeforeRetry(attempt: attempt, response: http)
                        attempt += 1
                        continue
                    }
                    throw SiteIntegrationsAPIError.requestFailed(http.statusCode, errorMessage(data))
                }
                guard !data.isEmpty else { return [String: Any]() }
                do {
                    return try JSONSerialization.jsonObject(with: data)
                } catch {
                    throw SiteIntegrationsAPIError.decoding(error.localizedDescription)
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch let urlError as URLError where urlError.code == .cancelled {
                throw CancellationError()
            } catch let urlError as URLError
                where isRetryableNetworkError(urlError) && attempt + 1 < maximumAttempts {
                try await waitBeforeRetry(attempt: attempt, response: nil)
                attempt += 1
            }
        }
    }

    private func isRetryableStatus(_ status: Int) -> Bool {
        status == 429 || status == 500 || status == 502 || status == 503 || status == 504
    }

    private func isRetryableNetworkError(_ error: URLError) -> Bool {
        switch error.code {
        case .timedOut, .networkConnectionLost, .cannotConnectToHost, .dnsLookupFailed:
            true
        default:
            false
        }
    }

    private func waitBeforeRetry(attempt: Int, response: HTTPURLResponse?) async throws {
        let seconds = Self.retryDelaySeconds(
            retryAfterHeader: response?.value(forHTTPHeaderField: "Retry-After"),
            attempt: attempt
        )
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }

    static func retryDelaySeconds(retryAfterHeader: String?, attempt: Int) -> TimeInterval {
        let parsedRetryAfter = retryAfterHeader.flatMap(Double.init)
        let retryAfter = parsedRetryAfter.flatMap { $0.isFinite ? $0 : nil }
        let boundedAttempt = min(max(attempt, 0), 4)
        let fallback = 0.5 * pow(2, Double(boundedAttempt))
        return min(max(retryAfter ?? fallback, 0.1), 8)
    }

    private func requiredCredential(label: String) throws -> String {
        guard let value = nonEmpty(account.credential) else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter your \(label).")
        }
        return value
    }

    private func requiredMetadata(_ key: String, label: String) throws -> String {
        guard let value = nonEmpty(account.metadata[key]) else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter the \(label).")
        }
        return value
    }

    private func throwIfGoogleUnauthorized(_ error: Error) throws {
        try throwIfCancellation(error)
        if let apiError = error as? SiteIntegrationsAPIError, apiError.isUnauthorized {
            throw apiError
        }
    }

    private func throwIfCancellation(_ error: Error) throws {
        if error is CancellationError {
            throw CancellationError()
        }
        if let urlError = error as? URLError, urlError.code == .cancelled {
            throw CancellationError()
        }
    }

    private func stableSiteResourceID(_ rawValue: String) -> String {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.lowercased().hasPrefix("sc-domain:") {
            return "sc-domain:" + value.dropFirst("sc-domain:".count).lowercased()
        }
        let hasScheme = value.contains("://")
        let candidate = hasScheme ? value : "https://\(value)"
        guard var components = URLComponents(string: candidate),
              let scheme = components.scheme,
              let host = components.host else {
            return value
        }
        components.scheme = scheme.lowercased()
        components.host = host.lowercased()
        components.fragment = nil
        guard let normalized = components.string else { return value }
        return normalized
    }

    private func validatedSiteURL(_ rawValue: String) throws -> URL {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let host = components.host,
              components.user == nil,
              components.password == nil else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter a complete HTTPS site URL.")
        }
        components.scheme = "https"
        components.host = host.lowercased()
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter a complete HTTPS site URL.")
        }
        return url
    }

    static func normalizedHTTPSBaseURL(_ rawValue: String) throws -> URL {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter a complete HTTPS base URL.")
        }
        components.scheme = "https"
        components.host = components.host?.lowercased()
        components.query = nil
        components.fragment = nil
        guard let url = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Enter a valid HTTPS base URL.")
        }
        return url
    }

    static func canonicalEndpointIdentity(_ rawValue: String) -> String? {
        guard let url = URL(string: rawValue),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              let host = components.host,
              components.user == nil,
              components.password == nil else { return nil }
        components.scheme = "https"
        components.host = host.lowercased()
        if components.port == 443 { components.port = nil }
        components.query = nil
        components.fragment = nil
        var path = components.path
        while path.count > 1, path.hasSuffix("/") { path.removeLast() }
        components.path = (path.isEmpty ? "" : path) + "/"
        return components.url?.absoluteString
    }

    static func umamiConnectionMetadata(
        from root: [String: Any],
        endpoint: URL
    ) throws -> [String: String] {
        let user = object(root["user"])
        guard let userID = nonEmpty(string(user["id"])),
              let endpointIdentity = canonicalEndpointIdentity(endpoint.absoluteString) else {
            throw SiteIntegrationsAPIError.decoding(
                "Umami did not return a stable account identity from /me."
            )
        }
        var metadata = [
            "umamiUserID": userID,
            "umamiEndpoint": endpointIdentity,
        ]
        if let username = nonEmpty(string(user["username"])) {
            metadata["umamiUsername"] = username
        }
        return metadata
    }

    static func originURL(from url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.lowercased() == "https",
              let host = components.host else { return nil }
        components.scheme = "https"
        components.host = host.lowercased()
        components.path = ""
        components.query = nil
        components.fragment = nil
        components.user = nil
        components.password = nil
        return components.url
    }

    static func pathComponent(_ value: String) throws -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed), !encoded.isEmpty else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The resource identifier is invalid.")
        }
        return encoded
    }

    nonisolated static func safeInteger(_ value: Double) -> Int? {
        guard value.isFinite else { return nil }
        return Int(exactly: value.rounded(.towardZero))
    }

    private func endpoint(base: URL, path: String, queryItems: [URLQueryItem] = []) throws -> URL {
        guard base.scheme?.lowercased() == "https", base.host != nil else {
            throw SiteIntegrationsAPIError.invalidConfiguration("Provider requests must use HTTPS.")
        }
        let baseValue = base.absoluteString.hasSuffix("/") ? base.absoluteString : base.absoluteString + "/"
        guard let url = URL(string: path, relativeTo: URL(string: baseValue))?.absoluteURL,
              url.scheme?.lowercased() == "https",
              url.host?.lowercased() == base.host?.lowercased(),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The provider endpoint is invalid.")
        }
        components.queryItems = queryItems.isEmpty ? nil : queryItems
        guard let result = components.url else {
            throw SiteIntegrationsAPIError.invalidConfiguration("The provider endpoint is invalid.")
        }
        return result
    }

    private func jsonData(_ value: Any) throws -> Data {
        do {
            return try JSONSerialization.data(withJSONObject: value)
        } catch {
            throw SiteIntegrationsAPIError.invalidConfiguration("Could not encode the provider request.")
        }
    }

    private func formData(_ items: [URLQueryItem]) -> Data {
        var components = URLComponents()
        components.queryItems = items
        return Data((components.percentEncodedQuery ?? "").utf8)
    }

    private func errorMessage(_ data: Data) -> String {
        if let value = try? JSONSerialization.jsonObject(with: data) {
            let root = object(value)
            if let message = string(root["message"] ?? root["error_description"]), !message.isEmpty { return message }
            let error = object(root["error"])
            if let message = string(error["message"] ?? error["error_description"]), !message.isEmpty { return message }
            if let first = array(root["errors"]).first,
               let message = string(object(first)["message"]), !message.isEmpty { return message }
        }
        let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.count <= 300 ? text : String(text.prefix(300))
    }
}

private func object(_ value: Any?) -> [String: Any] {
    value as? [String: Any] ?? [:]
}

private func array(_ value: Any?) -> [Any] {
    value as? [Any] ?? []
}

private func string(_ value: Any?) -> String? {
    if let value = value as? String { return value }
    if let value = value as? NSNumber { return value.stringValue }
    return nil
}

private func nonEmpty(_ value: String?) -> String? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

private func number(_ value: Any?) -> Double? {
    let result: Double?
    if let value = value as? NSNumber {
        result = value.doubleValue
    } else if let value = value as? String {
        result = Double(value.trimmingCharacters(in: .whitespacesAndNewlines))
    } else {
        result = nil
    }
    guard let result, result.isFinite else { return nil }
    return result
}

private func int(_ value: Any?) -> Int? {
    number(value).flatMap(SiteIntegrationsAPI.safeInteger)
}

private func boolean(_ value: Any?) -> Bool? {
    if let value = value as? Bool { return value }
    if let value = value as? NSNumber { return value.boolValue }
    if let value = value as? String {
        switch value.lowercased() {
        case "true", "1", "yes": return true
        case "false", "0", "no": return false
        default: return nil
        }
    }
    return nil
}

private func date(_ value: Any?) -> Date? {
    if let timestamp = number(value), timestamp > 0 {
        return Date(timeIntervalSince1970: timestamp > 10_000_000_000 ? timestamp / 1_000 : timestamp)
    }
    guard let value = string(value) else { return nil }
    let fractional = ISO8601DateFormatter()
    fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
}

private func metric(
    key: String,
    label: String,
    value: Double,
    unit: SiteIntegrationMetricUnit = .none,
    formattedValue: String? = nil,
    resourceID: String? = nil
) -> SiteIntegrationMetric {
    SiteIntegrationMetric(
        key: key,
        label: label,
        value: value,
        unit: unit,
        formattedValue: formattedValue,
        resourceID: resourceID
    )
}

private func markPartialMetrics(
    _ metrics: [SiteIntegrationMetric],
    when isPartial: Bool,
    excluding completeKeys: Set<String> = []
) -> [SiteIntegrationMetric] {
    guard isPartial else { return metrics }
    return metrics.map { metric in
        guard !completeKeys.contains(metric.key) else { return metric }
        return SiteIntegrationMetric(
            key: metric.key,
            label: "\(metric.label) · Partial",
            value: metric.value,
            unit: metric.unit,
            formattedValue: metric.formattedValue,
            resourceID: metric.resourceID
        )
    }
}

private func withResourceID(_ metric: SiteIntegrationMetric, resourceID: String) -> SiteIntegrationMetric {
    SiteIntegrationMetric(
        key: metric.key,
        label: metric.label,
        value: metric.value,
        unit: metric.unit,
        formattedValue: metric.formattedValue,
        resourceID: resourceID
    )
}

private func withoutResourceID(_ metric: SiteIntegrationMetric) -> SiteIntegrationMetric {
    SiteIntegrationMetric(
        key: metric.key,
        label: metric.label,
        value: metric.value,
        unit: metric.unit,
        formattedValue: metric.formattedValue
    )
}

private func inferredUnit(for key: String) -> SiteIntegrationMetricUnit {
    let value = key.lowercased()
    if value.contains("pagespersession") || value.contains("pages_per_session") { return .ratio }
    if value.contains("percentage") || value.contains("percent") || value.contains("rate") || value.contains("depth") {
        return .percent
    }
    if value.contains("millisecond") || value.hasSuffix("ms") { return .milliseconds }
    if value.contains("duration") || value.contains("time") { return .seconds }
    return .count
}

private func slug(_ value: String) -> String {
    let allowed = CharacterSet.alphanumerics
    return value.lowercased().unicodeScalars.map { allowed.contains($0) ? Character(String($0)) : "-" }
        .reduce(into: "") { result, character in
            if character != "-" || result.last != "-" { result.append(character) }
        }
        .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
}

private func humanized(_ value: String) -> String {
    var result = ""
    for character in value.replacingOccurrences(of: "_", with: " ") {
        if character.isUppercase, let last = result.last, !last.isWhitespace, !last.isUppercase {
            result.append(" ")
        }
        result.append(character)
    }
    return result.split(separator: " ").map { word in
        let lower = word.lowercased()
        return lower.prefix(1).uppercased() + lower.dropFirst()
    }.joined(separator: " ")
}
