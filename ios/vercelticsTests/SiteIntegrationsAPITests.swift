import XCTest
import UIKit
@testable import verceltics

@MainActor
final class SiteIntegrationsAPITests: XCTestCase {
    func testProviderCatalogIsCompleteAndStable() {
        let expectedProviders: [SiteIntegrationProvider] = [
            .googleSearchConsole,
            .googleAnalytics,
            .pageSpeed,
            .bingWebmaster,
            .clarity,
            .plausible,
            .umami,
            .uptimeRobot,
            .betterStack,
        ]
        XCTAssertEqual(SiteIntegrationProvider.allCases, expectedProviders)
        XCTAssertEqual(Set(SiteIntegrationProvider.allCases.map(\.rawValue)).count, expectedProviders.count)
        XCTAssertEqual(Set(SiteIntegrationProvider.allCases.map(\.displayName)).count, expectedProviders.count)
        XCTAssertEqual(Set(SiteIntegrationProvider.allCases.map(\.logoAssetName)).count, expectedProviders.count)

        for provider in SiteIntegrationProvider.allCases {
            XCTAssertFalse(provider.displayName.isEmpty, "\(provider) needs a display name")
            XCTAssertFalse(provider.connectionSubtitle.isEmpty, "\(provider) needs a connection subtitle")
            XCTAssertFalse(provider.systemImage.isEmpty, "\(provider) needs an SF Symbol fallback")
            XCTAssertEqual(provider.credentialURL?.scheme?.lowercased(), "https")
            XCTAssertNotNil(provider.credentialURL?.host)
        }
    }

    func testProviderOAuthBoundaryUsesCurrentSemantics() {
        XCTAssertEqual(
            Set(SiteIntegrationProvider.allCases.filter(\.usesOAuth)),
            Set([.googleSearchConsole, .googleAnalytics])
        )
        XCTAssertEqual(
            Set(SiteIntegrationProvider.allCases.filter { !$0.usesOAuth }),
            Set([.pageSpeed, .bingWebmaster, .clarity, .plausible, .umami, .uptimeRobot, .betterStack])
        )
    }

