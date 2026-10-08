package com.apoorvdarshan.verceltics.ui.netlify

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
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

/** Multi-account behaviour of [NetlifyViewModel] (iOS `ProviderAccountMenu`). */
@OptIn(ExperimentalCoroutinesApi::class)
class NetlifyViewModelAccountsTest {
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
    fun restoreLoadsEveryAccountForTheMenuWithTheActiveOneMarked() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second")
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(NetlifyConnectionStatus.CONNECTED, state.status)
        assertEquals(listOf(PRIMARY, SECOND), state.accounts.map(ProviderAccountUi::id))
        assertEquals(listOf(true, false), state.accounts.map(ProviderAccountUi::isActive))
        assertEquals(PRIMARY, state.activeAccountId)
        assertEquals("Primary", state.dashboard?.account?.displayName)
        assertEquals(1, gateway.accountsCalls)
    }

    @Test
    fun addingAnAccountShowsTheTokenFormOverTheDashboardAndCancelReturns() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = AccountsGateway(PRIMARY to "Primary")
        val viewModel = NetlifyViewModel(gateway, handle)
        advanceUntilIdle()
        viewModel.setRouteVisible(true)
        assertFalse(viewModel.uiState.value.showsConnectionForm)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        viewModel.startAddingAccount()

        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertTrue(viewModel.uiState.value.showsConnectionForm)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        assertEquals(NetlifyConnectionStatus.CONNECTED, viewModel.uiState.value.status)
        assertEquals(true, handle.get<Boolean>(NetlifyViewModel.ADDING_ACCOUNT))

        viewModel.setRouteVisible(false)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        viewModel.setRouteVisible(true)

        viewModel.cancelAddingAccount()

        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertFalse(viewModel.uiState.value.showsConnectionForm)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        assertNull(handle.get<Boolean>(NetlifyViewModel.ADDING_ACCOUNT))
        assertEquals(0, gateway.disconnectCalls)
    }

    @Test
    fun backDismissesTheAddAccountFormBeforeLeavingTheRoute() = runTest(dispatcher) {
        val viewModel = NetlifyViewModel(AccountsGateway(PRIMARY to "Primary"), SavedStateHandle())
        advanceUntilIdle()

        viewModel.startAddingAccount()
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertFalse(viewModel.handleBack())
    }

    @Test
    fun connectingWhileAddingSavesANewActiveAccountAndReloadsTheMenu() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = AccountsGateway(PRIMARY to "Primary")
        val viewModel = NetlifyViewModel(gateway, handle)
        advanceUntilIdle()
        val accountLoads = gateway.accountsCalls

        viewModel.startAddingAccount()
        viewModel.connect(SecretValue.of("second-token"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, gateway.connectCalls)
        assertFalse(state.isAddingAccount)
        assertFalse(state.showsConnectionForm)
        assertEquals(ADDED, state.dashboard?.account?.savedAccountId)
        assertEquals(listOf(PRIMARY, ADDED), state.accounts.map(ProviderAccountUi::id))
        assertEquals(ADDED, state.activeAccountId)
        assertTrue(gateway.accountsCalls > accountLoads)
        assertNull(handle.get<Boolean>(NetlifyViewModel.ADDING_ACCOUNT))
        assertEquals(0, gateway.disconnectCalls)
    }

    @Test
    fun switchingAccountsRestoresTheOtherAccountAndClosesTheOpenSite() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second")
        val viewModel = NetlifyViewModel(gateway, handle)
        advanceUntilIdle()
        viewModel.openSite(siteId(PRIMARY))
        advanceUntilIdle()
        assertEquals(siteId(PRIMARY), viewModel.uiState.value.selectedSiteId)

        viewModel.switchAccount(SECOND)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(SECOND), gateway.switchedIds)
        assertEquals(NetlifyConnectionStatus.CONNECTED, state.status)
        assertEquals("Second", state.dashboard?.account?.displayName)
        assertEquals(SECOND, state.activeAccountId)
        assertEquals(listOf(false, true), state.accounts.map(ProviderAccountUi::isActive))
        assertNull(state.selectedSiteId)
        assertNull(handle.get<String>(NetlifyViewModel.SELECTED_SITE_ID))
        assertEquals(0, gateway.disconnectCalls)
    }

    @Test
    fun switchingToTheActiveOrAnUnknownAccountIsIgnored() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second")
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.switchAccount(PRIMARY)
        viewModel.switchAccount("not-saved")
        advanceUntilIdle()

        assertTrue(gateway.switchedIds.isEmpty())
        assertEquals("Primary", viewModel.uiState.value.dashboard?.account?.displayName)
    }

    @Test
    fun removingTheCurrentAccountRemovesOnlyTheActiveOneAndOpensTheNext() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second")
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        assertTrue(viewModel.uiState.value.showDisconnectConfirmation)
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PRIMARY), gateway.removedIds)
        assertEquals(0, gateway.disconnectCalls)
        assertEquals(NetlifyConnectionStatus.CONNECTED, state.status)
        assertEquals("Second", state.dashboard?.account?.displayName)
        assertEquals(listOf(SECOND), state.accounts.map(ProviderAccountUi::id))
        assertTrue(state.accounts.single().isActive)
        assertFalse(state.showDisconnectConfirmation)
    }

    @Test
    fun removingTheLastAccountLandsOnTheTokenForm() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary")
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        assertEquals(listOf(PRIMARY), gateway.removedIds)
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.accounts.isEmpty())
        assertTrue(viewModel.uiState.value.showsConnectionForm)
    }

    @Test
    fun removeAllAsksForConfirmationThenErasesEveryAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second")
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestRemoveAllConfirmation()
        assertTrue(viewModel.uiState.value.showRemoveAllConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.showRemoveAllConfirmation)
        assertEquals(0, gateway.disconnectCalls)

        viewModel.requestRemoveAllConfirmation()
        viewModel.confirmRemoveAll()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, gateway.disconnectCalls)
        assertTrue(gateway.removedIds.isEmpty())
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, state.status)
        assertTrue(state.accounts.isEmpty())
        assertNull(state.dashboard)
        assertFalse(state.showRemoveAllConfirmation)
    }

    @Test
    fun addingAccountSavedStateIsRestoredOnlyOverASavedAccount() = runTest(dispatcher) {
        val connectedHandle = SavedStateHandle(mapOf(NetlifyViewModel.ADDING_ACCOUNT to true))
        val connected = NetlifyViewModel(AccountsGateway(PRIMARY to "Primary"), connectedHandle)
        advanceUntilIdle()
        assertTrue(connected.uiState.value.isAddingAccount)
        assertTrue(connected.uiState.value.showsConnectionForm)
        assertEquals(true, connectedHandle.get<Boolean>(NetlifyViewModel.ADDING_ACCOUNT))

        val emptyHandle = SavedStateHandle(mapOf(NetlifyViewModel.ADDING_ACCOUNT to true))
        val disconnected = NetlifyViewModel(AccountsGateway(), emptyHandle)
        advanceUntilIdle()
        assertEquals(NetlifyConnectionStatus.DISCONNECTED, disconnected.uiState.value.status)
        assertFalse(disconnected.uiState.value.isAddingAccount)
        assertNull(emptyHandle.get<Boolean>(NetlifyViewModel.ADDING_ACCOUNT))
    }

    @Test
    fun launchProfileRefreshRunsOnceInTheForegroundAndRenamesTheActiveAccount() = runTest(dispatcher) {
        val gateway = AccountsGateway(PRIMARY to "Primary", SECOND to "Second").apply {
            renamedOnProfileRefresh[PRIMARY] = "Primary renamed"
        }
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, gateway.profileRefreshCalls)

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, gateway.profileRefreshCalls)
        assertEquals("Primary renamed", viewModel.uiState.value.dashboard?.account?.displayName)
        assertEquals(
            listOf("Primary renamed", "Second"),
            viewModel.uiState.value.accounts.map(ProviderAccountUi::displayName),
        )

        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.profileRefreshCalls)
    }

    @Test
    fun accountActionsAreIgnoredWhileDisconnected() = runTest(dispatcher) {
        val gateway = AccountsGateway()
        val viewModel = NetlifyViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.startAddingAccount()
        viewModel.requestRemoveAllConfirmation()
        viewModel.switchAccount(PRIMARY)
        viewModel.onForeground()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertFalse(viewModel.uiState.value.showRemoveAllConfirmation)
        assertTrue(gateway.switchedIds.isEmpty())
        assertEquals(0, gateway.profileRefreshCalls)
    }

    /** A tiny in-memory account store behind the UI gateway. */
    private class AccountsGateway(vararg accounts: Pair<String, String>) : NetlifyUiGateway {
        private val saved = linkedMapOf<String, String>().apply { accounts.forEach { (id, name) -> put(id, name) } }
        private var activeId: String? = accounts.firstOrNull()?.first
        val renamedOnProfileRefresh = mutableMapOf<String, String>()
        var accountsCalls = 0
        var connectCalls = 0
        var disconnectCalls = 0
        var profileRefreshCalls = 0
        val switchedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()

        override suspend fun restore(): Result<NetlifyRestoreUi> = Result.success(current())

        override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> {
            connectCalls += 1
            saved[ADDED] = "Added"
            activeId = ADDED
            return Result.success(dashboard(ADDED, "Added", NetlifyCacheState.LIVE))
        }

        override suspend fun refresh(): Result<NetlifyDashboardUi> {
            val id = activeId ?: return Result.failure(NetlifyUiException("Connect a Netlify account first."))
            return Result.success(dashboard(id, saved.getValue(id), NetlifyCacheState.LIVE))
        }

        override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> =
            Result.success(
                NetlifySiteWorkspaceUi(
                    siteId = siteId,
                    details = NetlifyResourceUi.Unavailable("Not needed."),
                    deployments = NetlifyCollectionUi(emptyList(), 0, true, null),
                    builds = NetlifyCollectionUi(emptyList(), 0, true, null),
                ),
            )

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalls += 1
            saved.clear()
            activeId = null
            return Result.success(Unit)
        }

        override suspend fun accounts(): Result<List<ProviderAccountUi>> {
            accountsCalls += 1
            return Result.success(menu())
        }

        override suspend fun switchAccount(accountId: String): Result<NetlifyRestoreUi> {
            switchedIds += accountId
            if (accountId !in saved) return Result.failure(NetlifyUiException("That Netlify account is no longer saved."))
            activeId = accountId
            return Result.success(current())
        }

        override suspend fun removeAccount(accountId: String): Result<NetlifyRestoreUi> {
            removedIds += accountId
            saved.remove(accountId)
            if (activeId == accountId) activeId = saved.keys.firstOrNull()
            return Result.success(current())
        }

        override suspend fun refreshAccountProfiles(): Result<List<ProviderAccountUi>> {
            profileRefreshCalls += 1
            renamedOnProfileRefresh.forEach { (id, name) -> if (id in saved) saved[id] = name }
            return Result.success(menu())
        }

        private fun current(): NetlifyRestoreUi = activeId?.let { id ->
            NetlifyRestoreUi.Available(dashboard(id, saved.getValue(id), NetlifyCacheState.CACHED_FRESH))
        } ?: NetlifyRestoreUi.NotConnected

        private fun menu(): List<ProviderAccountUi> = saved.map { (id, name) ->
            ProviderAccountUi(id = id, displayName = name, detail = "$id@example.com", isActive = id == activeId)
        }
    }

    private companion object {
        const val PRIMARY = "primary"
        const val SECOND = "second-account"
        const val ADDED = "added-account"

        fun siteId(savedAccountId: String) = "site-$savedAccountId"

        fun dashboard(savedAccountId: String, name: String, cacheState: NetlifyCacheState) = NetlifyDashboardUi(
            account = NetlifyAccountUi(
                id = "user-$savedAccountId",
                displayName = name,
                email = "$savedAccountId@example.com",
                savedAccountId = savedAccountId,
            ),
            sites = listOf(
                NetlifySiteUi(
                    id = siteId(savedAccountId),
                    name = "$name site",
                    subtitle = null,
                    url = null,
                    status = "current",
                    updatedAtMillis = 1L,
                ),
            ),
            loadedSiteCount = 1,
            providerInventoryComplete = true,
            warnings = emptyList(),
            fetchedAtMillis = System.currentTimeMillis(),
            cacheState = cacheState,
        )
    }
}
