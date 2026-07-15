import AuthenticationServices
import CryptoKit
import Foundation
import Security
import UIKit

struct GoogleOAuthCredential: Codable, Equatable, Sendable {
    let accessToken: String
    let refreshToken: String?
    let tokenType: String
    let scopes: [String]
    let expiresAt: Date
    let subject: String?
    let email: String?

    init(
        accessToken: String,
        refreshToken: String?,
        tokenType: String,
        scopes: [String],
        expiresAt: Date,
        subject: String? = nil,
        email: String? = nil
    ) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.tokenType = tokenType
        self.scopes = scopes
        self.expiresAt = expiresAt
        self.subject = subject
        self.email = email
    }

    var needsRefresh: Bool {
        expiresAt.timeIntervalSinceNow < 90
    }

    func keychainValue() throws -> String {
        try JSONEncoder().encode(self).base64EncodedString()
    }

    static func fromKeychainValue(_ value: String) throws -> Self {
        guard let data = Data(base64Encoded: value),
              let credential = try? JSONDecoder().decode(Self.self, from: data),
              !credential.accessToken.isEmpty else {
            throw GoogleOAuthError.invalidStoredCredential
        }
        return credential
    }
}

struct GoogleOAuthClientConfiguration: Equatable, Sendable {
    let clientID: String
    let redirectScheme: String

    var redirectURI: String {
        "\(redirectScheme):/oauthredirect"
    }

    static var current: Self? {
        guard let rawClientID = Bundle.main.object(forInfoDictionaryKey: "GoogleOAuthClientID") as? String else {
            return nil
        }
        let clientID = rawClientID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clientID.isEmpty, !clientID.contains("$(") else { return nil }

        let configuredScheme = (Bundle.main.object(forInfoDictionaryKey: "GoogleOAuthRedirectScheme") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let redirectScheme = configuredScheme.flatMap { value in
            value.isEmpty || value.contains("$(") ? nil : value
        } ?? derivedRedirectScheme(from: clientID)

        guard let redirectScheme, !redirectScheme.isEmpty else { return nil }
        return Self(clientID: clientID, redirectScheme: redirectScheme)
    }

    private static func derivedRedirectScheme(from clientID: String) -> String? {
        let suffix = ".apps.googleusercontent.com"
        guard clientID.hasSuffix(suffix) else { return nil }
        let identifier = clientID.dropLast(suffix.count)
        guard !identifier.isEmpty else { return nil }
        return "com.googleusercontent.apps.\(identifier)"
    }
}

enum GoogleOAuthError: LocalizedError, Equatable {
    case configurationMissing
    case authorizationAlreadyRunning
    case authorizationCancelled
    case invalidAuthorizationResponse
    case stateMismatch
    case providerError(String)
    case tokenRequestFailed(Int, String)
    case invalidTokenResponse
    case invalidStoredCredential
    case refreshTokenMissing

    var errorDescription: String? {
        switch self {
        case .configurationMissing:
            "Google OAuth is implemented, but its iOS client configuration has not been added to this build yet."
        case .authorizationAlreadyRunning:
            "A Google authorization request is already open."
        case .authorizationCancelled:
            "Google authorization was cancelled."
        case .invalidAuthorizationResponse:
            "Google returned an invalid authorization response."
        case .stateMismatch:
            "Google authorization could not be verified. Please try again."
        case .providerError(let message):
            message
        case .tokenRequestFailed(let status, let message):
            message.isEmpty
                ? "Google token exchange failed (HTTP \(status))."
                : "Google token exchange failed (HTTP \(status)): \(message)"
        case .invalidTokenResponse:
            "Google returned an invalid token response."
        case .invalidStoredCredential:
            "The saved Google credential is invalid. Reconnect the account."
        case .refreshTokenMissing:
            "Google did not provide a refresh token. Reconnect the account to continue."
        }
    }
}

@MainActor
private final class GoogleOAuthSessionBox {
    weak var session: ASWebAuthenticationSession?

    func cancel() {
        session?.cancel()
    }
}

@MainActor
final class GoogleOAuthService: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = GoogleOAuthService()
    nonisolated static let firebaseHostingScopes = [
        "openid",
        "email",
        "https://www.googleapis.com/auth/firebase.hosting",
    ]

    private var activeSession: ASWebAuthenticationSession?

    var isConfigured: Bool {
        GoogleOAuthClientConfiguration.current != nil
    }

