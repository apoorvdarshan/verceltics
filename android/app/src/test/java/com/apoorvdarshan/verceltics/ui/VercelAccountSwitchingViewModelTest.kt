package com.apoorvdarshan.verceltics.ui

import java.io.IOException
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

/** Multiple saved Vercel accounts: switching, adding, removing, rotation and per-account caches. */
@OptIn(ExperimentalCoroutinesApi::class)
class VercelAccountSwitchingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = 1_712_000_000_000L

    @Before
    fun setMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun restoreListsEverySavedAccountWithTheActiveOneShown() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VercelConnectionStatus.CONNECTED, state.status)
        assertEquals(listOf(PERSONAL.id, TEAM.id), state.accounts.map(VercelAccountUi::id))
        assertEquals(PERSONAL.id, state.activeAccountId)
        assertEquals(listOf("prj_personal", "prj_shared"), state.dashboard?.projects?.map(VercelProjectUi::id))
    }

    @Test
    fun switchingLoadsTheOtherAccountThenReusesItsProjectsWithinFifteenMinutes() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        gateway.refreshRelease = CompletableDeferred()

        viewModel.switchAccount(TEAM.id)
        runCurrent()

        val loading = viewModel.uiState.value
        assertTrue("The new account's projects load in place.", loading.isLoadingAccount)
        assertEquals(TEAM.id, loading.activeAccountId)
        assertTrue(loading.dashboard?.projects.orEmpty().isEmpty())
        assertEquals(VercelConnectionMutation.SWITCHING, loading.mutation)

        gateway.refreshRelease?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("prj_team", "prj_shared"), viewModel.uiState.value.dashboard?.projects?.map(VercelProjectUi::id))
        assertFalse(viewModel.uiState.value.isLoadingAccount)
        assertEquals(mapOf(TEAM.id to 1), gateway.refreshesByAccount)

        now += 14 * MINUTE
        viewModel.switchAccount(PERSONAL.id)
        advanceUntilIdle()
        assertEquals(PERSONAL.id, viewModel.uiState.value.activeAccountId)
        assertEquals("Fresh cached projects are not refetched.", null, gateway.refreshesByAccount[PERSONAL.id])
        assertEquals(listOf("prj_personal", "prj_shared"), viewModel.uiState.value.dashboard?.projects?.map(VercelProjectUi::id))

        now += 2 * MINUTE
        viewModel.switchAccount(TEAM.id)
        advanceUntilIdle()
        assertEquals("Stale cached projects reload.", 2, gateway.refreshesByAccount[TEAM.id])
    }

    @Test
    fun analyticsCachesAreKeptPerAccount() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(SHARED_PROJECT)
        advanceUntilIdle()
        assertEquals(1, gateway.analyticsCalls)
        assertEquals(PERSONAL.id, gateway.analyticsAccounts.last())
        assertEquals(100L, viewModel.analyticsState.value.data?.overview?.pageViews)

        viewModel.switchAccount(TEAM.id)
        advanceUntilIdle()
        assertNull("Switching closes the open project.", viewModel.analyticsState.value.projectId)
        viewModel.openProjectAnalytics(SHARED_PROJECT)
        advanceUntilIdle()
        assertEquals("Another account never reuses the first account's report.", 2, gateway.analyticsCalls)
        assertEquals(200L, viewModel.analyticsState.value.data?.overview?.pageViews)
        assertEquals(2, gateway.contextCalls)

        viewModel.closeProjectAnalytics()
        viewModel.switchAccount(PERSONAL.id)
        advanceUntilIdle()
        viewModel.openProjectAnalytics(SHARED_PROJECT)
        advanceUntilIdle()
        assertEquals("The first account's report is still cached.", 2, gateway.analyticsCalls)
        assertEquals(100L, viewModel.analyticsState.value.data?.overview?.pageViews)
        assertEquals(2, gateway.contextCalls)
    }

    @Test
    fun addingAnAccountKeepsTheOthersAndShowsTheNewOne() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.startAddingAccount()
        assertTrue(viewModel.uiState.value.isAddingAccount)
        viewModel.connect("token-new")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("A successful connect closes the add-account form.", state.isAddingAccount)
        assertEquals(listOf(PERSONAL.id, TEAM.id, NEW.id), state.accounts.map(VercelAccountUi::id))
        assertEquals(NEW.id, state.activeAccountId)
        assertEquals(0, gateway.removals)
    }

    @Test
    fun aFailedAddKeepsTheFormOpenAndTheWorkspaceErrorFree() = runTest(dispatcher) {
        val gateway = MultiAccountGateway().apply { connectFailure = IllegalStateException("Vercel rejected this personal token.") }
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.startAddingAccount()
        viewModel.connect("bad-token")
        advanceUntilIdle()
        viewModel.connect("  ")

        val state = viewModel.uiState.value
        assertTrue(state.isAddingAccount)
        assertEquals("Enter a Vercel personal access token.", state.connectError)
        assertNull("The project list never shows an add-account failure.", state.error)
        assertEquals(PERSONAL.id, state.activeAccountId)

        viewModel.cancelAddingAccount()
        assertFalse(viewModel.uiState.value.isAddingAccount)
        assertNull(viewModel.uiState.value.connectError)
    }

    @Test
    fun aBackgroundRefreshDoesNotDismissTheAddAccountForm() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.startAddingAccount()
        viewModel.refresh()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isAddingAccount)
    }

    @Test
    fun reconnectingTheSameIdentityRotatesInPlaceAndDropsItsCachedReports() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(SHARED_PROJECT)
        advanceUntilIdle()
        viewModel.closeProjectAnalytics()

        viewModel.startAddingAccount()
        viewModel.connect("token-personal-rotated")
        advanceUntilIdle()
        viewModel.openProjectAnalytics(SHARED_PROJECT)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("No duplicate account appears.", listOf(PERSONAL.id, TEAM.id), state.accounts.map(VercelAccountUi::id))
        assertEquals(PERSONAL.id, state.activeAccountId)
        assertEquals("token-personal-rotated", gateway.tokens[PERSONAL.id])
        assertEquals("A rotated token reloads its reports.", 2, gateway.analyticsCalls)
    }

    @Test
    fun removingTheCurrentAccountShowsTheNextSavedAccount() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.removeCurrentAccount()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VercelConnectionStatus.CONNECTED, state.status)
        assertEquals(listOf(TEAM.id), state.accounts.map(VercelAccountUi::id))
        assertEquals(TEAM.id, state.activeAccountId)
        assertEquals(listOf("prj_team", "prj_shared"), state.dashboard?.projects?.map(VercelProjectUi::id))
        assertNull(state.mutation)
    }

    @Test
    fun removingAnInactiveAccountKeepsTheActiveDashboard() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        val refreshesBefore = gateway.refreshesByAccount.values.sum()

        viewModel.removeAccount(TEAM.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(PERSONAL.id), state.accounts.map(VercelAccountUi::id))
        assertEquals(PERSONAL.id, state.activeAccountId)
        assertEquals(refreshesBefore, gateway.refreshesByAccount.values.sum())
    }

    @Test
    fun removingAllAccountsDisconnects() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.removeAllAccounts()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VercelConnectionStatus.DISCONNECTED, state.status)
        assertTrue(state.accounts.isEmpty())
        assertTrue(gateway.saved.isEmpty())
        assertEquals(1, gateway.removeAllCalls)
    }

    @Test
    fun removingTheLastAccountDisconnects() = runTest(dispatcher) {
        val gateway = MultiAccountGateway(savedIds = listOf(PERSONAL.id))
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.removeCurrentAccount()
        advanceUntilIdle()

        assertEquals(VercelConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
    }

    @Test
    fun profilesRefreshOnLaunchWithoutResurrectingRemovedAccounts() = runTest(dispatcher) {
        val gateway = MultiAccountGateway()
        gateway.profileRelease = CompletableDeferred()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        assertEquals(1, gateway.profileRefreshes)

        viewModel.removeAccount(TEAM.id)
        advanceUntilIdle()
        gateway.profileRelease?.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Removed accounts stay removed.", listOf(PERSONAL.id), state.accounts.map(VercelAccountUi::id))
        assertEquals("Apoorv (renamed)", state.accounts.single().displayName)
        assertEquals("https://api.vercel.com/www/avatar/new", state.accounts.single().avatarUrl)
        assertEquals("The visible dashboard account updates too.", "Apoorv (renamed)", state.dashboard?.account?.displayName)

        viewModel.restore()
        advanceUntilIdle()
        assertEquals("Profiles refresh once per launch.", 1, gateway.profileRefreshes)
    }

    @Test
    fun aSwitchFailureKeepsTheCurrentAccount() = runTest(dispatcher) {
        val gateway = MultiAccountGateway().apply { switchFailure = IllegalStateException("That Vercel account is no longer saved on this device.") }
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.switchAccount(TEAM.id)
        advanceUntilIdle()

        assertEquals(PERSONAL.id, viewModel.uiState.value.activeAccountId)
        assertEquals("That Vercel account is no longer saved on this device.", viewModel.uiState.value.error)
    }

    @Test
    fun singleAccountSourcesStillReportTheirAccount() = runTest(dispatcher) {
        val single = object : VercelUiGateway {
            override suspend fun restore(): Result<VercelRestoreUi> =
                Result.success(VercelRestoreUi.Available(VercelDashboardUi(PERSONAL, emptyList())))

            override suspend fun connect(personalToken: String) = Result.success(VercelDashboardUi(PERSONAL, emptyList()))

            override suspend fun refresh() = Result.success(VercelDashboardUi(PERSONAL, emptyList()))

            override suspend fun loadProjectAnalytics(
                project: VercelProjectUi,
                range: VercelAnalyticsRange,
                environment: VercelAnalyticsEnvironment,
            ): Result<VercelAnalyticsLoadUi> = Result.failure(IOException("unused"))

            override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
        }
        val viewModel = VercelConnectionViewModel(single) { now }
        advanceUntilIdle()

        assertEquals(listOf(PERSONAL), viewModel.uiState.value.savedAccounts)

        viewModel.removeCurrentAccount()
        advanceUntilIdle()
        assertEquals(VercelConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
    }

    /** An in-memory multi-account backend; the token decides which identity a connect resolves to. */
    private inner class MultiAccountGateway(
        savedIds: List<String> = listOf(PERSONAL.id, TEAM.id),
    ) : VercelUiGateway {
        val saved = savedIds.toMutableList()
        var activeId: String? = savedIds.firstOrNull()
        val tokens = mutableMapOf(PERSONAL.id to "token-personal", TEAM.id to "token-team")
        val refreshesByAccount = mutableMapOf<String, Int>()
        val analyticsAccounts = mutableListOf<String?>()
        var analyticsCalls = 0
        var contextCalls = 0
        var removals = 0
        var removeAllCalls = 0
        var profileRefreshes = 0
        var connectFailure: Exception? = null
        var switchFailure: Exception? = null
        var refreshRelease: CompletableDeferred<Unit>? = null
        var profileRelease: CompletableDeferred<Unit>? = null

        private fun accounts() = VercelAccountsUi(ALL.filter { it.id in saved }, activeId)

        private fun dashboardFor(id: String?): VercelDashboardUi = when (id) {
            TEAM.id -> VercelDashboardUi(TEAM, listOf(TEAM_PROJECT, SHARED_PROJECT))
            NEW.id -> VercelDashboardUi(NEW, emptyList())
            else -> VercelDashboardUi(PERSONAL, listOf(PERSONAL_PROJECT, SHARED_PROJECT))
        }

        override suspend fun restore(): Result<VercelRestoreUi> {
            val active = activeId ?: return Result.success(VercelRestoreUi.NoSavedAccount)
            return Result.success(VercelRestoreUi.Available(dashboardFor(active), accounts()))
        }

        override suspend fun connect(personalToken: String): Result<VercelDashboardUi> {
            connectFailure?.let { return Result.failure(it) }
            val identity = if (personalToken.startsWith("token-personal")) PERSONAL.id else NEW.id
            tokens[identity] = personalToken
            if (identity !in saved) saved += identity
            activeId = identity
            return Result.success(dashboardFor(identity))
        }

        override suspend fun refresh(): Result<VercelDashboardUi> {
            val active = activeId ?: return Result.failure(IllegalStateException("Connect a Vercel account first."))
            refreshesByAccount[active] = (refreshesByAccount[active] ?: 0) + 1
            refreshRelease?.await()
            return Result.success(dashboardFor(active))
        }

        override suspend fun loadAccounts(): Result<VercelAccountsUi> = Result.success(accounts())

        override suspend fun switchAccount(accountId: String): Result<VercelAccountsUi> {
            switchFailure?.let { return Result.failure(it) }
            require(accountId in saved)
            activeId = accountId
            return Result.success(accounts())
        }

        override suspend fun removeAccount(accountId: String): Result<VercelAccountsUi> {
            removals += 1
            saved -= accountId
            if (activeId == accountId) activeId = saved.firstOrNull()
            return Result.success(accounts())
        }

        override suspend fun removeAllAccounts(): Result<Unit> {
            removeAllCalls += 1
            saved.clear()
            activeId = null
            return Result.success(Unit)
        }

        override suspend fun refreshAccountProfiles(): Result<VercelAccountsUi> {
            profileRefreshes += 1
            val snapshot = ALL.filter { it.id in saved }
            profileRelease?.await()
            return Result.success(
                VercelAccountsUi(
                    snapshot.map {
                        if (it.id == PERSONAL.id) {
                            it.copy(displayName = "Apoorv (renamed)", avatarUrl = "https://api.vercel.com/www/avatar/new")
                        } else {
                            it
                        }
                    },
                    activeId,
                ),
            )
        }

        override suspend fun loadProjectAnalytics(
            project: VercelProjectUi,
            range: VercelAnalyticsRange,
            environment: VercelAnalyticsEnvironment,
        ): Result<VercelAnalyticsLoadUi> {
            analyticsCalls += 1
            analyticsAccounts += activeId
            val views = if (activeId == TEAM.id) 200L else 100L
            return Result.success(
                VercelAnalyticsLoadUi.Available(
                    VercelAnalyticsDataUi(
                        overview = VercelAnalyticsOverviewUi(views, views / 2, 40.0),
                        previousOverview = null,
                        timeseries = emptyList(),
                        pages = emptyList(),
                        referrers = emptyList(),
                        countries = emptyList(),
                    ),
                ),
            )
        }

        override suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> {
            contextCalls += 1
            return Result.success(VercelProjectContextUi(project, listOfNotNull(project.primaryDomain), emptyList()))
        }

        override suspend fun disconnect(): Result<Unit> = removeAccount(activeId.orEmpty()).map { }
    }

    private companion object {
        const val MINUTE = 60_000L
        val PERSONAL = VercelAccountUi("Apoorv", "apoorv@example.com", username = "apoorv", id = "user_personal")
        val TEAM = VercelAccountUi("Studio token", "studio@example.com", username = "studio", id = "user_team")
        val NEW = VercelAccountUi("New Person", "new@example.com", username = "new", id = "user_new")
        val ALL = listOf(PERSONAL, TEAM, NEW)
        val PERSONAL_PROJECT = VercelProjectUi("prj_personal", "portfolio", null, 1L)
        val TEAM_PROJECT = VercelProjectUi("prj_team", "admin", null, 1L, teamId = "team_studio")
        val SHARED_PROJECT = VercelProjectUi("prj_shared", "studio-web", null, 1L, teamId = "team_studio", primaryDomain = "studio.example")
    }
}
