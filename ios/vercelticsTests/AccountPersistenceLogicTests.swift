import XCTest
@testable import verceltics

final class AccountPersistenceLogicTests: XCTestCase {
    func testRegistrarCredentialRotationMatchesStableUsername() {
        let original = RegistrarAccount(
            provider: .nameDotCom,
            name: "Apoorv",
            primaryCredential: "old-token",
            metadata: ["username": "ApoorvDarshan"]
        )

        XCTAssertEqual(
            RegistrarStore.matchingAccountIndex(
                in: [original],
                provider: .nameDotCom,
                primaryCredential: "new-token",
                metadata: ["username": " apoorvdarshan "]
            ),
            0
        )
    }

    func testRegistrarCredentialRotationMatchesStableOrganization() {
        let original = RegistrarAccount(
            provider: .gandi,
            name: "Example Organization",
            primaryCredential: "old-token",
            metadata: ["organization": "Example-Org"]
        )

        XCTAssertEqual(
            RegistrarStore.matchingAccountIndex(
                in: [original],
                provider: .gandi,
                primaryCredential: "new-token",
                metadata: ["organization": "example-org"]
            ),
            0
        )
    }

    func testRegistrarWithoutStableIdentityDoesNotMergeRotatedKeys() {
        let original = RegistrarAccount(
            provider: .porkbun,
            name: "Porkbun",
            primaryCredential: "old-key",
            secondaryCredential: "old-secret"
        )

        XCTAssertNil(
            RegistrarStore.matchingAccountIndex(
                in: [original],
                provider: .porkbun,
                primaryCredential: "new-key",
                metadata: [:]
            )
        )
    }

    func testSamePrimaryCredentialStillMatchesWithoutMetadata() {
        let original = RegistrarAccount(
            provider: .porkbun,
            name: "Porkbun",
            primaryCredential: "same-key",
            secondaryCredential: "old-secret"
        )

        XCTAssertEqual(
            RegistrarStore.matchingAccountIndex(
                in: [original],
                provider: .porkbun,
                primaryCredential: "same-key",
                metadata: [:]
            ),
            0
        )
    }
}
