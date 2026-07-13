import Foundation

private final class ProviderRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let allowedHosts: Set<String>

    init(allowedHosts: Set<String>) {
        self.allowedHosts = allowedHosts
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme == "https",
              let host = request.url?.host?.lowercased(),
              allowedHosts.contains(host) else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }
}

enum ProviderRequestSecurity {
    static func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard request.url?.scheme == "https",
              let host = request.url?.host?.lowercased() else {
            throw URLError(.badURL)
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        let session = URLSession(
            configuration: configuration,
            delegate: ProviderRedirectGuard(allowedHosts: [host]),
            delegateQueue: nil
        )
        defer { session.finishTasksAndInvalidate() }
        return try await session.data(for: request)
    }

    static func validatedHeaders(
        _ headers: [String: String],
        protectedHeaders: Set<String>
    ) throws -> [String: String] {
        var result: [String: String] = [:]
        for (rawName, rawValue) in headers {
            let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty,
                  !name.contains("\r"), !name.contains("\n"),
                  !value.contains("\r"), !value.contains("\n") else {
                throw ProviderRequestSecurityError.invalidHeader
            }
            guard !protectedHeaders.contains(name.lowercased()) else { continue }
            result[name] = value
        }
        return result
    }

    static func validatedContentType(_ value: String?) throws -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.contains("\r"), !trimmed.contains("\n") else {
            throw ProviderRequestSecurityError.invalidHeader
        }
        return trimmed.isEmpty ? nil : trimmed
    }
}

enum ProviderRequestSecurityError: LocalizedError {
    case invalidHeader
    case invalidBase64Body

    var errorDescription: String? {
        switch self {
        case .invalidHeader:
            "Header names and values cannot contain line breaks."
        case .invalidBase64Body:
            "The encoded binary request body is not valid Base64."
        }
    }
}
