import Foundation
import AuthenticationServices
import Observation

// MARK: - Replace these with your Vercel OAuth app credentials
private let vercelClientID = "YOUR_VERCEL_CLIENT_ID"
private let vercelClientSecret = "YOUR_VERCEL_CLIENT_SECRET"
private let vercelRedirectURI = "verceltics://callback"

@Observable
@MainActor
final class AuthManager {
    var isAuthenticated = false
    var isLoading = false
    var error: String?

    var token: String? {
        KeychainHelper.getToken()
    }

    init() {
        isAuthenticated = KeychainHelper.getToken() != nil
    }

    func login() async {
        isLoading = true
        error = nil

        let authURL = URL(string: "https://vercel.com/integrations/oAuthClient/authorize?client_id=\(vercelClientID)&redirect_uri=\(vercelRedirectURI.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")&response_type=code")!

        do {
            let callbackURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
                let session = ASWebAuthenticationSession(
                    url: authURL,
                    callbackURLScheme: "verceltics"
                ) { url, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let url {
                        continuation.resume(returning: url)
                    } else {
                        continuation.resume(throwing: AuthError.unknown)
                    }
                }
                session.prefersEphemeralWebBrowserSession = false
                session.presentationContextProvider = WebAuthContextProvider.shared
                session.start()
            }

            guard let code = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "code" })?.value else {
                throw AuthError.noCode
            }

            try await exchangeCodeForToken(code)
            isAuthenticated = true
        } catch let err as ASWebAuthenticationSessionError where err.code == .canceledLogin {
            // User cancelled — not an error
        } catch {
            self.error = error.localizedDescription
        }

        isLoading = false
    }

    func logout() {
        KeychainHelper.deleteToken()
        isAuthenticated = false
    }

    private func exchangeCodeForToken(_ code: String) async throws {
        var request = URLRequest(url: URL(string: "https://api.vercel.com/v2/oauth/access_token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        let body = [
            "client_id": vercelClientID,
            "client_secret": vercelClientSecret,
            "code": code,
            "redirect_uri": vercelRedirectURI
        ]
        request.httpBody = body.map { "\($0.key)=\($0.value)" }.joined(separator: "&").data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw AuthError.tokenExchangeFailed
        }

        let result = try JSONDecoder().decode(TokenResponse.self, from: data)
        KeychainHelper.saveToken(result.accessToken)
    }
}

// MARK: - Supporting Types

enum AuthError: LocalizedError {
    case noCode, tokenExchangeFailed, unknown

    var errorDescription: String? {
        switch self {
        case .noCode: "No authorization code received."
        case .tokenExchangeFailed: "Failed to exchange code for token."
        case .unknown: "An unknown error occurred."
        }
    }
}

private struct TokenResponse: Decodable {
    let accessToken: String
    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
    }
}

final class WebAuthContextProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = WebAuthContextProvider()
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        ASPresentationAnchor()
    }
}
