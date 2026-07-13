import Foundation

// MARK: - Shared storage response values

nonisolated struct CloudflareStorageResultInfo: Decodable, Equatable, Sendable {
    let page: Int?
    let perPage: Int?
    let count: Int?
    let totalCount: Int?
    let cursor: String?

    enum CodingKeys: String, CodingKey {
        case page
        case perPage = "per_page"
        case count
        case totalCount = "total_count"
        case cursor
    }
}

// MARK: - D1

nonisolated struct CloudflareD1Database: Identifiable, Decodable, Equatable, Sendable {
    let uuid: String
    let name: String
    let version: String?
    let createdAt: String?
    let fileSize: Int64?
    let numberOfTables: Int?
    let jurisdiction: String?
    let readReplication: ReadReplication?

    var id: String { uuid }
    var createdDate: Date? { CloudflareDateParser.date(from: createdAt) }

    enum CodingKeys: String, CodingKey {
        case uuid
        case name
        case version
        case createdAt = "created_at"
        case fileSize = "file_size"
        case numberOfTables = "num_tables"
        case jurisdiction
        case readReplication = "read_replication"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        uuid = try container.decodeIfPresent(String.self, forKey: .uuid) ?? ""
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? "Unnamed database"
        version = try container.decodeIfPresent(String.self, forKey: .version)
        createdAt = try container.decodeIfPresent(String.self, forKey: .createdAt)
        fileSize = try container.decodeIfPresent(Int64.self, forKey: .fileSize)
        numberOfTables = try container.decodeIfPresent(Int.self, forKey: .numberOfTables)
        jurisdiction = try container.decodeIfPresent(String.self, forKey: .jurisdiction)
        readReplication = try container.decodeIfPresent(ReadReplication.self, forKey: .readReplication)
    }

    nonisolated struct ReadReplication: Decodable, Equatable, Sendable {
        let mode: String
    }
}

nonisolated struct CloudflareD1CreateInput: Encodable, Equatable, Sendable {
    let name: String
    let jurisdiction: String?
    let primaryLocationHint: String?
    let readReplicationMode: String?

    enum CodingKeys: String, CodingKey {
        case name
        case jurisdiction
        case primaryLocationHint = "primary_location_hint"
        case readReplication = "read_replication"
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encodeIfPresent(jurisdiction, forKey: .jurisdiction)
        try container.encodeIfPresent(primaryLocationHint, forKey: .primaryLocationHint)
        if let readReplicationMode {
            try container.encode(["mode": readReplicationMode], forKey: .readReplication)
        }
    }
}

nonisolated struct CloudflareD1QueryResult: Decodable, Equatable, Sendable {
    let success: Bool
    let rows: [[String: CloudflareJSONValue]]
    let meta: Meta?

    enum CodingKeys: String, CodingKey {
        case success
        case rows = "results"
        case meta
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        rows = try container.decodeIfPresent([[String: CloudflareJSONValue]].self, forKey: .rows) ?? []
        meta = try container.decodeIfPresent(Meta.self, forKey: .meta)
    }

    nonisolated struct Meta: Decodable, Equatable, Sendable {
        let changedDatabase: Bool?
        let changes: Int?
        let duration: Double?
        let lastRowID: Int64?
        let rowsRead: Int?
        let rowsWritten: Int?
        let servedByColo: String?
        let servedByPrimary: Bool?
        let servedByRegion: String?
        let sizeAfter: Int64?
        let timings: Timings?

        enum CodingKeys: String, CodingKey {
            case changedDatabase = "changed_db"
            case changes
            case duration
            case lastRowID = "last_row_id"
            case rowsRead = "rows_read"
            case rowsWritten = "rows_written"
            case servedByColo = "served_by_colo"
            case servedByPrimary = "served_by_primary"
            case servedByRegion = "served_by_region"
            case sizeAfter = "size_after"
            case timings
        }

        nonisolated struct Timings: Decodable, Equatable, Sendable {
            let sqlDurationMilliseconds: Double?

            enum CodingKeys: String, CodingKey {
                case sqlDurationMilliseconds = "sql_duration_ms"
            }
        }
    }
}

// MARK: - Workers KV

nonisolated struct CloudflareKVNamespace: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let title: String
    let supportsURLEncoding: Bool?

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case supportsURLEncoding = "supports_url_encoding"
    }
}

nonisolated struct CloudflareKVKey: Identifiable, Decodable, Equatable, Sendable {
    let name: String
    let expiration: Double?
    let metadata: CloudflareJSONValue?

    var id: String { name }
    var expirationDate: Date? { expiration.map(Date.init(timeIntervalSince1970:)) }
}

nonisolated struct CloudflareKVValue: Equatable, Sendable {
    let data: Data
    let contentType: String?
    let expiration: String?

    var utf8Text: String? { String(data: data, encoding: .utf8) }
    var base64Text: String { data.base64EncodedString(options: [.lineLength76Characters, .endLineWithLineFeed]) }
}

// MARK: - R2

nonisolated struct CloudflareR2Bucket: Identifiable, Decodable, Equatable, Sendable {
    let name: String
    let creationDate: String?
    let jurisdiction: String?
    let location: String?
    let storageClass: String?

    var id: String { name }
    var createdDate: Date? { CloudflareDateParser.date(from: creationDate) }

    enum CodingKeys: String, CodingKey {
        case name
        case creationDate = "creation_date"
        case jurisdiction
        case location
        case storageClass = "storage_class"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? "Unnamed bucket"
        creationDate = try container.decodeIfPresent(String.self, forKey: .creationDate)
        jurisdiction = try container.decodeIfPresent(String.self, forKey: .jurisdiction)
        location = try container.decodeIfPresent(String.self, forKey: .location)
        storageClass = try container.decodeIfPresent(String.self, forKey: .storageClass)
    }
}

nonisolated struct CloudflareR2CreateInput: Encodable, Equatable, Sendable {
    let name: String
    let jurisdiction: String?
    let locationHint: String?
    let storageClass: String?

    enum CodingKeys: String, CodingKey {
        case name
        case locationHint
        case storageClass
    }
}
