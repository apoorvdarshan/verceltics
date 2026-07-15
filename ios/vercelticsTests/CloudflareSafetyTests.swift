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

    func testPagesBLAKE3MatchesOfficialVectors() {
        XCTAssertEqual(
            CloudflarePagesAssetHasher.blake3Hex(Data()),
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262"
        )
        XCTAssertEqual(
            CloudflarePagesAssetHasher.blake3Hex(Data("abc".utf8)),
            "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85"
        )

        let multiChunk = Data((0..<1_025).map { UInt8($0 % 251) })
        XCTAssertEqual(
            CloudflarePagesAssetHasher.blake3Hex(multiChunk),
            "d00278ae47eb27b34faecf67b4fe263f82d5412916c1ffd97c8cb7fb814b8444"
        )
    }

    func testPagesAssetHashUsesBase64ContentAndExtension() {
        XCTAssertEqual(
            CloudflarePagesAssetHasher.assetHash(data: Data("hello".utf8), fileExtension: "txt"),
            "f0b3413d4cabb000327fad369003d6a5"
        )
    }

    func testPagesUploadTokenClaimsRespectPlanLimitAndSafetyCap() throws {
        func token(maximum: Int) throws -> String {
            let data = try JSONSerialization.data(withJSONObject: ["max_file_count_allowed": maximum])
            let payload = data.base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
            return "header.\(payload).signature"
        }

        XCTAssertEqual(CloudflarePagesUploadTokenClaims.maximumFileCount(from: try token(maximum: 50_000)), 50_000)
        XCTAssertEqual(CloudflarePagesUploadTokenClaims.maximumFileCount(from: try token(maximum: 500_000)), 100_000)
        XCTAssertEqual(CloudflarePagesUploadTokenClaims.maximumFileCount(from: "invalid"), 20_000)
    }

    func testPagesMultipartBodyPreservesBinaryFileData() {
        var multipart = CloudflarePagesMultipartBody(boundary: "test-boundary")
        multipart.appendText(name: "manifest", value: #"{"/index.html":"hash"}"#)
        multipart.appendFile(
            name: "_worker.bundle",
            fileName: "_worker.bundle",
            contentType: "application/octet-stream",
            data: Data([0x00, 0xFF, 0x10])
        )
        let result = multipart.finalized()
        let text = String(decoding: result.data, as: UTF8.self)

        XCTAssertEqual(result.contentType, "multipart/form-data; boundary=test-boundary")
        XCTAssertTrue(text.contains("name=\"manifest\""))
        XCTAssertTrue(text.contains("filename=\"_worker.bundle\""))
        XCTAssertTrue(result.data.contains(Data([0x00, 0xFF, 0x10])))
        XCTAssertTrue(text.hasSuffix("--test-boundary--\r\n"))
    }

    func testPagesUploadProgressIncludesLiveCounts() {
        XCTAssertEqual(
            CloudflarePagesDirectUploadProgress(stage: .hashing, completed: 3, total: 9).message,
            "Preparing files · 3 of 9"
        )
        XCTAssertEqual(
            CloudflarePagesDirectUploadProgress(stage: .uploading, completed: 6, total: 9).message,
            "Uploading assets · 6 of 9"
        )
    }
}
