package com.apoorvdarshan.verceltics.billing

import android.app.Activity
import android.app.Application
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PackageType
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.StoreProduct
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * RevenueCat-backed billing. Google Play subscriptions, the lifetime unlock, and tips all live in
 * the same RevenueCat project as iOS; only the "Verceltics Pro" entitlement grants Pro access.
 */
class RevenueCatBillingGateway private constructor(
    private val purchases: Purchases,
) : BillingGateway {
    private val packagesById = ConcurrentHashMap<String, Package>()
    private val tipProductsById = ConcurrentHashMap<String, StoreProduct>()

    override val isAvailable: Boolean = true

    override val proStatusUpdates: Flow<Boolean> = callbackFlow {
        purchases.updatedCustomerInfoListener = UpdatedCustomerInfoListener { customerInfo ->
            trySend(customerInfo.hasPro())
        }
        awaitClose { purchases.removeUpdatedCustomerInfoListener() }
    }

    override suspend fun fetchProStatus(): Boolean = purchases.awaitCustomerInfo().hasPro()

    override suspend fun fetchPlans(): List<ProPlan> {
        val offerings = purchases.awaitOfferings()
        val offering = offerings.current ?: offerings[BillingProducts.FALLBACK_OFFERING_ID]
        val packages = offering?.availablePackages.orEmpty()
        packages.forEach { packagesById[it.identifier] = it }
        return selectProPlans(packages.map(::planCandidate))
    }

    override suspend fun purchasePlan(activity: Activity, packageId: String): PurchaseOutcome {
        val packageToPurchase = packagesById[packageId]
            ?: return PurchaseOutcome.Failed("This plan is no longer available. Please try again.")
        return purchase { PurchaseParams.Builder(activity, packageToPurchase).build() }
    }

    override suspend fun restorePurchases(): Boolean = purchases.awaitRestore().hasPro()

    override suspend fun fetchTips(productIds: List<String>): List<TipProduct> {
        val products = purchases.awaitGetProducts(productIds, ProductType.INAPP)
        products.forEach { tipProductsById[it.id] = it }
        return productIds.mapNotNull { id ->
            products.firstOrNull { it.id == id }?.let { TipProduct(id = id, price = it.price.formatted) }
        }
    }

    override suspend fun purchaseTip(activity: Activity, productId: String): PurchaseOutcome {
        val product = tipProductsById[productId]
            ?: return PurchaseOutcome.Failed("Something went wrong. Please try again.")
        return purchase { PurchaseParams.Builder(activity, product).build() }
    }

    private suspend fun purchase(params: () -> PurchaseParams): PurchaseOutcome = try {
        val result = purchases.awaitPurchase(params())
        PurchaseOutcome.Completed(hasPro = result.customerInfo.hasPro())
    } catch (error: PurchasesTransactionException) {
        if (error.userCancelled) PurchaseOutcome.Cancelled else error.toFailedOutcome()
    } catch (error: PurchasesException) {
        error.toFailedOutcome()
    }

    private fun planCandidate(item: Package): PlanCandidate {
        val product = item.product
        val defaultOption = product.defaultOption
        return PlanCandidate(
            packageId = item.identifier,
            productId = product.id,
            packageType = when (item.packageType) {
                PackageType.MONTHLY -> PlanPackageType.MONTHLY
                PackageType.ANNUAL -> PlanPackageType.ANNUAL
                PackageType.LIFETIME -> PlanPackageType.LIFETIME
                else -> PlanPackageType.OTHER
            },
            // The purchase uses the default option, so show the price it renews at.
            price = (defaultOption?.fullPricePhase?.price ?: product.price).formatted,
            // Google Play only returns trial offers the customer is still eligible for.
            freeTrial = defaultOption?.freePhase?.billingPeriod?.toFreeTrialOffer(),
        )
    }

    companion object {
        fun create(application: Application, apiKey: String, debugLogs: Boolean): BillingGateway {
            if (apiKey.isBlank()) return UnavailableBillingGateway
            if (debugLogs) Purchases.logLevel = LogLevel.DEBUG
            val purchases = if (Purchases.isConfigured) {
                Purchases.sharedInstance
            } else {
                Purchases.configure(PurchasesConfiguration.Builder(application, apiKey).build())
            }
            return RevenueCatBillingGateway(purchases)
        }
    }
}

private fun CustomerInfo.hasPro(): Boolean =
    entitlements[BillingProducts.ENTITLEMENT_ID]?.isActive == true

private fun PurchasesException.toFailedOutcome(): PurchaseOutcome.Failed = PurchaseOutcome.Failed(
    when (code) {
        PurchasesErrorCode.PaymentPendingError ->
            "Your payment is pending. Pro unlocks as soon as Google Play confirms it."
        PurchasesErrorCode.ProductAlreadyPurchasedError ->
            "You already own this. Tap Restore purchases to unlock Pro."
        PurchasesErrorCode.NetworkError ->
            "Couldn't reach Google Play. Check your connection and try again."
        else -> "Purchase failed. Please try again."
    },
)

private fun Period.toFreeTrialOffer(): FreeTrialOffer? {
    val trialUnit = when (unit) {
        Period.Unit.DAY -> TrialPeriodUnit.DAY
        Period.Unit.WEEK -> TrialPeriodUnit.WEEK
        Period.Unit.MONTH -> TrialPeriodUnit.MONTH
        Period.Unit.YEAR -> TrialPeriodUnit.YEAR
        else -> return null
    }
    return FreeTrialOffer(value = value, unit = trialUnit)
}
