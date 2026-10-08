package com.apoorvdarshan.verceltics.ui.hosting

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.RailwayTokenType
import java.io.IOException
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostingProvidersViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun restoreAppliesEverySlotIndependentlyInCatalogOrder() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(
            restored = mapOf(
                "render" to HostingRestoreUi.Available(dashboard("render")),
                "railway" to HostingRestoreUi.Available(dashboard("railway")),
                "heroku" to HostingRestoreUi.SavedUnavailable("The saved Heroku connection could not be opened."),
                "fly" to HostingRestoreUi.SavedWithoutInventory(HostingAccountUi("personal", "Fly.io Personal", null), "https://fly.io/dashboard"),
            ),
        )
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        assertEquals(HostingConnectionStatus.RESTORING, viewModel.uiState.value.provider("render").status)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("railway", "render", "heroku", "fly"), state.connectedProviderIds.toList())
        assertEquals(HostingConnectionStatus.CONNECTED, state.provider("render").status)
        assertEquals(HostingConnectionStatus.SAVED_UNAVAILABLE, state.provider("heroku").status)
        assertEquals("The saved Heroku connection could not be opened.", state.provider("heroku").error)
        assertEquals("https://fly.io/dashboard", state.provider("fly").dashboardUrl)
        assertEquals(HostingConnectionStatus.DISCONNECTED, state.provider("awsAmplify").status)
        assertEquals(0, gateway.refreshCalls.size)
    }

    @Test
    fun secureWindowOnlyCoversVisibleCredentialFormsAndInFlightSubmissions() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        gateway.connectRelease = CompletableDeferred()
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible("railway", true)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible("railway", false)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible("firebase", true)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible("render", true)
        assertEquals("render", viewModel.uiState.value.visibleProviderId)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        viewModel.setRouteVisible("railway", true)
        val rawToken = "never-publish-railway-token"
        viewModel.connect(HostingCredentials.Railway(SecretValue.of(rawToken), RailwayTokenType.ACCOUNT))
        runCurrent()
        assertEquals(HostingOperation.CONNECTING, viewModel.uiState.value.provider("railway").operation)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        gateway.connectRelease!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(HostingConnectionStatus.CONNECTED, viewModel.uiState.value.provider("railway").status)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        assertFalse(viewModel.uiState.value.toString().contains(rawToken))
    }

    @Test
    fun connectFailureKeepsFormAndShowsOnlyTheSafeMessage() = runTest(dispatcher) {
        val gateway = FakeHostingGateway()
        gateway.connectResult = { Result.failure(HostingUiException("Render rejected these credentials.")) }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(HostingCredentials.Render(SecretValue.of("bad")))
        advanceUntilIdle()
        val render = viewModel.uiState.value.provider("render")
        assertEquals(HostingConnectionStatus.DISCONNECTED, render.status)
        assertEquals("Render rejected these credentials.", render.error)
        assertNull(render.operation)

        gateway.connectResult = { Result.failure(IOException("raw provider detail")) }
        viewModel.connect(HostingCredentials.Render(SecretValue.of("bad")))
        advanceUntilIdle()
        assertEquals("Render could not complete this request.", viewModel.uiState.value.provider("render").error)
    }

    @Test
    fun operationsAreIndependentPerProvider() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        gateway.refreshRelease = CompletableDeferred()
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.refresh("render")
        runCurrent()
        assertEquals(HostingOperation.REFRESHING, viewModel.uiState.value.provider("render").operation)
        viewModel.connect(HostingCredentials.Heroku(SecretValue.of("token")))
        advanceUntilIdle()
        assertEquals(HostingConnectionStatus.CONNECTED, viewModel.uiState.value.provider("heroku").status)
        assertEquals(HostingOperation.REFRESHING, viewModel.uiState.value.provider("render").operation)

        gateway.refreshRelease!!.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.provider("render").operation)
        assertEquals(HostingCacheState.LIVE, viewModel.uiState.value.provider("render").dashboard?.cacheState)
    }

    @Test
    fun failedRefreshKeepsCachedInventoryWithNotice() = runTest(dispatcher) {
        val cached = dashboard("fly", cacheState = HostingCacheState.CACHED_FRESH)
        val gateway = FakeHostingGateway(restored = mapOf("fly" to HostingRestoreUi.Available(cached)))
        gateway.refreshResult = { Result.failure(HostingUiException("Fly.io is temporarily unavailable.")) }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.refresh("fly")
        advanceUntilIdle()
        val fly = viewModel.uiState.value.provider("fly")
        assertSame(cached, fly.dashboard)
        assertEquals("Fly.io is temporarily unavailable.", fly.error)
        assertEquals("Showing the last saved Fly.io inventory.", fly.notice)
    }

    @Test
    fun foregroundRefreshesOnlyStaleInventoriesOnce() = runTest(dispatcher) {
        val now = 50_000_000L
        val gateway = FakeHostingGateway(
            restored = mapOf(
                "render" to HostingRestoreUi.Available(dashboard("render", cacheState = HostingCacheState.CACHED_STALE, fetchedAt = 1L)),
                "heroku" to HostingRestoreUi.Available(dashboard("heroku", cacheState = HostingCacheState.CACHED_FRESH, fetchedAt = now - 1_000L)),
            ),
        )
        gateway.refreshResult = { id -> Result.success(dashboard(id, fetchedAt = now)) }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle(), nowMillis = { now })
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(listOf("render"), gateway.refreshCalls)
        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(listOf("render"), gateway.refreshCalls)

        val later = HostingProvidersViewModel(gateway, SavedStateHandle(), nowMillis = { now + HostingConnectionStore.CACHE_LIFETIME_MILLIS })
        advanceUntilIdle()
        later.setRouteVisible("heroku", true)
        advanceUntilIdle()
        assertEquals(listOf("render", "heroku"), gateway.refreshCalls)
    }

    @Test
    fun resourceDetailLoadsAndSurvivesRecreationOnlyWhileTheResourceExists() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        val savedState = SavedStateHandle()
        val viewModel = HostingProvidersViewModel(gateway, savedState)
        advanceUntilIdle()

        viewModel.openResource("render", "missing")
        assertNull(viewModel.uiState.value.provider("render").selectedResourceId)
        viewModel.openResource("render", "render-1")
        advanceUntilIdle()
        val render = viewModel.uiState.value.provider("render")
        assertEquals("render-1", render.selectedResourceId)
        assertEquals("render-1", render.resourceWorkspace?.resourceId)
        assertEquals("render-1", savedState[HostingProvidersViewModel.selectedResourceKey("render")])

        val recreated = HostingProvidersViewModel(gateway, savedState)
        advanceUntilIdle()
        assertEquals("render-1", recreated.uiState.value.provider("render").selectedResourceId)
        assertEquals(2, gateway.loadedResources.count { it == "render" to "render-1" })

        val stale = SavedStateHandle(mapOf(HostingProvidersViewModel.selectedResourceKey("render") to "deleted"))
        val missing = HostingProvidersViewModel(gateway, stale)
        advanceUntilIdle()
        assertNull(missing.uiState.value.provider("render").selectedResourceId)
        assertNull(stale[HostingProvidersViewModel.selectedResourceKey("render")])
    }

    @Test
    fun deploymentHistoryIsCachedForOneHundredEightySecondsPerAccountAndResource() = runTest(dispatcher) {
        var now = 5_000_000L
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle(), nowMillis = { now })
        advanceUntilIdle()

        viewModel.openResource("render", "render-1")
        advanceUntilIdle()
        assertEquals(1, gateway.loadedResources.size)
        val loaded = viewModel.uiState.value.provider("render").resourceWorkspace

        // Within 180 s: the cached history is shown immediately and nothing is fetched.
        viewModel.closeResource("render")
        now += 179_999L
        viewModel.openResource("render", "render-1")
        assertSame(loaded, viewModel.uiState.value.provider("render").resourceWorkspace)
        assertFalse(viewModel.uiState.value.provider("render").isLoadingResource)
        advanceUntilIdle()
        assertEquals(1, gateway.loadedResources.size)

        // A different resource has its own entry.
        viewModel.openResource("render", "render-2")
        advanceUntilIdle()
        assertEquals(listOf("render" to "render-1", "render" to "render-2"), gateway.loadedResources)

        // At 180 s the stale copy stays visible while a fresh one loads.
        now += 1L
        viewModel.openResource("render", "render-1")
        assertSame(loaded, viewModel.uiState.value.provider("render").resourceWorkspace)
        assertTrue(viewModel.uiState.value.provider("render").isLoadingResource)
        advanceUntilIdle()
        assertEquals(3, gateway.loadedResources.size)

        // The toolbar refresh always bypasses a fresh entry.
        viewModel.refreshSelectedResource("render")
        advanceUntilIdle()
        assertEquals(4, gateway.loadedResources.size)

        // Disconnecting forgets the account's cached histories.
        viewModel.requestDisconnectConfirmation("render")
        viewModel.confirmDisconnect("render")
        advanceUntilIdle()
        gateway.connectResult = { Result.success(dashboard("render")) }
        viewModel.connect(HostingCredentials.Render(SecretValue.of("again")))
        advanceUntilIdle()
        viewModel.openResource("render", "render-1")
        advanceUntilIdle()
        assertEquals(5, gateway.loadedResources.size)
    }

    @Test
    fun confirmedActionInvalidatesTheCachedHistory() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("railway" to HostingRestoreUi.Available(dashboard("railway"))))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle(), nowMillis = { 1_000L }, actionRefreshDelayMillis = 1_000L)
        advanceUntilIdle()
        viewModel.openResource("railway", "railway-1")
        advanceUntilIdle()
        assertEquals(1, gateway.loadedResources.size)

        viewModel.requestPrimaryAction("railway")
        viewModel.confirmPrimaryAction("railway")
        // Navigating away before the reload must not leave the pre-write history cached.
        runCurrent()
        viewModel.closeResource("railway")
        advanceUntilIdle()
        viewModel.openResource("railway", "railway-1")
        advanceUntilIdle()
        assertEquals(2, gateway.loadedResources.size)
    }

    @Test
    fun primaryActionNeedsConfirmationTargetsLatestDeploymentAndReloadsHistory() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("railway" to HostingRestoreUi.Available(dashboard("railway"))))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle(), actionRefreshDelayMillis = 1_000L)
        advanceUntilIdle()
        viewModel.openResource("railway", "railway-1")
        advanceUntilIdle()

        // A write request can only come from the visible confirmation dialog.
        viewModel.confirmPrimaryAction("railway")
        advanceUntilIdle()
        assertTrue(gateway.actions.isEmpty())

        viewModel.requestPrimaryAction("railway")
        assertTrue(viewModel.uiState.value.provider("railway").showActionConfirmation)
        assertTrue(viewModel.handleBack("railway"))
        assertFalse(viewModel.uiState.value.provider("railway").showActionConfirmation)
        assertTrue(gateway.actions.isEmpty())

        val loadsBefore = gateway.loadedResources.size
        viewModel.requestPrimaryAction("railway")
        viewModel.confirmPrimaryAction("railway")
        runCurrent()
        assertEquals(listOf(Triple("railway", "railway-1", "railway-1-deployment-0")), gateway.actions)
        assertEquals("Redeploy request accepted.", viewModel.uiState.value.provider("railway").actionMessage)
        assertFalse(viewModel.uiState.value.provider("railway").isPerformingAction)
        assertEquals(loadsBefore, gateway.loadedResources.size)
        advanceTimeBy(1_001L)
        advanceUntilIdle()
        assertEquals(loadsBefore + 1, gateway.loadedResources.size)
    }

    @Test
    fun primaryActionFailureIsReportedOnTheResource() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("heroku" to HostingRestoreUi.Available(dashboard("heroku"))))
        gateway.actionResult = Result.failure(HostingUiException("Heroku denied access. Check the credential's permissions."))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openResource("heroku", "heroku-1")
        advanceUntilIdle()
        viewModel.requestPrimaryAction("heroku")
        viewModel.confirmPrimaryAction("heroku")
        advanceUntilIdle()

        val heroku = viewModel.uiState.value.provider("heroku")
        assertEquals("Heroku denied access. Check the credential's permissions.", heroku.actionError)
        assertNull(heroku.actionMessage)
        assertFalse(heroku.isPerformingAction)
    }

    @Test
    fun firebaseHasNoPrimaryAction() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("firebase" to HostingRestoreUi.Available(dashboard("firebase"))))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openResource("firebase", "firebase-1")
        advanceUntilIdle()
        viewModel.requestPrimaryAction("firebase")
        assertFalse(viewModel.uiState.value.provider("firebase").showActionConfirmation)
    }

    @Test
    fun backClosesDialogsThenDetailThenLeavesTheRoute() = runTest(dispatcher) {
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.openResource("render", "render-1")
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("render")
        assertTrue(viewModel.handleBack("render"))
        assertFalse(viewModel.uiState.value.provider("render").showDisconnectConfirmation)
        assertTrue(viewModel.handleBack("render"))
        assertNull(viewModel.uiState.value.provider("render").selectedResourceId)
        assertFalse(viewModel.handleBack("render"))
    }

    @Test
    fun disconnectNeedsConfirmationAndClearsSelection() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val gateway = FakeHostingGateway(restored = mapOf("render" to HostingRestoreUi.Available(dashboard("render"))))
        val viewModel = HostingProvidersViewModel(gateway, savedState)
        advanceUntilIdle()
        viewModel.openResource("render", "render-1")
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("render")
        assertTrue(viewModel.uiState.value.provider("render").showDisconnectConfirmation)
        viewModel.confirmDisconnect("render")
        advanceUntilIdle()

        val render = viewModel.uiState.value.provider("render")
        assertEquals(HostingConnectionStatus.DISCONNECTED, render.status)
        assertNull(render.dashboard)
        assertNull(render.selectedResourceId)
        assertNull(savedState[HostingProvidersViewModel.selectedResourceKey("render")])
        assertEquals(listOf("render"), gateway.disconnected)
        assertTrue("render" !in viewModel.uiState.value.connectedProviderIds)
    }

    @Test
    fun firebaseSignInIsRequestedOnceThenRetriedAfterForeground() = runTest(dispatcher) {
        val gateway = FakeHostingGateway()
        var signedIn = false
        gateway.connectResult = { credentials ->
            if (signedIn) {
                Result.success(dashboard(credentials.provider.id))
            } else {
                Result.failure(HostingUiException("Continue with Google to connect Firebase Hosting.", requiresGoogleSignIn = true))
            }
        }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(HostingCredentials.Firebase("studio-prod"))
        advanceUntilIdle()
        val request = viewModel.uiState.value.provider("firebase").googleSignInRequest!!
        assertEquals(GoogleAccessTokenSource.FIREBASE_HOSTING_SCOPES, request.scopes)
        assertTrue(viewModel.uiState.value.provider("firebase").awaitingGoogleSignIn)
        assertNull(viewModel.uiState.value.provider("firebase").error)

        // Resuming before the shell consumed the request must not retry.
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.connects.size)

        viewModel.onGoogleSignInRequestHandled("firebase", request.id)
        assertNull(viewModel.uiState.value.provider("firebase").googleSignInRequest)
        signedIn = true
        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(2, gateway.connects.size)
        assertEquals("studio-prod", (gateway.connects.last() as HostingCredentials.Firebase).projectId)
        assertEquals(HostingConnectionStatus.CONNECTED, viewModel.uiState.value.provider("firebase").status)
        assertFalse(viewModel.uiState.value.provider("firebase").awaitingGoogleSignIn)
    }

    @Test
    fun firebaseAutomaticRetryNeverLoopsIntoAnotherSignInPrompt() = runTest(dispatcher) {
        val gateway = FakeHostingGateway()
        gateway.connectResult = {
            Result.failure(HostingUiException("Continue with Google to connect Firebase Hosting.", requiresGoogleSignIn = true))
        }
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.connect(HostingCredentials.Firebase("studio-prod"))
        advanceUntilIdle()
        viewModel.onGoogleSignInRequestHandled("firebase", viewModel.uiState.value.provider("firebase").googleSignInRequest!!.id)
        viewModel.onGoogleSignInCompleted()
        advanceUntilIdle()

        val firebase = viewModel.uiState.value.provider("firebase")
        assertEquals(2, gateway.connects.size)
        assertNull(firebase.googleSignInRequest)
        assertFalse(firebase.awaitingGoogleSignIn)
        assertEquals("Google sign-in did not complete. Continue with Google to try again.", firebase.error)
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(2, gateway.connects.size)
    }

    @Test
    fun cancelledConnectReconcilesWithTheSavedSlot() = runTest(dispatcher) {
        val gateway = FakeHostingGateway()
        gateway.connectRelease = CompletableDeferred()
        val viewModel = HostingProvidersViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.connect(HostingCredentials.DigitalOcean(SecretValue.of("dop")))
        runCurrent()
        viewModel.cancelOperation("digitalOcean")
        advanceUntilIdle()
        val digitalOcean = viewModel.uiState.value.provider("digitalOcean")
        assertEquals(HostingConnectionStatus.DISCONNECTED, digitalOcean.status)
        assertEquals("Request cancelled.", digitalOcean.notice)
        assertTrue(gateway.connectCancelled)

        gateway.restored = mapOf("digitalOcean" to HostingRestoreUi.Available(dashboard("digitalOcean")))
        gateway.connectRelease = CompletableDeferred()
        viewModel.connect(HostingCredentials.DigitalOcean(SecretValue.of("dop")))
        runCurrent()
        viewModel.cancelOperation("digitalOcean")
        advanceUntilIdle()
        assertEquals(HostingConnectionStatus.CONNECTED, viewModel.uiState.value.provider("digitalOcean").status)
        assertEquals(
            "The connection completed before cancellation and remains saved.",
            viewModel.uiState.value.provider("digitalOcean").notice,
        )
    }

    @Test
    fun sampleGatewayPreviewsFictionalProvidersWithoutConnecting() = runTest(dispatcher) {
        val viewModel = HostingProvidersViewModel(SampleHostingProviderUiGateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(setOf("railway", "render", "fly"), viewModel.uiState.value.connectedProviderIds)
        val render = viewModel.uiState.value.provider("render").dashboard!!
        assertTrue(render.resources.all { it.dashboardUrl.startsWith("https://") })

        viewModel.openResource("render", render.resources.first().id)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.provider("render").resourceWorkspace!!.deployments.isNotEmpty())
        viewModel.requestPrimaryAction("render")
        viewModel.confirmPrimaryAction("render")
        runCurrent()
        assertTrue(viewModel.uiState.value.provider("render").actionMessage!!.contains("Sample data never sends requests"))

        viewModel.connect(HostingCredentials.Heroku(SecretValue.of("token")))
        advanceUntilIdle()
        assertEquals("Exit sample data to connect Heroku.", viewModel.uiState.value.provider("heroku").error)
        viewModel.restore()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.provider("heroku").error)
    }
}

