import Foundation

// MARK: - Shared values

nonisolated indirect enum CloudflareJSONValue: Codable, Equatable, Sendable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case object([String: CloudflareJSONValue])
    case array([CloudflareJSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: CloudflareJSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([CloudflareJSONValue].self) {
            self = .array(value)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported Cloudflare JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()

        switch self {
        case .string(let value):
            try container.encode(value)
        case .int(let value):
            try container.encode(value)
        case .double(let value):
            try container.encode(value)
        case .bool(let value):
            try container.encode(value)
        case .object(let value):
            try container.encode(value)
        case .array(let value):
            try container.encode(value)
        case .null:
            try container.encodeNil()
        }
    }

    var stringValue: String? {
        guard case .string(let value) = self else { return nil }
        return value
    }

    var integerValue: Int64? {
        switch self {
        case .int(let value): value
        case .double(let value): Int64(value)
        default: nil
        }
    }
}

nonisolated struct CloudflareAPIIssue: Decodable, Equatable, Sendable {
    let code: Int?
    let message: String
    let documentationURL: String?
    let source: Source?

    enum CodingKeys: String, CodingKey {
        case code
        case message
        case documentationURL = "documentation_url"
        case source
    }

    struct Source: Decodable, Equatable, Sendable {
        let pointer: String?
    }
}

nonisolated struct CloudflareResultInfo: Decodable, Equatable, Sendable {
    let page: Int?
    let perPage: Int?
    let count: Int?
    let totalCount: Int?
    let totalPages: Int?
    let cursors: Cursors?

    enum CodingKeys: String, CodingKey {
        case page
        case perPage = "per_page"
        case count
        case totalCount = "total_count"
        case totalPages = "total_pages"
        case cursors
    }

    struct Cursors: Decodable, Equatable, Sendable {
        let before: String?
        let after: String?
    }
}

// MARK: - User and accounts

nonisolated struct CloudflareUser: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let email: String
    let firstName: String?
    let lastName: String?
    let country: String?
    let betas: [String]
    let telephone: String?
    let zipcode: String?
    let suspended: Bool?
    let twoFactorAuthenticationEnabled: Bool?
    let twoFactorAuthenticationLocked: Bool?
    let hasProZones: Bool?
    let hasBusinessZones: Bool?
    let hasEnterpriseZones: Bool?
    let organizations: [Organization]

    var displayName: String {
        let fullName = [firstName, lastName]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return fullName.isEmpty ? email : fullName
    }

    enum CodingKeys: String, CodingKey {
        case id
        case email
        case firstName = "first_name"
        case lastName = "last_name"
        case country
        case betas
        case telephone
        case zipcode
        case suspended
        case twoFactorAuthenticationEnabled = "two_factor_authentication_enabled"
        case twoFactorAuthenticationLocked = "two_factor_authentication_locked"
        case hasProZones = "has_pro_zones"
        case hasBusinessZones = "has_business_zones"
        case hasEnterpriseZones = "has_enterprise_zones"
        case organizations
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        email = try container.decode(String.self, forKey: .email)
        firstName = try container.decodeIfPresent(String.self, forKey: .firstName)
        lastName = try container.decodeIfPresent(String.self, forKey: .lastName)
        country = try container.decodeIfPresent(String.self, forKey: .country)
        betas = try container.decodeIfPresent([String].self, forKey: .betas) ?? []
        telephone = try container.decodeIfPresent(String.self, forKey: .telephone)
        zipcode = try container.decodeIfPresent(String.self, forKey: .zipcode)
        suspended = try container.decodeIfPresent(Bool.self, forKey: .suspended)
        twoFactorAuthenticationEnabled = try container.decodeIfPresent(Bool.self, forKey: .twoFactorAuthenticationEnabled)
        twoFactorAuthenticationLocked = try container.decodeIfPresent(Bool.self, forKey: .twoFactorAuthenticationLocked)
        hasProZones = try container.decodeIfPresent(Bool.self, forKey: .hasProZones)
        hasBusinessZones = try container.decodeIfPresent(Bool.self, forKey: .hasBusinessZones)
        hasEnterpriseZones = try container.decodeIfPresent(Bool.self, forKey: .hasEnterpriseZones)
        organizations = try container.decodeIfPresent([Organization].self, forKey: .organizations) ?? []
    }

    struct Organization: Identifiable, Decodable, Equatable, Sendable {
        let id: String
        let name: String?
        let permissions: [String]
        let roles: [String]
        let status: String?

        enum CodingKeys: String, CodingKey {
            case id
            case name
            case permissions
            case roles
            case status
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decodeIfPresent(String.self, forKey: .id) ?? UUID().uuidString
            name = try container.decodeIfPresent(String.self, forKey: .name)
            permissions = try container.decodeIfPresent([String].self, forKey: .permissions) ?? []
            roles = try container.decodeIfPresent([String].self, forKey: .roles) ?? []
            status = try container.decodeIfPresent(String.self, forKey: .status)
        }
    }
}

