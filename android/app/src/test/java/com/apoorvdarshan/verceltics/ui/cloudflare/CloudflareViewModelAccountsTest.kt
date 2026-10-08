package com.apoorvdarshan.verceltics.ui.cloudflare

import androidx.lifecycle.SavedStateHandle
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
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

/**
 * Multi-login behaviour of [CloudflareViewModel]: several saved Cloudflare logins (iOS
 * `ProviderAccountMenu`), independent from the Cloudflare accounts inside one login.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareViewModelAccountsTest {
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
    fun restoreLoadsEverySavedLoginWithTheActiveOneMarked() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(CloudflareConnectionStatus.CONNECTED, state.status)
        assertEquals(listOf(PRIMARY, SECOND), state.savedLogins.map(ProviderAccountUi::id))
        assertEquals(listOf(true, false), state.savedLogins.map(ProviderAccountUi::isActive))
        assertEquals(PRIMARY, state.activeLoginId)
        assertEquals("Primary login", state.dashboard?.profile?.displayName)
        assertEquals(1, gateway.loginListCalls)
    }

    @Test
    fun addingALoginShowsTheCredentialFormAndCancelReturnsToTheDashboard() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val viewModel = CloudflareViewModel(LoginsGateway(PRIMARY to "Primary login"), handle)
        advanceUntilIdle()
        viewModel.setRouteVisible(true)
        viewModel.openResource(CloudflareResourceKind.ZONE, zoneId(PRIMARY))
        assertFalse(viewModel.uiState.value.requiresSecureWindow)

        viewModel.startAddingAccount()

        assertTrue(viewModel.uiState.value.isAddingAccount)
        assertTrue(viewModel.uiState.value.showsConnectionForm)
        assertTrue(viewModel.uiState.value.requiresSecureWindow)
        assertNull(viewModel.uiState.value.selectedResource)
        assertEquals(true, handle.get<Boolean>(CloudflareViewModel.ADDING_ACCOUNT))

        assertTrue(viewModel.handleBack())

        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertFalse(viewModel.uiState.value.showsConnectionForm)
        assertFalse(viewModel.uiState.value.requiresSecureWindow)
        assertNull(handle.get<Boolean>(CloudflareViewModel.ADDING_ACCOUNT))
    }

    @Test
    fun connectingWhileAddingSavesANewActiveLoginWithoutDisconnecting() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = LoginsGateway(PRIMARY to "Primary login")
        val viewModel = CloudflareViewModel(gateway, handle)
        advanceUntilIdle()

        viewModel.startAddingAccount()
        viewModel.connect(CloudflareCredential.globalApiKey("owner@example.com", "global-key"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, gateway.connectCalls)
        assertEquals(0, gateway.disconnectCalls)
        assertFalse(state.isAddingAccount)
        assertEquals(ADDED, state.dashboard?.profile?.savedAccountId)
        assertEquals(listOf(PRIMARY, ADDED), state.savedLogins.map(ProviderAccountUi::id))
        assertEquals(ADDED, state.activeLoginId)
        assertNull(handle.get<Boolean>(CloudflareViewModel.ADDING_ACCOUNT))
    }

    @Test
    fun switchingLoginsClearsOperationsAndTheSelectedResource() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, handle)
        advanceUntilIdle()
        viewModel.openResource(CloudflareResourceKind.ZONE, zoneId(PRIMARY))
        assertTrue(viewModel.openStorage())
        assertTrue(viewModel.operationsNavigator.stack.value.isNotEmpty())

        viewModel.switchLogin(SECOND)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(SECOND), gateway.switchedIds)
        assertEquals(CloudflareConnectionStatus.CONNECTED, state.status)
        assertEquals("Second login", state.dashboard?.profile?.displayName)
        assertEquals(SECOND, state.activeLoginId)
        assertEquals(listOf(false, true), state.savedLogins.map(ProviderAccountUi::isActive))
        assertNull(state.selectedResource)
        assertTrue(viewModel.operationsNavigator.stack.value.isEmpty())
        assertNull(handle.get<String>(CloudflareViewModel.SELECTED_RESOURCE_ID))
        assertNull(handle.get<String>(CloudflareViewModel.SELECTED_RESOURCE_KIND))
        assertEquals(0, gateway.disconnectCalls)
    }

    @Test
    fun choosingACloudflareAccountInsideALoginDoesNotSwitchLogins() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.selectAccount("account-secondary")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("account-secondary", gateway.lastPreferredAccountId)
        assertEquals("account-secondary", state.dashboard?.selectedAccountId)
        assertTrue(gateway.switchedIds.isEmpty())
        assertEquals(PRIMARY, state.activeLoginId)
        assertEquals("Primary login", state.dashboard?.profile?.displayName)
        assertEquals(listOf(PRIMARY, SECOND), state.savedLogins.map(ProviderAccountUi::id))
    }

    @Test
    fun switchingToTheActiveOrAnUnknownLoginIsIgnored() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.switchLogin(PRIMARY)
        viewModel.switchLogin("not-saved")
        advanceUntilIdle()

        assertTrue(gateway.switchedIds.isEmpty())
    }

    @Test
    fun removingTheCurrentLoginRemovesOnlyTheActiveOneAndOpensTheNext() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PRIMARY), gateway.removedIds)
        assertEquals(0, gateway.disconnectCalls)
        assertEquals(CloudflareConnectionStatus.CONNECTED, state.status)
        assertEquals("Second login", state.dashboard?.profile?.displayName)
        assertEquals(listOf(SECOND), state.savedLogins.map(ProviderAccountUi::id))
        assertTrue(state.savedLogins.single().isActive)
    }

    @Test
    fun removingTheLastLoginLandsOnTheCredentialForm() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestDisconnectConfirmation()
        viewModel.confirmDisconnect()
        advanceUntilIdle()

        assertEquals(listOf(PRIMARY), gateway.removedIds)
        assertEquals(CloudflareConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertTrue(viewModel.uiState.value.savedLogins.isEmpty())
        assertTrue(viewModel.uiState.value.showsConnectionForm)
    }

    @Test
    fun removeAllAsksForConfirmationThenErasesEveryLogin() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login")
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()

        viewModel.requestRemoveAllConfirmation()
        assertTrue(viewModel.uiState.value.showRemoveAllConfirmation)
        assertTrue(viewModel.handleBack())
        assertFalse(viewModel.uiState.value.showRemoveAllConfirmation)

        viewModel.requestRemoveAllConfirmation()
        viewModel.confirmRemoveAll()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, gateway.disconnectCalls)
        assertTrue(gateway.removedIds.isEmpty())
        assertEquals(CloudflareConnectionStatus.DISCONNECTED, state.status)
        assertTrue(state.savedLogins.isEmpty())
        assertNull(state.dashboard)
    }

    @Test
    fun addingLoginSavedStateIsRestoredOnlyOverASavedLogin() = runTest(dispatcher) {
        val connectedHandle = SavedStateHandle(mapOf(CloudflareViewModel.ADDING_ACCOUNT to true))
        val connected = CloudflareViewModel(LoginsGateway(PRIMARY to "Primary login"), connectedHandle)
        advanceUntilIdle()
        assertTrue(connected.uiState.value.isAddingAccount)
        assertTrue(connected.uiState.value.showsConnectionForm)

        val emptyHandle = SavedStateHandle(mapOf(CloudflareViewModel.ADDING_ACCOUNT to true))
        val disconnected = CloudflareViewModel(LoginsGateway(), emptyHandle)
        advanceUntilIdle()
        assertEquals(CloudflareConnectionStatus.DISCONNECTED, disconnected.uiState.value.status)
        assertFalse(disconnected.uiState.value.isAddingAccount)
        assertNull(emptyHandle.get<Boolean>(CloudflareViewModel.ADDING_ACCOUNT))
    }

    @Test
    fun launchProfileRefreshRunsOnceInTheForegroundAndRenamesTheActiveLogin() = runTest(dispatcher) {
        val gateway = LoginsGateway(PRIMARY to "Primary login", SECOND to "Second login").apply {
            renamedOnProfileRefresh[PRIMARY] = "Primary renamed"
        }
        val viewModel = CloudflareViewModel(gateway, SavedStateHandle())
        advanceUntilIdle()
        assertEquals(0, gateway.profileRefreshCalls)

        viewModel.onForeground()
        advanceUntilIdle()

        assertEquals(1, gateway.profileRefreshCalls)
        assertEquals("Primary renamed", viewModel.uiState.value.dashboard?.profile?.displayName)
        assertEquals(
            listOf("Primary renamed", "Second login"),
            viewModel.uiState.value.savedLogins.map(ProviderAccountUi::displayName),
        )

        viewModel.onBackground()
        viewModel.onForeground()
        advanceUntilIdle()
        assertEquals(1, gateway.profileRefreshCalls)
    }

    /** A tiny in-memory saved-login store behind the UI gateway. */
    private class LoginsGateway(vararg logins: Pair<String, String>) : CloudflareUiGateway {
        private val saved = linkedMapOf<String, String>().apply { logins.forEach { (id, name) -> put(id, name) } }
        private var activeId: String? = logins.firstOrNull()?.first
        val renamedOnProfileRefresh = mutableMapOf<String, String>()
        var loginListCalls = 0
        var connectCalls = 0
        var disconnectCalls = 0
        var profileRefreshCalls = 0
        var lastPreferredAccountId: String? = null
        val switchedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()

        override suspend fun restore(): Result<CloudflareRestoreUi> = Result.success(current())

        override suspend fun connect(credential: CloudflareCredential): Result<CloudflareDashboardUi> {
            connectCalls += 1
            saved[ADDED] = "Added login"
            activeId = ADDED
            return Result.success(loginDashboard(ADDED, "Added login", CloudflareCacheState.LIVE))
        }

        override suspend fun refresh(preferredAccountId: String?): Result<CloudflareDashboardUi> {
            lastPreferredAccountId = preferredAccountId
            val id = activeId ?: return Result.failure(CloudflareUiException("Connect a Cloudflare account first."))
            return Result.success(
                loginDashboard(id, saved.getValue(id), CloudflareCacheState.LIVE, preferredAccountId ?: "account-primary"),
            )
        }

        override suspend fun disconnect(): Result<Unit> {
            disconnectCalls += 1
            saved.clear()
            activeId = null
            return Result.success(Unit)
        }

        override suspend fun savedLogins(): Result<List<ProviderAccountUi>> {
            loginListCalls += 1
            return Result.success(menu())
        }

        override suspend fun switchLogin(savedAccountId: String): Result<CloudflareRestoreUi> {
            switchedIds += savedAccountId
            if (savedAccountId !in saved) return Result.failure(CloudflareUiException("That login is no longer saved."))
            activeId = savedAccountId
            return Result.success(current())
        }

        override suspend fun removeLogin(savedAccountId: String): Result<CloudflareRestoreUi> {
            removedIds += savedAccountId
            saved.remove(savedAccountId)
            if (activeId == savedAccountId) activeId = saved.keys.firstOrNull()
            return Result.success(current())
        }

        override suspend fun refreshLoginProfiles(): Result<List<ProviderAccountUi>> {
            profileRefreshCalls += 1
            renamedOnProfileRefresh.forEach { (id, name) -> if (id in saved) saved[id] = name }
            return Result.success(menu())
        }

        private fun current(): CloudflareRestoreUi = activeId?.let { id ->
            CloudflareRestoreUi.Available(loginDashboard(id, saved.getValue(id), CloudflareCacheState.CACHED_FRESH))
        } ?: CloudflareRestoreUi.NotConnected

        private fun menu(): List<ProviderAccountUi> = saved.map { (id, name) ->
            ProviderAccountUi(id = id, displayName = name, detail = "Scoped API token", isActive = id == activeId)
        }
    }

    private companion object {
        const val PRIMARY = "primary"
        const val SECOND = "second-login"
        const val ADDED = "added-login"

        fun zoneId(savedAccountId: String) = "zone-$savedAccountId"

        fun loginDashboard(
            savedAccountId: String,
            name: String,
            cacheState: CloudflareCacheState,
            selectedAccountId: String = "account-primary",
        ): CloudflareDashboardUi = CloudflareDashboardUi(
            profile = CloudflareProfileUi(
                id = "profile-$savedAccountId",
                displayName = name,
                tokenStatus = "active",
                savedAccountId = savedAccountId,
            ),
            accounts = listOf(
                CloudflareAccountUi("account-primary", "Primary", "standard"),
                CloudflareAccountUi("account-secondary", "Secondary", "standard"),
            ),
            loadedAccountCount = 2,
            accountsComplete = true,
            accountsTruncatedForDisplay = false,
            selectedAccountId = selectedAccountId,
            inventory = CloudflareInventoryUi(
                accountId = selectedAccountId,
                zones = listOf(
                    CloudflareZoneUi(zoneId(savedAccountId), "$savedAccountId.example", "active", "full", false, name, "Free"),
                ),
                pagesProjects = emptyList(),
                workers = emptyList(),
                loadedZoneCount = 1,
                loadedPagesProjectCount = 0,
                loadedWorkerCount = 0,
                zonesComplete = true,
                pagesComplete = true,
                workersComplete = true,
                zonesTruncatedForDisplay = false,
                pagesTruncatedForDisplay = false,
                workersTruncatedForDisplay = false,
                warnings = emptyList(),
            ),
            warnings = emptyList(),
            fetchedAtMillis = System.currentTimeMillis(),
            cacheState = cacheState,
        )
    }
}
