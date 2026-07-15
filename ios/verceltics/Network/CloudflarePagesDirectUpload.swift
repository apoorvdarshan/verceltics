import Foundation
import UniformTypeIdentifiers

nonisolated struct CloudflarePagesDirectUploadOptions: Equatable, Sendable {
    let branch: String?
    let commitMessage: String?

    init(branch: String? = nil, commitMessage: String? = nil) {
        let normalizedBranch = branch?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let normalizedMessage = commitMessage?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.branch = normalizedBranch.isEmpty ? nil : normalizedBranch
        self.commitMessage = normalizedMessage.isEmpty ? nil : normalizedMessage
    }
}

nonisolated struct CloudflarePagesDirectUploadResult: Equatable, Sendable {
    let deployment: CloudflarePagesDeployment
    let assetCount: Int
    let uploadedAssetCount: Int
    let reusedAssetCount: Int
}

nonisolated struct CloudflarePagesDirectUploadProgress: Equatable, Sendable {
    enum Stage: Equatable, Sendable {
        case authorizing
        case hashing
        case checking
        case uploading
        case deploying
    }

    let stage: Stage
    let completed: Int
    let total: Int

    var fractionCompleted: Double? {
        guard total > 0 else { return nil }
        return min(1, max(0, Double(completed) / Double(total)))
    }

    var message: String {
        switch stage {
        case .authorizing:
            "Authorizing the Pages upload…"
        case .hashing:
            total > 0 ? "Preparing files · \(completed) of \(total)" : "Reading the build folder…"
        case .checking:
            "Checking which assets Cloudflare already has…"
        case .uploading:
            "Uploading assets · \(completed) of \(total)"
        case .deploying:
            "Creating the Pages deployment…"
        }
    }
}

