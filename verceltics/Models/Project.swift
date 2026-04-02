import Foundation

struct Project: Identifiable, Decodable {
    let id: String
    let name: String
    let framework: String?
    let latestDeployments: [Deployment]?
    let updatedAt: Int?
    let targets: Targets?
    let link: GitLink?

    var primaryDomain: String? {
        targets?.production?.alias?.first(where: { !$0.contains("vercel.app") })
            ?? targets?.production?.alias?.first
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
