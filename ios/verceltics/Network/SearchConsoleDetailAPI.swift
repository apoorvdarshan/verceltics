import Foundation

nonisolated enum SearchConsoleDetailAPIError: LocalizedError, Equatable {
    case invalidAccount
    case missingCredential
    case invalidCredential
    case expiredCredential
    case invalidRequest(String)
    case invalidResponse
    case requestFailed(Int, String)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .invalidAccount:
            "This API client requires a Google Search Console account."
        case .missingCredential:
            "The Search Console account does not contain a Google access token."
        case .invalidCredential:
            "The saved Google credential is invalid. Reconnect the account."
        case .expiredCredential:
            "The Google access token has expired. Refresh or reconnect the account."
        case .invalidRequest(let message):
            message
        case .invalidResponse:
            "Google returned an invalid response."
        case .requestFailed(let status, let message):
            message.isEmpty
                ? "Search Console request failed (HTTP \(status))."
                : "Search Console request failed (HTTP \(status)): \(message)"
        case .decoding(let message):
            "Could not read the Search Console response: \(message)"
        }
    }
}

struct SearchConsoleDetailAPI: Sendable {
    let account: SiteIntegrationAccount
    private let session: URLSession?

    private static let webmastersBaseURL = URL(string: "https://www.googleapis.com/webmasters/v3")!
    private static let inspectionURL = URL(
        string: "https://searchconsole.googleapis.com/v1/urlInspection/index:inspect"
    )!

    init(account: SiteIntegrationAccount, session: URLSession? = nil) {
        self.account = account
        self.session = session
    }

    // MARK: - Search Analytics

    func querySearchAnalytics(
        siteURL: String,
        query: SearchConsoleAnalyticsQuery
    ) async throws -> SearchConsoleAnalyticsResponse {
        try await execute(try makeSearchAnalyticsRequest(siteURL: siteURL, query: query))
    }

    func queryAllSearchAnalytics(
        siteURL: String,
        query: SearchConsoleAnalyticsQuery,
        maximumRows: Int = 100_000
    ) async throws -> SearchConsoleAnalyticsResponse {
        guard maximumRows > 0 else {
            throw SearchConsoleDetailAPIError.invalidRequest("Maximum rows must be greater than zero.")
        }

        var rows: [SearchConsoleAnalyticsRow] = []
        rows.reserveCapacity(min(maximumRows, query.rowLimit))
        var offset = query.startRow
        var aggregationType: String?
        var metadata: SearchConsoleAnalyticsMetadata?

        while rows.count < maximumRows {
            try Task.checkCancellation()
            let pageQuery = query.page(startingAt: offset)
            let page = try await querySearchAnalytics(siteURL: siteURL, query: pageQuery)
            aggregationType = page.responseAggregationType ?? aggregationType
            metadata = page.metadata ?? metadata

            let remaining = maximumRows - rows.count
            rows.append(contentsOf: page.rows.prefix(remaining))
            guard page.rows.count == query.rowLimit, rows.count < maximumRows else { break }
            offset += page.rows.count
        }

        return SearchConsoleAnalyticsResponse(
            rows: rows,
            responseAggregationType: aggregationType,
            metadata: metadata
        )
    }

    func makeSearchAnalyticsRequest(
        siteURL: String,
        query: SearchConsoleAnalyticsQuery
    ) throws -> URLRequest {
        try Self.validate(query)
        let site = try Self.validatedSiteURL(siteURL)
        let url = try Self.endpoint(
            base: Self.webmastersBaseURL,
            path: "sites/\(Self.percentEncodedPathSegment(site))/searchAnalytics/query"
        )
        return try makeRequest(url: url, method: "POST", body: try JSONEncoder().encode(query))
    }

    // MARK: - Sites

    func listSites() async throws -> [SearchConsoleSite] {
        let response: SearchConsoleSiteListResponse = try await execute(try makeListSitesRequest())
        return response.siteEntry
    }

    func getSite(_ siteURL: String) async throws -> SearchConsoleSite {
        try await execute(try makeGetSiteRequest(siteURL: siteURL))
    }

    func addSite(_ siteURL: String) async throws {
        try await executeWithoutResponse(try makeAddSiteRequest(siteURL: siteURL))
    }

