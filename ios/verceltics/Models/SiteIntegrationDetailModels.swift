import CoreFoundation
import Foundation

/// A lossless, Codable representation of provider JSON. Provider detail screens use this
/// alongside their typed sections so newly-added response fields are not silently discarded.
nonisolated enum SiteIntegrationJSONValue: Codable, Equatable, Sendable {
    case object([String: SiteIntegrationJSONValue])
    case array([SiteIntegrationJSONValue])
    case string(String)
    case integer(Int64)
    case unsignedInteger(UInt64)
    case decimal(Decimal)
    case number(Double)
    case bool(Bool)
    case null

    init(any value: Any) {
        switch value {
        case is NSNull:
            self = .null
        case let value as String:
            self = .string(value)
        case let value as NSNumber:
            if CFGetTypeID(value) == CFBooleanGetTypeID() {
                self = .bool(value.boolValue)
            } else if Self.signedIntegerObjCTypes.contains(String(cString: value.objCType)) {
                self = .integer(value.int64Value)
            } else if Self.unsignedIntegerObjCTypes.contains(String(cString: value.objCType)) {
                self = .unsignedInteger(value.uint64Value)
            } else if let decimal = Decimal(
                string: value.stringValue,
                locale: Locale(identifier: "en_US_POSIX")
            ) {
                self = .decimal(decimal)
            } else {
                self = .number(value.doubleValue)
            }
        case let value as [String: Any]:
            self = .object(value.mapValues(Self.init(any:)))
        case let value as [Any]:
            self = .array(value.map(Self.init(any:)))
        default:
            self = .string(String(describing: value))
        }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int64.self) {
            self = .integer(value)
        } else if let value = try? container.decode(UInt64.self) {
            self = .unsignedInteger(value)
        } else if let value = try? container.decode(Decimal.self) {
            self = .decimal(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: Self].self) {
            self = .object(value)
        } else if let value = try? container.decode([Self].self) {
            self = .array(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value."
            )
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .integer(let value): try container.encode(value)
        case .unsignedInteger(let value): try container.encode(value)
        case .decimal(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var objectValue: [String: Self]? {
        guard case .object(let value) = self else { return nil }
        return value
    }

    var arrayValue: [Self]? {
        guard case .array(let value) = self else { return nil }
        return value
    }

    var stringValue: String? {
        switch self {
        case .string(let value): value
        case .integer(let value): String(value)
        case .unsignedInteger(let value): String(value)
        case .decimal(let value): Self.decimalString(value)
        case .number(let value): value.formatted(.number.grouping(.never))
        case .bool(let value): String(value)
        case .object, .array, .null: nil
        }
    }

    var numberValue: Double? {
        switch self {
        case .integer(let value): Double(value)
        case .unsignedInteger(let value): Double(value)
        case .decimal(let value): NSDecimalNumber(decimal: value).doubleValue
        case .number(let value): value
        case .string(let value): Double(value)
        case .object, .array, .bool, .null: nil
        }
    }

    /// Exact numeric representation used for sorting and cross-case equality. Rendering and
    /// comparisons therefore do not round 64-bit identifiers through binary floating point.
    var decimalValue: Decimal? {
        switch self {
        case .integer(let value): Decimal(value)
        case .unsignedInteger(let value): Decimal(string: String(value))
        case .decimal(let value): value
        case .number(let value): Decimal(
            string: String(value),
            locale: Locale(identifier: "en_US_POSIX")
        )
        case .string(let value): Decimal(
            string: value,
            locale: Locale(identifier: "en_US_POSIX")
        )
        case .object, .array, .bool, .null: nil
        }
    }

    subscript(key: String) -> Self? { objectValue?[key] }

    /// Removes provider-returned credentials and write secrets while preserving every other
    /// response field. Sanitization is recursive because some APIs return request configuration
    /// inside nested monitor objects.
    func sanitizingSecrets() -> Self {
        switch self {
        case .object(let object):
            var sanitized: [String: Self] = [:]
            for (key, value) in object {
                if Self.isSensitiveKey(key) {
                    sanitized[key] = .string("[REDACTED]")
                } else {
                    sanitized[key] = value.sanitizingSecrets()
                }
            }
            return .object(sanitized)
        case .array(let values):
            return .array(values.map { $0.sanitizingSecrets() })
        case .string(let value):
            return .string(Self.sanitizedURLString(value))
        case .integer, .unsignedInteger, .decimal, .number, .bool, .null:
            return self
        }
    }

    static func == (lhs: Self, rhs: Self) -> Bool {
        switch (lhs, rhs) {
        case (.object(let left), .object(let right)): return left == right
        case (.array(let left), .array(let right)): return left == right
        case (.string(let left), .string(let right)): return left == right
        case (.integer(let left), .integer(let right)): return left == right
        case (.unsignedInteger(let left), .unsignedInteger(let right)): return left == right
        case (.decimal(let left), .decimal(let right)): return left == right
        case (.number(let left), .number(let right)): return left == right
        case (.bool(let left), .bool(let right)): return left == right
        case (.null, .null): return true
        case (.integer, .unsignedInteger), (.integer, .decimal), (.integer, .number),
             (.unsignedInteger, .integer), (.unsignedInteger, .decimal), (.unsignedInteger, .number),
             (.decimal, .integer), (.decimal, .unsignedInteger), (.decimal, .number),
             (.number, .integer), (.number, .unsignedInteger), (.number, .decimal):
            guard let left = lhs.decimalValue, let right = rhs.decimalValue else { return false }
            return left == right
        default: return false
        }
    }

    private static func normalizedKey(_ key: String) -> String {
        key.lowercased().unicodeScalars
            .filter { CharacterSet.alphanumerics.contains($0) }
            .map(String.init)
            .joined()
    }

    private static func isSensitiveKey(_ key: String) -> Bool {
        let normalized = normalizedKey(key)
        var candidates = [normalized]

        if normalized.hasPrefix("x"), normalized.count > 1 {
            candidates.append(String(normalized.dropFirst()))
        }
        for candidate in candidates {
            if sensitiveKeys.contains(candidate) { return true }
            if candidate.hasPrefix("header") {
                let stripped = String(candidate.dropFirst("header".count))
                if sensitiveKeys.contains(stripped) { return true }
            }
            if candidate.hasSuffix("header") {
                let stripped = String(candidate.dropLast("header".count))
                if sensitiveKeys.contains(stripped) { return true }
            }
            if sensitiveSuffixes.contains(where: candidate.hasSuffix) { return true }
        }
        return false
    }

    private static func sanitizedURLString(_ value: String) -> String {
        guard var components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              (scheme == "https" || scheme == "http"),
              components.host?.isEmpty == false else {
            return value
        }

        var changed = components.user != nil || components.password != nil
        components.user = nil
        components.password = nil
        if let queryItems = components.queryItems {
            components.queryItems = queryItems.map { item in
                guard item.value != nil, isSensitiveKey(item.name) else { return item }
                changed = true
                return URLQueryItem(name: item.name, value: "[REDACTED]")
            }
        }
        return changed ? (components.string ?? value) : value
    }

    private static func decimalString(_ value: Decimal) -> String {
        NSDecimalNumber(decimal: value).stringValue
    }

    private static let signedIntegerObjCTypes: Set<String> = ["c", "s", "i", "l", "q"]
    private static let unsignedIntegerObjCTypes: Set<String> = ["C", "S", "I", "L", "Q"]

    private static let sensitiveSuffixes = [
        "authorization", "apikey", "authtoken", "accesstoken", "refreshtoken",
        "bearertoken", "clientsecret", "apisecret", "secretkey", "privatekey",
        "signingkey", "encryptionkey", "password", "credential"
    ]

    private static let sensitiveKeys: Set<String> = [
        "authorization", "authentication", "authorizationheader", "proxyauthorization",
        "apikey", "accesskey", "secretkey",
        "privatekey", "signingkey", "encryptionkey", "accesskeyid", "secretaccesskey",
        "token", "sealedtoken", "accesstoken", "refreshtoken", "idtoken", "authtoken", "oauthtoken",
        "bearertoken", "clienttoken", "verificationtoken", "webhooktoken",
        "password", "httppassword", "proxypassword", "databasepassword",
        "secret", "clientsecret", "apisecret", "signingsecret", "webhooksecret",
        "credential", "credentials", "clientcredential", "clientcredentials",
        "httpusername", "requestheaders", "requestbody", "environmentvariables",
        "playwrightscript", "cookie", "cookies", "setcookie"
    ]
}

nonisolated struct SiteIntegrationDetailField: Codable, Equatable, Sendable, Identifiable {
    let key: String
    let label: String
    let value: SiteIntegrationJSONValue

    var id: String { key }
}

nonisolated struct SiteIntegrationDetailSection: Codable, Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let fields: [SiteIntegrationDetailField]
}

nonisolated struct SiteIntegrationDetailSeriesPoint: Codable, Equatable, Sendable, Identifiable {
    let x: String
    let values: [String: Double]

    var id: String { x }
}

nonisolated struct SiteIntegrationDetailSeries: Codable, Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let metricLabels: [String: String]
    let points: [SiteIntegrationDetailSeriesPoint]
}

