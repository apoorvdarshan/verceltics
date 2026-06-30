import Foundation
import RevenueCat
import Observation

/// Tip jar backed by RevenueCat so tips are tracked alongside subscriptions
/// and the lifetime purchase. Tips are consumables that unlock nothing — they
/// do not grant the "Verceltics Pro" entitlement, so they never affect the
/// paywall / gating handled by `PaywallManager`.
@Observable
@MainActor
final class TipStore {
    nonisolated static let coffeeID = "com.apoorvdarshan.verceltics.tip.coffee"
    nonisolated static let lunchID = "com.apoorvdarshan.verceltics.tip.lunch"
    nonisolated static let bigID = "com.apoorvdarshan.verceltics.tip.big"
    nonisolated static let hugeID = "com.apoorvdarshan.verceltics.tip.huge"

    /// Ordered ascending by price — drives display order.
    nonisolated static let productIDs = [coffeeID, lunchID, bigID, hugeID]

    private(set) var products: [StoreProduct] = []
    var isLoading = true
    var loadFailed = false
    var purchasingID: String?
    var didTip = false
    var errorMessage: String?

    init() {
        Task { await loadProducts() }
    }

    func loadProducts() async {
        isLoading = true
        loadFailed = false
        errorMessage = nil
        let fetched = await Purchases.shared.products(Self.productIDs)
        // Preserve our defined (ascending-price) order.
        products = Self.productIDs.compactMap { id in
            fetched.first { $0.productIdentifier == id }
        }
        loadFailed = products.isEmpty
        isLoading = false
    }

    func purchase(_ product: StoreProduct) async {
        guard purchasingID == nil else { return }
        purchasingID = product.productIdentifier
        errorMessage = nil
        defer { purchasingID = nil }

        do {
            let result = try await Purchases.shared.purchase(product: product)
            if !result.userCancelled {
                didTip = true
            }
        } catch {
            errorMessage = "Something went wrong. Please try again."
        }
    }
}