    func deleteSite(_ siteURL: String) async throws {
        try await executeWithoutResponse(try makeDeleteSiteRequest(siteURL: siteURL))
    }

    func makeListSitesRequest() throws -> URLRequest {
        try makeRequest(
            url: Self.webmastersBaseURL.appending(path: "sites"),
            method: "GET"
        )
    }

    func makeGetSiteRequest(siteURL: String) throws -> URLRequest {
        try makeSiteMutationRequest(siteURL: siteURL, method: "GET")
    }

    func makeAddSiteRequest(siteURL: String) throws -> URLRequest {
        try makeSiteMutationRequest(siteURL: siteURL, method: "PUT")
    }

    func makeDeleteSiteRequest(siteURL: String) throws -> URLRequest {
        try makeSiteMutationRequest(siteURL: siteURL, method: "DELETE")
    }

    private func makeSiteMutationRequest(
        siteURL: String,
        method: String
    ) throws -> URLRequest {
        let site = try Self.validatedSiteURL(siteURL)
        let url = try Self.endpoint(
            base: Self.webmastersBaseURL,
            path: "sites/\(Self.percentEncodedPathSegment(site))"
        )
        return try makeRequest(url: url, method: method)
    }

    // MARK: - Sitemaps

    func listSitemaps(
        siteURL: String,
        sitemapIndex: String? = nil
    ) async throws -> [SearchConsoleSitemap] {
        let response: SearchConsoleSitemapListResponse = try await execute(
            try makeListSitemapsRequest(siteURL: siteURL, sitemapIndex: sitemapIndex)
        )
        return response.sitemap
    }

    func getSitemap(siteURL: String, feedpath: String) async throws -> SearchConsoleSitemap {
        try await execute(try makeGetSitemapRequest(siteURL: siteURL, feedpath: feedpath))
    }

    func submitSitemap(siteURL: String, feedpath: String) async throws {
        try await executeWithoutResponse(
            try makeSubmitSitemapRequest(siteURL: siteURL, feedpath: feedpath)
        )
    }

    func deleteSitemap(siteURL: String, feedpath: String) async throws {
        try await executeWithoutResponse(
            try makeDeleteSitemapRequest(siteURL: siteURL, feedpath: feedpath)
        )
    }

    func makeListSitemapsRequest(
        siteURL: String,
        sitemapIndex: String? = nil
    ) throws -> URLRequest {
        let site = try Self.validatedSiteURL(siteURL)
        var components = URLComponents(
            url: try Self.endpoint(
                base: Self.webmastersBaseURL,
                path: "sites/\(Self.percentEncodedPathSegment(site))/sitemaps"
            ),
            resolvingAgainstBaseURL: false
        )!
        if let sitemapIndex {
            let value = try Self.validatedHTTPURLString(sitemapIndex, label: "Sitemap index")
            components.queryItems = [URLQueryItem(name: "sitemapIndex", value: value)]
        }
        guard let url = components.url else {
            throw SearchConsoleDetailAPIError.invalidRequest("Could not construct the sitemap request URL.")
        }
        return try makeRequest(url: url, method: "GET")
    }

    func makeGetSitemapRequest(siteURL: String, feedpath: String) throws -> URLRequest {
        try makeSitemapMutationRequest(siteURL: siteURL, feedpath: feedpath, method: "GET")
    }

    func makeSubmitSitemapRequest(siteURL: String, feedpath: String) throws -> URLRequest {
        try makeSitemapMutationRequest(siteURL: siteURL, feedpath: feedpath, method: "PUT")
    }

    func makeDeleteSitemapRequest(siteURL: String, feedpath: String) throws -> URLRequest {
        try makeSitemapMutationRequest(siteURL: siteURL, feedpath: feedpath, method: "DELETE")
    }

    private func makeSitemapMutationRequest(
        siteURL: String,
        feedpath: String,
        method: String
    ) throws -> URLRequest {
        let site = try Self.validatedSiteURL(siteURL)
        let sitemap = try Self.validatedHTTPURLString(feedpath, label: "Sitemap URL")
        let url = try Self.endpoint(
            base: Self.webmastersBaseURL,
            path: "sites/\(Self.percentEncodedPathSegment(site))/sitemaps/\(Self.percentEncodedPathSegment(sitemap))"
        )
        return try makeRequest(url: url, method: method)
    }

