import XCTest
@testable import verceltics

@MainActor
final class AppMemoryCacheTests: XCTestCase {
    func testCacheIsBounded() {
        var cache = ResettableMemoryCache<Int>(wrappedValue: [:], limit: 2)

        cache.wrappedValue["one"] = 1
        cache.wrappedValue["two"] = 2
        cache.wrappedValue["three"] = 3

        XCTAssertEqual(cache.wrappedValue.count, 2)
        XCTAssertEqual(cache.wrappedValue["three"], 3)
    }

    func testGlobalResetClearsRegisteredCaches() {
        var cache = ResettableMemoryCache<String>(wrappedValue: [:], limit: 4)
        cache.wrappedValue["credential-scope"] = "payload"

        AppMemoryCacheRegistry.resetAll()

        XCTAssertTrue(cache.wrappedValue.isEmpty)
    }
}
