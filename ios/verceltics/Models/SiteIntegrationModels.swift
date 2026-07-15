import Foundation
import SwiftUI

enum SiteIntegrationProvider: String, Codable, CaseIterable, Hashable, Identifiable, Sendable {
    case googleSearchConsole
    case googleAnalytics
    case pageSpeed
    case bingWebmaster
    case clarity
    case plausible
    case umami
    case uptimeRobot
    case betterStack

    var id: Self { self }

    var displayName: String {
        switch self {
        case .googleSearchConsole: "Google Search Console"
        case .googleAnalytics: "Google Analytics"
        case .pageSpeed: "PageSpeed & CrUX"
        case .bingWebmaster: "Bing Webmaster"
        case .clarity: "Microsoft Clarity"
        case .plausible: "Plausible"
        case .umami: "Umami"
        case .uptimeRobot: "UptimeRobot"
        case .betterStack: "Better Stack"
        }
    }

    var logoAssetName: String {
        switch self {
        case .googleSearchConsole: "GoogleSearchConsoleMark"
        case .googleAnalytics: "GoogleAnalyticsMark"
        case .pageSpeed: "PageSpeedMark"
        case .bingWebmaster: "BingWebmasterMark"
        case .clarity: "MicrosoftClarityMark"
        case .plausible: "PlausibleMark"
        case .umami: "UmamiMark"
        case .uptimeRobot: "UptimeRobotMark"
        case .betterStack: "BetterStackMark"
        }
    }

    var logoNeedsTint: Bool {
        switch self {
        case .bingWebmaster, .umami, .betterStack: true
        case .googleSearchConsole, .googleAnalytics, .pageSpeed, .clarity, .plausible, .uptimeRobot: false
        }
    }

    var connectionSubtitle: String {
        switch self {
        case .googleSearchConsole: "Search performance, indexing, sitemaps and URL inspection"
        case .googleAnalytics: "GA4 visitors, sessions, traffic, events and realtime"
        case .pageSpeed: "Lighthouse audits and Chrome UX field data"
        case .bingWebmaster: "Bing search traffic, crawling and verified sites"
        case .clarity: "Behavioral insights, sessions and interaction signals"
        case .plausible: "Privacy-friendly visitors, visits, views and engagement"
        case .umami: "30-day traffic across Cloud or self-hosted sites"
        case .uptimeRobot: "Monitor state, uptime ratios and response time"
        case .betterStack: "Monitor state, check cadence and availability"
        }
    }

    var systemImage: String {
        switch self {
        case .googleSearchConsole: "magnifyingglass.circle.fill"
        case .googleAnalytics: "chart.xyaxis.line"
        case .pageSpeed: "gauge.with.dots.needle.67percent"
        case .bingWebmaster: "b.circle.fill"
        case .clarity: "cursorarrow.rays"
        case .plausible: "chart.bar.xaxis"
        case .umami: "chart.line.uptrend.xyaxis"
        case .uptimeRobot: "waveform.path.ecg"
        case .betterStack: "checkmark.shield.fill"
        }
    }

    var accentColor: Color {
        switch self {
        case .googleSearchConsole: Color(red: 0.25, green: 0.52, blue: 0.96)
        case .googleAnalytics: Color(red: 0.96, green: 0.57, blue: 0.12)
        case .pageSpeed: Color(red: 0.31, green: 0.74, blue: 0.48)
        case .bingWebmaster: Color(red: 0.00, green: 0.68, blue: 0.68)
        case .clarity: Color(red: 0.20, green: 0.52, blue: 0.95)
        case .plausible: Color(red: 0.42, green: 0.36, blue: 0.90)
        case .umami: Color(red: 0.58, green: 0.45, blue: 0.94)
        case .uptimeRobot: Color(red: 0.19, green: 0.76, blue: 0.58)
        case .betterStack: Color(red: 0.98, green: 0.34, blue: 0.30)
        }
    }

