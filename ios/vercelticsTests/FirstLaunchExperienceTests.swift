import XCTest
@testable import verceltics

@MainActor
final class FirstLaunchExperienceTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "FirstLaunchExperienceTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testFreshInstallPresentsWelcome() {
        let store = FirstLaunchExperienceStore(defaults: defaults)

        XCTAssertTrue(store.shouldPresentWelcome(
            hasAnyConnection: false,
            hasActiveSubscription: false
        ))
    }

    func testContinuePersistsAcrossStoreInstances() {
        let store = FirstLaunchExperienceStore(defaults: defaults)
        store.completeWelcome()

        let restored = FirstLaunchExperienceStore(defaults: defaults)

        XCTAssertTrue(restored.hasCompletedWelcome)
        XCTAssertFalse(restored.shouldPresentWelcome(
            hasAnyConnection: false,
            hasActiveSubscription: false
        ))
    }

    func testExistingConnectionSkipsAndMigratesWelcome() {
        let store = FirstLaunchExperienceStore(defaults: defaults)
        store.migrateIfNeeded(hasAnyConnection: true, hasActiveSubscription: false)

        XCTAssertTrue(store.hasCompletedWelcome)
        XCTAssertFalse(store.shouldPresentWelcome(
            hasAnyConnection: true,
            hasActiveSubscription: false
        ))
    }

    func testActiveSubscriberWithoutConnectionsSkipsAndMigratesWelcome() {
        let store = FirstLaunchExperienceStore(defaults: defaults)
        store.migrateIfNeeded(hasAnyConnection: false, hasActiveSubscription: true)

        XCTAssertTrue(store.hasCompletedWelcome)
        XCTAssertFalse(store.shouldPresentWelcome(
            hasAnyConnection: false,
            hasActiveSubscription: true
        ))
    }

    func testWelcomeCompletionNeverGrantsSubscription() {
        XCTAssertFalse(FirstLaunchExperienceStore.shouldPresentWelcome(
            hasCompletedWelcome: true,
            hasAnyConnection: false,
            hasActiveSubscription: false
        ))
    }
}