nonisolated struct CloudflareAccountSummary: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let type: String?
    let createdOn: String?
    let settings: Settings?
    let managedBy: ManagedBy?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case type
        case createdOn = "created_on"
        case settings
        case managedBy = "managed_by"
    }

    struct Settings: Decodable, Equatable, Sendable {
        let abuseContactEmail: String?
        let enforceTwoFactor: Bool?

        enum CodingKeys: String, CodingKey {
            case abuseContactEmail = "abuse_contact_email"
            case enforceTwoFactor = "enforce_twofactor"
        }
    }

    struct ManagedBy: Decodable, Equatable, Sendable {
        let parentOrganizationID: String?
        let parentOrganizationName: String?

        enum CodingKeys: String, CodingKey {
            case parentOrganizationID = "parent_org_id"
            case parentOrganizationName = "parent_org_name"
        }
    }
}

// MARK: - Zones

nonisolated struct CloudflareZone: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let status: String?
    let type: String?
    let paused: Bool?
    let developmentMode: Int?
    let nameServers: [String]
    let originalNameServers: [String]
    let originalRegistrar: String?
    let originalDNSHost: String?
    let createdOn: String?
    let modifiedOn: String?
    let activatedOn: String?
    let account: AccountReference?
    let plan: Plan?
    let meta: [String: CloudflareJSONValue]
    let owner: [String: CloudflareJSONValue]?
    let tenant: [String: CloudflareJSONValue]?
    let tenantUnit: [String: CloudflareJSONValue]?
    let vanityNameServers: [String]
    let verificationKey: String?
    let permissions: [String]
    let cnameSuffix: String?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }
    var activatedDate: Date? { CloudflareDateParser.date(from: activatedOn) }
    var isActive: Bool { status?.lowercased() == "active" && paused != true }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case status
        case type
        case paused
        case developmentMode = "development_mode"
        case nameServers = "name_servers"
        case originalNameServers = "original_name_servers"
        case originalRegistrar = "original_registrar"
        case originalDNSHost = "original_dnshost"
        case createdOn = "created_on"
        case modifiedOn = "modified_on"
        case activatedOn = "activated_on"
        case account
        case plan
        case meta
        case owner
        case tenant
        case tenantUnit = "tenant_unit"
        case vanityNameServers = "vanity_name_servers"
        case verificationKey = "verification_key"
        case permissions
        case cnameSuffix = "cname_suffix"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        paused = try container.decodeIfPresent(Bool.self, forKey: .paused)
        developmentMode = try container.decodeIfPresent(Int.self, forKey: .developmentMode)
        nameServers = try container.decodeIfPresent([String].self, forKey: .nameServers) ?? []
        originalNameServers = try container.decodeIfPresent([String].self, forKey: .originalNameServers) ?? []
        originalRegistrar = try container.decodeIfPresent(String.self, forKey: .originalRegistrar)
        originalDNSHost = try container.decodeIfPresent(String.self, forKey: .originalDNSHost)
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        modifiedOn = try container.decodeIfPresent(String.self, forKey: .modifiedOn)
        activatedOn = try container.decodeIfPresent(String.self, forKey: .activatedOn)
        account = try container.decodeIfPresent(AccountReference.self, forKey: .account)
        plan = try container.decodeIfPresent(Plan.self, forKey: .plan)
        meta = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .meta) ?? [:]
        owner = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .owner)
        tenant = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .tenant)
        tenantUnit = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .tenantUnit)
        vanityNameServers = try container.decodeIfPresent([String].self, forKey: .vanityNameServers) ?? []
        verificationKey = try container.decodeIfPresent(String.self, forKey: .verificationKey)
        permissions = try container.decodeIfPresent([String].self, forKey: .permissions) ?? []
        cnameSuffix = try container.decodeIfPresent(String.self, forKey: .cnameSuffix)
    }

    struct AccountReference: Decodable, Equatable, Sendable {
        let id: String?
        let name: String?
    }

    struct Plan: Decodable, Equatable, Sendable {
        let id: String?
        let name: String?
        let currency: String?
        let frequency: String?
        let price: Double?
        let isSubscribed: Bool?
        let canSubscribe: Bool?
        let externallyManaged: Bool?
        let legacyDiscount: Bool?
        let legacyID: String?

        enum CodingKeys: String, CodingKey {
            case id
            case name
            case currency
            case frequency
            case price
            case isSubscribed = "is_subscribed"
            case canSubscribe = "can_subscribe"
            case externallyManaged = "externally_managed"
            case legacyDiscount = "legacy_discount"
            case legacyID = "legacy_id"
        }
    }
}

