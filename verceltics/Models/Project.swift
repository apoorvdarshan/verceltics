import Foundation

struct Project: Identifiable, Decodable {
    let id: String
    let name: String
    let targets: [Target]?
    let latestDeployments: [Deployment]?
    let updatedAt: Int?

    var primaryDomain: String? {
        targets?.first?.productionDomain
    }

    var lastDeployment: Deployment? {
        latestDeployments?.first
    }

    struct Target: Decodable {
        let productionDomain: String?

        enum CodingKeys: String, CodingKey {
            case productionDomain
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            productionDomain = try container.decodeIfPresent(String.self, forKey: .productionDomain)
        }
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
}

struct ProjectsResponse: Decodable {
    let projects: [Project]
}
