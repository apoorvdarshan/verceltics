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

        Task {
            await refreshAccountProfiles()
        }
    }

    func login(token: String) async {
        isLoading = true
        error = nil

        do {
            let result = try await fetchAccountProfile(token: token)

            guard result.isValid else {
                self.error = "Network error. Please try again."
                isLoading = false
                return
            }
            
            switch result.statusCode {
            case 200...299:
                let name = result.profile?.name ?? "Account \(accounts.count + 1)"
                
                // Check if account already exists
                if let existingIndex = accounts.firstIndex(where: { $0.token == token }) {
                    accounts[existingIndex].name = name
                    accounts[existingIndex].avatarURL = result.profile?.avatarURL
                    activeAccountId = accounts[existingIndex].id
                } else {
                    let newAccount = VercelAccount(name: name, token: token, avatarURL: result.profile?.avatarURL)
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

    func refreshAccountProfiles() async {
        let savedAccounts = accounts.map { (id: $0.id, token: $0.token) }
        guard !savedAccounts.isEmpty else { return }

        var didUpdate = false

        for account in savedAccounts {
            guard let result = try? await fetchAccountProfile(token: account.token),
                  result.statusCode == 200,
                  let profile = result.profile,
                  let index = accounts.firstIndex(where: { $0.id == account.id }) else { continue }

            if accounts[index].name != profile.name {
                accounts[index].name = profile.name
                didUpdate = true
            }

            if accounts[index].avatarURL != profile.avatarURL {
                accounts[index].avatarURL = profile.avatarURL
                didUpdate = true
            }
        }

        if didUpdate {
            KeychainHelper.saveAccounts(accounts)
        }
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

    private struct AccountProfile {
        let name: String
        let avatarURL: String?
    }

    private struct ProfileResult {
        let statusCode: Int
        let profile: AccountProfile?

        var isValid: Bool {
            statusCode > 0
        }
    }

    private func fetchAccountProfile(token: String) async throws -> ProfileResult {
        var request = URLRequest(url: URL(string: "https://api.vercel.com/v2/user")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            return ProfileResult(statusCode: 0, profile: nil)
        }

        return ProfileResult(
            statusCode: http.statusCode,
            profile: extractProfile(from: data)
        )
    }

    private func extractProfile(from data: Data) -> AccountProfile? {
        struct UserResponse: Codable {
            struct User: Codable {
                let username: String
                let name: String?
                let avatar: String?
            }
            let user: User
        }
        guard let decoded = try? JSONDecoder().decode(UserResponse.self, from: data) else { return nil }

        return AccountProfile(
            name: decoded.user.name ?? decoded.user.username,
            avatarURL: avatarURL(from: decoded.user.avatar)
        )
    }

    private func avatarURL(from avatar: String?) -> String? {
        guard let avatar, !avatar.isEmpty else { return nil }
        if avatar.hasPrefix("http://") || avatar.hasPrefix("https://") {
            return avatar
        }
        return "https://api.vercel.com/www/avatar/\(avatar)"
    }
}
