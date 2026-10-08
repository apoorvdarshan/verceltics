package com.apoorvdarshan.verceltics.ui.registrar

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegistrarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = NOW

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoreAppliesEachRegistrarIndependently() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi(
                "nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM),
                "porkbun" to RegistrarProviderRestoreUi.SavedWithoutInventory(RegistrarAccountUi("porkbun", "Porkbun · ab12")),
                "gandi" to RegistrarProviderRestoreUi.SavedUnavailable("The saved Gandi connection could not be opened."),
            ),
        )
        val viewModel = viewModel(gateway)
        assertEquals(RegistrarConnectionStatus.RESTORING, viewModel.uiState.value.provider("gandi").status)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RegistrarConnectionStatus.CONNECTED, state.provider("nameDotCom").status)
        assertSame(NAME_DOT_COM, state.provider("nameDotCom").dashboard)
        assertEquals(RegistrarConnectionStatus.SAVED_UNAVAILABLE, state.provider("porkbun").status)
        assertEquals("Porkbun · ab12", state.provider("porkbun").savedAccount?.displayName)
        assertEquals(RegistrarConnectionStatus.SAVED_UNAVAILABLE, state.provider("gandi").status)
        assertEquals("The saved Gandi connection could not be opened.", state.provider("gandi").error)
        assertEquals(RegistrarConnectionStatus.DISCONNECTED, state.provider("goDaddy").status)
        assertEquals(listOf("nameDotCom", "porkbun", "gandi"), state.connectedProviderIds.toList())
        assertEquals(0, gateway.refreshCalls.size)
    }

    @Test
    fun restoreFailureShowsAttentionAndConnectionForms() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi()).apply {
            restoreFailure = RegistrarUiException("Secure storage is unavailable.")
        }
        val viewModel = viewModel(gateway)

        advanceUntilIdle()

        assertEquals("Secure storage is unavailable.", viewModel.uiState.value.restoreError)
        assertTrue(viewModel.uiState.value.connectedProviderIds.isEmpty())
        assertEquals(RegistrarConnectionStatus.DISCONNECTED, viewModel.uiState.value.provider("namecheap").status)
    }

    @Test
    fun secureWindowOnlyFollowsAVisibleCredentialFlow() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi("nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM)))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setVisibleProvider("porkbun")
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setVisibleProvider("nameDotCom")
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.clearVisibleProvider("porkbun")
        assertEquals("nameDotCom", viewModel.uiState.value.visibleProviderId)
        viewModel.clearVisibleProvider("nameDotCom")
        assertNull(viewModel.uiState.value.visibleProviderId)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
    }

    @Test
    fun connectKeepsSecretsOutOfStateAndShowsTheLivePortfolio() = runTest(dispatcher) {
        val rawKey = "never-publish-porkbun-key"
        val gateway = FakeGateway(restoreUi()).apply { connectResult = Result.success(PORKBUN) }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setVisibleProvider("porkbun")

        viewModel.connect(request("porkbun", rawKey))
        assertEquals(RegistrarOperation.CONNECTING, viewModel.uiState.value.provider("porkbun").operation)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        advanceUntilIdle()

        assertEquals(rawKey, gateway.lastRequest?.apiKey?.use { it })
        val state = viewModel.uiState.value
        assertFalse(state.toString().contains(rawKey))
        assertFalse(gateway.lastRequest.toString().contains(rawKey))
        assertEquals(RegistrarConnectionStatus.CONNECTED, state.provider("porkbun").status)
        assertSame(PORKBUN, state.provider("porkbun").dashboard)
        assertFalse(state.requiresSecureWindow)
        assertEquals(setOf("porkbun"), state.connectedProviderIds)
    }

    @Test
    fun connectFailureKeepsTheFormWithTheRegistrarMessage() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi()).apply {
            connectResult = Result.failure(RegistrarUiException("Request failed (HTTP 401): Unauthenticated"))
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect(request("gandi"))
        advanceUntilIdle()

        val gandi = viewModel.uiState.value.provider("gandi")
        assertEquals(RegistrarConnectionStatus.DISCONNECTED, gandi.status)
        assertEquals("Request failed (HTTP 401): Unauthenticated", gandi.error)
        assertNull(gandi.operation)
    }

    @Test
    fun connectIsIgnoredForUnknownOrAlreadyConnectedRegistrars() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi("nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM)))
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect(request("vercel"))
        viewModel.connect(request("nameDotCom"))
        advanceUntilIdle()

        assertEquals(0, gateway.connectCalls)
    }

    @Test
    fun cancellingAConnectionReconcilesToTheSavedState() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi()).apply { connectRelease = CompletableDeferred() }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect(request("dynadot"))
        runCurrent()
        gateway.connectStarted.await()
        viewModel.cancelOperation("dynadot")
        runCurrent()
        advanceUntilIdle()

        assertTrue(gateway.connectCancelled.await())
        val dynadot = viewModel.uiState.value.provider("dynadot")
        assertEquals(RegistrarConnectionStatus.DISCONNECTED, dynadot.status)
        assertEquals("Request cancelled.", dynadot.notice)
        assertNull(dynadot.operation)
    }

    @Test
    fun cancellationAfterCommitReconcilesToConnected() = runTest(dispatcher) {
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val gateway = object : RegistrarUiGateway {
            override suspend fun restore(): Result<RegistrarRestoreUi> = Result.success(
                if (committed) restoreUi("spaceship" to RegistrarProviderRestoreUi.Available(SPACESHIP)) else restoreUi(),
            )

            override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> =
                withContext(NonCancellable) {
                    committed = true
                    accepted.complete(Unit)
                    release.await()
                    Result.success(SPACESHIP)
                }

            override suspend fun refresh(providerId: String) = Result.success(SPACESHIP)
            override suspend fun disconnect(providerId: String) = Result.success(Unit)
            override suspend fun detectPublicIpv4() = Result.success("8.8.8.8")
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect(request("spaceship"))
        runCurrent()
        accepted.await()
        viewModel.cancelOperation("spaceship")
        assertEquals(RegistrarOperation.RESTORING, viewModel.uiState.value.provider("spaceship").operation)
        release.complete(Unit)
        advanceUntilIdle()

        val spaceship = viewModel.uiState.value.provider("spaceship")
        assertEquals(RegistrarConnectionStatus.CONNECTED, spaceship.status)
        assertEquals("The connection completed before cancellation and remains saved.", spaceship.notice)
    }

    @Test
    fun refreshFailureKeepsTheCachedPortfolioWithANotice() = runTest(dispatcher) {
        val cached = NAME_DOT_COM.copy(cacheState = RegistrarCacheState.CACHED_STALE)
        val gateway = FakeGateway(restoreUi("nameDotCom" to RegistrarProviderRestoreUi.Available(cached))).apply {
            refreshResult = { Result.failure(RegistrarUiException("Name.com could not be reached. Check your connection and try again.")) }
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.refresh("nameDotCom")
        assertEquals(RegistrarOperation.REFRESHING, viewModel.uiState.value.provider("nameDotCom").operation)
        advanceUntilIdle()

        val state = viewModel.uiState.value.provider("nameDotCom")
        assertSame(cached, state.dashboard)
        assertEquals(RegistrarConnectionStatus.CONNECTED, state.status)
        assertEquals("Name.com could not be reached. Check your connection and try again.", state.error)
        assertEquals("Showing the last saved domain portfolio.", state.notice)
        assertEquals(RegistrarOperation.REFRESHING, state.failedOperation)
    }

    @Test
    fun failedDisconnectKeepsTheConnectionAndExplainsTheFailedChange() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi("goDaddy" to RegistrarProviderRestoreUi.Available(PORKBUN))).apply {
            disconnectResult = Result.failure(RegistrarUiException("Secure storage is unavailable."))
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation("goDaddy")
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        val goDaddy = viewModel.uiState.value.provider("goDaddy")
        assertEquals(RegistrarConnectionStatus.CONNECTED, goDaddy.status)
        assertEquals("Secure storage is unavailable.", goDaddy.error)
        assertEquals(RegistrarOperation.DISCONNECTING, goDaddy.failedOperation)
        viewModel.clearFeedback("goDaddy")
        assertNull(viewModel.uiState.value.provider("goDaddy").failedOperation)
    }

    @Test
    fun refreshIfStaleFollowsTheFifteenMinuteFreshnessRule() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi(
                "nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM.copy(cacheState = RegistrarCacheState.CACHED_FRESH)),
                "porkbun" to RegistrarProviderRestoreUi.Available(PORKBUN),
            ),
        )
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.refreshIfStale("porkbun")
        viewModel.refreshIfStale("nameDotCom")
        viewModel.refreshIfStale("goDaddy")
        advanceUntilIdle()
        assertEquals(listOf("nameDotCom"), gateway.refreshCalls)

        now += 15 * 60 * 1_000L
        viewModel.refreshIfStale("porkbun")
        advanceUntilIdle()
        assertEquals(listOf("nameDotCom", "porkbun"), gateway.refreshCalls)
    }

    @Test
    fun foregroundRefreshWaitsForRestoreAndRunsOncePerStaleRegistrar() = runTest(dispatcher) {
        val restoreRelease = CompletableDeferred<Unit>()
        val gateway = FakeGateway(restoreUi(
                "nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM.copy(cacheState = RegistrarCacheState.CACHED_STALE)),
                "namecheap" to RegistrarProviderRestoreUi.Available(NAMECHEAP.copy(cacheState = RegistrarCacheState.CACHED_FRESH)),
            ),
        ).apply { this.restoreRelease = restoreRelease }
        val viewModel = viewModel(gateway)
        runCurrent()

        viewModel.onForeground()
        assertTrue(gateway.refreshCalls.isEmpty())
        restoreRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("nameDotCom", "namecheap"), gateway.refreshCalls.toSet())
        assertEquals(2, gateway.refreshCalls.size)

        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(2, gateway.refreshCalls.size)
    }

    @Test
    fun disconnectNeedsConfirmationAndOnlyResetsThatRegistrar() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi(
                "nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM),
                "namecheap" to RegistrarProviderRestoreUi.Available(NAMECHEAP),
            ),
        )
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.openDomain("namecheap", "commerce.example")

        viewModel.requestDisconnectConfirmation("namecheap")
        assertEquals("namecheap", viewModel.uiState.value.disconnectConfirmationProviderId)
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.disconnectConfirmationProviderId)
        viewModel.requestDisconnectConfirmation("namecheap")
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        assertEquals(listOf("namecheap"), gateway.disconnectCalls)
        assertEquals(RegistrarConnectionStatus.DISCONNECTED, viewModel.uiState.value.provider("namecheap").status)
        assertEquals(RegistrarConnectionStatus.CONNECTED, viewModel.uiState.value.provider("nameDotCom").status)
        assertNull(viewModel.uiState.value.selectedDomainId)
        assertEquals(setOf("nameDotCom"), viewModel.uiState.value.connectedProviderIds)
    }

    @Test
    fun domainSelectionSurvivesRecreationWhileTheDomainStillExists() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = FakeGateway(restoreUi("namecheap" to RegistrarProviderRestoreUi.Available(NAMECHEAP)))
        val first = viewModel(gateway, handle)
        advanceUntilIdle()

        first.openDomain("namecheap", "missing.example")
        assertNull(first.uiState.value.selectedDomainId)
        first.openDomain("namecheap", "launch-kit.example")
        assertEquals("launch-kit.example", first.uiState.value.selectedDomain("namecheap")?.name)
        assertNull(first.uiState.value.selectedDomain("nameDotCom"))

        val recreated = viewModel(gateway, handle)
        assertEquals("launch-kit.example", recreated.uiState.value.selectedDomainId)
        advanceUntilIdle()
        assertEquals("launch-kit.example", recreated.uiState.value.selectedDomain("namecheap")?.name)

        gateway.restoreResult = restoreUi("namecheap" to RegistrarProviderRestoreUi.Available(NAMECHEAP.copy(domains = emptyList())))
        val afterRemoval = viewModel(gateway, handle)
        advanceUntilIdle()
        assertNull(afterRemoval.uiState.value.selectedDomainId)
        assertNull(handle.get<String>(RegistrarViewModel.SELECTED_DOMAIN_ID))
    }

    @Test
    fun backClosesTheDetailBeforeLeavingAndForeignSelectionsCloseOnEntry() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi(
                "nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM),
                "namecheap" to RegistrarProviderRestoreUi.Available(NAMECHEAP),
            ),
        )
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.openDomain("nameDotCom", "studio.example")
        assertTrue(viewModel.handleBack())
        assertNull(viewModel.uiState.value.selectedDomainId)
        assertFalse(viewModel.handleBack())

        viewModel.openDomain("nameDotCom", "studio.example")
        viewModel.setVisibleProvider("namecheap")
        assertNull(viewModel.uiState.value.selectedDomainId)
    }

    @Test
    fun refreshThatDropsTheOpenDomainClosesTheDetail() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi("nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM))).apply {
            refreshResult = { Result.success(NAME_DOT_COM.copy(domains = NAME_DOT_COM.domains.drop(1))) }
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.openDomain("nameDotCom", NAME_DOT_COM.domains.first().id)

        viewModel.refresh("nameDotCom")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedDomainId)
    }

    @Test
    fun publicIpv4DetectionUsesProviderSpecificCopyAndCanBeReused() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi()).apply { ipResult = Result.success("8.8.4.4") }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.detectPublicIpv4("porkbun")
        assertEquals(RegistrarPublicIpv4Ui(), viewModel.uiState.value.publicIpv4)

        viewModel.detectPublicIpv4("namecheap")
        assertTrue(viewModel.uiState.value.publicIpv4.isDetecting)
        advanceUntilIdle()
        assertEquals(RegistrarPublicIpv4Ui("namecheap", address = "8.8.4.4"), viewModel.uiState.value.publicIpv4)

        viewModel.detectPublicIpv4("namecheap", force = false)
        advanceUntilIdle()
        assertEquals(1, gateway.ipCalls)

        gateway.ipResult = Result.failure(RegistrarUiException("offline"))
        viewModel.detectPublicIpv4("nameDotCom")
        advanceUntilIdle()
        assertEquals(
            "Couldn’t detect this network. Retry here or manage Name.com’s optional allowlist in its API settings.",
            viewModel.uiState.value.publicIpv4.error,
        )
        viewModel.detectPublicIpv4("namecheap")
        advanceUntilIdle()
        assertEquals(
            "Couldn’t detect this network. You can still enter the address manually.",
            viewModel.uiState.value.publicIpv4.error,
        )
        viewModel.resetPublicIpv4()
        assertEquals(RegistrarPublicIpv4Ui(), viewModel.uiState.value.publicIpv4)
    }

    @Test
    fun differentRegistrarsOperateIndependently() = runTest(dispatcher) {
        val gateway = FakeGateway(restoreUi("nameDotCom" to RegistrarProviderRestoreUi.Available(NAME_DOT_COM))).apply {
            connectRelease = CompletableDeferred()
            connectResult = Result.success(PORKBUN)
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.connect(request("porkbun"))
        runCurrent()
        viewModel.refresh("nameDotCom")
        advanceUntilIdle()

        assertEquals(listOf("nameDotCom"), gateway.refreshCalls)
        assertNull(viewModel.uiState.value.provider("nameDotCom").operation)
        assertEquals(RegistrarOperation.CONNECTING, viewModel.uiState.value.provider("porkbun").operation)
        gateway.connectRelease?.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf("nameDotCom", "porkbun"), viewModel.uiState.value.connectedProviderIds)
    }

    @Test
    fun sampleGatewayRestoreProducesTheFictionalPortfolios() = runTest(dispatcher) {
        val viewModel = viewModel(SampleRegistrarUiGateway)
        advanceUntilIdle()
        viewModel.restore()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("nameDotCom", "namecheap"), state.connectedProviderIds.toList())
        assertEquals(4, state.provider("nameDotCom").dashboard?.domains?.size)
        assertEquals(5, state.provider("namecheap").dashboard?.domains?.size)
        viewModel.connect(request("porkbun"))
        advanceUntilIdle()
        assertEquals("Exit sample data to connect Porkbun.", viewModel.uiState.value.provider("porkbun").error)
    }

    private fun viewModel(gateway: RegistrarUiGateway, handle: SavedStateHandle = SavedStateHandle()) =
        RegistrarViewModel(gateway, handle) { now }

    private fun request(providerId: String, key: String = "key-$providerId") = RegistrarConnectRequest(
        providerId = providerId,
        apiKey = SecretValue.of(key),
        apiSecret = SecretValue.of("secret-$providerId"),
        username = "alice",
        clientIp = "8.8.4.4",
    )

    private class FakeGateway(var restoreResult: RegistrarRestoreUi) : RegistrarUiGateway {
        var restoreFailure: Exception? = null
        var restoreRelease: CompletableDeferred<Unit>? = null
        var connectRelease: CompletableDeferred<Unit>? = null
        var connectResult: Result<RegistrarDashboardUi> = Result.success(PORKBUN)
        var refreshResult: (String) -> Result<RegistrarDashboardUi> = { id ->
            Result.success(DASHBOARDS.getValue(id).copy(cacheState = RegistrarCacheState.LIVE, fetchedAtMillis = NOW))
        }
        var ipResult: Result<String> = Result.success("8.8.8.8")
        var disconnectResult: Result<Unit> = Result.success(Unit)
        val connectStarted = CompletableDeferred<Unit>()
        val connectCancelled = CompletableDeferred<Boolean>()
        var connectCalls = 0
        var ipCalls = 0
        var lastRequest: RegistrarConnectRequest? = null
        val refreshCalls = mutableListOf<String>()
        val disconnectCalls = mutableListOf<String>()

        override suspend fun restore(): Result<RegistrarRestoreUi> {
            restoreRelease?.await()
            return restoreFailure?.let { Result.failure(it) } ?: Result.success(restoreResult)
        }

        override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> {
            connectCalls += 1
            lastRequest = request
            connectStarted.complete(Unit)
            try {
                connectRelease?.await()
            } catch (error: CancellationException) {
                connectCancelled.complete(true)
                throw error
            }
            return connectResult
        }

        override suspend fun refresh(providerId: String): Result<RegistrarDashboardUi> {
            refreshCalls += providerId
            return refreshResult(providerId)
        }

        override suspend fun disconnect(providerId: String): Result<Unit> {
            disconnectCalls += providerId
            return disconnectResult
        }

        override suspend fun detectPublicIpv4(): Result<String> {
            ipCalls += 1
            return ipResult
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val DAY = 86_400_000L

        fun dashboard(providerId: String, name: String, vararg domains: Pair<String, Int?>) = RegistrarDashboardUi(
            account = RegistrarAccountUi(providerId, name),
            domains = domains.map { (domain, days) ->
                RegistrarDomainUi(
                    id = domain,
                    name = domain,
                    status = "Active",
                    createdAtMillis = NOW - 400 * DAY,
                    expiresAtMillis = days?.let { NOW + it * DAY + DAY / 2 },
                    autoRenew = true,
                    locked = true,
                    privacyEnabled = true,
                    nameservers = listOf("ns1.example.net"),
                )
            },
            inventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = NOW,
            cacheState = RegistrarCacheState.LIVE,
        )

        val NAME_DOT_COM = dashboard("nameDotCom", "alice", "studio.example" to 284, "studio-design.example" to 18)
        val NAMECHEAP = dashboard("namecheap", "bob", "commerce.example" to 347, "launch-kit.example" to 12)
        val PORKBUN = dashboard("porkbun", "Porkbun · ab12", "pork.example" to 90)
        val SPACESHIP = dashboard("spaceship", "Spaceship · cd34", "ship.example" to null)
        val DASHBOARDS = listOf(NAME_DOT_COM, NAMECHEAP, PORKBUN, SPACESHIP).associateBy { it.account.providerId }

        fun restoreUi(vararg providers: Pair<String, RegistrarProviderRestoreUi>) = RegistrarRestoreUi(providers.toMap())
    }
}
