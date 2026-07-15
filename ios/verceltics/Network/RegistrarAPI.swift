import CryptoKit
import Foundation

struct RegistrarAPI {
    let provider: RegistrarProvider
    let primaryCredential: String
    let secondaryCredential: String?
    let metadata: [String: String]
    private static let maximumPaginationPages = 200

    init(
        provider: RegistrarProvider,
        primaryCredential: String,
        secondaryCredential: String? = nil,
        metadata: [String: String] = [:]
    ) {
        self.provider = provider
        self.primaryCredential = primaryCredential
        self.secondaryCredential = secondaryCredential
        self.metadata = metadata
    }

    init(account: RegistrarAccount) {
        self.init(
            provider: account.provider,
            primaryCredential: account.primaryCredential,
            secondaryCredential: account.secondaryCredential,
            metadata: account.metadata
        )
    }

    func validateCredentials() async throws -> String {
        _ = try await fetchDomains()
        switch provider {
        case .nameDotCom, .namecheap:
            return metadata["username"].flatMap { $0.isEmpty ? nil : $0 } ?? provider.displayName
        case .gandi:
            return metadata["organization"].flatMap { $0.isEmpty ? nil : $0 } ?? "Gandi Account"
        default:
            return "\(provider.displayName) · \(credentialFingerprint.suffix(4))"
        }
    }

    func fetchDomains() async throws -> [RegistrarDomain] {
        switch provider {
        case .nameDotCom:
            return try await fetchNameDotComDomains()

        case .namecheap:
            return try await fetchNamecheapDomains()

        case .porkbun:
            let root = object(try await jsonRequest(method: "POST", path: "/domain/listAll", body: "{}"))
            try validateStatus(root)
            return array(root["domains"]).compactMap { normalizePorkbun(object($0)) }

        case .spaceship:
            return try await fetchSpaceshipDomains()

        case .dynadot:
            return try await fetchDynadotDomains()

        case .nameSilo:
            return try await fetchNameSiloDomains()

        case .gandi:
            return try await fetchGandiDomains()

        case .goDaddy:
            return try await fetchGoDaddyDomains()
        }
    }

    func rawRequest(
        method: String,
        path: String,
        body: String?,
        additionalHeaders: [String: String] = [:],
        contentType: String? = nil,
        bodyIsBase64: Bool = false,
        returnHTTPErrorResponse: Bool = false
    ) async throws -> RegistrarRawResponse {
        let normalizedMethod = method.uppercased()
        guard ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].contains(normalizedMethod) else {
            throw RegistrarAPIError.invalidConfiguration("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.")
        }
        if provider == .nameSilo, normalizedMethod != "GET" {
            throw RegistrarAPIError.invalidConfiguration("NameSilo requires every API operation to use GET.")
        }
        guard path.hasPrefix("/"), !path.hasPrefix("//") else {
            throw RegistrarAPIError.invalidConfiguration("Enter a registrar-relative path beginning with /.")
        }

        var components = URLComponents(string: baseURL + path)
        guard components != nil else { throw RegistrarAPIError.invalidConfiguration("The API path is invalid.") }
        var items = components?.queryItems ?? []

        switch provider {
        case .namecheap:
            let username = try requiredMetadata("username", label: "Namecheap API username")
            let clientIP = try requiredMetadata("clientIP", label: "whitelisted client IP")
            items += [
                URLQueryItem(name: "ApiUser", value: username),
                URLQueryItem(name: "ApiKey", value: primaryCredential),
                URLQueryItem(name: "UserName", value: username),
                URLQueryItem(name: "ClientIp", value: clientIP)
            ]
        case .dynadot:
            items.append(URLQueryItem(name: "key", value: primaryCredential))
        case .nameSilo:
            items += [
                URLQueryItem(name: "version", value: "1"),
                URLQueryItem(name: "type", value: "json"),
                URLQueryItem(name: "key", value: primaryCredential)
            ]
        default:
            break
        }
        components?.queryItems = items
        guard let url = components?.url else { throw RegistrarAPIError.invalidConfiguration("The API path is invalid.") }

