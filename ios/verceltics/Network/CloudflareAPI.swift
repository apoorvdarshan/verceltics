import Foundation

private final class CloudflareRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme == "https", request.url?.host == "api.cloudflare.com" else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }
}

nonisolated enum CloudflareAPIError: LocalizedError, Equatable, Sendable {
    case invalidCredentials
    case forbidden(String)
    case invalidRequest(String)
    case confirmationRequired(String)
    case requestFailed(statusCode: Int, message: String)
    case api([CloudflareAPIIssue])
    case graphQL([String])
    case decoding(String)
    case network(String)

    var errorDescription: String? {
        switch self {
        case .invalidCredentials:
            "Cloudflare rejected these credentials."
        case .forbidden(let message):
            message.isEmpty ? "This Cloudflare user cannot access that resource." : message
        case .invalidRequest(let message):
            message
        case .confirmationRequired(let resourceID):
            "Confirm the change to \(resourceID) before continuing."
        case .requestFailed(let statusCode, let message):
            message.isEmpty ? "Cloudflare request failed (\(statusCode))." : "Cloudflare request failed (\(statusCode)): \(message)"
        case .api(let issues):
            issues.map { issue in
                var context: [String] = []
                if let code = issue.code { context.append("code \(code)") }
                if let pointer = issue.source?.pointer, !pointer.isEmpty { context.append(pointer) }
                let suffix = context.isEmpty ? "" : " [\(context.joined(separator: " · "))]"
                return issue.message + suffix
            }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
        case .graphQL(let messages):
            messages.joined(separator: "\n")
        case .decoding:
            "Cloudflare returned data the app could not parse."
        case .network(let message):
            message
        }
    }
}

nonisolated enum CloudflareHTTPMethod: String, CaseIterable, Identifiable, Codable, Sendable {
    case get = "GET"
    case post = "POST"
    case put = "PUT"
    case patch = "PATCH"
    case delete = "DELETE"

    var id: String { rawValue }
    var isMutation: Bool { self != .get }
}

nonisolated enum CloudflareRequestBodyEncoding: String, CaseIterable, Identifiable, Sendable {
    case utf8 = "UTF-8"
    case base64 = "Base64"

    var id: Self { self }
}

nonisolated struct CloudflareRawResponse: Sendable {
    let statusCode: Int
    let headers: [String: String]
    let data: Data

    var text: String {
        String(data: data, encoding: .utf8) ?? ""
    }

    var prettyPrintedBody: String {
        guard
            let object = try? JSONSerialization.jsonObject(with: data),
            let prettyData = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
            let value = String(data: prettyData, encoding: .utf8)
        else {
            return text
        }
        return value
    }
}

