import Foundation
import Observation

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

    func login(token: String) async {
        isLoading = true
        error = nil

        // Validate the token by making a test API call
        var request = URLRequest(url: URL(string: "https://api.vercel.com/v2/user")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                self.error = "Network error. Please try again."
                isLoading = false
                return
            }
            switch http.statusCode {
            case 200...299:
                KeychainHelper.saveToken(token)
                isAuthenticated = true
            case 401, 403:
                self.error = "Invalid token. Please check and try again."
            case 429:
                self.error = "Too many requests. Please wait a moment and try again."
            case 500...599:
                self.error = "Vercel servers are down. Please try again later."
            default:
                self.error = "Unexpected error (\(http.statusCode)). Please try again."
            }
        } catch {
            self.error = "Network error. Check your connection and try again."
        }

        isLoading = false
    }

    func logout() {
        KeychainHelper.deleteToken()
        isAuthenticated = false
    }
}
