import Foundation
import Observation

@Observable
@MainActor
final class RegistrarStore {
    var accounts: [RegistrarAccount]
    var activeAccountID: UUID?
    var isConnecting = false
    var error: String?
    private var accountPersistenceFailure: String?

    var activeAccount: RegistrarAccount? {
        accounts.first { $0.id == activeAccountID }
    }

    init() {
        do {
            accounts = try KeychainHelper.getRegistrarAccounts()
        } catch {
            accounts = []
            accountPersistenceFailure = error.localizedDescription
            self.error = error.localizedDescription
        }
        activeAccountID = KeychainHelper.getActiveRegistrarAccountID()
        if activeAccount == nil, let first = accounts.first {
            activeAccountID = first.id
            KeychainHelper.saveActiveRegistrarAccountID(first.id)
        }
    }

    func connect(
        provider: RegistrarProvider,
        primaryCredential: String,
        secondaryCredential: String? = nil,
        metadata: [String: String] = [:]
    ) async {
        guard ensureAccountPersistenceIsAvailable() else { return }
        isConnecting = true
        error = nil
        defer { isConnecting = false }
        let primary = primaryCredential.trimmingCharacters(in: .whitespacesAndNewlines)
        let secondary = secondaryCredential?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanMetadata = metadata.reduce(into: [String: String]()) { result, pair in
            result[pair.key] = pair.value.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        do {
            let api = RegistrarAPI(
                provider: provider,
                primaryCredential: primary,
                secondaryCredential: secondary,
                metadata: cleanMetadata
            )
            let identity = try await api.validateCredentials()
            var updatedAccounts = accounts
            let updatedActiveID: UUID
            if let index = Self.matchingAccountIndex(
                in: accounts,
                provider: provider,
                primaryCredential: primary,
                metadata: cleanMetadata
            ) {
                updatedAccounts[index].name = identity
                updatedAccounts[index].primaryCredential = primary
                updatedAccounts[index].secondaryCredential = secondary
                updatedAccounts[index].metadata = cleanMetadata
                updatedActiveID = updatedAccounts[index].id
            } else {
                let account = RegistrarAccount(
                    provider: provider,
                    name: identity,
                    primaryCredential: primary,
                    secondaryCredential: secondary,
                    metadata: cleanMetadata
                )
                updatedAccounts.append(account)
                updatedActiveID = account.id
            }
            try persist(updatedAccounts, activeAccountID: updatedActiveID)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func switchAccount(to id: UUID) {
        guard activeAccountID != id,
              accounts.contains(where: { $0.id == id }) else { return }
        activeAccountID = id
        KeychainHelper.saveActiveRegistrarAccountID(id)
    }

    func removeAccount(id: UUID) {
        guard ensureAccountPersistenceIsAvailable() else { return }
        let updatedAccounts = accounts.filter { $0.id != id }
        guard updatedAccounts.count != accounts.count else { return }
        let updatedActiveID = activeAccountID == id ? updatedAccounts.first?.id : activeAccountID
        do {
            try persist(updatedAccounts, activeAccountID: updatedActiveID)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func removeAll() {
        guard ensureAccountPersistenceIsAvailable() else { return }
        do {
            try persist([], activeAccountID: nil)
        } catch {
            self.error = error.localizedDescription
        }
    }

    nonisolated static func matchingAccountIndex(
        in accounts: [RegistrarAccount],
        provider: RegistrarProvider,
        primaryCredential: String,
        metadata: [String: String]
    ) -> Int? {
        if let exactMatch = accounts.firstIndex(where: {
            $0.provider == provider && $0.primaryCredential == primaryCredential
        }) {
            return exactMatch
        }

        let stableMetadataKey: String?
        switch provider {
        case .nameDotCom, .namecheap:
            stableMetadataKey = "username"
        case .gandi:
            stableMetadataKey = "organization"
        case .porkbun, .spaceship, .dynadot, .nameSilo, .goDaddy:
            stableMetadataKey = nil
        }
        guard let stableMetadataKey,
              let stableValue = normalizedIdentity(metadata[stableMetadataKey]),
              !stableValue.isEmpty else { return nil }
        return accounts.firstIndex {
            $0.provider == provider
                && normalizedIdentity($0.metadata[stableMetadataKey]) == stableValue
        }
    }

    nonisolated private static func normalizedIdentity(_ value: String?) -> String? {
        value?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    private func ensureAccountPersistenceIsAvailable() -> Bool {
        guard let accountPersistenceFailure else { return true }
        error = accountPersistenceFailure
        return false
    }

    private func persist(
        _ updatedAccounts: [RegistrarAccount],
        activeAccountID updatedActiveID: UUID?
    ) throws {
        try KeychainHelper.saveRegistrarAccounts(updatedAccounts)
        AppMemoryCacheRegistry.resetAll()
        accounts = updatedAccounts
        activeAccountID = updatedActiveID
        KeychainHelper.saveActiveRegistrarAccountID(updatedActiveID)
    }
}
