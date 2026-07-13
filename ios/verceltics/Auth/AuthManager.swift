import Foundation
import Observation

@Observable
@MainActor
final class AuthManager {
    var accounts: [VercelAccount] = []
    var activeAccountId: UUID?
    var accountsWithLongAnalyticsHistory: Set<UUID> = []
    var isLoading = false
    var error: String?

    var isAuthenticated: Bool {
        activeAccount != nil
    }

    var token: String? {
        guard activeAccount?.provider == .vercel else { return nil }
        return activeAccount?.token
    }

    var activeAccount: VercelAccount? {
        accounts.first { $0.id == activeAccountId }
    }

    var activeProvider: AccountProvider? {
        activeAccount?.provider
    }

    var cloudflareCredentials: (
        mode: CloudflareAuthenticationMode,
        email: String?,
        credential: String
    )? {
        guard let account = activeAccount,
              account.provider == .cloudflare else { return nil }
        return (
            account.cloudflareAuthenticationMode ?? .globalAPIKey,
            account.email,
            account.token
        )
    }

    init() {
        self.accounts = KeychainHelper.getAccounts()
        self.activeAccountId = KeychainHelper.getActiveAccountId()
        self.accountsWithLongAnalyticsHistory = KeychainHelper.getLongAnalyticsHistoryAccountIds()

        // Re-save legacy Vercel-only records using the current schema and
        // device-only Keychain accessibility without changing their UUIDs.
        if !accounts.isEmpty {
            KeychainHelper.saveAccounts(accounts)
        }
        
        // Default to first account if active one is missing
        if activeAccount == nil, let first = accounts.first {
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
                if let existingIndex = accounts.firstIndex(where: {
                    $0.provider == .vercel && $0.token == token
                }) {
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
                self.error = "Unexpected error (\(result.statusCode))."
            }
        } catch {
            self.error = "Network error. Check your connection."
        }

        isLoading = false
    }

    func loginCloudflare(email: String, globalAPIKey: String) async {
        isLoading = true
        error = nil

        do {
            let profile = try await CloudflareAPI(
                email: email,
                globalAPIKey: globalAPIKey
            ).validateCredentials()
            let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if let existingIndex = accounts.firstIndex(where: {
                guard $0.provider == .cloudflare else { return false }
                if let savedUserID = $0.providerUserId {
                    return savedUserID == profile.id
                }
                return $0.email?.caseInsensitiveCompare(normalizedEmail) == .orderedSame
            }) {
                accounts[existingIndex].name = profile.displayName
                accounts[existingIndex].email = normalizedEmail
                accounts[existingIndex].token = globalAPIKey
                accounts[existingIndex].providerUserId = profile.id
                accounts[existingIndex].cloudflareAuthenticationMode = .globalAPIKey
                activeAccountId = accounts[existingIndex].id
            } else {
                let account = VercelAccount(
                    name: profile.displayName,
                    token: globalAPIKey,
                    provider: .cloudflare,
                    email: normalizedEmail,
                    providerUserId: profile.id,
                    cloudflareAuthenticationMode: .globalAPIKey
                )
                accounts.append(account)
                activeAccountId = account.id
            }
            KeychainHelper.saveAccounts(accounts)
            KeychainHelper.saveActiveAccountId(activeAccountId)
        } catch let error as LocalizedError {
            self.error = error.errorDescription ?? "Cloudflare rejected these credentials."
        } catch {
            self.error = "Could not connect to Cloudflare. Check your connection."
        }

        isLoading = false
    }

    func loginCloudflare(apiToken: String) async {
        isLoading = true
        error = nil
        let normalizedToken = apiToken.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            let client = CloudflareAPI(apiToken: normalizedToken)
            let verification = try await client.validateAPIToken()
            guard verification.status?.lowercased() == "active" else {
                self.error = "This Cloudflare API token is not active."
                isLoading = false
                return
            }
            let accessibleAccounts = try await client.fetchAccounts()
            let displayName = accessibleAccounts.first?.name ?? "Cloudflare API Token"

            if let existingIndex = accounts.firstIndex(where: {
                guard $0.provider == .cloudflare,
                      $0.cloudflareAuthenticationMode == .apiToken else { return false }
                if let verificationID = verification.id,
                   $0.providerUserId == verificationID {
                    return true
                }
                return $0.token == normalizedToken
            }) {
                accounts[existingIndex].name = displayName
                accounts[existingIndex].token = normalizedToken
                accounts[existingIndex].providerUserId = verification.id
                accounts[existingIndex].cloudflareAuthenticationMode = .apiToken
                activeAccountId = accounts[existingIndex].id
            } else {
                let account = VercelAccount(
                    name: displayName,
                    token: normalizedToken,
                    provider: .cloudflare,
                    providerUserId: verification.id,
                    cloudflareAuthenticationMode: .apiToken
                )
                accounts.append(account)
                activeAccountId = account.id
            }
            KeychainHelper.saveAccounts(accounts)
            KeychainHelper.saveActiveAccountId(activeAccountId)
        } catch let error as LocalizedError {
            self.error = error.errorDescription ?? "Cloudflare rejected this API token."
        } catch {
            self.error = "Could not connect to Cloudflare. Check the token permissions and connection."
        }

        isLoading = false
    }

    func refreshAccountProfiles() async {
        let savedAccounts = accounts
        guard !savedAccounts.isEmpty else { return }

        var didUpdate = false

        for account in savedAccounts {
            switch account.provider {
            case .vercel:
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
            case .cloudflare:
                let mode = account.cloudflareAuthenticationMode ?? .globalAPIKey
                switch mode {
                case .globalAPIKey:
                    guard let email = account.email,
                          let profile = try? await CloudflareAPI(email: email, globalAPIKey: account.token).validateCredentials(),
                          let index = accounts.firstIndex(where: { $0.id == account.id }) else { continue }
                    if accounts[index].name != profile.displayName {
                        accounts[index].name = profile.displayName
                        didUpdate = true
                    }
                    if accounts[index].providerUserId != profile.id {
                        accounts[index].providerUserId = profile.id
                        didUpdate = true
                    }
                case .apiToken:
                    let client = CloudflareAPI(apiToken: account.token)
                    guard let verification = try? await client.validateAPIToken(),
                          verification.status?.lowercased() == "active",
                          let index = accounts.firstIndex(where: { $0.id == account.id }) else { continue }
                    if accounts[index].providerUserId != verification.id {
                        accounts[index].providerUserId = verification.id
                        didUpdate = true
                    }
                }
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

    func hasLongAnalyticsHistory(for id: UUID?) -> Bool {
        guard let id else { return false }
        return accountsWithLongAnalyticsHistory.contains(id)
    }

    func markLongAnalyticsHistoryAvailable(for id: UUID?) {
        guard let id else { return }
        accountsWithLongAnalyticsHistory.insert(id)
        KeychainHelper.saveLongAnalyticsHistoryAccountIds(accountsWithLongAnalyticsHistory)
    }

    func removeAccount(id: UUID) {
        accounts.removeAll { $0.id == id }
        accountsWithLongAnalyticsHistory.remove(id)
        KeychainHelper.saveAccounts(accounts)
        KeychainHelper.saveLongAnalyticsHistoryAccountIds(accountsWithLongAnalyticsHistory)
        
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
        accountsWithLongAnalyticsHistory.removeAll()
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
