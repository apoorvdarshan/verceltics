import XCTest
@testable import verceltics

final class SiteDashboardSelectionTests: XCTestCase {
    func testEverySiteProviderResolvesOnlyItsActiveAccountSnapshot() throws {
        let accounts = SiteIntegrationProvider.allCases.map { provider in
            SiteIntegrationAccount(
                provider: provider,
                name: provider.displayName,
                credential: "credential-\(provider.rawValue)"
            )
        }
        let snapshots = Dictionary(uniqueKeysWithValues: accounts.map { account in
            (
                account.id,
                SiteIntegrationSnapshot(
                    accountID: account.id,
                    provider: account.provider,
                    resources: [
                        SiteIntegrationResource(
                            id: "shared.example.com",
                            provider: account.provider,
                            name: "shared.example.com",
                            status: "Connected"
                        )
                    ],
                    metrics: [
                        SiteIntegrationMetric(
                            key: "providerMetric",
                            label: account.provider.displayName,
                            value: 1
                        )
                    ],
                    warnings: ["\(account.provider.displayName) warning"]
                )
            )
        })

        for account in accounts {
            let selection = try XCTUnwrap(
                SiteDashboardSelection.active(
                    accounts: accounts,
                    snapshots: snapshots,
                    activeAccountID: account.id
                )
            )

            XCTAssertEqual(selection.account.id, account.id)
            XCTAssertEqual(selection.account.provider, account.provider)
            XCTAssertEqual(selection.snapshot?.accountID, account.id)
            XCTAssertEqual(selection.snapshot?.resources.map(\.provider), [account.provider])
            XCTAssertEqual(selection.snapshot?.warnings, ["\(account.provider.displayName) warning"])
        }
    }

    func testSelectionIsolatedByAccountIDWhenProviderAndDomainMatch() throws {
        let first = SiteIntegrationAccount(
            provider: .googleSearchConsole,
            name: "First Google account",
            credential: "first"
        )
        let second = SiteIntegrationAccount(
            provider: .googleSearchConsole,
            name: "Second Google account",
            credential: "second"
        )
        let firstSnapshot = snapshot(for: first, resourceID: "first-property")
        let secondSnapshot = snapshot(for: second, resourceID: "second-property")
        let snapshots = [first.id: firstSnapshot, second.id: secondSnapshot]

        let firstSelection = try XCTUnwrap(
            SiteDashboardSelection.active(
                accounts: [first, second],
                snapshots: snapshots,
                activeAccountID: first.id
            )
        )
        let secondSelection = try XCTUnwrap(
            SiteDashboardSelection.active(
                accounts: [first, second],
                snapshots: snapshots,
                activeAccountID: second.id
            )
        )

        XCTAssertEqual(firstSelection.snapshot?.resources.map(\.id), ["first-property"])
        XCTAssertEqual(secondSelection.snapshot?.resources.map(\.id), ["second-property"])
        XCTAssertNotEqual(
            CredentialCacheScope.siteIntegrationAccount(firstSelection.account),
            CredentialCacheScope.siteIntegrationAccount(secondSelection.account)
        )
    }

    func testMissingOrUnknownActiveAccountReturnsNoDashboard() {
        let account = SiteIntegrationAccount(
            provider: .googleAnalytics,
            name: "Analytics",
            credential: "credential"
        )

        XCTAssertNil(
            SiteDashboardSelection.active(
                accounts: [account],
                snapshots: [:],
                activeAccountID: nil
            )
        )
        XCTAssertNil(
            SiteDashboardSelection.active(
                accounts: [account],
                snapshots: [:],
                activeAccountID: UUID()
            )
        )
    }

    func testDashboardIdentityStaysStableAcrossCredentialAndMetadataRotation() throws {
        let accountID = UUID()
        let original = SiteIntegrationAccount(
            id: accountID,
            provider: .googleAnalytics,
            name: "Analytics",
            credential: "expiring-token",
            metadata: ["googleEmail": "owner@example.com"]
        )
        let refreshed = SiteIntegrationAccount(
            id: accountID,
            provider: .googleAnalytics,
            name: "Analytics",
            credential: "refreshed-token",
            metadata: [
                "googleEmail": "owner@example.com",
                "googleSubject": "subject-123"
            ]
        )

        let originalSelection = try XCTUnwrap(
            SiteDashboardSelection.active(
                accounts: [original],
                snapshots: [:],
                activeAccountID: accountID
            )
        )
        let refreshedSelection = try XCTUnwrap(
            SiteDashboardSelection.active(
                accounts: [refreshed],
                snapshots: [:],
                activeAccountID: accountID
            )
        )

        XCTAssertEqual(originalSelection.dashboardID, refreshedSelection.dashboardID)
        XCTAssertNotEqual(
            CredentialCacheScope.siteIntegrationAccount(original),
            CredentialCacheScope.siteIntegrationAccount(refreshed)
        )
    }

    private func snapshot(
        for account: SiteIntegrationAccount,
        resourceID: String
    ) -> SiteIntegrationSnapshot {
        SiteIntegrationSnapshot(
            accountID: account.id,
            provider: account.provider,
            resources: [
                SiteIntegrationResource(
                    id: resourceID,
                    provider: account.provider,
                    name: "shared.example.com",
                    url: URL(string: "https://shared.example.com"),
                    status: "Verified"
                )
            ]
        )
    }
}
