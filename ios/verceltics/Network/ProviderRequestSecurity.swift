import Foundation

private final class ProviderRedirectGuard: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let originalURL = task.originalRequest?.url,
              let redirectURL = request.url,
              redirectURL.scheme?.lowercased() == "https",
              redirectURL.host?.lowercased() == originalURL.host?.lowercased(),
              (redirectURL.port ?? 443) == (originalURL.port ?? 443),
              redirectURL.user == nil,
              redirectURL.password == nil else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }
}

enum ProviderRequestSecurity {
    private static let redirectGuard = ProviderRedirectGuard()
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 60
        return URLSession(
            configuration: configuration,
            delegate: redirectGuard,
            delegateQueue: nil
        )
    }()

    static func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard request.url?.scheme?.lowercased() == "https",
              request.url?.host != nil,
              request.url?.user == nil,
              request.url?.password == nil else {
            throw URLError(.badURL)
        }
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
