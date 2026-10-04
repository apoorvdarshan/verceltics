import Foundation
import Observation

/// The 2.1 introduction stays pending across launches and later updates until
/// the user explicitly closes it. Opening community links never completes it.
@Observable
@MainActor
final class DeveloperWelcomeStore {
    static let releaseVersion = "2.1"
    static let storageKey = "welcome.developer.2.1.state"

    private enum Status: String {
        case pending
        case closed
    }

    private let defaults: UserDefaults
    private(set) var isPresented: Bool

    init(
        defaults: UserDefaults = .standard,
        appVersion: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    ) {
        self.defaults = defaults
        let savedStatus = defaults.string(forKey: Self.storageKey).flatMap(Status.init(rawValue:))

        if savedStatus == .closed {
            isPresented = false
        } else if savedStatus == .pending || appVersion == Self.releaseVersion {
            // Persist eligibility, not completion, so an unfinished 2.1
            // introduction also survives an update to a later app version.
            defaults.set(Status.pending.rawValue, forKey: Self.storageKey)
            isPresented = true
        } else {
            isPresented = false
        }
    }

    func close() {
        defaults.set(Status.closed.rawValue, forKey: Self.storageKey)
        isPresented = false
    }
}
