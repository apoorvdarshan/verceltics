import Foundation

nonisolated struct Project: Identifiable, Decodable {
    let id: String
    let name: String
    let accountId: String?
    let framework: String?
    let latestDeployments: [Deployment]?
    let updatedAt: Int?
    let targets: Targets?
    let link: GitLink?
    var alias: [AliasEntry]?
    let customEnvironments: [CustomEnvironment]?

    var teamId: String? {
        guard let accountId else { return nil }
        return accountId.hasPrefix("team_") ? accountId : nil
    }

    var primaryDomain: String? {
        let productionEnvironment = customEnvironments?.first(where: { $0.type == "production" })

        let allAliases = deduplicatedDomains(
            from: [
                lastDeployment?.alias ?? [],
                productionEnvironment?.currentDeploymentAliases ?? [],
                targets?.production?.alias ?? [],
                alias?.map(\.domain) ?? [],
                productionEnvironment?.preferredDomains ?? []
            ]
        )

        guard !allAliases.isEmpty else { return nil }

        if let custom = allAliases.first(where: { !Self.isVercelDomain($0) }) {
            return custom
        }

        return allAliases
            .filter(Self.isVercelDomain)
            .min(by: { $0.count < $1.count })
    }

    var needsPrimaryDomainRefresh: Bool {
        guard let primaryDomain else { return true }
        return Self.isVercelDomain(primaryDomain)
    }

    struct AliasEntry: Decodable {
        let domain: String
    }

    var lastDeployment: Deployment? {
        latestDeployments?.first
    }

    struct Targets: Decodable {
        let production: ProductionTarget?
    }

    struct ProductionTarget: Decodable {
        let alias: [String]?
    }

    struct Deployment: Decodable {
        let createdAt: Int?
        let alias: [String]?
        let meta: DeploymentMeta?

        var date: Date? {
            guard let createdAt else { return nil }
            return Date(timeIntervalSince1970: Double(createdAt) / 1000)
        }

        var commitMessage: String? {
            meta?.githubCommitMessage
        }
    }

    struct DeploymentMeta: Decodable {
        let githubCommitMessage: String?
    }

    struct GitLink: Decodable {
        let repo: String?
        let org: String?
    }

    struct CustomEnvironment: Decodable {
        let type: String?
        let domains: [EnvironmentDomain]?
        let currentDeploymentAliases: [String]?

        var preferredDomains: [String] {
            let nonRedirects = domains?.filter { $0.redirect == nil }.map(\.name) ?? []
            let redirects = domains?.filter { $0.redirect != nil }.map(\.name) ?? []
            return nonRedirects + redirects
        }
    }

    struct EnvironmentDomain: Decodable {
        let name: String
        let redirect: String?
    }

    private func deduplicatedDomains(from groups: [[String]]) -> [String] {
        var seen = Set<String>()
        var result: [String] = []

        for domain in groups.joined() {
            let normalized = domain.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !normalized.isEmpty else { continue }

            let key = normalized.lowercased()
            guard seen.insert(key).inserted else { continue }
            result.append(normalized)
        }

        return result
    }

    static func isVercelDomain(_ domain: String) -> Bool {
        let normalized = domain.lowercased()
        return normalized == "vercel.app" || normalized.hasSuffix(".vercel.app")
    }
}

nonisolated struct ProjectsResponse: Decodable {
    let projects: [Project]
}

nonisolated struct ProjectDomainsResponse: Decodable {
    let domains: [Domain]

    struct Domain: Decodable {
        let name: String
        let verified: Bool?
        let redirect: String?
    }
}
