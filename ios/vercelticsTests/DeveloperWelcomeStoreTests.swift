import XCTest
@testable import verceltics

@MainActor
final class DeveloperWelcomeStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "DeveloperWelcomeStoreTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testReleaseVersionPresentsTheIntroductionAndRemembersItIsPending() {
        let store = DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion)

        XCTAssertTrue(store.isPresented)
        XCTAssertEqual(defaults.string(forKey: DeveloperWelcomeStore.storageKey), "pending")
    }

    func testClosingDismissesTheIntroductionPermanently() {
        let store = DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion)
        store.close()

        XCTAssertFalse(store.isPresented)
        let relaunched = DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion)
        XCTAssertFalse(relaunched.isPresented)
    }

    func testAnUnfinishedIntroductionSurvivesAnUpdate() {
        _ = DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion)

        let updated = DeveloperWelcomeStore(defaults: defaults, appVersion: "9.9")

        XCTAssertTrue(updated.isPresented)
    }

    func testOtherVersionsDoNotIntroduceItOnAFreshInstall() {
        let store = DeveloperWelcomeStore(defaults: defaults, appVersion: "9.9")

        XCTAssertFalse(store.isPresented)
        XCTAssertNil(defaults.string(forKey: DeveloperWelcomeStore.storageKey))
    }

    func testUnknownSavedStateFallsBackToTheVersionRule() {
        defaults.set("unexpected", forKey: DeveloperWelcomeStore.storageKey)

        XCTAssertFalse(DeveloperWelcomeStore(defaults: defaults, appVersion: "9.9").isPresented)
        XCTAssertTrue(DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion).isPresented)
    }

    func testUITestPreparationControlsTheIntroductionForAnyVersion() {
        XCTAssertFalse(DeveloperWelcomeStore.hasSavedState(in: defaults))

        DeveloperWelcomeStore.prepareForUITests(pending: true, defaults: defaults)
        XCTAssertTrue(DeveloperWelcomeStore.hasSavedState(in: defaults))
        XCTAssertTrue(DeveloperWelcomeStore(defaults: defaults, appVersion: "9.9").isPresented)

        DeveloperWelcomeStore.prepareForUITests(pending: false, defaults: defaults)
        XCTAssertFalse(DeveloperWelcomeStore(defaults: defaults, appVersion: DeveloperWelcomeStore.releaseVersion).isPresented)
    }
}