actor CloudflareAPI {
    private static let baseURL = "https://api.cloudflare.com/client/v4"

    private let email: String?
    private let credential: String
    private let authenticationMode: CloudflareAuthenticationMode
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(email: String, globalAPIKey: String, session: URLSession? = nil) {
        self.email = email.trimmingCharacters(in: .whitespacesAndNewlines)
        self.credential = globalAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        self.authenticationMode = .globalAPIKey
        self.session = session ?? Self.makeSecureSession()
    }

    init(apiToken: String, session: URLSession? = nil) {
        self.email = nil
        self.credential = apiToken.trimmingCharacters(in: .whitespacesAndNewlines)
        self.authenticationMode = .apiToken
        self.session = session ?? Self.makeSecureSession()
    }

    init(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String,
        session: URLSession? = nil
    ) {
        self.email = email?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.credential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        self.authenticationMode = authenticationMode
        self.session = session ?? Self.makeSecureSession()
    }

    // MARK: - Authentication

    @discardableResult
    func validateCredentials() async throws -> CloudflareUser {
        try validateConfiguredCredentials()
        return try await requestResult(path: "/user")
    }

    func validateAPIToken() async throws -> CloudflareAPITokenVerification {
        guard authenticationMode == .apiToken else {
            throw CloudflareAPIError.invalidRequest("This credential is not an API token.")
        }
        return try await requestResult(path: "/user/tokens/verify")
    }

    // MARK: - Accounts and zones

    func fetchAccounts() async throws -> [CloudflareAccountSummary] {
        try await fetchAllPages(path: "/accounts", perPage: 50)
    }

    func fetchZones(accountID: String? = nil) async throws -> [CloudflareZone] {
        var queryItems: [URLQueryItem] = []
        if let accountID, !accountID.isEmpty {
            queryItems.append(URLQueryItem(name: "account.id", value: accountID))
        }
        return try await fetchAllPages(path: "/zones", queryItems: queryItems, perPage: 50)
    }

    func fetchZone(id: String) async throws -> CloudflareZone {
        try await requestResult(path: "/zones/\(pathSegment(id))")
    }

    // MARK: - Pages

    func fetchPagesProjects(accountID: String) async throws -> [CloudflarePagesProject] {
        try await fetchAllPages(
            path: "/accounts/\(pathSegment(accountID))/pages/projects",
            perPage: 20
        )
    }

    func fetchPagesProject(accountID: String, projectName: String) async throws -> CloudflarePagesProject {
        try await requestResult(
            path: "/accounts/\(pathSegment(accountID))/pages/projects/\(pathSegment(projectName))"
        )
    }

    func fetchPagesDeployments(
        accountID: String,
        projectName: String,
        environment: CloudflarePagesEnvironment? = nil
    ) async throws -> [CloudflarePagesDeployment] {
        var queryItems: [URLQueryItem] = []
        if let environment {
            queryItems.append(URLQueryItem(name: "env", value: environment.rawValue))
        }

        return try await fetchAllPages(
            path: "/accounts/\(pathSegment(accountID))/pages/projects/\(pathSegment(projectName))/deployments",
            queryItems: queryItems,
            perPage: 20
        )
    }

    func fetchPagesDeployment(
        accountID: String,
        projectName: String,
        deploymentID: String
    ) async throws -> CloudflarePagesDeployment {
        try await requestResult(
            path: pagesDeploymentPath(accountID: accountID, projectName: projectName, deploymentID: deploymentID)
        )
    }

    func fetchPagesDeploymentLogs(
        accountID: String,
        projectName: String,
        deploymentID: String
    ) async throws -> [CloudflarePagesDeploymentLog] {
        let response: PagesDeploymentLogsResult = try await requestResult(
            path: pagesDeploymentPath(accountID: accountID, projectName: projectName, deploymentID: deploymentID) + "/history/logs"
        )
        return response.data
    }

    func retryPagesDeployment(
        accountID: String,
        projectName: String,
        deploymentID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflarePagesDeployment {
        try requireConfirmation(confirmation, resourceID: deploymentID)
        return try await requestResult(
            path: pagesDeploymentPath(accountID: accountID, projectName: projectName, deploymentID: deploymentID) + "/retry",
            method: .post
        )
    }

    func rollbackPagesDeployment(
        accountID: String,
        projectName: String,
        deploymentID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflarePagesDeployment {
        try requireConfirmation(confirmation, resourceID: deploymentID)
        return try await requestResult(
            path: pagesDeploymentPath(accountID: accountID, projectName: projectName, deploymentID: deploymentID) + "/rollback",
            method: .post
        )
    }

    func deletePagesDeployment(
        accountID: String,
        projectName: String,
        deploymentID: String,
        force: Bool = false,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireConfirmation(confirmation, resourceID: deploymentID)
        let queryItems = force ? [URLQueryItem(name: "force", value: "true")] : []
        try await requestWithoutResult(
            path: pagesDeploymentPath(accountID: accountID, projectName: projectName, deploymentID: deploymentID),
            method: .delete,
            queryItems: queryItems
        )
    }

    // MARK: - Workers

    func fetchWorkerScripts(accountID: String) async throws -> [CloudflareWorkerScript] {
        try await requestResult(path: "/accounts/\(pathSegment(accountID))/workers/scripts")
    }

    func fetchWorkerDeployments(accountID: String, scriptName: String) async throws -> [CloudflareWorkerDeployment] {
        let response: WorkerDeploymentsResult = try await requestResult(
            path: workerDeploymentsPath(accountID: accountID, scriptName: scriptName)
        )
        return response.deployments
    }

    func deleteWorker(
        accountID: String,
        scriptName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireConfirmation(confirmation, resourceID: scriptName)
        try await requestWithoutResult(
            path: "/accounts/\(pathSegment(accountID))/workers/scripts/\(pathSegment(scriptName))",
            method: .delete
        )
    }

    func deleteWorkerDeployment(
        accountID: String,
        scriptName: String,
        deploymentID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireConfirmation(confirmation, resourceID: deploymentID)
        try await requestWithoutResult(
            path: workerDeploymentsPath(accountID: accountID, scriptName: scriptName) + "/\(pathSegment(deploymentID))",
            method: .delete
        )
    }

    // MARK: - DNS

    func fetchDNSRecords(zoneID: String) async throws -> [CloudflareDNSRecord] {
        try await fetchAllPages(
            path: "/zones/\(pathSegment(zoneID))/dns_records",
            perPage: 100
        )
    }

    func createDNSRecord(zoneID: String, record: CloudflareDNSRecordInput) async throws -> CloudflareDNSRecord {
        try validateDNSRecord(record)
        return try await requestResult(
            path: "/zones/\(pathSegment(zoneID))/dns_records",
            method: .post,
            body: try encoded(record)
        )
    }

    func updateDNSRecord(
        zoneID: String,
        recordID: String,
        record: CloudflareDNSRecordInput
    ) async throws -> CloudflareDNSRecord {
        try validateDNSRecord(record)
        return try await requestResult(
            path: "/zones/\(pathSegment(zoneID))/dns_records/\(pathSegment(recordID))",
            method: .put,
            body: try encoded(record)
        )
    }

    func deleteDNSRecord(
        zoneID: String,
        recordID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireConfirmation(confirmation, resourceID: recordID)
        try await requestWithoutResult(
            path: "/zones/\(pathSegment(zoneID))/dns_records/\(pathSegment(recordID))",
            method: .delete
        )
    }

    // MARK: - Cache

    func purgeCache(
        zoneID: String,
        purge: CloudflareCachePurge,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireConfirmation(confirmation, resourceID: zoneID)
        try validateCachePurge(purge)
        let _: CloudflareCachePurgeResult = try await requestResult(
            path: "/zones/\(pathSegment(zoneID))/purge_cache",
            method: .post,
            body: try encoded(purge)
        )
    }

    // MARK: - Zone analytics

    func fetchZoneAnalytics(zoneID: String, from: Date, to: Date) async throws -> CloudflareZoneAnalyticsSummary {
        guard from < to else {
            throw CloudflareAPIError.invalidRequest("The analytics start date must be before the end date.")
        }

        let plan = try await analyticsQueryPlan(zoneID: zoneID, requestedFrom: from, requestedTo: to)
        let query = analyticsQuery(granularity: plan.granularity, seriesLimit: plan.seriesLimit)
        let variables = analyticsVariables(
            zoneID: zoneID,
            from: plan.from,
            to: plan.to,
            granularity: plan.granularity
        )
        let body = try JSONSerialization.data(withJSONObject: ["query": query, "variables": variables])
        let (data, response) = try await execute(path: "/graphql", method: .post, body: body)
        try throwForHTTPFailure(data: data, response: response)

        let graphResponse: GraphQLAnalyticsResponse
        do {
            graphResponse = try decoder.decode(GraphQLAnalyticsResponse.self, from: data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }

        if let errors = graphResponse.errors, !errors.isEmpty {
            throw CloudflareAPIError.graphQL(errors.map(\.message))
        }

        guard let zone = graphResponse.data?.viewer.zones.first else {
            return CloudflareZoneAnalyticsSummary(
                zoneID: zoneID,
                requestedFrom: from,
                requestedTo: to,
                from: plan.from,
                to: plan.to,
                granularity: plan.granularity,
                isWindowLimited: plan.isWindowLimited,
                totals: .zero,
                series: []
            )
        }

        let points = zone.series.compactMap { group -> CloudflareZoneAnalyticsPoint? in
            guard let timestamp = CloudflareDateParser.date(from: group.dimensions?.timestamp) else { return nil }
            return CloudflareZoneAnalyticsPoint(timestamp: timestamp, metrics: metrics(from: group))
        }
        let totals = zone.totals.first.map(metrics(from:)) ?? aggregate(points.map(\.metrics))

        return CloudflareZoneAnalyticsSummary(
            zoneID: zoneID,
            requestedFrom: from,
            requestedTo: to,
            from: plan.from,
            to: plan.to,
            granularity: plan.granularity,
            isWindowLimited: plan.isWindowLimited,
            totals: totals,
            series: points.sorted { $0.timestamp < $1.timestamp }
        )
    }

    func fetchZoneAnalyticsBreakdowns(
        zoneID: String,
        from: Date,
        to: Date
    ) async throws -> CloudflareZoneAnalyticsBreakdowns {
        guard from < to else {
            throw CloudflareAPIError.invalidRequest("The analytics start date must be before the end date.")
        }

        let plan = try await analyticsQueryPlan(zoneID: zoneID, requestedFrom: from, requestedTo: to)
        let query = analyticsBreakdownQuery(granularity: plan.granularity)
        let variables = analyticsVariables(
            zoneID: zoneID,
            from: plan.from,
            to: plan.to,
            granularity: plan.granularity
        )
        let body = try JSONSerialization.data(withJSONObject: ["query": query, "variables": variables])
        let (data, response) = try await execute(path: "/graphql", method: .post, body: body)
        try throwForHTTPFailure(data: data, response: response)

        let graphResponse: GraphQLAnalyticsBreakdownResponse
        do {
            graphResponse = try decoder.decode(GraphQLAnalyticsBreakdownResponse.self, from: data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
        if let errors = graphResponse.errors, !errors.isEmpty {
            throw CloudflareAPIError.graphQL(errors.map(\.message))
        }
        guard let sum = graphResponse.data?.viewer.zones.first?.totals.first?.sum else {
            return .empty
        }
        return analyticsBreakdowns(from: sum)
    }

    // MARK: - Advanced API explorer

    /// Sends an authenticated request to a relative Cloudflare `/client/v4` path.
    /// Mutation methods require confirmation tied to the exact path entered by the user.
    func rawRequest(
        method: CloudflareHTTPMethod,
        path: String,
        query: [String: String] = [:],
        headers: [String: String] = [:],
        bodyText: String? = nil,
        contentType: String? = "application/json",
        bodyEncoding: CloudflareRequestBodyEncoding = .utf8,
        confirmation: CloudflareMutationConfirmation? = nil
    ) async throws -> CloudflareRawResponse {
        let normalizedPath = try normalizeExplorerPath(path)
        if method.isMutation {
            guard
                let confirmation,
                confirmation.resourceID == path || confirmation.resourceID == normalizedPath
            else {
                throw CloudflareAPIError.confirmationRequired(path)
            }
        }

        let requestBody: Data?
        if let bodyText, !bodyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            switch bodyEncoding {
            case .utf8:
                guard let data = bodyText.data(using: .utf8) else {
                    throw CloudflareAPIError.invalidRequest("The request body is not valid UTF-8 text.")
                }
                requestBody = data
            case .base64:
                let normalized = bodyText.filter { !$0.isWhitespace }
                guard let data = Data(base64Encoded: normalized) else {
                    throw CloudflareAPIError.invalidRequest("The request body is not valid Base64 data.")
                }
                requestBody = data
            }

            if contentType?.lowercased().contains("json") == true, let requestBody {
                do {
                    _ = try JSONSerialization.jsonObject(with: requestBody, options: [.fragmentsAllowed])
                } catch {
                    throw CloudflareAPIError.invalidRequest("The request body is not valid JSON: \(error.localizedDescription)")
                }
            }
        } else {
            requestBody = nil
        }

        if let contentType,
           contentType.contains("\n") || contentType.contains("\r") {
            throw CloudflareAPIError.invalidRequest("The Content-Type header is invalid.")
        }
        let protectedHeaders = ["authorization", "content-length", "content-type", "host", "x-auth-email", "x-auth-key"]
        for (name, value) in headers {
            let normalizedName = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !normalizedName.isEmpty,
                  !protectedHeaders.contains(normalizedName),
                  !name.contains("\n"), !name.contains("\r"),
                  !value.contains("\n"), !value.contains("\r") else {
                throw CloudflareAPIError.invalidRequest("The custom header \(name) is not allowed.")
            }
        }

        let queryItems = query
            .sorted { $0.key < $1.key }
            .map { URLQueryItem(name: $0.key, value: $0.value) }
        let (data, response) = try await execute(
            path: normalizedPath,
            method: method,
            queryItems: queryItems,
            body: requestBody,
            contentType: contentType,
            additionalHeaders: headers
        )
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            result[String(describing: entry.key)] = String(describing: entry.value)
        }
        return CloudflareRawResponse(statusCode: response.statusCode, headers: headers, data: data)
    }

    // MARK: - Request helpers

    private nonisolated static func makeSecureSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        return URLSession(
            configuration: configuration,
            delegate: CloudflareRedirectGuard(),
            delegateQueue: nil
        )
    }

    private func fetchAllPages<T: Decodable & Sendable>(
        path: String,
        queryItems: [URLQueryItem] = [],
        perPage: Int
    ) async throws -> [T] {
        var page = 1
        var allItems: [T] = []

        while true {
            var items = queryItems.filter { $0.name != "page" && $0.name != "per_page" }
            items.append(URLQueryItem(name: "page", value: String(page)))
            items.append(URLQueryItem(name: "per_page", value: String(perPage)))

            let envelope: CloudflareEnvelope<[T]> = try await requestEnvelope(path: path, queryItems: items)
            let batch = envelope.result ?? []
            allItems.append(contentsOf: batch)

            if let totalPages = envelope.resultInfo?.totalPages {
                guard page < totalPages else { break }
            } else if batch.count < perPage {
                break
            }

            page += 1
        }

        return allItems
    }

    private func requestResult<T: Decodable & Sendable>(
        path: String,
        method: CloudflareHTTPMethod = .get,
        queryItems: [URLQueryItem] = [],
        body: Data? = nil
    ) async throws -> T {
        let envelope: CloudflareEnvelope<T> = try await requestEnvelope(
            path: path,
            method: method,
            queryItems: queryItems,
            body: body
        )
        guard let result = envelope.result else {
            throw CloudflareAPIError.decoding("The response did not contain a result.")
        }
        return result
    }

    private func requestWithoutResult(
        path: String,
        method: CloudflareHTTPMethod,
        queryItems: [URLQueryItem] = [],
        body: Data? = nil
    ) async throws {
        let _: CloudflareEnvelope<CloudflareJSONValue> = try await requestEnvelope(
            path: path,
            method: method,
            queryItems: queryItems,
            body: body
        )
    }

    private func requestEnvelope<T: Decodable & Sendable>(
        path: String,
        method: CloudflareHTTPMethod = .get,
        queryItems: [URLQueryItem] = [],
        body: Data? = nil
    ) async throws -> CloudflareEnvelope<T> {
        let (data, response) = try await execute(
            path: path,
            method: method,
            queryItems: queryItems,
            body: body
        )
        try throwForHTTPFailure(data: data, response: response)

        let envelope: CloudflareEnvelope<T>
        do {
            envelope = try decoder.decode(CloudflareEnvelope<T>.self, from: data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }

        guard envelope.success else {
            if !envelope.errors.isEmpty {
                throw CloudflareAPIError.api(envelope.errors)
            }
            throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: "Cloudflare reported an unsuccessful request.")
        }
        return envelope
    }

    private func execute(
        path: String,
        method: CloudflareHTTPMethod,
        queryItems: [URLQueryItem] = [],
        body: Data? = nil,
        contentType: String? = "application/json",
        additionalHeaders: [String: String] = [:]
    ) async throws -> (Data, HTTPURLResponse) {
        try validateConfiguredCredentials()

        guard var components = URLComponents(string: Self.baseURL + path) else {
            throw CloudflareAPIError.invalidRequest("The Cloudflare API path is invalid.")
        }
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else {
            throw CloudflareAPIError.invalidRequest("The Cloudflare API path is invalid.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method.rawValue
        request.httpBody = body
        switch authenticationMode {
        case .globalAPIKey:
            request.setValue(email, forHTTPHeaderField: "X-Auth-Email")
            request.setValue(credential, forHTTPHeaderField: "X-Auth-Key")
        case .apiToken:
            request.setValue("Bearer \(credential)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil, let contentType, !contentType.isEmpty {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        for (name, value) in additionalHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CloudflareAPIError.network(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw CloudflareAPIError.network("Cloudflare returned an invalid network response.")
        }
        return (data, httpResponse)
    }

    private func throwForHTTPFailure(data: Data, response: HTTPURLResponse) throws {
        guard !(200...299).contains(response.statusCode) else { return }

        let issues = (try? decoder.decode(CloudflareFailureEnvelope.self, from: data))?.errors ?? []
        let message = issues.map(\.message).filter { !$0.isEmpty }.joined(separator: "\n")

        switch response.statusCode {
        case 401:
            throw CloudflareAPIError.invalidCredentials
        case 403:
            throw CloudflareAPIError.forbidden(message)
        default:
            throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: message)
        }
    }

    private func validateConfiguredCredentials() throws {
        if authenticationMode == .globalAPIKey {
            guard let email, !email.isEmpty, email.contains("@") else {
                throw CloudflareAPIError.invalidRequest("Enter the email address used for your Cloudflare account.")
            }
        }
        guard !credential.isEmpty else {
            throw CloudflareAPIError.invalidRequest(
                authenticationMode == .apiToken ? "Enter your Cloudflare API token." : "Enter your Cloudflare Global API Key."
            )
        }
    }

    private func requireConfirmation(_ confirmation: CloudflareMutationConfirmation, resourceID: String) throws {
        guard confirmation.resourceID == resourceID else {
            throw CloudflareAPIError.confirmationRequired(resourceID)
        }
    }

    private func validateDNSRecord(_ record: CloudflareDNSRecordInput) throws {
        guard !record.type.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CloudflareAPIError.invalidRequest("Choose a DNS record type.")
        }
        guard !record.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a DNS record name.")
        }
        guard record.ttl == 1 || (30...86_400).contains(record.ttl) else {
            throw CloudflareAPIError.invalidRequest("DNS TTL must be Automatic (1) or between 30 and 86,400 seconds.")
        }
        guard record.content?.isEmpty == false || record.data?.isEmpty == false else {
            throw CloudflareAPIError.invalidRequest("Enter record content or structured record data.")
        }
    }

    private func validateCachePurge(_ purge: CloudflareCachePurge) throws {
        let values: [String]?
        switch purge {
        case .everything:
            values = nil
        case .files(let entries), .tags(let entries), .hosts(let entries), .prefixes(let entries):
            values = entries
        }
        if let values, values.isEmpty || values.contains(where: { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
            throw CloudflareAPIError.invalidRequest("Cache purge values cannot be empty.")
        }
    }

    private func encoded<T: Encodable>(_ value: T) throws -> Data {
        do {
            return try encoder.encode(value)
        } catch {
            throw CloudflareAPIError.invalidRequest("The request could not be encoded: \(error.localizedDescription)")
        }
    }

    private func pathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func pagesDeploymentPath(accountID: String, projectName: String, deploymentID: String) -> String {
        "/accounts/\(pathSegment(accountID))/pages/projects/\(pathSegment(projectName))/deployments/\(pathSegment(deploymentID))"
    }

    private func workerDeploymentsPath(accountID: String, scriptName: String) -> String {
        "/accounts/\(pathSegment(accountID))/workers/scripts/\(pathSegment(scriptName))/deployments"
    }

    private func normalizeExplorerPath(_ input: String) throws -> String {
        var path = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a relative Cloudflare API path.")
        }
        guard !path.contains("://"), !path.contains("?"), !path.contains("#") else {
            throw CloudflareAPIError.invalidRequest("Use a relative /client/v4 path and put query parameters in the query fields.")
        }

        if path == "/client/v4" || path == "client/v4" {
            path = "/"
        } else if path.hasPrefix("/client/v4/") {
            path.removeFirst("/client/v4".count)
        } else if path.hasPrefix("client/v4/") {
            path.removeFirst("client/v4".count)
        } else if !path.hasPrefix("/") {
            path = "/" + path
        }

        let decodedPath = path.removingPercentEncoding ?? path
        guard !decodedPath.split(separator: "/", omittingEmptySubsequences: true).contains("..") else {
            throw CloudflareAPIError.invalidRequest("Parent path components are not allowed.")
        }
        return path
    }

    // MARK: Analytics helpers

    private func analyticsQueryPlan(
        zoneID: String,
        requestedFrom: Date,
        requestedTo: Date
    ) async throws -> CloudflareAnalyticsQueryPlan {
        let settings = try await fetchZoneAnalyticsSettings(zoneID: zoneID)
        let now = Date()
        let candidates = [
            analyticsCandidate(
                granularity: .hourly,
                settings: settings.hourly,
                requestedFrom: requestedFrom,
                requestedTo: requestedTo,
                now: now
            ),
            analyticsCandidate(
                granularity: .daily,
                settings: settings.daily,
                requestedFrom: requestedFrom,
                requestedTo: requestedTo,
                now: now
            )
        ]
            .compactMap { $0 }
            .compactMap(normalizeDailyAnalyticsPlan)

        guard let selected = candidates.sorted(by: analyticsPlanPrecedes).first else {
            throw CloudflareAPIError.graphQL([
                "Cloudflare did not provide an enabled HTTP analytics dataset for this zone."
            ])
        }
        return selected
    }

    private func fetchZoneAnalyticsSettings(
        zoneID: String
    ) async throws -> GraphQLAnalyticsSettingsResponse.Settings {
        let query = """
        query ZoneAnalyticsSettings($zoneTag: string) {
          viewer {
            zones(filter: { zoneTag: $zoneTag }) {
              settings {
                hourly: httpRequests1hGroups { enabled maxDuration maxPageSize notOlderThan }
                daily: httpRequests1dGroups { enabled maxDuration maxPageSize notOlderThan }
              }
            }
          }
        }
        """
        let body = try JSONSerialization.data(
            withJSONObject: ["query": query, "variables": ["zoneTag": zoneID]]
        )
        let (data, response) = try await execute(path: "/graphql", method: .post, body: body)
        try throwForHTTPFailure(data: data, response: response)

        let graphResponse: GraphQLAnalyticsSettingsResponse
        do {
            graphResponse = try decoder.decode(GraphQLAnalyticsSettingsResponse.self, from: data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }

        if let settings = graphResponse.data?.viewer.zones.first?.settings,
           settings.hourly != nil || settings.daily != nil {
            return settings
        }
        if let errors = graphResponse.errors, !errors.isEmpty {
            throw CloudflareAPIError.graphQL(errors.map(\.message))
        }
        throw CloudflareAPIError.decoding("Cloudflare did not return analytics settings for this zone.")
    }

    private func analyticsCandidate(
        granularity: CloudflareAnalyticsGranularity,
        settings: GraphQLAnalyticsDatasetSettings?,
        requestedFrom: Date,
        requestedTo: Date,
        now: Date
    ) -> CloudflareAnalyticsQueryPlan? {
        guard
            let settings,
            settings.enabled != false,
            let maximumDuration = settings.maxDuration,
            maximumDuration > 0
        else {
            return nil
        }

        let effectiveEnd = min(requestedTo, now)
        var effectiveStart = max(requestedFrom, effectiveEnd.addingTimeInterval(-TimeInterval(maximumDuration)))

        if let notOlderThan = settings.notOlderThan, notOlderThan > 0 {
            effectiveStart = max(effectiveStart, now.addingTimeInterval(-TimeInterval(notOlderThan)))
        }

        let bucketDuration = analyticsBucketDuration(for: granularity)
        if let maxPageSize = settings.maxPageSize, maxPageSize > 1 {
            let pageCapacity = TimeInterval(maxPageSize - 1) * bucketDuration
            effectiveStart = max(effectiveStart, effectiveEnd.addingTimeInterval(-pageCapacity))
        }

        guard effectiveStart < effectiveEnd else { return nil }

        let duration = effectiveEnd.timeIntervalSince(effectiveStart)
        let expectedBuckets = max(1, Int(ceil(duration / bucketDuration)) + 1)
        let seriesLimit = max(1, min(settings.maxPageSize ?? expectedBuckets, expectedBuckets))
        let tolerance: TimeInterval = 1
        let coversRequestedWindow = effectiveStart <= requestedFrom.addingTimeInterval(tolerance)
            && effectiveEnd >= requestedTo.addingTimeInterval(-tolerance)

        return CloudflareAnalyticsQueryPlan(
            granularity: granularity,
            from: effectiveStart,
            to: effectiveEnd,
            seriesLimit: seriesLimit,
            isWindowLimited: !coversRequestedWindow
        )
    }

    private func analyticsPlanPrecedes(
        _ lhs: CloudflareAnalyticsQueryPlan,
        _ rhs: CloudflareAnalyticsQueryPlan
    ) -> Bool {
        if lhs.isWindowLimited != rhs.isWindowLimited {
            return !lhs.isWindowLimited
        }
        if !lhs.isWindowLimited, lhs.granularity != rhs.granularity {
            return lhs.granularity == .hourly
        }

        let lhsDuration = lhs.to.timeIntervalSince(lhs.from)
        let rhsDuration = rhs.to.timeIntervalSince(rhs.from)
        if abs(lhsDuration - rhsDuration) > 1 {
            return lhsDuration > rhsDuration
        }
        return lhs.granularity == .hourly && rhs.granularity == .daily
    }

    private func normalizeDailyAnalyticsPlan(
        _ plan: CloudflareAnalyticsQueryPlan
    ) -> CloudflareAnalyticsQueryPlan? {
        guard plan.granularity == .daily else { return plan }

        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt

        let startDay = calendar.startOfDay(for: plan.from)
        let firstIncludedDay: Date
        if plan.from.timeIntervalSince(startDay) > 0.5 {
            firstIncludedDay = calendar.date(byAdding: .day, value: 1, to: startDay) ?? startDay
        } else {
            firstIncludedDay = startDay
        }
        let lastIncludedDay = calendar.startOfDay(for: plan.to)
        guard firstIncludedDay <= lastIncludedDay else { return nil }
        let normalizedFrom = firstIncludedDay
        let dayCount = max(
            1,
            (calendar.dateComponents([.day], from: normalizedFrom, to: lastIncludedDay).day ?? 0) + 1
        )

        return CloudflareAnalyticsQueryPlan(
            granularity: plan.granularity,
            from: normalizedFrom,
            to: plan.to,
            seriesLimit: min(plan.seriesLimit, dayCount),
            isWindowLimited: plan.isWindowLimited
        )
    }

    private func analyticsBucketDuration(for granularity: CloudflareAnalyticsGranularity) -> TimeInterval {
        switch granularity {
        case .hourly: 3_600
        case .daily: 86_400
        }
    }

    private func analyticsQuery(
        granularity: CloudflareAnalyticsGranularity,
        seriesLimit: Int
    ) -> String {
        let daily = granularity == .daily
        let dataset = daily ? "httpRequests1dGroups" : "httpRequests1hGroups"
        let dimension = daily ? "date" : "datetime"
        let scalar = daily ? "Date" : "Time"
        let lowerBound = daily ? "date_geq" : "datetime_geq"
        let upperBound = daily ? "date_leq" : "datetime_leq"

        return """
        query ZoneTraffic($zoneTag: string, $start: \(scalar), $end: \(scalar)) {
          viewer {
            zones(filter: { zoneTag: $zoneTag }) {
              totals: \(dataset)(limit: 1, filter: { \(lowerBound): $start, \(upperBound): $end }) {
                sum { requests pageViews bytes cachedRequests cachedBytes threats encryptedRequests }
                uniq { uniques }
              }
              series: \(dataset)(limit: \(seriesLimit), orderBy: [\(dimension)_ASC], filter: { \(lowerBound): $start, \(upperBound): $end }) {
                dimensions { \(dimension) }
                sum { requests pageViews bytes cachedRequests cachedBytes threats encryptedRequests }
                uniq { uniques }
              }
            }
          }
        }
        """
    }

    private func analyticsBreakdownQuery(granularity: CloudflareAnalyticsGranularity) -> String {
        let daily = granularity == .daily
        let dataset = daily ? "httpRequests1dGroups" : "httpRequests1hGroups"
        let scalar = daily ? "Date" : "Time"
        let lowerBound = daily ? "date_geq" : "datetime_geq"
        let upperBound = daily ? "date_leq" : "datetime_leq"

        return """
        query ZoneTrafficBreakdowns($zoneTag: string, $start: \(scalar), $end: \(scalar)) {
          viewer {
            zones(filter: { zoneTag: $zoneTag }) {
              totals: \(dataset)(limit: 1, filter: { \(lowerBound): $start, \(upperBound): $end }) {
                sum {
                  encryptedBytes
                  browserMap { pageViews uaBrowserFamily }
                  contentTypeMap { bytes requests edgeResponseContentTypeName }
                  clientSSLMap { requests clientSSLProtocol }
                  countryMap { bytes requests threats clientCountryName }
                  ipClassMap { requests ipType }
                  responseStatusMap { requests edgeResponseStatus }
                  threatPathingMap { requests threatPathingName }
                }
              }
            }
          }
        }
        """
    }

    private func analyticsVariables(
        zoneID: String,
        from: Date,
        to: Date,
        granularity: CloudflareAnalyticsGranularity
    ) -> [String: String] {
        if granularity == .daily {
            let formatter = DateFormatter()
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(secondsFromGMT: 0)
            formatter.dateFormat = "yyyy-MM-dd"
            return ["zoneTag": zoneID, "start": formatter.string(from: from), "end": formatter.string(from: to)]
        }

        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return ["zoneTag": zoneID, "start": formatter.string(from: from), "end": formatter.string(from: to)]
    }

    private func metrics(from group: GraphQLAnalyticsGroup) -> CloudflareAnalyticsMetrics {
        CloudflareAnalyticsMetrics(
            requests: group.sum?.requests ?? 0,
            pageViews: group.sum?.pageViews ?? 0,
            bytes: group.sum?.bytes ?? 0,
            cachedRequests: group.sum?.cachedRequests ?? 0,
            cachedBytes: group.sum?.cachedBytes ?? 0,
            threats: group.sum?.threats ?? 0,
            encryptedRequests: group.sum?.encryptedRequests ?? 0,
            uniqueVisitors: group.uniq?.uniques ?? 0
        )
    }

    private func aggregate(_ values: [CloudflareAnalyticsMetrics]) -> CloudflareAnalyticsMetrics {
        CloudflareAnalyticsMetrics(
            requests: values.reduce(0) { $0 + $1.requests },
            pageViews: values.reduce(0) { $0 + $1.pageViews },
            bytes: values.reduce(0) { $0 + $1.bytes },
            cachedRequests: values.reduce(0) { $0 + $1.cachedRequests },
            cachedBytes: values.reduce(0) { $0 + $1.cachedBytes },
            threats: values.reduce(0) { $0 + $1.threats },
            encryptedRequests: values.reduce(0) { $0 + $1.encryptedRequests },
            uniqueVisitors: values.reduce(0) { $0 + $1.uniqueVisitors }
        )
    }

    private func analyticsBreakdowns(
        from sum: GraphQLAnalyticsGroup.Sum
    ) -> CloudflareZoneAnalyticsBreakdowns {
        func sorted(_ values: [CloudflareAnalyticsBreakdownItem]) -> [CloudflareAnalyticsBreakdownItem] {
            values.sorted {
                let lhs = $0.requests + $0.pageViews
                let rhs = $1.requests + $1.pageViews
                if lhs == rhs { return $0.label.localizedCaseInsensitiveCompare($1.label) == .orderedAscending }
                return lhs > rhs
            }
        }

        return CloudflareZoneAnalyticsBreakdowns(
            countries: sorted((sum.countryMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.clientCountryName ?? "Unknown",
                    requests: $0.requests ?? 0,
                    bytes: $0.bytes ?? 0,
                    threats: $0.threats ?? 0,
                    pageViews: 0
                )
            }),
            statusCodes: sorted((sum.responseStatusMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.edgeResponseStatus.map(String.init) ?? "Unknown",
                    requests: $0.requests ?? 0,
                    bytes: 0,
                    threats: 0,
                    pageViews: 0
                )
            }),
            contentTypes: sorted((sum.contentTypeMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.edgeResponseContentTypeName ?? "Unknown",
                    requests: $0.requests ?? 0,
                    bytes: $0.bytes ?? 0,
                    threats: 0,
                    pageViews: 0
                )
            }),
            tlsProtocols: sorted((sum.clientSSLMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.clientSSLProtocol ?? "None",
                    requests: $0.requests ?? 0,
                    bytes: 0,
                    threats: 0,
                    pageViews: 0
                )
            }),
            browsers: sorted((sum.browserMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.uaBrowserFamily ?? "Unknown",
                    requests: 0,
                    bytes: 0,
                    threats: 0,
                    pageViews: $0.pageViews ?? 0
                )
            }),
            ipClasses: sorted((sum.ipClassMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.ipType ?? "Unknown",
                    requests: $0.requests ?? 0,
                    bytes: 0,
                    threats: 0,
                    pageViews: 0
                )
            }),
            threatTypes: sorted((sum.threatPathingMap ?? []).map {
                CloudflareAnalyticsBreakdownItem(
                    label: $0.threatPathingName ?? "Unknown",
                    requests: $0.requests ?? 0,
                    bytes: 0,
                    threats: $0.requests ?? 0,
                    pageViews: 0
                )
            }),
            encryptedBytes: sum.encryptedBytes ?? 0
        )
    }
}

