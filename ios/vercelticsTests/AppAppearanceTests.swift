import XCTest
@testable import verceltics

@MainActor
final class AppAppearanceTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "AppAppearanceTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testMissingPreferenceDefaultsToSystem() {
        let store = AppAppearanceStore(defaults: defaults)

        XCTAssertEqual(store.selection, .system)
        XCTAssertNil(store.selection.preferredColorScheme)
    }

    func testUnknownPreferenceSafelyFallsBackAndIsRemoved() {
        defaults.set("sepia", forKey: AppAppearanceStore.storageKey)

        let store = AppAppearanceStore(defaults: defaults)

        XCTAssertEqual(store.selection, .system)
        XCTAssertNil(defaults.string(forKey: AppAppearanceStore.storageKey))
    }

    func testSelectionPersistsAcrossStoreInstances() {
        let store = AppAppearanceStore(defaults: defaults)
        store.select(.light)

        let restored = AppAppearanceStore(defaults: defaults)

        XCTAssertEqual(restored.selection, .light)
        XCTAssertEqual(defaults.string(forKey: AppAppearanceStore.storageKey), "light")
    }
}