        let bodyData: Data?
        if bodyIsBase64, let body {
            guard let decoded = Data(base64Encoded: body) else {
                throw ProviderRequestSecurityError.invalidBase64Body
            }
            bodyData = decoded
        } else {
            bodyData = body?.data(using: .utf8)
        }
        let validatedContentType = try ProviderRequestSecurity.validatedContentType(contentType)
        let validatedHeaders = try ProviderRequestSecurity.validatedHeaders(
            additionalHeaders,
            protectedHeaders: Self.protectedHeaders
        )

        var request = URLRequest(url: url)
        request.httpMethod = normalizedMethod
        request.httpBody = bodyData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if bodyData != nil { request.setValue(validatedContentType ?? "application/json", forHTTPHeaderField: "Content-Type") }

        switch provider {
        case .nameDotCom:
            let username = try requiredMetadata("username", label: "Name.com API username")
            let encoded = Data("\(username):\(primaryCredential)".utf8).base64EncodedString()
            request.setValue("Basic \(encoded)", forHTTPHeaderField: "Authorization")
        case .porkbun:
            request.setValue(primaryCredential, forHTTPHeaderField: "X-API-Key")
            request.setValue(secondaryCredential ?? "", forHTTPHeaderField: "X-Secret-API-Key")
        case .spaceship:
            request.setValue(primaryCredential, forHTTPHeaderField: "X-API-Key")
            request.setValue(secondaryCredential ?? "", forHTTPHeaderField: "X-API-Secret")
        case .gandi:
            request.setValue("Bearer \(primaryCredential)", forHTTPHeaderField: "Authorization")
        case .goDaddy:
            request.setValue("sso-key \(primaryCredential):\(secondaryCredential ?? "")", forHTTPHeaderField: "Authorization")
        case .namecheap, .dynadot, .nameSilo:
            break
        }

        for (name, value) in validatedHeaders {
            request.setValue(value, forHTTPHeaderField: name)
        }

