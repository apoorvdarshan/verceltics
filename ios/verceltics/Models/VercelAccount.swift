import Foundation

struct VercelAccount: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    let token: String
    
    init(id: UUID = UUID(), name: String, token: String) {
        self.id = id
        self.name = name
        self.token = token
    }
}
