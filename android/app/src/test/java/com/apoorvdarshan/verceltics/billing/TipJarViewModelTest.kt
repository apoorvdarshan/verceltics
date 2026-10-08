package com.apoorvdarshan.verceltics.billing

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class TipJarViewModelTest {
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
    fun loadsTipsFromStore() = runTest(dispatcher) {
        val tips = listOf(TipProduct(BillingProducts.TIP_COFFEE_ID, "$1.99"))
        val viewModel = TipJarViewModel(FakeBillingGateway(tips = tips))

        assertTrue(viewModel.uiState.value.isLoading)
        advanceUntilIdle()

        assertEquals(tips, viewModel.uiState.value.products)
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun failedOrEmptyLoadOffersRetry() = runTest(dispatcher) {
        val gateway = FakeBillingGateway().apply { tipsError = IOException("offline") }
        val viewModel = TipJarViewModel(gateway)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)

        gateway.tipsError = null
        gateway.tips = listOf(TipProduct(BillingProducts.TIP_LUNCH_ID, "$4.99"))
        viewModel.loadProducts()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun completedTipShowsThankYouUntilDone() = runTest(dispatcher) {
        val viewModel = TipJarViewModel(FakeBillingGateway())
        advanceUntilIdle()

        viewModel.runPurchase(BillingProducts.TIP_BIG_ID) { PurchaseOutcome.Completed(hasPro = false) }
        assertTrue(viewModel.uiState.value.didTip)
        assertNull(viewModel.uiState.value.purchasingId)

        viewModel.dismissThankYou()
        assertFalse(viewModel.uiState.value.didTip)
    }

    @Test
    fun cancelledTipIsSilentAndFailedTipExplains() = runTest(dispatcher) {
        val viewModel = TipJarViewModel(FakeBillingGateway())
        advanceUntilIdle()

        viewModel.runPurchase(BillingProducts.TIP_HUGE_ID) { PurchaseOutcome.Cancelled }
        assertFalse(viewModel.uiState.value.didTip)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.runPurchase(BillingProducts.TIP_HUGE_ID) { PurchaseOutcome.Failed("Something went wrong. Please try again.") }
        assertEquals("Something went wrong. Please try again.", viewModel.uiState.value.errorMessage)
    }
}