// MARK: - Private response envelopes

private nonisolated struct CloudflareEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let success: Bool
    let result: Result?
    let errors: [CloudflareAPIIssue]
    let messages: [CloudflareAPIIssue]
    let resultInfo: CloudflareResultInfo?

    enum CodingKeys: String, CodingKey {
        case success
        case result
        case errors
        case messages
        case resultInfo = "result_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
        messages = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .messages) ?? []
        resultInfo = try container.decodeIfPresent(CloudflareResultInfo.self, forKey: .resultInfo)
    }
}

private nonisolated struct CloudflareFailureEnvelope: Decodable, Sendable {
    let errors: [CloudflareAPIIssue]
}

private nonisolated struct PagesDeploymentLogsResult: Decodable, Sendable {
    let data: [CloudflarePagesDeploymentLog]
    let includesContainerLogs: Bool?
    let total: Int?

    enum CodingKeys: String, CodingKey {
        case data
        case includesContainerLogs = "includes_container_logs"
        case total
    }
}

private nonisolated struct WorkerDeploymentsResult: Decodable, Sendable {
    let deployments: [CloudflareWorkerDeployment]
}

private nonisolated struct CloudflareCachePurgeResult: Decodable, Sendable {
    let id: String
}

private nonisolated struct GraphQLAnalyticsResponse: Decodable, Sendable {
    let data: DataNode?
    let errors: [GraphQLResponseError]?

    struct DataNode: Decodable, Sendable {
        let viewer: Viewer
    }

    struct Viewer: Decodable, Sendable {
        let zones: [Zone]
    }

    struct Zone: Decodable, Sendable {
        let totals: [GraphQLAnalyticsGroup]
        let series: [GraphQLAnalyticsGroup]
    }
}

