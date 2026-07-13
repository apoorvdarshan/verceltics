import Foundation

// MARK: - Shared operations envelopes

nonisolated struct CloudflareOperationsEnvelope<Result: Decodable & Sendable>: Decodable, Sendable {
    let success: Bool
    let result: Result?
    let errors: [CloudflareOperationsIssue]
    let messages: [CloudflareOperationsIssue]
    let resultInfo: CloudflareOperationsResultInfo?

    enum CodingKeys: String, CodingKey {
        case success
        case result
        case errors
        case messages
        case resultInfo = "result_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        result = try container.decodeIfPresent(Result.self, forKey: .result)
        errors = try container.decodeIfPresent([CloudflareOperationsIssue].self, forKey: .errors) ?? []
        messages = try container.decodeIfPresent([CloudflareOperationsIssue].self, forKey: .messages) ?? []
        resultInfo = try container.decodeIfPresent(CloudflareOperationsResultInfo.self, forKey: .resultInfo)
    }
}

nonisolated struct CloudflareOperationsIssue: Decodable, Equatable, Sendable {
    let code: Int?
    let message: String

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CloudflareOperationsCodingKey.self)
        code = try? container.decodeIfPresent(Int.self, forKey: CloudflareOperationsCodingKey("code"))
        message = (try? container.decodeIfPresent(String.self, forKey: CloudflareOperationsCodingKey("message")))
            ?? "Cloudflare rejected the request."
    }
}

nonisolated struct CloudflareOperationsResultInfo: Decodable, Equatable, Sendable {
    let page: Int?
    let totalPages: Int?
    let cursor: String?

    enum CodingKeys: String, CodingKey {
        case page
        case totalPages = "total_pages"
        case cursor
    }
}

nonisolated struct CloudflareOperationsCodingKey: CodingKey, Sendable {
    let stringValue: String
    let intValue: Int?

    init(_ stringValue: String) {
        self.stringValue = stringValue
        intValue = nil
    }

    init?(stringValue: String) {
        self.init(stringValue)
    }

    init?(intValue: Int) {
        stringValue = String(intValue)
        self.intValue = intValue
    }
}

// MARK: - Account access

nonisolated struct CloudflareAccountMember: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let email: String?
    let status: String?
    let user: User?
    let roles: [CloudflareAccountRole]
    let policies: [Policy]

    var displayName: String {
        let name = [user?.firstName, user?.lastName]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return name.isEmpty ? resolvedEmail : name
    }

    var resolvedEmail: String { user?.email ?? email ?? "Email unavailable" }

    enum CodingKeys: String, CodingKey {
        case id, email, status, user, roles, policies
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? "unknown-member"
        email = try container.decodeIfPresent(String.self, forKey: .email)
        status = try container.decodeIfPresent(String.self, forKey: .status)
        user = try container.decodeIfPresent(User.self, forKey: .user)
        roles = try container.decodeIfPresent([CloudflareAccountRole].self, forKey: .roles) ?? []
        policies = try container.decodeIfPresent([Policy].self, forKey: .policies) ?? []
    }

    struct User: Decodable, Equatable, Sendable {
        let id: String?
        let email: String?
        let firstName: String?
        let lastName: String?
        let twoFactorAuthenticationEnabled: Bool?

        enum CodingKeys: String, CodingKey {
            case id, email
            case firstName = "first_name"
            case lastName = "last_name"
            case twoFactorAuthenticationEnabled = "two_factor_authentication_enabled"
        }
    }

    struct Policy: Identifiable, Decodable, Equatable, Sendable {
        let id: String
        let access: String?
        let permissionGroups: [CloudflareJSONValue]
        let resourceGroups: [CloudflareJSONValue]

        enum CodingKeys: String, CodingKey {
            case id, access
            case permissionGroups = "permission_groups"
            case resourceGroups = "resource_groups"
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decodeIfPresent(String.self, forKey: .id) ?? "unknown-policy"
            access = try container.decodeIfPresent(String.self, forKey: .access)
            permissionGroups = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .permissionGroups) ?? []
            resourceGroups = try container.decodeIfPresent([CloudflareJSONValue].self, forKey: .resourceGroups) ?? []
        }
    }
}