    func testProviderLogoMappingHasEveryCompiledAsset() throws {
        let expectedAssets: [SiteIntegrationProvider: String] = [
            .googleSearchConsole: "GoogleSearchConsoleMark",
            .googleAnalytics: "GoogleAnalyticsMark",
            .pageSpeed: "PageSpeedMark",
            .bingWebmaster: "BingWebmasterMark",
            .clarity: "MicrosoftClarityMark",
            .plausible: "PlausibleMark",
            .umami: "UmamiMark",
            .uptimeRobot: "UptimeRobotMark",
            .betterStack: "BetterStackMark",
        ]
        XCTAssertEqual(Set(expectedAssets.keys), Set(SiteIntegrationProvider.allCases))
        for provider in SiteIntegrationProvider.allCases {
            let expectedAsset = try XCTUnwrap(expectedAssets[provider])
            XCTAssertEqual(provider.logoAssetName, expectedAsset)
            XCTAssertNotNil(
                UIImage(named: provider.logoAssetName),
                "Missing compiled Sites logo asset \(provider.logoAssetName) for \(provider.displayName)"
            )
        }
        XCTAssertEqual(
            Set(SiteIntegrationProvider.allCases.filter(\.logoNeedsTint)),
            Set([.bingWebmaster, .umami, .betterStack])
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

    func testFullSnapshotCodableRoundTripPreservesCachePayload() throws {
        let accountID = UUID(uuidString: "9F266B7A-60FD-4F5D-A893-805AD2CD9793")!
        let updatedAt = Date(timeIntervalSince1970: 1_900_000_123.5)
        let resource = SiteIntegrationResource(
            id: "https://Example.com/CaseSensitivePath",
            provider: .googleSearchConsole,
            name: "Example.com",
            subtitle: "Domain property",
            url: URL(string: "https://example.com/CaseSensitivePath?source=search")!,
            status: "Verified",
            updatedAt: updatedAt,
            metrics: [
                SiteIntegrationMetric(
                    key: "clicks",
                    label: "Clicks",
                    value: 123.25,
                    unit: .count,
                    formattedValue: "123",
                    resourceID: "https://Example.com/CaseSensitivePath"
                ),
            ],
            metadata: ["permission": "siteOwner", "domain": "example.com"]
        )
        let snapshot = SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .googleSearchConsole,
            resources: [resource],
            metrics: [
                SiteIntegrationMetric(
                    key: "properties",
                    label: "Properties",
                    value: 1,
                    unit: .count
                ),
            ],
            status: "Connected",
            updatedAt: updatedAt,
            warnings: ["URL inspection quota reached"]
        )

        let data = try JSONEncoder().encode(snapshot)
        let decoded = try JSONDecoder().decode(SiteIntegrationSnapshot.self, from: data)
        XCTAssertEqual(decoded, snapshot)
        XCTAssertEqual(decoded.id, accountID)
        XCTAssertEqual(decoded.resources.first?.metrics.first?.id, "https://Example.com/CaseSensitivePath|clicks")
    }

    func testEveryProviderAndMetricUnitRemainCodable() throws {
        let units: [SiteIntegrationMetricUnit] = [
            .count, .percent, .milliseconds, .seconds, .bytes,
            .score, .ratio, .position, .none,
        ]
        for provider in SiteIntegrationProvider.allCases {
            let data = try JSONEncoder().encode(provider)
            XCTAssertEqual(try JSONDecoder().decode(SiteIntegrationProvider.self, from: data), provider)
        }
        for unit in units {
            let data = try JSONEncoder().encode(unit)
            XCTAssertEqual(try JSONDecoder().decode(SiteIntegrationMetricUnit.self, from: data), unit)
        }
    }

    func testFileBackedSnapshotCacheRoundTripSupportsLargePayload() throws {
        let originalSnapshots = (try? KeychainHelper.getSiteIntegrationSnapshots()) ?? []
        defer { try? KeychainHelper.saveSiteIntegrationSnapshots(originalSnapshots) }

        let accountID = UUID()
        let resources = (0..<300).map { index in
            SiteIntegrationResource(
                id: "monitor-\(index)",
                provider: .uptimeRobot,
                name: "Monitor \(index)",
                url: URL(string: "https://status.example.com/monitors/\(index)"),
                status: index.isMultiple(of: 2) ? "Up" : "Paused",
                metrics: [
                    SiteIntegrationMetric(
                        key: "responseTime",
                        label: "Response time",
                        value: Double(index),
                        unit: .milliseconds,
                        resourceID: "monitor-\(index)"
                    ),
                ],
                metadata: ["index": String(index)]
            )
        }
        let snapshot = SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .uptimeRobot,
            resources: resources,
            status: "Connected",
            warnings: ["Synthetic cache fixture"]
        )

        try KeychainHelper.saveSiteIntegrationSnapshots([snapshot])
        XCTAssertEqual(try KeychainHelper.getSiteIntegrationSnapshots(), [snapshot])
    }

    func testHTTPSBaseURLValidation() throws {
        XCTAssertThrowsError(try SiteIntegrationsAPI.normalizedHTTPSBaseURL("http://analytics.example.com"))
        XCTAssertThrowsError(try SiteIntegrationsAPI.normalizedHTTPSBaseURL("https://user:password@analytics.example.com"))
        let value = try SiteIntegrationsAPI.normalizedHTTPSBaseURL("https://analytics.example.com/root?token=secret#fragment")
        XCTAssertEqual(value.absoluteString, "https://analytics.example.com/root")
    }

    func testHTTPSBaseURLNormalizesUppercaseSchemeAndHostWithoutChangingPathIdentity() throws {
        let value = try SiteIntegrationsAPI.normalizedHTTPSBaseURL(
            "  HTTPS://Analytics.Example.COM/Team/CaseSensitive?token=secret#fragment  "
        )
        XCTAssertEqual(value.scheme, "https")
        XCTAssertEqual(value.host, "analytics.example.com")
        XCTAssertEqual(value.path, "/Team/CaseSensitive")
        XCTAssertNil(value.query)
        XCTAssertNil(value.fragment)
    }

    func testOriginDropsPathQueryAndCredentials() {
        let input = URL(string: "https://user:password@example.com:8443/path?q=1#part")!
        XCTAssertEqual(
            SiteIntegrationsAPI.originURL(from: input)?.absoluteString,
            "https://example.com:8443"
        )
    }

