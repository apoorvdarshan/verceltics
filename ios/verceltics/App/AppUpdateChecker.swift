import Foundation
import Observation

@Observable
@MainActor
final class AppUpdateChecker {
    private static let appID = "6761645656"
    private static let fallbackAppStoreURL = URL(string: "https://apps.apple.com/us/app/verceltics/id6761645656")!

    var latestVersion: String?
    var appStoreURL = fallbackAppStoreURL
    var isChecking = false
    var hasChecked = false
    var errorMessage: String?

    private var lastCheckedAt: Date?

    var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.1"
    }

    var isUpdateAvailable: Bool {
        guard let latestVersion else { return false }
        return Self.compareVersions(latestVersion, currentVersion) == .orderedDescending
    }

    func checkForUpdates(force: Bool = false) async {
        guard !isChecking else { return }

        if !force, let lastCheckedAt, Date().timeIntervalSince(lastCheckedAt) < 60 * 60 {
            return
        }

        isChecking = true
        errorMessage = nil

        defer {
            isChecking = false
            hasChecked = true
            lastCheckedAt = Date()
        }

        do {
            var components = URLComponents(string: "https://itunes.apple.com/lookup")!
            components.queryItems = [
                URLQueryItem(name: "id", value: Self.appID),
                URLQueryItem(name: "country", value: "us")
            ]

            let (data, response) = try await URLSession.shared.data(from: components.url!)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }

            let lookup = try JSONDecoder().decode(AppStoreLookupResponse.self, from: data)
            guard let result = lookup.results.first else {
                throw URLError(.cannotParseResponse)
            }

            latestVersion = result.version
            appStoreURL = URL(string: result.trackViewURL) ?? Self.fallbackAppStoreURL
        } catch {
            errorMessage = "Unable to check right now"
        }
    }

    private static func compareVersions(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let lhsParts = versionParts(lhs)
        let rhsParts = versionParts(rhs)
        let maxCount = max(lhsParts.count, rhsParts.count)

        for index in 0..<maxCount {
            let left = index < lhsParts.count ? lhsParts[index] : 0
            let right = index < rhsParts.count ? rhsParts[index] : 0

            if left > right { return .orderedDescending }
            if left < right { return .orderedAscending }
        }

        return .orderedSame
    }

    private static func versionParts(_ version: String) -> [Int] {
        version.split(separator: ".").map { part in
            let digits = part.prefix { $0.isNumber }
            return Int(digits) ?? 0
        }
    }
}

private struct AppStoreLookupResponse: Decodable {
    let results: [AppStoreLookupResult]
}

private struct AppStoreLookupResult: Decodable {
    let version: String
    let trackViewURL: String

    enum CodingKeys: String, CodingKey {
        case version
        case trackViewURL = "trackViewUrl"
    }
}
