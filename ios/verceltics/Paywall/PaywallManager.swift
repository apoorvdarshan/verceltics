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
    private static let unconfiguredMessage = "Purchases are unavailable in this build."

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

    convenience init() {
        self.init(configuresRevenueCat: true)
    }

    private init(configuresRevenueCat: Bool) {
        guard configuresRevenueCat else {
            // Entitlements are known to be absent: Pro stays locked.
            isLoading = false
            hasCheckedEntitlements = true
            return
        }
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
        PaywallManager(configuresRevenueCat: false)
    }
#endif

    func loadProducts() async {
        if !packages.isEmpty {
            isLoading = false
            return
        }
        guard Purchases.isConfigured else {
            isLoading = false
            error = Self.unconfiguredMessage
            return
        }

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
        guard Purchases.isConfigured else {
            error = Self.unconfiguredMessage
            return false
        }
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
        guard Purchases.isConfigured else {
            error = Self.unconfiguredMessage
            return
        }
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
        guard Purchases.isConfigured else {
            hasCheckedEntitlements = true
            return
        }
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
        guard Purchases.isConfigured,
              let package,
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
