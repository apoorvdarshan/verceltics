import Foundation

nonisolated enum PublicIPv4LookupError: LocalizedError, Equatable {
    case invalidResponse
    case requestFailed(Int)
    case invalidAddress

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            "The public IP service returned an invalid response."
        case .requestFailed(let statusCode):
            "The public IP service returned HTTP \(statusCode)."
        case .invalidAddress:
            "A valid public IPv4 address could not be detected."
        }
    }
}

enum PublicIPv4Lookup {
    nonisolated static let endpoint = URL(string: "https://api.ipify.org")!
    nonisolated static let maximumResponseBytes = 64

    static func resolve(using session: URLSession? = nil) async throws -> String {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        request.setValue("text/plain", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")

        let (data, response) = try await ProviderRequestSecurity.data(
            for: request,
            using: session,
            maximumResponseBytes: maximumResponseBytes
        )
        guard let http = response as? HTTPURLResponse else {
            throw PublicIPv4LookupError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw PublicIPv4LookupError.requestFailed(http.statusCode)
        }
        guard let rawAddress = String(data: data, encoding: .utf8),
              let address = normalizedPublicIPv4(rawAddress) else {
            throw PublicIPv4LookupError.invalidAddress
        }
        return address
    }

    nonisolated static func normalizedPublicIPv4(_ rawValue: String) -> String? {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }

        var octets: [Int] = []
        octets.reserveCapacity(4)
        for part in parts {
            guard !part.isEmpty,
                  part.unicodeScalars.allSatisfy({ (48...57).contains(Int($0.value)) }),
                  let octet = Int(part),
                  (0...255).contains(octet) else {
                return nil
            }
            octets.append(octet)
        }

        guard isPublic(octets) else { return nil }
        return octets.map(String.init).joined(separator: ".")
    }

    nonisolated private static func isPublic(_ octets: [Int]) -> Bool {
        let first = octets[0]
        let second = octets[1]
        let third = octets[2]

        if first == 0 || first == 10 || first == 127 || first >= 224 { return false }
        if first == 100 && (64...127).contains(second) { return false }
        if first == 169 && second == 254 { return false }
        if first == 172 && (16...31).contains(second) { return false }
        if first == 192 && second == 168 { return false }
        if first == 192 && second == 0 && [0, 2].contains(third) { return false }
        if first == 192 && second == 88 && third == 99 { return false }
        if first == 198 && [18, 19].contains(second) { return false }
        if first == 198 && second == 51 && third == 100 { return false }
        if first == 203 && second == 0 && third == 113 { return false }
        return true
    }
}