    func authorize(provider: SiteIntegrationProvider) async throws -> GoogleOAuthCredential {
        guard provider == .googleSearchConsole || provider == .googleAnalytics else {
            throw GoogleOAuthError.invalidAuthorizationResponse
        }
        return try await authorize(scopes: oauthConfiguration(for: provider).scopes)
    }

    func authorizeFirebaseHosting() async throws -> GoogleOAuthCredential {
        let credential = try await authorize(scopes: Self.firebaseHostingScopes)
        guard credential.refreshToken?.isEmpty == false else {
            // Firebase credentials live in the Keychain and must remain usable
            // after Google's short-lived access token expires.
            throw GoogleOAuthError.refreshTokenMissing
        }
        return credential
    }

    private func authorize(scopes: [String]) async throws -> GoogleOAuthCredential {
        guard let configuration = GoogleOAuthClientConfiguration.current else {
            throw GoogleOAuthError.configurationMissing
        }
        guard activeSession == nil else {
            throw GoogleOAuthError.authorizationAlreadyRunning
        }

        let verifier = try randomURLSafeString(byteCount: 64)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncodedString()
        let state = try randomURLSafeString(byteCount: 32)
        var components = URLComponents(
            url: SiteIntegrationsAPI.googleSearchConsoleOAuth.authorizationEndpoint,
            resolvingAgainstBaseURL: false
        )!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: configuration.clientID),
            URLQueryItem(name: "redirect_uri", value: configuration.redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: scopes.joined(separator: " ")),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "include_granted_scopes", value: "true"),
            URLQueryItem(name: "prompt", value: "consent"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
        ]
        guard let authorizationURL = components.url else {
            throw GoogleOAuthError.invalidAuthorizationResponse
        }

        let callbackURL = try await startAuthorization(
            url: authorizationURL,
            callbackScheme: configuration.redirectScheme
        )
        guard let callback = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false) else {
            throw GoogleOAuthError.invalidAuthorizationResponse
        }
        var values: [String: String] = [:]
        for item in callback.queryItems ?? [] {
            guard values[item.name] == nil else {
                throw GoogleOAuthError.invalidAuthorizationResponse
            }
            values[item.name] = item.value ?? ""
        }
        if let error = values["error"], !error.isEmpty {
            let description = values["error_description"]?.removingPercentEncoding ?? error
            throw GoogleOAuthError.providerError(description)
        }
        guard values["state"] == state else { throw GoogleOAuthError.stateMismatch }
        guard let code = values["code"], !code.isEmpty else {
            throw GoogleOAuthError.invalidAuthorizationResponse
        }

        let credential = try await exchangeAuthorizationCode(
            code,
            verifier: verifier,
            scopes: scopes,
            configuration: configuration
        )
        return try await credentialWithIdentity(credential)
    }

    func refreshedCredential(
        _ credential: GoogleOAuthCredential,
        force: Bool = false
    ) async throws -> GoogleOAuthCredential {
        guard force || credential.needsRefresh else { return credential }
        guard let configuration = GoogleOAuthClientConfiguration.current else {
            throw GoogleOAuthError.configurationMissing
        }
        guard let refreshToken = credential.refreshToken, !refreshToken.isEmpty else {
            throw GoogleOAuthError.refreshTokenMissing
        }

        let response = try await tokenRequest([
            URLQueryItem(name: "client_id", value: configuration.clientID),
            URLQueryItem(name: "refresh_token", value: refreshToken),
            URLQueryItem(name: "grant_type", value: "refresh_token"),
        ])
        return try makeCredential(
            from: response,
            fallbackRefreshToken: refreshToken,
            fallbackScopes: credential.scopes,
            fallbackSubject: credential.subject,
            fallbackEmail: credential.email
        )
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if let keyWindow = scenes.flatMap(\.windows).first(where: \.isKeyWindow) {
            return keyWindow
        }
        if let window = scenes.flatMap(\.windows).first {
            return window
        }
        return ASPresentationAnchor()
    }

    private func oauthConfiguration(for provider: SiteIntegrationProvider) -> SiteIntegrationOAuthConfiguration {
        provider == .googleAnalytics
            ? SiteIntegrationsAPI.googleAnalyticsOAuth
            : SiteIntegrationsAPI.googleSearchConsoleOAuth
    }

    private func startAuthorization(url: URL, callbackScheme: String) async throws -> URL {
        try Task.checkCancellation()
        let sessionBox = GoogleOAuthSessionBox()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let session = ASWebAuthenticationSession(url: url, callbackURLScheme: callbackScheme) { [weak self] callbackURL, error in
                    Task { @MainActor in
                        if let completedSession = sessionBox.session,
                           self?.activeSession === completedSession {
                            self?.activeSession = nil
                        }
                        sessionBox.session = nil
                        if let authenticationError = error as? ASWebAuthenticationSessionError,
                           authenticationError.code == .canceledLogin {
                            continuation.resume(throwing: GoogleOAuthError.authorizationCancelled)
                        } else if let error {
                            continuation.resume(throwing: error)
                        } else if let callbackURL {
                            continuation.resume(returning: callbackURL)
                        } else {
                            continuation.resume(throwing: GoogleOAuthError.invalidAuthorizationResponse)
                        }
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                sessionBox.session = session
                activeSession = session
                guard session.start() else {
                    if activeSession === session {
                        activeSession = nil
                    }
                    sessionBox.session = nil
                    continuation.resume(throwing: GoogleOAuthError.invalidAuthorizationResponse)
                    return
                }
            }
        } onCancel: {
            Task { @MainActor in
                sessionBox.cancel()
            }
        }
    }

    private func exchangeAuthorizationCode(
        _ code: String,
        verifier: String,
        scopes: [String],
        configuration: GoogleOAuthClientConfiguration
    ) async throws -> GoogleOAuthCredential {
        let response = try await tokenRequest([
            URLQueryItem(name: "client_id", value: configuration.clientID),
            URLQueryItem(name: "code", value: code),
            URLQueryItem(name: "code_verifier", value: verifier),
            URLQueryItem(name: "grant_type", value: "authorization_code"),
            URLQueryItem(name: "redirect_uri", value: configuration.redirectURI),
        ])
        return try makeCredential(
            from: response,
            fallbackRefreshToken: nil,
            fallbackScopes: scopes,
            fallbackSubject: nil,
            fallbackEmail: nil
        )
    }

    private func credentialWithIdentity(
        _ credential: GoogleOAuthCredential
    ) async throws -> GoogleOAuthCredential {
        var request = URLRequest(url: URL(string: "https://openidconnect.googleapis.com/v1/userinfo")!)
        request.setValue("\(credential.tokenType) \(credential.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await ProviderRequestSecurity.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              let identity = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let subject = identity["sub"] as? String,
              !subject.isEmpty else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        return GoogleOAuthCredential(
            accessToken: credential.accessToken,
            refreshToken: credential.refreshToken,
            tokenType: credential.tokenType,
            scopes: credential.scopes,
            expiresAt: credential.expiresAt,
            subject: subject,
            email: identity["email"] as? String
        )
    }

    private func tokenRequest(_ items: [URLQueryItem]) async throws -> [String: Any] {
        var form = URLComponents()
        form.queryItems = items
        var request = URLRequest(url: SiteIntegrationsAPI.googleSearchConsoleOAuth.tokenEndpoint)
        request.httpMethod = "POST"
        request.httpBody = Data((form.percentEncodedQuery ?? "").utf8)
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await ProviderRequestSecurity.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        guard (200...299).contains(http.statusCode) else {
            let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            let message = object?["error_description"] as? String
                ?? object?["error"] as? String
                ?? String(data: data, encoding: .utf8)
                ?? ""
            throw GoogleOAuthError.tokenRequestFailed(http.statusCode, String(message.prefix(300)))
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        return object
    }

    private func makeCredential(
        from response: [String: Any],
        fallbackRefreshToken: String?,
        fallbackScopes: [String],
        fallbackSubject: String?,
        fallbackEmail: String?
    ) throws -> GoogleOAuthCredential {
        guard let accessToken = response["access_token"] as? String, !accessToken.isEmpty else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        let expiresIn: TimeInterval
        if let value = response["expires_in"] as? NSNumber {
            expiresIn = value.doubleValue
        } else if let value = response["expires_in"] as? String, let parsed = Double(value) {
            expiresIn = parsed
        } else {
            throw GoogleOAuthError.invalidTokenResponse
        }
        let scopeString = response["scope"] as? String
        let scopes = scopeString?.split(separator: " ").map(String.init) ?? fallbackScopes
        return GoogleOAuthCredential(
            accessToken: accessToken,
            refreshToken: (response["refresh_token"] as? String) ?? fallbackRefreshToken,
            tokenType: (response["token_type"] as? String) ?? "Bearer",
            scopes: scopes,
            expiresAt: Date.now.addingTimeInterval(expiresIn),
            subject: fallbackSubject,
            email: fallbackEmail
        )
    }

    private func randomURLSafeString(byteCount: Int) throws -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw GoogleOAuthError.invalidAuthorizationResponse
        }
        return Data(bytes).base64URLEncodedString()
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