        let (data, response) = try await ProviderRequestSecurity.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw RegistrarAPIError.invalidResponse }
        let text = String(data: data, encoding: .utf8) ?? data.base64EncodedString()
        if !(200...299).contains(http.statusCode), !returnHTTPErrorResponse {
            throw RegistrarAPIError.requestFailed(http.statusCode, errorMessage(data: data))
        }
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { result, item in
            result[String(describing: item.key)] = String(describing: item.value)
        }
        return RegistrarRawResponse(statusCode: http.statusCode, headers: headers, body: text)
    }

    private static let protectedHeaders = Set(["authorization", "host", "content-length", "content-type", "x-api-key", "x-secret-api-key", "x-api-secret"])

    func isLikelyWrite(method: String, path: String) -> Bool {
        if !["GET", "HEAD", "OPTIONS"].contains(method.uppercased()) { return true }
        let value = path.lowercased()
        switch provider {
        case .namecheap:
            return [".create", ".set", ".renew", ".reactivate", ".delete", ".update", ".change", ".enable", ".disable", ".activate", ".reissue", ".resend", ".purchase", ".revoke", ".edit", ".reset"].contains { value.contains($0) }
        case .dynadot:
            return ["command=register", "command=delete", "command=restore", "command=renew", "command=set_", "command=transfer", "command=push", "command=buy_", "command=make_", "command=place_", "command=create_", "command=edit_", "command=clear_", "command=modify_"].contains { value.contains($0) }
        case .nameSilo:
            return ["registerdomain", "renewdomain", "transferdomain", "transferupdate", "changedns", "contactadd", "contactupdate", "domainupdate", "addprivacy", "removeprivacy", "addautorenewal", "removeautorenewal", "addregistrylock", "removeregistrylock", "dnsaddrecord", "dnsupdaterecord", "dnsdeleterecord", "domainforward", "addregisterednameserver", "modifyregisterednameserver", "deleteregisterednameserver", "portfolioadd", "portfoliodelete"].contains { value.contains($0) }
        default:
            return false
        }
    }

    func suggestedPath(for domain: RegistrarDomain? = nil) -> String {
        switch provider {
        case .nameDotCom: domain.map { "/core/v1/domains/\($0.name)" } ?? "/core/v1/domains?perPage=250"
        case .namecheap: domain.map { "/xml.response?Command=namecheap.domains.getInfo&DomainName=\($0.name)" } ?? "/xml.response?Command=namecheap.domains.getList&ListType=ALL&PageSize=100"
        case .porkbun: domain.map { "/dns/retrieve/\($0.name)" } ?? "/domain/listAll"
        case .spaceship: domain.map { "/v1/domains/\($0.name)" } ?? "/v1/domains?take=100&skip=0"
        case .dynadot: domain.map { "/api3.json?command=domain_info&domain=\($0.name)" } ?? "/api3.json?command=list_domain"
        case .nameSilo: domain.map { "/api/getDomainInfo?domain=\($0.name)" } ?? "/api/listDomains"
        case .gandi: domain.map { "/v5/domain/domains/\($0.name)" } ?? "/v5/domain/domains?per_page=100&page=1"
        case .goDaddy: domain.map { "/v1/domains/\($0.name)" } ?? "/v1/domains?limit=1000&includes=nameServers"
        }
    }

    // MARK: - Normalization

    private func fetchNameDotComDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var page = 1
        var seenPages = Set<Int>()
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            guard seenPages.insert(page).inserted else {
                throw RegistrarAPIError.decoding("Name.com pagination repeated page \(page).")
            }
            let root = object(try await jsonRequest(method: "GET", path: "/core/v1/domains?perPage=250&page=\(page)", body: nil))
            domains += array(root["domains"]).compactMap { normalizeNameDotCom(object($0)) }
            pageCount += 1
            guard let nextPage = int(root["nextPage"]), nextPage > page else { break }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            page = nextPage
        }
        return deduplicatedDomains(domains)
    }

    private func fetchNamecheapDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var page = 1
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            let response = try await rawRequest(
                method: "GET",
                path: "/xml.response?Command=namecheap.domains.getList&ListType=ALL&PageSize=100&Page=\(page)",
                body: nil
            )
            let result = try parseNamecheapDomains(response.body)
            domains += result.domains
            pageCount += 1
            guard result.totalItems > page * max(result.pageSize, 1) else { break }
            guard !result.domains.isEmpty else {
                throw RegistrarAPIError.decoding("Namecheap pagination returned no domains before reaching the reported total.")
            }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private func fetchSpaceshipDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var skip = 0
        let pageSize = 100
        var seenOffsets = Set<Int>()
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            guard seenOffsets.insert(skip).inserted else {
                throw RegistrarAPIError.decoding("Spaceship pagination repeated offset \(skip).")
            }
            let value = try await jsonRequest(method: "GET", path: "/v1/domains?take=\(pageSize)&skip=\(skip)", body: nil)
            let root = object(value)
            let items = array(root["items"] ?? root["domains"] ?? value)
            domains += items.compactMap { normalizeSpaceship(object($0)) }
            let total = int(root["total"]) ?? items.count
            skip += items.count
            pageCount += 1
            guard !items.isEmpty, skip < total else { break }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
        }
        return deduplicatedDomains(domains)
    }

    private func fetchDynadotDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var page = 0
        let pageSize = 100
        var seenPageSignatures = Set<String>()
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            let value = try await jsonRequest(
                method: "GET",
                path: "/api3.json?command=list_domain&count_per_page=\(pageSize)&page_index=\(page)",
                body: nil
            )
            let root = object(value)
            let response = object(root["ListDomainInfoResponse"])
            if let status = string(response, "Status"), status.lowercased() != "success" {
                throw RegistrarAPIError.requestFailed(int(response["ResponseCode"]) ?? 400, string(response, "Error") ?? status)
            }
            let items = findArray(in: value, keys: ["MainDomains", "domains", "domain", "Domain"])
            let pageDomains = items.compactMap { item in
                if let name = item as? String { return emptyDomain(name: name) }
                return normalizeDynadot(object(item))
            }
            let signature = pageDomains.map { $0.name.lowercased() }.sorted().joined(separator: "|")
            if !signature.isEmpty, !seenPageSignatures.insert(signature).inserted {
                throw RegistrarAPIError.decoding("Dynadot pagination repeated a results page.")
            }
            domains += pageDomains
            pageCount += 1
            guard items.count == pageSize else { break }
            guard !pageDomains.isEmpty else {
                throw RegistrarAPIError.decoding("Dynadot pagination returned no usable domains for a full page.")
            }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private func fetchGandiDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var page = 1
        let pageSize = 100
        var seenPageSignatures = Set<String>()
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            let value = try await jsonRequest(method: "GET", path: "/v5/domain/domains?per_page=\(pageSize)&page=\(page)", body: nil)
            let items = array(value)
            let pageDomains = items.compactMap { normalizeGandi(object($0)) }
            let signature = pageDomains.map { $0.name.lowercased() }.sorted().joined(separator: "|")
            if !signature.isEmpty, !seenPageSignatures.insert(signature).inserted {
                throw RegistrarAPIError.decoding("Gandi pagination repeated a results page.")
            }
            domains += pageDomains
            pageCount += 1
            guard items.count == pageSize else { break }
            guard !pageDomains.isEmpty else {
                throw RegistrarAPIError.decoding("Gandi pagination returned no usable domains for a full page.")
            }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private func fetchNameSiloDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var page = 1
        let pageSize = 100
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            let root = object(try await jsonRequest(method: "GET", path: "/api/listDomains?pageSize=\(pageSize)&page=\(page)", body: nil))
            let reply = object(root["reply"])
            if let code = string(reply, "code"), code != "300" {
                throw RegistrarAPIError.requestFailed(Int(code) ?? 400, string(reply, "detail") ?? "NameSilo rejected the request.")
            }
            let items = array(reply["domains"])
            domains += items.compactMap { value in
                if let name = value as? String { return emptyDomain(name: name) }
                let item = object(value)
                guard let name = string(item, "domain", "name") else { return nil }
                return RegistrarDomain(
                    name: name,
                    status: string(item, "status"),
                    createdAt: date(item["created"] ?? item["created_at"]),
                    expiresAt: date(item["expires"] ?? item["expiration"]),
                    autoRenew: bool(item["auto_renew"] ?? item["autoRenew"]),
                    locked: bool(item["locked"]),
                    privacyEnabled: bool(item["private"] ?? item["privacy"]),
                    nameservers: strings(item["nameservers"]),
                    metadata: [:]
                )
            }
            let pager = object(reply["pager"])
            let total = int(pager["total"]) ?? domains.count
            pageCount += 1
            guard domains.count < total, !items.isEmpty else { break }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            page += 1
        }
        return deduplicatedDomains(domains)
    }

    private func fetchGoDaddyDomains() async throws -> [RegistrarDomain] {
        var domains: [RegistrarDomain] = []
        var marker: String?
        let pageSize = 1_000
        var seenMarkers = Set<String>()
        var pageCount = 0
        while true {
            try Task.checkCancellation()
            let markerQuery = marker.map { "&marker=\(queryComponent($0))" } ?? ""
            let value = try await jsonRequest(
                method: "GET",
                path: "/v1/domains?limit=\(pageSize)&includes=nameServers\(markerQuery)",
                body: nil
            )
            let items = array(value)
            domains += items.compactMap { normalizeGoDaddy(object($0)) }
            pageCount += 1
            guard items.count == pageSize,
                  let nextMarker = items.last.flatMap({ string(object($0), "domain") }) else { break }
            guard seenMarkers.insert(nextMarker).inserted, nextMarker != marker else {
                throw RegistrarAPIError.decoding("GoDaddy pagination repeated a marker.")
            }
            guard pageCount < Self.maximumPaginationPages else { throw paginationLimitError() }
            marker = nextMarker
        }
        return deduplicatedDomains(domains)
    }

    private func normalizeNameDotCom(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "domainName", "domain") else { return nil }
        return RegistrarDomain(
            name: name,
            status: string(value, "status"),
            createdAt: date(value["createDate"]),
            expiresAt: date(value["expireDate"]),
            autoRenew: bool(value["autorenewEnabled"] ?? value["autoRenew"]),
            locked: bool(value["locked"]),
            privacyEnabled: bool(value["privacyEnabled"]),
            nameservers: strings(value["nameservers"]),
            metadata: [:]
        )
    }

    private func normalizePorkbun(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "domain", "name") else { return nil }
        return RegistrarDomain(
            name: name,
            status: string(value, "status"),
            createdAt: date(value["createDate"] ?? value["createdAt"]),
            expiresAt: date(value["expireDate"] ?? value["expirationDate"]),
            autoRenew: bool(value["autoRenew"]),
            locked: bool(value["securityLock"] ?? value["locked"]),
            privacyEnabled: bool(value["whoisPrivacy"] ?? value["privacy"]),
            nameservers: strings(value["nameservers"]),
            metadata: [:]
        )
    }

    private func normalizeSpaceship(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "unicodeName", "name", "domain") else { return nil }
        let privacy = object(value["privacyProtection"])
        let nameserverRoot = object(value["nameservers"])
        let statuses = strings(value["eppStatuses"])
        let privacyEnabled = bool(privacy["contactForm"])
            ?? string(privacy, "level").map { $0.lowercased() != "none" }
        return RegistrarDomain(
            name: name,
            status: string(value, "lifecycleStatus", "status"),
            createdAt: date(value["registrationDate"]),
            expiresAt: date(value["expirationDate"]),
            autoRenew: bool(value["autoRenew"]),
            locked: statuses.contains { $0.localizedCaseInsensitiveContains("transferProhibited") },
            privacyEnabled: privacyEnabled,
            nameservers: strings(nameserverRoot["hosts"] ?? value["nameservers"]),
            metadata: ["verification": string(value, "verificationStatus") ?? ""]
        )
    }

    private func normalizeDynadot(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "Name", "name", "Domain", "domain") else { return nil }
        return RegistrarDomain(
            name: name,
            status: string(value, "Status", "status"),
            createdAt: date(value["Registration"] ?? value["CreateDate"] ?? value["created"]),
            expiresAt: date(value["Expiration"] ?? value["ExpireDate"] ?? value["expiration"]),
            autoRenew: bool(value["RenewOption"] ?? value["AutoRenew"]),
            locked: bool(value["Locked"] ?? value["lock"]),
            privacyEnabled: bool(value["Privacy"] ?? value["privacy"]),
            nameservers: strings(value["NameServers"] ?? value["nameservers"]),
            metadata: [:]
        )
    }

    private func normalizeGandi(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "fqdn", "name") else { return nil }
        let dates = object(value["dates"])
        let nameserver = object(value["nameserver"])
        let statuses = strings(value["status"])
        return RegistrarDomain(
            name: name,
            status: statuses.isEmpty ? nil : statuses.joined(separator: ", "),
            createdAt: date(value["dates_registry_created_at"] ?? dates["registry_created_at"]),
            expiresAt: date(value["dates_registry_ends_at"] ?? dates["registry_ends_at"]),
            autoRenew: bool(value["autorenew"]),
            locked: statuses.contains { $0.localizedCaseInsensitiveContains("transferProhibited") },
            privacyEnabled: bool(value["is_private"] ?? value["privacy"]),
            nameservers: strings(nameserver["hosts"] ?? value["nameservers"]),
            metadata: ["sharingID": string(value, "sharing_id") ?? ""]
        )
    }

    private func normalizeGoDaddy(_ value: [String: Any]) -> RegistrarDomain? {
        guard let name = string(value, "domain", "name") else { return nil }
        return RegistrarDomain(
            name: name,
            status: string(value, "status"),
            createdAt: date(value["createdAt"] ?? value["created"]),
            expiresAt: date(value["expires"] ?? value["expiresAt"]),
            autoRenew: bool(value["renewAuto"] ?? value["autoRenew"]),
            locked: bool(value["locked"]),
            privacyEnabled: bool(value["privacy"]),
            nameservers: strings(value["nameServers"] ?? value["nameservers"]),
            metadata: ["domainID": string(value, "domainId") ?? ""]
        )
    }

    private func parseNamecheapDomains(_ xml: String) throws -> NamecheapDomainPage {
        guard let data = xml.data(using: .utf8) else { throw RegistrarAPIError.decoding("Namecheap returned invalid XML.") }
        let parser = XMLParser(data: data)
        let delegate = NamecheapDomainXMLDelegate()
        parser.delegate = delegate
        guard parser.parse() else {
            throw RegistrarAPIError.decoding(parser.parserError?.localizedDescription ?? "Namecheap XML parsing failed.")
        }
        if let error = delegate.apiError { throw RegistrarAPIError.requestFailed(400, error) }
        let domains: [RegistrarDomain] = delegate.domains.compactMap { attributes -> RegistrarDomain? in
            guard let name = attributes["Name"] else { return nil }
            return RegistrarDomain(
                name: name,
                status: attributes["IsExpired"] == "true" ? "Expired" : "Active",
                createdAt: date(attributes["Created"]),
                expiresAt: date(attributes["Expires"]),
                autoRenew: bool(attributes["AutoRenew"]),
                locked: bool(attributes["IsLocked"]),
                privacyEnabled: ["ENABLED", "WITHHELD"].contains(attributes["WhoisGuard"]?.uppercased() ?? ""),
                nameservers: [],
                metadata: ["isOurDNS": attributes["IsOurDNS"] ?? ""]
            )
        }
        return NamecheapDomainPage(
            domains: domains,
            totalItems: delegate.totalItems ?? domains.count,
            pageSize: delegate.pageSize ?? max(domains.count, 1)
        )
    }

    // MARK: - Request helpers

    private var baseURL: String {
        switch provider {
        case .nameDotCom: "https://api.name.com"
        case .namecheap: "https://api.namecheap.com"
        case .porkbun: "https://api.porkbun.com/api/json/v3"
        case .spaceship: "https://spaceship.dev/api"
        case .dynadot: "https://api.dynadot.com"
        case .nameSilo: "https://www.namesilo.com"
        case .gandi: "https://api.gandi.net"
        case .goDaddy: "https://api.godaddy.com"
        }
    }

    private func jsonRequest(method: String, path: String, body: String?) async throws -> Any {
        let response = try await rawRequest(method: method, path: path, body: body)
        guard let data = response.body.data(using: .utf8) else { throw RegistrarAPIError.decoding("Response is not UTF-8.") }
        do { return try JSONSerialization.jsonObject(with: data) }
        catch { throw RegistrarAPIError.decoding(error.localizedDescription) }
    }

    private func validateStatus(_ root: [String: Any]) throws {
        if let status = string(root, "status"), !["success", "ok"].contains(status.lowercased()) {
            throw RegistrarAPIError.requestFailed(400, string(root, "message", "error") ?? status)
        }
    }

    private func errorMessage(data: Data) -> String {
        guard let value = try? JSONSerialization.jsonObject(with: data) else {
            return String(data: data, encoding: .utf8) ?? ""
        }
        let root = object(value)
        let message = string(root, "message", "detail", "details", "error")
        let details = string(root, "details", "detail")
        if let message, let details, message != details { return "\(message) — \(details)" }
        return message ?? String(data: data, encoding: .utf8) ?? ""
    }

    private func requiredMetadata(_ key: String, label: String) throws -> String {
        guard let value = metadata[key], !value.isEmpty else {
            throw RegistrarAPIError.invalidConfiguration("Enter the \(label).")
        }
        return value
    }

    private var credentialFingerprint: String {
        SHA256.hash(data: Data(primaryCredential.utf8)).prefix(4).map { String(format: "%02x", $0) }.joined()
    }

    private func object(_ value: Any?) -> [String: Any] { value as? [String: Any] ?? [:] }
    private func array(_ value: Any?) -> [Any] { value as? [Any] ?? [] }

    private func string(_ value: [String: Any], _ keys: String...) -> String? {
        for key in keys {
            if let text = value[key] as? String, !text.isEmpty { return text }
            if let number = value[key] as? NSNumber { return number.stringValue }
        }
        return nil
    }

    private func bool(_ value: Any?) -> Bool? {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String {
            switch value.lowercased() {
            case "1", "true", "yes", "enabled", "on", "auto", "renew": return true
            case "0", "false", "no", "disabled", "off", "none", "manual": return false
            default: return nil
            }
        }
        return nil
    }

    private func int(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        if let value = value as? String { return Int(value) }
        return nil
    }

    private func strings(_ value: Any?) -> [String] {
        if let values = value as? [String] { return values }
        if let values = value as? [Any] { return values.compactMap { $0 as? String } }
        if let value = value as? String {
            return value.split(whereSeparator: { $0 == "," || $0 == ";" || $0 == " " }).map(String.init)
        }
        return []
    }

    private func date(_ value: Any?) -> Date? {
        if let value = value as? NSNumber { return unixDate(value.doubleValue) }
        guard let text = value as? String, !text.isEmpty else { return nil }
        if let timestamp = Double(text) { return unixDate(timestamp) }
        if let date = ISO8601DateFormatter().date(from: text) { return date }
        for format in ["MM/dd/yyyy", "yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"] {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(secondsFromGMT: 0)
            formatter.dateFormat = format
            if let date = formatter.date(from: text) { return date }
        }
        return nil
    }

    private func unixDate(_ value: Double) -> Date {
        Date(timeIntervalSince1970: value > 100_000_000_000 ? value / 1_000 : value)
    }

    private func emptyDomain(name: String) -> RegistrarDomain {
        RegistrarDomain(name: name, status: nil, createdAt: nil, expiresAt: nil, autoRenew: nil, locked: nil, privacyEnabled: nil, nameservers: [], metadata: [:])
    }

    private func deduplicatedDomains(_ domains: [RegistrarDomain]) -> [RegistrarDomain] {
        var seenNames = Set<String>()
        return domains.filter { domain in
            let key = domain.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !key.isEmpty else { return false }
            return seenNames.insert(key).inserted
        }
    }

    private func paginationLimitError() -> RegistrarAPIError {
        .decoding("Pagination exceeded \(Self.maximumPaginationPages) pages.")
    }

    private func queryComponent(_ value: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    private func findArray(in value: Any, keys: Set<String>) -> [Any] {
        if let values = value as? [Any], !values.isEmpty { return values }
        guard let object = value as? [String: Any] else { return [] }
        for (key, nested) in object {
            if keys.contains(key), let values = nested as? [Any] { return values }
        }
        for nested in object.values {
            let result = findArray(in: nested, keys: keys)
            if !result.isEmpty { return result }
        }
        return []
    }
}

private struct NamecheapDomainPage {
    let domains: [RegistrarDomain]
    let totalItems: Int
    let pageSize: Int
}

private final class NamecheapDomainXMLDelegate: NSObject, XMLParserDelegate {
    var domains: [[String: String]] = []
    var apiError: String?
    var totalItems: Int?
    var pageSize: Int?
    private var text = ""

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        text = ""
        if elementName == "Domain", !attributeDict.isEmpty { domains.append(attributeDict) }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "Error" {
            let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !message.isEmpty { apiError = message }
        }
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if elementName == "TotalItems" { totalItems = Int(value) }
        if elementName == "PageSize" { pageSize = Int(value) }
    }
}
