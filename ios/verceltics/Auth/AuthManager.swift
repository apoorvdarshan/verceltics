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
    private var accountPersistenceFailure: String?

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

    var activeHostingAccount: VercelAccount? {
        guard let account = activeAccount, account.provider.isGenericHostingProvider else { return nil }
        return account
    }

    init() {
        do {
            self.accounts = try KeychainHelper.getAccounts()
        } catch {
            self.accounts = []
            self.accountPersistenceFailure = error.localizedDescription
            self.error = error.localizedDescription
        }
        self.activeAccountId = KeychainHelper.getActiveAccountId()
        self.accountsWithLongAnalyticsHistory = KeychainHelper.getLongAnalyticsHistoryAccountIds()

        // Re-save legacy Vercel-only records using the current schema and
        // device-only Keychain accessibility without changing their UUIDs.
        if !accounts.isEmpty {
            do {
                try KeychainHelper.saveAccounts(accounts)
            } catch {
                accountPersistenceFailure = error.localizedDescription
                self.error = error.localizedDescription
            }
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
        guard ensureAccountPersistenceIsAvailable() else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }

        do {
            let result = try await fetchAccountProfile(token: token)

            guard result.isValid else {
                self.error = "Network error. Please try again."
                return
            }
            
            switch result.statusCode {
            case 200...299:
                let name = result.profile?.name ?? "Account \(accounts.count + 1)"
                
                var updatedAccounts = accounts
                let updatedActiveID: UUID
                if let existingIndex = updatedAccounts.firstIndex(where: {
                    $0.provider == .vercel && $0.token == token
                }) {
                    updatedAccounts[existingIndex].name = name
                    updatedAccounts[existingIndex].avatarURL = result.profile?.avatarURL
                    updatedActiveID = updatedAccounts[existingIndex].id
                } else {
                    let newAccount = VercelAccount(name: name, token: token, avatarURL: result.profile?.avatarURL)
                    updatedAccounts.append(newAccount)
                    updatedActiveID = newAccount.id
                }
                try persistAccounts(updatedAccounts, activeAccountID: updatedActiveID)
            case 401, 403:
                self.error = "Invalid token. Please check and try again."
            default:
                self.error = "Unexpected error (\(result.statusCode))."
            }
        } catch let persistenceError as ConnectedAccountPersistenceError {
            self.error = persistenceError.localizedDescription
        } catch {
            self.error = "Network error. Check your connection."
        }
    }

    func loginCloudflare(email: String, globalAPIKey: String) async {
        guard ensureAccountPersistenceIsAvailable() else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }

        do {
            let profile = try await CloudflareAPI(
                email: email,
                globalAPIKey: globalAPIKey
            ).validateCredentials()
            let normalizedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            var updatedAccounts = accounts
            let updatedActiveID: UUID
            if let existingIndex = updatedAccounts.firstIndex(where: {
                guard $0.provider == .cloudflare else { return false }
                if let savedUserID = $0.providerUserId {
                    return savedUserID == profile.id
                }
                return $0.email?.caseInsensitiveCompare(normalizedEmail) == .orderedSame
            }) {
                updatedAccounts[existingIndex].name = profile.displayName
                updatedAccounts[existingIndex].email = normalizedEmail
                updatedAccounts[existingIndex].token = globalAPIKey
                updatedAccounts[existingIndex].providerUserId = profile.id
                updatedAccounts[existingIndex].cloudflareAuthenticationMode = .globalAPIKey
                updatedActiveID = updatedAccounts[existingIndex].id
            } else {
                let account = VercelAccount(
                    name: profile.displayName,
                    token: globalAPIKey,
                    provider: .cloudflare,
                    email: normalizedEmail,
                    providerUserId: profile.id,
                    cloudflareAuthenticationMode: .globalAPIKey
                )
                updatedAccounts.append(account)
                updatedActiveID = account.id
            }
            try persistAccounts(updatedAccounts, activeAccountID: updatedActiveID)
        } catch let error as LocalizedError {
            self.error = error.errorDescription ?? "Cloudflare rejected these credentials."
        } catch {
            self.error = "Could not connect to Cloudflare. Check your connection."
        }
    }

    func loginCloudflare(apiToken: String) async {
        guard ensureAccountPersistenceIsAvailable() else { return }
        isLoading = true
        error = nil
        defer { isLoading = false }
        let normalizedToken = apiToken.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            let client = CloudflareAPI(apiToken: normalizedToken)
            let verification = try await client.validateAPIToken()
            guard verification.status?.lowercased() == "active" else {
                self.error = "This Cloudflare API token is not active."
                return
            }
            let accessibleAccounts = try await client.fetchAccounts()
            let displayName = accessibleAccounts.first?.name ?? "Cloudflare API Token"

            var updatedAccounts = accounts
            let updatedActiveID: UUID
            if let existingIndex = updatedAccounts.firstIndex(where: {
                guard $0.provider == .cloudflare,
                      $0.cloudflareAuthenticationMode == .apiToken else { return false }
                if let verificationID = verification.id,
                   $0.providerUserId == verificationID {
                    return true
                }
                return $0.token == normalizedToken
            }) {
                updatedAccounts[existingIndex].name = displayName
                updatedAccounts[existingIndex].token = normalizedToken
                updatedAccounts[existingIndex].providerUserId = verification.id
                updatedAccounts[existingIndex].cloudflareAuthenticationMode = .apiToken
                updatedActiveID = updatedAccounts[existingIndex].id
            } else {
                let account = VercelAccount(
                    name: displayName,
                    token: normalizedToken,
                    provider: .cloudflare,
                    providerUserId: verification.id,
                    cloudflareAuthenticationMode: .apiToken
                )
                updatedAccounts.append(account)
                updatedActiveID = account.id
            }
            try persistAccounts(updatedAccounts, activeAccountID: updatedActiveID)
        } catch let error as LocalizedError {
            self.error = error.errorDescription ?? "Cloudflare rejected this API token."
        } catch {
            self.error = "Could not connect to Cloudflare. Check the token permissions and connection."
        }
    }

    func loginHostingProvider(
        _ provider: AccountProvider,
        credential: String,
        metadata: [String: String] = [:]
    ) async {
        guard provider.isGenericHostingProvider else {
            error = "Use the dedicated \(provider.displayName) connection flow."
            return
        }
        guard ensureAccountPersistenceIsAvailable() else { return }

        isLoading = true
        error = nil
        defer { isLoading = false }
        let normalizedCredential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedMetadata = metadata.reduce(into: [String: String]()) { result, pair in
            result[pair.key] = pair.value.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        do {
            let profile = try await HostingProviderAPI(
                provider: provider,
                credential: normalizedCredential,
                metadata: normalizedMetadata
            ).validateProfile()

            var updatedAccounts = accounts
            let updatedActiveID: UUID
            if let existingIndex = updatedAccounts.firstIndex(where: {
                $0.provider == provider && ($0.providerUserId == profile.id || $0.token == normalizedCredential)
            }) {
                updatedAccounts[existingIndex].name = profile.name
                updatedAccounts[existingIndex].token = normalizedCredential
                updatedAccounts[existingIndex].email = profile.email
                updatedAccounts[existingIndex].avatarURL = profile.avatarURL
                updatedAccounts[existingIndex].providerUserId = profile.id
                updatedAccounts[existingIndex].providerMetadata = normalizedMetadata
                updatedActiveID = updatedAccounts[existingIndex].id
            } else {
                let account = VercelAccount(
                    name: profile.name,
                    token: normalizedCredential,
                    avatarURL: profile.avatarURL,
                    provider: provider,
                    email: profile.email,
                    providerUserId: profile.id,
                    providerMetadata: normalizedMetadata
                )
                updatedAccounts.append(account)
                updatedActiveID = account.id
            }
            try persistAccounts(updatedAccounts, activeAccountID: updatedActiveID)
        } catch let localized as LocalizedError {
            error = localized.errorDescription ?? "\(provider.displayName) rejected these credentials."
        } catch {
            self.error = "Could not connect to \(provider.displayName). Check the credentials and connection."
        }
    }

    func refreshAccountProfiles() async {
        guard ensureAccountPersistenceIsAvailable() else { return }
        let savedAccounts = accounts
        guard !savedAccounts.isEmpty else { return }

        var profileUpdates: [UUID: AccountProfileUpdate] = [:]

        for account in savedAccounts {
            switch account.provider {
            case .vercel:
                guard let result = try? await fetchAccountProfile(token: account.token),
                      result.statusCode == 200,
                      let profile = result.profile else { continue }
                profileUpdates[account.id] = .vercel(
                    name: profile.name,
                    avatarURL: profile.avatarURL
                )
            case .cloudflare:
                let mode = account.cloudflareAuthenticationMode ?? .globalAPIKey
                switch mode {
                case .globalAPIKey:
                    guard let email = account.email,
                          let profile = try? await CloudflareAPI(
                            email: email,
                            globalAPIKey: account.token
                          ).validateCredentials() else { continue }
                    profileUpdates[account.id] = .cloudflare(
                        name: profile.displayName,
                        providerUserID: profile.id
                    )
                case .apiToken:
                    let client = CloudflareAPI(apiToken: account.token)
                    guard let verification = try? await client.validateAPIToken(),
                          verification.status?.lowercased() == "active" else { continue }
                    profileUpdates[account.id] = .cloudflareToken(providerUserID: verification.id)
                }
            case .netlify, .railway, .render, .digitalOcean, .heroku, .fly, .firebase, .awsAmplify:
                guard let profile = try? await HostingProviderAPI(account: account).validateProfile() else { continue }
                profileUpdates[account.id] = .hosting(
                    name: profile.name,
                    email: profile.email,
                    avatarURL: profile.avatarURL,
                    providerUserID: profile.id
                )
            }
        }

        var updatedAccounts = accounts
        for (id, update) in profileUpdates {
            guard let index = updatedAccounts.firstIndex(where: { $0.id == id }),
                  let savedAccount = savedAccounts.first(where: { $0.id == id }),
                  updatedAccounts[index].provider == savedAccount.provider,
                  updatedAccounts[index].token == savedAccount.token else { continue }
            update.apply(to: &updatedAccounts[index])
        }
        guard updatedAccounts != accounts else { return }
        do {
            try KeychainHelper.saveAccounts(updatedAccounts)
            accounts = updatedAccounts
        } catch {
            self.error = error.localizedDescription
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
        guard ensureAccountPersistenceIsAvailable() else { return }
        let updatedAccounts = accounts.filter { $0.id != id }
        guard updatedAccounts.count != accounts.count else { return }
        let updatedActiveID = activeAccountId == id ? updatedAccounts.first?.id : activeAccountId
        do {
            try persistAccounts(updatedAccounts, activeAccountID: updatedActiveID)
            accountsWithLongAnalyticsHistory.remove(id)
            KeychainHelper.saveLongAnalyticsHistoryAccountIds(accountsWithLongAnalyticsHistory)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func logout() {
        if let id = activeAccountId {
            removeAccount(id: id)
        }
    }
    
    func logoutAll() {
        guard ensureAccountPersistenceIsAvailable() else { return }
        do {
            try persistAccounts([], activeAccountID: nil)
            accountsWithLongAnalyticsHistory.removeAll()
            KeychainHelper.saveLongAnalyticsHistoryAccountIds([])
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func ensureAccountPersistenceIsAvailable() -> Bool {
        guard let accountPersistenceFailure else { return true }
        error = accountPersistenceFailure
        return false
    }

    private func persistAccounts(
        _ updatedAccounts: [VercelAccount],
        activeAccountID updatedActiveID: UUID?
    ) throws {
        try KeychainHelper.saveAccounts(updatedAccounts)
        accounts = updatedAccounts
        activeAccountId = updatedActiveID
        KeychainHelper.saveActiveAccountId(updatedActiveID)
    }

    private enum AccountProfileUpdate {
        case vercel(name: String, avatarURL: String?)
        case cloudflare(name: String, providerUserID: String)
        case cloudflareToken(providerUserID: String?)
        case hosting(name: String, email: String?, avatarURL: String?, providerUserID: String)

        func apply(to account: inout VercelAccount) {
            switch self {
            case .vercel(let name, let avatarURL):
                account.name = name
                account.avatarURL = avatarURL
            case .cloudflare(let name, let providerUserID):
                account.name = name
                account.providerUserId = providerUserID
            case .cloudflareToken(let providerUserID):
                account.providerUserId = providerUserID
            case .hosting(let name, let email, let avatarURL, let providerUserID):
                account.name = name
                account.email = email
                account.avatarURL = avatarURL
                account.providerUserId = providerUserID
            }
        }
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
        guard let profileURL = URL(string: "https://api.vercel.com/v2/user") else {
            throw URLError(.badURL)
        }
        var request = URLRequest(url: profileURL)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await ProviderRequestSecurity.data(for: request)
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
