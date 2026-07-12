import Foundation
import Security

enum KeychainHelper {
    private static let service = "com.apoorvdarshan.verceltics"
    private static let accountsKey = "vercel_accounts"
    private static let activeAccountIdKey = "active_account_id"
    private static let longAnalyticsHistoryAccountIdsKey = "long_analytics_history_account_ids"

    static func saveAccounts(_ accounts: [VercelAccount]) {
        do {
            let data = try JSONEncoder().encode(accounts)
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: accountsKey
            ]
            let attributes: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            ]
            let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
            if updateStatus == errSecItemNotFound {
                var addQuery = query
                attributes.forEach { addQuery[$0.key] = $0.value }
                let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
                if addStatus != errSecSuccess {
                    print("Failed to save accounts to Keychain: \(addStatus)")
                }
            } else if updateStatus != errSecSuccess {
                print("Failed to update accounts in Keychain: \(updateStatus)")
            }
        } catch {
            print("Failed to encode accounts: \(error)")
        }
    }

    static func getAccounts() -> [VercelAccount] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountsKey,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return [] }
        return (try? JSONDecoder().decode([VercelAccount].self, from: data)) ?? []
    }

    static func saveActiveAccountId(_ id: UUID?) {
        guard let id = id else {
            UserDefaults.standard.removeObject(forKey: activeAccountIdKey)
            return
        }
        UserDefaults.standard.set(id.uuidString, forKey: activeAccountIdKey)
    }

    static func getActiveAccountId() -> UUID? {
        guard let string = UserDefaults.standard.string(forKey: activeAccountIdKey) else { return nil }
        return UUID(uuidString: string)
    }

    static func saveLongAnalyticsHistoryAccountIds(_ ids: Set<UUID>) {
        UserDefaults.standard.set(ids.map(\.uuidString), forKey: longAnalyticsHistoryAccountIdsKey)
    }

    static func getLongAnalyticsHistoryAccountIds() -> Set<UUID> {
        let strings = UserDefaults.standard.stringArray(forKey: longAnalyticsHistoryAccountIdsKey) ?? []
        return Set(strings.compactMap(UUID.init(uuidString:)))
    }

    static func deleteEverything() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]
        SecItemDelete(query as CFDictionary)
        UserDefaults.standard.removeObject(forKey: activeAccountIdKey)
        UserDefaults.standard.removeObject(forKey: longAnalyticsHistoryAccountIdsKey)
    }
}
