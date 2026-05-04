import Foundation
import Observation

@Observable
@MainActor
final class AuthManager {
    var accounts: [VercelAccount] = []
    var activeAccountId: UUID?
    var isLoading = false
    var error: String?

    var isAuthenticated: Bool {
        !accounts.isEmpty && activeAccountId != nil
    }

    var token: String? {
        activeAccount?.token
    }

    var activeAccount: VercelAccount? {
        accounts.first { $0.id == activeAccountId }
    }

    init() {
        self.accounts = KeychainHelper.getAccounts()
        self.activeAccountId = KeychainHelper.getActiveAccountId()
        
        // Default to first account if active one is missing
        if activeAccountId == nil, let first = accounts.first {
            activeAccountId = first.id
            KeychainHelper.saveActiveAccountId(first.id)
        }
    }

    func login(token: String) async {
        isLoading = true
        error = nil

        // Validate the token and get user info
        var request = URLRequest(url: URL(string: "https://api.vercel.com/v2/user")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                self.error = "Network error. Please try again."
                isLoading = false
                return
            }
            
            switch http.statusCode {
            case 200...299:
                // Extract name from user profile if possible
                let name = extractName(from: data) ?? "Account \(accounts.count + 1)"
                
                // Check if account already exists
                if let existingIndex = accounts.firstIndex(where: { $0.token == token }) {
                    accounts[existingIndex].name = name
                    activeAccountId = accounts[existingIndex].id
                } else {
                    let newAccount = VercelAccount(name: name, token: token)
                    accounts.append(newAccount)
                    activeAccountId = newAccount.id
                }
                
                KeychainHelper.saveAccounts(accounts)
                KeychainHelper.saveActiveAccountId(activeAccountId)
            case 401, 403:
                self.error = "Invalid token. Please check and try again."
            default:
                self.error = "Unexpected error (\(http.statusCode))."
            }
        } catch {
            self.error = "Network error. Check your connection."
        }

        isLoading = false
    }

    func switchAccount(to id: UUID) {
        if accounts.contains(where: { $0.id == id }) {
            activeAccountId = id
            KeychainHelper.saveActiveAccountId(id)
        }
    }

    func removeAccount(id: UUID) {
        accounts.removeAll { $0.id == id }
        KeychainHelper.saveAccounts(accounts)
        
        if activeAccountId == id {
            activeAccountId = accounts.first?.id
            KeychainHelper.saveActiveAccountId(activeAccountId)
        }
    }

    func logout() {
        if let id = activeAccountId {
            removeAccount(id: id)
        }
    }
    
    func logoutAll() {
        accounts = []
        activeAccountId = nil
        KeychainHelper.deleteEverything()
    }

    private func extractName(from data: Data) -> String? {
        struct UserResponse: Codable {
            struct User: Codable {
                let username: String
                let name: String?
            }
            let user: User
        }
        let decoded = try? JSONDecoder().decode(UserResponse.self, from: data)
        return decoded?.user.name ?? decoded?.user.username
    }
}
