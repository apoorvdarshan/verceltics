import Foundation

// MARK: - Complete, safe Pages project metadata

/// The complete Pages project shape used by the operations screen.
/// Sensitive token and environment-variable values are intentionally reduced to presence metadata.
nonisolated struct CloudflarePagesOperationsProject: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let subdomain: String?
    let domains: [String]
    let productionBranch: String?
    let createdOn: String?
    let framework: String?
    let frameworkVersion: String?
    let latestDeployment: CloudflarePagesDeployment?
    let canonicalDeployment: CloudflarePagesDeployment?
    let source: CloudflarePagesOperationsSource?
    let buildConfig: CloudflarePagesSafeBuildConfig?
    let usesFunctions: Bool?
    let productionScriptName: String?
    let previewScriptName: String?
    let deploymentConfigs: CloudflarePagesDeploymentConfigurations?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }

    enum CodingKeys: String, CodingKey {
        case id, name, subdomain, domains, framework, source
        case productionBranch = "production_branch"
        case createdOn = "created_on"
        case frameworkVersion = "framework_version"
        case latestDeployment = "latest_deployment"
        case canonicalDeployment = "canonical_deployment"
        case buildConfig = "build_config"
        case usesFunctions = "uses_functions"
        case productionScriptName = "production_script_name"
        case previewScriptName = "preview_script_name"
        case deploymentConfigs = "deployment_configs"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        subdomain = try container.decodeIfPresent(String.self, forKey: .subdomain)
        domains = try container.decodeIfPresent([String].self, forKey: .domains) ?? []
        productionBranch = try container.decodeIfPresent(String.self, forKey: .productionBranch)
        createdOn = try container.decodeIfPresent(String.self, forKey: .createdOn)
        framework = try container.decodeIfPresent(String.self, forKey: .framework)
        frameworkVersion = try container.decodeIfPresent(String.self, forKey: .frameworkVersion)
        latestDeployment = try container.decodeIfPresent(CloudflarePagesDeployment.self, forKey: .latestDeployment)
        canonicalDeployment = try container.decodeIfPresent(CloudflarePagesDeployment.self, forKey: .canonicalDeployment)
        source = try container.decodeIfPresent(CloudflarePagesOperationsSource.self, forKey: .source)
        buildConfig = try container.decodeIfPresent(CloudflarePagesSafeBuildConfig.self, forKey: .buildConfig)
        usesFunctions = try container.decodeIfPresent(Bool.self, forKey: .usesFunctions)
        productionScriptName = try container.decodeIfPresent(String.self, forKey: .productionScriptName)
        previewScriptName = try container.decodeIfPresent(String.self, forKey: .previewScriptName)
        deploymentConfigs = try container.decodeIfPresent(CloudflarePagesDeploymentConfigurations.self, forKey: .deploymentConfigs)
    }
}

nonisolated struct CloudflarePagesSafeBuildConfig: Decodable, Equatable, Sendable {
    let buildCommand: String?
    let destinationDirectory: String?
    let rootDirectory: String?
    let buildCaching: Bool?
    let webAnalyticsTag: String?
    let webAnalyticsTokenConfigured: Bool

    enum CodingKeys: String, CodingKey {
        case buildCommand = "build_command"
        case destinationDirectory = "destination_dir"
        case rootDirectory = "root_dir"
        case buildCaching = "build_caching"
        case webAnalyticsTag = "web_analytics_tag"
        case webAnalyticsToken = "web_analytics_token"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        buildCommand = try container.decodeIfPresent(String.self, forKey: .buildCommand)
        destinationDirectory = try container.decodeIfPresent(String.self, forKey: .destinationDirectory)
        rootDirectory = try container.decodeIfPresent(String.self, forKey: .rootDirectory)
        buildCaching = try container.decodeIfPresent(Bool.self, forKey: .buildCaching)
        webAnalyticsTag = try container.decodeIfPresent(String.self, forKey: .webAnalyticsTag)
        webAnalyticsTokenConfigured = (try? container.decodeIfPresent(CloudflarePagesRedactedPresence.self, forKey: .webAnalyticsToken))?.isConfigured ?? false
    }
}

