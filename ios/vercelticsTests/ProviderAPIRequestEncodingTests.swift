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

    func testDigitalOceanFirstClassPathsReceiveExactlyOneVersionPrefix() {
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/account"),
            "/v2/account"
        )
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/v2/apps?per_page=200"),
            "/v2/apps?per_page=200"
        )
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .digitalOcean, path: "/v2?example=true"),
            "/v2?example=true"
        )
    }

    func testOtherHostingProviderPathsRemainUnchanged() {
        XCTAssertEqual(
            HostingProviderAPI.normalizedRequestPath(for: .netlify, path: "/sites"),
            "/sites"
        )
    }

    func testAmplifyBranchAndJobPageSizeStaysWithinAWSLimit() {
        XCTAssertEqual(HostingProviderAPI.awsAmplifyBranchAndJobPageSize, 50)
    }

    func testAmplifyEndpointUsesExactAWSHostForValidRegion() throws {
        let endpoint = try HostingProviderAPI.awsAmplifyEndpoint(
            region: "ap-southeast-2",
            path: "/apps?maxResults=1"
        )

        XCTAssertEqual(endpoint.host, "amplify.ap-southeast-2.amazonaws.com")
        XCTAssertEqual(endpoint.url.scheme, "https")
        XCTAssertEqual(endpoint.url.host, endpoint.host)
        XCTAssertNil(endpoint.url.user)
        XCTAssertNil(endpoint.url.password)
        XCTAssertNil(endpoint.url.port)
        XCTAssertEqual(endpoint.url.path, "/apps")
        XCTAssertEqual(endpoint.url.query, "maxResults=1")
        XCTAssertEqual(
            endpoint.url.absoluteString,
            "https://amplify.ap-southeast-2.amazonaws.com/apps?maxResults=1"
        )
    }

    func testAmplifyEndpointRejectsRegionsThatCouldChangeAuthority() {
        for region in [
            "evil.example/",
            "us-east-1.evil.example",
            "us-east-1@evil.example",
            "us-east-1.amazonaws.com@evil.example/",
            "us_east_1",
            "US-EAST-1",
            "us-east-0",
            "us-east-01",
            "us-gov-west-1",
            "cn-north-1",
            "zz-east-1",
            "us-east-1\n",
        ] {
            XCTAssertThrowsError(
                try HostingProviderAPI.awsAmplifyEndpoint(region: region, path: "/apps"),
                region
            )
        }
    }

    func testRailwayPaginationRequiresAndDeduplicatesContinuationCursors() throws {
        var pagination = RailwayPaginationGuard()
        XCTAssertEqual(
            try pagination.continuation(hasNextPage: true, endCursor: "next-page"),
            "next-page"
        )
        XCTAssertThrowsError(
            try pagination.continuation(hasNextPage: true, endCursor: "next-page")
        )

        var missingCursor = RailwayPaginationGuard()
        XCTAssertThrowsError(
            try missingCursor.continuation(hasNextPage: true, endCursor: nil)
        )
        XCTAssertNil(try missingCursor.continuation(hasNextPage: false, endCursor: nil))
    }

    func testRailwayPaginationEnforcesMaximumPages() throws {
        var pagination = RailwayPaginationGuard(maximumPages: 1)
        XCTAssertEqual(try pagination.continuation(hasNextPage: true, endCursor: "one"), "one")
        XCTAssertThrowsError(
            try pagination.continuation(hasNextPage: false, endCursor: nil)
        )
    }

    func testFaviconHostSafetyRejectsPrivateAndReservedAddresses() {
        for address in [
            "127.0.0.1", "10.1.2.3", "100.64.0.1", "169.254.1.1", "172.31.1.1",
            "192.168.1.1", "198.18.0.1", "203.0.113.1", "::1", "fc00::1", "fe80::1",
            "::ffff:192.168.1.1", "64:ff9b::c0a8:0101",
        ] {
            XCTAssertFalse(FaviconHostSafety.isPublicIPAddress(address), address)
        }
    }

    func testFaviconHostSafetyAcceptsPublicAddresses() {
        XCTAssertTrue(FaviconHostSafety.isPublicIPAddress("1.1.1.1"))
        XCTAssertTrue(FaviconHostSafety.isPublicIPAddress("2606:4700:4700::1111"))
    }
}