    // MARK: - URL Inspection

    func inspectURL(
        _ inspectionURL: URL,
        siteURL: String,
        languageCode: String = "en-US"
    ) async throws -> SearchConsoleURLInspectionResult {
        let response: SearchConsoleURLInspectionResponse = try await execute(
            try makeURLInspectionRequest(
                inspectionURL: inspectionURL,
                siteURL: siteURL,
                languageCode: languageCode
            )
        )
        return response.inspectionResult
    }

    func makeURLInspectionRequest(
        inspectionURL: URL,
        siteURL: String,
        languageCode: String = "en-US"
    ) throws -> URLRequest {
        let inspected = try Self.validatedHTTPURLString(
            inspectionURL.absoluteString,
            label: "Inspection URL"
        )
        let site = try Self.validatedSiteURL(siteURL)
        let language = languageCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...35).contains(language.count),
              language.unicodeScalars.allSatisfy({
                  CharacterSet.alphanumerics.contains($0) || $0 == "-"
              }) else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Language code must be a valid BCP-47 language tag."
            )
        }
        let body = SearchConsoleURLInspectionRequest(
            inspectionUrl: inspected,
            siteUrl: site,
            languageCode: language
        )
        return try makeRequest(
            url: Self.inspectionURL,
            method: "POST",
            body: try JSONEncoder().encode(body)
        )
    }

    // MARK: - Validation and request execution

    nonisolated static func validate(_ query: SearchConsoleAnalyticsQuery) throws {
        guard isGoogleDate(query.dateRange.startDate), isGoogleDate(query.dateRange.endDate) else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Search Analytics dates must use YYYY-MM-DD format."
            )
        }
        guard query.dateRange.startDate <= query.dateRange.endDate else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Search Analytics start date must not be after the end date."
            )
        }
        guard (1...25_000).contains(query.rowLimit) else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Search Analytics row limit must be between 1 and 25,000."
            )
        }
        guard query.startRow >= 0 else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Search Analytics start row must be zero or greater."
            )
        }
        guard Set(query.dimensions).count == query.dimensions.count else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "A Search Analytics dimension cannot be selected more than once."
            )
        }
        if query.dimensions.contains(.hour), query.dataState != .hourlyAll {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Hourly results require the hourly_all data state."
            )
        }

        let filters = query.dimensionFilterGroups.flatMap(\.filters)
        guard query.dimensionFilterGroups.allSatisfy({ !$0.filters.isEmpty }) else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Search Analytics filter groups cannot be empty."
            )
        }
        guard filters.allSatisfy({
            !$0.expression.isEmpty && $0.expression.count <= 4_096
        }) else {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Every Search Analytics filter needs an expression of at most 4,096 characters."
            )
        }

        let usesPage = query.dimensions.contains(.page)
            || filters.contains(where: { $0.dimension == .page })
        if usesPage, query.aggregationType == .byProperty {
            throw SearchConsoleDetailAPIError.invalidRequest(
                "Page grouping or filtering cannot use by-property aggregation."
            )
        }

        if query.aggregationType == .byNewsShowcasePanel {
            let supportsShowcase = query.searchType == .discover || query.searchType == .googleNews
            let filtersToNewsShowcase = filters.contains {
                $0.dimension == .searchAppearance && $0.expression == "NEWS_SHOWCASE"
            }
            guard supportsShowcase, filtersToNewsShowcase, !usesPage else {
                throw SearchConsoleDetailAPIError.invalidRequest(
                    "News Showcase aggregation requires Discover or Google News, a NEWS_SHOWCASE appearance filter, and no page grouping or filter."
                )
            }
        }
    }

    nonisolated static func percentEncodedPathSegment(_ value: String) -> String {
        let unreserved = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~".utf8)
        return value.utf8.map { byte in
            unreserved.contains(byte) ? String(UnicodeScalar(byte)) : String(format: "%%%02X", byte)
        }.joined()
    }

    private nonisolated static func isGoogleDate(_ value: String) -> Bool {
        guard value.count == 10 else { return false }
        let characters = Array(value)
        guard characters[4] == "-", characters[7] == "-",
              characters.enumerated().allSatisfy({ index, character in
                  index == 4 || index == 7 || character.isNumber
              }),
              let year = Int(value.prefix(4)),
              let month = Int(value.dropFirst(5).prefix(2)),
              let day = Int(value.suffix(2)),
              year > 0,
              (1...12).contains(month),
              (1...31).contains(day) else { return false }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")
            ?? TimeZone(secondsFromGMT: -8 * 60 * 60)!
        guard let date = calendar.date(from: DateComponents(year: year, month: month, day: day)) else {
            return false
        }
        let roundTripped = calendar.dateComponents([.year, .month, .day], from: date)
        return roundTripped.year == year
            && roundTripped.month == month
            && roundTripped.day == day
    }

    private nonisolated static func validatedSiteURL(_ value: String) throws -> String {
        let site = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !site.isEmpty, !site.contains("\r"), !site.contains("\n") else {
            throw SearchConsoleDetailAPIError.invalidRequest("Enter a Search Console property URL.")
        }
        if site.hasPrefix("sc-domain:") {
            let domain = String(site.dropFirst("sc-domain:".count))
            guard !domain.isEmpty,
                  !domain.contains("/"),
                  !domain.contains(":"),
                  !domain.contains(" ") else {
                throw SearchConsoleDetailAPIError.invalidRequest("Enter a valid domain property.")
            }
            return site
        }
        return try validatedHTTPURLString(site, label: "Search Console property")
    }

    private nonisolated static func validatedHTTPURLString(
        _ value: String,
        label: String
    ) throws -> String {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: normalized),
              let scheme = components.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              components.host?.isEmpty == false,
              components.user == nil,
              components.password == nil,
              !normalized.contains("\r"),
              !normalized.contains("\n") else {
            throw SearchConsoleDetailAPIError.invalidRequest("\(label) must be a fully-qualified HTTP or HTTPS URL.")
        }
        return normalized
    }

    private nonisolated static func endpoint(base: URL, path: String) throws -> URL {
        guard let url = URL(string: base.absoluteString + "/" + path),
              url.scheme == "https",
              url.host == base.host,
              url.user == nil,
              url.password == nil else {
            throw SearchConsoleDetailAPIError.invalidRequest("Could not construct the Google API URL.")
        }
        return url
    }

    private func makeRequest(
        url: URL,
        method: String,
        body: Data? = nil
    ) throws -> URLRequest {
        let accessToken = try bearerAccessToken()
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return request
    }

    private func bearerAccessToken() throws -> String {
        guard account.provider == .googleSearchConsole else {
            throw SearchConsoleDetailAPIError.invalidAccount
        }
        let value = account.credential.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { throw SearchConsoleDetailAPIError.missingCredential }
        guard let credential = try? GoogleOAuthCredential.fromKeychainValue(value) else {
            throw SearchConsoleDetailAPIError.invalidCredential
        }
        guard !credential.needsRefresh else {
            throw SearchConsoleDetailAPIError.expiredCredential
        }
        return credential.accessToken
    }

    private func execute<Response: Decodable & Sendable>(
        _ request: URLRequest
    ) async throws -> Response {
        let data = try await responseData(for: request)
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw SearchConsoleDetailAPIError.decoding(error.localizedDescription)
        }
    }

    private func executeWithoutResponse(_ request: URLRequest) async throws {
        _ = try await responseData(for: request)
    }

    private func responseData(for request: URLRequest) async throws -> Data {
        let (data, response) = try await ProviderRequestSecurity.data(
            for: request,
            using: session,
            maximumResponseBytes: 32 * 1_024 * 1_024
        )
        guard let response = response as? HTTPURLResponse else {
            throw SearchConsoleDetailAPIError.invalidResponse
        }
        guard (200...299).contains(response.statusCode) else {
            throw SearchConsoleDetailAPIError.requestFailed(
                response.statusCode,
                Self.googleErrorMessage(from: data)
            )
        }
        return data
    }

    private nonisolated static func googleErrorMessage(from data: Data) -> String {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return String(data: data, encoding: .utf8) ?? ""
        }
        if let error = root["error"] as? [String: Any],
           let message = error["message"] as? String {
            return message
        }
        return root["message"] as? String ?? ""
    }
}

private nonisolated struct SearchConsoleURLInspectionRequest: Encodable, Sendable {
    let inspectionUrl: String
    let siteUrl: String
    let languageCode: String
}
