package com.apoorvdarshan.verceltics.billing

import androidx.compose.runtime.Immutable

/** Product identifiers shared with the iOS app and the RevenueCat product catalog. */
object BillingProducts {
    const val ENTITLEMENT_ID = "Verceltics Pro"
    const val FALLBACK_OFFERING_ID = "default"

    const val MONTHLY_ID = "com.apoorvdarshan.verceltics.monthly"
    const val YEARLY_ID = "com.apoorvdarshan.verceltics.yearly"
    const val LIFETIME_ID = "com.apoorvdarshan.verceltics.lifetime"

    const val TIP_COFFEE_ID = "com.apoorvdarshan.verceltics.tip.coffee"
    const val TIP_LUNCH_ID = "com.apoorvdarshan.verceltics.tip.lunch"
    const val TIP_BIG_ID = "com.apoorvdarshan.verceltics.tip.big"
    const val TIP_HUGE_ID = "com.apoorvdarshan.verceltics.tip.huge"

    /** Ordered ascending by price; drives display order. */
    val tipIds = listOf(TIP_COFFEE_ID, TIP_LUNCH_ID, TIP_BIG_ID, TIP_HUGE_ID)

    const val MANAGE_SUBSCRIPTIONS_URI =
        "https://play.google.com/store/account/subscriptions?package=com.apoorvdarshan.verceltics"
    const val PRIVACY_POLICY_URI = "https://verceltics.com/privacy"
    const val TERMS_OF_USE_URI = "https://verceltics.com/terms"
}

enum class ProPlanKind { YEARLY, MONTHLY, LIFETIME }

enum class TrialPeriodUnit { DAY, WEEK, MONTH, YEAR }

@Immutable
data class FreeTrialOffer(val value: Int, val unit: TrialPeriodUnit) {
    /** Mirrors the iOS paywall so both stores describe trials identically. */
    val badgeText: String
        get() {
            val duration = when (unit) {
                TrialPeriodUnit.DAY -> "$value-day"
                TrialPeriodUnit.WEEK -> if (value == 1) "7-day" else "$value-week"
                TrialPeriodUnit.MONTH -> "$value-month"
                TrialPeriodUnit.YEAR -> "$value-year"
            }
            return "$duration free trial"
        }
}

@Immutable
data class ProPlan(
    /** RevenueCat package identifier used to start the purchase. */
    val packageId: String,
    val kind: ProPlanKind,
    /** Localized price of the recurring (or one-time) charge, formatted by Google Play. */
    val price: String,
    /** Present only when Google Play reports the customer is eligible for a free trial. */
    val freeTrial: FreeTrialOffer? = null,
)

@Immutable
data class TipProduct(val id: String, val price: String)

sealed interface PurchaseOutcome {
    data class Completed(val hasPro: Boolean) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
}

class BillingUnavailableException : IllegalStateException(
    "In-app purchases aren't set up in this build yet.",
)

/** Store-agnostic view of a RevenueCat package, used to pick the paywall's three plans. */
internal data class PlanCandidate(
    val packageId: String,
    /** RevenueCat product id; Google Play subscriptions use `subscriptionId:basePlanId`. */
    val productId: String,
    val packageType: PlanPackageType,
    val price: String,
    val freeTrial: FreeTrialOffer?,
)

internal enum class PlanPackageType { MONTHLY, ANNUAL, LIFETIME, OTHER }

/**
 * Picks the yearly, monthly, and lifetime plans in display order. Like iOS, an exact product id
 * wins over the package type so a custom offering layout can't swap prices between cards.
 */
internal fun selectProPlans(candidates: List<PlanCandidate>): List<ProPlan> =
    ProPlanKind.entries.mapNotNull { kind ->
        val productId = when (kind) {
            ProPlanKind.YEARLY -> BillingProducts.YEARLY_ID
            ProPlanKind.MONTHLY -> BillingProducts.MONTHLY_ID
            ProPlanKind.LIFETIME -> BillingProducts.LIFETIME_ID
        }
        val packageType = when (kind) {
            ProPlanKind.YEARLY -> PlanPackageType.ANNUAL
            ProPlanKind.MONTHLY -> PlanPackageType.MONTHLY
            ProPlanKind.LIFETIME -> PlanPackageType.LIFETIME
        }
        val match = candidates.firstOrNull { it.productId.substringBefore(':') == productId }
            ?: candidates.firstOrNull { it.packageType == packageType }
        match?.let {
            ProPlan(
                packageId = it.packageId,
                kind = kind,
                price = it.price,
                freeTrial = it.freeTrial.takeIf { kind == ProPlanKind.YEARLY },
            )
        }
    }.distinctBy(ProPlan::packageId)