// MARK: - Pages

nonisolated enum CloudflarePagesEnvironment: String, CaseIterable, Identifiable, Codable, Sendable {
    case production
    case preview

    var id: String { rawValue }
}

nonisolated struct CloudflarePagesProject: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let subdomain: String?
    let domains: [String]
    let productionBranch: String?
    let createdOn: String?
    let latestDeployment: CloudflarePagesDeployment?
    let canonicalDeployment: CloudflarePagesDeployment?
    let source: CloudflarePagesSource?
    let buildConfig: CloudflarePagesBuildConfig?
    let usesFunctions: Bool?
    let productionScriptName: String?
    let previewScriptName: String?
    let deploymentConfigs: [String: CloudflareJSONValue]?
    let framework: String?
    let frameworkVersion: String?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var primaryURL: String? {
        canonicalDeployment?.url ?? latestDeployment?.url ?? subdomain
    }

    enum CodingKeys: String, CodingKey {
        case id
        case name
        case subdomain
        case domains
        case productionBranch = "production_branch"
        case createdOn = "created_on"
        case latestDeployment = "latest_deployment"
        case canonicalDeployment = "canonical_deployment"
        case source
        case buildConfig = "build_config"
        case usesFunctions = "uses_functions"
        case productionScriptName = "production_script_name"
        case previewScriptName = "preview_script_name"
        case deploymentConfigs = "deployment_configs"
        case framework
        case frameworkVersion = "framework_version"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        subdomain = try container.decodeIfPresent(String.self, forKey: .subdomain)
        domains = try container.decodeIfPresent([String].self, forKey: .domains) ?? []
        productionBranch = try container.decodeIfPresent(String.self, forKey: .productionBranch)
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        latestDeployment = try container.decodeIfPresent(CloudflarePagesDeployment.self, forKey: .latestDeployment)
        canonicalDeployment = try container.decodeIfPresent(CloudflarePagesDeployment.self, forKey: .canonicalDeployment)
        source = try container.decodeIfPresent(CloudflarePagesSource.self, forKey: .source)
        buildConfig = try container.decodeIfPresent(CloudflarePagesBuildConfig.self, forKey: .buildConfig)
        usesFunctions = try container.decodeIfPresent(Bool.self, forKey: .usesFunctions)
        productionScriptName = try container.decodeIfPresent(String.self, forKey: .productionScriptName)
        previewScriptName = try container.decodeIfPresent(String.self, forKey: .previewScriptName)
        deploymentConfigs = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .deploymentConfigs)
        framework = try container.decodeIfPresent(String.self, forKey: .framework)
        frameworkVersion = try container.decodeIfPresent(String.self, forKey: .frameworkVersion)
    }
}

nonisolated struct CloudflarePagesBuildConfig: Decodable, Equatable, Sendable {
    let buildCommand: String?
    let destinationDirectory: String?
    let rootDirectory: String?
    let buildCaching: Bool?
    let webAnalyticsTag: String?
    let webAnalyticsToken: String?

    enum CodingKeys: String, CodingKey {
        case buildCommand = "build_command"
        case destinationDirectory = "destination_dir"
        case rootDirectory = "root_dir"
        case buildCaching = "build_caching"
        case webAnalyticsTag = "web_analytics_tag"
        case webAnalyticsToken = "web_analytics_token"
    }
}

