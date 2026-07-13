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
}

actor ProviderAPICatalogStore {
    static let shared = ProviderAPICatalogStore()

    private var cached: ProviderAPICatalogBundle?

    func catalog(id: String) throws -> ProviderAPICatalog {
        let bundle = try load()
        guard let catalog = bundle.providers.first(where: { $0.id == id }) else {
            throw ProviderAPICatalogError.missingProvider(id)
        }
        return catalog
    }

    func railwayCatalog(account: VercelAccount) async throws -> ProviderAPICatalog {
        let query = """
        query VercelticsIntrospection {
          __schema {
            queryType { name }
            mutationType { name }
            types {
              kind name
              fields(includeDeprecated: true) {
                name description isDeprecated deprecationReason
                args { name description defaultValue type { kind name ofType { kind name ofType { kind name ofType { kind name } } } } }
                type { kind name ofType { kind name ofType { kind name ofType { kind name } } } }
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
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let payload = root["data"] as? [String: Any],
              let schema = payload["__schema"] as? [String: Any],
              let types = schema["types"] as? [[String: Any]] else {
            throw HostingProviderAPIError.decoding("Railway did not return its GraphQL schema.")
        }
        if let errors = root["errors"] as? [[String: Any]], let message = errors.first?["message"] as? String {
            throw HostingProviderAPIError.decoding(message)
        }

        let queryName = (schema["queryType"] as? [String: Any])?["name"] as? String
        let mutationName = (schema["mutationType"] as? [String: Any])?["name"] as? String
        var operations: [ProviderAPIOperation] = []
        for type in types {
            guard let typeName = type["name"] as? String,
                  typeName == queryName || typeName == mutationName,
                  let fields = type["fields"] as? [[String: Any]] else { continue }
            let isMutation = typeName == mutationName
            for field in fields {
                guard let fieldName = field["name"] as? String else { continue }
                let args = field["args"] as? [[String: Any]] ?? []
                var declarations: [String] = []
                var calls: [String] = []
                var variables: [String: Any] = [:]
                for argument in args {
                    guard let name = argument["name"] as? String,
                          let type = argument["type"] as? [String: Any] else { continue }
                    let typeName = Self.graphQLTypeName(type)
                    declarations.append("$\(name): \(typeName)")
                    calls.append("\(name): $\(name)")
                    variables[name] = Self.graphQLPlaceholder(type)
                }
                let declaration = declarations.isEmpty ? "" : "(\(declarations.joined(separator: ", ")))"
                let call = calls.isEmpty ? "" : "(\(calls.joined(separator: ", ")))"
                let returnType = field["type"] as? [String: Any] ?? [:]
                let selection = Self.graphQLIsScalar(returnType) ? "" : " { __typename }"
                let operationKind = isMutation ? "mutation" : "query"
                let document = "\(operationKind) Verceltics_\(fieldName)\(declaration) { \(fieldName)\(call)\(selection) }"
                let request = try JSONSerialization.data(
                    withJSONObject: ["query": document, "variables": variables],
                    options: [.prettyPrinted, .sortedKeys]
                )
                operations.append(ProviderAPIOperation(
                    id: "railway.\(typeName).\(fieldName)",
                    method: "POST",
                    path: "/graphql/v2",
                    summary: fieldName,
                    description: (field["description"] as? String) ?? (field["deprecationReason"] as? String) ?? "",
                    tags: [isMutation ? "Mutations" : "Queries"],
                    deprecated: field["isDeprecated"] as? Bool ?? false,
                    parameters: [],
                    contentTypes: ["application/json"],
                    requestBodyRequired: true,
                    bodyTemplate: String(data: request, encoding: .utf8) ?? "{}"
                ))
            }
        }
        operations.sort { ($0.primaryTag, $0.summary) < ($1.primaryTag, $1.summary) }
        return ProviderAPICatalog(
            id: "hosting.railway",
            title: "Railway",
            apiVersion: "Live GraphQL v2",
            sourceURL: "https://docs.railway.com/reference/public-api",
            sourceDescription: "Every query and mutation discovered live from the authenticated Railway GraphQL schema",
            operations: operations
        )
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

    private static func graphQLPlaceholder(_ value: [String: Any]) -> Any {
        let kind = value["kind"] as? String ?? ""
        if (kind == "NON_NULL" || kind == "LIST"), let nested = value["ofType"] as? [String: Any] {
            let placeholder = graphQLPlaceholder(nested)
            return kind == "LIST" ? [placeholder] : placeholder
        }
        switch value["name"] as? String {
        case "Int": return 0
        case "Float": return 0.0
        case "Boolean": return false
        default: return ""
        }
    }

    private static func graphQLIsScalar(_ value: [String: Any]) -> Bool {
        let kind = value["kind"] as? String ?? ""
        if let nested = value["ofType"] as? [String: Any] { return graphQLIsScalar(nested) }
        return kind == "SCALAR" || kind == "ENUM"
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
