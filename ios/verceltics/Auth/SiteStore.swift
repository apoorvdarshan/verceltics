import Foundation
import Observation

@Observable
@MainActor
final class SiteStore {
    var accounts: [SiteIntegrationAccount]
    var activeAccountID: UUID?
    var isConnecting = false
    var error: String?
    var snapshots: [UUID: SiteIntegrationSnapshot] = [:]
    var refreshErrors: [UUID: String] = [:]
    private var refreshingAccountIDs = Set<UUID>()

    var isRefreshing: Bool {
        !refreshingAccountIDs.isEmpty
    }

    var activeAccount: SiteIntegrationAccount? {
        accounts.first { $0.id == activeAccountID }
    }

    init() {
        accounts = KeychainHelper.getSiteIntegrationAccounts()
        activeAccountID = KeychainHelper.getActiveSiteIntegrationAccountID()
        let accountIDs = Set(accounts.map(\.id))
        for snapshot in KeychainHelper.getSiteIntegrationSnapshots() where accountIDs.contains(snapshot.accountID) {
            if let existing = snapshots[snapshot.accountID], existing.updatedAt >= snapshot.updatedAt { continue }
            snapshots[snapshot.accountID] = snapshot
        }
        if activeAccount == nil, let first = accounts.first {
            activeAccountID = first.id
            KeychainHelper.saveActiveSiteIntegrationAccountID(first.id)
        }
    }