nonisolated struct CloudflarePagesSource: Decodable, Equatable, Sendable {
    let type: String?
    let config: Config?

    struct Config: Decodable, Equatable, Sendable {
        let owner: String?
        let ownerID: String?
        let repositoryID: String?
        let repositoryName: String?
        let productionBranch: String?
        let productionDeploymentsEnabled: Bool?
        let deploymentsEnabled: Bool?
        let previewDeploymentSetting: String?
        let previewBranchIncludes: [String]
        let previewBranchExcludes: [String]
        let pathIncludes: [String]
        let pathExcludes: [String]
        let pullRequestCommentsEnabled: Bool?

        enum CodingKeys: String, CodingKey {
            case owner
            case ownerID = "owner_id"
            case repositoryID = "repo_id"
            case repositoryName = "repo_name"
            case productionBranch = "production_branch"
            case productionDeploymentsEnabled = "production_deployments_enabled"
            case deploymentsEnabled = "deployments_enabled"
            case previewDeploymentSetting = "preview_deployment_setting"
            case previewBranchIncludes = "preview_branch_includes"
            case previewBranchExcludes = "preview_branch_excludes"
            case pathIncludes = "path_includes"
            case pathExcludes = "path_excludes"
            case pullRequestCommentsEnabled = "pr_comments_enabled"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            owner = try container.decodeIfPresent(String.self, forKey: .owner)
            ownerID = try container.decodeIfPresent(String.self, forKey: .ownerID)
            repositoryID = try container.decodeIfPresent(String.self, forKey: .repositoryID)
            repositoryName = try container.decodeIfPresent(String.self, forKey: .repositoryName)
            productionBranch = try container.decodeIfPresent(String.self, forKey: .productionBranch)
            productionDeploymentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .productionDeploymentsEnabled)
            deploymentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .deploymentsEnabled)
            previewDeploymentSetting = try container.decodeIfPresent(String.self, forKey: .previewDeploymentSetting)
            previewBranchIncludes = try container.decodeIfPresent([String].self, forKey: .previewBranchIncludes) ?? []
            previewBranchExcludes = try container.decodeIfPresent([String].self, forKey: .previewBranchExcludes) ?? []
            pathIncludes = try container.decodeIfPresent([String].self, forKey: .pathIncludes) ?? []
            pathExcludes = try container.decodeIfPresent([String].self, forKey: .pathExcludes) ?? []
            pullRequestCommentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .pullRequestCommentsEnabled)
        }
    }
}

