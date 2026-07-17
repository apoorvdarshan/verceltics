import XCTest
@testable import verceltics

final class ProAccessGateTests: XCTestCase {
    private enum Route: Hashable {
        case project(String)
        case domain(String)
    }

    func testProOwnerContinuesImmediatelyWithoutPresentingPaywall() {
        var gate = ProAccessGate<Route>()

        let route = gate.request(.project("project-1"), hasProAccess: true)

        XCTAssertEqual(route, .project("project-1"))
        XCTAssertFalse(gate.isPaywallPresented)
        XCTAssertNil(gate.pendingRoute)
    }

    func testFreeUserStoresIntentAndPresentsPaywall() {
        var gate = ProAccessGate<Route>()

        let route = gate.request(.domain("example.com"), hasProAccess: false)

        XCTAssertNil(route)
        XCTAssertTrue(gate.isPaywallPresented)
        XCTAssertEqual(gate.pendingRoute, .domain("example.com"))
    }

    func testDismissWithoutPurchaseClearsPendingIntent() {
        var gate = ProAccessGate<Route>()
        _ = gate.request(.project("project-1"), hasProAccess: false)

        let route = gate.resumeAfterDismiss(hasProAccess: false)

        XCTAssertNil(route)
        XCTAssertNil(gate.pendingRoute)
    }

    func testPurchaseResumesOnlyTheOriginallyRequestedIntent() {
        var gate = ProAccessGate<Route>()
        _ = gate.request(.domain("example.com"), hasProAccess: false)

        let route = gate.resumeAfterDismiss(hasProAccess: true)

        XCTAssertEqual(route, .domain("example.com"))
        XCTAssertNil(gate.pendingRoute)
    }
}
