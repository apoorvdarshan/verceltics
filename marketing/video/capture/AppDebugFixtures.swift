#if DEBUG && targetEnvironment(simulator)
import SwiftUI

/// Copied into a temporary simulator project by prepare-simulator.py.
/// Fictional data; never part of the shipping iOS target.
@MainActor
enum AppDebugFixtures {
    static let showsRegistrarDomain = false
    static let showsMainNavigation = true
    static let usesIsolatedAccountStores = true
    static let now = Date()
    static var registrarDomainView: some View { Color.clear }
    static var mainNavigationView: some View {
        MainTabView(performsUpdateCheck: false, performsAutomaticRefresh: false,
                    initialWorkspace: ProcessInfo.processInfo.arguments.contains("-sites") ? .sites : ProcessInfo.processInfo.arguments.contains("-registrars") ? .registrars : .hosting)
    }
    static func decoded<T: Decodable>(_ json: String) -> T {
        try! JSONDecoder().decode(T.self, from: Data(json.utf8))
    }
    static func makeAuthManager() -> AuthManager {
        let accounts = [
            VercelAccount(name: "Studio · Vercel", token: "fictional-demo", email: "hello@studio.example"),
            VercelAccount(name: "Studio · Cloudflare", token: "fictional-demo", provider: .cloudflare, email: "hello@studio.example", cloudflareAuthenticationMode: .apiToken)
        ]
        return AuthManager(ephemeralAccounts: accounts,
            activeAccountID: ProcessInfo.processInfo.arguments.contains("-cloudflare") ? accounts[1].id : accounts[0].id)
    }
    static func makeRegistrarStore() -> RegistrarStore {
        RegistrarStore(ephemeralAccounts: [
            RegistrarAccount(provider: .namecheap, name: "Studio · Namecheap", primaryCredential: "fictional-demo"),
            RegistrarAccount(provider: .nameDotCom, name: "Studio · Name.com", primaryCredential: "fictional-demo")
        ])
    }
    static var projects: [Project] {
        ["studio-web", "commerce", "launch-kit", "docs", "portfolio"].enumerated().map { index, name in
            let timestamp = Int(now.addingTimeInterval(Double(-index * 3600)).timeIntervalSince1970 * 1000)
            return decoded("""
            {"id":"prj_demo_\(index)","name":"\(name)","framework":"nextjs","updatedAt":\(timestamp),"latestDeployments":[{"createdAt":\(timestamp),"alias":["\(name).example"],"meta":{"githubCommitMessage":"Ship a better experience"}}],"link":{"org":"studio","repo":"\(name)"}}
            """)
        }
    }
    static var analytics: AnalyticsData {
        var data = AnalyticsData()
        data.overview = AnalyticsOverview(total: 42860, devices: 18240, bounceRate: nil)
        data.previousOverview = AnalyticsOverview(total: 33210, devices: 13950, bounceRate: nil)
        let formatter = ISO8601DateFormatter()
        data.timeseries = [3600, 4400, 4100, 5800, 6200, 7900, 10860].enumerated().map { index, total in
            TimeseriesPoint(key: formatter.string(from: now.addingTimeInterval(Double(index-6)*86400)), total: total, devices: Int(Double(total)*0.43), bounceRate: nil)
        }
        data.pages = [BreakdownItem(key: "/", visitors: 9200, pageViews: 22000), BreakdownItem(key: "/pricing", visitors: 3400, pageViews: 6400), BreakdownItem(key: "/docs", visitors: 2900, pageViews: 5100)]
        data.referrers = [BreakdownItem(key: "google.com", visitors: 8600, pageViews: 17200), BreakdownItem(key: "github.com", visitors: 3600, pageViews: 6400), BreakdownItem(key: "producthunt.com", visitors: 2400, pageViews: 5100)]
        data.countries = [BreakdownItem(key: "US", visitors: 6900, pageViews: 13200), BreakdownItem(key: "IN", visitors: 4800, pageViews: 10800), BreakdownItem(key: "GB", visitors: 2400, pageViews: 5100)]
        data.devices = [BreakdownItem(key: "Desktop", visitors: 10400, pageViews: 21000), BreakdownItem(key: "Mobile", visitors: 7840, pageViews: 21860)]
        return data
    }
    static var domains: [RegistrarDomain] {
        ["studio.example", "commerce.example", "launch-kit.example", "docs.example", "portfolio.example"].enumerated().map { index, name in
            RegistrarDomain(name: name, status: "active", createdAt: now.addingTimeInterval(-63072000), expiresAt: now.addingTimeInterval(Double([347, 180, 23, 12, 290][index])*86400), autoRenew: index != 2 && index != 3, locked: true, privacyEnabled: true, nameservers: ["ada.ns.cloudflare.com", "ben.ns.cloudflare.com"], metadata: [:])
        }
    }
    static var cloudAccounts: [CloudflareAccountSummary] { decoded("[{\"id\":\"demo\",\"name\":\"Studio workspace\"}]") }
    static var zones: [CloudflareZone] {
        decoded("""
        [{"id":"demo-zone","name":"studio.example","status":"active","type":"full","name_servers":["ada.ns.cloudflare.com","ben.ns.cloudflare.com"],"account":{"id":"demo","name":"Studio workspace"},"plan":{"name":"Pro"}},
         {"id":"demo-commerce","name":"commerce.example","status":"active","type":"full","account":{"id":"demo","name":"Studio workspace"},"plan":{"name":"Free"}},
         {"id":"demo-docs","name":"docs.example","status":"active","type":"full","account":{"id":"demo","name":"Studio workspace"},"plan":{"name":"Free"}}]
        """)
    }
    static var pages: [CloudflarePagesProject] { decoded("[{\"id\":\"demo-pages\",\"name\":\"studio-docs\",\"subdomain\":\"studio-docs.pages.dev\",\"production_branch\":\"main\"}]") }
    static var workers: [CloudflareWorkerScript] { decoded("[{\"id\":\"edge-api\"},{\"id\":\"image-proxy\"},{\"id\":\"site-router\"}]") }
    static var dns: [CloudflareDNSRecord] { decoded("[{\"id\":\"dns1\",\"type\":\"A\",\"name\":\"studio.example\",\"content\":\"192.0.2.1\",\"proxied\":true,\"ttl\":1},{\"id\":\"dns2\",\"type\":\"CNAME\",\"name\":\"www.studio.example\",\"content\":\"studio.example\",\"proxied\":true,\"ttl\":1}]") }
    static func cloudAnalytics(zoneID: String) -> CloudflareZoneAnalyticsSummary {
        func metrics(_ requests: Int64) -> CloudflareAnalyticsMetrics {
            CloudflareAnalyticsMetrics(requests: requests, pageViews: requests/2, bytes: requests*5800, cachedRequests: requests*9/10, cachedBytes: requests*5000, threats: requests/200, encryptedRequests: requests*99/100, uniqueVisitors: requests/3)
        }
        let from = now.addingTimeInterval(-6*86400)
        return CloudflareZoneAnalyticsSummary(zoneID: zoneID, requestedFrom: from, requestedTo: now, from: from, to: now, granularity: .daily, isWindowLimited: false, totals: metrics(128400), series: [12000, 15200, 14500, 18700, 19800, 21300, 26900].enumerated().map { index, n in CloudflareZoneAnalyticsPoint(timestamp: from.addingTimeInterval(Double(index)*86400), metrics: metrics(Int64(n))) })
    }
    static func makeSiteStore() -> SiteStore {
        let providers: [SiteIntegrationProvider] = [.googleSearchConsole, .googleAnalytics, .pageSpeed, .uptimeRobot]
        let accounts = providers.map { SiteIntegrationAccount(provider: $0, name: "Studio · \($0.displayName)", credential: "fictional-demo", metadata: [:]) }
        let snapshots = Dictionary(uniqueKeysWithValues: accounts.map { account in
            let metrics: [SiteIntegrationMetric]
            switch account.provider {
            case .googleSearchConsole:
                metrics = [.init(key: "clicks", label: "Clicks", value: 8240, unit: .count), .init(key: "impressions", label: "Impressions", value: 186400, unit: .count), .init(key: "ctr", label: "CTR", value: 4.42, unit: .percent), .init(key: "position", label: "Position", value: 8.6, unit: .position)]
            case .pageSpeed:
                metrics = [.init(key: "performance", label: "Performance", value: 98, unit: .score), .init(key: "lcp", label: "LCP", value: 1.2, unit: .seconds), .init(key: "accessibility", label: "Accessibility", value: 100, unit: .score)]
            case .uptimeRobot:
                metrics = [.init(key: "uptime", label: "Uptime", value: 99.99, unit: .percent), .init(key: "response", label: "Response time", value: 142, unit: .milliseconds)]
            default:
                metrics = [.init(key: "users", label: "Users", value: 18240, unit: .count), .init(key: "sessions", label: "Sessions", value: 24160, unit: .count), .init(key: "views", label: "Page views", value: 42860, unit: .count)]
            }
            let resources = ["studio.example", "commerce.example", "docs.example"].enumerated().map { index, name in SiteIntegrationResource(id: "site-\(index)", provider: account.provider, name: name, subtitle: "Verified site", status: "Healthy", updatedAt: now, metrics: metrics) }
            return (account.id, SiteIntegrationSnapshot(accountID: account.id, provider: account.provider, resources: resources, metrics: metrics, status: "Healthy", updatedAt: now))
        })
        return SiteStore(ephemeralAccounts: accounts, snapshots: snapshots)
    }
}
#endif
