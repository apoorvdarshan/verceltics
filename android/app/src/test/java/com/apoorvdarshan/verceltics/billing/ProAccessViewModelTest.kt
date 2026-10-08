package com.apoorvdarshan.verceltics.billing

import android.app.Activity
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProAccessViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun proIsLockedUntilFirstEntitlementCheck() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway(hasPro = true))

        assertFalse(viewModel.uiState.value.hasPro)
        assertFalse(viewModel.uiState.value.hasCheckedEntitlements)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasPro)
        assertTrue(viewModel.uiState.value.hasCheckedEntitlements)
    }

    @Test
    fun failedFirstCheckMeansFreeButLaterFailureKeepsPro() = runTest(dispatcher) {
        val gateway = FakeBillingGateway(hasPro = true).apply { statusError = IOException("offline") }
        val viewModel = ProAccessViewModel(gateway)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasPro)
        assertTrue(viewModel.uiState.value.hasCheckedEntitlements)

        gateway.statusError = null
        viewModel.onForeground()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasPro)

        gateway.statusError = IOException("offline")
        viewModel.onForeground()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasPro)
    }

    @Test
    fun storeUpdatesRevokeAccessImmediately() = runTest(dispatcher) {
        val gateway = FakeBillingGateway(hasPro = true)
        val viewModel = ProAccessViewModel(gateway)
        advanceUntilIdle()

        gateway.updates.emit(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.hasPro)
    }

    @Test
    fun loadingPlansPreselectsYearly() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway(plans = PLANS))

        viewModel.loadPlans()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(PLANS, state.plans)
        assertEquals("yearly", state.selectedPlanId)
        assertFalse(state.isLoadingPlans)
        assertNull(state.plansError)
    }

    @Test
    fun emptyAndFailedPlanLoadsExplainWhy() = runTest(dispatcher) {
        val gateway = FakeBillingGateway(plans = emptyList())
        val viewModel = ProAccessViewModel(gateway)

        viewModel.loadPlans()
        advanceUntilIdle()
        assertEquals("No purchase options are available right now.", viewModel.uiState.value.plansError)

        gateway.plansError = IOException("offline")
        viewModel.loadPlans()
        advanceUntilIdle()
        assertEquals("Failed to load purchase options.", viewModel.uiState.value.plansError)

        gateway.plansError = null
        gateway.plans = PLANS
        viewModel.loadPlans()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.plansError)
        assertEquals("yearly", viewModel.uiState.value.selectedPlanId)
    }

    @Test
    fun buildWithoutKeyStaysLockedAndSaysPurchasesAreNotSetUp() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(UnavailableBillingGateway)
        advanceUntilIdle()

        viewModel.loadPlans()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isBillingAvailable)
        assertFalse(state.hasPro)
        assertTrue(state.hasCheckedEntitlements)
        assertEquals("In-app purchases aren't set up in this build yet.", state.plansError)
    }

    @Test
    fun selectingAPlanIgnoresUnknownIdsAndInFlightTransactions() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway(plans = PLANS))
        viewModel.loadPlans()
        advanceUntilIdle()

        viewModel.selectPlan("missing")
        assertEquals("yearly", viewModel.uiState.value.selectedPlanId)

        viewModel.selectPlan("monthly")
        assertEquals("monthly", viewModel.uiState.value.selectedPlanId)
    }

    @Test
    fun completedPurchaseUnlocksPro() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway())
        advanceUntilIdle()

        viewModel.runPurchase { PurchaseOutcome.Completed(hasPro = true) }

        assertTrue(viewModel.uiState.value.hasPro)
        assertFalse(viewModel.uiState.value.isPurchasing)
        assertNull(viewModel.uiState.value.alert)
    }

    @Test
    fun cancelledPurchaseShowsNoAlert() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway())
        advanceUntilIdle()

        viewModel.runPurchase { PurchaseOutcome.Cancelled }

        assertFalse(viewModel.uiState.value.hasPro)
        assertNull(viewModel.uiState.value.alert)
    }

    @Test
    fun failedPurchaseShowsStoreMessage() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway())
        advanceUntilIdle()

        viewModel.runPurchase { PurchaseOutcome.Failed("Your payment is pending.") }

        assertEquals(
            PaywallAlert("Purchase couldn’t be completed", "Your payment is pending."),
            viewModel.uiState.value.alert,
        )
        assertFalse(viewModel.uiState.value.isPurchasing)
    }

    @Test
    fun restoreWithoutPurchaseExplainsNothingWasFound() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway(restoreResult = false))
        advanceUntilIdle()

        viewModel.restorePurchases()
        advanceUntilIdle()

        assertEquals("No purchase found", viewModel.uiState.value.alert?.title)
        assertFalse(viewModel.uiState.value.isRestoring)
    }

    @Test
    fun successfulRestoreUnlocksPro() = runTest(dispatcher) {
        val viewModel = ProAccessViewModel(FakeBillingGateway(restoreResult = true))
        advanceUntilIdle()

        viewModel.restorePurchases()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasPro)
        assertNull(viewModel.uiState.value.alert)
    }

    private companion object {
        val PLANS = listOf(
            ProPlan("yearly", ProPlanKind.YEARLY, "$29.99"),
            ProPlan("monthly", ProPlanKind.MONTHLY, "$4.99"),
            ProPlan("lifetime", ProPlanKind.LIFETIME, "$49.99"),
        )
    }
}

internal class FakeBillingGateway(
    var hasPro: Boolean = false,
    var plans: List<ProPlan> = emptyList(),
    var restoreResult: Boolean = false,
    var tips: List<TipProduct> = emptyList(),
) : BillingGateway {
    var statusError: Exception? = null
    var plansError: Exception? = null
    var tipsError: Exception? = null
    val updates = MutableSharedFlow<Boolean>()

    override val isAvailable: Boolean = true
    override val proStatusUpdates: Flow<Boolean> = updates

    override suspend fun fetchProStatus(): Boolean {
        statusError?.let { throw it }
        return hasPro
    }

    override suspend fun fetchPlans(): List<ProPlan> {
        plansError?.let { throw it }
        return plans
    }

    override suspend fun purchasePlan(activity: Activity, packageId: String): PurchaseOutcome =
        error("Tests drive purchases through runPurchase")

    override suspend fun restorePurchases(): Boolean = restoreResult

    override suspend fun fetchTips(productIds: List<String>): List<TipProduct> {
        tipsError?.let { throw it }
        return tips
    }

    override suspend fun purchaseTip(activity: Activity, productId: String): PurchaseOutcome =
        error("Tests drive purchases through runPurchase")
}
