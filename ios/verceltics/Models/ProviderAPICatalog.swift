import Foundation

nonisolated struct ProviderAPICatalogBundle: Codable, Sendable {
    let schemaVersion: Int
    let generatedAt: String
    let providers: [ProviderAPICatalog]
}

nonisolated struct ProviderAPICatalog: Codable, Identifiable, Sendable {
    let id: String
    let title: String
    let apiVersion: String
    let sourceURL: String
    let sourceDescription: String
    let operations: [ProviderAPIOperation]

    var tagCount: Int { Set(operations.flatMap(\.tags)).count }
}

nonisolated struct ProviderAPIOperation: Codable, Identifiable, Sendable, Equatable {
    let id: String
    let method: String
    let path: String
    let summary: String
    let description: String
    let tags: [String]
    let deprecated: Bool
    let parameters: [ProviderAPIParameter]
    let contentTypes: [String]
    let requestBodyRequired: Bool
    let bodyTemplate: String
    let multipartFields: [CloudflareOpenAPIMultipartField]

    var primaryTag: String { tags.first ?? "Other" }
    var isMutation: Bool {
        let value = (path + " " + id + " " + primaryTag).lowercased()
        if value.contains("graphql") || value.contains("queries") || value.contains("mutations") {
            return value.contains("mutation") || value.contains("mutations")
        }
        if value.contains("api.porkbun") || value.contains("porkbun:") {
            let reads = ["/ping", "/pricing/get", "/domain/listall", "/domain/checkdomain", "/domain/getns", "/domain/geturlforwarding", "/dns/retrieve", "/ssl/retrieve"]
            if reads.contains(where: value.contains) { return false }
        }
        if method.uppercased() == "GET" {
            let writes = [
                ".create", ".set", ".renew", ".reactivate", ".delete", ".update", ".change", ".enable", ".disable", ".activate", ".reissue", ".resend", ".purchase", ".revoke", ".edit", ".reset",
                "command=register", "command=restore", "command=renew", "command=transfer", "command=set_", "command=create_", "command=edit_", "command=delete", "command=clear_", "command=push_", "command=buy_", "command=make_", "command=place_",
                "/api/register", "/api/renew", "/api/transfer", "/api/change", "/api/contactadd", "/api/contactupdate", "/api/domainupdate", "/api/add", "/api/remove", "/api/dnsadd", "/api/dnsupdate", "/api/dnsdelete", "/api/domainforward", "/api/modify", "/api/delete", "/api/portfolioadd", "/api/portfoliodelete"
            ]
            return writes.contains(where: value.contains)
        }
        return !["HEAD", "OPTIONS"].contains(method.uppercased())
    }

    func matches(_ query: String) -> Bool {
        let terms = query.split(whereSeparator: \.isWhitespace).map { $0.lowercased() }
        guard !terms.isEmpty else { return true }
        let haystack = ([id, method, path, summary, description] + tags).joined(separator: " ").lowercased()
        return terms.allSatisfy(haystack.contains)
    }
}

nonisolated struct ProviderAPIParameter: Codable, Identifiable, Sendable, Equatable {
    let name: String
    let location: ProviderAPIParameterLocation
    let required: Bool
    let description: String
    let type: String
    let example: String
    let enumValues: [String]

    var id: String { "\(location.rawValue):\(name)" }
}

nonisolated enum ProviderAPIParameterLocation: String, Codable, Sendable {
    case path
    case query
    case header
}

nonisolated struct ProviderAPIRequestPreset: Sendable, Equatable {
    let title: String
    let method: String
    let path: String
    let body: String
    let headers: [String: String]
    let contentType: String?
    let multipartFields: [CloudflareOpenAPIMultipartField]
}

