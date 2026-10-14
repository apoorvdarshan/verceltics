import Foundation
import Observation
import RevenueCat

@Observable
@MainActor
final class PaywallManager {
    static let monthlyProductID = "com.apoorvdarshan.verceltics.monthly"
    static let yearlyProductID = "com.apoorvdarshan.verceltics.yearly"
    static let lifetimeProductID = "com.apoorvdarshan.verceltics.lifetime"

    private static let revenueCatAPIKey = "appl_kIbnXTGTOxEEAuvRXUhenQYtzlk"
    private static let entitlementID = "Verceltics Pro"
#if DEBUG
    static let unconfiguredMessage = "Purchases are unavailable in this build."
#endif

    var packages: [Package] = []
    var purchasedProductIDs: Set<String> = []
    var hasActiveEntitlement = false
    var isLoading = true
    var hasCheckedEntitlements = false
    var error: String?

    var hasActiveSubscription: Bool {
        hasActiveEntitlement
    }

    var monthlyPackage: Package? {
        package(for: Self.monthlyProductID, type: .monthly)
    }

    var yearlyPackage: Package? {
        package(for: Self.yearlyProductID, type: .annual)
    }

    var lifetimePackage: Package? {
        package(for: Self.lifetimeProductID, type: .lifetime)
    }

    init() {
        configureRevenueCat()
        Task {
            await checkEntitlements()
            await loadProducts()
        }
    }

#if DEBUG
    /// UI tests and hosted unit tests never configure RevenueCat, so no purchase
    /// or entitlement request leaves the simulator and Pro stays locked.
    static func withoutRevenueCatForTesting() -> PaywallManager {
        PaywallManager(revenueCatDisabledForTesting: true)
    }

    private init(revenueCatDisabledForTesting: Bool) {
        isLoading = false
        hasCheckedEntitlements = true
    }

    /// Debug builds may run without RevenueCat (see `withoutRevenueCatForTesting`);
    /// Release builds always configure it before any purchase API is used.
    private static var isRevenueCatUnavailable: Bool { !Purchases.isConfigured }
#endif

    func loadProducts() async {
        if !packages.isEmpty {
            isLoading = false
            return
        }
#if DEBUG
        if Self.isRevenueCatUnavailable {
            isLoading = false
            error = Self.unconfiguredMessage
            return
        }
#endif

        isLoading = true
        error = nil

        do {
            let offerings = try await Purchases.shared.offerings()
            let offering = offerings.current ?? offerings.offering(identifier: "default")
            packages = offering?.availablePackages ?? []
            if packages.isEmpty {
                error = "No purchase options are available right now."
            }
        } catch {
            self.error = "Failed to load purchase options."
        }

        await checkEntitlements()
        isLoading = false
    }

    func purchase(_ package: Package) async -> Bool {
#if DEBUG
        if Self.isRevenueCatUnavailable {
            error = Self.unconfiguredMessage
            return false
        }
#endif
        error = nil

        do {
            let result = try await Purchases.shared.purchase(package: package)
            apply(customerInfo: result.customerInfo)
            return !result.userCancelled && hasActiveSubscription
        } catch {
            self.error = "Purchase failed. Please try again."
            await checkEntitlements()
            return false
        }
    }

    func restorePurchases() async {
#if DEBUG
        if Self.isRevenueCatUnavailable {
            error = Self.unconfiguredMessage
            return
        }
#endif
        error = nil

        do {
            let customerInfo = try await Purchases.shared.restorePurchases()
            apply(customerInfo: customerInfo)
        } catch {
            self.error = "Restore failed. Please try again."
            await checkEntitlements()
        }
    }

    func checkEntitlements() async {
#if DEBUG
        if Self.isRevenueCatUnavailable {
            hasCheckedEntitlements = true
            return
        }
#endif
        do {
            let customerInfo = try await Purchases.shared.customerInfo()
            apply(customerInfo: customerInfo)
        } catch {
            if !hasCheckedEntitlements {
                purchasedProductIDs = []
                hasActiveEntitlement = false
            }
        }
        hasCheckedEntitlements = true
    }

    func isEligibleForTrial(_ package: Package?) async -> Bool {
#if DEBUG
        if Self.isRevenueCatUnavailable { return false }
#endif
        guard let package,
              package.storeProduct.introductoryDiscount?.paymentMode == .freeTrial else { return false }
        let status = await Purchases.shared.checkTrialOrIntroDiscountEligibility(product: package.storeProduct)
        return status.isEligible
    }

    private func package(for productID: String, type: PackageType) -> Package? {
        packages.first { $0.storeProduct.productIdentifier == productID }
            ?? packages.first { $0.packageType == type }
    }

    private func apply(customerInfo: CustomerInfo) {
        let hasPro = customerInfo.entitlements[Self.entitlementID]?.isActive == true
        hasActiveEntitlement = hasPro
        purchasedProductIDs = hasPro ? customerInfo.allPurchasedProductIdentifiers : []
    }

    private func configureRevenueCat() {
        guard !Purchases.isConfigured else { return }
        #if DEBUG
        Purchases.logLevel = .debug
        #endif
        Purchases.configure(withAPIKey: Self.revenueCatAPIKey)
    }
}
