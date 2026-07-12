import Foundation

enum AccountProvider: String, Codable, CaseIterable, Identifiable {
    case vercel
    case cloudflare

    var id: Self { self }

    var displayName: String {
        switch self {
        case .vercel: "Vercel"
        case .cloudflare: "Cloudflare"
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
    
    init(
        id: UUID = UUID(),
        name: String,
        token: String,
        avatarURL: String? = nil,
        provider: AccountProvider = .vercel,
        email: String? = nil,
        providerUserId: String? = nil
    ) {
        self.id = id
        self.name = name
        self.token = token
        self.avatarURL = avatarURL
        self.provider = provider
        self.email = email
        self.providerUserId = providerUserId
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, token, avatarURL, provider, email, providerUserId
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
    }
}
