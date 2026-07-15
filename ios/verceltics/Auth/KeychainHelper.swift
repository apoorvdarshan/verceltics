import Foundation
import Security

enum KeychainHelper {
    private static let service = "com.apoorvdarshan.verceltics"
    private static let accountsKey = "vercel_accounts"
    private static let activeAccountIdKey = "active_account_id"
    private static let longAnalyticsHistoryAccountIdsKey = "long_analytics_history_account_ids"
    private static let registrarAccountsKey = "registrar_accounts"
    private static let activeRegistrarAccountIdKey = "active_registrar_account_id"
    private static let siteIntegrationAccountsKey = "site_integration_accounts"
    private static let siteIntegrationSnapshotsKey = "site_integration_snapshots"
    private static let activeSiteIntegrationAccountIdKey = "active_site_integration_account_id"

    static func saveAccounts(_ accounts: [VercelAccount]) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(accounts)
        } catch {
            throw ConnectedAccountPersistenceError.encoding(.hosting, error)
        }
        do {
            try writeKeychainData(data, account: accountsKey)
        } catch let error as KeychainDataError {
            throw error.persistenceError(for: .hosting)
        }
    }

    static func getAccounts() throws -> [VercelAccount] {
        let data: Data?
        do {
            data = try readKeychainDataWithStatus(account: accountsKey)
        } catch let error as KeychainDataError {
            throw error.persistenceError(for: .hosting)
        }
        guard let data else { return [] }
        do {
            return try JSONDecoder().decode([VercelAccount].self, from: data)
        } catch {
            throw ConnectedAccountPersistenceError.decoding(.hosting, error)
        }
    }

    static func saveRegistrarAccounts(_ accounts: [RegistrarAccount]) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(accounts)
        } catch {
            throw ConnectedAccountPersistenceError.encoding(.registrar, error)
        }
        do {
            try writeKeychainData(data, account: registrarAccountsKey)
        } catch let error as KeychainDataError {
            throw error.persistenceError(for: .registrar)
        }
    }

    static func getRegistrarAccounts() throws -> [RegistrarAccount] {
        let data: Data?
        do {
            data = try readKeychainDataWithStatus(account: registrarAccountsKey)
        } catch let error as KeychainDataError {
            throw error.persistenceError(for: .registrar)
        }
        guard let data else { return [] }
        do {
            return try JSONDecoder().decode([RegistrarAccount].self, from: data)
        } catch {
            throw ConnectedAccountPersistenceError.decoding(.registrar, error)
        }
    }

    static func saveActiveRegistrarAccountID(_ id: UUID?) {
        guard let id else {
            UserDefaults.standard.removeObject(forKey: activeRegistrarAccountIdKey)
            return
        }
        UserDefaults.standard.set(id.uuidString, forKey: activeRegistrarAccountIdKey)
    }

    static func getActiveRegistrarAccountID() -> UUID? {
        guard let value = UserDefaults.standard.string(forKey: activeRegistrarAccountIdKey) else { return nil }
        return UUID(uuidString: value)
    }

    static func saveSiteIntegrationAccounts(_ accounts: [SiteIntegrationAccount]) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(accounts)
        } catch {
            throw SiteIntegrationAccountPersistenceError.encoding(error)
        }
        try saveKeychainDataThrowing(data, account: siteIntegrationAccountsKey)
    }

    static func getSiteIntegrationAccounts() throws -> [SiteIntegrationAccount] {
        guard let data = try readKeychainDataThrowing(account: siteIntegrationAccountsKey) else { return [] }
        do {
            return try JSONDecoder().decode([SiteIntegrationAccount].self, from: data)
        } catch {
            throw SiteIntegrationAccountPersistenceError.decoding(error)
        }
    }

    static func saveSiteIntegrationSnapshots(_ snapshots: [SiteIntegrationSnapshot]) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(snapshots)
        } catch {
            throw SiteIntegrationSnapshotCacheError.encoding(error)
        }
        let fileURL = try siteIntegrationSnapshotsFileURL()
        do {
            try data.write(to: fileURL, options: .atomic)
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: fileURL.path
            )
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            var protectedFileURL = fileURL
            try protectedFileURL.setResourceValues(resourceValues)
        } catch {
            throw SiteIntegrationSnapshotCacheError.writing(error)
        }
    }

    static func getSiteIntegrationSnapshots() throws -> [SiteIntegrationSnapshot] {
        let fileURL = try siteIntegrationSnapshotsFileURL()
        if FileManager.default.fileExists(atPath: fileURL.path) {
            do {
                let data = try Data(contentsOf: fileURL)
                return try JSONDecoder().decode([SiteIntegrationSnapshot].self, from: data)
            } catch {
                throw SiteIntegrationSnapshotCacheError.reading(error)
            }
        }

        // Versions before the file-backed cache kept every non-secret snapshot in one
        // Keychain value. Migrate it once, deleting the legacy value only after the
        // protected file has been written successfully.
        guard let legacyData = readKeychainData(account: siteIntegrationSnapshotsKey) else { return [] }
        let snapshots: [SiteIntegrationSnapshot]
        do {
            snapshots = try JSONDecoder().decode([SiteIntegrationSnapshot].self, from: legacyData)
        } catch {
            throw SiteIntegrationSnapshotCacheError.reading(error)
        }
        try saveSiteIntegrationSnapshots(snapshots)
        deleteKeychainData(account: siteIntegrationSnapshotsKey)
        return snapshots
    }

    static func saveActiveSiteIntegrationAccountID(_ id: UUID?) {
        guard let id else {
            UserDefaults.standard.removeObject(forKey: activeSiteIntegrationAccountIdKey)
            return
        }
        UserDefaults.standard.set(id.uuidString, forKey: activeSiteIntegrationAccountIdKey)
    }

    static func getActiveSiteIntegrationAccountID() -> UUID? {
        guard let value = UserDefaults.standard.string(forKey: activeSiteIntegrationAccountIdKey) else { return nil }
        return UUID(uuidString: value)
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

    private static func saveKeychainDataThrowing(_ data: Data, account: String) throws {
        do {
            try writeKeychainData(data, account: account)
        } catch let error as KeychainDataError {
            switch error {
            case .read(let status):
                throw SiteIntegrationAccountPersistenceError.keychainRead(status)
            case .write(let status):
                throw SiteIntegrationAccountPersistenceError.keychain(status)
            }
        }
    }

    private static func writeKeychainData(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var addQuery = query
            attributes.forEach { addQuery[$0.key] = $0.value }
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainDataError.write(addStatus)
            }
        } else if status != errSecSuccess {
            throw KeychainDataError.write(status)
        }
    }

    private static func readKeychainData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func readKeychainDataThrowing(account: String) throws -> Data? {
        do {
            return try readKeychainDataWithStatus(account: account)
        } catch let error as KeychainDataError {
            switch error {
            case .read(let status):
                throw SiteIntegrationAccountPersistenceError.keychainRead(status)
            case .write(let status):
                throw SiteIntegrationAccountPersistenceError.keychain(status)
            }
        }
    }

    private static func readKeychainDataWithStatus(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainDataError.read(status)
        }
        return data
    }

    private static func deleteKeychainData(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }

    private static func siteIntegrationSnapshotsFileURL() throws -> URL {
        guard let applicationSupport = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw SiteIntegrationSnapshotCacheError.applicationSupportUnavailable
        }
        let directory = applicationSupport
            .appendingPathComponent("Verceltics", isDirectory: true)
            .appendingPathComponent("SiteCache", isDirectory: true)
        do {
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            var protectedDirectory = directory
            try protectedDirectory.setResourceValues(resourceValues)
        } catch {
            throw SiteIntegrationSnapshotCacheError.writing(error)
        }
        return directory.appendingPathComponent("site-integration-snapshots.json", isDirectory: false)
    }
}