    func testUmamiEndpointIdentityCanonicalizesDefaultPortAndTrailingSlash() {
        XCTAssertEqual(
            SiteIntegrationsAPI.canonicalEndpointIdentity(
                "https://API.UMAMI.IS:443/v1///?token=ignored#fragment"
            ),
            "https://api.umami.is/v1/"
        )
        XCTAssertEqual(
            SiteIntegrationsAPI.canonicalEndpointIdentity("https://analytics.example.com:8443/Team/API"),
            "https://analytics.example.com:8443/Team/API/"
        )
        XCTAssertNil(SiteIntegrationsAPI.canonicalEndpointIdentity("http://api.umami.is/v1"))
        XCTAssertNil(SiteIntegrationsAPI.canonicalEndpointIdentity("https://user:secret@api.umami.is/v1"))
    }

    func testUmamiConnectionMetadataUsesStableUserIdentity() throws {
        let endpoint = try XCTUnwrap(URL(string: "https://API.UMAMI.IS:443/v1/"))
        let metadata = try SiteIntegrationsAPI.umamiConnectionMetadata(
            from: [
                "token": "session-value-must-not-be-persisted",
                "authKey": "auth-value-must-not-be-persisted",
                "user": [
                    "id": "user-123",
                    "username": "operator",
                ],
            ],
            endpoint: endpoint
        )

        XCTAssertEqual(metadata["umamiUserID"], "user-123")
        XCTAssertEqual(metadata["umamiUsername"], "operator")
        XCTAssertEqual(metadata["umamiEndpoint"], "https://api.umami.is/v1/")
        XCTAssertNil(metadata["token"])
        XCTAssertNil(metadata["authKey"])
    }

    func testUmamiConnectionMetadataRequiresStableUserIdentity() throws {
        let endpoint = try XCTUnwrap(URL(string: "https://api.umami.is/v1/"))

        XCTAssertThrowsError(
            try SiteIntegrationsAPI.umamiConnectionMetadata(
                from: ["user": ["username": "missing-id"]],
                endpoint: endpoint
            )
        ) { error in
            XCTAssertTrue(error.localizedDescription.contains("stable account identity"))
        }
    }

    func testRetryDelayRejectsNonFiniteHeaderValues() {
        XCTAssertEqual(
            SiteIntegrationsAPI.retryDelaySeconds(retryAfterHeader: "NaN", attempt: 0),
            0.5
        )
        XCTAssertEqual(
            SiteIntegrationsAPI.retryDelaySeconds(retryAfterHeader: "infinity", attempt: 1),
            1
        )
        XCTAssertEqual(
            SiteIntegrationsAPI.retryDelaySeconds(retryAfterHeader: "999", attempt: 0),
            8
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
        XCTAssertEqual(try SiteIntegrationsAPI.pathComponent("Site/Production"), "Site%2FProduction")
        XCTAssertNotEqual(
            try SiteIntegrationsAPI.pathComponent("Site/Production"),
            try SiteIntegrationsAPI.pathComponent("site/production")
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

    func testGoogleProvidersRejectMissingOAuthCredentialBeforeNetwork() async {
        let configurations: [(SiteIntegrationProvider, SiteIntegrationOAuthConfiguration)] = [
            (.googleSearchConsole, SiteIntegrationsAPI.googleSearchConsoleOAuth),
            (.googleAnalytics, SiteIntegrationsAPI.googleAnalyticsOAuth),
        ]
        for (provider, configuration) in configurations {
            XCTAssertEqual(configuration.authorizationEndpoint.scheme, "https")
            XCTAssertEqual(configuration.tokenEndpoint.scheme, "https")
            XCTAssertTrue(configuration.scopes.contains("openid"))
            XCTAssertTrue(configuration.scopes.contains("email"))

            let api = SiteIntegrationsAPI(provider: provider, credential: "")
            do {
                _ = try await api.fetchSnapshot()
                XCTFail("\(provider.displayName) should reject a missing OAuth credential before networking.")
            } catch let error as SiteIntegrationsAPIError {
                XCTAssertEqual(
                    error,
                    .oauthNotConfigured(provider: provider, scopes: configuration.scopes)
                )
            } catch {
                XCTFail("Unexpected error for \(provider.displayName): \(error)")
            }
        }
    }
}
