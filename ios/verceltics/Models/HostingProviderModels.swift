import Foundation

struct HostingProviderProfile: Equatable {
    let id: String
    let name: String
    let email: String?
    let avatarURL: String?
}

struct HostingResource: Identifiable, Equatable {
    let id: String
    let name: String
    let subtitle: String?
    let url: String?
    let status: String?
    let region: String?
    let kind: String?
    let updatedAt: Date?
    let metadata: [String: String]
}

struct HostingDeployment: Identifiable, Equatable {
    let id: String
    let title: String
    let status: String
    let createdAt: Date?
    let url: String?
    let branch: String?
    let commitMessage: String?
    let metadata: [String: String]
}

struct HostingRawResponse: Equatable {
    let statusCode: Int
    let headers: [String: String]
    let body: String
}

enum HostingProviderAPIError: LocalizedError {
    case invalidConfiguration(String)
    case invalidResponse
    case requestFailed(Int, String)
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let message): message
        case .invalidResponse: "The provider returned an invalid response."
        case .requestFailed(let status, let message):
            message.isEmpty ? "Request failed (HTTP \(status))." : "Request failed (HTTP \(status)): \(message)"
        case .decoding(let message): "Could not read the provider response: \(message)"
        }
    }
}
