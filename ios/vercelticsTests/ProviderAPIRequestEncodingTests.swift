import XCTest
@testable import verceltics

final class ProviderAPIRequestEncodingTests: XCTestCase {
    func testPathParameterEncodesSeparatorsAndUnicode() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.pathParameter("folder/a b/✓", allowReserved: false),
            "folder%2Fa%20b%2F%E2%9C%93"
        )
    }

    func testReservedPathExpansionKeepsPathSeparatorsButBlocksQueryAndFragment() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.pathParameter("folder/a b?token=x#part", allowReserved: true),
            "folder/a%20b%3Ftoken=x%23part"
        )
    }

    func testAWSQueryEncodingUsesOnlyRFC3986UnreservedCharacters() {
        XCTAssertEqual(
            ProviderAPIRequestEncoding.awsQueryComponent("a+b /?=&~"),
            "a%2Bb%20%2F%3F%3D%26~"
        )
    }
}
