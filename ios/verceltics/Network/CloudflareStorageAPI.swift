import Foundation

private nonisolated struct CloudflareStorageEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let success: Bool
    let result: Result?
    let errors: [CloudflareAPIIssue]
    let resultInfo: CloudflareStorageResultInfo?

    enum CodingKeys: String, CodingKey {
        case success
        case result
        case errors
        case resultInfo = "result_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
        resultInfo = try container.decodeIfPresent(CloudflareStorageResultInfo.self, forKey: .resultInfo)
    }
}

private nonisolated struct CloudflareR2BucketList: Decodable, Sendable {
    let buckets: [CloudflareR2Bucket]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        buckets = try container.decodeIfPresent([CloudflareR2Bucket].self, forKey: .buckets) ?? []
    }

    private enum CodingKeys: String, CodingKey { case buckets }
}

private nonisolated struct CloudflareD1QueryInput: Encodable, Sendable {
    let sql: String
    let params: [CloudflareJSONValue]
}

extension CloudflareAPI {
    // MARK: D1

    func fetchD1Databases(accountID: String) async throws -> [CloudflareD1Database] {
        let path = "/accounts/\(cloudflareStoragePathSegment(accountID))/d1/database"
        var page = 1
        var databases: [CloudflareD1Database] = []
        var paginationGuard = CloudflarePaginationGuard()

        while true {
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: ["page": String(page), "per_page": "1000"]
            )
            let envelope: CloudflareStorageEnvelope<[CloudflareD1Database]> = try decodeStorageEnvelope(response)
            let batch = envelope.result ?? []
            try paginationGuard.record(batchCount: batch.count, signature: response.data.hashValue)
            databases.append(contentsOf: batch)

            guard !batch.isEmpty else { break }

            if let totalCount = envelope.resultInfo?.totalCount, databases.count < totalCount {
                page += 1
            } else if batch.count == 1000 {
                page += 1
            } else {
                break
            }
        }

