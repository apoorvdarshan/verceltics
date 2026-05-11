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
    var sourceScope: ProjectSourceScope?

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

    func withSourceScope(_ scope: ProjectSourceScope) -> Project {
        var copy = self
        copy.sourceScope = scope
        return copy
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

nonisolated struct ProjectSourceScope: Decodable, Equatable {
    let id: String?
    let name: String
    let slug: String?
    let isTeam: Bool
}

nonisolated struct VercelTeamsResponse: Decodable {
    let teams: [VercelTeam]
}

nonisolated struct VercelTeam: Identifiable, Decodable, Equatable {
    let id: String
    let slug: String
    let name: String?
    let avatar: String?
    let membership: Membership?

    var displayName: String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? slug : trimmed
    }

    var isConfirmedMember: Bool {
        membership?.confirmed ?? true
    }

    struct Membership: Decodable, Equatable {
        let confirmed: Bool?
        let role: String?
    }
}

nonisolated struct DeploymentsResponse: Decodable {
    let deployments: [RecentDeployment]
}

nonisolated struct RecentDeployment: Identifiable, Decodable, Equatable {
    let uid: String?
    let name: String?
    let url: String?
    let inspectorUrl: String?
    let state: String?
    let readyState: String?
    let target: String?
    let createdAt: Int?
    let meta: Meta?
    let creator: Creator?

    var id: String {
        uid ?? url ?? "\(name ?? "deployment")-\(createdAt ?? 0)"
    }

    var date: Date? {
        guard let createdAt else { return nil }
        let seconds = createdAt > 10_000_000_000
            ? Double(createdAt) / 1000
            : Double(createdAt)
        return Date(timeIntervalSince1970: seconds)
    }

    var displayState: String {
        state ?? readyState ?? "UNKNOWN"
    }

    var displayTarget: String {
        target?.capitalized ?? "Preview"
    }

    struct Meta: Decodable, Equatable {
        let githubCommitMessage: String?
        let githubCommitRef: String?
        let githubCommitSha: String?
        let githubCommitAuthorName: String?
        let githubOrg: String?
        let githubRepo: String?
    }

    struct Creator: Decodable, Equatable {
        let username: String?
        let email: String?
    }
}

nonisolated struct DeploymentEvent: Identifiable, Decodable, Equatable {
    let type: String
    let created: Int
    let payload: [String: VercelJSONValue]

    enum CodingKeys: String, CodingKey {
        case type
        case created
        case payload
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type) ?? "event"
        created = try container.decodeIfPresent(Int.self, forKey: .created) ?? 0
        payload = try container.decodeIfPresent([String: VercelJSONValue].self, forKey: .payload) ?? [:]
    }

    var id: String {
        "\(created)-\(type)-\(message.prefix(32))"
    }

    var date: Date {
        let seconds = created > 10_000_000_000
            ? Double(created) / 1000
            : Double(created)
        return Date(timeIntervalSince1970: seconds)
    }

    var message: String {
        if let text = payload["text"]?.stringValue, !text.isEmpty {
            return text.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if let info = payload["info"]?.objectValue {
            let fields = [
                info["step"]?.stringValue,
                info["type"]?.stringValue,
                info["name"]?.stringValue,
                info["entrypoint"]?.stringValue,
                info["readyState"]?.stringValue
            ]
            let joined = fields.compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
            if !joined.isEmpty { return joined }
        }

        if let statusCode = payload["statusCode"]?.displayValue {
            return "HTTP \(statusCode)"
        }

        return type
    }

    var statusCode: String? {
        payload["statusCode"]?.displayValue
    }
}

nonisolated indirect enum VercelJSONValue: Decodable, Equatable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case object([String: VercelJSONValue])
    case array([VercelJSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: VercelJSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([VercelJSONValue].self) {
            self = .array(value)
        } else {
            self = .null
        }
    }

    var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    var objectValue: [String: VercelJSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    var displayValue: String? {
        switch self {
        case .string(let value):
            return value
        case .int(let value):
            return "\(value)"
        case .double(let value):
            return String(format: "%.0f", value)
        case .bool(let value):
            return value ? "true" : "false"
        case .array(let values):
            return values.compactMap(\.displayValue).joined(separator: ", ")
        case .object, .null:
            return nil
        }
    }
}