extension CloudflareAPI {
    /// Uploads a prebuilt folder with the same three-phase contract used by
    /// Wrangler: obtain a project upload JWT, upload only missing BLAKE3 assets,
    /// then create a deployment containing the completed manifest.
    func pagesOperationsDirectUpload(
        accountID: String,
        projectName: String,
        directoryURL: URL,
        options: CloudflarePagesDirectUploadOptions,
        confirmation: CloudflareMutationConfirmation,
        progress: @escaping @Sendable (CloudflarePagesDirectUploadProgress) -> Void = { _ in }
    ) async throws -> CloudflarePagesDirectUploadResult {
        let projectPath = "/accounts/\(pagesDirectUploadEscapedSegment(accountID))/pages/projects/\(pagesDirectUploadEscapedSegment(projectName))"
        let deploymentPath = projectPath + "/deployments"
        guard confirmation.resourceID == deploymentPath else {
            throw CloudflareAPIError.confirmationRequired(deploymentPath)
        }

        progress(.init(stage: .authorizing, completed: 0, total: 0))
        var uploadJWT = try await pagesDirectUploadFetchToken(projectPath: projectPath)
        let fileLimit = CloudflarePagesUploadTokenClaims.maximumFileCount(from: uploadJWT)
        let package = try CloudflarePagesUploadPackage.prepare(
            directoryURL: directoryURL,
            fileLimit: fileLimit,
            progress: progress
        )

        try Task.checkCancellation()
        progress(.init(stage: .checking, completed: 0, total: package.assets.count))
        let checkBody = try JSONEncoder().encode(
            CloudflarePagesHashList(hashes: package.uniqueAssets.map(\.hash))
        )
        let missingResponse = try await pagesDirectUploadAssetRequest(
            path: "/pages/assets/check-missing",
            body: checkBody,
            jwt: uploadJWT,
            projectPath: projectPath
        )
        uploadJWT = missingResponse.jwt
        let missingHashes: [String] = try pagesDirectUploadResult(
            from: missingResponse.response,
            as: [String].self
        )
        let missingSet = Set(missingHashes)
        let filesToUpload = package.uniqueAssets
            .filter { missingSet.contains($0.hash) }
            .sorted { lhs, rhs in
                if lhs.size == rhs.size { return lhs.relativePath < rhs.relativePath }
                return lhs.size > rhs.size
            }
        let reusedCount = package.uniqueAssets.count - filesToUpload.count

        var uploadedCount = 0
        for bucket in CloudflarePagesUploadPackage.buckets(for: filesToUpload) {
            try Task.checkCancellation()
            let payload = try bucket.map { file in
                let data = try CloudflarePagesUploadPackage.readFile(file.url, maximumBytes: file.size)
                return CloudflarePagesAssetPayload(
                    key: file.hash,
                    value: data.base64EncodedString(),
                    metadata: .init(contentType: file.contentType),
                    base64: true
                )
            }
            let body = try JSONEncoder().encode(payload)
            let uploadResponse = try await pagesDirectUploadAssetRequest(
                path: "/pages/assets/upload",
                body: body,
                jwt: uploadJWT,
                projectPath: projectPath
            )
            uploadJWT = uploadResponse.jwt
            try pagesDirectUploadValidate(uploadResponse.response)
            uploadedCount += bucket.count
            progress(
                .init(
                    stage: .uploading,
                    completed: reusedCount + uploadedCount,
                    total: package.uniqueAssets.count
                )
            )
        }

        // This cache update is an optimization only. A deployment remains valid
        // if Cloudflare accepts every uploaded asset but this endpoint is briefly
        // unavailable, which is also how Wrangler treats it.
        do {
            let body = try JSONEncoder().encode(
                CloudflarePagesHashList(hashes: package.uniqueAssets.map(\.hash))
            )
            let response = try await pagesDirectUploadAssetRequest(
                path: "/pages/assets/upsert-hashes",
                body: body,
                jwt: uploadJWT,
                projectPath: projectPath,
                maximumAttempts: 2
            )
            try pagesDirectUploadValidate(response.response)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Asset bodies were already accepted; continue to deployment.
        }

        try Task.checkCancellation()
        progress(
            .init(
                stage: .deploying,
                completed: package.uniqueAssets.count,
                total: package.uniqueAssets.count
            )
        )
        let manifest = Dictionary(
            uniqueKeysWithValues: package.assets.map { ("/" + $0.relativePath, $0.hash) }
        )
        let manifestData = try JSONSerialization.data(
            withJSONObject: manifest,
            options: [.sortedKeys, .withoutEscapingSlashes]
        )
        guard let manifestText = String(data: manifestData, encoding: .utf8) else {
            throw CloudflareAPIError.invalidRequest("The Pages manifest could not be encoded as UTF-8.")
        }

        var multipart = CloudflarePagesMultipartBody()
        multipart.appendText(name: "manifest", value: manifestText)
        if let branch = options.branch {
            multipart.appendText(name: "branch", value: branch)
        }
        if let commitMessage = options.commitMessage {
            multipart.appendText(
                name: "commit_message",
                value: CloudflarePagesUploadPackage.truncateUTF8(commitMessage, maximumBytes: 384)
            )
        }
        multipart.appendText(name: "commit_dirty", value: "false")
        for file in package.specialFiles {
            multipart.appendFile(
                name: file.fieldName,
                fileName: file.fileName,
                contentType: file.contentType,
                data: file.data
            )
        }
        let multipartBody = multipart.finalized()
        let rawResponse = try await rawRequest(
            method: .post,
            path: deploymentPath,
            bodyText: multipartBody.data.base64EncodedString(),
            contentType: multipartBody.contentType,
            bodyEncoding: .base64,
            confirmation: confirmation
        )
        let deployment: CloudflarePagesDeployment = try pagesDirectUploadResult(
            from: rawResponse,
            as: CloudflarePagesDeployment.self
        )

        return .init(
            deployment: deployment,
            assetCount: package.assets.count,
            uploadedAssetCount: uploadedCount,
            reusedAssetCount: reusedCount
        )
    }

