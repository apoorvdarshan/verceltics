package com.apoorvdarshan.verceltics.ui.sites

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class SiteServicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = 10_000_000L

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun restoreMapsEveryProviderAndExposesConnectedIds() = runTest(dispatcher) {
        val gateway = FakeGateway(
            restore = mapOf(
                "plausible" to SiteServiceRestoreUi.Available(dashboard("plausible")),
                "umami" to SiteServiceRestoreUi.SavedWithoutInventory("Umami Cloud"),
                "clarity" to SiteServiceRestoreUi.SavedUnavailable("Secure storage is unavailable."),
            ),
        )
        val viewModel = viewModel(gateway)
        assertTrue(viewModel.uiState.value.isRestoring)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRestoring)
        assertEquals(SiteServiceConnectionStatus.CONNECTED, state.service("plausible").status)
        assertEquals(SiteServiceConnectionStatus.SAVED_UNAVAILABLE, state.service("umami").status)
        assertEquals("Umami Cloud", state.service("umami").savedAccountName)
        assertEquals("Secure storage is unavailable.", state.service("clarity").error)
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, state.service("bingWebmaster").status)
        assertEquals(setOf("clarity", "plausible", "umami"), state.connectedProviderIds)
        assertEquals(listOf("clarity", "plausible", "umami"), state.connectedProviderIds.toList())
        assertEquals(0, gateway.refreshCalls.size)
    }

    @Test
    fun failedRestoreLeavesServicesDisconnectedWithAMessage() = runTest(dispatcher) {
        val viewModel = viewModel(FakeGateway(restoreFailure = SiteServicesUiException("Storage locked.")))
        advanceUntilIdle()
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, viewModel.uiState.value.service("umami").status)
        assertEquals("Storage locked.", viewModel.uiState.value.service("umami").error)
        assertTrue(viewModel.uiState.value.connectedProviderIds.isEmpty())
    }

    @Test
    fun secureWindowFollowsVisibleCredentialFormsAndInFlightAuthorization() = runTest(dispatcher) {
        val gateway = FakeGateway(connectGate = CompletableDeferred())
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setActiveRoute("betterStack")
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setActiveRoute("googleAnalytics")
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.connectGoogle("googleAnalytics")
        runCurrent()
        assertEquals(SiteServiceOperation.AUTHORIZING, viewModel.uiState.value.service("googleAnalytics").operation)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        gateway.connectGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(SiteServiceConnectionStatus.CONNECTED, viewModel.uiState.value.service("googleAnalytics").status)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setActiveRoute(null)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
    }

    @Test
    fun connectSuccessAndFailureKeepCredentialsOutOfState() = runTest(dispatcher) {
        val gateway = FakeGateway(connectFailure = SiteServicesUiException("Request failed (HTTP 401)."))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect("uptimeRobot", SiteServiceConnectionInputUi(SecretValue.of("never-publish-key")))
        advanceUntilIdle()
        assertEquals("Request failed (HTTP 401).", viewModel.uiState.value.service("uptimeRobot").error)
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, viewModel.uiState.value.service("uptimeRobot").status)
        assertFalse(viewModel.uiState.value.toString().contains("never-publish-key"))
        assertEquals("never-publish-key", gateway.lastInput?.credential?.use { it })

        gateway.connectFailure = null
        viewModel.connect("uptimeRobot", SiteServiceConnectionInputUi(SecretValue.of("good-key")))
        advanceUntilIdle()
        val service = viewModel.uiState.value.service("uptimeRobot")
        assertEquals(SiteServiceConnectionStatus.CONNECTED, service.status)
        assertNull(service.error)
        assertEquals("uptimeRobot account", service.dashboard?.accountName)
    }

    @Test
    fun cancellingConnectReconcilesWithSavedState() = runTest(dispatcher) {
        val gateway = FakeGateway(connectGate = CompletableDeferred())
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect("plausible", SiteServiceConnectionInputUi(SecretValue.of("k"), mapOf("siteID" to "a.example")))
        runCurrent()
        viewModel.cancelOperation("plausible")
        assertEquals(SiteServiceOperation.RESTORING, viewModel.uiState.value.service("plausible").operation)
        assertEquals("Cancelling request…", viewModel.uiState.value.service("plausible").notice)
        advanceUntilIdle()
        assertEquals("Request cancelled.", viewModel.uiState.value.service("plausible").notice)
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, viewModel.uiState.value.service("plausible").status)

        gateway.restoreResult = mapOf("plausible" to SiteServiceRestoreUi.Available(dashboard("plausible")))
        gateway.connectGate = CompletableDeferred()
        viewModel.connect("plausible", SiteServiceConnectionInputUi(SecretValue.of("k")))
        runCurrent()
        viewModel.cancelOperation("plausible")
        advanceUntilIdle()
        assertEquals("The connection completed before cancellation and remains saved.", viewModel.uiState.value.service("plausible").notice)
        assertEquals(SiteServiceConnectionStatus.CONNECTED, viewModel.uiState.value.service("plausible").status)
    }

    @Test
    fun refreshFailureKeepsTheLastSavedDashboardWithANotice() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("bingWebmaster" to SiteServiceRestoreUi.Available(dashboard("bingWebmaster"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        gateway.refreshFailure = SiteServicesUiException("The network connection was lost.")

        viewModel.refresh("bingWebmaster")
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("bingWebmaster")
        assertEquals("The network connection was lost.", service.error)
        assertEquals("Showing the last saved Bing Webmaster data.", service.notice)
        assertEquals(dashboard("bingWebmaster"), service.dashboard)
        assertEquals("Refresh failed", SiteServiceFormat.serviceStatus(service).text)
    }

    @Test
    fun foregroundRefreshesOnlyStaleServicesAndSpacesRetries() = runTest(dispatcher) {
        val gateway = FakeGateway(
            restore = mapOf(
                "plausible" to SiteServiceRestoreUi.Available(dashboard("plausible", cacheState = SiteServiceCacheState.CACHED_STALE)),
                "umami" to SiteServiceRestoreUi.Available(dashboard("umami", fetchedAt = now)),
                "clarity" to SiteServiceRestoreUi.SavedWithoutInventory("Clarity"),
            ),
            refreshFailure = SiteServicesUiException("offline"),
        )
        val viewModel = viewModel(gateway)
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("plausible", "clarity").sorted(), gateway.refreshCalls.sorted())

        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(2, gateway.refreshCalls.size)

        now += 61_000
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(3, gateway.refreshCalls.size)
        assertEquals(listOf("plausible"), gateway.refreshCalls.drop(2))
    }

    @Test
    fun disconnectRequiresConfirmationAndClearsAnOpenDetail() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("umami" to SiteServiceRestoreUi.Available(dashboard("umami"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("umami")
        viewModel.openDetail("umami", "umami-1")
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("umami")
        assertTrue(viewModel.uiState.value.service("umami").showDisconnectConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.service("umami").showDisconnectConfirmation)

        viewModel.requestDisconnectConfirmation("umami")
        viewModel.confirmDisconnect("umami")
        advanceUntilIdle()
        assertEquals(listOf("umami"), gateway.disconnects)
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, viewModel.uiState.value.service("umami").status)
        assertNull(viewModel.uiState.value.detail)
    }

    @Test
    fun detailOpensLoadsPartialThenCompleteAndSurvivesRecreation() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("googleAnalytics" to SiteServiceRestoreUi.Available(dashboard("googleAnalytics"))))
        gateway.detailGate = CompletableDeferred()
        val saved = SavedStateHandle()
        val viewModel = viewModel(gateway, saved)
        advanceUntilIdle()
        viewModel.setActiveRoute("googleAnalytics")

        viewModel.openDetail("googleAnalytics", "missing-resource")
        runCurrent()
        var detail = viewModel.uiState.value.detail!!
        assertEquals("googleAnalytics-1", detail.resourceId)
        assertTrue(detail.payload!!.isPartial)
        assertTrue(detail.isRefreshing)
        gateway.detailGate!!.complete(Unit)
        advanceUntilIdle()
        detail = viewModel.uiState.value.detail!!
        assertFalse(detail.payload!!.isPartial)
        assertFalse(detail.isLoading || detail.isRefreshing)

        viewModel.selectDetailRange(SiteDetailRangePresetUi.DAYS_7)
        viewModel.toggleClarityDimension("Browser")
        assertEquals(SiteDetailRangePresetUi.DAYS_7, saved.get<String>(SiteServicesViewModel.DETAIL_PRESET)?.let(SiteDetailRangePresetUi::valueOf))
        assertEquals("googleAnalytics", saved.get<String>(SiteServicesViewModel.DETAIL_PROVIDER))
        advanceUntilIdle()

        val recreatedGateway = FakeGateway(restore = mapOf("googleAnalytics" to SiteServiceRestoreUi.Available(dashboard("googleAnalytics"))))
        val recreated = viewModel(recreatedGateway, saved)
        advanceUntilIdle()
        val restoredDetail = recreated.uiState.value.detail!!
        assertEquals("googleAnalytics-1", restoredDetail.resourceId)
        assertEquals(SiteDetailRangePresetUi.DAYS_7, restoredDetail.query.preset)
        assertEquals(listOf("Browser"), restoredDetail.query.clarityDimensions)
        assertEquals(1, recreatedGateway.detailRequests.size)
    }

    @Test
    fun queryChangesAreDebouncedAndCoalesced() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("plausible" to SiteServiceRestoreUi.Available(dashboard("plausible"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.openDetail("plausible")
        advanceUntilIdle()
        assertEquals(1, gateway.detailRequests.size)

        viewModel.selectDetailRange(SiteDetailRangePresetUi.DAYS_7)
        viewModel.selectDetailRange(SiteDetailRangePresetUi.DAYS_90)
        viewModel.setCustomDetailRange(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 1), today = LocalDate.of(2026, 7, 15))
        advanceTimeBy(SiteServicesViewModel.QUERY_DEBOUNCE_MILLIS - 1)
        runCurrent()
        assertEquals(1, gateway.detailRequests.size)
        advanceUntilIdle()
        assertEquals(2, gateway.detailRequests.size)
        val query = gateway.detailRequests.last().query
        assertEquals(SiteDetailRangePresetUi.CUSTOM, query.preset)
        assertEquals("2026-07-15", query.customStartDate)
        assertEquals("2026-07-15", query.customEndDate)

        viewModel.refreshDetail()
        advanceUntilIdle()
        assertTrue(gateway.forceRefreshes.last())
    }

    @Test
    fun clarityControlsAreBoundedAndDetailErrorsAreShown() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("clarity" to SiteServiceRestoreUi.Available(dashboard("clarity"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        gateway.detailFailure = SiteServicesUiException("The provider request failed (HTTP 429).")
        viewModel.openDetail("clarity")
        advanceUntilIdle()
        assertEquals("The provider request failed (HTTP 429).", viewModel.uiState.value.detail?.error)

        listOf("Browser", "Device", "OS", "URL", "Bogus").forEach(viewModel::toggleClarityDimension)
        viewModel.setClarityDays(9)
        val query = viewModel.uiState.value.detail!!.query
        assertEquals(listOf("Browser", "Device", "OS"), query.clarityDimensions)
        assertEquals(3, query.clarityDays)
        viewModel.toggleClarityDimension("Device")
        assertEquals(listOf("Browser", "OS"), viewModel.uiState.value.detail!!.query.clarityDimensions)
    }

    @Test
    fun anotherProvidersRouteClosesTheDetailButLeavingKeepsIt() = runTest(dispatcher) {
        val gateway = FakeGateway(
            restore = mapOf(
                "plausible" to SiteServiceRestoreUi.Available(dashboard("plausible")),
                "umami" to SiteServiceRestoreUi.Available(dashboard("umami")),
            ),
        )
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("plausible")
        viewModel.updateResourceSearch("docs")
        viewModel.openDetail("plausible")
        advanceUntilIdle()

        viewModel.setActiveRoute(null)
        viewModel.setActiveRoute("plausible")
        assertEquals("plausible", viewModel.uiState.value.detail?.providerId)
        assertEquals("docs", viewModel.uiState.value.resourceSearch)

        viewModel.setActiveRoute("umami")
        assertNull(viewModel.uiState.value.detail)
        assertEquals("", viewModel.uiState.value.resourceSearch)
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun rawResponsesAndDetailUnwindThroughBack() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("betterStack" to SiteServiceRestoreUi.Available(dashboard("betterStack"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("betterStack")
        viewModel.openDetail("betterStack", "betterStack-2")
        advanceUntilIdle()
        viewModel.showRawResponses(true)

        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.detail!!.showRawResponses)
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.detail)
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun refreshThatDropsTheOpenResourceClosesItsDetail() = runTest(dispatcher) {
        val gateway = FakeGateway(restore = mapOf("uptimeRobot" to SiteServiceRestoreUi.Available(dashboard("uptimeRobot"))))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.openDetail("uptimeRobot", "uptimeRobot-2")
        advanceUntilIdle()
        gateway.refreshDashboard = dashboard("uptimeRobot").copy(resources = dashboard("uptimeRobot").resources.take(1))

        viewModel.refresh("uptimeRobot")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.detail)
    }

    private fun viewModel(gateway: FakeGateway, saved: SavedStateHandle = SavedStateHandle()) =
        SiteServicesViewModel(gateway, saved) { now }

    private fun dashboard(
        providerId: String,
        cacheState: SiteServiceCacheState = SiteServiceCacheState.CACHED_FRESH,
        fetchedAt: Long = 1L,
    ) = SiteServiceDashboardUi(
        providerId = providerId,
        accountName = "$providerId account",
        accountDetail = null,
        status = "Connected",
        resources = (1..2).map { index ->
            SiteResourceUi(
                "$providerId-$index", "Resource $index", "https://r$index.example", "https://r$index.example", "Up", null,
                listOf(SiteMetricUi("m", "Visitors", index * 10.0, SiteMetricUnit.COUNT)),
            )
        },
        metrics = listOf(SiteMetricUi("total", "Visitors", 30.0, SiteMetricUnit.COUNT)),
        warnings = emptyList(),
        fetchedAtMillis = fetchedAt,
        cacheState = cacheState,
    )

    private inner class FakeGateway(
        restore: Map<String, SiteServiceRestoreUi> = emptyMap(),
        private val restoreFailure: Throwable? = null,
        var connectFailure: Throwable? = null,
        var connectGate: CompletableDeferred<Unit>? = null,
        var refreshFailure: Throwable? = null,
    ) : SiteServicesUiGateway {
        var restoreResult = restore
        var refreshDashboard: SiteServiceDashboardUi? = null
        var detailGate: CompletableDeferred<Unit>? = null
        var detailFailure: Throwable? = null
        var lastInput: SiteServiceConnectionInputUi? = null
        val refreshCalls = ArrayList<String>()
        val disconnects = ArrayList<String>()
        val detailRequests = ArrayList<SiteServiceDetailRequestUi>()
        val forceRefreshes = ArrayList<Boolean>()

        override val googleOAuthReadiness: SiteGoogleOAuthReadinessUi = SiteGoogleOAuthReadinessUi.Ready

        override suspend fun restore(): Result<SiteServicesRestoreUi> =
            restoreFailure?.let { Result.failure(it) } ?: Result.success(SiteServicesRestoreUi(restoreResult))

        override suspend fun connect(providerId: String, input: SiteServiceConnectionInputUi): Result<SiteServiceDashboardUi> {
            lastInput = input
            connectGate?.await()
            return connectFailure?.let { Result.failure(it) } ?: Result.success(dashboard(providerId, SiteServiceCacheState.LIVE))
        }

        override suspend fun connectGoogle(providerId: String): Result<SiteServiceDashboardUi> {
            connectGate?.await()
            return Result.success(dashboard(providerId, SiteServiceCacheState.LIVE))
        }

        override suspend fun refresh(providerId: String): Result<SiteServiceDashboardUi> {
            refreshCalls += providerId
            return refreshFailure?.let { Result.failure(it) }
                ?: Result.success(refreshDashboard ?: dashboard(providerId, SiteServiceCacheState.LIVE, now))
        }

        override suspend fun loadDetail(
            request: SiteServiceDetailRequestUi,
            forceRefresh: Boolean,
            onPartial: suspend (SiteServiceDetailUi) -> Unit,
        ): Result<SiteServiceDetailUi> {
            detailRequests += request
            forceRefreshes += forceRefresh
            val payload = SiteServiceDetailUi(
                providerId = request.providerId,
                resourceId = request.resourceId.orEmpty(),
                title = "Detail",
                sections = emptyList(),
                series = emptyList(),
                tables = emptyList(),
                rawResponses = emptyMap(),
                warnings = emptyList(),
                fetchedAtMillis = now,
            )
            detailGate?.let { gate ->
                onPartial(payload.copy(isPartial = true))
                gate.await()
            }
            return detailFailure?.let { Result.failure(it) } ?: Result.success(payload)
        }

        override suspend fun disconnect(providerId: String): Result<Unit> {
            disconnects += providerId
            return Result.success(Unit)
        }
    }
}