nonisolated struct CloudflarePagesOperationsSource: Decodable, Equatable, Sendable {
    let type: String?
    let config: Configuration?

    struct Configuration: Decodable, Equatable, Sendable {
        let deploymentsEnabled: Bool?
        let owner: String?
        let ownerID: String?
        let repositoryID: String?
        let repositoryName: String?
        let productionBranch: String?
        let productionDeploymentsEnabled: Bool?
        let previewDeploymentSetting: String?
        let previewBranchIncludes: [String]
        let previewBranchExcludes: [String]
        let pathIncludes: [String]
        let pathExcludes: [String]
        let pullRequestCommentsEnabled: Bool?

        enum CodingKeys: String, CodingKey {
            case deploymentsEnabled = "deployments_enabled"
            case owner
            case ownerID = "owner_id"
            case repositoryID = "repo_id"
            case repositoryName = "repo_name"
            case productionBranch = "production_branch"
            case productionDeploymentsEnabled = "production_deployments_enabled"
            case previewDeploymentSetting = "preview_deployment_setting"
            case previewBranchIncludes = "preview_branch_includes"
            case previewBranchExcludes = "preview_branch_excludes"
            case pathIncludes = "path_includes"
            case pathExcludes = "path_excludes"
            case pullRequestCommentsEnabled = "pr_comments_enabled"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            deploymentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .deploymentsEnabled)
            owner = try container.decodeIfPresent(String.self, forKey: .owner)
            ownerID = try container.decodeIfPresent(String.self, forKey: .ownerID)
            repositoryID = try container.decodeIfPresent(String.self, forKey: .repositoryID)
            repositoryName = try container.decodeIfPresent(String.self, forKey: .repositoryName)
            productionBranch = try container.decodeIfPresent(String.self, forKey: .productionBranch)
            productionDeploymentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .productionDeploymentsEnabled)
            previewDeploymentSetting = try container.decodeIfPresent(String.self, forKey: .previewDeploymentSetting)
            previewBranchIncludes = try container.decodeIfPresent([String].self, forKey: .previewBranchIncludes) ?? []
            previewBranchExcludes = try container.decodeIfPresent([String].self, forKey: .previewBranchExcludes) ?? []
            pathIncludes = try container.decodeIfPresent([String].self, forKey: .pathIncludes) ?? []
            pathExcludes = try container.decodeIfPresent([String].self, forKey: .pathExcludes) ?? []
            pullRequestCommentsEnabled = try container.decodeIfPresent(Bool.self, forKey: .pullRequestCommentsEnabled)
        }
    }
}

nonisolated struct CloudflarePagesDeploymentConfigurations: Decodable, Equatable, Sendable {
    let production: CloudflarePagesDeploymentConfiguration?
    let preview: CloudflarePagesDeploymentConfiguration?
}

