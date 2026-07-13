import Foundation

nonisolated struct CloudflareOpenAPICatalog: Codable, Sendable {
    let schemaVersion: Int
    let openAPIVersion: String
    let apiVersion: String
    let sourceCommit: String
    let sourceURL: String
    let operationCount: Int
    let operations: [CloudflareOpenAPIOperation]

    var tagCount: Int {
        Set(operations.flatMap(\.tags)).count
    }
}

nonisolated struct CloudflareOpenAPIOperation: Codable, Identifiable, Sendable, Equatable {
    let id: String
    let method: CloudflareHTTPMethod
    let path: String
    let summary: String
    let description: String
    let tags: [String]
    let deprecated: Bool
    let permissions: [String]
    let supportsGlobalKey: Bool
    let supportsAPIToken: Bool
    let supportsUserServiceKey: Bool
    let parameters: [CloudflareOpenAPIParameter]
    let contentTypes: [String]
    let requestBodyRequired: Bool
    let bodyTemplate: String
    let multipartFields: [CloudflareOpenAPIMultipartField]

    var primaryTag: String { tags.first ?? "Other" }
    var isMutation: Bool { method.isMutation }
    var isMultipart: Bool {
        contentTypes.contains { $0.localizedCaseInsensitiveContains("multipart/form-data") }
    }

    func matches(_ query: String) -> Bool {
        let terms = query
            .split(whereSeparator: \.isWhitespace)
            .map { $0.lowercased() }
        guard !terms.isEmpty else { return true }
        let haystack = ([summary, description, path, method.rawValue, id] + tags + permissions)
            .joined(separator: " ")
            .lowercased()
        return terms.allSatisfy(haystack.contains)
    }
}

nonisolated struct CloudflareOpenAPIParameter: Codable, Identifiable, Sendable, Equatable {
    let name: String
    let location: CloudflareOpenAPIParameterLocation
    let required: Bool
    let description: String
    let type: String?
    let format: String?
    let defaultValue: CloudflareJSONValue?
    let example: CloudflareJSONValue?
    let minimum: Double?
    let maximum: Double?
    let minLength: Int?
    let maxLength: Int?
    let pattern: String?
    let enumValues: [CloudflareJSONValue]?

    var id: String { "\(location.rawValue):\(name)" }

    private enum CodingKeys: String, CodingKey {
        case name, location, required, description, type, format, example
        case defaultValue = "default"
        case minimum, maximum, minLength, maxLength, pattern, enumValues
    }

    var suggestedValue: String {
        if let defaultValue { return defaultValue.catalogText }
        if let example { return example.catalogText }
        if let first = enumValues?.first { return first.catalogText }
        return ""
    }
}

nonisolated enum CloudflareOpenAPIParameterLocation: String, Codable, Sendable {
    case path
    case query
    case header
}

nonisolated struct CloudflareOpenAPIMultipartField: Codable, Identifiable, Sendable, Equatable {
    let name: String
    let required: Bool
    let isFile: Bool
    let type: String?
    let format: String?
    let description: String?
    let defaultValue: CloudflareJSONValue?
    let example: CloudflareJSONValue?
    let enumValues: [CloudflareJSONValue]?

    var id: String { name }

    private enum CodingKeys: String, CodingKey {
        case name, required, isFile, type, format, description, example, enumValues
        case defaultValue = "default"
    }

    var suggestedValue: String {
        if let defaultValue { return defaultValue.catalogText }
        if let example { return example.catalogText }
        if let first = enumValues?.first { return first.catalogText }
        return ""
    }
}

nonisolated extension CloudflareJSONValue {
    var catalogText: String {
        switch self {
        case .string(let value): value
        case .int(let value): String(value)
        case .double(let value): String(value)
        case .bool(let value): value ? "true" : "false"
        case .null: ""
        case .object, .array:
            (try? String(data: JSONEncoder().encode(self), encoding: .utf8)) ?? ""
        }
    }
}

actor CloudflareOpenAPICatalogStore {
    static let shared = CloudflareOpenAPICatalogStore()

    private var cachedCatalog: CloudflareOpenAPICatalog?

    func load() throws -> CloudflareOpenAPICatalog {
        if let cachedCatalog { return cachedCatalog }
        guard let url = Bundle.main.url(forResource: "CloudflareAPICatalog", withExtension: "json") else {
            throw CloudflareAPIError.decoding("The bundled Cloudflare API catalog is missing.")
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        let catalog = try JSONDecoder().decode(CloudflareOpenAPICatalog.self, from: data)
        cachedCatalog = catalog
        return catalog
    }
}