        return databases
    }

    func fetchD1Database(accountID: String, databaseID: String) async throws -> CloudflareD1Database {
        let path = d1DatabasePath(accountID: accountID, databaseID: databaseID)
        let response = try await rawRequest(method: .get, path: path)
        return try storageResult(response)
    }

    func createD1Database(
        accountID: String,
        input: CloudflareD1CreateInput,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareD1Database {
        let name = input.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a D1 database name.")
        }
        try requireStorageConfirmation(confirmation, resourceID: name)

        let path = "/accounts/\(cloudflareStoragePathSegment(accountID))/d1/database"
        let response = try await rawRequest(
            method: .post,
            path: path,
            bodyText: try storageBody(input),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        return try storageResult(response)
    }

    func deleteD1Database(
        accountID: String,
        databaseID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireStorageConfirmation(confirmation, resourceID: databaseID)
        let path = d1DatabasePath(accountID: accountID, databaseID: databaseID)
        let response = try await rawRequest(
            method: .delete,
            path: path,
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try validateStorageMutationResponse(response)
    }

    func queryD1Database(
        accountID: String,
        databaseID: String,
        sql: String,
        params: [CloudflareJSONValue] = [],
        confirmation: CloudflareMutationConfirmation
    ) async throws -> [CloudflareD1QueryResult] {
        let statement = sql.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !statement.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a SQL statement.")
        }
        try requireStorageConfirmation(confirmation, resourceID: databaseID)

        let path = d1DatabasePath(accountID: accountID, databaseID: databaseID) + "/query"
        let input = CloudflareD1QueryInput(sql: statement, params: params)
        let response = try await rawRequest(
            method: .post,
            path: path,
            bodyText: try storageBody(input),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        return try storageResult(response)
    }

    // MARK: Workers KV

    func fetchKVNamespaces(accountID: String) async throws -> [CloudflareKVNamespace] {
        let path = kvNamespacesPath(accountID: accountID)
        var page = 1
        var namespaces: [CloudflareKVNamespace] = []
        var paginationGuard = CloudflarePaginationGuard()

        while true {
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: [
                    "page": String(page),
                    "per_page": "1000",
                    "order": "title",
                    "direction": "asc"
                ]
            )
            let envelope: CloudflareStorageEnvelope<[CloudflareKVNamespace]> = try decodeStorageEnvelope(response)
            let batch = envelope.result ?? []
            try paginationGuard.record(batchCount: batch.count, signature: response.data.hashValue)
            namespaces.append(contentsOf: batch)

            guard !batch.isEmpty else { break }

            if let totalCount = envelope.resultInfo?.totalCount, namespaces.count < totalCount {
                page += 1
            } else if batch.count == 1000 {
                page += 1
            } else {
                break
            }
        }

        return namespaces
    }

    func createKVNamespace(
        accountID: String,
        title: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareKVNamespace {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedTitle.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a KV namespace title.")
        }
        try requireStorageConfirmation(confirmation, resourceID: normalizedTitle)

        let path = kvNamespacesPath(accountID: accountID)
        let response = try await rawRequest(
            method: .post,
            path: path,
            bodyText: try storageBody(["title": normalizedTitle]),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        return try storageResult(response)
    }

    func renameKVNamespace(
        accountID: String,
        namespaceID: String,
        title: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareKVNamespace {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedTitle.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a KV namespace title.")
        }
        try requireStorageConfirmation(confirmation, resourceID: namespaceID)

        let path = kvNamespacePath(accountID: accountID, namespaceID: namespaceID)
        let response = try await rawRequest(
            method: .put,
            path: path,
            bodyText: try storageBody(["title": normalizedTitle]),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        return try storageResult(response)
    }

    func deleteKVNamespace(
        accountID: String,
        namespaceID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireStorageConfirmation(confirmation, resourceID: namespaceID)
        let path = kvNamespacePath(accountID: accountID, namespaceID: namespaceID)
        let response = try await rawRequest(
            method: .delete,
            path: path,
            bodyText: "{}",
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try validateStorageMutationResponse(response)
    }

    func fetchKVKeys(accountID: String, namespaceID: String, prefix: String? = nil) async throws -> [CloudflareKVKey] {
        let path = kvNamespacePath(accountID: accountID, namespaceID: namespaceID) + "/keys"
        var cursor: String?
        var seenCursors = Set<String>()
        var keys: [CloudflareKVKey] = []
        var paginationGuard = CloudflarePaginationGuard()

        repeat {
            var query = ["limit": "1000"]
            if let prefix, !prefix.isEmpty { query["prefix"] = prefix }
            if let cursor, !cursor.isEmpty { query["cursor"] = cursor }

            let response = try await rawRequest(method: .get, path: path, query: query)
            let envelope: CloudflareStorageEnvelope<[CloudflareKVKey]> = try decodeStorageEnvelope(response)
            let batch = envelope.result ?? []
            try paginationGuard.record(batchCount: batch.count, signature: response.data.hashValue)
            keys.append(contentsOf: batch)
            cursor = envelope.resultInfo?.cursor
            if let cursor, !cursor.isEmpty {
                guard seenCursors.insert(cursor).inserted else {
                    throw CloudflareAPIError.invalidRequest(
                        "Cloudflare repeated a KV cursor, so loading stopped safely."
                    )
                }
            }
        } while cursor?.isEmpty == false

        return keys
    }

    func readKVValue(accountID: String, namespaceID: String, key: String) async throws -> CloudflareKVValue {
        let path = kvValuePath(accountID: accountID, namespaceID: namespaceID, key: key)
        let response = try await rawRequest(method: .get, path: path)
        try throwForStorageHTTPFailure(response)
        return CloudflareKVValue(
            data: response.data,
            contentType: storageHeader("Content-Type", in: response.headers),
            expiration: storageHeader("Expiration", in: response.headers)
        )
    }

    func writeKVValue(
        accountID: String,
        namespaceID: String,
        key: String,
        data: Data,
        contentType: String = "application/octet-stream",
        expirationTTL: Int? = nil,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let normalizedKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedKey.isEmpty else {
            throw CloudflareAPIError.invalidRequest("Enter a KV key.")
        }
        if let expirationTTL, expirationTTL < 60 {
            throw CloudflareAPIError.invalidRequest("KV expiration TTL must be at least 60 seconds.")
        }
        try requireStorageConfirmation(confirmation, resourceID: normalizedKey)

        let path = kvValuePath(accountID: accountID, namespaceID: namespaceID, key: normalizedKey)
        var query: [String: String] = [:]
        if let expirationTTL { query["expiration_ttl"] = String(expirationTTL) }
        let response = try await rawRequest(
            method: .put,
            path: path,
            query: query,
            bodyText: data.base64EncodedString(),
            contentType: contentType,
            bodyEncoding: .base64,
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try validateStorageMutationResponse(response)
    }

    func deleteKVValue(
        accountID: String,
        namespaceID: String,
        key: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireStorageConfirmation(confirmation, resourceID: key)
        let path = kvValuePath(accountID: accountID, namespaceID: namespaceID, key: key)
        let response = try await rawRequest(
            method: .delete,
            path: path,
            bodyText: "{}",
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try validateStorageMutationResponse(response)
    }

    // MARK: R2 buckets

    func fetchR2Buckets(accountID: String) async throws -> [CloudflareR2Bucket] {
        let defaultBuckets = try await fetchR2Buckets(accountID: accountID, jurisdiction: nil)
        var buckets = defaultBuckets

        // Jurisdictional buckets live in separate API scopes. Unsupported scopes are optional.
        for jurisdiction in ["eu", "fedramp"] {
            do {
                let scoped = try await fetchR2Buckets(accountID: accountID, jurisdiction: jurisdiction)
                buckets.append(contentsOf: scoped)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                guard isOptionalProductUnavailable(error) else { throw error }
            }
        }

        var unique: [String: CloudflareR2Bucket] = [:]
        for bucket in buckets {
            unique["\(bucket.jurisdiction ?? "default")|\(bucket.name)"] = bucket
        }
        return Array(unique.values)
    }

    func fetchR2Bucket(accountID: String, bucketName: String, jurisdiction: String? = nil) async throws -> CloudflareR2Bucket {
        let path = r2BucketPath(accountID: accountID, bucketName: bucketName)
        let response = try await rawRequest(
            method: .get,
            path: path,
            headers: r2Headers(jurisdiction: jurisdiction)
        )
        let bucket: CloudflareR2Bucket = try storageResult(response)
        return storageR2Bucket(bucket, jurisdiction: jurisdiction)
    }

    func createR2Bucket(
        accountID: String,
        input: CloudflareR2CreateInput,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareR2Bucket {
        let name = input.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isValidR2BucketName(name) else {
            throw CloudflareAPIError.invalidRequest(
                "R2 bucket names must be 3–63 lowercase letters, numbers, or hyphens, and cannot start or end with a hyphen."
            )
        }
        try requireStorageConfirmation(confirmation, resourceID: name)

        let path = "/accounts/\(cloudflareStoragePathSegment(accountID))/r2/buckets"
        let response = try await rawRequest(
            method: .post,
            path: path,
            headers: r2Headers(jurisdiction: input.jurisdiction),
            bodyText: try storageBody(input),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        let bucket: CloudflareR2Bucket = try storageResult(response)
        return storageR2Bucket(bucket, jurisdiction: input.jurisdiction)
    }

    func deleteR2Bucket(
        accountID: String,
        bucketName: String,
        jurisdiction: String? = nil,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        try requireStorageConfirmation(confirmation, resourceID: bucketName)
        let path = r2BucketPath(accountID: accountID, bucketName: bucketName)
        let response = try await rawRequest(
            method: .delete,
            path: path,
            headers: r2Headers(jurisdiction: jurisdiction),
            confirmation: CloudflareMutationConfirmation(confirmingResourceID: path)
        )
        try validateStorageMutationResponse(response)
    }

    // MARK: Private helpers

    private func fetchR2Buckets(accountID: String, jurisdiction: String?) async throws -> [CloudflareR2Bucket] {
        let path = "/accounts/\(cloudflareStoragePathSegment(accountID))/r2/buckets"
        var cursor: String?
        var seenCursors = Set<String>()
        var buckets: [CloudflareR2Bucket] = []
        var paginationGuard = CloudflarePaginationGuard()

        repeat {
            var query = ["per_page": "1000", "order": "name", "direction": "asc"]
            if let cursor, !cursor.isEmpty { query["cursor"] = cursor }
            let response = try await rawRequest(
                method: .get,
                path: path,
                query: query,
                headers: r2Headers(jurisdiction: jurisdiction)
            )
            let envelope: CloudflareStorageEnvelope<CloudflareR2BucketList> = try decodeStorageEnvelope(response)
            let scopedBuckets = (envelope.result?.buckets ?? []).map {
                storageR2Bucket($0, jurisdiction: jurisdiction)
            }
            try paginationGuard.record(batchCount: scopedBuckets.count, signature: response.data.hashValue)
            buckets.append(contentsOf: scopedBuckets)
            cursor = envelope.resultInfo?.cursor
            if let cursor, !cursor.isEmpty {
                guard seenCursors.insert(cursor).inserted else {
                    throw CloudflareAPIError.invalidRequest(
                        "Cloudflare repeated an R2 cursor, so loading stopped safely."
                    )
                }
            }
        } while cursor?.isEmpty == false

        return buckets
    }

    private func d1DatabasePath(accountID: String, databaseID: String) -> String {
        "/accounts/\(cloudflareStoragePathSegment(accountID))/d1/database/\(cloudflareStoragePathSegment(databaseID))"
    }

    private func kvNamespacesPath(accountID: String) -> String {
        "/accounts/\(cloudflareStoragePathSegment(accountID))/storage/kv/namespaces"
    }

    private func kvNamespacePath(accountID: String, namespaceID: String) -> String {
        kvNamespacesPath(accountID: accountID) + "/\(cloudflareStoragePathSegment(namespaceID))"
    }

    private func kvValuePath(accountID: String, namespaceID: String, key: String) -> String {
        kvNamespacePath(accountID: accountID, namespaceID: namespaceID)
            + "/values/\(cloudflareStoragePathSegment(key))"
    }

    private func r2BucketPath(accountID: String, bucketName: String) -> String {
        "/accounts/\(cloudflareStoragePathSegment(accountID))/r2/buckets/\(cloudflareStoragePathSegment(bucketName))"
    }

    private func r2Headers(jurisdiction: String?) -> [String: String] {
        guard let jurisdiction, jurisdiction != "default" else { return [:] }
        return ["cf-r2-jurisdiction": jurisdiction]
    }

    private func storageR2Bucket(_ bucket: CloudflareR2Bucket, jurisdiction: String?) -> CloudflareR2Bucket {
        guard bucket.jurisdiction == nil, let jurisdiction else { return bucket }
        return CloudflareR2Bucket(
            name: bucket.name,
            creationDate: bucket.creationDate,
            jurisdiction: jurisdiction,
            location: bucket.location,
            storageClass: bucket.storageClass
        )
    }

    private func storageResult<T: Decodable & Sendable>(_ response: CloudflareRawResponse) throws -> T {
        let envelope: CloudflareStorageEnvelope<T> = try decodeStorageEnvelope(response)
        guard let result = envelope.result else {
            throw CloudflareAPIError.decoding("Cloudflare did not return the requested storage resource.")
        }
        return result
    }

    private func decodeStorageEnvelope<T: Decodable & Sendable>(
        _ response: CloudflareRawResponse
    ) throws -> CloudflareStorageEnvelope<T> {
        try throwForStorageHTTPFailure(response)

        let envelope: CloudflareStorageEnvelope<T>
        do {
            envelope = try JSONDecoder().decode(CloudflareStorageEnvelope<T>.self, from: response.data)
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }

        guard envelope.success else {
            if !envelope.errors.isEmpty { throw CloudflareAPIError.api(envelope.errors) }
            throw CloudflareAPIError.requestFailed(
                statusCode: response.statusCode,
                message: "Cloudflare reported an unsuccessful storage request."
            )
        }
        return envelope
    }

    private func validateStorageMutationResponse(_ response: CloudflareRawResponse) throws {
        try throwForStorageHTTPFailure(response)
        guard !response.data.isEmpty else { return }

        do {
            let envelope = try JSONDecoder().decode(
                CloudflareStorageEnvelope<CloudflareJSONValue>.self,
                from: response.data
            )
            guard envelope.success else {
                if !envelope.errors.isEmpty { throw CloudflareAPIError.api(envelope.errors) }
                throw CloudflareAPIError.requestFailed(
                    statusCode: response.statusCode,
                    message: "Cloudflare reported an unsuccessful storage change."
                )
            }
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
    }

    private func throwForStorageHTTPFailure(_ response: CloudflareRawResponse) throws {
        guard !(200...299).contains(response.statusCode) else { return }
        let failure = try? JSONDecoder().decode(
            CloudflareStorageEnvelope<CloudflareJSONValue>.self,
            from: response.data
        )
        let issues = failure?.errors ?? []
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

    private func storageBody<T: Encodable>(_ body: T) throws -> String {
        do {
            let data = try JSONEncoder().encode(body)
            guard let string = String(data: data, encoding: .utf8) else {
                throw CloudflareAPIError.invalidRequest("The storage request body is not valid UTF-8.")
            }
            return string
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.invalidRequest(error.localizedDescription)
        }
    }

    private func requireStorageConfirmation(
        _ confirmation: CloudflareMutationConfirmation,
        resourceID: String
    ) throws {
        guard confirmation.resourceID == resourceID else {
            throw CloudflareAPIError.confirmationRequired(resourceID)
        }
    }

    private func storageHeader(_ name: String, in headers: [String: String]) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    private func isValidR2BucketName(_ name: String) -> Bool {
        guard (3...63).contains(name.count),
              name.first != "-", name.last != "-" else { return false }
        return name.allSatisfy { $0.isLowercase || $0.isNumber || $0 == "-" }
    }
}

private nonisolated func cloudflareStoragePathSegment(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}

private extension CloudflareR2Bucket {
    nonisolated init(name: String, creationDate: String?, jurisdiction: String?, location: String?, storageClass: String?) {
        self.name = name
        self.creationDate = creationDate
        self.jurisdiction = jurisdiction
        self.location = location
        self.storageClass = storageClass
    }
}
