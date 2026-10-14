import XCTest
@testable import verceltics

/// Pro access and update checks without RevenueCat or the App Store, as in UI
/// tests. Hosted unit tests never configure RevenueCat in the app process.
@MainActor
final class StoreServicesIsolationTests: XCTestCase {
    func testWithoutRevenueCatProStaysLockedAndPlansAreUnavailable() async {
        let paywall = PaywallManager.withoutRevenueCatForTesting()

        XCTAssertTrue(paywall.hasCheckedEntitlements)
        XCTAssertFalse(paywall.hasActiveSubscription)
        XCTAssertFalse(paywall.isLoading)

        await paywall.loadProducts()
        XCTAssertTrue(paywall.packages.isEmpty)
        XCTAssertFalse(paywall.isLoading)
        XCTAssertEqual(paywall.error, "Purchases are unavailable in this build.")

        await paywall.checkEntitlements()
        XCTAssertTrue(paywall.hasCheckedEntitlements)
        XCTAssertFalse(paywall.hasActiveSubscription)
        XCTAssertTrue(paywall.purchasedProductIDs.isEmpty)
    }

    func testRestoreWithoutRevenueCatReportsAnErrorAndGrantsNothing() async {
        let paywall = PaywallManager.withoutRevenueCatForTesting()

        await paywall.restorePurchases()

        XCTAssertEqual(paywall.error, "Purchases are unavailable in this build.")
        XCTAssertFalse(paywall.hasActiveSubscription)
    }

    func testTipJarWithoutRevenueCatShowsTipsAsUnavailable() async {
        let tips = TipStore()

        await tips.loadProducts()

        XCTAssertTrue(tips.products.isEmpty)
        XCTAssertTrue(tips.loadFailed)
        XCTAssertFalse(tips.isLoading)
    }

    func testDisabledUpdateCheckNeverContactsTheAppStore() async {
        let checker = AppUpdateChecker()
        checker.checksAppStore = false

        await checker.checkForUpdates(force: true)

        XCTAssertFalse(checker.hasChecked)
        XCTAssertFalse(checker.isChecking)
        XCTAssertNil(checker.latestVersion)
        XCTAssertNil(checker.errorMessage)
    }

    func testUpdateAvailabilityComparesVersionsNumerically() throws {
        let checker = AppUpdateChecker()
        checker.checksAppStore = false
        let current = checker.currentVersion
        var parts = current.split(separator: ".").compactMap { Int($0) }
        let last = try XCTUnwrap(parts.popLast(), "current version \(current) is not numeric")
        let prefix = parts.map(String.init)

        checker.latestVersion = (prefix + ["\(last + 9)"]).joined(separator: ".")
        XCTAssertTrue(checker.isUpdateAvailable, "\(checker.latestVersion ?? "") is newer than \(current)")

        checker.latestVersion = "\(current).1"
        XCTAssertTrue(checker.isUpdateAvailable)

        checker.latestVersion = current
        XCTAssertFalse(checker.isUpdateAvailable)

        checker.latestVersion = "\(current).0"
        XCTAssertFalse(checker.isUpdateAvailable, "a trailing .0 is the same version")

        checker.latestVersion = "0.1"
        XCTAssertFalse(checker.isUpdateAvailable)

        checker.latestVersion = nil
        XCTAssertFalse(checker.isUpdateAvailable)
    }
}