nonisolated struct CloudflarePagesDeploymentConfiguration: Decodable, Equatable, Sendable {
    let compatibilityDate: String?
    let compatibilityFlags: [String]
    let alwaysUseLatestCompatibilityDate: Bool?
    let buildImageMajorVersion: Int?
    let failOpen: Bool?
    let usageModel: String?
    let wranglerConfigHash: String?
    let placement: Placement?
    let limits: Limits?
    let environmentVariables: [String: CloudflarePagesVariableSummary]
    let aiBindings: [String: CloudflarePagesBindingReference]
    let analyticsEngineDatasets: [String: CloudflarePagesBindingReference]
    let browserBindings: [String: CloudflarePagesBindingReference]
    let d1Databases: [String: CloudflarePagesBindingReference]
    let durableObjectNamespaces: [String: CloudflarePagesBindingReference]
    let hyperdriveBindings: [String: CloudflarePagesBindingReference]
    let kvNamespaces: [String: CloudflarePagesBindingReference]
    let mtlsCertificates: [String: CloudflarePagesBindingReference]
    let queueProducers: [String: CloudflarePagesBindingReference]
    let r2Buckets: [String: CloudflarePagesBindingReference]
    let services: [String: CloudflarePagesBindingReference]
    let vectorizeBindings: [String: CloudflarePagesBindingReference]

    var bindingCount: Int {
        aiBindings.count + analyticsEngineDatasets.count + browserBindings.count + d1Databases.count
            + durableObjectNamespaces.count + hyperdriveBindings.count + kvNamespaces.count
            + mtlsCertificates.count + queueProducers.count + r2Buckets.count + services.count
            + vectorizeBindings.count
    }

    var bindingGroups: [(name: String, values: [String: CloudflarePagesBindingReference])] {
        [
            ("AI", aiBindings), ("Analytics Engine", analyticsEngineDatasets), ("Browser", browserBindings),
            ("D1", d1Databases), ("Durable Objects", durableObjectNamespaces), ("Hyperdrive", hyperdriveBindings),
            ("KV", kvNamespaces), ("mTLS", mtlsCertificates), ("Queues", queueProducers),
            ("R2", r2Buckets), ("Services", services), ("Vectorize", vectorizeBindings)
        ].filter { !$0.values.isEmpty }
    }

    struct Placement: Decodable, Equatable, Sendable { let mode: String? }

    struct Limits: Decodable, Equatable, Sendable {
        let cpuMilliseconds: Int?
        enum CodingKeys: String, CodingKey { case cpuMilliseconds = "cpu_ms" }
    }

    enum CodingKeys: String, CodingKey {
        case compatibilityDate = "compatibility_date"
        case compatibilityFlags = "compatibility_flags"
        case alwaysUseLatestCompatibilityDate = "always_use_latest_compatibility_date"
        case buildImageMajorVersion = "build_image_major_version"
        case failOpen = "fail_open"
        case usageModel = "usage_model"
        case wranglerConfigHash = "wrangler_config_hash"
        case placement, limits
        case environmentVariables = "env_vars"
        case aiBindings = "ai_bindings"
        case analyticsEngineDatasets = "analytics_engine_datasets"
        case browserBindings = "browsers"
        case d1Databases = "d1_databases"
        case durableObjectNamespaces = "durable_object_namespaces"
        case hyperdriveBindings = "hyperdrive_bindings"
        case kvNamespaces = "kv_namespaces"
        case mtlsCertificates = "mtls_certificates"
        case queueProducers = "queue_producers"
        case r2Buckets = "r2_buckets"
        case services
        case vectorizeBindings = "vectorize_bindings"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        compatibilityDate = try container.decodeIfPresent(String.self, forKey: .compatibilityDate)
        compatibilityFlags = try container.decodeIfPresent([String].self, forKey: .compatibilityFlags) ?? []
        alwaysUseLatestCompatibilityDate = try container.decodeIfPresent(Bool.self, forKey: .alwaysUseLatestCompatibilityDate)
        buildImageMajorVersion = try container.decodeIfPresent(Int.self, forKey: .buildImageMajorVersion)
        failOpen = try container.decodeIfPresent(Bool.self, forKey: .failOpen)
        usageModel = try container.decodeIfPresent(String.self, forKey: .usageModel)
        wranglerConfigHash = try container.decodeIfPresent(String.self, forKey: .wranglerConfigHash)
        placement = try container.decodeIfPresent(Placement.self, forKey: .placement)
        limits = try container.decodeIfPresent(Limits.self, forKey: .limits)
        let decodedVariables = try container.decodeIfPresent([String: CloudflarePagesVariableSummary?].self, forKey: .environmentVariables)
        environmentVariables = decodedVariables?.compactMapValues { $0 } ?? [:]
        aiBindings = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .aiBindings) ?? [:]
        analyticsEngineDatasets = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .analyticsEngineDatasets) ?? [:]
        browserBindings = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .browserBindings) ?? [:]
        d1Databases = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .d1Databases) ?? [:]
        durableObjectNamespaces = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .durableObjectNamespaces) ?? [:]
        hyperdriveBindings = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .hyperdriveBindings) ?? [:]
        kvNamespaces = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .kvNamespaces) ?? [:]
        mtlsCertificates = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .mtlsCertificates) ?? [:]
        queueProducers = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .queueProducers) ?? [:]
        r2Buckets = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .r2Buckets) ?? [:]
        services = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .services) ?? [:]
        vectorizeBindings = try container.decodeIfPresent([String: CloudflarePagesBindingReference].self, forKey: .vectorizeBindings) ?? [:]
    }
}

nonisolated struct CloudflarePagesVariableSummary: Decodable, Equatable, Sendable {
    let type: String?
    let valueConfigured: Bool

    var isSecret: Bool { type?.lowercased() == "secret_text" }

    enum CodingKeys: String, CodingKey { case type, value }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        valueConfigured = (try? container.decodeIfPresent(CloudflarePagesRedactedPresence.self, forKey: .value))?.isConfigured ?? false
    }
}

nonisolated struct CloudflarePagesBindingReference: Decodable, Equatable, Sendable {
    let id: String?
    let namespaceID: String?
    let name: String?
    let service: String?
    let environment: String?
    let entrypoint: String?
    let dataset: String?
    let projectID: String?
    let certificateID: String?
    let indexName: String?
    let jurisdiction: String?

    var summary: String {
        [name, service, dataset, indexName, projectID, id, namespaceID, certificateID, environment, entrypoint, jurisdiction]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }

    enum CodingKeys: String, CodingKey {
        case id, name, service, environment, entrypoint, dataset, jurisdiction
        case namespaceID = "namespace_id"
        case projectID = "project_id"
        case certificateID = "certificate_id"
        case indexName = "index_name"
    }
}

