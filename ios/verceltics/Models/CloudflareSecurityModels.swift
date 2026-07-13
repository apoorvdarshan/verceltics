import Foundation

nonisolated enum CloudflareSecurityCategory: String, CaseIterable, Identifiable, Sendable {
    case wafRulesets = "WAF rulesets"
    case accessRules = "IP access rules"
    case rateLimits = "Rate limits"
    case certificates = "Certificates"
    case pageShield = "Page Shield"
    case botManagement = "Bot management"
    case apiShield = "API Shield"

    var id: String { rawValue }
}

nonisolated struct CloudflareSecurityItem: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let subtitle: String?
    let status: String?
    let raw: CloudflareJSONValue
}

nonisolated struct CloudflareSecuritySnapshot: Equatable, Sendable {
    var rulesets: [CloudflareSecurityItem] = []
    var accessRules: [CloudflareSecurityItem] = []
    var rateLimits: [CloudflareSecurityItem] = []
    var certificates: [CloudflareSecurityItem] = []
    var pageShield: [CloudflareSecurityItem] = []
    var botManagement: [CloudflareSecurityItem] = []
    var apiShield: [CloudflareSecurityItem] = []
    var securityLevel: String?
    var warnings: [String] = []

    var totalItems: Int {
        rulesets.count + accessRules.count + rateLimits.count + certificates.count
            + pageShield.count + botManagement.count + apiShield.count
    }
}
