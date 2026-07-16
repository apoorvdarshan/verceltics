import XCTest
@testable import verceltics

final class CredentialCacheScopeTests: XCTestCase {
    func testHostingScopeChangesWhenCredentialRotatesAndNeverContainsCredential() {
        let id = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
        let original = VercelAccount(
            id: id,
            name: "Example",
            token: "super-secret-token",
            provider: .netlify,
            providerMetadata: ["team": "example"]
        )
        var rotated = original
        rotated.token = "replacement-secret-token"

        let originalScope = CredentialCacheScope.hostingAccount(original)
        let rotatedScope = CredentialCacheScope.hostingAccount(rotated)

        XCTAssertNotEqual(originalScope, rotatedScope)
        XCTAssertFalse(originalScope.contains(original.token))
        XCTAssertFalse(rotatedScope.contains(rotated.token))
        XCTAssertEqual(originalScope.count, 64)
    }

    func testRegistrarScopeIncludesBothCredentialsAndCanonicalMetadata() {
        let id = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
        let first = RegistrarAccount(
            id: id,
            provider: .porkbun,
            name: "Example",
            primaryCredential: "api-key",
            secondaryCredential: "secret-key",
            metadata: ["second": "2", "first": "1"]
        )
        let reordered = RegistrarAccount(
            id: id,
            provider: .porkbun,
            name: "Example",
            primaryCredential: "api-key",
            secondaryCredential: "secret-key",
            metadata: ["first": "1", "second": "2"]
        )
        var rotated = first
        rotated.secondaryCredential = "replacement-secret"

        let firstScope = CredentialCacheScope.registrarAccount(first)

        XCTAssertEqual(firstScope, CredentialCacheScope.registrarAccount(reordered))
        XCTAssertNotEqual(firstScope, CredentialCacheScope.registrarAccount(rotated))
        XCTAssertFalse(firstScope.contains(first.primaryCredential))
        XCTAssertFalse(firstScope.contains(first.secondaryCredential!))
    }

    func testCloudflareScopeNormalizesIdentityAndScopesCredential() {
        let first = CredentialCacheScope.cloudflare(
            authenticationMode: .globalAPIKey,
            email: " User@Example.com ",
            credential: " cloudflare-secret "
        )
        let normalized = CredentialCacheScope.cloudflare(
            authenticationMode: .globalAPIKey,
            email: "user@example.com",
            credential: "cloudflare-secret"
        )
        let rotated = CredentialCacheScope.cloudflare(
            authenticationMode: .globalAPIKey,
            email: "user@example.com",
            credential: "new-cloudflare-secret"
        )

        XCTAssertEqual(first, normalized)
        XCTAssertNotEqual(first, rotated)
        XCTAssertFalse(first.contains("cloudflare-secret"))
    }

    func testSiteIntegrationScopeChangesOnCredentialRotationWithoutExposingSecret() {
        let id = UUID(uuidString: "00000000-0000-0000-0000-000000000003")!
        let original = SiteIntegrationAccount(
            id: id,
            provider: .googleSearchConsole,
            name: "Search Console",
            credential: "google-oauth-secret",
            metadata: ["email": "user@example.com"]
        )
        var rotated = original
        rotated.credential = "rotated-google-oauth-secret"

        let originalScope = CredentialCacheScope.siteIntegrationAccount(original)
        let rotatedScope = CredentialCacheScope.siteIntegrationAccount(rotated)

        XCTAssertNotEqual(originalScope, rotatedScope)
        XCTAssertFalse(originalScope.contains(original.credential))
        XCTAssertFalse(rotatedScope.contains(rotated.credential))
        XCTAssertEqual(originalScope.count, 64)
    }
}
