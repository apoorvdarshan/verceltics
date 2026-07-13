import Foundation
import Observation

@Observable
@MainActor
final class RegistrarStore {
    var accounts: [RegistrarAccount]
    var activeAccountID: UUID?
    var isConnecting = false
    var error: String?

    var activeAccount: RegistrarAccount? {
        accounts.first { $0.id == activeAccountID }
    }

    init() {
        accounts = KeychainHelper.getRegistrarAccounts()
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
        isConnecting = true
        error = nil
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
            if let index = accounts.firstIndex(where: {
                $0.provider == provider && $0.primaryCredential == primary
            }) {
                accounts[index].name = identity
                accounts[index].primaryCredential = primary
                accounts[index].secondaryCredential = secondary
                accounts[index].metadata = cleanMetadata
                activeAccountID = accounts[index].id
            } else {
                let account = RegistrarAccount(
                    provider: provider,
                    name: identity,
                    primaryCredential: primary,
                    secondaryCredential: secondary,
                    metadata: cleanMetadata
                )
                accounts.append(account)
                activeAccountID = account.id
            }
            persist()
        } catch {
            self.error = error.localizedDescription
        }
        isConnecting = false
    }

    func switchAccount(to id: UUID) {
        guard accounts.contains(where: { $0.id == id }) else { return }
        activeAccountID = id
        KeychainHelper.saveActiveRegistrarAccountID(id)
    }

    func removeAccount(id: UUID) {
        accounts.removeAll { $0.id == id }
        if activeAccountID == id { activeAccountID = accounts.first?.id }
        persist()
    }

    func removeAll() {
        accounts = []
        activeAccountID = nil
        persist()
    }

    private func persist() {
        KeychainHelper.saveRegistrarAccounts(accounts)
        KeychainHelper.saveActiveRegistrarAccountID(activeAccountID)
    }
}
