import Foundation

extension CloudflareAPI {
    // MARK: - Account operations

    func fetchAccountOperationsDetail(accountID: String) async throws -> CloudflareAccountSummary {
        try await cloudflareOperationsResult(
            method: .get,
            path: "/accounts/\(cloudflareOperationsSegment(accountID))"
        )
    }

    func fetchAccountMembers(accountID: String) async throws -> [CloudflareAccountMember] {
        try await cloudflareOperationsAllPages(
            path: "/accounts/\(cloudflareOperationsSegment(accountID))/members"
        )
    }

    func fetchAccountRoles(accountID: String) async throws -> [CloudflareAccountRole] {
        try await cloudflareOperationsAllPages(
            path: "/accounts/\(cloudflareOperationsSegment(accountID))/roles"
        )
    }

    func fetchAccountAuditEvents(
        accountID: String,
        since: Date,
        before: Date,
        limit: Int = 50
    ) async throws -> [CloudflareAccountAuditEvent] {
        guard since < before else {
            throw CloudflareAPIError.invalidRequest("The audit-log start date must be before the end date.")
        }

        return try await cloudflareOperationsResult(
            method: .get,
            path: "/accounts/\(cloudflareOperationsSegment(accountID))/logs/audit",
            query: [
                "since": cloudflareOperationsDate(since),
                "before": cloudflareOperationsDate(before),
                "direction": "desc",
                "limit": String(min(max(limit, 1), 1_000))
            ]
        )
    }

    // MARK: - Zone operations

    func fetchZoneDNSSEC(zoneID: String) async throws -> CloudflareDNSSECStatus {
        try await cloudflareOperationsResult(
            method: .get,
            path: "/zones/\(cloudflareOperationsSegment(zoneID))/dnssec"
        )
    }

