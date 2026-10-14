#if DEBUG
import SwiftUI

@MainActor
enum AppDebugFixtures {
    static var showsRegistrarDomain: Bool {
        ProcessInfo.processInfo.arguments.contains("-registrarDomainFixture")
    }

    static var showsMainNavigation: Bool {
        ProcessInfo.processInfo.arguments.contains("-mainNavigationFixture")
    }

    static var usesIsolatedAccountStores: Bool {
        showsRegistrarDomain || showsMainNavigation
    }

    static var registrarDomainView: some View {
        let account = RegistrarAccount(
            provider: .namecheap,
            name: "Fixture registrar",
            primaryCredential: "fixture-only"
        )
        let domain = RegistrarDomain(
            name: "example.dev",
            status: "active",
            createdAt: Calendar.current.date(byAdding: .year, value: -2, to: .now),
            expiresAt: Calendar.current.date(byAdding: .day, value: 23, to: .now),
            autoRenew: true,
            locked: true,
            privacyEnabled: true,
            nameservers: ["dns1.registrar-servers.com", "dns2.registrar-servers.com"],
            metadata: [:]
        )

        return NavigationStack {
            RegistrarDomainDetailView(account: account, domain: domain)
        }
    }

    static var mainNavigationView: some View {
        MainTabView(
            performsUpdateCheck: false,
            performsAutomaticRefresh: false,
            initialWorkspace: .hosting
        )
    }

    static func makeSiteStore() -> SiteStore {
        guard showsMainNavigation else {
            return SiteStore(ephemeralAccounts: [])
        }
        return sampleSiteStore()
    }

    /// One in-memory PageSpeed account whose snapshot is fresh, so opening the
    /// Sites workspace shows it without a network refresh.
    static func sampleSiteStore() -> SiteStore {
        let accountID = UUID(uuidString: "6E12A0C4-8C46-4261-A21E-32E7F1724E1C")!
        let account = SiteIntegrationAccount(
            id: accountID,
            provider: .pageSpeed,
            name: "Verceltics demo",
            credential: "fixture-only",
            metadata: ["siteURL": "https://verceltics.com"]
        )
        let resource = SiteIntegrationResource(
            id: "https://verceltics.com",
            provider: .pageSpeed,
            name: "verceltics.com",
            subtitle: "Mobile performance",
            url: URL(string: "https://verceltics.com"),
            status: "Healthy",
            updatedAt: .now,
            metrics: [
                SiteIntegrationMetric(
                    key: "performance",
                    label: "Performance",
                    value: 96,
                    unit: .score,
                    formattedValue: "96"
                ),
                SiteIntegrationMetric(
                    key: "lcp",
                    label: "LCP",
                    value: 1.4,
                    unit: .seconds,
                    formattedValue: "1.4 s"
                ),
            ]
        )

        let snapshot = SiteIntegrationSnapshot(
            accountID: accountID,
            provider: .pageSpeed,
            resources: [resource],
            metrics: resource.metrics,
            status: "Healthy",
            updatedAt: .now
        )

        return SiteStore(
            ephemeralAccounts: [account],
            activeAccountID: accountID,
            snapshots: [accountID: snapshot]
        )
    }
}
#endif
