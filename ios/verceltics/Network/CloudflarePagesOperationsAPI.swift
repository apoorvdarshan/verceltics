import Foundation

extension CloudflareAPI {
    // MARK: Project

    func pagesOperationsFetchProject(
        accountID: String,
        projectName: String
    ) async throws -> CloudflarePagesOperationsProject {
        try await pagesOperationsResult(
            method: .get,
            path: pagesOperationsProjectPath(accountID: accountID, projectName: projectName)
        )
    }

    func pagesOperationsUpdateProject(
        accountID: String,
        projectName: String,
        update: CloudflarePagesProjectUpdateRequest,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflarePagesOperationsProject {
        let path = pagesOperationsProjectPath(accountID: accountID, projectName: projectName)
        return try await pagesOperationsResult(
            method: .patch,
            path: path,
            bodyText: try pagesOperationsEncode(update),
            confirmation: confirmation
        )
    }

    func pagesOperationsPurgeBuildCache(
        accountID: String,
        projectName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let path = pagesOperationsProjectPath(accountID: accountID, projectName: projectName) + "/purge_build_cache"
        try await pagesOperationsSuccess(method: .post, path: path, confirmation: confirmation)
    }

    func pagesOperationsDeleteProject(
        accountID: String,
        projectName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let path = pagesOperationsProjectPath(accountID: accountID, projectName: projectName)
        try await pagesOperationsSuccess(method: .delete, path: path, confirmation: confirmation)
    }

    nonisolated func pagesOperationsDirectUploadPreparation(
        accountID: String,
        projectName: String
    ) -> CloudflarePagesDirectUploadPreparation {
        CloudflarePagesDirectUploadPreparation.make(
            accountID: pagesOperationsEscapedSegment(accountID),
            projectName: pagesOperationsEscapedSegment(projectName)
        )
    }

    // MARK: Custom domains

    func pagesOperationsFetchDomains(
        accountID: String,
        projectName: String
    ) async throws -> [CloudflarePagesCustomDomain] {
        let path = pagesOperationsDomainsPath(accountID: accountID, projectName: projectName)
        var page = 1
        var domains: [CloudflarePagesCustomDomain] = []

        while true {
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: ["page": String(page), "per_page": "20"]
            )
            let envelope: CloudflarePagesOperationsEnvelope<[CloudflarePagesCustomDomain]> = try pagesOperationsDecode(response)
            try pagesOperationsValidate(envelope: envelope, response: response)
            let batch = envelope.result ?? []
            domains.append(contentsOf: batch)

            if let totalPages = envelope.resultInfo?.totalPages {
                guard page < totalPages else { break }
            } else if let totalCount = envelope.resultInfo?.totalCount {
                guard domains.count < totalCount else { break }
            } else if batch.count < 20 {
                break
            }
            page += 1
        }
        return domains
    }

    func pagesOperationsFetchDomain(
        accountID: String,
        projectName: String,
        domainName: String
    ) async throws -> CloudflarePagesCustomDomain {
        try await pagesOperationsResult(
            method: .get,
            path: pagesOperationsDomainPath(accountID: accountID, projectName: projectName, domainName: domainName)
        )
    }

