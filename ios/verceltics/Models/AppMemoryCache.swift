import Foundation

/// Coordinates short-lived, process-local UI caches so credentials and their
/// associated payloads can be discarded immediately when an account changes.
@MainActor
enum AppMemoryCacheRegistry {
    private static var storage: [UUID: Any] = [:]

    static func value<Value>(for id: UUID) -> [String: Value] {
        storage[id] as? [String: Value] ?? [:]
    }

    static func set<Value>(_ value: [String: Value], for id: UUID) {
        storage[id] = value
    }

    static func resetAll() {
        storage.removeAll(keepingCapacity: false)
        URLCache.shared.removeAllCachedResponses()
    }

    static var registeredCacheCount: Int {
        storage.count
    }
}

/// A bounded dictionary cache that also participates in account-level cache
/// invalidation. Keeping the cap here prevents each screen from growing an
/// unbounded static dictionary as users switch accounts and resources.
@propertyWrapper
@MainActor
struct ResettableMemoryCache<Value> {
    private let registrationID = UUID()
    private let limit: Int

    init(wrappedValue: [String: Value], limit: Int = 32) {
        self.limit = max(1, limit)
        AppMemoryCacheRegistry.set(trimmed(wrappedValue), for: registrationID)
    }

    var wrappedValue: [String: Value] {
        get { AppMemoryCacheRegistry.value(for: registrationID) }
        set {
            AppMemoryCacheRegistry.set(trimmed(newValue), for: registrationID)
        }
        _modify {
            var value: [String: Value] = AppMemoryCacheRegistry.value(for: registrationID)
            let existingKeys = Set(value.keys)
            defer {
                let insertedKeys = Set(value.keys).subtracting(existingKeys)
                AppMemoryCacheRegistry.set(
                    trimmed(value, preserving: insertedKeys),
                    for: registrationID
                )
            }
            yield &value
        }
    }

    private func trimmed(
        _ value: [String: Value],
        preserving preservedKeys: Set<String> = []
    ) -> [String: Value] {
        let overflow = value.count - limit
        guard overflow > 0 else { return value }
        var result = value
        let protectedKeys = Set(
            preservedKeys
                .filter { result[$0] != nil }
                .sorted()
                .suffix(limit)
        )
        let evictionCandidates = result.keys
            .filter { !protectedKeys.contains($0) }
            .sorted()
        for key in evictionCandidates.prefix(overflow) {
            result[key] = nil
        }
        return result
    }
}