nonisolated enum ProviderAPIRequestEncoding {
    private static let unreserved = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".utf8)
    private static let reservedPath = Set(":/@!$&'()*+,;=".utf8)

    /// RFC 3986 path-parameter encoding. Reserved expansion (`{+value}`) keeps
    /// path separators and other reserved path characters but never `?` or `#`.
    static func pathParameter(_ value: String, allowReserved: Bool) -> String {
        percentEncode(value, additionallyAllowed: allowReserved ? reservedPath : [])
    }

    /// AWS Signature Version 4 requires every byte except RFC 3986 unreserved
    /// characters to be percent encoded using uppercase hexadecimal digits.
    static func awsQueryComponent(_ value: String) -> String {
        percentEncode(value, additionallyAllowed: [])
    }

    private static func percentEncode(_ value: String, additionallyAllowed: Set<UInt8>) -> String {
        value.utf8.map { byte in
            if unreserved.contains(byte) || additionallyAllowed.contains(byte) {
                return String(UnicodeScalar(byte))
            }
            return String(format: "%%%02X", byte)
        }.joined()
    }
}

/// Builds safe, editable starter requests from Railway's live GraphQL schema.
/// Required inputs receive type-aware placeholders, optional arguments are
/// omitted, and response selections include useful fields instead of returning
/// only `__typename`.
nonisolated enum RailwayGraphQLTemplateBuilder {
    static func operations(schemaData: Data) throws -> [ProviderAPIOperation] {
        guard let schema = try JSONSerialization.jsonObject(with: schemaData) as? [String: Any],
              let types = schema["types"] as? [[String: Any]] else {
            throw HostingProviderAPIError.decoding("Railway did not return a usable GraphQL schema.")
        }
        let typesByName = Dictionary(uniqueKeysWithValues: types.compactMap { type -> (String, [String: Any])? in
            guard let name = type["name"] as? String else { return nil }
            return (name, type)
        })
        let queryName = (schema["queryType"] as? [String: Any])?["name"] as? String
        let mutationName = (schema["mutationType"] as? [String: Any])?["name"] as? String
        var operations: [ProviderAPIOperation] = []

        for rootType in types {
            guard let rootTypeName = rootType["name"] as? String,
                  rootTypeName == queryName || rootTypeName == mutationName,
                  let fields = rootType["fields"] as? [[String: Any]] else { continue }
            let isMutation = rootTypeName == mutationName

            for field in fields {
                guard let fieldName = field["name"] as? String else { continue }
                let arguments = field["args"] as? [[String: Any]] ?? []
                let requiredArguments = arguments.filter(isRequired)
                var declarations: [String] = []
                var calls: [String] = []
                var variables: [String: Any] = [:]

                for argument in requiredArguments {
                    guard let name = argument["name"] as? String,
                          let type = argument["type"] as? [String: Any] else { continue }
                    declarations.append("$\(name): \(graphQLTypeName(type))")
                    calls.append("\(name): $\(name)")
                    variables[name] = placeholder(for: type, types: typesByName, visited: [])
                }

                let declaration = declarations.isEmpty ? "" : "(\(declarations.joined(separator: ", ")))"
                let call = calls.isEmpty ? "" : "(\(calls.joined(separator: ", ")))"
                let returnType = field["type"] as? [String: Any] ?? [:]
                let responseSelection = selection(
                    for: returnType,
                    types: typesByName,
                    depth: 0,
                    visited: []
                )
                let operationKind = isMutation ? "mutation" : "query"
                let document = "\(operationKind) Verceltics_\(fieldName)\(declaration) { \(fieldName)\(call)\(responseSelection) }"
                let request = try JSONSerialization.data(
                    withJSONObject: ["query": document, "variables": variables],
                    options: [.prettyPrinted, .sortedKeys]
                )

                var description = (field["description"] as? String)
                    ?? (field["deprecationReason"] as? String)
                    ?? ""
                let optionalArguments = arguments.filter { !isRequired($0) }.compactMap { argument -> String? in
                    guard let name = argument["name"] as? String,
                          let type = argument["type"] as? [String: Any] else { return nil }
                    return "\(name): \(graphQLTypeName(type))"
                }
                if !optionalArguments.isEmpty {
                    if !description.isEmpty { description += "\n\n" }
                    description += "Optional arguments omitted from the safe starter request: \(optionalArguments.joined(separator: ", "))."
                }
                if String(data: request, encoding: .utf8)?.contains("REPLACE_ME") == true {
                    if !description.isEmpty { description += "\n\n" }
                    description += "Replace every REPLACE_ME value before sending."
                }

                operations.append(ProviderAPIOperation(
                    id: "railway.\(rootTypeName).\(fieldName)",
                    method: "POST",
                    path: "/graphql/v2",
                    summary: fieldName,
                    description: description,
                    tags: [isMutation ? "Mutations" : "Queries"],
                    deprecated: field["isDeprecated"] as? Bool ?? false,
                    parameters: [],
                    contentTypes: ["application/json"],
                    requestBodyRequired: true,
                    bodyTemplate: String(data: request, encoding: .utf8) ?? "{}",
                    multipartFields: []
                ))
            }
        }
        return operations.sorted { ($0.primaryTag, $0.summary) < ($1.primaryTag, $1.summary) }
    }

    private static func graphQLTypeName(_ value: [String: Any]) -> String {
        let kind = value["kind"] as? String ?? ""
        if kind == "NON_NULL", let nested = value["ofType"] as? [String: Any] {
            return graphQLTypeName(nested) + "!"
        }
        if kind == "LIST", let nested = value["ofType"] as? [String: Any] {
            return "[\(graphQLTypeName(nested))]"
        }
        return value["name"] as? String ?? "String"
    }

    private static func isRequired(_ value: [String: Any]) -> Bool {
        guard let type = value["type"] as? [String: Any], type["kind"] as? String == "NON_NULL" else {
            return false
        }
        let defaultValue = value["defaultValue"]
        return defaultValue == nil || defaultValue is NSNull
    }

    private static func placeholder(
        for type: [String: Any],
        types: [String: [String: Any]],
        visited: Set<String>
    ) -> Any {
        let kind = type["kind"] as? String ?? ""
        if kind == "NON_NULL", let nested = type["ofType"] as? [String: Any] {
            return placeholder(for: nested, types: types, visited: visited)
        }
        if kind == "LIST" { return [Any]() }

        let name = type["name"] as? String ?? "String"
        let definition = types[name]
        let resolvedKind = definition?["kind"] as? String ?? kind
        if resolvedKind == "ENUM" {
            return (definition?["enumValues"] as? [[String: Any]])?.first?["name"] as? String
                ?? "REPLACE_ME"
        }
        if resolvedKind == "INPUT_OBJECT" {
            guard !visited.contains(name) else { return [String: Any]() }
            var nextVisited = visited
            nextVisited.insert(name)
            let inputFields = definition?["inputFields"] as? [[String: Any]] ?? []
            return inputFields.filter(isRequired).reduce(into: [String: Any]()) { result, field in
                guard let fieldName = field["name"] as? String,
                      let fieldType = field["type"] as? [String: Any] else { return }
                result[fieldName] = placeholder(for: fieldType, types: types, visited: nextVisited)
            }
        }

        switch name.lowercased() {
        case "int", "bigint", "long", "positiveint", "nonnegativeint": return 1
        case "float", "decimal": return 1.0
        case "boolean": return false
        case "json", "jsonobject": return [String: Any]()
        case "datetime", "timestamp": return "2026-01-01T00:00:00.000Z"
        case "date": return "2026-01-01"
        case "url", "uri": return "https://example.com"
        case "email": return "user@example.com"
        default: return "REPLACE_ME"
        }
    }

    private static func selection(
        for type: [String: Any],
        types: [String: [String: Any]],
        depth: Int,
        visited: Set<String>
    ) -> String {
        guard let name = namedTypeName(type), let definition = types[name] else { return "" }
        let kind = definition["kind"] as? String ?? ""
        if kind == "SCALAR" || kind == "ENUM" { return "" }
        guard depth < 4, !visited.contains(name) else { return " { __typename }" }

        var nextVisited = visited
        nextVisited.insert(name)
        if kind == "UNION" {
            let fragments = (definition["possibleTypes"] as? [[String: Any]] ?? [])
                .compactMap { $0["name"] as? String }
                .prefix(2)
                .compactMap { possibleType -> String? in
                    guard let possibleDefinition = types[possibleType] else { return nil }
                    let nested = selection(
                        for: ["kind": possibleDefinition["kind"] as? String ?? "OBJECT", "name": possibleType],
                        types: types,
                        depth: depth + 1,
                        visited: nextVisited
                    )
                    return "... on \(possibleType)\(nested)"
                }
            return fragments.isEmpty
                ? " { __typename }"
                : " { __typename \(fragments.joined(separator: " ")) }"
        }

        let fields = (definition["fields"] as? [[String: Any]] ?? []).filter { field in
            guard field["isDeprecated"] as? Bool != true else { return false }
            let arguments = field["args"] as? [[String: Any]] ?? []
            return !arguments.contains(where: isRequired)
        }
        let sortedFields = fields.sorted { outputPriority($0) < outputPriority($1) }
        var selections: [String] = []

        for field in sortedFields where isLeaf(field["type"] as? [String: Any] ?? [:], types: types) {
            guard let fieldName = field["name"] as? String else { continue }
            selections.append(fieldName)
            if selections.count == 8 { break }
        }

        var nestedCount = 0
        for field in sortedFields where !isLeaf(field["type"] as? [String: Any] ?? [:], types: types) {
            guard nestedCount < 3,
                  let fieldName = field["name"] as? String,
                  let fieldType = field["type"] as? [String: Any] else { continue }
            let nested = selection(for: fieldType, types: types, depth: depth + 1, visited: nextVisited)
            guard !nested.isEmpty else { continue }
            selections.append("\(fieldName)\(nested)")
            nestedCount += 1
        }
        return selections.isEmpty ? " { __typename }" : " { \(selections.joined(separator: " ")) }"
    }

    private static func namedTypeName(_ value: [String: Any]) -> String? {
        if let name = value["name"] as? String { return name }
        guard let nested = value["ofType"] as? [String: Any] else { return nil }
        return namedTypeName(nested)
    }

    private static func isLeaf(_ value: [String: Any], types: [String: [String: Any]]) -> Bool {
        guard let name = namedTypeName(value) else { return true }
        let kind = types[name]?["kind"] as? String ?? value["kind"] as? String
        return kind == "SCALAR" || kind == "ENUM"
    }

    private static func outputPriority(_ field: [String: Any]) -> String {
        let name = field["name"] as? String ?? ""
        let preferred = [
            "id", "name", "status", "success", "message", "url", "email",
            "createdAt", "updatedAt", "totalCount", "hasNextPage", "endCursor",
            "edges", "nodes", "node", "pageInfo", "data", "results",
        ]
        let rank = preferred.firstIndex(of: name) ?? preferred.count
        return String(format: "%03d-%@", rank, name.lowercased())
    }
}

