import Foundation
import XCTest
@testable import verceltics

@MainActor
final class RegistrarAPITests: XCTestCase {
    override func tearDown() {
        RegistrarAPIMockURLProtocol.handler = nil
        RegistrarAPIMockURLProtocol.requests = []
        super.tearDown()
    }

    func testNamecheapRejectsInvalidClientIPBeforeSendingRequest() async {
        let api = RegistrarAPI(
            provider: .namecheap,
            primaryCredential: "api-key",
            metadata: ["username": "alice", "clientIP": "192.168.1.5"]
        )

        do {
            _ = try await api.rawRequest(method: "GET", path: "/xml.response", body: nil)
            XCTFail("Expected a non-public ClientIp to be rejected.")
        } catch let error as RegistrarAPIError {
            XCTAssertEqual(
                error.errorDescription,
                "Enter a valid public IPv4 address that is whitelisted in Namecheap."
            )
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testNamecheapSendsCanonicalClientIPOnAPIRequest() async throws {
        RegistrarAPIMockURLProtocol.handler = { request in
            let components = try XCTUnwrap(
                URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)
            )
            let items = components.queryItems ?? []

            XCTAssertEqual(items.filter { $0.name == "ApiUser" }.map(\.value), ["alice"])
            XCTAssertEqual(items.filter { $0.name == "ApiKey" }.map(\.value), ["api-key"])
            XCTAssertEqual(items.filter { $0.name == "UserName" }.map(\.value), ["alice"])
            XCTAssertEqual(items.filter { $0.name == "ClientIp" }.map(\.value), ["8.8.4.4"])
            XCTAssertEqual(items.filter { $0.name == "Command" }.map(\.value), ["namecheap.domains.getList"])
            return (200, Data("{}".utf8))
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let api = RegistrarAPI(
            provider: .namecheap,
            primaryCredential: "api-key",
            metadata: ["username": "alice", "clientIP": "008.008.004.004"]
        )

        let response = try await api.rawRequest(
            method: "GET",
            path: "/xml.response?Command=namecheap.domains.getList",
            body: nil,
            requestSession: session
        )

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(RegistrarAPIMockURLProtocol.requests.count, 1)
    }

    func testNameDotComDoesNotSendConfiguredAllowlistIP() async throws {
        RegistrarAPIMockURLProtocol.handler = { request in
            let components = try XCTUnwrap(
                URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)
            )
            let queryNames = (components.queryItems ?? []).map { $0.name.lowercased() }
            let expectedAuthorization = Data("alice:api-token".utf8).base64EncodedString()

            XCTAssertFalse(queryNames.contains("clientip"))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Basic \(expectedAuthorization)")
            return (200, Data("{}".utf8))
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let api = RegistrarAPI(
            provider: .nameDotCom,
            primaryCredential: "api-token",
            metadata: [
                "username": "alice",
                "clientIP": "8.8.8.8"
            ]
        )

        let response = try await api.rawRequest(
            method: "GET",
            path: "/core/v1/domains?perPage=1",
            body: nil,
            requestSession: session
        )

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(RegistrarAPIMockURLProtocol.requests.count, 1)
    }

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

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RegistrarAPIMockURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class RegistrarAPIMockURLProtocol: URLProtocol {
    private static let stateLock = NSLock()
    nonisolated(unsafe) private static var storedHandler: ((URLRequest) throws -> (Int, Data))?
    nonisolated(unsafe) private static var storedRequests: [URLRequest] = []

    static var handler: ((URLRequest) throws -> (Int, Data))? {
        get { stateLock.withLock { storedHandler } }
        set { stateLock.withLock { storedHandler = newValue } }
    }

    static var requests: [URLRequest] {
        get { stateLock.withLock { storedRequests } }
        set { stateLock.withLock { storedRequests = newValue } }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            Self.stateLock.withLock { Self.storedRequests.append(request) }
            let handler = try XCTUnwrap(Self.handler)
            let (statusCode, data) = try handler(request)
            let response = try XCTUnwrap(HTTPURLResponse(
                url: try XCTUnwrap(request.url),
                statusCode: statusCode,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            ))
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