nonisolated struct CloudflarePagesDeployment: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let shortID: String?
    let projectID: String?
    let projectName: String?
    let environment: CloudflarePagesEnvironment?
    let url: String?
    let aliases: [String]
    let createdOn: String?
    let modifiedOn: String?
    let latestStage: Stage?
    let deploymentTrigger: Trigger?
    let stages: [Stage]
    let source: CloudflarePagesSource?
    let buildConfig: CloudflarePagesBuildConfig?
    let isSkipped: Bool?
    let usesFunctions: Bool?
    let environmentVariables: [String: CloudflareJSONValue]

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }
    var displayStatus: String { latestStage?.status ?? "unknown" }
    var branch: String? { deploymentTrigger?.metadata?.branch }
    var commitHash: String? { deploymentTrigger?.metadata?.commitHash }
    var commitMessage: String? { deploymentTrigger?.metadata?.commitMessage }

    enum CodingKeys: String, CodingKey {
        case id
        case shortID = "short_id"
        case projectID = "project_id"
        case projectName = "project_name"
        case environment
        case url
        case aliases
        case createdOn = "created_on"
        case modifiedOn = "modified_on"
        case latestStage = "latest_stage"
        case deploymentTrigger = "deployment_trigger"
        case stages
        case source
        case buildConfig = "build_config"
        case isSkipped = "is_skipped"
        case usesFunctions = "uses_functions"
        case environmentVariables = "env_vars"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        shortID = try container.decodeIfPresent(String.self, forKey: .shortID)
        projectID = try container.decodeIfPresent(String.self, forKey: .projectID)
        projectName = try container.decodeIfPresent(String.self, forKey: .projectName)
        environment = try container.decodeIfPresent(CloudflarePagesEnvironment.self, forKey: .environment)
        url = try container.decodeIfPresent(String.self, forKey: .url)
        aliases = try container.decodeIfPresent([String].self, forKey: .aliases) ?? []
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        modifiedOn = try container.decodeIfPresent(String.self, forKey: .modifiedOn)
        latestStage = try container.decodeIfPresent(Stage.self, forKey: .latestStage)
        deploymentTrigger = try container.decodeIfPresent(Trigger.self, forKey: .deploymentTrigger)
        stages = try container.decodeIfPresent([Stage].self, forKey: .stages) ?? []
        source = try container.decodeIfPresent(CloudflarePagesSource.self, forKey: .source)
        buildConfig = try container.decodeIfPresent(CloudflarePagesBuildConfig.self, forKey: .buildConfig)
        isSkipped = try container.decodeIfPresent(Bool.self, forKey: .isSkipped)
        usesFunctions = try container.decodeIfPresent(Bool.self, forKey: .usesFunctions)
        environmentVariables = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .environmentVariables) ?? [:]
    }

    struct Stage: Decodable, Equatable, Sendable {
        let name: String?
        let status: String?
        let startedOn: String?
        let endedOn: String?

        enum CodingKeys: String, CodingKey {
            case name
            case status
            case startedOn = "started_on"
            case endedOn = "ended_on"
        }
    }

    struct Trigger: Decodable, Equatable, Sendable {
        let type: String?
        let metadata: Metadata?

        struct Metadata: Decodable, Equatable, Sendable {
            let branch: String?
            let commitHash: String?
            let commitMessage: String?
            let commitDirty: Bool?

            enum CodingKeys: String, CodingKey {
                case branch
                case commitHash = "commit_hash"
                case commitMessage = "commit_message"
                case commitDirty = "commit_dirty"
            }
        }
    }
}

nonisolated struct CloudflarePagesDeploymentLog: Identifiable, Decodable, Equatable, Sendable {
    let line: String
    let timestamp: String

    var id: String { "\(timestamp)-\(line)" }
    var date: Date? { CloudflareDateParser.date(from: timestamp) }

    enum CodingKeys: String, CodingKey {
        case line
        case timestamp = "ts"
    }
}

// MARK: - Workers

