import XCTest
@testable import verceltics

@MainActor
final class PublicIPv4LookupTests: XCTestCase {
    override func tearDown() {
        PublicIPv4LookupMockURLProtocol.handler = nil
        PublicIPv4LookupMockURLProtocol.requests = []
        super.tearDown()
    }

    func testResolveReturnsCanonicalPublicIPv4AndUsesCredentialFreeRequest() async throws {
        PublicIPv4LookupMockURLProtocol.handler = { request in
            XCTAssertEqual(request.url, PublicIPv4Lookup.endpoint)
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "text/plain")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Cache-Control"), "no-store")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            return (200, Data(" 008.008.004.004\n".utf8))
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }

        let address = try await PublicIPv4Lookup.resolve(using: session)

        XCTAssertEqual(address, "8.8.4.4")
        XCTAssertEqual(PublicIPv4LookupMockURLProtocol.requests.count, 1)
    }

    func testResolveRejectsNonSuccessResponse() async throws {
        PublicIPv4LookupMockURLProtocol.handler = { _ in
            (503, Data("temporarily unavailable".utf8))
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }

        do {
            _ = try await PublicIPv4Lookup.resolve(using: session)
            XCTFail("Expected the lookup to reject a non-success response.")
        } catch let error as PublicIPv4LookupError {
            XCTAssertEqual(error, .requestFailed(503))
        }
    }

    func testResolveRejectsInvalidAndPrivateAddresses() async throws {
        for value in ["not-an-ip", "2001:4860:4860::8888", "192.168.1.5", "100.64.0.1"] {
            PublicIPv4LookupMockURLProtocol.handler = { _ in (200, Data(value.utf8)) }
            let session = makeSession()
            defer { session.invalidateAndCancel() }

            do {
                _ = try await PublicIPv4Lookup.resolve(using: session)
                XCTFail("Expected \(value) to be rejected.")
            } catch let error as PublicIPv4LookupError {
                XCTAssertEqual(error, .invalidAddress)
            }
        }
    }

    func testResolveEnforcesSmallResponseLimit() async throws {
        PublicIPv4LookupMockURLProtocol.handler = { _ in
            (200, Data(repeating: 65, count: PublicIPv4Lookup.maximumResponseBytes + 1))
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }

        do {
            _ = try await PublicIPv4Lookup.resolve(using: session)
            XCTFail("Expected the response-size limit to be enforced.")
        } catch let error as ProviderRequestSecurityError {
            guard case .responseTooLarge(let maximumBytes) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(maximumBytes, PublicIPv4Lookup.maximumResponseBytes)
        }
    }

    func testResolvePreservesTransportFailure() async throws {
        PublicIPv4LookupMockURLProtocol.handler = { _ in
            throw URLError(.notConnectedToInternet)
        }
        let session = makeSession()
        defer { session.invalidateAndCancel() }

        do {
            _ = try await PublicIPv4Lookup.resolve(using: session)
            XCTFail("Expected the transport failure to be preserved.")
        } catch let error as URLError {
            XCTAssertEqual(error.code, .notConnectedToInternet)
        }
    }

    func testPublicIPv4ValidationRejectsNonRoutableAndReservedRanges() {
        XCTAssertEqual(PublicIPv4Lookup.normalizedPublicIPv4(" 1.1.1.1\n"), "1.1.1.1")
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4(""))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("1.2.3"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("1.2.3.999"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("10.0.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("127.0.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("169.254.1.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("172.16.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("192.168.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("192.0.2.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("198.18.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("198.51.100.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("203.0.113.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("224.0.0.1"))
        XCTAssertNil(PublicIPv4Lookup.normalizedPublicIPv4("2001:db8::1"))
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PublicIPv4LookupMockURLProtocol.self]
        return URLSession(configuration: configuration)
    }
}

private final class PublicIPv4LookupMockURLProtocol: URLProtocol {
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
                headerFields: ["Content-Type": "text/plain"]
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
