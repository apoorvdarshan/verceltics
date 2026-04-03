import Foundation
import StoreKit
import Observation

@Observable
@MainActor
final class PaywallManager {
    static let monthlyProductID = "com.apoorvdarshan.verceltics.monthly"
    static let yearlyProductID = "com.apoorvdarshan.verceltics.yearly"

    var products: [Product] = []
    var purchasedProductIDs: Set<String> = []
    var isLoading = true
    var hasCheckedEntitlements = false
    var error: String?

    var hasActiveSubscription: Bool {
        !purchasedProductIDs.isEmpty
    }

    var monthlyProduct: Product? {
        products.first { $0.id == Self.monthlyProductID }
    }

    var yearlyProduct: Product? {
        products.first { $0.id == Self.yearlyProductID }
    }

    private var updateTask: Task<Void, Never>?

    init() {
        updateTask = Task {
            await updatePurchasedProducts()
            hasCheckedEntitlements = true
            await listenForTransactions()
        }
    }

    func loadProducts() async {
        // Skip if already loaded
        if !products.isEmpty {
            isLoading = false
            return
        }
        isLoading = true
        error = nil
        do {
            products = try await Product.products(for: [
                Self.monthlyProductID,
                Self.yearlyProductID
            ])
        } catch {
            self.error = "Failed to load products."
        }
        // Always check entitlements even if product load fails
        await updatePurchasedProducts()
        isLoading = false
    }

    func purchase(_ product: Product) async -> Bool {
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                let transaction = try checkVerified(verification)
                purchasedProductIDs.insert(transaction.productID)
                await transaction.finish()
                await updatePurchasedProducts()
                return true
            case .userCancelled:
                return false
            case .pending:
                return false
            @unknown default:
                return false
            }
        } catch {
            self.error = "Purchase failed. Please try again."
            return false
        }
    }

    func restorePurchases() async {
        try? await AppStore.sync()
        await updatePurchasedProducts()
    }

    func checkEntitlements() async {
        await updatePurchasedProducts()
    }

    private func updatePurchasedProducts() async {
        var purchased: Set<String> = []
        for await result in Transaction.currentEntitlements {
            if let transaction = try? checkVerified(result) {
                purchased.insert(transaction.productID)
            }
        }
        purchasedProductIDs = purchased
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .unverified:
            throw StoreError.verificationFailed
        case .verified(let safe):
            return safe
        }
    }

    private func listenForTransactions() async {
        for await result in Transaction.updates {
            if let transaction = try? checkVerified(result) {
                await transaction.finish()
                await updatePurchasedProducts()
            }
        }
    }
}

enum StoreError: LocalizedError {
    case verificationFailed

    var errorDescription: String? {
        "Transaction verification failed."
    }
}
