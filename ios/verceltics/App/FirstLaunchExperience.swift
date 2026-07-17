import Foundation
import Observation

/// Owns only the one-time welcome presentation. Pro access continues to be
/// decided exclusively by `PaywallManager`.
@Observable
@MainActor
final class FirstLaunchExperienceStore {
    static let completionKey = "onboarding.firstLaunchWelcome.completed.v1"

    private let defaults: UserDefaults
    private(set) var hasCompletedWelcome: Bool

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.hasCompletedWelcome = defaults.bool(forKey: Self.completionKey)
    }

    static func shouldPresentWelcome(
        hasCompletedWelcome: Bool,
        hasAnyConnection: Bool,
        hasActiveSubscription: Bool
    ) -> Bool {
        !hasCompletedWelcome && !hasAnyConnection && !hasActiveSubscription
    }

    func shouldPresentWelcome(
        hasAnyConnection: Bool,
        hasActiveSubscription: Bool
    ) -> Bool {
        Self.shouldPresentWelcome(
            hasCompletedWelcome: hasCompletedWelcome,
            hasAnyConnection: hasAnyConnection,
            hasActiveSubscription: hasActiveSubscription
        )
    }

    /// Existing connected or subscribed users should never be routed through
    /// a newly introduced first-launch screen after updating the app.
    func migrateIfNeeded(
        hasAnyConnection: Bool,
        hasActiveSubscription: Bool
    ) {
        guard hasAnyConnection || hasActiveSubscription else { return }
        completeWelcome()
    }

    func completeWelcome() {
        guard !hasCompletedWelcome else { return }
        defaults.set(true, forKey: Self.completionKey)
        hasCompletedWelcome = true
    }
}
