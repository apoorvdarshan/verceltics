import Foundation

struct VercelAccount: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    let token: String
    var avatarURL: String?
    
    init(id: UUID = UUID(), name: String, token: String, avatarURL: String? = nil) {
        self.id = id
        self.name = name
        self.token = token
        self.avatarURL = avatarURL
    }
}
