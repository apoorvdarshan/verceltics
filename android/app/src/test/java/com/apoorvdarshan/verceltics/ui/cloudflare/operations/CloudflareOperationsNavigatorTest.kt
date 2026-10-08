package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
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
class CloudflareOperationsNavigatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val zoneOps = CloudflareOperationsRoute(CloudflareOperationsDomain.ZONE, "operations", "Zone & DNS operations", listOf("zone-1"))
    private val security = CloudflareOperationsRoute(CloudflareOperationsDomain.ZONE, "security", "Security center", listOf("zone-1"))

    @Test
    fun routesRoundTripThroughSavedState() {
        val handle = SavedStateHandle()
        val navigator = CloudflareOperationsNavigator(handle)
        navigator.push(zoneOps)
        navigator.push(security)

        val restored = CloudflareOperationsNavigator(handle)
        assertEquals(listOf(zoneOps, security), restored.stack.value)
        assertNull(CloudflareOperationsRoute.decode("garbage"))
        assertNull(CloudflareOperationsRoute.decode("NOPE\u001Fscreen\u001Ftitle"))
    }

    @Test
    fun pushingAnExistingRouteMovesItToTheTopWithoutDuplicates() {
        val navigator = CloudflareOperationsNavigator()
        navigator.push(zoneOps)
        navigator.push(security)
        navigator.push(security)
        navigator.push(zoneOps)
        assertEquals(listOf(security, zoneOps), navigator.stack.value)
    }

    @Test
    fun popAndClearReleaseScreenViewModels() {
        val navigator = CloudflareOperationsNavigator()
        navigator.push(zoneOps)
        navigator.push(security)
        val securityModel = ViewModelProvider.create(navigator.storeFor(security.key), Factory)[Probe::class.java]
        val zoneModel = ViewModelProvider.create(navigator.storeFor(zoneOps.key), Factory)[Probe::class.java]

        assertTrue(navigator.pop())
        assertTrue(securityModel.cleared)
        assertFalse(zoneModel.cleared)
        assertFalse(securityModel.viewModelScope.isActive)

        navigator.clear()
        assertTrue(zoneModel.cleared)
        assertFalse(navigator.pop())
    }

    @Test
    fun depthIsBounded() {
        val navigator = CloudflareOperationsNavigator()
        repeat(30) { index ->
            navigator.push(CloudflareOperationsRoute(CloudflareOperationsDomain.STORAGE, "kv", "KV $index", listOf("$index")))
        }
        assertEquals(CloudflareOperationsNavigator.MAXIMUM_DEPTH, navigator.stack.value.size)
        assertEquals("KV 29", navigator.top?.title)
    }

    @Test
    fun mutationsRunOnlyAfterTheConfirmationDialogIsConfirmed() = runTest(dispatcher) {
        val model = GatedModel()
        model.requestDelete("record-1")
        advanceUntilIdle()
        assertEquals("record-1", model.confirmation.value?.resourceId)
        assertTrue(model.confirmations.isEmpty())

        model.dismissPendingMutation()
        advanceUntilIdle()
        assertNull(model.confirmation.value)
        assertTrue(model.confirmations.isEmpty())

        model.requestDelete("record-1")
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf(CloudflareMutationConfirmation("record-1")), model.confirmations)
        assertEquals(CloudflareActionBanner("Deleted.", isError = false), model.banner.value)
        assertTrue(model.working.value.isEmpty())
    }

    @Test
    fun failedMutationsBecomeErrorBannersWithIosCopy() = runTest(dispatcher) {
        val model = GatedModel(failure = CloudflareOperationException.requestFailed(400, "Record exists"))
        model.requestDelete("record-1")
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(CloudflareActionBanner("Cloudflare request failed (400): Record exists", isError = true), model.banner.value)
        assertEquals("Cloudflare could not complete this request.", cloudflareUserMessage(IllegalStateException("secret")))
    }

    class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private object Factory : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }

    private class GatedModel(private val failure: Exception? = null) : CloudflareOperationsViewModel() {
        val confirmations = mutableListOf<CloudflareMutationConfirmation>()

        fun requestDelete(id: String) = requestConfirmation(
            CloudflareConfirmationPrompt("Delete?", "It will be removed.", "Delete", id, destructive = true),
        ) { confirmation ->
            failure?.let { throw it }
            confirmations += confirmation
            showSuccess("Deleted.")
        }
    }
}
