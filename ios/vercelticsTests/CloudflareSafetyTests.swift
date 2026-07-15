import XCTest
@testable import verceltics

final class CloudflareSafetyTests: XCTestCase {
    func testPaginationGuardRejectsRepeatedNonemptyPage() throws {
        var guardrail = CloudflarePaginationGuard()
        try guardrail.record(batchCount: 2, signature: 42)

        XCTAssertThrowsError(try guardrail.record(batchCount: 2, signature: 42)) { error in
            XCTAssertEqual(
                error as? CloudflareAPIError,
                .invalidRequest("Cloudflare repeated a results page, so loading stopped safely.")
            )
        }
    }

    func testPaginationGuardAllowsTerminalEmptyPage() throws {
        var guardrail = CloudflarePaginationGuard()
        try guardrail.record(batchCount: 1, signature: 7)
        XCTAssertNoThrow(try guardrail.record(batchCount: 0, signature: nil))
    }

    func testPaginationGuardBoundsTotalItems() {
        var guardrail = CloudflarePaginationGuard()

        XCTAssertThrowsError(try guardrail.record(batchCount: 100_001, signature: 1)) { error in
            XCTAssertEqual(
                error as? CloudflareAPIError,
                .invalidRequest("Cloudflare returned too many paginated results. Narrow the request and try again.")
            )
        }
    }

    func testOrganizationFallbackIdentifierIsStableAcrossDecodes() throws {
        let payload = Data(#"{"name":"Example Org","roles":["Admin"],"permissions":["dns:read"]}"#.utf8)

        let first = try JSONDecoder().decode(CloudflareUser.Organization.self, from: payload)
        let second = try JSONDecoder().decode(CloudflareUser.Organization.self, from: payload)

        XCTAssertEqual(first.id, second.id)
        XCTAssertTrue(first.id.hasPrefix("organization-"))
    }
}