    func connect(
        provider: SiteIntegrationProvider,
        credential: String,
        metadata: [String: String] = [:]
    ) async {
        isConnecting = true
        error = nil
        defer { isConnecting = false }

        let cleanCredential = credential.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanMetadata = metadata.reduce(into: [String: String]()) { result, pair in
            let key = pair.key.trimmingCharacters(in: .whitespacesAndNewlines)
            let value = pair.value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !key.isEmpty, !value.isEmpty { result[key] = value }
        }
        let existingIndex = accounts.firstIndex {
            isSameConnection(
                $0,
                provider: provider,
                credential: cleanCredential,
                metadata: cleanMetadata
            )
        }
        let candidate = SiteIntegrationAccount(
            id: existingIndex.map { accounts[$0].id } ?? UUID(),
            provider: provider,
            name: existingIndex.map { accounts[$0].name } ?? provider.displayName,
            credential: cleanCredential,
            metadata: cleanMetadata
        )

        do {
            let result = try await SiteIntegrationsAPI(account: candidate).validatedSnapshot()
            var connectedAccount = candidate
            connectedAccount.name = result.name
            if let existingIndex {
                accounts[existingIndex] = connectedAccount
            } else {
                accounts.append(connectedAccount)
            }
            activeAccountID = connectedAccount.id
            snapshots[connectedAccount.id] = result.snapshot
            refreshErrors[connectedAccount.id] = nil
            updateVisibleRefreshError(preferredAccountID: connectedAccount.id)
            persist()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func connectGoogle(provider: SiteIntegrationProvider) async {
        guard provider == .googleSearchConsole || provider == .googleAnalytics else { return }
        isConnecting = true
        error = nil
        do {
            let credential = try await GoogleOAuthService.shared.authorize(provider: provider)
            let keychainValue = try credential.keychainValue()
            var metadata: [String: String] = [:]
            if let subject = credential.subject { metadata["googleSubject"] = subject }
            if let email = credential.email { metadata["googleEmail"] = email }
            isConnecting = false
            await connect(provider: provider, credential: keychainValue, metadata: metadata)
        } catch {
            isConnecting = false
            self.error = error.localizedDescription
        }
    }

    func switchAccount(to id: UUID) {
        guard accounts.contains(where: { $0.id == id }) else { return }
        activeAccountID = id
        error = refreshErrors[id]
        KeychainHelper.saveActiveSiteIntegrationAccountID(id)
    }

    func removeAccount(id: UUID) {
        accounts.removeAll { $0.id == id }
        snapshots[id] = nil
        refreshErrors[id] = nil
        if activeAccountID == id { activeAccountID = accounts.first?.id }
        updateVisibleRefreshError(preferredAccountID: activeAccountID)
        persist()
    }

    func remove(id: UUID) {
        removeAccount(id: id)
    }

    func removeAll() {
        accounts = []
        snapshots = [:]
        refreshErrors = [:]
        activeAccountID = nil
        error = nil
        persist()
    }

    func snapshot(for accountID: UUID? = nil) -> SiteIntegrationSnapshot? {
        guard let id = accountID ?? activeAccountID else { return nil }
        return snapshots[id]
    }

    func refresh(accountID: UUID? = nil, force: Bool = false) async {
        guard let id = accountID ?? activeAccountID,
              let savedAccount = accounts.first(where: { $0.id == id }) else { return }
        if !force,
           let cached = snapshots[id],
           Date.now.timeIntervalSince(cached.updatedAt) < cacheLifetime(for: savedAccount.provider) {
            return
        }
        guard refreshingAccountIDs.insert(id).inserted else { return }

        if refreshErrors.isEmpty { error = nil }
        defer { refreshingAccountIDs.remove(id) }
        do {
            let requestAccount = try await accountWithFreshGoogleCredential(savedAccount)
            do {
                snapshots[id] = try await SiteIntegrationsAPI(account: requestAccount).fetchSnapshot(accountID: id)
            } catch let apiError as SiteIntegrationsAPIError {
                guard case .requestFailed(let status, _) = apiError,
                      status == 401,
                      requestAccount.provider == .googleSearchConsole
                        || requestAccount.provider == .googleAnalytics else {
                    throw apiError
                }
                let retryAccount = try await accountWithFreshGoogleCredential(requestAccount, force: true)
                snapshots[id] = try await SiteIntegrationsAPI(account: retryAccount).fetchSnapshot(accountID: id)
            }
            refreshErrors[id] = nil
            updateVisibleRefreshError(preferredAccountID: id)
            persistSnapshots()
        } catch {
            refreshErrors[id] = error.localizedDescription
            updateVisibleRefreshError(preferredAccountID: id)
        }
    }

    private func accountWithFreshGoogleCredential(
        _ account: SiteIntegrationAccount,
        force: Bool = false
    ) async throws -> SiteIntegrationAccount {
        guard account.provider == .googleSearchConsole || account.provider == .googleAnalytics else {
            return account
        }
        let storedCredential = try GoogleOAuthCredential.fromKeychainValue(account.credential)
        guard force || storedCredential.needsRefresh else { return account }

        let refreshed = try await GoogleOAuthService.shared.refreshedCredential(storedCredential, force: force)
        var updatedAccount = account
        updatedAccount.credential = try refreshed.keychainValue()
        if let index = accounts.firstIndex(where: { $0.id == account.id }) {
            accounts[index] = updatedAccount
            KeychainHelper.saveSiteIntegrationAccounts(accounts)
        }
        return updatedAccount
    }

    private func isSameConnection(
        _ account: SiteIntegrationAccount,
        provider: SiteIntegrationProvider,
        credential: String,
        metadata: [String: String]
    ) -> Bool {
        guard account.provider == provider else { return false }
        switch provider {
        case .pageSpeed:
            return account.metadata["siteURL"]?.caseInsensitiveCompare(metadata["siteURL"] ?? "") == .orderedSame
        case .plausible:
            return account.metadata["siteID"]?.caseInsensitiveCompare(metadata["siteID"] ?? "") == .orderedSame
        case .umami:
            return account.credential == credential
                && account.metadata["authMode"] == metadata["authMode"]
                && (account.metadata["baseURL"] ?? "").caseInsensitiveCompare(metadata["baseURL"] ?? "") == .orderedSame
        case .googleSearchConsole, .googleAnalytics:
            if let subject = metadata["googleSubject"] {
                return account.metadata["googleSubject"] == subject
            }
            return account.credential == credential
        case .bingWebmaster, .clarity, .uptimeRobot, .betterStack:
            return account.credential == credential
        }
    }

    private func cacheLifetime(for provider: SiteIntegrationProvider) -> TimeInterval {
        switch provider {
        case .googleSearchConsole, .googleAnalytics: 5 * 60
        case .pageSpeed: 30 * 60
        case .bingWebmaster: 15 * 60
        case .clarity: 6 * 60 * 60
        case .plausible, .umami: 5 * 60
        case .uptimeRobot, .betterStack: 2 * 60
        }
    }

    private func persist() {
        KeychainHelper.saveSiteIntegrationAccounts(accounts)
        KeychainHelper.saveActiveSiteIntegrationAccountID(activeAccountID)
        persistSnapshots()
    }

    private func persistSnapshots() {
        let accountIDs = Set(accounts.map(\.id))
        KeychainHelper.saveSiteIntegrationSnapshots(
            snapshots.values.filter { accountIDs.contains($0.accountID) }
        )
    }

    private func updateVisibleRefreshError(preferredAccountID: UUID?) {
        if let preferredAccountID, let preferred = refreshErrors[preferredAccountID] {
            error = preferred
        } else if let activeAccountID, let active = refreshErrors[activeAccountID] {
            error = active
        } else {
            error = refreshErrors.values.first
        }
    }
}