    func enableZoneDNSSEC(
        zoneID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareDNSSECStatus {
        let path = "/zones/\(cloudflareOperationsSegment(zoneID))/dnssec"
        return try await cloudflareOperationsResult(
            method: .patch,
            path: path,
            body: ["status": CloudflareJSONValue.string("active")],
            confirmation: confirmation
        )
    }

    func disableZoneDNSSEC(
        zoneID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let path = "/zones/\(cloudflareOperationsSegment(zoneID))/dnssec"
        let _: String = try await cloudflareOperationsResult(
            method: .delete,
            path: path,
            confirmation: confirmation
        )
    }

    func fetchZoneSettings(zoneID: String) async throws -> [CloudflareZoneSetting] {
        try await cloudflareOperationsResult(
            method: .get,
            path: "/zones/\(cloudflareOperationsSegment(zoneID))/settings"
        )
    }

    func updateZoneSetting(
        zoneID: String,
        settingID: String,
        value: CloudflareJSONValue,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareZoneSetting {
        let path = "/zones/\(cloudflareOperationsSegment(zoneID))/settings/\(cloudflareOperationsSegment(settingID))"
        return try await cloudflareOperationsResult(
            method: .patch,
            path: path,
            body: ["value": value],
            confirmation: confirmation
        )
    }

    func fetchZoneDNSSettings(zoneID: String) async throws -> CloudflareZoneDNSSettings {
        try await cloudflareOperationsResult(
            method: .get,
            path: "/zones/\(cloudflareOperationsSegment(zoneID))/dns_settings"
        )
    }

    func updateZoneDNSSettings(
        zoneID: String,
        changes: [String: CloudflareJSONValue],
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareZoneDNSSettings {
        guard !changes.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Change at least one DNS setting before saving.")
        }
        let path = "/zones/\(cloudflareOperationsSegment(zoneID))/dns_settings"
        return try await cloudflareOperationsResult(
            method: .patch,
            path: path,
            body: changes,
            confirmation: confirmation
        )
    }

    func requestZoneActivationCheck(
        zoneID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareZoneActivationResult {
        let path = "/zones/\(cloudflareOperationsSegment(zoneID))/activation_check"
        return try await cloudflareOperationsResult(
            method: .put,
            path: path,
            confirmation: confirmation
        )
    }

    func fetchZoneDNSUsage(zoneID: String) async throws -> CloudflareDNSUsage {
        try await cloudflareOperationsResult(
            method: .get,
            path: "/zones/\(cloudflareOperationsSegment(zoneID))/dns_records/usage"
        )
    }

    func fetchZoneDNSAnalytics(
        zoneID: String,
        since: Date,
        until: Date
    ) async throws -> CloudflareDNSAnalyticsReport {
        guard since < until else {
            throw CloudflareAPIError.invalidRequest("The DNS analytics start date must be before the end date.")
        }

        return try await cloudflareOperationsResult(
            method: .get,
            path: "/zones/\(cloudflareOperationsSegment(zoneID))/dns_analytics/report",
            query: [
                "metrics": "queryCount,uncachedCount,staleCount",
                "since": cloudflareOperationsDate(since),
                "until": cloudflareOperationsDate(until),
                "limit": "1"
            ]
        )
    }

    // MARK: - Typed raw-request bridge

    private func cloudflareOperationsAllPages<Item: Decodable & Sendable>(
        path: String,
        perPage: Int = 50
    ) async throws -> [Item] {
        var page = 1
        var items: [Item] = []

        while true {
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: ["page": String(page), "per_page": String(perPage)]
            )
            let envelope: CloudflareOperationsEnvelope<[Item]> = try cloudflareOperationsDecode(response)
            items.append(contentsOf: envelope.result ?? [])

            guard let totalPages = envelope.resultInfo?.totalPages, page < totalPages else { break }
            page += 1
        }
        return items
    }

    private func cloudflareOperationsResult<Result: Decodable & Sendable>(
        method: CloudflareHTTPMethod,
        path: String,
        query: [String: String] = [:],
        body: [String: CloudflareJSONValue]? = nil,
        confirmation: CloudflareMutationConfirmation? = nil
    ) async throws -> Result {
        let bodyText: String?
        if let body {
            let data = try JSONEncoder().encode(body)
            guard let string = String(data: data, encoding: .utf8) else {
                throw CloudflareAPIError.invalidRequest("The Cloudflare request body could not be encoded.")
            }
            bodyText = string
        } else {
            bodyText = nil
        }

        let response = try await rawRequest(
            method: method,
            path: path,
            query: query,
            bodyText: bodyText,
            contentType: bodyText == nil ? nil : "application/json",
            confirmation: confirmation
        )
        let envelope: CloudflareOperationsEnvelope<Result> = try cloudflareOperationsDecode(response)
        guard let result = envelope.result else {
            throw CloudflareAPIError.decoding("Cloudflare returned a successful response without a result.")
        }
        return result
    }

    private func cloudflareOperationsDecode<Result: Decodable & Sendable>(
        _ response: CloudflareRawResponse
    ) throws -> CloudflareOperationsEnvelope<Result> {
        let decoder = JSONDecoder()
        guard (200...299).contains(response.statusCode) else {
            let failure = try? decoder.decode(CloudflareOperationsFailure.self, from: response.data)
            let message = failure?.errors.map(\.message).filter { !$0.isEmpty }.joined(separator: "\n") ?? ""
            switch response.statusCode {
            case 401:
                throw CloudflareAPIError.invalidCredentials
            case 403:
                throw CloudflareAPIError.forbidden(message)
            default:
                throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: message)
            }
        }

        let envelope: CloudflareOperationsEnvelope<Result>
        do {
            envelope = try decoder.decode(CloudflareOperationsEnvelope<Result>.self, from: response.data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
        guard envelope.success else {
            let message = envelope.errors.map(\.message).filter { !$0.isEmpty }.joined(separator: "\n")
            throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: message)
        }
        return envelope
    }

    private func cloudflareOperationsSegment(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func cloudflareOperationsDate(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }
}

private nonisolated struct CloudflareOperationsFailure: Decodable, Sendable {
    let errors: [CloudflareOperationsIssue]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CloudflareOperationsCodingKey.self)
        errors = try container.decodeIfPresent([CloudflareOperationsIssue].self, forKey: CloudflareOperationsCodingKey("errors")) ?? []
    }
}