private nonisolated struct GraphQLAnalyticsBreakdownResponse: Decodable, Sendable {
    let data: DataNode?
    let errors: [GraphQLResponseError]?

    struct DataNode: Decodable, Sendable {
        let viewer: Viewer
    }

    struct Viewer: Decodable, Sendable {
        let zones: [Zone]
    }

    struct Zone: Decodable, Sendable {
        let totals: [GraphQLAnalyticsGroup]
    }
}

private nonisolated struct GraphQLAnalyticsSettingsResponse: Decodable, Sendable {
    let data: DataNode?
    let errors: [GraphQLResponseError]?

    struct DataNode: Decodable, Sendable {
        let viewer: Viewer
    }

    struct Viewer: Decodable, Sendable {
        let zones: [Zone]
    }

    struct Zone: Decodable, Sendable {
        let settings: Settings
    }

    struct Settings: Decodable, Sendable {
        let hourly: GraphQLAnalyticsDatasetSettings?
        let daily: GraphQLAnalyticsDatasetSettings?
    }
}

private nonisolated struct GraphQLAnalyticsDatasetSettings: Decodable, Sendable {
    let enabled: Bool?
    let maxDuration: Int?
    let maxPageSize: Int?
    let notOlderThan: Int?
}

private nonisolated struct GraphQLResponseError: Decodable, Sendable {
    let message: String
}