enum SiteIntegrationSnapshotCacheError: LocalizedError {
    case applicationSupportUnavailable
    case encoding(Error)
    case reading(Error)
    case writing(Error)

    var errorDescription: String? {
        switch self {
        case .applicationSupportUnavailable:
            "The device cache directory is unavailable."
        case .encoding:
            "The Sites dashboard cache could not be encoded."
        case .reading:
            "The Sites dashboard cache could not be read."
        case .writing:
            "The Sites dashboard cache could not be saved."
        }
    }
}

enum SiteIntegrationAccountPersistenceError: LocalizedError {
    case encoding(Error)
    case decoding(Error)
    case keychainRead(OSStatus)
    case keychain(OSStatus)

    var errorDescription: String? {
        switch self {
        case .encoding:
            "The connected Sites account could not be encoded."
        case .decoding:
            "The connected Sites accounts could not be read securely. Your saved credentials were left unchanged."
        case .keychainRead(let status):
            if let message = SecCopyErrorMessageString(status, nil) as String? {
                "The connected Sites accounts could not be read securely: \(message)"
            } else {
                "The connected Sites accounts could not be read securely (Keychain error \(status))."
            }
        case .keychain(let status):
            if let message = SecCopyErrorMessageString(status, nil) as String? {
                "The connected Sites account could not be saved securely: \(message)"
            } else {
                "The connected Sites account could not be saved securely (Keychain error \(status))."
            }
        }
    }
}

enum ConnectedAccountPersistenceScope {
    case hosting
    case registrar

    var displayName: String {
        switch self {
        case .hosting: "hosting"
        case .registrar: "registrar"
        }
    }
}

enum ConnectedAccountPersistenceError: LocalizedError {
    case encoding(ConnectedAccountPersistenceScope, Error)
    case decoding(ConnectedAccountPersistenceScope, Error)
    case keychainRead(ConnectedAccountPersistenceScope, OSStatus)
    case keychainWrite(ConnectedAccountPersistenceScope, OSStatus)

    var errorDescription: String? {
        switch self {
        case .encoding(let scope, _):
            "The connected \(scope.displayName) account could not be encoded."
        case .decoding(let scope, _):
            "The connected \(scope.displayName) accounts could not be read securely. Your saved credentials were left unchanged."
        case .keychainRead(let scope, let status):
            if let message = SecCopyErrorMessageString(status, nil) as String? {
                "The connected \(scope.displayName) accounts could not be read securely: \(message)"
            } else {
                "The connected \(scope.displayName) accounts could not be read securely (Keychain error \(status))."
            }
        case .keychainWrite(let scope, let status):
            if let message = SecCopyErrorMessageString(status, nil) as String? {
                "The connected \(scope.displayName) accounts could not be saved securely: \(message)"
            } else {
                "The connected \(scope.displayName) accounts could not be saved securely (Keychain error \(status))."
            }
        }
    }
}

private enum KeychainDataError: Error {
    case read(OSStatus)
    case write(OSStatus)

    func persistenceError(for scope: ConnectedAccountPersistenceScope) -> ConnectedAccountPersistenceError {
        switch self {
        case .read(let status): .keychainRead(scope, status)
        case .write(let status): .keychainWrite(scope, status)
        }
    }
}
