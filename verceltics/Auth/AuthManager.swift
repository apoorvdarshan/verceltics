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
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                self.error = "Invalid token. Please check and try again."
                isLoading = false
                return
            }
            KeychainHelper.saveToken(token)
            isAuthenticated = true
        } catch {
            self.error = "Network error. Please try again."
        }

        isLoading = false
    }

    func logout() {
        KeychainHelper.deleteToken()
        isAuthenticated = false
    }
}