private nonisolated struct CloudflareAnalyticsQueryPlan: Sendable {
    let granularity: CloudflareAnalyticsGranularity
    let from: Date
    let to: Date
    let seriesLimit: Int
    let isWindowLimited: Bool
}

private nonisolated struct GraphQLAnalyticsGroup: Decodable, Sendable {
    let dimensions: Dimensions?
    let sum: Sum?
    let uniq: Unique?

    struct Dimensions: Decodable, Sendable {
        let date: String?
        let datetime: String?

        var timestamp: String? { datetime ?? date }
    }

    struct Sum: Decodable, Sendable {
        let requests: Int64?
        let pageViews: Int64?
        let bytes: Int64?
        let cachedRequests: Int64?
        let cachedBytes: Int64?
        let threats: Int64?
        let encryptedRequests: Int64?
        let encryptedBytes: Int64?
        let browserMap: [BrowserMap]?
        let contentTypeMap: [ContentTypeMap]?
        let clientSSLMap: [ClientSSLMap]?
        let countryMap: [CountryMap]?
        let ipClassMap: [IPClassMap]?
        let responseStatusMap: [ResponseStatusMap]?
        let threatPathingMap: [ThreatPathingMap]?

        struct BrowserMap: Decodable, Sendable {
            let pageViews: Int64?
            let uaBrowserFamily: String?
        }

        struct ContentTypeMap: Decodable, Sendable {
            let bytes: Int64?
            let requests: Int64?
            let edgeResponseContentTypeName: String?
        }

        struct ClientSSLMap: Decodable, Sendable {
            let requests: Int64?
            let clientSSLProtocol: String?
        }

        struct CountryMap: Decodable, Sendable {
            let bytes: Int64?
            let requests: Int64?
            let threats: Int64?
            let clientCountryName: String?
        }

        struct IPClassMap: Decodable, Sendable {
            let requests: Int64?
            let ipType: String?
        }

        struct ResponseStatusMap: Decodable, Sendable {
            let requests: Int64?
            let edgeResponseStatus: Int?
        }

        struct ThreatPathingMap: Decodable, Sendable {
            let requests: Int64?
            let threatPathingName: String?
        }
    }

    struct Unique: Decodable, Sendable {
        let uniques: Int64?
    }
}
