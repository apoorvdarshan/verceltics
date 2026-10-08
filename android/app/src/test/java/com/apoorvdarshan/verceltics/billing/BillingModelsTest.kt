package com.apoorvdarshan.verceltics.billing

import com.apoorvdarshan.verceltics.ui.billing.purchaseDisclosure
import com.apoorvdarshan.verceltics.ui.billing.subscribeButtonLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingModelsTest {
    @Test
    fun plansFollowYearlyMonthlyLifetimeOrder() {
        val plans = selectProPlans(
            listOf(
                candidate("\$rc_lifetime", BillingProducts.LIFETIME_ID, PlanPackageType.LIFETIME, "$49.99"),
                candidate("\$rc_monthly", "${BillingProducts.MONTHLY_ID}:monthly", PlanPackageType.MONTHLY, "$4.99"),
                candidate("\$rc_annual", "${BillingProducts.YEARLY_ID}:yearly", PlanPackageType.ANNUAL, "$29.99"),
            ),
        )

        assertEquals(listOf(ProPlanKind.YEARLY, ProPlanKind.MONTHLY, ProPlanKind.LIFETIME), plans.map(ProPlan::kind))
        assertEquals(listOf("$29.99", "$4.99", "$49.99"), plans.map(ProPlan::price))
    }

    @Test
    fun exactProductIdWinsOverPackageType() {
        val plans = selectProPlans(
            listOf(
                candidate("custom_annual", "com.example.other:annual", PlanPackageType.ANNUAL, "$1.00"),
                candidate("custom_yearly", "${BillingProducts.YEARLY_ID}:p1y", PlanPackageType.OTHER, "$29.99"),
            ),
        )

        assertEquals("custom_yearly", plans.single { it.kind == ProPlanKind.YEARLY }.packageId)
    }

    @Test
    fun packageTypeIsFallbackWhenProductIdsDiffer() {
        val plans = selectProPlans(
            listOf(candidate("\$rc_monthly", "pro_monthly:base", PlanPackageType.MONTHLY, "$3.99")),
        )

        assertEquals(ProPlanKind.MONTHLY, plans.single().kind)
    }

    @Test
    fun freeTrialIsOnlyShownOnYearlyPlan() {
        val trial = FreeTrialOffer(7, TrialPeriodUnit.DAY)
        val plans = selectProPlans(
            listOf(
                candidate("\$rc_annual", BillingProducts.YEARLY_ID, PlanPackageType.ANNUAL, "$29.99", trial),
                candidate("\$rc_monthly", BillingProducts.MONTHLY_ID, PlanPackageType.MONTHLY, "$4.99", trial),
            ),
        )

        assertEquals(trial, plans.first { it.kind == ProPlanKind.YEARLY }.freeTrial)
        assertNull(plans.first { it.kind == ProPlanKind.MONTHLY }.freeTrial)
    }

    @Test
    fun samePackageIsNeverListedTwice() {
        val plans = selectProPlans(
            listOf(candidate("\$rc_annual", BillingProducts.MONTHLY_ID, PlanPackageType.ANNUAL, "$4.99")),
        )

        assertEquals(1, plans.size)
    }

    @Test
    fun trialBadgeMatchesIos() {
        assertEquals("7-day free trial", FreeTrialOffer(7, TrialPeriodUnit.DAY).badgeText)
        assertEquals("7-day free trial", FreeTrialOffer(1, TrialPeriodUnit.WEEK).badgeText)
        assertEquals("2-week free trial", FreeTrialOffer(2, TrialPeriodUnit.WEEK).badgeText)
        assertEquals("1-month free trial", FreeTrialOffer(1, TrialPeriodUnit.MONTH).badgeText)
    }

    @Test
    fun defaultPlanPrefersYearlyThenMonthlyThenLifetime() {
        val monthly = ProPlan("m", ProPlanKind.MONTHLY, "$4.99")
        val lifetime = ProPlan("l", ProPlanKind.LIFETIME, "$49.99")
        val yearly = ProPlan("y", ProPlanKind.YEARLY, "$29.99")

        assertEquals(yearly, defaultPlan(listOf(lifetime, monthly, yearly)))
        assertEquals(monthly, defaultPlan(listOf(lifetime, monthly)))
        assertEquals(lifetime, defaultPlan(listOf(lifetime)))
        assertNull(defaultPlan(emptyList()))
    }

    @Test
    fun purchaseCopyStatesRenewalTerms() {
        val yearly = ProPlan("y", ProPlanKind.YEARLY, "$29.99", FreeTrialOffer(7, TrialPeriodUnit.DAY))
        val monthly = ProPlan("m", ProPlanKind.MONTHLY, "$4.99")
        val lifetime = ProPlan("l", ProPlanKind.LIFETIME, "$49.99")
        val state = ProAccessUiState(isBillingAvailable = true, plans = listOf(yearly, monthly, lifetime))

        assertEquals("Start 7-day free trial", subscribeButtonLabel(state.copy(selectedPlanId = "y")))
        assertEquals(
            "7-day free trial, then $29.99 per year. Renews annually until canceled.",
            purchaseDisclosure(state.copy(selectedPlanId = "y")),
        )
        assertEquals("Subscribe monthly", subscribeButtonLabel(state.copy(selectedPlanId = "m")))
        assertEquals("$4.99 per month. Renews monthly until canceled.", purchaseDisclosure(state.copy(selectedPlanId = "m")))
        assertEquals("Buy lifetime access", subscribeButtonLabel(state.copy(selectedPlanId = "l")))
        assertEquals("$49.99 one-time purchase. No renewal.", purchaseDisclosure(state.copy(selectedPlanId = "l")))
        assertEquals("Loading plans…", subscribeButtonLabel(state.copy(isLoadingPlans = true)))
        assertEquals("Choose a plan", subscribeButtonLabel(state))
    }

    @Test
    fun yearlyWithoutTrialSaysSubscribe() {
        val state = ProAccessUiState(
            isBillingAvailable = true,
            plans = listOf(ProPlan("y", ProPlanKind.YEARLY, "$29.99")),
            selectedPlanId = "y",
        )

        assertEquals("Subscribe yearly", subscribeButtonLabel(state))
        assertEquals("$29.99 per year. Renews annually until canceled.", purchaseDisclosure(state))
    }

    private fun candidate(
        packageId: String,
        productId: String,
        type: PlanPackageType,
        price: String,
        trial: FreeTrialOffer? = null,
    ) = PlanCandidate(packageId, productId, type, price, trial)
}
