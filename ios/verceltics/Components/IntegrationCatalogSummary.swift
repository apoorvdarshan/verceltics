import SwiftUI

struct IntegrationCatalogLane: Identifiable {
    let id: String
    let icon: String
    let count: Int
    let label: String
    let detail: String
}

enum IntegrationCatalogSummary {
    static var lanes: [IntegrationCatalogLane] {
        [
            IntegrationCatalogLane(
                id: "hosting",
                icon: "server.rack",
                count: AccountProvider.allCases.count,
                label: "Hosting",
                detail: "Projects · deploys · logs"
            ),
            IntegrationCatalogLane(
                id: "registrars",
                icon: "globe.americas.fill",
                count: RegistrarProvider.allCases.count,
                label: "Registrars",
                detail: "Domains · DNS · renewals"
            ),
            IntegrationCatalogLane(
                id: "sites",
                icon: "chart.xyaxis.line",
                count: SiteIntegrationProvider.allCases.count,
                label: "Site services",
                detail: "Search · speed · uptime"
            ),
        ]
    }

    static var totalCount: Int {
        lanes.reduce(0) { $0 + $1.count }
    }

    static var accessibilitySummary: String {
        "\(totalCount) integrations: \(AccountProvider.allCases.count) hosting platforms, \(RegistrarProvider.allCases.count) registrars, and \(SiteIntegrationProvider.allCases.count) site services"
    }
}
