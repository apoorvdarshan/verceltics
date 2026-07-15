import XCTest
@testable import verceltics

final class RegistrarAPITests: XCTestCase {
    func testPorkbunListAllUsesOfficialZeroBasedStartOffset() {
        XCTAssertEqual(RegistrarAPI.porkbunListAllPath(start: 0), "/domain/listAll?start=0")
        XCTAssertEqual(RegistrarAPI.porkbunListAllPath(start: 1_000), "/domain/listAll?start=1000")
        XCTAssertEqual(RegistrarAPI.porkbunListAllPath(start: -1), "/domain/listAll?start=0")
    }

    func testPorkbunPaginationContinuesOnlyAfterAFullProductivePage() {
        XCTAssertEqual(
            RegistrarAPI.porkbunPaginationAction(
                pageItemCount: RegistrarAPI.porkbunPageSize,
                newUniqueDomainCount: RegistrarAPI.porkbunPageSize
            ),
            .loadNextPage
        )
        XCTAssertEqual(
            RegistrarAPI.porkbunPaginationAction(
                pageItemCount: RegistrarAPI.porkbunPageSize - 1,
                newUniqueDomainCount: RegistrarAPI.porkbunPageSize - 1
            ),
            .complete
        )
    }

    func testPorkbunPaginationRejectsARepeatedFullPage() {
        XCTAssertEqual(
            RegistrarAPI.porkbunPaginationAction(
                pageItemCount: RegistrarAPI.porkbunPageSize,
                newUniqueDomainCount: 0
            ),
            .noProgress
        )
    }
}