internal fun dashboard(
    providerId: String,
    cacheState: HostingCacheState = HostingCacheState.LIVE,
    fetchedAt: Long = 1_000L,
    resourceCount: Int = 2,
) = HostingDashboardUi(
    providerId = providerId,
    account = HostingAccountUi("$providerId-account", "$providerId account", "owner@example.com"),
    resources = (1..resourceCount).map { index ->
        HostingResourceUi(
            id = "$providerId-$index",
            name = "$providerId app $index",
            subtitle = "Subtitle $index",
            url = "https://$providerId-$index.example",
            status = if (index == 1) "Running" else "Failed",
            region = "iad",
            kind = "App",
            updatedAtMillis = 1_000L,
            dashboardUrl = "https://console.example/$providerId/$index",
        )
    },
    loadedResourceCount = resourceCount,
    warnings = emptyList(),
    fetchedAtMillis = fetchedAt,
    cacheState = cacheState,
    dashboardUrl = "https://console.example/$providerId",
)

internal class FakeHostingGateway(
    var restored: Map<String, HostingRestoreUi> = emptyMap(),
) : HostingProviderUiGateway {
    var connectRelease: CompletableDeferred<Unit>? = null
    var refreshRelease: CompletableDeferred<Unit>? = null
    var connectCancelled = false
    var connectResult: (HostingCredentials) -> Result<HostingDashboardUi> = { Result.success(dashboard(it.provider.id)) }
    var refreshResult: (String) -> Result<HostingDashboardUi> = { Result.success(dashboard(it)) }
    var actionResult: Result<String>? = null
    val connects = mutableListOf<HostingCredentials>()
    val refreshCalls = mutableListOf<String>()
    val loadedResources = mutableListOf<Pair<String, String>>()
    val actions = mutableListOf<Triple<String, String, String?>>()
    val disconnected = mutableListOf<String>()

    override suspend fun restore(): Result<Map<String, HostingRestoreUi>> = Result.success(restored)

    override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> {
        connects += credentials
        try {
            connectRelease?.await()
        } catch (error: kotlinx.coroutines.CancellationException) {
            connectCancelled = true
            throw error
        }
        return connectResult(credentials)
    }

    override suspend fun refresh(providerId: String): Result<HostingDashboardUi> {
        refreshCalls += providerId
        refreshRelease?.await()
        return refreshResult(providerId)
    }

    override suspend fun loadResource(providerId: String, resource: HostingResourceUi): Result<HostingResourceWorkspaceUi> {
        loadedResources += providerId to resource.id
        val deployments = (0..1).map { index ->
            HostingDeploymentUi("${resource.id}-deployment-$index", "Deploy $index", "SUCCESS", 1_000L - index, null, "main", "Commit $index")
        }
        return Result.success(HostingResourceWorkspaceUi(providerId, resource.id, deployments, deployments.size))
    }

    override suspend fun performPrimaryAction(
        providerId: String,
        resource: HostingResourceUi,
        latestDeploymentId: String?,
    ): Result<String> {
        actions += Triple(providerId, resource.id, latestDeploymentId)
        return actionResult ?: Result.success(
            "${com.apoorvdarshan.verceltics.data.hosting.HostingProvider.fromId(providerId)!!.primaryActionLabel} request accepted.",
        )
    }

    override suspend fun disconnect(providerId: String): Result<Unit> {
        disconnected += providerId
        return Result.success(Unit)
    }
}
