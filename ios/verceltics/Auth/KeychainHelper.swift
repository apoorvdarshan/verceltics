import Foundation
import Security

enum KeychainHelper {
    private static let service = "com.apoorvdarshan.verceltics"
    private static let accountsKey = "vercel_accounts"
    private static let activeAccountIdKey = "active_account_id"

    static func saveAccounts(_ accounts: [VercelAccount]) {
        do {
            let data = try JSONEncoder().encode(accounts)
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: accountsKey
            ]
            SecItemDelete(query as CFDictionary)
            var addQuery = query
            addQuery[kSecValueData as String] = data
            SecItemAdd(addQuery as CFDictionary, nil)
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

    static func deleteEverything() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service
        ]
        SecItemDelete(query as CFDictionary)
        UserDefaults.standard.removeObject(forKey: activeAccountIdKey)
    }
}
