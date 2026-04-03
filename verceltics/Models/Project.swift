import Foundation

struct Project: Identifiable, Decodable {
    let id: String
    let name: String
    let accountId: String?
    let framework: String?
    let latestDeployments: [Deployment]?
    let updatedAt: Int?
    let targets: Targets?
    let link: GitLink?
    let alias: [AliasEntry]?

    var teamId: String? { accountId }

    var primaryDomain: String? {
        // Collect all aliases from targets + top-level alias array
        var allAliases: [String] = []
        if let targetAliases = targets?.production?.alias {
            allAliases.append(contentsOf: targetAliases)
        }
        if let topAliases = alias {
            allAliases.append(contentsOf: topAliases.map(\.domain))
        }
        guard !allAliases.isEmpty else { return nil }
        // Prefer custom domains (non vercel.app)
        if let custom = allAliases.first(where: { !$0.contains("vercel.app") }) {
            return custom
        }
        // Among vercel.app aliases, pick the shortest
        return allAliases.filter { $0.contains("vercel.app") }.min(by: { $0.count < $1.count })
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
}

struct ProjectsResponse: Decodable {
    let projects: [Project]
}
