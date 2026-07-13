import Foundation
import SwiftUI

enum RegistrarProvider: String, Codable, CaseIterable, Identifiable, Sendable {
    case nameDotCom
    case namecheap
    case porkbun
    case spaceship
    case dynadot
    case nameSilo
    case gandi
    case goDaddy

    var id: Self { self }

    var displayName: String {
        switch self {
        case .nameDotCom: "Name.com"
        case .namecheap: "Namecheap"
        case .porkbun: "Porkbun"
        case .spaceship: "Spaceship"
        case .dynadot: "Dynadot"
        case .nameSilo: "NameSilo"
        case .gandi: "Gandi"
        case .goDaddy: "GoDaddy"
        }
    }

    var logoAssetName: String {
        switch self {
        case .nameDotCom: "NameDotComMark"
        case .namecheap: "NamecheapMark"
        case .porkbun: "PorkbunMark"
        case .spaceship: "SpaceshipMark"
        case .dynadot: "DynadotMark"
        case .nameSilo: "NameSiloMark"
        case .gandi: "GandiMark"
        case .goDaddy: "GoDaddyMark"
        }
    }

    var logoNeedsTint: Bool {
        switch self {
        case .dynadot, .nameSilo: true
        case .nameDotCom, .namecheap, .porkbun, .spaceship, .gandi, .goDaddy: false
        }
    }

    var accentColor: Color {
        switch self {
        case .nameDotCom: Color(red: 0.16, green: 0.55, blue: 0.96)
        case .namecheap: Color(red: 1.00, green: 0.37, blue: 0.12)
        case .porkbun: Color(red: 0.95, green: 0.37, blue: 0.53)
        case .spaceship: Color(red: 0.50, green: 0.42, blue: 0.98)
        case .dynadot: Color(red: 0.20, green: 0.70, blue: 0.92)
        case .nameSilo: Color(red: 0.14, green: 0.72, blue: 0.55)
        case .gandi: Color(red: 0.42, green: 0.38, blue: 0.92)
        case .goDaddy: Color(red: 0.12, green: 0.72, blue: 0.63)
        }
    }

    var credentialURL: URL? {
        let value: String
        switch self {
        case .nameDotCom: value = "https://www.name.com/account/settings/api"
        case .namecheap: value = "https://ap.www.namecheap.com/settings/tools/apiaccess/"
        case .porkbun: value = "https://porkbun.com/account/api"
        case .spaceship: value = "https://www.spaceship.com/application/api-manager/"
        case .dynadot: value = "https://www.dynadot.com/account/domain/setting/api.html"
        case .nameSilo: value = "https://www.namesilo.com/account/api-manager"
        case .gandi: value = "https://admin.gandi.net/organizations"
        case .goDaddy: value = "https://developer.godaddy.com/keys"
        }
        return URL(string: value)
    }

    var apiDescription: String {
        switch self {
        case .nameDotCom: "Domains, DNS, renewals, transfers and privacy"
        case .namecheap: "Domains, DNS, contacts, renewals and transfers"
        case .porkbun: "Domains, DNS, SSL, forwarding and marketplace"
        case .spaceship: "Domains, contacts, DNS and nameservers"
        case .dynadot: "Domains, DNS, renewals, auctions and aftermarket"
        case .nameSilo: "Domains, DNS, renewals, contacts and transfers"
        case .gandi: "Domains, LiveDNS, certificates, mail and billing"
        case .goDaddy: "Domains, DNS, renewals, privacy and transfers"
        }
    }

    var dashboardURL: URL? {
        let value: String
        switch self {
        case .nameDotCom: value = "https://www.name.com/account/domain"
        case .namecheap: value = "https://ap.www.namecheap.com/domains/domainlist"
        case .porkbun: value = "https://porkbun.com/account/domainsSpeedy"
        case .spaceship: value = "https://www.spaceship.com/application/domain-list-application/"
        case .dynadot: value = "https://www.dynadot.com/account/domain/name/list.html"
        case .nameSilo: value = "https://www.namesilo.com/account_domains.php"
        case .gandi: value = "https://admin.gandi.net/domain"
        case .goDaddy: value = "https://dcc.godaddy.com/portfolio"
        }
        return URL(string: value)
    }
}

struct RegistrarAccount: Codable, Identifiable, Equatable {
    let id: UUID
    var provider: RegistrarProvider
    var name: String
    var primaryCredential: String
    var secondaryCredential: String?
    var metadata: [String: String]

    init(
        id: UUID = UUID(),
        provider: RegistrarProvider,
        name: String,
        primaryCredential: String,
        secondaryCredential: String? = nil,
        metadata: [String: String] = [:]
    ) {
        self.id = id
        self.provider = provider
        self.name = name
        self.primaryCredential = primaryCredential
        self.secondaryCredential = secondaryCredential
        self.metadata = metadata
    }
}

struct RegistrarDomain: Identifiable, Equatable {
    var id: String { name.lowercased() }
    let name: String
    let status: String?
    let createdAt: Date?
    let expiresAt: Date?
    let autoRenew: Bool?
    let locked: Bool?
    let privacyEnabled: Bool?
    let nameservers: [String]
    let metadata: [String: String]

    var daysUntilExpiry: Int? {
        guard let expiresAt else { return nil }
        return Calendar.current.dateComponents([.day], from: .now, to: expiresAt).day
    }
}

struct RegistrarRawResponse: Equatable {
    let statusCode: Int
    let headers: [String: String]
    let body: String
}

enum RegistrarAPIError: LocalizedError {
    case invalidConfiguration(String)
    case invalidResponse
    case requestFailed(Int, String)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let message): message
        case .invalidResponse: "The registrar returned an invalid response."
        case .requestFailed(let status, let message):
            message.isEmpty ? "Request failed (HTTP \(status))." : "Request failed (HTTP \(status)): \(message)"
        case .decoding(let message): "Could not read the registrar response: \(message)"
        }
    }
}

struct RegistrarMark: View {
    let provider: RegistrarProvider
    var size: CGFloat = 38
    var monochrome = false

    var body: some View {
        Image(provider.logoAssetName)
            .resizable()
            .renderingMode(monochrome || provider.logoNeedsTint ? .template : .original)
            .scaledToFit()
            .foregroundStyle(monochrome ? Color.white : provider.accentColor)
            .frame(width: size * 0.55, height: size * 0.55)
            .frame(width: size, height: size)
            .background((monochrome ? Color.white : provider.accentColor).opacity(monochrome ? 0.10 : 0.13))
            .clipShape(RoundedRectangle(cornerRadius: size * 0.28, style: .continuous))
            .accessibilityLabel(provider.displayName)
    }
}