    private func pagesDirectUploadFetchToken(projectPath: String) async throws -> String {
        let response = try await rawRequest(method: .get, path: projectPath + "/upload-token")
        let result: CloudflarePagesUploadToken = try pagesDirectUploadResult(
            from: response,
            as: CloudflarePagesUploadToken.self
        )
        let jwt = result.jwt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !jwt.isEmpty, !jwt.contains("\r"), !jwt.contains("\n") else {
            throw CloudflareAPIError.decoding("Cloudflare returned an invalid Pages upload token.")
        }
        return jwt
    }

    private func pagesDirectUploadAssetRequest(
        path: String,
        body: Data,
        jwt initialJWT: String,
        projectPath: String,
        maximumAttempts: Int = 4
    ) async throws -> (response: CloudflareRawResponse, jwt: String) {
        var jwt = initialJWT
        var lastError: Error?

        for attempt in 0..<maximumAttempts {
            try Task.checkCancellation()
            do {
                let response = try await pagesAuthenticatedAssetRequest(path: path, jwt: jwt, body: body)
                return (response, jwt)
            } catch is CancellationError {
                throw CancellationError()
            } catch CloudflareAPIError.invalidCredentials {
                lastError = CloudflareAPIError.invalidCredentials
                jwt = try await pagesDirectUploadFetchToken(projectPath: projectPath)
            } catch {
                lastError = error
                guard attempt + 1 < maximumAttempts, pagesDirectUploadIsRetryable(error) else {
                    throw error
                }
            }

            if attempt + 1 < maximumAttempts {
                let delay = UInt64(1 << min(attempt, 3)) * 1_000_000_000
                try await Task.sleep(nanoseconds: delay)
            }
        }

        throw lastError ?? CloudflareAPIError.network("The Pages asset upload failed.")
    }

    private nonisolated func pagesDirectUploadIsRetryable(_ error: Error) -> Bool {
        guard case let CloudflareAPIError.requestFailed(statusCode, _) = error else { return false }
        return statusCode == 408 || statusCode == 429 || (500...599).contains(statusCode)
    }

    private nonisolated func pagesDirectUploadResult<Result: Decodable & Sendable>(
        from response: CloudflareRawResponse,
        as type: Result.Type
    ) throws -> Result {
        let envelope: CloudflarePagesUploadEnvelope<Result>
        do {
            envelope = try JSONDecoder().decode(CloudflarePagesUploadEnvelope<Result>.self, from: response.data)
        } catch {
            if !(200...299).contains(response.statusCode) {
                throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: response.text)
            }
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
        guard (200...299).contains(response.statusCode), envelope.success else {
            if !envelope.errors.isEmpty { throw CloudflareAPIError.api(envelope.errors) }
            throw CloudflareAPIError.requestFailed(
                statusCode: response.statusCode,
                message: "Cloudflare Pages rejected the upload request."
            )
        }
        guard let result = envelope.result else {
            throw CloudflareAPIError.decoding("Cloudflare Pages returned no upload result.")
        }
        return result
    }