    var credentialURL: URL? {
        let value: String
        switch self {
        case .googleSearchConsole, .googleAnalytics, .pageSpeed:
            value = "https://console.cloud.google.com/apis/credentials"
        case .bingWebmaster:
            value = "https://www.bing.com/webmasters/home?rt=2#/Configure/MyAPI"
        case .clarity:
            value = "https://clarity.microsoft.com/projects"
        case .plausible:
            value = "https://plausible.io/settings/api-keys"
        case .umami:
            value = "https://cloud.umami.is/settings/api-keys"
        case .uptimeRobot:
            value = "https://dashboard.uptimerobot.com/integrations"
        case .betterStack:
            value = "https://betterstack.com/settings/api-tokens"
        }
        return URL(string: value)
    }

    var usesOAuth: Bool {
        switch self {
        case .googleSearchConsole, .googleAnalytics: true
        case .pageSpeed, .bingWebmaster, .clarity, .plausible, .umami, .uptimeRobot, .betterStack: false
        }
    }
}

struct SiteProviderMark: View {
    let provider: SiteIntegrationProvider
    var size: CGFloat = 22
    var monochrome = false

    var body: some View {
        Image(provider.logoAssetName)
            .resizable()
            .renderingMode(monochrome || provider.logoNeedsTint ? .template : .original)
            .scaledToFit()
            .foregroundStyle(monochrome ? Color.white : provider.accentColor)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct SiteIntegrationAccount: Codable, Identifiable, Equatable, Sendable {
    let id: UUID
    var provider: SiteIntegrationProvider
    var name: String
    var credential: String
    var metadata: [String: String]

    init(
        id: UUID = UUID(),
        provider: SiteIntegrationProvider,
        name: String,
        credential: String,
        metadata: [String: String] = [:]
    ) {
        self.id = id
        self.provider = provider
        self.name = name
        self.credential = credential
        self.metadata = metadata
    }
}

enum SiteIntegrationMetricUnit: String, Codable, Sendable {
    case count
    case percent
    case milliseconds
    case seconds
    case bytes
    case score
    case ratio
    case position
    case none
}

struct SiteIntegrationMetric: Codable, Identifiable, Equatable, Sendable {
    let key: String
    let label: String
    let value: Double
    let unit: SiteIntegrationMetricUnit
    let formattedValue: String?
    let resourceID: String?

    var id: String { "\(resourceID ?? "account")|\(key)" }

    init(
        key: String,
        label: String,
        value: Double,
        unit: SiteIntegrationMetricUnit = .none,
        formattedValue: String? = nil,
        resourceID: String? = nil
    ) {
        self.key = key
        self.label = label
        self.value = value
        self.unit = unit
        self.formattedValue = formattedValue
        self.resourceID = resourceID
    }
}

struct SiteIntegrationResource: Codable, Identifiable, Equatable, Sendable {
    let id: String
    let provider: SiteIntegrationProvider
    let name: String
    let subtitle: String?
    let url: URL?
    let status: String?
    let updatedAt: Date?
    let metrics: [SiteIntegrationMetric]
    let metadata: [String: String]

    init(
        id: String,
        provider: SiteIntegrationProvider,
        name: String,
        subtitle: String? = nil,
        url: URL? = nil,
        status: String? = nil,
        updatedAt: Date? = nil,
        metrics: [SiteIntegrationMetric] = [],
        metadata: [String: String] = [:]
    ) {
        self.id = id
        self.provider = provider
        self.name = name
        self.subtitle = subtitle
        self.url = url
        self.status = status
        self.updatedAt = updatedAt
        self.metrics = metrics
        self.metadata = metadata
    }
}

struct SiteIntegrationSnapshot: Codable, Identifiable, Equatable, Sendable {
    var id: UUID { accountID }

    let accountID: UUID
    let provider: SiteIntegrationProvider
    let resources: [SiteIntegrationResource]
    let metrics: [SiteIntegrationMetric]
    let status: String?
    let updatedAt: Date
    let warnings: [String]

    init(
        accountID: UUID,
        provider: SiteIntegrationProvider,
        resources: [SiteIntegrationResource] = [],
        metrics: [SiteIntegrationMetric] = [],
        status: String? = nil,
        updatedAt: Date = .now,
        warnings: [String] = []
    ) {
        self.accountID = accountID
        self.provider = provider
        self.resources = resources
        self.metrics = metrics
        self.status = status
        self.updatedAt = updatedAt
        self.warnings = warnings
    }
}
