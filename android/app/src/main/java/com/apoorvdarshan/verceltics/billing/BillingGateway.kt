package com.apoorvdarshan.verceltics.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Billing seam for the paywall and tip jar. The production implementation is RevenueCat-backed;
 * tests and builds without a RevenueCat key use fakes or [UnavailableBillingGateway].
 */
interface BillingGateway {
    /** False when this build has no RevenueCat key, so nothing can be purchased. */
    val isAvailable: Boolean

    /** Pro status pushed by the store, e.g. after a renewal, refund, or deferred payment. */
    val proStatusUpdates: Flow<Boolean>

    suspend fun fetchProStatus(): Boolean

    suspend fun fetchPlans(): List<ProPlan>

    suspend fun purchasePlan(activity: Activity, packageId: String): PurchaseOutcome

    /** Returns whether Pro is active after restoring. */
    suspend fun restorePurchases(): Boolean

    suspend fun fetchTips(productIds: List<String>): List<TipProduct>

    suspend fun purchaseTip(activity: Activity, productId: String): PurchaseOutcome
}

/** Used when the build has no RevenueCat key: Pro stays locked and every store call explains why. */
object UnavailableBillingGateway : BillingGateway {
    override val isAvailable: Boolean = false
    override val proStatusUpdates: Flow<Boolean> = emptyFlow()

    override suspend fun fetchProStatus(): Boolean = false

    override suspend fun fetchPlans(): List<ProPlan> = throw BillingUnavailableException()

    override suspend fun purchasePlan(activity: Activity, packageId: String): PurchaseOutcome =
        PurchaseOutcome.Failed(BillingUnavailableException().message.orEmpty())

    override suspend fun restorePurchases(): Boolean = throw BillingUnavailableException()

    override suspend fun fetchTips(productIds: List<String>): List<TipProduct> =
        throw BillingUnavailableException()

    override suspend fun purchaseTip(activity: Activity, productId: String): PurchaseOutcome =
        PurchaseOutcome.Failed(BillingUnavailableException().message.orEmpty())
}