    private nonisolated func pagesDirectUploadValidate(_ response: CloudflareRawResponse) throws {
        let envelope: CloudflarePagesUploadEnvelope<CloudflareJSONValue>
        do {
            envelope = try JSONDecoder().decode(CloudflarePagesUploadEnvelope<CloudflareJSONValue>.self, from: response.data)
        } catch {
            if (200...299).contains(response.statusCode), response.data.isEmpty { return }
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
        guard (200...299).contains(response.statusCode), envelope.success else {
            if !envelope.errors.isEmpty { throw CloudflareAPIError.api(envelope.errors) }
            throw CloudflareAPIError.requestFailed(
                statusCode: response.statusCode,
                message: "Cloudflare Pages rejected the asset upload."
            )
        }
    }
}

nonisolated enum CloudflarePagesUploadTokenClaims {
    static let defaultMaximumFileCount = 20_000
    static let absoluteMaximumFileCount = 100_000

    static func maximumFileCount(from token: String) -> Int {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2 else { return defaultMaximumFileCount }
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        payload += String(repeating: "=", count: (4 - payload.count % 4) % 4)
        guard let data = Data(base64Encoded: payload),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawValue = object["max_file_count_allowed"] as? NSNumber else {
            return defaultMaximumFileCount
        }
        return min(absoluteMaximumFileCount, max(1, rawValue.intValue))
    }
}

nonisolated enum CloudflarePagesAssetHasher {
    static func blake3Hex(_ data: Data) -> String {
        CloudflareBLAKE3.hash(data)
            .map { String(format: "%02x", $0) }
            .joined()
    }

    static func assetHash(data: Data, fileExtension: String) -> String {
        var input = data.base64EncodedData()
        input.append(contentsOf: fileExtension.utf8)
        return CloudflareBLAKE3.hash(input)
            .prefix(16)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

nonisolated struct CloudflarePagesMultipartBody {
    private let boundary: String
    private var data = Data()

    init(boundary: String = "Verceltics-\(UUID().uuidString)") {
        self.boundary = boundary
    }

    mutating func appendText(name: String, value: String) {
        appendBoundary()
        data.appendUTF8("Content-Disposition: form-data; name=\"\(safe(name))\"\r\n\r\n")
        data.appendUTF8(value)
        data.appendUTF8("\r\n")
    }

    mutating func appendFile(name: String, fileName: String, contentType: String, data fileData: Data) {
        appendBoundary()
        data.appendUTF8(
            "Content-Disposition: form-data; name=\"\(safe(name))\"; filename=\"\(safe(fileName))\"\r\n"
        )
        data.appendUTF8("Content-Type: \(safeContentType(contentType))\r\n\r\n")
        data.append(fileData)
        data.appendUTF8("\r\n")
    }

    mutating func finalized() -> (data: Data, contentType: String) {
        data.appendUTF8("--\(boundary)--\r\n")
        return (data, "multipart/form-data; boundary=\(boundary)")
    }

    private mutating func appendBoundary() {
        data.appendUTF8("--\(boundary)\r\n")
    }

    private func safe(_ value: String) -> String {
        value.replacingOccurrences(of: "\"", with: "_")
            .replacingOccurrences(of: "\r", with: "_")
            .replacingOccurrences(of: "\n", with: "_")
    }

    private func safeContentType(_ value: String) -> String {
        let sanitized = value.replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
        return sanitized.isEmpty ? "application/octet-stream" : sanitized
    }
}

private nonisolated struct CloudflarePagesUploadPackage: Sendable {
    static let maximumAssetBytes = 25 * 1_024 * 1_024
    static let maximumBucketBytes = 40 * 1_024 * 1_024
    static let maximumBucketFiles = 2_000

    let assets: [CloudflarePagesPreparedAsset]
    let specialFiles: [CloudflarePagesPreparedSpecialFile]

    var uniqueAssets: [CloudflarePagesPreparedAsset] {
        var seen = Set<String>()
        return assets.filter { seen.insert($0.hash).inserted }
    }

    static func prepare(
        directoryURL: URL,
        fileLimit: Int,
        progress: @escaping @Sendable (CloudflarePagesDirectUploadProgress) -> Void
    ) throws -> Self {
        let values = try directoryURL.resourceValues(forKeys: [.isDirectoryKey])
        guard values.isDirectory == true else {
            throw CloudflareAPIError.invalidRequest("Choose a folder containing a prebuilt Pages site.")
        }

        let fileManager = FileManager.default
        let workerJSURL = directoryURL.appendingPathComponent("_worker.js", isDirectory: false)
        if fileManager.fileExists(atPath: workerJSURL.path) {
            let workerValues = try workerJSURL.resourceValues(forKeys: [.isDirectoryKey])
            let kind = workerValues.isDirectory == true ? "directory" : "file"
            throw CloudflareAPIError.invalidRequest(
                "This build contains an _worker.js \(kind). Cloudflare's current direct-upload contract requires it to be bundled into _worker.bundle with Wrangler first."
            )
        }

        let standardSpecialNames = ["_headers", "_redirects", "_worker.bundle"]
        var specialFiles: [CloudflarePagesPreparedSpecialFile] = []
        for name in standardSpecialNames {
            let url = directoryURL.appendingPathComponent(name, isDirectory: false)
            guard fileManager.fileExists(atPath: url.path) else { continue }
            let data = try readFile(url, maximumBytes: maximumAssetBytes)
            specialFiles.append(
                .init(
                    fieldName: name,
                    fileName: name,
                    contentType: contentType(for: name),
                    data: data
                )
            )
        }

        let hasWorkerArtifact = specialFiles.contains { $0.fieldName == "_worker.bundle" }
        if hasWorkerArtifact {
            for name in ["_routes.json", "functions-filepath-routing-config.json"] {
                let url = directoryURL.appendingPathComponent(name, isDirectory: false)
                guard fileManager.fileExists(atPath: url.path) else { continue }
                let data = try readFile(url, maximumBytes: maximumAssetBytes)
                specialFiles.append(
                    .init(
                        fieldName: name,
                        fileName: name,
                        contentType: contentType(for: name),
                        data: data
                    )
                )
            }
        }

        let functionsURL = directoryURL.appendingPathComponent("functions", isDirectory: true)
        let hasFunctionsDirectory = (try? functionsURL.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
        if hasFunctionsDirectory && !hasWorkerArtifact {
            throw CloudflareAPIError.invalidRequest(
                "This folder contains Pages Functions source. Build it with Wrangler first and include the generated _worker.bundle before uploading from the app."
            )
        }

        let resourceKeys: Set<URLResourceKey> = [
            .isRegularFileKey, .isDirectoryKey, .isSymbolicLinkKey, .fileSizeKey
        ]
        var enumerationError: Error?
        guard let enumerator = fileManager.enumerator(
            at: directoryURL,
            includingPropertiesForKeys: Array(resourceKeys),
            options: [],
            errorHandler: { _, error in
                enumerationError = error
                return false
            }
        ) else {
            throw CloudflareAPIError.invalidRequest("The selected build folder could not be read.")
        }

        var candidates: [(url: URL, relativePath: String, size: Int)] = []
        while let url = enumerator.nextObject() as? URL {
            try Task.checkCancellation()
            let relativePath = relativePath(for: url, under: directoryURL)
            guard !relativePath.isEmpty else { continue }
            let values = try url.resourceValues(forKeys: resourceKeys)

            if shouldIgnore(relativePath: relativePath) {
                if values.isDirectory == true { enumerator.skipDescendants() }
                continue
            }
            if values.isSymbolicLink == true {
                if values.isDirectory == true { enumerator.skipDescendants() }
                continue
            }
            guard values.isRegularFile == true else { continue }
            let size = values.fileSize ?? 0
            guard size <= maximumAssetBytes else {
                let formatted = ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
                throw CloudflareAPIError.invalidRequest(
                    "Pages supports files up to 25 MiB. \(relativePath) is \(formatted)."
                )
            }
            candidates.append((url, relativePath, size))
            guard candidates.count <= fileLimit else {
                throw CloudflareAPIError.invalidRequest(
                    "This Pages plan allows up to \(fileLimit.formatted()) files per deployment."
                )
            }
        }
        if let enumerationError {
            throw CloudflareAPIError.invalidRequest(
                "The selected build folder could not be read completely: \(enumerationError.localizedDescription)"
            )
        }
        candidates.sort { $0.relativePath < $1.relativePath }

        guard !candidates.isEmpty || !specialFiles.isEmpty else {
            throw CloudflareAPIError.invalidRequest("The selected build folder contains no deployable files.")
        }

        var assets: [CloudflarePagesPreparedAsset] = []
        assets.reserveCapacity(candidates.count)
        progress(.init(stage: .hashing, completed: 0, total: candidates.count))
        for (index, candidate) in candidates.enumerated() {
            try Task.checkCancellation()
            let data = try readFile(candidate.url, maximumBytes: maximumAssetBytes)
            assets.append(
                .init(
                    url: candidate.url,
                    relativePath: candidate.relativePath,
                    size: candidate.size,
                    contentType: contentType(for: candidate.relativePath),
                    hash: CloudflarePagesAssetHasher.assetHash(
                        data: data,
                        fileExtension: candidate.url.pathExtension
                    )
                )
            )
            progress(.init(stage: .hashing, completed: index + 1, total: candidates.count))
        }

        return .init(assets: assets, specialFiles: specialFiles)
    }

    static func buckets(for files: [CloudflarePagesPreparedAsset]) -> [[CloudflarePagesPreparedAsset]] {
        var buckets: [[CloudflarePagesPreparedAsset]] = []
        var remainingBytes: [Int] = []
        var offset = 0

        for file in files {
            var inserted = false
            for indexOffset in 0..<buckets.count {
                let index = (indexOffset + offset) % buckets.count
                if remainingBytes[index] >= file.size, buckets[index].count < maximumBucketFiles {
                    buckets[index].append(file)
                    remainingBytes[index] -= file.size
                    inserted = true
                    break
                }
            }
            if !inserted {
                buckets.append([file])
                remainingBytes.append(maximumBucketBytes - file.size)
            }
            offset += 1
        }
        return buckets
    }

    static func readFile(_ url: URL, maximumBytes: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true else {
            throw CloudflareAPIError.invalidRequest("\(url.lastPathComponent) is not a regular file.")
        }
        if let size = values.fileSize, size > maximumBytes {
            throw CloudflareAPIError.invalidRequest("\(url.lastPathComponent) exceeds the Pages 25 MiB file limit.")
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        guard data.count <= maximumBytes else {
            throw CloudflareAPIError.invalidRequest("\(url.lastPathComponent) exceeds the Pages 25 MiB file limit.")
        }
        return data
    }

    static func truncateUTF8(_ value: String, maximumBytes: Int) -> String {
        guard value.lengthOfBytes(using: .utf8) > maximumBytes else { return value }
        var result = ""
        var bytes = 0
        for character in value {
            let text = String(character)
            let count = text.lengthOfBytes(using: .utf8)
            guard bytes + count <= maximumBytes else { break }
            result.append(character)
            bytes += count
        }
        return result
    }

    private static func relativePath(for url: URL, under root: URL) -> String {
        let rootPath = root.standardizedFileURL.path(percentEncoded: false)
        let filePath = url.standardizedFileURL.path(percentEncoded: false)
        guard filePath.hasPrefix(rootPath) else { return "" }
        return filePath.dropFirst(rootPath.count).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private static func shouldIgnore(relativePath: String) -> Bool {
        let components = relativePath.split(separator: "/").map(String.init)
        guard !components.isEmpty else { return true }
        if components.contains(".git") || components.contains(".wrangler") || components.contains("node_modules") {
            return true
        }
        if components.last == ".DS_Store" { return true }
        if components.count == 1 {
            return [
                "_headers", "_redirects", "_routes.json", "_worker.js", "_worker.bundle",
                "functions-filepath-routing-config.json", "functions"
            ].contains(components[0])
        }
        return false
    }

    private static func contentType(for path: String) -> String {
        let fileExtension = URL(fileURLWithPath: path).pathExtension
        return UTType(filenameExtension: fileExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}

private nonisolated struct CloudflarePagesPreparedAsset: Sendable {
    let url: URL
    let relativePath: String
    let size: Int
    let contentType: String
    let hash: String
}

private nonisolated struct CloudflarePagesPreparedSpecialFile: Sendable {
    let fieldName: String
    let fileName: String
    let contentType: String
    let data: Data
}

private nonisolated struct CloudflarePagesUploadToken: Decodable, Sendable {
    let jwt: String
}

private nonisolated struct CloudflarePagesHashList: Encodable, Sendable {
    let hashes: [String]
}

private nonisolated struct CloudflarePagesAssetPayload: Encodable, Sendable {
    let key: String
    let value: String
    let metadata: Metadata
    let base64: Bool

    struct Metadata: Encodable, Sendable {
        let contentType: String
    }
}

private nonisolated struct CloudflarePagesUploadEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let result: Result?
    let success: Bool
    let errors: [CloudflareAPIIssue]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case result, success, errors
    }
}

private nonisolated func pagesDirectUploadEscapedSegment(_ value: String) -> String {
    var allowed = CharacterSet.urlPathAllowed
    allowed.remove(charactersIn: "/?#")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
}

private nonisolated enum CloudflareBLAKE3 {
    private static let chunkLength = 1_024
    private static let blockLength = 64
    private static let chunkStart: UInt32 = 1
    private static let chunkEnd: UInt32 = 2
    private static let parent: UInt32 = 4
    private static let root: UInt32 = 8
    private static let initializationVector: [UInt32] = [
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
    ]
    private static let messagePermutation = [2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8]

    static func hash(_ data: Data) -> [UInt8] {
        let chunkCount = max(1, (data.count + chunkLength - 1) / chunkLength)
        var chainingValueStack: [[UInt32]] = []

        if chunkCount > 1 {
            for chunkIndex in 0..<(chunkCount - 1) {
                let start = chunkIndex * chunkLength
                let end = min(data.count, start + chunkLength)
                let output = chunkOutput(Data(data[start..<end]), counter: UInt64(chunkIndex))
                addChunkChainingValue(
                    output.chainingValue(),
                    totalChunks: UInt64(chunkIndex + 1),
                    stack: &chainingValueStack
                )
            }
        }

        let finalStart = (chunkCount - 1) * chunkLength
        let finalEnd = min(data.count, finalStart + chunkLength)
        var output = chunkOutput(Data(data[finalStart..<finalEnd]), counter: UInt64(chunkCount - 1))
        while let left = chainingValueStack.popLast() {
            output = parentOutput(left: left, right: output.chainingValue())
        }
        return output.rootBytes(count: 32)
    }

    private static func addChunkChainingValue(
        _ chunkValue: [UInt32],
        totalChunks: UInt64,
        stack: inout [[UInt32]]
    ) {
        var value = chunkValue
        var chunks = totalChunks
        while chunks & 1 == 0 {
            guard let left = stack.popLast() else { break }
            value = parentOutput(left: left, right: value).chainingValue()
            chunks >>= 1
        }
        stack.append(value)
    }

    private static func chunkOutput(_ chunk: Data, counter: UInt64) -> Output {
        let blockCount = max(1, (chunk.count + blockLength - 1) / blockLength)
        var chainingValue = initializationVector

        for blockIndex in 0..<blockCount {
            let start = blockIndex * blockLength
            let end = min(chunk.count, start + blockLength)
            let block = Data(chunk[start..<end])
            var flags: UInt32 = blockIndex == 0 ? chunkStart : 0
            if blockIndex == blockCount - 1 { flags |= chunkEnd }
            let output = Output(
                inputChainingValue: chainingValue,
                blockWords: words(from: block),
                counter: counter,
                blockLength: UInt32(block.count),
                flags: flags
            )
            if blockIndex == blockCount - 1 { return output }
            chainingValue = output.chainingValue()
        }

        preconditionFailure("BLAKE3 chunks always contain a final block")
    }

    private static func parentOutput(left: [UInt32], right: [UInt32]) -> Output {
        Output(
            inputChainingValue: initializationVector,
            blockWords: left + right,
            counter: 0,
            blockLength: UInt32(blockLength),
            flags: parent
        )
    }

    private static func words(from block: Data) -> [UInt32] {
        var result = Array(repeating: UInt32(0), count: 16)
        for (index, byte) in block.enumerated() {
            result[index / 4] |= UInt32(byte) << UInt32((index % 4) * 8)
        }
        return result
    }

    private static func compress(
        chainingValue: [UInt32],
        blockWords: [UInt32],
        counter: UInt64,
        blockLength: UInt32,
        flags: UInt32
    ) -> [UInt32] {
        var state = chainingValue + Array(initializationVector.prefix(4)) + [
            UInt32(truncatingIfNeeded: counter),
            UInt32(truncatingIfNeeded: counter >> 32),
            blockLength,
            flags
        ]
        var message = blockWords

        for roundIndex in 0..<7 {
            round(state: &state, message: message)
            if roundIndex < 6 {
                message = messagePermutation.map { message[$0] }
            }
        }

        var output = Array(repeating: UInt32(0), count: 16)
        for index in 0..<8 {
            output[index] = state[index] ^ state[index + 8]
            output[index + 8] = state[index + 8] ^ chainingValue[index]
        }
        return output
    }

    private static func round(state: inout [UInt32], message: [UInt32]) {
        mix(state: &state, 0, 4, 8, 12, message[0], message[1])
        mix(state: &state, 1, 5, 9, 13, message[2], message[3])
        mix(state: &state, 2, 6, 10, 14, message[4], message[5])
        mix(state: &state, 3, 7, 11, 15, message[6], message[7])
        mix(state: &state, 0, 5, 10, 15, message[8], message[9])
        mix(state: &state, 1, 6, 11, 12, message[10], message[11])
        mix(state: &state, 2, 7, 8, 13, message[12], message[13])
        mix(state: &state, 3, 4, 9, 14, message[14], message[15])
    }

    private static func mix(
        state: inout [UInt32],
        _ a: Int,
        _ b: Int,
        _ c: Int,
        _ d: Int,
        _ x: UInt32,
        _ y: UInt32
    ) {
        state[a] = state[a] &+ state[b] &+ x
        state[d] = rotateRight(state[d] ^ state[a], by: 16)
        state[c] = state[c] &+ state[d]
        state[b] = rotateRight(state[b] ^ state[c], by: 12)
        state[a] = state[a] &+ state[b] &+ y
        state[d] = rotateRight(state[d] ^ state[a], by: 8)
        state[c] = state[c] &+ state[d]
        state[b] = rotateRight(state[b] ^ state[c], by: 7)
    }

    private static func rotateRight(_ value: UInt32, by amount: UInt32) -> UInt32 {
        (value >> amount) | (value << (32 - amount))
    }

    private struct Output {
        let inputChainingValue: [UInt32]
        let blockWords: [UInt32]
        let counter: UInt64
        let blockLength: UInt32
        let flags: UInt32

        func chainingValue() -> [UInt32] {
            Array(
                CloudflareBLAKE3.compress(
                    chainingValue: inputChainingValue,
                    blockWords: blockWords,
                    counter: counter,
                    blockLength: blockLength,
                    flags: flags
                ).prefix(8)
            )
        }

        func rootBytes(count: Int) -> [UInt8] {
            var bytes: [UInt8] = []
            var outputBlockCounter: UInt64 = 0
            while bytes.count < count {
                let words = CloudflareBLAKE3.compress(
                    chainingValue: inputChainingValue,
                    blockWords: blockWords,
                    counter: outputBlockCounter,
                    blockLength: blockLength,
                    flags: flags | CloudflareBLAKE3.root
                )
                for word in words {
                    bytes.append(UInt8(truncatingIfNeeded: word))
                    bytes.append(UInt8(truncatingIfNeeded: word >> 8))
                    bytes.append(UInt8(truncatingIfNeeded: word >> 16))
                    bytes.append(UInt8(truncatingIfNeeded: word >> 24))
                }
                outputBlockCounter += 1
            }
            return Array(bytes.prefix(count))
        }
    }
}

private extension Data {
    nonisolated mutating func appendUTF8(_ value: String) {
        append(contentsOf: value.utf8)
    }
}
