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
    private var refreshAttemptIDs: [UUID: UUID] = [:]
    private var accountPersistenceFailure: String?

    var isRefreshing: Bool {
        !refreshAttemptIDs.isEmpty
    }

    var activeAccount: SiteIntegrationAccount? {
        accounts.first { $0.id == activeAccountID }
    }

    init() {
        do {
            accounts = try KeychainHelper.getSiteIntegrationAccounts()
        } catch {
            accounts = []
            accountPersistenceFailure = error.localizedDescription
            self.error = error.localizedDescription
        }
        activeAccountID = KeychainHelper.getActiveSiteIntegrationAccountID()
        let accountIDs = Set(accounts.map(\.id))
        do {
            for snapshot in try KeychainHelper.getSiteIntegrationSnapshots()
            where accountIDs.contains(snapshot.accountID) {
                if let existing = snapshots[snapshot.accountID], existing.updatedAt >= snapshot.updatedAt { continue }
                snapshots[snapshot.accountID] = snapshot
            }
        } catch {
            self.error = error.localizedDescription
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
    ) async -> Bool {
        guard ensureAccountPersistenceIsAvailable() else { return false }
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
            try Task.checkCancellation()
            var connectedMetadata = cleanMetadata
            connectedMetadata.merge(result.connectionMetadata) { _, discovered in discovered }
            var resolvedIndex = existingIndex
            if provider == .umami,
               let userID = connectedMetadata["umamiUserID"],
               let endpoint = normalizedUmamiEndpoint(connectedMetadata["umamiEndpoint"]) {
                resolvedIndex = accounts.firstIndex {
                    $0.provider == .umami
                        && $0.metadata["umamiUserID"] == userID
                        && normalizedUmamiEndpoint($0.metadata["umamiEndpoint"]) == endpoint
                } ?? resolvedIndex
            }
            var persistedMetadata = resolvedIndex.map { accounts[$0].metadata } ?? [:]
            persistedMetadata.merge(cleanMetadata) { _, input in input }
            persistedMetadata.merge(result.connectionMetadata) { _, discovered in discovered }
            let connectedAccount = SiteIntegrationAccount(
                id: resolvedIndex.map { accounts[$0].id } ?? candidate.id,
                provider: provider,
                name: result.name,
                credential: cleanCredential,
                metadata: persistedMetadata
            )
            let connectedSnapshot = rekeyedSnapshot(
                result.snapshot,
                accountID: connectedAccount.id
            )
            var updatedAccounts = accounts
            if let resolvedIndex {
                updatedAccounts[resolvedIndex] = connectedAccount
            } else {
                updatedAccounts.append(connectedAccount)
            }
            try KeychainHelper.saveSiteIntegrationAccounts(updatedAccounts)
            AppMemoryCacheRegistry.resetAll()

            invalidateRefresh(for: connectedAccount.id)
            accounts = updatedAccounts
            activeAccountID = connectedAccount.id
            snapshots[connectedAccount.id] = connectedSnapshot
            refreshErrors[connectedAccount.id] = nil
            error = nil
            KeychainHelper.saveActiveSiteIntegrationAccountID(connectedAccount.id)
            persistSnapshots()
            return true
        } catch is CancellationError {
            return false
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func connectGoogle(provider: SiteIntegrationProvider) async -> Bool {
        guard provider == .googleSearchConsole || provider == .googleAnalytics else { return false }
        guard ensureAccountPersistenceIsAvailable() else { return false }
        isConnecting = true
        error = nil
        do {
            let credential = try await GoogleOAuthService.shared.authorize(provider: provider)
            try Task.checkCancellation()
            let keychainValue = try credential.keychainValue()
            var metadata: [String: String] = [:]
            if let subject = credential.subject { metadata["googleSubject"] = subject }
            if let email = credential.email { metadata["googleEmail"] = email }
            isConnecting = false
            return await connect(provider: provider, credential: keychainValue, metadata: metadata)
        } catch is CancellationError {
            isConnecting = false
            return false
        } catch let oauthError as GoogleOAuthError where oauthError == .authorizationCancelled {
            isConnecting = false
            return false
        } catch {
            isConnecting = false
            self.error = error.localizedDescription
            return false
        }
    }

    func switchAccount(to id: UUID) {
        guard activeAccountID != id,
              accounts.contains(where: { $0.id == id }) else { return }
        activeAccountID = id
        error = refreshErrors[id]
        KeychainHelper.saveActiveSiteIntegrationAccountID(id)
    }

    func clearTransientError() {
        guard accountPersistenceFailure == nil else { return }
        error = nil
    }

    func removeAccount(id: UUID) {
        guard ensureAccountPersistenceIsAvailable() else { return }
        let updatedAccounts = accounts.filter { $0.id != id }
        guard updatedAccounts.count != accounts.count else { return }
        do {
            try KeychainHelper.saveSiteIntegrationAccounts(updatedAccounts)
        } catch {
            self.error = error.localizedDescription
            return
        }

        invalidateRefresh(for: id)
        AppMemoryCacheRegistry.resetAll()
        accounts = updatedAccounts
        snapshots[id] = nil
        refreshErrors[id] = nil
        if activeAccountID == id { activeAccountID = accounts.first?.id }
        updateVisibleRefreshError(preferredAccountID: activeAccountID)
        KeychainHelper.saveActiveSiteIntegrationAccountID(activeAccountID)
        persistSnapshots()
    }

    func remove(id: UUID) {
        removeAccount(id: id)
    }

    func removeAll() {
        guard ensureAccountPersistenceIsAvailable() else { return }
        do {
            try KeychainHelper.saveSiteIntegrationAccounts([])
        } catch {
            self.error = error.localizedDescription
            return
        }

        refreshAttemptIDs = [:]
        AppMemoryCacheRegistry.resetAll()
        accounts = []
        snapshots = [:]
        refreshErrors = [:]
        activeAccountID = nil
        error = nil
        KeychainHelper.saveActiveSiteIntegrationAccountID(nil)
        persistSnapshots()
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
        guard refreshAttemptIDs[id] == nil else { return }
        let attemptID = UUID()
        refreshAttemptIDs[id] = attemptID

        if refreshErrors.isEmpty { error = nil }
        defer {
            if refreshAttemptIDs[id] == attemptID {
                refreshAttemptIDs[id] = nil
            }
        }
        do {
            let requestAccount = try await accountWithFreshGoogleCredential(savedAccount)
            try Task.checkCancellation()
            guard isCurrentRefresh(attemptID, accountID: id) else { return }
            let fetchedSnapshot: SiteIntegrationSnapshot
            do {
                fetchedSnapshot = try await SiteIntegrationsAPI(account: requestAccount).fetchSnapshot(accountID: id)
            } catch let apiError as SiteIntegrationsAPIError {
                guard apiError.isUnauthorized,
                      [.googleSearchConsole, .googleAnalytics].contains(requestAccount.provider) else {
                    throw apiError
                }
                guard isCurrentRefresh(attemptID, accountID: id) else { return }
                let retryAccount = try await accountWithFreshGoogleCredential(requestAccount, force: true)
                try Task.checkCancellation()
                guard isCurrentRefresh(attemptID, accountID: id) else { return }
                fetchedSnapshot = try await SiteIntegrationsAPI(account: retryAccount).fetchSnapshot(accountID: id)
            }
            try Task.checkCancellation()
            guard isCurrentRefresh(attemptID, accountID: id) else { return }
            try await backfillUmamiConnectionMetadata(
                for: requestAccount,
                attemptID: attemptID
            )
            try Task.checkCancellation()
            guard isCurrentRefresh(attemptID, accountID: id) else { return }
            snapshots[id] = fetchedSnapshot
            refreshErrors[id] = nil
            updateVisibleRefreshError(preferredAccountID: id)
            persistSnapshots()
        } catch is CancellationError {
            return
        } catch {
            guard isCurrentRefresh(attemptID, accountID: id) else { return }
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
        try Task.checkCancellation()
        var updatedAccount = account
        updatedAccount.credential = try refreshed.keychainValue()
        if let index = accounts.firstIndex(where: { $0.id == account.id }) {
            guard accounts[index].credential == account.credential else {
                return accounts[index]
            }
            var updatedAccounts = accounts
            updatedAccounts[index] = updatedAccount
            try KeychainHelper.saveSiteIntegrationAccounts(updatedAccounts)
            accounts = updatedAccounts
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
            guard let current = normalizedURLIdentity(account.metadata["siteURL"]),
                  let replacement = normalizedURLIdentity(metadata["siteURL"]) else { return false }
            return current == replacement
        case .plausible:
            guard let current = normalizedSiteIdentifier(account.metadata["siteID"]),
                  let replacement = normalizedSiteIdentifier(metadata["siteID"]) else { return false }
            return current == replacement
        case .umami:
            return account.credential == credential
                && account.metadata["authMode"] == metadata["authMode"]
                && (account.metadata["baseURL"] ?? "").caseInsensitiveCompare(metadata["baseURL"] ?? "") == .orderedSame
        case .googleSearchConsole, .googleAnalytics:
            if let subject = metadata["googleSubject"] {
                return account.metadata["googleSubject"] == subject
            }
            return account.credential == credential
        case .clarity:
            if let currentSite = normalizedOriginIdentity(account.metadata["siteURL"]),
               let newSite = normalizedOriginIdentity(metadata["siteURL"]),
               let currentProject = normalizedLabelIdentity(account.metadata["projectName"]),
               let newProject = normalizedLabelIdentity(metadata["projectName"]) {
                return currentSite == newSite && currentProject == newProject
            }
            return account.credential == credential
        case .bingWebmaster, .uptimeRobot, .betterStack:
            return account.credential == credential
        }
    }

    private func backfillUmamiConnectionMetadata(
        for account: SiteIntegrationAccount,
        attemptID: UUID
    ) async throws {
        guard account.provider == .umami,
              account.metadata["umamiUserID"] == nil || account.metadata["umamiEndpoint"] == nil else {
            return
        }
        let discovered: [String: String]
        do {
            discovered = try await SiteIntegrationsAPI(account: account).connectionMetadata()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            // Identity backfill is best effort for accounts connected by older builds.
            // A healthy snapshot should remain visible when the optional /me call fails.
            return
        }
        guard let userID = discovered["umamiUserID"],
              let endpoint = normalizedUmamiEndpoint(discovered["umamiEndpoint"]),
              isCurrentRefresh(attemptID, accountID: account.id),
              let index = accounts.firstIndex(where: { $0.id == account.id }),
              accounts[index].credential == account.credential else { return }
        let identityAlreadyBelongsToAnotherAccount = accounts.contains {
            $0.id != account.id
                && $0.provider == .umami
                && $0.metadata["umamiUserID"] == userID
                && normalizedUmamiEndpoint($0.metadata["umamiEndpoint"]) == endpoint
        }
        guard !identityAlreadyBelongsToAnotherAccount else { return }

        var updatedAccount = accounts[index]
        updatedAccount.metadata.merge(discovered) { _, value in value }
        var updatedAccounts = accounts
        updatedAccounts[index] = updatedAccount
        try KeychainHelper.saveSiteIntegrationAccounts(updatedAccounts)
        guard isCurrentRefresh(attemptID, accountID: account.id) else { return }
        accounts = updatedAccounts
    }

    private func rekeyedSnapshot(
        _ snapshot: SiteIntegrationSnapshot,
        accountID: UUID
    ) -> SiteIntegrationSnapshot {
        guard snapshot.accountID != accountID else { return snapshot }
        return SiteIntegrationSnapshot(
            accountID: accountID,
            provider: snapshot.provider,
            resources: snapshot.resources,
            metrics: snapshot.metrics,
            status: snapshot.status,
            updatedAt: snapshot.updatedAt,
            warnings: snapshot.warnings
        )
    }

    private func normalizedUmamiEndpoint(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        return SiteIntegrationsAPI.canonicalEndpointIdentity(rawValue)
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

    private func persistSnapshots() {
        let accountIDs = Set(accounts.map(\.id))
        do {
            try KeychainHelper.saveSiteIntegrationSnapshots(
                snapshots.values.filter { accountIDs.contains($0.accountID) }
            )
        } catch {
            self.error = error.localizedDescription
        }
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

    private func invalidateRefresh(for accountID: UUID) {
        refreshAttemptIDs[accountID] = nil
    }

    private func isCurrentRefresh(_ attemptID: UUID, accountID: UUID) -> Bool {
        refreshAttemptIDs[accountID] == attemptID
            && accounts.contains(where: { $0.id == accountID })
    }

    private func normalizedURLIdentity(_ rawValue: String?) -> String? {
        guard let value = nonEmpty(rawValue),
              var components = URLComponents(string: value),
              let scheme = components.scheme,
              let host = components.host else { return nil }
        components.scheme = scheme.lowercased()
        components.host = host.lowercased()
        return components.string
    }

    private func normalizedSiteIdentifier(_ rawValue: String?) -> String? {
        guard let value = nonEmpty(rawValue) else { return nil }
        if value.contains("://") {
            return normalizedURLIdentity(value)
        }
        let parts = value.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false)
        guard let host = parts.first else { return value }
        return ([host.lowercased()] + parts.dropFirst().map(String.init)).joined(separator: "/")
    }

    private func normalizedOriginIdentity(_ rawValue: String?) -> String? {
        guard let value = nonEmpty(rawValue),
              var components = URLComponents(string: value),
              let scheme = components.scheme,
              let host = components.host else { return nil }
        components.scheme = scheme.lowercased()
        components.host = host.lowercased()
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.string
    }

    private func normalizedLabelIdentity(_ rawValue: String?) -> String? {
        nonEmpty(rawValue)?.lowercased()
    }

    private func ensureAccountPersistenceIsAvailable() -> Bool {
        guard let accountPersistenceFailure else { return true }
        error = accountPersistenceFailure
        return false
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
