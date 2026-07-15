import Foundation

extension CloudflareAPI {
    // MARK: Versions and deployments

    func fetchWorkerVersions(accountID: String, scriptName: String) async throws -> [CloudflareWorkerVersion] {
        let result: CloudflareWorkerVersionList = try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/versions",
            query: ["deployable": "true"]
        )
        return result.items
    }

    func fetchWorkerVersion(
        accountID: String,
        scriptName: String,
        versionID: String
    ) async throws -> CloudflareWorkerVersionDetail {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName)
                + "/versions/\(workerOperationsPathSegment(versionID))"
        )
    }

    func fetchWorkerDeployment(
        accountID: String,
        scriptName: String,
        deploymentID: String
    ) async throws -> CloudflareWorkerDeployment {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName)
                + "/deployments/\(workerOperationsPathSegment(deploymentID))"
        )
    }

    func deployWorkerVersion(
        accountID: String,
        scriptName: String,
        versionID: String,
        message: String?
    ) async throws -> CloudflareWorkerDeployment {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/deployments"
        let annotations: CloudflareJSONValue = {
            guard let message, !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return .object(["workers/triggered_by": .string("verceltics")])
            }
            return .object([
                "workers/message": .string(message.trimmingCharacters(in: .whitespacesAndNewlines)),
                "workers/triggered_by": .string("verceltics")
            ])
        }()
        return try await workerOperationsResult(
            path: path,
            method: .post,
            body: .object([
                "strategy": .string("percentage"),
                "versions": .array([
                    .object([
                        "version_id": .string(versionID),
                        "percentage": .double(100)
                    ])
                ]),
                "annotations": annotations
            ])
        )
    }

    // MARK: Secrets

    func fetchWorkerSecrets(accountID: String, scriptName: String) async throws -> [CloudflareWorkerSecretMetadata] {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/secrets"
        )
    }

    func putWorkerSecret(
        accountID: String,
        scriptName: String,
        name: String,
        value: String
    ) async throws {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/secrets"
        try await workerOperationsNoResult(
            path: path,
            method: .put,
            body: .object([
                "name": .string(name),
                "text": .string(value),
                "type": .string("secret_text")
            ])
        )
    }

    func deleteWorkerSecret(accountID: String, scriptName: String, name: String) async throws {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName)
            + "/secrets/\(workerOperationsPathSegment(name))"
        try await workerOperationsNoResult(path: path, method: .delete)
    }

    // MARK: Schedules

    func fetchWorkerSchedules(accountID: String, scriptName: String) async throws -> [CloudflareWorkerSchedule] {
        let result: CloudflareWorkerScheduleList = try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/schedules"
        )
        return result.schedules
    }

    func updateWorkerSchedules(
        accountID: String,
        scriptName: String,
        cronExpressions: [String]
    ) async throws -> [CloudflareWorkerSchedule] {
        let values = cronExpressions.map {
            CloudflareJSONValue.object(["cron": .string($0)])
        }
        let result: CloudflareWorkerScheduleList = try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/schedules",
            method: .put,
            body: .array(values)
        )
        return result.schedules
    }

    // MARK: Domains and workers.dev

    func fetchWorkerDomains(accountID: String) async throws -> [CloudflareWorkerDomain] {
        let path = "/accounts/\(workerOperationsPathSegment(accountID))/workers/domains"
        var page = 1
        var domains: [CloudflareWorkerDomain] = []
        var paginationGuard = CloudflarePaginationGuard()

        while true {
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: ["page": String(page), "per_page": "50"]
            )
            try workerOperationsValidate(response)
            let envelope: CloudflareWorkerOperationsEnvelope<[CloudflareWorkerDomain]>
            do {
                envelope = try JSONDecoder().decode(CloudflareWorkerOperationsEnvelope<[CloudflareWorkerDomain]>.self, from: response.data)
            } catch {
                throw CloudflareAPIError.decoding(error.localizedDescription)
            }
            guard envelope.success else { throw CloudflareAPIError.api(envelope.errors) }
            let batch = envelope.result ?? []
            try paginationGuard.record(batchCount: batch.count, signature: response.data.hashValue)
            domains.append(contentsOf: batch)

            if let totalPages = envelope.resultInfo?.totalPages {
                guard page < totalPages else { break }
            } else if let totalCount = envelope.resultInfo?.totalCount {
                guard domains.count < totalCount else { break }
            } else if batch.count < 50 {
                break
            }
            page += 1
        }
        return domains
    }

    func attachWorkerDomain(accountID: String, hostname: String, scriptName: String) async throws -> CloudflareWorkerDomain {
        let path = "/accounts/\(workerOperationsPathSegment(accountID))/workers/domains"
        return try await workerOperationsResult(
            path: path,
            method: .put,
            body: .object([
                "hostname": .string(hostname),
                "service": .string(scriptName),
                "environment": .string("production")
            ])
        )
    }

    func detachWorkerDomain(accountID: String, domainID: String) async throws {
        let path = "/accounts/\(workerOperationsPathSegment(accountID))/workers/domains/"
            + workerOperationsPathSegment(domainID)
        try await workerOperationsNoResult(path: path, method: .delete)
    }

    func fetchWorkersAccountSubdomain(accountID: String) async throws -> CloudflareWorkersAccountSubdomain {
        try await workerOperationsResult(
            path: "/accounts/\(workerOperationsPathSegment(accountID))/workers/subdomain"
        )
    }

    func fetchWorkerSubdomain(accountID: String, scriptName: String) async throws -> CloudflareWorkerSubdomain {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/subdomain"
        )
    }

    func updateWorkerSubdomain(
        accountID: String,
        scriptName: String,
        enabled: Bool,
        previewsEnabled: Bool
    ) async throws -> CloudflareWorkerSubdomain {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/subdomain"
        return try await workerOperationsResult(
            path: path,
            method: .post,
            body: .object([
                "enabled": .bool(enabled),
                "previews_enabled": .bool(previewsEnabled)
            ])
        )
    }

    // MARK: Settings and tails

    func fetchWorkerScriptSettings(accountID: String, scriptName: String) async throws -> CloudflareWorkerScriptSettings {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/settings"
        )
    }

    func fetchWorkerScriptLevelSettings(
        accountID: String,
        scriptName: String
    ) async throws -> CloudflareWorkerScriptLevelSettings {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/script-settings"
        )
    }

    func updateWorkerObservability(
        accountID: String,
        scriptName: String,
        enabled: Bool,
        logsEnabled: Bool,
        tracesEnabled: Bool
    ) async throws -> CloudflareWorkerScriptLevelSettings {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/script-settings"
        return try await workerOperationsResult(
            path: path,
            method: .patch,
            body: .object([
                "observability": .object([
                    "enabled": .bool(enabled),
                    "logs": .object([
                        "enabled": .bool(logsEnabled),
                        "invocation_logs": .bool(logsEnabled)
                    ]),
                    "traces": .object([
                        "enabled": .bool(tracesEnabled)
                    ])
                ])
            ])
        )
    }

    func fetchWorkerTails(accountID: String, scriptName: String) async throws -> [CloudflareWorkerTail] {
        try await workerOperationsResult(
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/tails"
        )
    }

    func createWorkerTail(accountID: String, scriptName: String) async throws -> CloudflareWorkerTail {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/tails"
        return try await workerOperationsResult(path: path, method: .post, body: .object([:]))
    }

    func deleteWorkerTail(accountID: String, scriptName: String, tailID: String) async throws {
        let path = workerOperationsScriptPath(accountID: accountID, scriptName: scriptName)
            + "/tails/\(workerOperationsPathSegment(tailID))"
        try await workerOperationsNoResult(path: path, method: .delete)
    }

    func fetchWorkerContent(accountID: String, scriptName: String) async throws -> CloudflareRawResponse {
        try await rawRequest(
            method: .get,
            path: workerOperationsScriptPath(accountID: accountID, scriptName: scriptName) + "/content/v2"
        )
    }

    // MARK: Request plumbing

    private func workerOperationsResult<Result: Decodable & Sendable>(
        path: String,
        method: CloudflareHTTPMethod = .get,
        query: [String: String] = [:],
        body: CloudflareJSONValue? = nil
    ) async throws -> Result {
        let response = try await rawRequest(
            method: method,
            path: path,
            query: query,
            bodyText: try workerOperationsBodyText(body),
            confirmation: method.isMutation
                ? CloudflareMutationConfirmation(confirmingResourceID: path)
                : nil
        )
        try workerOperationsValidate(response)

        do {
            let envelope = try JSONDecoder().decode(CloudflareWorkerOperationsEnvelope<Result>.self, from: response.data)
            guard envelope.success else {
                throw CloudflareAPIError.api(envelope.errors)
            }
            guard let result = envelope.result else {
                throw CloudflareAPIError.decoding("Cloudflare returned no Worker result.")
            }
            return result
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
    }

    private func workerOperationsNoResult(
        path: String,
        method: CloudflareHTTPMethod,
        body: CloudflareJSONValue? = nil
    ) async throws {
        let response = try await rawRequest(
            method: method,
            path: path,
            bodyText: try workerOperationsBodyText(body),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try workerOperationsValidate(response)

        guard !response.data.isEmpty else { return }
        if let envelope = try? JSONDecoder().decode(
            CloudflareWorkerOperationsEnvelope<CloudflareJSONValue>.self,
            from: response.data
        ), !envelope.success {
            throw CloudflareAPIError.api(envelope.errors)
        }
    }

    private func workerOperationsValidate(_ response: CloudflareRawResponse) throws {
        guard (200...299).contains(response.statusCode) else {
            let failure = try? JSONDecoder().decode(CloudflareWorkerOperationsFailure.self, from: response.data)
            let message = failure?.errors.map(\.message).filter { !$0.isEmpty }.joined(separator: "\n") ?? ""
            switch response.statusCode {
            case 401: throw CloudflareAPIError.invalidCredentials
            case 403: throw CloudflareAPIError.forbidden(message)
            default: throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: message)
            }
        }
    }

    private func workerOperationsBodyText(_ value: CloudflareJSONValue?) throws -> String? {
        guard let value else { return nil }
        do {
            let data = try JSONEncoder().encode(value)
            guard let string = String(data: data, encoding: .utf8) else {
                throw CloudflareAPIError.invalidRequest("The Worker request body is not UTF-8.")
            }
            return string
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.invalidRequest(error.localizedDescription)
        }
    }

    private func workerOperationsScriptPath(accountID: String, scriptName: String) -> String {
        "/accounts/\(workerOperationsPathSegment(accountID))/workers/scripts/\(workerOperationsPathSegment(scriptName))"
    }

    private func workerOperationsPathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

private nonisolated struct CloudflareWorkerOperationsEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let success: Bool
    let result: Result?
    let errors: [CloudflareAPIIssue]
    let resultInfo: CloudflareResultInfo?

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
        resultInfo = try container.decodeIfPresent(CloudflareResultInfo.self, forKey: .resultInfo)
    }

    private enum CodingKeys: String, CodingKey {
        case success
        case result
        case errors
        case resultInfo = "result_info"
    }
}

private nonisolated struct CloudflareWorkerOperationsFailure: Decodable, Sendable {
    let errors: [CloudflareAPIIssue]
}
