import Foundation

extension CloudflareAPI {
    func fetchZoneSecurityItems(
        zoneID: String,
        category: CloudflareSecurityCategory
    ) async throws -> [CloudflareSecurityItem] {
        switch category {
        case .wafRulesets:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/rulesets",
                query: ["per_page": "50"],
                category: category
            )
        case .accessRules:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/firewall/access_rules/rules",
                query: ["per_page": "50"],
                category: category
            )
        case .rateLimits:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/rate_limits",
                query: ["per_page": "50"],
                category: category
            )
        case .certificates:
            let edge = try await securityItems(
                path: securityZonePath(zoneID) + "/ssl/certificate_packs",
                query: ["status": "all", "per_page": "50"],
                category: category
            )
            let custom = (try? await securityItems(
                path: securityZonePath(zoneID) + "/custom_certificates",
                query: ["per_page": "50"],
                category: category
            )) ?? []
            return edge + custom
        case .pageShield:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/page_shield/policies",
                query: ["per_page": "50"],
                category: category
            )
        case .botManagement:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/bot_management",
                category: category
            )
        case .apiShield:
            return try await securityItems(
                path: securityZonePath(zoneID) + "/api_gateway/configuration",
                category: category
            )
        }
    }

    func fetchZoneSecurityLevel(zoneID: String) async throws -> String? {
        let result = try await securityResult(path: securityZonePath(zoneID) + "/settings/security_level")
        guard case .object(let object) = result else { return nil }
        return object["value"]?.securityStringValue
    }

    func updateZoneSecurityLevel(
        zoneID: String,
        level: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> String? {
        let path = securityZonePath(zoneID) + "/settings/security_level"
        let result = try await securityResult(
            path: path,
            method: .patch,
            body: .object(["value": .string(level)]),
            confirmation: confirmation
        )
        guard case .object(let object) = result else { return nil }
        return object["value"]?.securityStringValue
    }

    func fetchZoneRulesetRules(zoneID: String, rulesetID: String) async throws -> [CloudflareSecurityItem] {
        let result = try await securityResult(
            path: securityZonePath(zoneID) + "/rulesets/\(securityPathSegment(rulesetID))"
        )
        guard case .object(let object) = result,
              case .array(let rules)? = object["rules"] else { return [] }
        return rules.enumerated().map {
            securityItem(from: $0.element, category: .wafRulesets, fallbackIndex: $0.offset)
        }
    }

    func createZoneAccessRule(
        zoneID: String,
        target: String,
        value: String,
        mode: String,
        notes: String?,
        confirmation: CloudflareMutationConfirmation
    ) async throws -> CloudflareSecurityItem {
        let path = securityZonePath(zoneID) + "/firewall/access_rules/rules"
        var body: [String: CloudflareJSONValue] = [
            "mode": .string(mode),
            "configuration": .object([
                "target": .string(target),
                "value": .string(value)
            ])
        ]
        if let notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            body["notes"] = .string(notes.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        let result = try await securityResult(
            path: path,
            method: .post,
            body: .object(body),
            confirmation: confirmation
        )
        return securityItem(from: result, category: .accessRules, fallbackIndex: 0)
    }

    func deleteZoneAccessRule(
        zoneID: String,
        ruleID: String,
        confirmation: CloudflareMutationConfirmation
    ) async throws {
        let path = securityZonePath(zoneID) + "/firewall/access_rules/rules/\(securityPathSegment(ruleID))"
        _ = try await securityResult(path: path, method: .delete, confirmation: confirmation)
    }

    private func securityItems(
        path: String,
        query: [String: String] = [:],
        category: CloudflareSecurityCategory
    ) async throws -> [CloudflareSecurityItem] {
        var page = 1
        var cursor: String?
        var seenCursors = Set<String>()
        var items: [CloudflareSecurityItem] = []

        while true {
            var requestQuery = query
            if let cursor {
                requestQuery["cursor"] = cursor
            } else if requestQuery["per_page"] != nil, category != .wafRulesets {
                requestQuery["page"] = String(page)
            }

            let response = try await securityResponse(path: path, query: requestQuery)
            let pageItems = securityItems(from: response.result, category: category, startIndex: items.count)
            items.append(contentsOf: pageItems)

            if let after = response.resultInfo?.cursors?.after, !after.isEmpty {
                guard seenCursors.insert(after).inserted else { break }
                cursor = after
                continue
            }
            if category != .wafRulesets,
               let totalPages = response.resultInfo?.totalPages,
               page < totalPages {
                page += 1
                continue
            }
            if category != .wafRulesets,
               let totalCount = response.resultInfo?.totalCount,
               items.count < totalCount,
               !pageItems.isEmpty {
                page += 1
                continue
            }
            break
        }
        return items
    }

    private func securityItems(
        from result: CloudflareJSONValue,
        category: CloudflareSecurityCategory,
        startIndex: Int
    ) -> [CloudflareSecurityItem] {
        switch result {
        case .array(let values):
            return values.enumerated().map {
                securityItem(from: $0.element, category: category, fallbackIndex: startIndex + $0.offset)
            }
        case .object(let object):
            if case .array(let values)? = object["items"] {
                return values.enumerated().map {
                    securityItem(from: $0.element, category: category, fallbackIndex: startIndex + $0.offset)
                }
            }
            return [securityItem(from: result, category: category, fallbackIndex: startIndex)]
        default:
            return []
        }
    }

    private func securityItem(
        from value: CloudflareJSONValue,
        category: CloudflareSecurityCategory,
        fallbackIndex: Int
    ) -> CloudflareSecurityItem {
        guard case .object(let object) = value else {
            return CloudflareSecurityItem(
                id: "\(category.id)-\(fallbackIndex)",
                title: category.rawValue,
                subtitle: value.securityDisplayValue,
                status: nil,
                raw: value
            )
        }

        let configurationValue: String? = {
            guard case .object(let configuration)? = object["configuration"] else { return nil }
            return configuration["value"]?.securityStringValue
        }()
        let id = object["id"]?.securityStringValue
            ?? object["uuid"]?.securityStringValue
            ?? "\(category.id)-\(fallbackIndex)"
        let title = object["name"]?.securityStringValue
            ?? configurationValue
            ?? object["hostname"]?.securityStringValue
            ?? object["description"]?.securityStringValue
            ?? object["type"]?.securityStringValue
            ?? String(id.prefix(20))
        let subtitle = [
            object["description"]?.securityStringValue,
            object["phase"]?.securityStringValue,
            object["kind"]?.securityStringValue,
            object["type"]?.securityStringValue
        ]
            .compactMap { $0 }
            .filter { !$0.isEmpty && $0 != title }
            .joined(separator: " · ")
        let status = object["status"]?.securityStringValue
            ?? object["action"]?.securityStringValue
            ?? object["mode"]?.securityStringValue
            ?? object["enabled"]?.securityDisplayValue

        return CloudflareSecurityItem(
            id: id,
            title: title,
            subtitle: subtitle.isEmpty ? nil : subtitle,
            status: status,
            raw: value
        )
    }

    private func securityResult(
        path: String,
        method: CloudflareHTTPMethod = .get,
        query: [String: String] = [:],
        body: CloudflareJSONValue? = nil,
        confirmation: CloudflareMutationConfirmation? = nil
    ) async throws -> CloudflareJSONValue {
        (try await securityResponse(
            path: path,
            method: method,
            query: query,
            body: body,
            confirmation: confirmation
        )).result
    }

    private func securityResponse(
        path: String,
        method: CloudflareHTTPMethod = .get,
        query: [String: String] = [:],
        body: CloudflareJSONValue? = nil,
        confirmation: CloudflareMutationConfirmation? = nil
    ) async throws -> (result: CloudflareJSONValue, resultInfo: CloudflareResultInfo?) {
        let bodyText: String?
        if let body {
            let data = try JSONEncoder().encode(body)
            bodyText = String(data: data, encoding: .utf8)
        } else {
            bodyText = nil
        }
        let response = try await rawRequest(
            method: method,
            path: path,
            query: query,
            bodyText: bodyText,
            confirmation: confirmation
        )
        guard (200...299).contains(response.statusCode) else {
            let message = securityErrorMessage(response.data)
            switch response.statusCode {
            case 401: throw CloudflareAPIError.invalidCredentials
            case 403: throw CloudflareAPIError.forbidden(message)
            default: throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: message)
            }
        }
        guard !response.data.isEmpty else { return (.null, nil) }
        do {
            let envelope = try JSONDecoder().decode(CloudflareSecurityResponseEnvelope.self, from: response.data)
            guard envelope.success else {
                if !envelope.errors.isEmpty { throw CloudflareAPIError.api(envelope.errors) }
                throw CloudflareAPIError.requestFailed(statusCode: response.statusCode, message: "Cloudflare rejected the security request.")
            }
            return (envelope.result ?? .null, envelope.resultInfo)
        } catch let error as CloudflareAPIError {
            throw error
        } catch {
            throw CloudflareAPIError.decoding(error.localizedDescription)
        }
    }

    private func securityErrorMessage(_ data: Data) -> String {
        guard let root = try? JSONDecoder().decode(CloudflareJSONValue.self, from: data),
              case .object(let object) = root,
              case .array(let errors)? = object["errors"] else { return "" }
        return errors.compactMap { error -> String? in
            guard case .object(let values) = error else { return nil }
            return values["message"]?.securityStringValue
        }.joined(separator: "\n")
    }

    private func securityZonePath(_ zoneID: String) -> String {
        "/zones/\(securityPathSegment(zoneID))"
    }

    private func securityPathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

private nonisolated struct CloudflareSecurityResponseEnvelope: Decodable, Sendable {
    let success: Bool
    let result: CloudflareJSONValue?
    let resultInfo: CloudflareResultInfo?
    let errors: [CloudflareAPIIssue]

    private enum CodingKeys: String, CodingKey {
        case success, result, errors
        case resultInfo = "result_info"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        success = try container.decodeIfPresent(Bool.self, forKey: .success) ?? false
        result = try container.decodeIfPresent(CloudflareJSONValue.self, forKey: .result)
        resultInfo = try container.decodeIfPresent(CloudflareResultInfo.self, forKey: .resultInfo)
        errors = try container.decodeIfPresent([CloudflareAPIIssue].self, forKey: .errors) ?? []
    }
}

private extension CloudflareJSONValue {
    nonisolated var securityStringValue: String? {
        guard case .string(let value) = self else { return nil }
        return value
    }

    nonisolated var securityBoolValue: Bool? {
        guard case .bool(let value) = self else { return nil }
        return value
    }

    nonisolated var securityDisplayValue: String {
        switch self {
        case .string(let value): value
        case .int(let value): value.formatted()
        case .double(let value): value.formatted()
        case .bool(let value): value ? "Enabled" : "Disabled"
        case .object(let value): "\(value.count) properties"
        case .array(let value): "\(value.count) items"
        case .null: "Not returned"
        }
    }
}
