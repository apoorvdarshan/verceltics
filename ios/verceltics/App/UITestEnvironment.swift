#if DEBUG
import Foundation

/// Debug-only test switches. Release builds compile none of this file.
///
/// XCUITests launch the real app with `-uiTesting` so it starts from a known,
/// offline state: in-memory accounts, no RevenueCat or App Store requests, and
/// only the welcome screens a test asks for. The UI-test switches work only in
/// the iOS Simulator, so they cannot reset or seed data on a device.
nonisolated enum UITestEnvironment {
    /// Turns on UI-test mode.
    static let enabledArgument = "-uiTesting"
    /// Clears this app's UserDefaults before any store reads them.
    static let resetArgument = "-uiTestingReset"
    /// Starts with the one-time 2.1 developer introduction still pending.
    /// Without it, UI tests start after that introduction was closed.
    static let developerWelcomeArgument = "-uiTestingDeveloperWelcome"
    /// Seeds one in-memory PageSpeed site account with a fresh snapshot.
    static let sampleSiteArgument = "-uiTestingSampleSite"

    static var isUITesting: Bool {
#if targetEnvironment(simulator)
        ProcessInfo.processInfo.arguments.contains(enabledArgument)
#else
        false
#endif
    }

    /// True while XCTest hosts the unit tests inside the app process.
    static var isHostingUnitTests: Bool {
        let environment = ProcessInfo.processInfo.environment
        return ["XCTestConfigurationFilePath", "XCTestBundlePath", "XCTestSessionIdentifier"]
            .contains { environment[$0] != nil }
    }

    /// Accounts stay in memory, and RevenueCat and App Store update checks stay
    /// off, so test runs never depend on the network or a developer's Keychain.
    static var isolatesExternalServices: Bool {
        isUITesting || isHostingUnitTests
    }

    static var usesSampleSite: Bool {
        isUITesting && ProcessInfo.processInfo.arguments.contains(sampleSiteArgument)
    }

    /// Runs before any store reads UserDefaults.
    @MainActor
    static func prepare(defaults: UserDefaults = .standard) {
        guard isUITesting else { return }
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains(resetArgument), let domain = Bundle.main.bundleIdentifier {
            defaults.removePersistentDomain(forName: domain)
        }
        if arguments.contains(developerWelcomeArgument) {
            DeveloperWelcomeStore.prepareForUITests(pending: true, defaults: defaults)
        } else if !DeveloperWelcomeStore.hasSavedState(in: defaults) {
            DeveloperWelcomeStore.prepareForUITests(pending: false, defaults: defaults)
        }
    }
}
#endif