nonisolated struct CloudflareAccountRole: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let description: String?
    let permissions: [String: PermissionGrant]

    enum CodingKeys: String, CodingKey {
        case id, name, description, permissions
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(String.self, forKey: .id) ?? "unknown-role"
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? "Unnamed role"
        description = try container.decodeIfPresent(String.self, forKey: .description)
        permissions = try container.decodeIfPresent([String: PermissionGrant].self, forKey: .permissions) ?? [:]
    }

    struct PermissionGrant: Decodable, Equatable, Sendable {
        let read: Bool?
        let write: Bool?
    }
}

nonisolated struct CloudflareAccountAuditEvent: Identifiable, Decodable, Equatable, Sendable {
    let eventID: String?
    let account: Reference?
    let action: Action?
    let actor: Actor?
    let raw: RawRequest?
    let resource: Resource?
    let zone: Reference?

    var id: String {
        eventID ?? [action?.time, raw?.method, raw?.uri].compactMap { $0 }.joined(separator: "|")
    }

    var date: Date? { CloudflareDateParser.date(from: action?.time) }

    enum CodingKeys: String, CodingKey {
        case eventID = "id"
        case account, action, actor, raw, resource, zone
    }

    struct Reference: Decodable, Equatable, Sendable {
        let id: String?
        let name: String?
    }

    struct Action: Decodable, Equatable, Sendable {
        let description: String?
        let result: String?
        let time: String?
        let type: String?
    }

    struct Actor: Decodable, Equatable, Sendable {
        let context: String?
        let email: String?
        let id: String?
        let ipAddress: String?
        let tokenID: String?
        let tokenName: String?
        let type: String?

        enum CodingKeys: String, CodingKey {
            case context, email, id, type
            case ipAddress = "ip_address"
            case tokenID = "token_id"
            case tokenName = "token_name"
        }
    }

    struct RawRequest: Decodable, Equatable, Sendable {
        let cfRayID: String?
        let method: String?
        let statusCode: Int?
        let uri: String?
        let userAgent: String?

        enum CodingKeys: String, CodingKey {
            case cfRayID = "cf_ray_id"
            case method
            case statusCode = "status_code"
            case uri
            case userAgent = "user_agent"
        }
    }

    struct Resource: Decodable, Equatable, Sendable {
        let id: String?
        let product: String?
        let type: String?
        let scope: CloudflareJSONValue?
        let request: CloudflareJSONValue?
        let response: CloudflareJSONValue?
    }
}

// MARK: - Zone operations

nonisolated struct CloudflareZoneActivationResult: Decodable, Equatable, Sendable {
    let id: String?
}

nonisolated struct CloudflareDNSSECStatus: Decodable, Equatable, Sendable {
    let status: String?
    let flags: Int?
    let algorithm: String?
    let keyType: String?
    let digestType: String?
    let digestAlgorithm: String?
    let digest: String?
    let ds: String?
    let keyTag: Int?
    let publicKey: String?
    let modifiedOn: String?
    let multiSigner: Bool?
    let presigned: Bool?
    let useNSEC3: Bool?

    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }
    var isActive: Bool { status?.lowercased() == "active" }

    enum CodingKeys: String, CodingKey {
        case status, flags, algorithm, digest, ds
        case keyType = "key_type"
        case digestType = "digest_type"
        case digestAlgorithm = "digest_algorithm"
        case keyTag = "key_tag"
        case publicKey = "public_key"
        case modifiedOn = "modified_on"
        case multiSigner = "dnssec_multi_signer"
        case presigned = "dnssec_presigned"
        case useNSEC3 = "dnssec_use_nsec3"
    }
}

nonisolated struct CloudflareZoneSetting: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let value: CloudflareJSONValue
    let editable: Bool
    let modifiedOn: String?

    var modifiedDate: Date? { CloudflareDateParser.date(from: modifiedOn) }

    enum CodingKeys: String, CodingKey {
        case id, value, editable
        case modifiedOn = "modified_on"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        value = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .value) ?? .null
        editable = try container.decodeIfPresent(Bool.self, forKey: .editable) ?? false
        modifiedOn = try container.decodeIfPresent(String.self, forKey: .modifiedOn)
    }
}