nonisolated struct CloudflareWorkerScript: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let createdOn: String?
    let modifiedOn: String?
    let compatibilityDate: String?
    let compatibilityFlags: [String]
    let handlers: [String]
    let hasAssets: Bool?
    let hasModules: Bool?
    let lastDeployedFrom: String?
    let logpush: Bool?
    let migrationTag: String?
    let tag: String?
    let routes: [Route]
    let tags: [String]
    let usageModel: String?
    let etag: String?
    let cacheOptions: [String: CloudflareJSONValue]?
    let namedHandlers: [CloudflareJSONValue]
    let observability: [String: CloudflareJSONValue]?
    let placement: [String: CloudflareJSONValue]?
    let placementMode: String?
    let placementStatus: String?
    let tailConsumers: [CloudflareJSONValue]

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }

    enum CodingKeys: String, CodingKey {
        case id
        case createdOn = "created_on"
        case modifiedOn = "modified_on"
        case compatibilityDate = "compatibility_date"
        case compatibilityFlags = "compatibility_flags"
        case handlers
        case hasAssets = "has_assets"
        case hasModules = "has_modules"
        case lastDeployedFrom = "last_deployed_from"
        case logpush
        case migrationTag = "migration_tag"
        case tag
        case routes
        case tags
        case usageModel = "usage_model"
        case etag
        case cacheOptions = "cache_options"
        case namedHandlers = "named_handlers"
        case observability
        case placement
        case placementMode = "placement_mode"
        case placementStatus = "placement_status"
        case tailConsumers = "tail_consumers"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? "unknown-worker"
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        modifiedOn = try container.decodeIfPresent(String.self, forKey: .modifiedOn)
        compatibilityDate = try container.decodeIfPresent(String.self, forKey: .compatibilityDate)
        compatibilityFlags = try container.decodeIfPresent([String].self, forKey: .compatibilityFlags) ?? []
        handlers = try container.decodeIfPresent([String].self, forKey: .handlers) ?? []
        hasAssets = try container.decodeIfPresent(Bool.self, forKey: .hasAssets)
        hasModules = try container.decodeIfPresent(Bool.self, forKey: .hasModules)
        lastDeployedFrom = try container.decodeIfPresent(String.self, forKey: .lastDeployedFrom)
        logpush = try container.decodeIfPresent(Bool.self, forKey: .logpush)
        migrationTag = try container.decodeIfPresent(String.self, forKey: .migrationTag)
        tag = try container.decodeIfPresent(String.self, forKey: .tag)
        routes = try container.decodeIfPresent([Route].self, forKey: .routes) ?? []
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        usageModel = try container.decodeIfPresent(String.self, forKey: .usageModel)
        etag = try container.decodeIfPresent(String.self, forKey: .etag)
        cacheOptions = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .cacheOptions)
        namedHandlers = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .namedHandlers) ?? []
        observability = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .observability)
        placement = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .placement)
        placementMode = try container.decodeIfPresent(String.self, forKey: .placementMode)
        placementStatus = try container.decodeIfPresent(String.self, forKey: .placementStatus)
        tailConsumers = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .tailConsumers) ?? []
    }

    struct Route: Identifiable, Decodable, Equatable, Sendable {
        let id: String
        let pattern: String
        let script: String?

        enum CodingKeys: String, CodingKey {
            case id
            case pattern
            case script
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            pattern = try container.decodeIfPresent(String.self, forKey: .pattern) ?? ""
            script = try container.decodeIfPresent(String.self, forKey: .script)
            id = try container.decodeIfPresent(String.self, forKey: .id) ?? pattern
        }
    }
}

nonisolated struct CloudflareWorkerDeployment: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let createdOn: String?
    let source: String?
    let strategy: String?
    let versions: [Version]
    let annotations: Annotations?
    let authorEmail: String?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }

    enum CodingKeys: String, CodingKey {
        case id
        case createdOn = "created_on"
        case source
        case strategy
        case versions
        case annotations
        case authorEmail = "author_email"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        source = try container.decodeIfPresent(String.self, forKey: .source)
        strategy = try container.decodeIfPresent(String.self, forKey: .strategy)
        versions = try container.decodeIfPresent([Version].self, forKey: .versions) ?? []
        annotations = try container.decodeIfPresent(Annotations.self, forKey: .annotations)
        authorEmail = try container.decodeIfPresent(String.self, forKey: .authorEmail)
    }

    struct Version: Decodable, Equatable, Sendable {
        let versionID: String
        let percentage: Double

        enum CodingKeys: String, CodingKey {
            case versionID = "version_id"
            case percentage
        }
    }

    struct Annotations: Decodable, Equatable, Sendable {
        let message: String?
        let triggeredBy: String?

        enum CodingKeys: String, CodingKey {
            case message = "workers/message"
            case triggeredBy = "workers/triggered_by"
        }
    }
}

// MARK: - DNS

