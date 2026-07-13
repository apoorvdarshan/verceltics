import Foundation

enum AccountProvider: String, Codable, CaseIterable, Identifiable, Sendable {
    case vercel
    case cloudflare
    case netlify
    case railway
    case render
    case digitalOcean
    case heroku
    case fly
    case firebase
    case awsAmplify

    var id: Self { self }

    var displayName: String {
        switch self {
        case .vercel: "Vercel"
        case .cloudflare: "Cloudflare"
        case .netlify: "Netlify"
        case .railway: "Railway"
        case .render: "Render"
        case .digitalOcean: "DigitalOcean"
        case .heroku: "Heroku"
        case .fly: "Fly.io"
        case .firebase: "Firebase"
        case .awsAmplify: "AWS Amplify"
        }
    }

    var isGenericHostingProvider: Bool {
        self != .vercel && self != .cloudflare
    }
}

enum CloudflareAuthenticationMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case globalAPIKey
    case apiToken

    var id: Self { self }

    var displayName: String {
        switch self {
        case .globalAPIKey: "Global key"
        case .apiToken: "API token"
        }
    }
}

struct VercelAccount: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    var token: String
    var avatarURL: String?
    var provider: AccountProvider
    var email: String?
    var providerUserId: String?
    var cloudflareAuthenticationMode: CloudflareAuthenticationMode?
    var providerMetadata: [String: String]
    
    init(
        id: UUID = UUID(),
        name: String,
        token: String,
        avatarURL: String? = nil,
        provider: AccountProvider = .vercel,
        email: String? = nil,
        providerUserId: String? = nil,
        cloudflareAuthenticationMode: CloudflareAuthenticationMode? = nil,
        providerMetadata: [String: String] = [:]
    ) {
        self.id = id
        self.name = name
        self.token = token
        self.avatarURL = avatarURL
        self.provider = provider
        self.email = email
        self.providerUserId = providerUserId
        self.cloudflareAuthenticationMode = cloudflareAuthenticationMode
        self.providerMetadata = providerMetadata
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, token, avatarURL, provider, email, providerUserId, cloudflareAuthenticationMode, providerMetadata
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        name = try container.decode(String.self, forKey: .name)
        token = try container.decode(String.self, forKey: .token)
        avatarURL = try container.decodeIfPresent(String.self, forKey: .avatarURL)
        provider = try container.decodeIfPresent(AccountProvider.self, forKey: .provider) ?? .vercel
        email = try container.decodeIfPresent(String.self, forKey: .email)
        providerUserId = try container.decodeIfPresent(String.self, forKey: .providerUserId)
        cloudflareAuthenticationMode = try container.decodeIfPresent(
            CloudflareAuthenticationMode.self,
            forKey: .cloudflareAuthenticationMode
        )
        providerMetadata = try container.decodeIfPresent([String: String].self, forKey: .providerMetadata) ?? [:]
        if provider == .cloudflare, cloudflareAuthenticationMode == nil {
            cloudflareAuthenticationMode = .globalAPIKey
        }
    }
}
