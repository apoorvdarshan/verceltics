import Foundation

// MARK: - Worker versions

nonisolated struct CloudflareWorkerVersionList: Decodable, Sendable {
    let items: [CloudflareWorkerVersion]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([CloudflareWorkerVersion].self, forKey: .items) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case items
    }
}

nonisolated struct CloudflareWorkerVersion: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let number: Int?
    let metadata: Metadata?

    struct Metadata: Decodable, Equatable, Sendable {
        let authorEmail: String?
        let authorID: String?
        let createdOn: String?
        let modifiedOn: String?
        let source: String?
        let hasPreview: Bool?

        var createdDate: Date? { CloudflareDateParser.date(from: createdOn) }

        private enum CodingKeys: String, CodingKey {
            case authorEmail = "author_email"
            case authorID = "author_id"
            case createdOn = "created_on"
            case modifiedOn = "modified_on"
            case source
            case hasPreview
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case number
        case metadata
    }
}

nonisolated struct CloudflareWorkerVersionDetail: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let number: Int?
    let metadata: CloudflareWorkerVersion.Metadata?
    let resources: Resources?
    let startupTimeMilliseconds: Double?

    struct Resources: Decodable, Equatable, Sendable {
        let bindings: [CloudflareJSONValue]
        let script: CloudflareJSONValue?
        let scriptRuntime: CloudflareJSONValue?

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            bindings = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .bindings) ?? []
            script = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .script)
            scriptRuntime = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .scriptRuntime)
        }

        private enum CodingKeys: String, CodingKey {
            case bindings
            case script
            case scriptRuntime = "script_runtime"
        }
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case number
        case metadata
        case resources
        case startupTimeMilliseconds = "startup_time_ms"
    }
}

// MARK: - Worker bindings, schedules and routing

nonisolated struct CloudflareWorkerSecretMetadata: Identifiable, Decodable, Equatable, Sendable {
    let name: String
    let type: String
    let format: String?
    let usages: [String]

    var id: String { name }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        type = try container.decodeIfPresent(String.self, forKey: .type) ?? "secret_text"
        format = try container.decodeIfPresent(String.self, forKey: .format)
        usages = try container.decodeIfPresent([String].self, forKey: .usages) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case name
        case type
        case format
        case usages
    }
}

nonisolated struct CloudflareWorkerScheduleList: Decodable, Sendable {
    let schedules: [CloudflareWorkerSchedule]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schedules = try container.decodeIfPresent([CloudflareWorkerSchedule].self, forKey: .schedules) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case schedules
    }
}

nonisolated struct CloudflareWorkerSchedule: Identifiable, Codable, Equatable, Sendable {
    let cron: String
    let createdOn: String?
    let modifiedOn: String?

    var id: String { cron }

    init(cron: String, createdOn: String? = nil, modifiedOn: String? = nil) {
        self.cron = cron
        self.createdOn = createdOn
        self.modifiedOn = modifiedOn
    }

    private enum CodingKeys: String, CodingKey {
        case cron
        case createdOn = "created_on"
        case modifiedOn = "modified_on"
    }
}

nonisolated struct CloudflareWorkerDomain: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let hostname: String
    let service: String
    let environment: String?
    let zoneID: String?
    let zoneName: String?
    let certificateID: String?

    private enum CodingKeys: String, CodingKey {
        case id
        case hostname
        case service
        case environment
        case zoneID = "zone_id"
        case zoneName = "zone_name"
        case certificateID = "cert_id"
    }
}

nonisolated struct CloudflareWorkerSubdomain: Decodable, Equatable, Sendable {
    let enabled: Bool
    let previewsEnabled: Bool

    private enum CodingKeys: String, CodingKey {
        case enabled
        case previewsEnabled = "previews_enabled"
    }
}

nonisolated struct CloudflareWorkersAccountSubdomain: Decodable, Equatable, Sendable {
    let subdomain: String
}

