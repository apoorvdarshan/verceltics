package com.apoorvdarshan.verceltics.ui.searchconsole

import androidx.lifecycle.SavedStateHandle
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

/** Multi-account behaviour of [SearchConsoleViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchConsoleAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setMainDispatcher() = Dispatchers.setMain(dispatcher)

    @After
    fun resetMainDispatcher() = Dispatchers.resetMain()

    @Test
    fun restoreExposesTheSavedGoogleAccounts() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("a@example.com")
            add("b@example.com", activate = false)
        }
        val viewModel = SearchConsoleViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a@example.com", "b@example.com"), state.accounts.accounts.map { it.title })
        assertEquals("acct-1", state.accounts.activeAccountId)
        assertEquals("acct-1", state.dashboard?.account?.id)
    }

    @Test
    fun addingAGoogleAccountKeepsTheCurrentOneAndActivatesTheNewOne() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply { add("a@example.com") }
        val viewModel = SearchConsoleViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.setRouteVisible(true)
        viewModel.openProperty("sc-domain:acct-1.example")
        advanceUntilIdle()

        viewModel.startAddingAccount()
        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertTrue(viewModel.uiState.value.showsConnectPanel)
        assertNull(viewModel.uiState.value.selectedPropertyUrl)
        assertEquals(SearchConsoleConnectionStatus.CONNECTED, viewModel.uiState.value.status)

        gateway.connectGate = CompletableDeferred()
        viewModel.connect()
        runCurrent()
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        gateway.connectGate!!.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isAddingAccount)
        assertEquals(listOf("a@example.com", "new-2@example.com"), state.accounts.accounts.map { it.title })
        assertEquals("acct-2", state.accounts.activeAccountId)
        assertEquals("acct-2", state.dashboard?.account?.id)
        assertEquals(listOf("sc-domain:acct-2.example"), gateway.summaryRequests.last())
    }

    @Test
    fun cancellingTheAddReturnsToTheSavedAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply { add("a@example.com") }
        val viewModel = SearchConsoleViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.startAddingAccount()
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertEquals("acct-1", viewModel.uiState.value.dashboard?.account?.id)

        // Cancelling an in-flight sign-in while adding keeps the add panel open.
        viewModel.startAddingAccount()
        gateway.connectGate = CompletableDeferred()
        viewModel.connect()
        runCurrent()
        viewModel.cancelAddingAccount()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertEquals("Google authorization cancelled.", viewModel.uiState.value.notice)
        assertEquals(1, gateway.accounts.size)
    }

    @Test
    fun switchingClosesThePropertyRestoresOfflineAndRefreshesOnForeground() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("a@example.com")
            add("b@example.com", activate = false)
        }
        val viewModel = SearchConsoleViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        viewModel.onForeground()
        advanceUntilIdle()
        val refreshesBefore = gateway.refreshed.size
        viewModel.openProperty("sc-domain:acct-1.example")
        viewModel.updatePropertySearch("needle")
        advanceUntilIdle()

        viewModel.switchAccount("acct-2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.selectedPropertyUrl)
        assertEquals("", state.propertySearch)
        assertEquals("acct-2", state.accounts.activeAccountId)
        assertEquals("acct-2", state.dashboard?.account?.id)
        assertEquals(listOf("acct-2"), gateway.refreshed.drop(refreshesBefore))
    }

    @Test
    fun removeCurrentActivatesTheNextAccountAndRemoveAllDisconnects() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("a@example.com")
            add("b@example.com", activate = false)
            add("c@example.com", activate = false)
        }
        val viewModel = SearchConsoleViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        assertTrue(viewModel.uiState.value.showDisconnectConfirmation)
        viewModel.confirmDisconnect()
        advanceUntilIdle()
        assertEquals("acct-2", viewModel.uiState.value.dashboard?.account?.id)
        assertEquals(listOf("b@example.com", "c@example.com"), viewModel.uiState.value.accounts.accounts.map { it.title })
        assertEquals(listOf("acct-1"), gateway.removed)

        viewModel.requestRemoveAllConfirmation()
        assertTrue(viewModel.uiState.value.showRemoveAllConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.showRemoveAllConfirmation)
        viewModel.requestRemoveAllConfirmation()
        viewModel.confirmRemoveAll()
        advanceUntilIdle()
        assertEquals(SearchConsoleConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.accounts.accounts.isEmpty())
        assertTrue(gateway.accounts.isEmpty())
    }

    @Test
    fun aPropertySavedForAnotherAccountIsNotReopened() = runTest(dispatcher) {
        val gateway = AccountsGateway().apply {
            add("a@example.com")
            add("b@example.com", activate = false)
        }
        val other = SavedStateHandle(
            mapOf(
                SearchConsoleViewModel.SELECTED_PROPERTY_URL to "sc-domain:acct-1.example",
                SearchConsoleViewModel.SELECTED_ACCOUNT_ID to "acct-2",
            ),
        )
        val viewModel = SearchConsoleViewModel(gateway, other)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedPropertyUrl)
        assertNull(other.get<String>(SearchConsoleViewModel.SELECTED_PROPERTY_URL))

        val same = SavedStateHandle(
            mapOf(
                SearchConsoleViewModel.SELECTED_PROPERTY_URL to "sc-domain:acct-1.example",
                SearchConsoleViewModel.SELECTED_ACCOUNT_ID to "acct-1",
            ),
        )
        val restored = SearchConsoleViewModel(gateway, same)
        advanceUntilIdle()
        assertEquals("sc-domain:acct-1.example", restored.uiState.value.selectedPropertyUrl)
    }

    /** In-memory Google accounts; ids are "acct-<n>", properties "sc-domain:acct-<n>.example". */
    private class AccountsGateway : SearchConsoleUiGateway {
        val accounts = ArrayList<SiteAccountOptionUi>()
        private var active: String? = null
        private var counter = 0
        var connectGate: CompletableDeferred<Unit>? = null
        val refreshed = ArrayList<String>()
        val removed = ArrayList<String>()
        val summaryRequests = ArrayList<List<String>>()

        override val oauthReadiness: SearchConsoleOAuthReadinessUi = SearchConsoleOAuthReadinessUi.Ready

        fun add(email: String, activate: Boolean = true): String {
            counter += 1
            val id = "acct-$counter"
            accounts += SiteAccountOptionUi(id, email)
            if (activate || active == null) active = id
            return id
        }

        private fun dashboard(id: String, cacheState: SearchConsoleCacheState): SearchConsoleDashboardUi {
            val property = SearchConsolePropertyUi("sc-domain:$id.example", "$id.example", "Owner")
            return SearchConsoleDashboardUi(
                account = SearchConsoleAccountUi(id, accounts.first { it.id == id }.title),
                properties = listOf(property),
                loadedPropertyCount = 1,
                providerInventoryComplete = true,
                inventoryTruncatedForDisplay = false,
                warnings = emptyList(),
                fetchedAtMillis = 1L,
                cacheState = cacheState,
            )
        }

        private fun restoreOfActive(): SearchConsoleRestoreUi =
            active?.let { SearchConsoleRestoreUi.Available(dashboard(it, SearchConsoleCacheState.CACHED_STALE)) }
                ?: SearchConsoleRestoreUi.NotConnected

        override suspend fun restore(): Result<SearchConsoleRestoreUi> = Result.success(restoreOfActive())

        override suspend fun connect(): Result<SearchConsoleDashboardUi> {
            connectGate?.await()
            val id = add("new-${counter + 1}@example.com")
            return Result.success(dashboard(id, SearchConsoleCacheState.LIVE))
        }

        override suspend fun refresh(): Result<SearchConsoleDashboardUi> {
            val id = checkNotNull(active)
            refreshed += id
            return Result.success(dashboard(id, SearchConsoleCacheState.LIVE))
        }

        override suspend fun loadProperty(
            property: SearchConsolePropertyUi,
            performanceQuery: SearchConsolePerformanceQueryUi,
        ): Result<SearchConsolePropertyWorkspaceUi> = Result.success(
            SearchConsolePropertyWorkspaceUi(
                property,
                SearchConsoleResourceUi.Unavailable("unused"),
                SearchConsoleResourceUi.Available(emptyList()),
            ),
        )

        override suspend fun loadPerformance(
            siteUrl: String,
            query: SearchConsolePerformanceQueryUi,
        ): Result<SearchConsoleResourceUi<SearchConsolePerformanceUi>> =
            Result.success(SearchConsoleResourceUi.Unavailable("unused"))

        override suspend fun loadPropertySummaries(
            siteUrls: List<String>,
            onSummary: suspend (SearchConsolePropertySummaryUi) -> Unit,
        ): Result<Unit> {
            summaryRequests += siteUrls
            return Result.success(Unit)
        }

        override suspend fun inspect(siteUrl: String, inspectionUrl: String): Result<SearchConsoleInspectionUi> =
            Result.failure(SearchConsoleUiException("unused"))

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)

        override suspend fun accounts(): Result<SiteAccountsUi> = Result.success(SiteAccountsUi(accounts.toList(), active))

        override suspend fun switchAccount(accountId: String): Result<SearchConsoleRestoreUi> {
            active = accountId
            return Result.success(restoreOfActive())
        }

        override suspend fun removeAccount(accountId: String): Result<SearchConsoleRestoreUi> {
            removed += accountId
            accounts.removeAll { it.id == accountId }
            if (active == accountId) active = accounts.firstOrNull()?.id
            return Result.success(restoreOfActive())
        }

        override suspend fun removeAllAccounts(): Result<Unit> {
            accounts.clear()
            active = null
            return Result.success(Unit)
        }
    }
}
