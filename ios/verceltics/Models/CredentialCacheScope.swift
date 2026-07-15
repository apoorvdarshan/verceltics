import CryptoKit
import Foundation

/// Creates deterministic, credential-scoped cache identifiers without ever
/// retaining a credential in a cache key or exposing it through UI state.
nonisolated enum CredentialCacheScope {
    static func hostingAccount(_ account: VercelAccount) -> String {
        fingerprint(
            fields: [
                "hosting-account",
                account.id.uuidString.lowercased(),
                account.provider.rawValue,
                account.token,
                canonicalMetadata(account.providerMetadata)
            ]
        )
    }

    static func registrarAccount(_ account: RegistrarAccount) -> String {
        fingerprint(
            fields: [
                "registrar-account",
                account.id.uuidString.lowercased(),
                account.provider.rawValue,
                account.primaryCredential,
                account.secondaryCredential ?? "",
                canonicalMetadata(account.metadata)
            ]
        )
    }

    static func cloudflare(
        authenticationMode: CloudflareAuthenticationMode,
        email: String?,
        credential: String
    ) -> String {
        fingerprint(
            fields: [
                "cloudflare",
                authenticationMode.rawValue,
                email?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? "",
                credential.trimmingCharacters(in: .whitespacesAndNewlines)
            ]
        )
    }

    static func fingerprint(fields: [String]) -> String {
        var canonical = Data()
        for field in fields {
            let bytes = Data(field.utf8)
            var length = UInt64(bytes.count).bigEndian
            withUnsafeBytes(of: &length) { canonical.append(contentsOf: $0) }
            canonical.append(bytes)
        }
        return SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
    }

    private static func canonicalMetadata(_ metadata: [String: String]) -> String {
        metadata.keys.sorted().map { key in
            let value = metadata[key] ?? ""
            return "\(key.utf8.count):\(key)\(value.utf8.count):\(value)"
        }
        .joined()
    }
}