    func pagesOperationsAddDomain(
        accountID: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflarePagesCustomDomain {
        struct AddDomainBody: Encodable, Sendable { let name: String }

        let path = pagesOperationsDomainsPath(accountID: accountID, projectName: projectName)
        return try await pagesOperationsResult(
            method: .post,
            path: path,
            bodyText: try pagesOperationsEncode(AddDomainBody(name: domainName)),
            confirmation: confirmation
        )
    }

    func pagesOperationsRetryDomainValidation(
        accountID: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflarePagesCustomDomain {
        let path = pagesOperationsDomainPath(accountID: accountID, projectName: projectName, domainName: domainName)
        return try await pagesOperationsResult(method: .patch, path: path, confirmation: confirmation)
    }

    func pagesOperationsDeleteDomain(
        accountID: String,
        projectName: String,
        domainName: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let path = pagesOperationsDomainPath(accountID: accountID, projectName: projectName, domainName: domainName)
        try await pagesOperationsSuccess(method: .delete, path: path, confirmation: confirmation)
    }

    // MARK: Raw request bridge

    private func pagesOperationsResult<Result: Decodable & Sendable>(
        method: CloudflareHTTPMethod,
        path: String,
        bodyText: String? = nil,
        confirmation: CloudflareMutationConfirmation? = nil
    ) async throws -> Result {
        let response = try await rawRequest(
            method: method,
            path: path,
            bodyText: bodyText,
            confirmation: confirmation
        )
        let envelope: CloudflarePagesOperationsEnvelope<Result> = try pagesOperationsDecode(response)
        try pagesOperationsValidate(envelope: envelope, response: response)
        guard let result = envelope.result else {
            throw CloudflareAPIError.decoding("Cloudflare Pages returned no result.")
        }
        return result
    }

    private func pagesOperationsSuccess(
        method: CloudflareHTTPMethod,
        path: String,
        bodyText: String? = nil,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let response = try await rawRequest(
            method: method,
            path: path,
            bodyText: bodyText,
            confirmation: confirmation
        )
        let envelope: CloudflarePagesOperationsEnvelope<CloudflareJSONValue> = try pagesOperationsDecode(response)
        try pagesOperationsValidate(envelope: envelope, response: response)
    }

    private func pagesOperationsDecode<Result: Decodable & Sendable>(
        _ response: CloudflareRawResponse
    ) throws -> CloudflarePagesOperationsEnvelope<Result> {
        do {
            return try JSONDecoder().decode(CloudflarePagesOperationsEnvelope<Result>.self, from: response.data)
        } catch {
            if !(200...299).contains(response.statusCode) {
                throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: response.text)
            }
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
    }

    private func pagesOperationsValidate<Result: Decodable & Sendable>(
        envelope: CloudflarePagesOperationsEnvelope<Result>,
        response: CloudflareRawResponse
    ) throws {
        guard (200...299).contains(response.statusCode), envelope.success else {
            if !envelope.errors.isEmpty {
                throw CloudflareAPIError.api(envelope.errors)
            }
            throw CloudflareAPIError.requestFailed(
                statusCode: response.statusCode,
                message: "Cloudflare Pages rejected the request."
            )
        }
    }

    private func pagesOperationsEncode<Value: Encodable>(_ value: Value) throws -> String {
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(value)
            guard let text = String(data: data, encoding: .utf8) else {
                throw CloudflareAPIError.invalidRequest("The Pages request could not be represented as UTF-8.")
            }
            return text
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.invalidRequest("The Pages request could not be encoded: \(error.localizedDescription)")
        }
    }

    private nonisolated func pagesOperationsProjectPath(accountID: String, projectName: String) -> String {
        "/accounts/\(pagesOperationsEscapedSegment(accountID))/pages/projects/\(pagesOperationsEscapedSegment(projectName))"
    }

    private nonisolated func pagesOperationsDomainsPath(accountID: String, projectName: String) -> String {
        pagesOperationsProjectPath(accountID: accountID, projectName: projectName) + "/domains"
    }

    private nonisolated func pagesOperationsDomainPath(
        accountID: String,
        projectName: String,
        domainName: String
    ) -> String {
        pagesOperationsDomainsPath(accountID: accountID, projectName: projectName)
            + "/\(pagesOperationsEscapedSegment(domainName))"
    }
}

private nonisolated struct CloudflarePagesOperationsEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let result: Result?
    let success: Bool
    let errors: [CloudflareAPIIssue]
    let resultInfo: CloudflareResultInfo?

    enum CodingKeys: String, CodingKey {
        case result, success, errors
        case resultInfo = "result_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
        resultInfo = try container.decodeIfPresent(CloudflareResultInfo.self, forKey: .resultInfo)
    }
}

private nonisolated func pagesOperationsEscapedSegment(_ value: String) -> String {
    var allowed = CharacterSet.urlPathAllowed
    allowed.remove(charactersIn: "/?#")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}