nonisolated struct CloudflareZoneDNSSettings: Decodable, Equatable, Sendable {
    let flattenAllCNAMEs: Bool?
    let foundationDNS: Bool?
    let multiProvider: Bool?
    let secondaryOverrides: Bool?
    let nameservers: Nameservers?
    let nameServerTTL: Double?
    let zoneMode: String?
    let internalDNS: InternalDNS?
    let soa: SOA?

    enum CodingKeys: String, CodingKey {
        case flattenAllCNAMEs = "flatten_all_cnames"
        case foundationDNS = "foundation_dns"
        case multiProvider = "multi_provider"
        case secondaryOverrides = "secondary_overrides"
        case nameservers
        case nameServerTTL = "ns_ttl"
        case zoneMode = "zone_mode"
        case internalDNS = "internal_dns"
        case soa
    }

    struct Nameservers: Decodable, Equatable, Sendable {
        let type: String?
        let set: Int?

        enum CodingKeys: String, CodingKey {
            case type
            case set = "ns_set"
        }
    }

    struct InternalDNS: Decodable, Equatable, Sendable {
        let referenceZoneID: String?

        enum CodingKeys: String, CodingKey {
            case referenceZoneID = "reference_zone_id"
        }
    }

    struct SOA: Decodable, Equatable, Sendable {
        let primaryNameServer: String?
        let responsibleName: String?
        let refresh: Double?
        let retry: Double?
        let expire: Double?
        let minimumTTL: Double?
        let ttl: Double?

        enum CodingKeys: String, CodingKey {
            case primaryNameServer = "mname"
            case responsibleName = "rname"
            case refresh, retry, expire, ttl
            case minimumTTL = "min_ttl"
        }
    }
}

nonisolated struct CloudflareDNSUsage: Decodable, Equatable, Sendable {
    let recordUsage: Int
    let recordQuota: Int?

    enum CodingKeys: String, CodingKey {
        case recordUsage = "record_usage"
        case recordQuota = "record_quota"
    }

    var available: Int? {
        guard let recordQuota else { return nil }
        return max(0, recordQuota - recordUsage)
    }
}

nonisolated struct CloudflareDNSAnalyticsReport: Decodable, Equatable, Sendable {
    let rows: Int
    let totals: [String: CloudflareJSONValue]
    let minimums: [String: CloudflareJSONValue]
    let maximums: [String: CloudflareJSONValue]
    let dataLag: Double
    let query: CloudflareJSONValue?

    enum CodingKeys: String, CodingKey {
        case rows, totals, query
        case minimums = "min"
        case maximums = "max"
        case dataLag = "data_lag"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rows = try container.decodeIfPresent(Int.self, forKey: .rows) ?? 0
        totals = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .totals) ?? [:]
        minimums = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .minimums) ?? [:]
        maximums = try container.decodeIfPresent([String: CloudflareJSONValue].self, forKey: .maximums) ?? [:]
        dataLag = try container.decodeIfPresent(Double.self, forKey: .dataLag) ?? 0
        query = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .query)
    }

    func total(named name: String) -> Double? {
        totals[name]?.operationsDoubleValue
    }
}

extension CloudflareJSONValue {
    nonisolated var operationsDisplayText: String {
        switch self {
        case .string(let value):
            return value
        case .int(let value):
            return value.formatted()
        case .double(let value):
            return value.formatted(.number.precision(.fractionLength(0...2)))
        case .bool(let value):
            return value ? "On" : "Off"
        case .object(let value):
            return value
                .sorted { $0.key < $1.key }
                .map { "\($0.key.replacingOccurrences(of: "_", with: " ")): \($0.value.operationsDisplayText)" }
                .joined(separator: ", ")
        case .array(let value):
            return value.map(\.operationsDisplayText).joined(separator: ", ")
        case .null:
            return "Not set"
        }
    }

    nonisolated var operationsDoubleValue: Double? {
        switch self {
        case .int(let value): return Double(value)
        case .double(let value): return value
        case .string(let value): return Double(value)
        default: return nil
        }
    }
}
