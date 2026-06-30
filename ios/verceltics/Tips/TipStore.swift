import Foundation
import StoreKit
import Observation

/// Standalone StoreKit 2 tip jar.
///
/// Intentionally does NOT go through RevenueCat — tips are plain consumables
/// that unlock nothing, so they're handled with StoreKit directly. The
/// transaction listener below only ever touches the tip product IDs, so it
/// can't interfere with RevenueCat's subscription / lifetime processing.
@Observable
@MainActor
final class TipStore {
    nonisolated static let coffeeID = "com.apoorvdarshan.verceltics.tip.coffee"
    nonisolated static let lunchID = "com.apoorvdarshan.verceltics.tip.lunch"
    nonisolated static let bigID = "com.apoorvdarshan.verceltics.tip.big"
    nonisolated static let hugeID = "com.apoorvdarshan.verceltics.tip.huge"

    /// Ordered ascending by price — drives display order.
    nonisolated static let productIDs = [coffeeID, lunchID, bigID, hugeID]

    private(set) var products: [Product] = []
    var isLoading = true
    var loadFailed = false
    var purchasingID: String?
    var didTip = false
    var errorMessage: String?

    @ObservationIgnored nonisolated(unsafe) private var updatesTask: Task<Void, Never>?

    init() {
        updatesTask = listenForTransactions()
        Task { await loadProducts() }
    }

    deinit { updatesTask?.cancel() }

    func loadProducts() async {
        isLoading = true
        loadFailed = false
        errorMessage = nil
        do {
            let fetched = try await Product.products(for: Self.productIDs)
            // Preserve our defined (ascending-price) order.
            products = Self.productIDs.compactMap { id in fetched.first { $0.id == id } }
            loadFailed = products.isEmpty
        } catch {
            loadFailed = true
        }
        isLoading = false
    }

    func purchase(_ product: Product) async {
        guard purchasingID == nil else { return }
        purchasingID = product.id
        errorMessage = nil
        defer { purchasingID = nil }

        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                    didTip = true
                }
            case .userCancelled, .pending:
                break
            @unknown default:
                break
            }
        } catch {
            errorMessage = "Something went wrong. Please try again."
        }
    }

    /// Drains any unfinished tip transactions (e.g. Ask-to-Buy approvals or a
    /// purchase interrupted before `finish()`). Only finishes tip products.
    private func listenForTransactions() -> Task<Void, Never> {
        Task { [weak self] in
            for await update in Transaction.updates {
                guard case .verified(let transaction) = update,
                      TipStore.productIDs.contains(transaction.productID) else { continue }
                await transaction.finish()
                self?.didTip = true
            }
        }
    }
}