nonisolated struct CloudflareDNSRecord: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let type: String
    let name: String
    let content: String?
    let proxiable: Bool?
    let proxied: Bool?
    let ttl: Int?
    let locked: Bool?
    let comment: String?
    let commentModifiedOn: String?
    let tags: [String]
    let tagsModifiedOn: String?
    let createdOn: String?
    let modifiedOn: String?
    let priority: Int?
    let data: [String: CloudflareJSONValue]?
    let settings: [String: CloudflareJSONValue]?
    let privateRouting: Bool?
    let meta: [String: CloudflareJSONValue]

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }
    var commentModifiedDate: Date? { CloudflareDateParser.date(from: commentModifiedOn) }
    var tagsModifiedDate: Date? { CloudflareDateParser.date(from: tagsModifiedOn) }

    enum CodingKeys: String, CodingKey {
        case id
        case type
        case name
        case content
        case proxiable
        case proxied
        case ttl
        case locked
        case comment
        case commentModifiedOn = "comment_modified_on"
        case tags
        case tagsModifiedOn = "tags_modified_on"
        case createdOn = "created_on"
        case modifiedOn = "modified_on"
        case priority
        case data
        case settings
        case privateRouting = "private_routing"
        case meta
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        type = try container.decode(String.self, forKey: .type)
        name = try container.decode(String.self, forKey: .name)
        content = try container.decodeIfPresent(String.self, forKey: .content)
        proxiable = try container.decodeIfPresent(Bool.self, forKey: .proxiable)
        proxied = try container.decodeIfPresent(Bool.self, forKey: .proxied)
        ttl = try container.decodeIfPresent(Int.self, forKey: .ttl)
        locked = try container.decodeIfPresent(Bool.self, forKey: .locked)
        comment = try container.decodeIfPresent(String.self, forKey: .comment)
        commentModifiedOn = try container.decodeIfPresent(String.self, forKey: .commentModifiedOn)
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        tagsModifiedOn = try container.decodeIfPresent(String.self, forKey: .tagsModifiedOn)
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        modifiedOn = try container.decodeIfPresent(String.self, forKey: .modifiedOn)
        priority = try container.decodeIfPresent(Int.self, forKey: .priority)
        data = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .data)
        settings = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .settings)
        privateRouting = try container.decodeIfPresent(Bool.self, forKey: .privateRouting)
        meta = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .meta) ?? [:]
    }
}

nonisolated struct CloudflareDNSRecordInput: Encodable, Equatable, Sendable {
    let type: String
    let name: String
    let content: String?
    let ttl: Int
    let proxied: Bool?
    let comment: String?
    let tags: [String]?
    let priority: Int?
    let data: [String: CloudflareJSONValue]?
    let settings: [String: CloudflareJSONValue]?
    let privateRouting: Bool?

    init(
        type: String,
        name: String,
        content: String? = nil,
        ttl: Int = 1,
        proxied: Bool? = nil,
        comment: String? = nil,
        tags: [String]? = nil,
        priority: Int? = nil,
        data: [String: CloudflareJSONValue]? = nil,
        settings: [String: CloudflareJSONValue]? = nil,
        privateRouting: Bool? = nil
    ) {
        self.type = type.uppercased()
        self.name = name
        self.content = content
        self.ttl = ttl
        self.proxied = proxied
        self.comment = comment
        self.tags = tags
        self.priority = priority
        self.data = data
        self.settings = settings
        self.privateRouting = privateRouting
    }

    enum CodingKeys: String, CodingKey {
        case type
        case name
        case content
        case ttl
        case proxied
        case comment
        case tags
        case priority
        case data
        case settings
        case privateRouting = "private_routing"
    }
}

// MARK: - Cache and mutation safety

nonisolated enum CloudflareCachePurge: Encodable, Equatable, Sendable {
    case everything
    case files([String])
    case tags([String])
    case hosts([String])
    case prefixes([String])

    enum CodingKeys: String, CodingKey {
        case purgeEverything = "purge_everything"
        case files
        case tags
        case hosts
        case prefixes
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .everything:
            try container.encode(true, forKey: .purgeEverything)
        case .files(let values):
            try container.encode(values, forKey: .files)
        case .tags(let values):
            try container.encode(values, forKey: .tags)
        case .hosts(let values):
            try container.encode(values, forKey: .hosts)
        case .prefixes(let values):
            try container.encode(values, forKey: .prefixes)
        }
    }
}

/// Destructive methods require a confirmation object tied to the exact resource.
/// Construct this only after the app has shown a destructive-action confirmation UI.
nonisolated struct CloudflareMutationConfirmation: Equatable, Sendable {
    let resourceID: String

    init(confirmingResourceID resourceID: String) {
        self.resourceID = resourceID
    }
}

// MARK: - Analytics

nonisolated enum CloudflareAnalyticsGranularity: String, Equatable, Sendable {
    case hourly
    case daily

    var displayName: String { rawValue.uppercased() }
}

