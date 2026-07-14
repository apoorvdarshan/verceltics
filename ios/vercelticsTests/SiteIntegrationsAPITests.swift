import XCTest
@testable import verceltics

@MainActor
final class SiteIntegrationsAPITests: XCTestCase {
    func testProviderCatalogHasExpectedOAuthBoundary() {
        XCTAssertEqual(SiteIntegrationProvider.allCases.count, 9)
        XCTAssertEqual(
            Set(SiteIntegrationProvider.allCases.filter(\.isOAuthPending)),
            Set([.googleSearchConsole, .googleAnalytics])
        )
    }

    func testSiteAccountAndSnapshotRoundTrip() throws {
        let account = SiteIntegrationAccount(
            provider: .plausible,
            name: "example.com",
            credential: "secret",
            metadata: ["siteID": "example.com"]
        )
        let data = try JSONEncoder().encode(account)
        XCTAssertEqual(try JSONDecoder().decode(SiteIntegrationAccount.self, from: data), account)

        let snapshot = SiteIntegrationSnapshot(
            accountID: account.id,
            provider: account.provider,
            metrics: [
                SiteIntegrationMetric(
                    key: "visitors",
                    label: "Visitors",
                    value: 42,
                    unit: .count
                )
            ],
            status: "Connected"
        )
        let snapshotData = try JSONEncoder().encode(snapshot)
        XCTAssertEqual(try JSONDecoder().decode(SiteIntegrationSnapshot.self, from: snapshotData), snapshot)
    }

    func testHTTPSBaseURLValidation() throws {
        XCTAssertThrowsError(try SiteIntegrationsAPI.normalizedHTTPSBaseURL("http://analytics.example.com"))
        XCTAssertThrowsError(try SiteIntegrationsAPI.normalizedHTTPSBaseURL("https://user:password@analytics.example.com"))
        let value = try SiteIntegrationsAPI.normalizedHTTPSBaseURL("https://analytics.example.com/root?token=secret#fragment")
        XCTAssertEqual(value.absoluteString, "https://analytics.example.com/root")
    }

    func testOriginDropsPathQueryAndCredentials() {
        let input = URL(string: "https://user:password@example.com:8443/path?q=1#part")!
        XCTAssertEqual(
            SiteIntegrationsAPI.originURL(from: input)?.absoluteString,
            "https://example.com:8443"
        )
    }

    func testResourcePathComponentEncodesSeparators() throws {
        XCTAssertEqual(
            try SiteIntegrationsAPI.pathComponent("site/id ?#"),
            "site%2Fid%20%3F%23"
        )
        XCTAssertEqual(
            try SiteIntegrationsAPI.pathComponent("https://example.com/"),
            "https%3A%2F%2Fexample.com%2F"
        )
    }

    func testGoogleOAuthCredentialRoundTrip() throws {
        let credential = GoogleOAuthCredential(
            accessToken: "access-token",
            refreshToken: "refresh-token",
            tokenType: "Bearer",
            scopes: ["scope-a", "scope-b"],
            expiresAt: Date(timeIntervalSince1970: 1_900_000_000),
            subject: "google-subject",
            email: "owner@example.com"
        )
        XCTAssertEqual(
            try GoogleOAuthCredential.fromKeychainValue(credential.keychainValue()),
            credential
        )
    }

    func testGoogleProvidersFailBeforeNetworkWithPreciseOAuthError() async {
        let api = SiteIntegrationsAPI(provider: .googleSearchConsole, credential: "")
        do {
            _ = try await api.fetchSnapshot()
            XCTFail("Google Search Console should remain unavailable until OAuth is configured.")
        } catch let error as SiteIntegrationsAPIError {
            XCTAssertEqual(
                error,
                .oauthNotConfigured(
                    provider: .googleSearchConsole,
                    scopes: SiteIntegrationsAPI.googleSearchConsoleOAuth.scopes
                )
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }
}
