package com.apoorvdarshan.verceltics.ui.pagespeed

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountOptionUi
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi
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

/** Multi-site behaviour of [PageSpeedViewModel]: add, switch, Remove Current, Remove All. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageSpeedAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun addingASiteKeepsTheCurrentOneSavedAndActivatesTheNewOne() = runTest(dispatcher) {
        val gateway = SitesGateway().apply { add("https://one.example") }
        val viewModel = PageSpeedViewModel(gateway)
        advanceUntilIdle()
        assertEquals(listOf("one.example"), viewModel.uiState.value.accounts.accounts.map { it.title })

        viewModel.startAddingAccount()
        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertTrue(viewModel.uiState.value.showsConnectForm)
        assertEquals(PageSpeedConnectionStatus.CONNECTED, viewModel.uiState.value.status)
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(gateway.refreshed.isEmpty())

        gateway.failNextConnect = true
        viewModel.connect("https://two.example", SecretValue.of("bad-key"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertEquals("Invalid key.", viewModel.uiState.value.error)

        viewModel.connect("https://two.example", SecretValue.of("good-key"))
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.isAddingAccount)
        assertEquals(listOf("one.example", "two.example"), state.accounts.accounts.map { it.title })
        assertEquals("site-2", state.accounts.activeAccountId)
        assertEquals("https://two.example", state.dashboard?.siteUrl)
        assertFalse(state.toString().contains("good-key"))
    }

    @Test
    fun backAndCancelLeaveTheAddFormWithoutTouchingTheSavedSite() = runTest(dispatcher) {
        val gateway = SitesGateway().apply { add("https://one.example") }
        val viewModel = PageSpeedViewModel(gateway)
        advanceUntilIdle()

        viewModel.startAddingAccount()
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertFalse(viewModel.handleBack())

        viewModel.startAddingAccount()
        gateway.connectGate = CompletableDeferred()
        viewModel.connect("https://two.example", SecretValue.of("key"))
        runCurrent()
        viewModel.cancelAddingAccount()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertEquals("https://one.example", viewModel.uiState.value.dashboard?.siteUrl)
        assertEquals(1, gateway.sites.size)
    }

    @Test
    fun switchingShowsTheOtherSitesSavedAuditAndAbandonsARefresh() = runTest(dispatcher) {
        val gateway = SitesGateway().apply {
            add("https://one.example")
            add("https://two.example", activate = false)
        }
        val viewModel = PageSpeedViewModel(gateway)
        advanceUntilIdle()
        gateway.refreshGate = CompletableDeferred()
        viewModel.refresh()
        runCurrent()
        assertEquals(PageSpeedOperation.REFRESHING, viewModel.uiState.value.operation)

        viewModel.switchAccount("site-2")
        advanceUntilIdle()
        gateway.refreshGate!!.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("site-2", state.accounts.activeAccountId)
        assertEquals("https://two.example", state.dashboard?.siteUrl)
        assertEquals(PageSpeedCacheState.CACHED_FRESH, state.dashboard?.cacheState)
        assertNull(state.operation)
    }

    @Test
    fun removeCurrentActivatesTheNextSiteAndRemoveAllDisconnects() = runTest(dispatcher) {
        val gateway = SitesGateway().apply {
            add("https://one.example")
            add("https://two.example", activate = false)
        }
        val viewModel = PageSpeedViewModel(gateway)
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.showDisconnectConfirmation)
        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()
        assertEquals("https://two.example", viewModel.uiState.value.dashboard?.siteUrl)
        assertEquals(listOf("two.example"), viewModel.uiState.value.accounts.accounts.map { it.title })

        gateway.add("https://three.example", activate = false)
        viewModel.requestRemoveAllConfirmation()
        assertTrue(viewModel.uiState.value.showRemoveAllConfirmation)
        viewModel.confirmRemoveAll()
        advanceUntilIdle()
        assertEquals(PageSpeedConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertTrue(gateway.sites.isEmpty())
    }

    /** In-memory saved sites; ids are "site-<n>". */
    private class SitesGateway : PageSpeedUiGateway {
        val sites = ArrayList<SiteAccountOptionUi>()
        private val urls = HashMap<String, String>()
        private var active: String? = null
        private var counter = 0
        var failNextConnect = false
        var connectGate: CompletableDeferred<Unit>? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        val refreshed = ArrayList<String>()

        fun add(url: String, activate: Boolean = true): String {
            counter += 1
            val id = "site-$counter"
            sites += SiteAccountOptionUi(id, url.removePrefix("https://"))
            urls[id] = url
            if (activate || active == null) active = id
            return id
        }

        private fun dashboard(id: String, cacheState: PageSpeedCacheState) = PageSpeedDashboardUi(
            siteUrl = checkNotNull(urls[id]),
            siteName = urls.getValue(id).removePrefix("https://"),
            status = "Good",
            metrics = emptyList(),
            fetchedAtMillis = 1L,
            sources = PageSpeedSourcesUi(PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE),
            warnings = emptyList(),
            cacheState = cacheState,
            accountId = id,
        )

        private fun restoreOfActive(): PageSpeedRestoreUi =
            active?.let { PageSpeedRestoreUi.Available(dashboard(it, PageSpeedCacheState.CACHED_FRESH)) }
                ?: PageSpeedRestoreUi.NotConnected

        override suspend fun restore(): Result<PageSpeedRestoreUi> = Result.success(restoreOfActive())

        override suspend fun connect(apiKey: SecretValue, siteUrl: String): Result<PageSpeedDashboardUi> {
            connectGate?.await()
            if (failNextConnect) {
                failNextConnect = false
                return Result.failure(PageSpeedUiException("Invalid key."))
            }
            return Result.success(dashboard(add(siteUrl), PageSpeedCacheState.LIVE))
        }

        override suspend fun refresh(): Result<PageSpeedDashboardUi> {
            val id = checkNotNull(active)
            refreshed += id
            refreshGate?.await()
            return Result.success(dashboard(id, PageSpeedCacheState.LIVE))
        }

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

        override suspend fun accounts(): Result<SiteAccountsUi> = Result.success(SiteAccountsUi(sites.toList(), active))

        override suspend fun switchAccount(accountId: String): Result<PageSpeedRestoreUi> {
            active = accountId
            return Result.success(restoreOfActive())
        }

        override suspend fun removeAccount(accountId: String): Result<PageSpeedRestoreUi> {
            sites.removeAll { it.id == accountId }
            if (active == accountId) active = sites.firstOrNull()?.id
            return Result.success(restoreOfActive())
        }

        override suspend fun removeAllAccounts(): Result<Unit> {
            sites.clear()
            active = null
            return Result.success(Unit)
        }
    }
}
