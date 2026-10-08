package com.apoorvdarshan.verceltics.ui.netlify

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetlifyViewModelTest {
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
    fun cachedRestoreIsOfflineAndKeepsTruthfulStaleInventory() = runTest(dispatcher) {
        val cached = DASHBOARD.copy(cacheState = NetlifyCacheState.CACHED_STALE)
        val gateway = FakeGateway(NetlifyRestoreUi.Available(cached))
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())

        advanceUntilIdle()

        assertEquals(1, gateway.restoreCalls)
        assertEquals(0, gateway.refreshCalls)
        assertSame(cached, viewModel.uiState.value.dashboard)
        assertEquals(NetlifyConnectionStatus.CONNECTED, viewModel.uiState.value.status)
    }

    @Test
    fun foregroundRefreshWaitsForDelayedCachedRestoreAndRunsExactlyOnce() = runTest(dispatcher) {
        val restoreRelease = CompletableDeferred<Unit>()
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD)).apply {
            this.restoreRelease = restoreRelease
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        runCurrent()

        viewModel.onForeground()
        assertEquals(0, gateway.refreshCalls)
        restoreRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, gateway.refreshCalls)
        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.refreshCalls)
    }

    @Test
    fun tokenIsEphemeralAndSecureWindowOnlyFollowsVisibleCredentialFlow() = runTest(dispatcher) {
        val rawToken = "never-publish-netlify-token"
        val gateway = FakeGateway(NetlifyRestoreUi.NotConnected)
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible(true)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)

        viewModel.connect(SecretValue.of(rawToken))
        advanceUntilIdle()

        assertEquals(rawToken, gateway.lastToken?.use { it })
        assertFalse(viewModel.uiState.value.toString().contains(rawToken))
        assertEquals(NetlifyConnectionStatus.CONNECTED, viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
    }

    @Test
    fun cancelInFlightConnectRestoresDisconnectedStateAndIgnoresLateResult() = runTest(dispatcher) {
        val gateway = FakeGateway(NetlifyRestoreUi.NotConnected).apply {
            connectRelease = CompletableDeferred()
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(SecretValue.of("temporary"))
        runCurrent()
        gateway.connectStarted.await()
        viewModel.cancelOperation()
        runCurrent()

        assertTrue(gateway.connectCancelled.await())
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertEquals("Request cancelled.", viewModel.uiState.value.notice)
        assertNull(viewModel.uiState.value.dashboard)
    }

    @Test
    fun cancellationAfterCommitReconcilesToSavedConnectedState() = runTest(dispatcher) {
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val gateway = object : NetlifyUiGateway {
            override suspend fun restore(): Result<NetlifyRestoreUi> = Result.success(
                if (committed) NetlifyRestoreUi.Available(DASHBOARD) else NetlifyRestoreUi.NotConnected,
            )

            override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> =
                withContext(NonCancellable) {
                    committed = true
                    accepted.complete(Unit)
                    release.await()
                    Result.success(DASHBOARD)
                }

            override suspend fun refresh() = Result.success(DASHBOARD)
            override suspend fun loadSite(siteId: String) = Result.success(SITE_WORKSPACE)
            override suspend fun disconnect() = Result.success(Unit)
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(SecretValue.of("committed"))
        runCurrent()
        accepted.await()
        viewModel.cancelOperation()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(NetlifyConnectionStatus.CONNECTED, viewModel.uiState.value.status)
        assertSame(DASHBOARD, viewModel.uiState.value.dashboard)
        assertEquals(
            "The connection completed before cancellation and remains saved.",
            viewModel.uiState.value.notice,
        )
    }

    @Test
    fun failedRefreshPreservesCachedDashboardAndRedactsRawFailure() = runTest(dispatcher) {
        val cached = DASHBOARD.copy(cacheState = NetlifyCacheState.CACHED_FRESH)
        val gateway = FakeGateway(NetlifyRestoreUi.Available(cached)).apply {
            refreshResult = Result.failure(IOException("provider-secret-detail"))
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertSame(cached, viewModel.uiState.value.dashboard)
        assertEquals("Showing the last saved Netlify inventory.", viewModel.uiState.value.notice)
        assertEquals("Netlify could not complete this request.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.toString().contains("provider-secret-detail"))
    }

    @Test
    fun coldRestoreReopensSavedSiteDetailOnlyWhenItStillExists() = runTest(dispatcher) {
        val savedState = SavedStateHandle(
            mapOf(NetlifyViewModel.SELECTED_SITE_ID to SITE.id),
        )
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD))

        val recreated = NetlifyViewModel(gateway, savedState)
        advanceUntilIdle()

        assertEquals(SITE.id, recreated.uiState.value.selectedSiteId)
        assertSame(SITE_WORKSPACE, recreated.uiState.value.selectedSiteWorkspace)
        assertEquals(listOf(SITE.id), gateway.loadedSiteIds)

        val missingState = SavedStateHandle(
            mapOf(NetlifyViewModel.SELECTED_SITE_ID to "removed-site"),
        )
        val missing = NetlifyViewModel(gateway, missingState)
        advanceUntilIdle()

        assertNull(missing.uiState.value.selectedSiteId)
        assertNull(missingState.get<String>(NetlifyViewModel.SELECTED_SITE_ID))
    }

    @Test
    fun disconnectRequiresConfirmationAndClearsSavedSiteSelection() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD))
        val viewModel = NetlifyViewModel(gateway, savedState)
        advanceUntilIdle()
        viewModel.openSite(SITE.id)
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        assertTrue(viewModel.uiState.value.showDisconnectConfirmation)
        assertEquals(0, gateway.disconnectCalls)

        viewModel.confirmDisconnect()
        advanceUntilIdle()

        assertEquals(1, gateway.disconnectCalls)
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.selectedSiteId)
        assertNull(savedState.get<String>(NetlifyViewModel.SELECTED_SITE_ID))
    }

    @Test
    fun redeployIsOnlySentFromAnExplicitConfirmationAndReloadsTheSite() = runTest(dispatcher) {
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD))
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openSite(SITE.id)
        advanceUntilIdle()
        assertEquals(listOf(SITE.id), gateway.loadedSiteIds)

        // Confirming without a visible dialog is a no-op; dismissing never sends anything.
        viewModel.confirmRedeploy()
        viewModel.requestRedeployConfirmation()
        assertTrue(viewModel.uiState.value.showRedeployConfirmation)
        viewModel.dismissRedeployConfirmation()
        viewModel.confirmRedeploy()
        advanceUntilIdle()
        assertTrue(gateway.redeployedSiteIds.isEmpty())

        viewModel.requestRedeployConfirmation()
        viewModel.confirmRedeploy()
        runCurrent()
        assertFalse(viewModel.uiState.value.showRedeployConfirmation)
        assertEquals(listOf(SITE.id), gateway.redeployedSiteIds)
        assertEquals("Redeploy request accepted.", viewModel.uiState.value.redeployMessage)
        assertEquals(listOf(SITE.id), gateway.loadedSiteIds)

        // iOS waits a second, then force-reloads (the fresh cache entry must not be served).
        advanceUntilIdle()
        assertEquals(listOf(SITE.id, SITE.id), gateway.loadedSiteIds)
        assertFalse(viewModel.uiState.value.isRedeploying)
    }

    @Test
    fun redeployFailureIsReportedSafelyAndBackDismissesTheConfirmation() = runTest(dispatcher) {
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD)).apply {
            redeployResult = Result.failure(IOException("raw-provider-secret"))
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openSite(SITE.id)
        advanceUntilIdle()

        viewModel.requestRedeployConfirmation()
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.showRedeployConfirmation)
        assertEquals(SITE.id, viewModel.uiState.value.selectedSiteId)

        viewModel.requestRedeployConfirmation()
        viewModel.confirmRedeploy()
        advanceUntilIdle()

        assertEquals("Netlify could not complete this request.", viewModel.uiState.value.redeployError)
        assertNull(viewModel.uiState.value.redeployMessage)
        assertFalse(viewModel.uiState.value.toString().contains("raw-provider-secret"))
        assertEquals(listOf(SITE.id), gateway.loadedSiteIds)
    }

    @Test
    fun siteHistoryIsCachedForOneHundredEightySeconds() = runTest(dispatcher) {
        var now = 1_000_000L
        val gateway = FakeGateway(NetlifyRestoreUi.Available(DASHBOARD))
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle(), nowMillis = { now })
        advanceUntilIdle()

        viewModel.openSite(SITE.id)
        advanceUntilIdle()
        assertEquals(1, gateway.loadedSiteIds.size)

        // Reopening within 180 s shows the cached history with no request.
        viewModel.closeSite()
        now += 179_999L
        viewModel.openSite(SITE.id)
        assertSame(SITE_WORKSPACE, viewModel.uiState.value.selectedSiteWorkspace)
        assertFalse(viewModel.uiState.value.isLoadingSite)
        advanceUntilIdle()
        assertEquals(1, gateway.loadedSiteIds.size)

        // After 180 s the stale copy is shown immediately while a fresh one loads.
        viewModel.closeSite()
        now += 1L
        viewModel.openSite(SITE.id)
        assertSame(SITE_WORKSPACE, viewModel.uiState.value.selectedSiteWorkspace)
        assertTrue(viewModel.uiState.value.isLoadingSite)
        advanceUntilIdle()
        assertEquals(2, gateway.loadedSiteIds.size)

        // An explicit refresh always bypasses a fresh cache entry.
        viewModel.refreshSelectedSite()
        advanceUntilIdle()
        assertEquals(3, gateway.loadedSiteIds.size)

        // Disconnecting closes the site and drops its cached history.
        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedSiteWorkspace)
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
    }

    private class FakeGateway(
        var restored: NetlifyRestoreUi,
    ) : NetlifyUiGateway {
        var restoreCalls = 0
        var connectCalls = 0
        var refreshCalls = 0
        var disconnectCalls = 0
        var lastToken: SecretValue? = null
        var connectStarted = CompletableDeferred<Unit>()
        var connectRelease = CompletableDeferred(Unit)
        var connectCancelled = CompletableDeferred<Boolean>()
        var restoreRelease = CompletableDeferred(Unit)
        var refreshResult: Result<NetlifyDashboardUi> = Result.success(DASHBOARD)
        val loadedSiteIds = mutableListOf<String>()
        val redeployedSiteIds = mutableListOf<String>()
        var redeployResult: Result<String> = Result.success("Redeploy request accepted.")

        override suspend fun redeploySite(siteId: String): Result<String> {
            redeployedSiteIds += siteId
            return redeployResult
        }

        override suspend fun restore(): Result<NetlifyRestoreUi> {
            restoreCalls += 1
            restoreRelease.await()
            return Result.success(restored)
        }

        override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> {
            connectCalls += 1
            lastToken = personalToken
            connectStarted.complete(Unit)
            return try {
                connectRelease.await()
                Result.success(DASHBOARD)
            } finally {
                if (!connectCancelled.isCompleted) {
                    connectCancelled.complete(!connectRelease.isCompleted)
                }
            }
        }

        override suspend fun refresh(): Result<NetlifyDashboardUi> {
            refreshCalls += 1
            return refreshResult
        }

        override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> {
            loadedSiteIds += siteId
            return if (siteId == SITE.id) {
                Result.success(SITE_WORKSPACE)
            } else {
                Result.failure(NetlifyUiException("Site unavailable."))
            }
        }

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalls += 1
            return Result.success(Unit)
        }
    }

    private companion object {
        val SITE = NetlifySiteUi(
            id = "site-1",
            name = "Example",
            subtitle = "example.netlify.app",
            url = "https://example.netlify.app",
            status = "current",
            updatedAtMillis = 42L,
        )
        val DASHBOARD = NetlifyDashboardUi(
            account = NetlifyAccountUi("account-1", "Example Account", "owner@example.com"),
            sites = listOf(SITE),
            loadedSiteCount = 1,
            providerInventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = 42L,
            cacheState = NetlifyCacheState.LIVE,
        )
        val SITE_WORKSPACE = NetlifySiteWorkspaceUi(
            siteId = SITE.id,
            details = NetlifyResourceUi.Available(
                NetlifySiteDetailsUi(SITE, emptyList(), null, null),
            ),
            deployments = NetlifyCollectionUi(emptyList(), 0, true, null),
            builds = NetlifyCollectionUi(emptyList(), 0, true, null),
        )
    }
}