nonisolated struct CloudflareWorkerTail: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let expiresAt: String
    let url: String

    var expiresDate: Date? { CloudflareDateParser.date(from: expiresAt) }

    private enum CodingKeys: String, CodingKey {
        case id
        case expiresAt = "expires_at"
        case url
    }
}

// MARK: - Worker settings

nonisolated struct CloudflareWorkerScriptSettings: Decodable, Equatable, Sendable {
    let annotations: [String: String]
    let bindings: [CloudflareJSONValue]
    let cacheOptions: [String: CloudflareJSONValue]?
    let compatibilityDate: String?
    let compatibilityFlags: [String]
    let limits: [String: CloudflareJSONValue]?
    let migrations: CloudflareJSONValue?
    let observability: CloudflareWorkerObservability?
    let placement: CloudflareJSONValue?
    let tags: [String]
    let tailConsumers: [CloudflareJSONValue]
    let usageModel: String?

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        annotations = try container.decodeIfPresent([String: String].self, forKey: .annotations) ?? [:]
        bindings = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .bindings) ?? []
        cacheOptions = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .cacheOptions)
        compatibilityDate = try container.decodeIfPresent(String.self, forKey: .compatibilityDate)
        compatibilityFlags = try container.decodeIfPresent([String].self, forKey: .compatibilityFlags) ?? []
        limits = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .limits)
        migrations = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .migrations)
        observability = try container.decodeIfPresent(CloudflareWorkerObservability.self, forKey: .observability)
        placement = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .placement)
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        tailConsumers = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .tailConsumers) ?? []
        usageModel = try container.decodeIfPresent(String.self, forKey: .usageModel)
    }

    private enum CodingKeys: String, CodingKey {
        case annotations
        case bindings
        case cacheOptions = "cache_options"
        case compatibilityDate = "compatibility_date"
        case compatibilityFlags = "compatibility_flags"
        case limits
        case migrations
        case observability
        case placement
        case tags
        case tailConsumers = "tail_consumers"
        case usageModel = "usage_model"
    }
}

/// Script-level settings use the JSON `/script-settings` endpoint and remain
/// separate from version settings, whose update endpoint is multipart.
nonisolated struct CloudflareWorkerScriptLevelSettings: Decodable, Equatable, Sendable {
    let logpush: Bool?
    let observability: CloudflareWorkerObservability?
    let tags: [String]
    let tailConsumers: [CloudflareJSONValue]

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        logpush = try container.decodeIfPresent(Bool.self, forKey: .logpush)
        observability = try container.decodeIfPresent(CloudflareWorkerObservability.self, forKey: .observability)
        tags = try container.decodeIfPresent([String].self, forKey: .tags) ?? []
        tailConsumers = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .tailConsumers) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case logpush
        case observability
        case tags
        case tailConsumers = "tail_consumers"
    }
}

nonisolated struct CloudflareWorkerObservability: Codable, Equatable, Sendable {
    let enabled: Bool
    let headSamplingRate: Double?
    let logs: Logs?
    let traces: Traces?

    struct Logs: Codable, Equatable, Sendable {
        let enabled: Bool
        let invocationLogs: Bool
        let destinations: [String]?
        let headSamplingRate: Double?
        let persist: Bool?

        private enum CodingKeys: String, CodingKey {
            case enabled
            case invocationLogs = "invocation_logs"
            case destinations
            case headSamplingRate = "head_sampling_rate"
            case persist
        }
    }

    struct Traces: Codable, Equatable, Sendable {
        let enabled: Bool?
        let destinations: [String]?
        let headSamplingRate: Double?
        let persist: Bool?
        let propagationPolicy: String?

        private enum CodingKeys: String, CodingKey {
            case enabled
            case destinations
            case headSamplingRate = "head_sampling_rate"
            case persist
            case propagationPolicy = "propagation_policy"
        }
    }

    private enum CodingKeys: String, CodingKey {
        case enabled
        case headSamplingRate = "head_sampling_rate"
        case logs
        case traces
    }
}