nonisolated struct SiteIntegrationDetailTable: Codable, Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let columns: [String]
    let rows: [[String: SiteIntegrationJSONValue]]
    let nextCursor: String?

    init(
        id: String,
        title: String,
        columns: [String],
        rows: [[String: SiteIntegrationJSONValue]],
        nextCursor: String? = nil
    ) {
        self.id = id
        self.title = title
        self.columns = columns
        self.rows = rows
        self.nextCursor = nextCursor
    }
}

nonisolated struct SiteIntegrationDetailPayload: Codable, Equatable, Sendable {
    let provider: SiteIntegrationProvider
    let resourceID: String
    let title: String
    let sections: [SiteIntegrationDetailSection]
    let series: [SiteIntegrationDetailSeries]
    let tables: [SiteIntegrationDetailTable]
    let rawResponses: [String: SiteIntegrationJSONValue]
    let warnings: [String]
    let fetchedAt: Date

    init(
        provider: SiteIntegrationProvider,
        resourceID: String,
        title: String,
        sections: [SiteIntegrationDetailSection] = [],
        series: [SiteIntegrationDetailSeries] = [],
        tables: [SiteIntegrationDetailTable] = [],
        rawResponses: [String: SiteIntegrationJSONValue] = [:],
        warnings: [String] = [],
        fetchedAt: Date = .now
    ) {
        self.provider = provider
        self.resourceID = resourceID
        self.title = title
        self.sections = sections
        self.series = series
        self.tables = tables
        self.rawResponses = rawResponses
        self.warnings = warnings
        self.fetchedAt = fetchedAt
    }
}

nonisolated struct SiteIntegrationDetailRange: Codable, Equatable, Sendable {
    let start: Date
    let end: Date

    init(start: Date, end: Date) {
        self.start = min(start, end)
        self.end = max(start, end)
    }

    static func last30Days(endingAt end: Date = .now) -> Self {
        Self(start: end.addingTimeInterval(-29 * 86_400), end: end)
    }

    var startDate: String { Self.dateString(start) }
    var endDate: String { Self.dateString(end) }
    var startMilliseconds: Int64 { Int64(start.timeIntervalSince1970 * 1_000) }
    var endMilliseconds: Int64 { Int64(end.timeIntervalSince1970 * 1_000) }
    var startSeconds: Int64 { Int64(start.timeIntervalSince1970) }
    var endSeconds: Int64 { Int64(end.timeIntervalSince1970) }

    /// Serializes the calendar day the user selected rather than converting that instant to UTC.
    /// A midnight Date from a positive-offset locale (such as India) is still the previous day in
    /// UTC, so formatting in UTC would silently move the selected range back one day.
    nonisolated static func dateString(
        _ date: Date,
        calendar suppliedCalendar: Calendar = .autoupdatingCurrent
    ) -> String {
        let components = suppliedCalendar.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
    }
}