nonisolated struct CloudflareZoneAnalyticsSummary: Equatable, Sendable {
    let zoneID: String
    let requestedFrom: Date
    let requestedTo: Date
    let from: Date
    let to: Date
    let granularity: CloudflareAnalyticsGranularity
    let isWindowLimited: Bool
    let totals: CloudflareAnalyticsMetrics
    let series: [CloudflareZoneAnalyticsPoint]

    var windowLabel: String {
        if granularity == .daily {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? calendar.timeZone
            let fromDay = calendar.startOfDay(for: from)
            let toDay = calendar.startOfDay(for: to)
            let days = max(
                1,
                (calendar.dateComponents([.day], from: fromDay, to: toDay).day ?? 0) + 1
            )
            return "LAST \(days) \(days == 1 ? "DAY" : "DAYS")"
        }

        let duration = max(1, to.timeIntervalSince(from))

        if duration >= 86_400 {
            let days = max(1, Int(ceil(duration / 86_400)))
            return "LAST \(days) \(days == 1 ? "DAY" : "DAYS")"
        }

        if duration >= 3_600 {
            let hours = max(1, Int(ceil(duration / 3_600)))
            return "LAST \(hours) \(hours == 1 ? "HOUR" : "HOURS")"
        }

        let minutes = max(1, Int(ceil(duration / 60)))
        return "LAST \(minutes) \(minutes == 1 ? "MINUTE" : "MINUTES")"
    }

    var chartTitle: String { "REQUESTS · \(windowLabel)" }
}

nonisolated struct CloudflareAnalyticsMetrics: Equatable, Sendable {
    let requests: Int64
    let pageViews: Int64
    let bytes: Int64
    let cachedRequests: Int64
    let cachedBytes: Int64
    let threats: Int64
    let encryptedRequests: Int64
    let uniqueVisitors: Int64

    static let zero = CloudflareAnalyticsMetrics(
        requests: 0,
        pageViews: 0,
        bytes: 0,
        cachedRequests: 0,
        cachedBytes: 0,
        threats: 0,
        encryptedRequests: 0,
        uniqueVisitors: 0
    )

    var cacheHitRate: Double? {
        guard requests > 0 else { return nil }
        return (Double(cachedRequests) / Double(requests)) * 100
    }

    var encryptedRequestRate: Double? {
        guard requests > 0 else { return nil }
        return (Double(encryptedRequests) / Double(requests)) * 100
    }
}

nonisolated struct CloudflareZoneAnalyticsPoint: Identifiable, Equatable, Sendable {
    let timestamp: Date
    let metrics: CloudflareAnalyticsMetrics

    var id: Date { timestamp }
}

nonisolated struct CloudflareZoneAnalyticsBreakdowns: Equatable, Sendable {
    let countries: [CloudflareAnalyticsBreakdownItem]
    let statusCodes: [CloudflareAnalyticsBreakdownItem]
    let contentTypes: [CloudflareAnalyticsBreakdownItem]
    let tlsProtocols: [CloudflareAnalyticsBreakdownItem]
    let browsers: [CloudflareAnalyticsBreakdownItem]
    let ipClasses: [CloudflareAnalyticsBreakdownItem]
    let threatTypes: [CloudflareAnalyticsBreakdownItem]
    let encryptedBytes: Int64

    static let empty = CloudflareZoneAnalyticsBreakdowns(
        countries: [],
        statusCodes: [],
        contentTypes: [],
        tlsProtocols: [],
        browsers: [],
        ipClasses: [],
        threatTypes: [],
        encryptedBytes: 0
    )
}

nonisolated struct CloudflareAnalyticsBreakdownItem: Identifiable, Equatable, Sendable {
    let label: String
    let requests: Int64
    let bytes: Int64
    let threats: Int64
    let pageViews: Int64

    var id: String { label }
}

// MARK: - Helpers

nonisolated enum CloudflareDateParser {
    static func date(from value: String?) -> Date? {
        guard let value else { return nil }

        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }

        let standard = ISO8601DateFormatter()
        standard.formatOptions = [.withInternetDateTime]
        if let date = standard.date(from: value) { return date }

        let dateOnly = DateFormatter()
        dateOnly.calendar = Calendar(identifier: .gregorian)
        dateOnly.locale = Locale(identifier: "en_US_POSIX")
        dateOnly.timeZone = TimeZone(secondsFromGMT: 0)
        dateOnly.dateFormat = "yyyy-MM-dd"
        return dateOnly.date(from: value)
    }
}