actor ProviderAPICatalogStore {
    private struct RailwayCacheEntry {
        let catalog: ProviderAPICatalog
        let updatedAt: Date
    }

    static let shared = ProviderAPICatalogStore()

    private var cached: ProviderAPICatalogBundle?
    private var railwayCache: [String: RailwayCacheEntry] = [:]
    private var railwayLoads: [String: Task<ProviderAPICatalog, Error>] = [:]
    private let railwayCacheLifetime: TimeInterval = 300

    func catalog(id: String) throws -> ProviderAPICatalog {
        let bundle = try load()
        guard let catalog = bundle.providers.first(where: { $0.id == id }) else {
            throw ProviderAPICatalogError.missingProvider(id)
        }
        return catalog
    }

    func railwayCatalog(account: VercelAccount, forceRefresh: Bool = false) async throws -> ProviderAPICatalog {
        let cacheKey = CredentialCacheScope.hostingAccount(account)
        let cachedEntry = railwayCache[cacheKey]
        if !forceRefresh,
           let cachedEntry,
           Date.now.timeIntervalSince(cachedEntry.updatedAt) < railwayCacheLifetime {
            return cachedEntry.catalog
        }

        if let existingLoad = railwayLoads[cacheKey] {
            return try await existingLoad.value
        }

        let load = Task { try await liveRailwayCatalog(account: account) }
        railwayLoads[cacheKey] = load
        do {
            let discovered = try await load.value
            railwayLoads[cacheKey] = nil
            railwayCache[cacheKey] = RailwayCacheEntry(catalog: discovered, updatedAt: .now)
            return discovered
        } catch is CancellationError {
            railwayLoads[cacheKey] = nil
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled && Task.isCancelled {
            railwayLoads[cacheKey] = nil
            throw CancellationError()
        } catch {
            railwayLoads[cacheKey] = nil
            if let cachedEntry { return cachedEntry.catalog }
            let fallback = try catalog(id: "hosting.railway")
            let value = ProviderAPICatalog(
                id: fallback.id,
                title: fallback.title,
                apiVersion: "\(fallback.apiVersion) · Bundled fallback",
                sourceURL: fallback.sourceURL,
                sourceDescription: "Live schema discovery was unavailable. The bundled manual GraphQL request remains usable. \(error.localizedDescription)",
                operations: fallback.operations
            )
            railwayCache[cacheKey] = RailwayCacheEntry(catalog: value, updatedAt: .now)
            return value
        }
    }

    private func liveRailwayCatalog(account: VercelAccount) async throws -> ProviderAPICatalog {
        let query = """
        query VercelticsIntrospection {
          __schema {
            queryType { name }
            mutationType { name }
            types {
              kind name
              enumValues(includeDeprecated: true) { name }
              possibleTypes { name }
              inputFields {
                name description defaultValue
                type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
              }
              fields(includeDeprecated: true) {
                name description isDeprecated deprecationReason
                args {
                  name description defaultValue
                  type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
                }
                type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
              }
            }
          }
        }
        """
        let body = try JSONSerialization.data(withJSONObject: ["query": query])
        let response = try await HostingProviderAPI(account: account).rawRequest(
            method: "POST",
            path: "/graphql/v2",
            body: String(data: body, encoding: .utf8)
        )
        guard let data = response.body.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw HostingProviderAPIError.decoding("Railway did not return its GraphQL schema.")
        }
        if let errors = root["errors"] as? [[String: Any]], let message = errors.first?["message"] as? String {
            throw HostingProviderAPIError.decoding(message)
        }
        guard let payload = root["data"] as? [String: Any],
              let schema = payload["__schema"] as? [String: Any] else {
            throw HostingProviderAPIError.decoding("Railway did not return its GraphQL schema.")
        }
        let schemaData = try JSONSerialization.data(withJSONObject: schema)
        let operations = try RailwayGraphQLTemplateBuilder.operations(schemaData: schemaData)
        return ProviderAPICatalog(
            id: "hosting.railway",
            title: "Railway",
            apiVersion: "Live GraphQL v2",
            sourceURL: "https://docs.railway.com/integrations/api",
            sourceDescription: "Every query and mutation discovered live from the authenticated Railway GraphQL schema",
            operations: operations
        )
    }

    private func load() throws -> ProviderAPICatalogBundle {
        if let cached { return cached }
        guard let url = Bundle.main.url(forResource: "ProviderAPICatalog", withExtension: "json") else {
            throw ProviderAPICatalogError.missingBundle
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe])
        let value = try JSONDecoder().decode(ProviderAPICatalogBundle.self, from: data)
        cached = value
        return value
    }
}

enum ProviderAPICatalogError: LocalizedError {
    case missingBundle
    case missingProvider(String)

    var errorDescription: String? {
        switch self {
        case .missingBundle: "The complete provider API catalog is missing from this build."
        case .missingProvider(let id): "No API definition is bundled for \(id)."
        }
    }
}

extension AccountProvider {
    var apiCatalogID: String { "hosting.\(rawValue)" }
}

extension RegistrarProvider {
    var apiCatalogID: String { "registrar.\(rawValue)" }
}
