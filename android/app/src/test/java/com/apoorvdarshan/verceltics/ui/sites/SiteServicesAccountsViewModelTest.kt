package com.apoorvdarshan.verceltics.ui.sites

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Multi-account behaviour of [SiteServicesViewModel]: add, switch, Remove Current, Remove All. */
@OptIn(ExperimentalCoroutinesApi::class)
class SiteServicesAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = 50_000_000L

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun restoreExposesEverySavedAccountAndTheActiveOne() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("plausible", "a.example")
            add("plausible", "b.example", activate = false)
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("plausible")
        assertEquals(listOf("a.example", "b.example"), service.accounts.accounts.map { it.title })
        assertEquals("plausible-1", service.accounts.activeAccountId)
        assertEquals("plausible-1", service.dashboard?.accountId)
        assertTrue(viewModel.uiState.value.service("umami").accounts.accounts.isEmpty())
    }

    @Test
    fun addingAnAccountKeepsTheCurrentOneConnectedAndProtectsTheCredentialForm() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply { add("plausible", "a.example") }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("plausible")
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        viewModel.startAddingAccount("plausible")
        val adding = viewModel.uiState.value.service("plausible")
        assertTrue(adding.isAddingAccount)
        assertTrue(adding.showsConnectForm)
        assertEquals(SiteServiceConnectionStatus.CONNECTED, adding.status)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)

        // A failure keeps the form open with the error; the saved account is untouched.
        gateway.connectFailure = SiteServicesUiException("Request failed (HTTP 401).")
        viewModel.connect("plausible", SiteServiceConnectionInputUi(SecretValue.of("bad-key"), mapOf("siteID" to "b.example")))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.service("plausible").isAddingAccount)
        assertEquals("Request failed (HTTP 401).", viewModel.uiState.value.service("plausible").error)
        assertEquals(1, gateway.accountsOf("plausible").size)

        gateway.connectFailure = null
        viewModel.connect("plausible", SiteServiceConnectionInputUi(SecretValue.of("good-key"), mapOf("siteID" to "b.example")))
        advanceUntilIdle()
        val connected = viewModel.uiState.value.service("plausible")
        assertFalse(connected.isAddingAccount)
        assertEquals(listOf("a.example", "b.example"), connected.accounts.accounts.map { it.title })
        assertEquals(connected.dashboard?.accountId, connected.accounts.activeAccountId)
        assertEquals("b.example", connected.dashboard?.accountName)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        assertFalse(viewModel.uiState.value.toString().contains("good-key"))
    }

    @Test
    fun cancellingAddingReturnsToTheSavedAccountAndBackUnwindsIt() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply { add("uptimeRobot", "Main") }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("uptimeRobot")

        viewModel.startAddingAccount("uptimeRobot")
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.service("uptimeRobot").isAddingAccount)

        viewModel.startAddingAccount("uptimeRobot")
        viewModel.cancelAddingAccount("uptimeRobot")
        val service = viewModel.uiState.value.service("uptimeRobot")
        assertFalse(service.isAddingAccount)
        assertEquals("Main", service.dashboard?.accountName)
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun switchingRestoresTheOtherAccountOfflineClosesItsDetailAndRefreshesItWhenStale() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("plausible", "a.example")
            val second = add("plausible", "b.example", activate = false)
            staleAccounts += second
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("plausible")
        viewModel.openDetail("plausible")
        viewModel.updateResourceSearch("needle")
        advanceUntilIdle()
        viewModel.onForeground()
        advanceUntilIdle()
        assertTrue(gateway.refreshCalls.isEmpty())

        viewModel.switchAccount("plausible", "plausible-2")
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("plausible")
        assertEquals("plausible-2", service.accounts.activeAccountId)
        assertEquals("b.example", service.dashboard?.accountName)
        assertNull(viewModel.uiState.value.detail)
        assertEquals("", viewModel.uiState.value.resourceSearch)
        // The stale cached account refreshes right away, against the newly active account.
        assertEquals(listOf("plausible" to "plausible-2"), gateway.refreshCalls)
        assertEquals(SiteServiceCacheState.LIVE, service.dashboard?.cacheState)
    }

    @Test
    fun switchingAbandonsAnInFlightRefreshOfThePreviousAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("betterStack", "First")
            add("betterStack", "Second", activate = false)
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        gateway.refreshGate = CompletableDeferred()
        viewModel.refresh("betterStack")
        runCurrent()
        assertEquals(SiteServiceOperation.REFRESHING, viewModel.uiState.value.service("betterStack").operation)

        viewModel.switchAccount("betterStack", "betterStack-2")
        advanceUntilIdle()
        gateway.refreshGate!!.complete(Unit)
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("betterStack")
        assertEquals("betterStack-2", service.accounts.activeAccountId)
        assertEquals("Second", service.dashboard?.accountName)
        assertNull(service.operation)
    }

    @Test
    fun removeCurrentNeedsConfirmationAndActivatesTheNextAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("clarity", "Studio")
            add("clarity", "Docs", activate = false)
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("clarity")

        viewModel.requestDisconnectConfirmation("clarity")
        assertTrue(viewModel.uiState.value.service("clarity").showDisconnectConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.service("clarity").showDisconnectConfirmation)
        assertEquals(2, gateway.accountsOf("clarity").size)

        viewModel.requestDisconnectConfirmation("clarity")
        viewModel.confirmDisconnect("clarity")
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("clarity")
        assertEquals(SiteServiceConnectionStatus.CONNECTED, service.status)
        assertEquals("Docs", service.dashboard?.accountName)
        assertEquals(listOf("Docs"), service.accounts.accounts.map { it.title })
        assertEquals(listOf("clarity-1"), gateway.removed)
    }

    @Test
    fun removeAllNeedsConfirmationAndDisconnectsTheService() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("umami", "Cloud")
            add("umami", "Self-hosted", activate = false)
        }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("umami")

        viewModel.requestRemoveAllConfirmation("umami")
        assertTrue(viewModel.uiState.value.service("umami").showRemoveAllConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.service("umami").showRemoveAllConfirmation)

        viewModel.requestRemoveAllConfirmation("umami")
        viewModel.confirmRemoveAll("umami")
        advanceUntilIdle()

        val service = viewModel.uiState.value.service("umami")
        assertEquals(SiteServiceConnectionStatus.DISCONNECTED, service.status)
        assertTrue(service.accounts.accounts.isEmpty())
        assertTrue(gateway.accountsOf("umami").isEmpty())
        assertFalse("umami" in viewModel.uiState.value.connectedProviderIds)
    }

    @Test
    fun aSavedDetailForAnotherAccountIsNeverReopened() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("plausible", "a.example")
            add("plausible", "b.example", activate = false)
        }
        val saved = SavedStateHandle(
            mapOf(
                SiteServicesViewModel.DETAIL_PROVIDER to "plausible",
                SiteServicesViewModel.DETAIL_ACCOUNT to "plausible-2",
                SiteServicesViewModel.DETAIL_RESOURCE to "plausible-2-1",
            ),
        )
        val viewModel = viewModel(gateway, saved)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.detail)
        assertNull(saved.get<String>(SiteServicesViewModel.DETAIL_PROVIDER))

        val matching = SavedStateHandle(
            mapOf(
                SiteServicesViewModel.DETAIL_PROVIDER to "plausible",
                SiteServicesViewModel.DETAIL_ACCOUNT to "plausible-1",
            ),
        )
        val restored = viewModel(gateway, matching)
        advanceUntilIdle()
        assertEquals("plausible", restored.uiState.value.detail?.providerId)
    }

    @Test
    fun theAccountMenuIsDisabledWhileAddingAndGoogleAddingNeedsNoSecureWindow() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply { add("googleAnalytics", "Google Analytics · 1 property") }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        viewModel.setActiveRoute("googleAnalytics")

        viewModel.startAddingAccount("googleAnalytics")
        assertTrue(viewModel.uiState.value.service("googleAnalytics").isAddingAccount)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        // Refresh is unavailable while the add form is up.
        viewModel.refresh("googleAnalytics")
        advanceUntilIdle()
        assertTrue(gateway.refreshCalls.isEmpty())

        gateway.googleGate = CompletableDeferred()
        viewModel.connectGoogle("googleAnalytics")
        runCurrent()
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        gateway.googleGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.service("googleAnalytics").accounts.accounts.size)
        assertFalse(viewModel.uiState.value.service("googleAnalytics").isAddingAccount)
    }

    private fun viewModel(gateway: AccountsGateway, saved: SavedStateHandle = SavedStateHandle()) =
        SiteServicesViewModel(gateway, saved) { now }

    /** In-memory multi-account backend; account ids are "<provider>-<n>". */
    private inner class AccountsGateway : SiteServicesUiGateway {
        private val accounts = HashMap<String, MutableList<SiteAccountOptionUi>>()
        private val active = HashMap<String, String>()
        private val counters = HashMap<String, Int>()
        val staleAccounts = HashSet<String>()
        val refreshCalls = ArrayList<Pair<String, String?>>()
        val removed = ArrayList<String>()
        var connectFailure: Throwable? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        var googleGate: CompletableDeferred<Unit>? = null

        override val googleOAuthReadiness: SiteGoogleOAuthReadinessUi = SiteGoogleOAuthReadinessUi.Ready

        fun accountsOf(providerId: String): List<SiteAccountOptionUi> = accounts[providerId].orEmpty()

        fun add(providerId: String, title: String, activate: Boolean = true): String {
            val id = "$providerId-${(counters[providerId] ?: 0) + 1}"
            counters[providerId] = (counters[providerId] ?: 0) + 1
            accounts.getOrPut(providerId) { ArrayList() } += SiteAccountOptionUi(id, title)
            if (activate || active[providerId] == null) active[providerId] = id
            return id
        }

        private fun accountsUi(providerId: String) = SiteAccountsUi(accountsOf(providerId).toList(), active[providerId])

        private fun dashboard(providerId: String, accountId: String, cacheState: SiteServiceCacheState) = SiteServiceDashboardUi(
            providerId = providerId,
            accountId = accountId,
            accountName = accountsOf(providerId).first { it.id == accountId }.title,
            accountDetail = null,
            status = "Connected",
            resources = (1..2).map { index ->
                SiteResourceUi(
                    "$accountId-$index", "Resource $index", null, null, "Up", null,
                    listOf(SiteMetricUi("m", "Visitors", index.toDouble(), SiteMetricUnit.COUNT)),
                )
            },
            metrics = emptyList(),
            warnings = emptyList(),
            fetchedAtMillis = if (accountId in staleAccounts) 1L else now,
            cacheState = cacheState,
        )

        private fun restoreOf(providerId: String): SiteServiceRestoreUi {
            val id = active[providerId] ?: return SiteServiceRestoreUi.NotConnected
            val state = if (id in staleAccounts) SiteServiceCacheState.CACHED_STALE else SiteServiceCacheState.CACHED_FRESH
            return SiteServiceRestoreUi.Available(dashboard(providerId, id, state))
        }

        override suspend fun restore(): Result<SiteServicesRestoreUi> = Result.success(
            SiteServicesRestoreUi(
                services = SiteServiceProviderIds.associateWith(::restoreOf),
                accounts = SiteServiceProviderIds.associateWith(::accountsUi),
            ),
        )

        override suspend fun connect(providerId: String, input: SiteServiceConnectionInputUi): Result<SiteServiceDashboardUi> {
            connectFailure?.let { return Result.failure(it) }
            val id = add(providerId, input.fields["siteID"] ?: "Account")
            return Result.success(dashboard(providerId, id, SiteServiceCacheState.LIVE))
        }

        override suspend fun connectGoogle(providerId: String): Result<SiteServiceDashboardUi> {
            googleGate?.await()
            val id = add(providerId, "Google account ${accountsOf(providerId).size + 1}")
            return Result.success(dashboard(providerId, id, SiteServiceCacheState.LIVE))
        }

        override suspend fun refresh(providerId: String): Result<SiteServiceDashboardUi> {
            val id = active[providerId]
            refreshCalls += providerId to id
            refreshGate?.await()
            staleAccounts -= id.orEmpty()
            return Result.success(dashboard(providerId, checkNotNull(id), SiteServiceCacheState.LIVE))
        }

        override suspend fun loadDetail(
            request: SiteServiceDetailRequestUi,
            forceRefresh: Boolean,
            onPartial: suspend (SiteServiceDetailUi) -> Unit,
        ): Result<SiteServiceDetailUi> = Result.success(
            SiteServiceDetailUi(
                request.providerId, request.resourceId.orEmpty(), "Detail", emptyList(), emptyList(), emptyList(),
                emptyMap(), emptyList(), now,
            ),
        )

        override suspend fun disconnect(providerId: String): Result<Unit> {
            active[providerId]?.let { removeAccount(providerId, it) }
            return Result.success(Unit)
        }

        override suspend fun accounts(providerId: String): Result<SiteAccountsUi> = Result.success(accountsUi(providerId))

        override suspend fun switchAccount(providerId: String, accountId: String): Result<SiteServiceRestoreUi> {
            if (accountsOf(providerId).none { it.id == accountId }) return Result.failure(SiteServicesUiException("Missing."))
            active[providerId] = accountId
            return Result.success(restoreOf(providerId))
        }

        override suspend fun removeAccount(providerId: String, accountId: String): Result<SiteServiceRestoreUi> {
            removed += accountId
            accounts[providerId]?.removeAll { it.id == accountId }
            if (active[providerId] == accountId) {
                accountsOf(providerId).firstOrNull()?.let { active[providerId] = it.id } ?: active.remove(providerId)
            }
            return Result.success(restoreOf(providerId))
        }

        override suspend fun removeAllAccounts(providerId: String): Result<Unit> {
            accounts.remove(providerId)
            active.remove(providerId)
            return Result.success(Unit)
        }
    }
}