private nonisolated struct CloudflarePagesRedactedPresence: Decodable, Equatable, Sendable {
    let isConfigured: Bool

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        isConfigured = !container.decodeNil()
    }
}

// MARK: - Custom domains

nonisolated struct CloudflarePagesCustomDomain: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let domainID: String?
    let name: String
    let status: String?
    let createdOn: String?
    let certificateAuthority: String?
    let zoneTag: String?
    let validationData: ValidationData?
    let verificationData: VerificationData?

    var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }
    var isActive: Bool { status?.lowercased() == "active" }

    enum CodingKeys: String, CodingKey {
        case id, name, status
        case domainID = "domain_id"
        case createdOn = "created_on"
        case certificateAuthority = "certificate_authority"
        case zoneTag = "zone_tag"
        case validationData = "validation_data"
        case verificationData = "verification_data"
    }

    struct ValidationData: Decodable, Equatable, Sendable {
        let status: String?
        let method: String?
        let errorMessage: String?
        let txtName: String?
        let txtValue: String?

        enum CodingKeys: String, CodingKey {
            case status, method
            case errorMessage = "error_message"
            case txtName = "txt_name"
            case txtValue = "txt_value"
        }
    }

    struct VerificationData: Decodable, Equatable, Sendable {
        let status: String?
        let errorMessage: String?

        enum CodingKeys: String, CodingKey {
            case status
            case errorMessage = "error_message"
        }
    }
}

// MARK: - Typed mutation payloads

nonisolated struct CloudflarePagesProjectUpdateRequest: Encodable, Equatable, Sendable {
    let productionBranch: String
    let buildConfig: BuildConfiguration
    let source: Source?

    enum CodingKeys: String, CodingKey {
        case productionBranch = "production_branch"
        case buildConfig = "build_config"
        case source
    }

    struct BuildConfiguration: Encodable, Equatable, Sendable {
        let buildCommand: String
        let destinationDirectory: String
        let rootDirectory: String
        let buildCaching: Bool

        enum CodingKeys: String, CodingKey {
            case buildCommand = "build_command"
            case destinationDirectory = "destination_dir"
            case rootDirectory = "root_dir"
            case buildCaching = "build_caching"
        }
    }

    struct Source: Encodable, Equatable, Sendable {
        let type: String
        let config: Configuration

        struct Configuration: Encodable, Equatable, Sendable {
            let owner: String?
            let ownerID: String?
            let repositoryID: String?
            let repositoryName: String?
            let productionBranch: String
            let productionDeploymentsEnabled: Bool
            let previewDeploymentSetting: String
            let previewBranchIncludes: [String]
            let previewBranchExcludes: [String]
            let pathIncludes: [String]
            let pathExcludes: [String]
            let pullRequestCommentsEnabled: Bool

            enum CodingKeys: String, CodingKey {
                case owner
                case ownerID = "owner_id"
                case repositoryID = "repo_id"
                case repositoryName = "repo_name"
                case productionBranch = "production_branch"
                case productionDeploymentsEnabled = "production_deployments_enabled"
                case previewDeploymentSetting = "preview_deployment_setting"
                case previewBranchIncludes = "preview_branch_includes"
                case previewBranchExcludes = "preview_branch_excludes"
                case pathIncludes = "path_includes"
                case pathExcludes = "path_excludes"
                case pullRequestCommentsEnabled = "pr_comments_enabled"
            }
        }
    }
}

nonisolated struct CloudflarePagesDirectUploadPreparation: Equatable, Sendable {
    let endpointPath: String
    let contentType: String
    let requiredParts: [String]
    let optionalParts: [String]

    static func make(accountID: String, projectName: String) -> Self {
        .init(
            endpointPath: "/accounts/\(accountID)/pages/projects/\(projectName)/deployments",
            contentType: "multipart/form-data",
            requiredParts: ["manifest", "one file part for each manifest hash"],
            optionalParts: [
                "branch", "commit_hash", "commit_message", "commit_dirty", "pages_build_output_dir",
                "_headers", "_redirects", "_routes.json", "_worker.js", "_worker.bundle",
                "functions-filepath-routing-config.json", "wrangler_config_hash"
            ]
        )
    }
}
