package com.apoorvdarshan.verceltics.ui

import java.io.IOException
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

/** Stale-while-revalidate, project context, long-history unlocking and build events. */
@OptIn(ExperimentalCoroutinesApi::class)
class VercelWorkspaceViewModelTest {
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
    fun foregroundRefreshWaitsForTheFifteenMinuteWindow() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        assertEquals(now, viewModel.uiState.value.lastUpdatedMillis)

        now += 14 * MINUTE
        viewModel.refreshIfStale()
        advanceUntilIdle()
        assertEquals("Fresh projects are not refetched on resume.", 0, gateway.refreshCalls)

        now += MINUTE
        viewModel.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, gateway.refreshCalls)
        assertEquals(now, viewModel.uiState.value.lastUpdatedMillis)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("Manual refresh always reloads.", 2, gateway.refreshCalls)
    }

    @Test
    fun foregroundRefreshRetriesAfterAFailureEvenInsideTheWindow() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        gateway.refreshResult = Result.failure(IOException("offline"))
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals("offline", viewModel.uiState.value.error)
        assertEquals("Cached projects stay visible.", DASHBOARD, viewModel.uiState.value.dashboard)

        gateway.refreshResult = Result.success(DASHBOARD)
        now += MINUTE
        viewModel.refreshIfStale()
        advanceUntilIdle()

        assertEquals(2, gateway.refreshCalls)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun savedUnavailableAccountAlwaysRetriesOnResume() = runTest(dispatcher) {
        val gateway = FakeGateway(
            restore = VercelRestoreUi.DashboardUnavailable(DASHBOARD.account, IOException("offline")),
        )
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.refreshIfStale()
        advanceUntilIdle()

        assertEquals(1, gateway.refreshCalls)
        assertEquals(VercelConnectionStatus.CONNECTED, viewModel.uiState.value.status)
    }

    @Test
    fun analyticsReportsAreReusedForTenMinutes() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        viewModel.closeProjectAnalytics()
        now += 9 * MINUTE
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        assertEquals(1, gateway.analyticsCalls)
        assertTrue(viewModel.analyticsState.value.hasVisibleContent)

        viewModel.closeProjectAnalytics()
        now += MINUTE
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        assertEquals("Stale reports revalidate.", 2, gateway.analyticsCalls)
    }

    @Test
    fun disconnectClearsEveryProjectCache() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        viewModel.openDeployment(DEPLOYMENT)
        advanceUntilIdle()
        assertEquals(1, gateway.analyticsCalls)
        assertEquals(1, gateway.contextCalls)
        assertEquals(1, gateway.eventCalls)

        viewModel.disconnect()
        advanceUntilIdle()
        assertEquals(VercelConnectionStatus.DISCONNECTED, viewModel.uiState.value.status)
        assertNull(viewModel.analyticsState.value.data)
        assertNull(viewModel.deploymentState.value.deploymentId)

        viewModel.connect("another-token")
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        viewModel.openDeployment(DEPLOYMENT)
        advanceUntilIdle()

        assertEquals("No report is reused across a disconnect.", 2, gateway.analyticsCalls)
        assertEquals(2, gateway.contextCalls)
        assertEquals(2, gateway.eventCalls)
    }

    @Test
    fun projectContextMergesPartialResultsAndCachesCompleteOnes() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(PROJECT)
        val loading = viewModel.analyticsState.value
        assertTrue(loading.isContextLoading)
        assertEquals("The listed domain shows before context loads.", listOf("studio.example"), loading.domains)
        advanceUntilIdle()

        val loaded = viewModel.analyticsState.value
        assertEquals("astro", loaded.projectDetails?.framework)
        assertEquals(listOf("studio.example", "studio-web.vercel.app"), loaded.domains)
        assertEquals(listOf(DEPLOYMENT), loaded.recentDeployments)
        assertTrue(loaded.hasLoadedContext)
        assertFalse(loaded.isContextLoading)

        gateway.contextResult = Result.success(VercelProjectContextUi(project = null, domains = emptyList(), deployments = null))
        viewModel.refreshProjectAnalytics()
        advanceUntilIdle()

        val partial = viewModel.analyticsState.value
        assertEquals("A failed detail request keeps the loaded details.", "astro", partial.projectDetails?.framework)
        assertEquals("An empty domain list falls back to the primary domain.", listOf("studio.example"), partial.domains)
        assertEquals(listOf(DEPLOYMENT), partial.recentDeployments)

        viewModel.closeProjectAnalytics()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        assertEquals("The first complete context is reused within 15 minutes.", 2, gateway.contextCalls)
        assertEquals(listOf("studio.example", "studio-web.vercel.app"), viewModel.analyticsState.value.domains)
    }

    @Test
    fun contextFailureKeepsWhatIsVisible() = runTest(dispatcher) {
        val gateway = FakeGateway().apply { contextResult = Result.failure(IOException("offline")) }
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()

        val state = viewModel.analyticsState.value
        assertEquals(PROJECT, state.projectDetails)
        assertEquals(listOf("studio.example"), state.domains)
        assertTrue(state.recentDeployments.isEmpty())
        assertTrue(state.hasLoadedContext)
        assertNull("Context failures never replace analytics errors.", state.error)
    }

    @Test
    fun aSuccessfulLongRangeUnlocksAndPersistsLongHistoryOnce() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        assertFalse(viewModel.analyticsState.value.hasLongAnalyticsHistory)

        gateway.analyticsResult = Result.success(VercelAnalyticsLoadUi.Unavailable("Plan limit"))
        viewModel.selectAnalyticsRange(VercelAnalyticsRange.QUARTER)
        advanceUntilIdle()
        assertFalse("An unavailable long range proves nothing.", viewModel.analyticsState.value.hasLongAnalyticsHistory)
        assertEquals(0, gateway.markCalls)

        gateway.analyticsResult = Result.success(ANALYTICS)
        viewModel.selectAnalyticsRange(VercelAnalyticsRange.YEAR)
        advanceUntilIdle()
        assertTrue(viewModel.analyticsState.value.hasLongAnalyticsHistory)
        assertEquals(1, gateway.markCalls)

        viewModel.selectAnalyticsRange(VercelAnalyticsRange.MONTH)
        advanceUntilIdle()
        viewModel.selectAnalyticsRange(VercelAnalyticsRange.QUARTER)
        advanceUntilIdle()
        assertEquals("Persisted once per account.", 1, gateway.markCalls)
    }

    @Test
    fun accountsWithKnownLongHistoryStartUnlocked() = runTest(dispatcher) {
        val unlocked = DASHBOARD.copy(account = DASHBOARD.account.copy(hasLongAnalyticsHistory = true))
        val gateway = FakeGateway(restore = VercelRestoreUi.Available(unlocked)).apply { refreshResult = Result.success(unlocked) }
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(PROJECT)
        viewModel.selectAnalyticsRange(VercelAnalyticsRange.YEAR)
        advanceUntilIdle()

        assertTrue(viewModel.analyticsState.value.hasLongAnalyticsHistory)
        assertEquals(0, gateway.markCalls)
    }

    @Test
    fun rangeChangesWhileLoadingApplyOnlyTheNewestSelection() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()

        viewModel.openProjectAnalytics(PROJECT)
        assertTrue(viewModel.analyticsState.value.isLoading)
        viewModel.selectAnalyticsRange(VercelAnalyticsRange.DAY)
        viewModel.selectAnalyticsEnvironment(VercelAnalyticsEnvironment.PREVIEW)
        advanceUntilIdle()

        assertEquals(VercelAnalyticsRange.DAY, viewModel.analyticsState.value.displayedRange)
        assertEquals(VercelAnalyticsEnvironment.PREVIEW, viewModel.analyticsState.value.displayedEnvironment)
        assertEquals(VercelAnalyticsEnvironment.PREVIEW, gateway.lastEnvironment)
    }

    @Test
    fun deploymentEventsLoadCacheAndKeepResultsAfterAFailedRefresh() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()

        viewModel.openDeployment(DEPLOYMENT)
        assertTrue(viewModel.deploymentState.value.isLoading)
        assertFalse(viewModel.deploymentState.value.hasLoaded)
        advanceUntilIdle()
        assertEquals(EVENTS, viewModel.deploymentState.value.events)
        assertTrue(viewModel.deploymentState.value.hasLoaded)

        viewModel.closeDeployment()
        now += MINUTE
        viewModel.openDeployment(DEPLOYMENT)
        advanceUntilIdle()
        assertEquals("Events are reused for two minutes.", 1, gateway.eventCalls)
        assertEquals(EVENTS, viewModel.deploymentState.value.events)

        gateway.eventsResult = Result.failure(IOException("timeout"))
        viewModel.refreshDeploymentEvents()
        advanceUntilIdle()
        val failed = viewModel.deploymentState.value
        assertEquals(2, gateway.eventCalls)
        assertEquals("timeout", failed.error)
        assertEquals("The last successful events stay visible.", EVENTS, failed.events)
        assertTrue(failed.hasLoaded)
        assertFalse(failed.isLoading)
    }

    @Test
    fun deploymentsWithoutAnIdentifierExplainInsteadOfRequesting() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()

        viewModel.openDeployment(DEPLOYMENT.copy(id = "orphan", eventsIdentifier = null))
        advanceUntilIdle()

        assertEquals(0, gateway.eventCalls)
        assertEquals("This deployment does not include an event identifier.", viewModel.deploymentState.value.error)
        assertEquals("orphan", viewModel.deploymentState.value.deploymentId)
    }

    @Test
    fun closingTheProjectAlsoClosesItsDeployment() = runTest(dispatcher) {
        val gateway = FakeGateway()
        val viewModel = VercelConnectionViewModel(gateway) { now }
        advanceUntilIdle()
        viewModel.openProjectAnalytics(PROJECT)
        advanceUntilIdle()
        viewModel.openDeployment(DEPLOYMENT)
        advanceUntilIdle()

        viewModel.closeProjectAnalytics()

        assertEquals(VercelDeploymentDetailUiState(), viewModel.deploymentState.value)
        assertNull(viewModel.analyticsState.value.projectId)
    }

    @Test
    fun faviconFailuresAreSwallowed() = runTest(dispatcher) {
        val gateway = FakeGateway().apply { faviconFailure = IllegalStateException("decoder crashed") }
        val viewModel = VercelConnectionViewModel(gateway) { now }

        assertNull(viewModel.loadFavicon("studio.example"))
    }

    private class FakeGateway(
        private val restore: VercelRestoreUi = VercelRestoreUi.Available(DASHBOARD),
    ) : VercelUiGateway {
        var refreshResult: Result<VercelDashboardUi> = Result.success(DASHBOARD)
        var analyticsResult: Result<VercelAnalyticsLoadUi> = Result.success(ANALYTICS)
        var contextResult: Result<VercelProjectContextUi> = Result.success(
            VercelProjectContextUi(
                project = PROJECT.copy(framework = "astro"),
                domains = listOf("studio.example", "studio-web.vercel.app"),
                deployments = listOf(DEPLOYMENT),
            ),
        )
        var eventsResult: Result<List<VercelDeploymentEventUi>> = Result.success(EVENTS)
        var faviconFailure: Exception? = null
        var refreshCalls = 0
        var analyticsCalls = 0
        var contextCalls = 0
        var eventCalls = 0
        var markCalls = 0
        var lastEnvironment: VercelAnalyticsEnvironment? = null

        override suspend fun restore(): Result<VercelRestoreUi> = Result.success(restore)

        override suspend fun connect(personalToken: String): Result<VercelDashboardUi> = Result.success(DASHBOARD)

        override suspend fun refresh(): Result<VercelDashboardUi> {
            refreshCalls += 1
            return refreshResult
        }

        override suspend fun loadProjectAnalytics(
            project: VercelProjectUi,
            range: VercelAnalyticsRange,
            environment: VercelAnalyticsEnvironment,
        ): Result<VercelAnalyticsLoadUi> {
            analyticsCalls += 1
            lastEnvironment = environment
            return analyticsResult
        }

        override suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> {
            contextCalls += 1
            return contextResult
        }

        override suspend fun loadDeploymentEvents(
            project: VercelProjectUi,
            deployment: VercelDeploymentUi,
        ): Result<List<VercelDeploymentEventUi>> {
            eventCalls += 1
            return eventsResult
        }

        override suspend fun loadFavicon(domain: String): androidx.compose.ui.graphics.ImageBitmap? {
            faviconFailure?.let { throw it }
            return null
        }

        override suspend fun markLongAnalyticsHistoryAvailable(): Result<Unit> {
            markCalls += 1
            return Result.success(Unit)
        }

        override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val MINUTE = 60_000L
        val PROJECT = VercelProjectUi(
            id = "prj_web",
            name = "studio-web",
            framework = "nextjs",
            updatedAtMillis = 1L,
            teamId = "team_studio",
            primaryDomain = "studio.example",
        )
        val DASHBOARD = VercelDashboardUi(
            account = VercelAccountUi("Apoorv", "apoorv@example.com", username = "apoorv"),
            projects = listOf(PROJECT),
        )
        val ANALYTICS = VercelAnalyticsLoadUi.Available(
            VercelAnalyticsDataUi(
                overview = VercelAnalyticsOverviewUi(100, 40, 42.0),
                previousOverview = null,
                timeseries = emptyList(),
                pages = emptyList(),
                referrers = emptyList(),
                countries = emptyList(),
            ),
        )
        val DEPLOYMENT = VercelDeploymentUi(
            id = "dpl_ready",
            eventsIdentifier = "dpl_ready",
            name = "studio-web",
            url = "studio-web-abc.vercel.app",
            inspectorUrl = null,
            state = "READY",
            target = "Production",
            createdAtMillis = 1L,
        )
        val EVENTS = listOf(VercelDeploymentEventUi("0-1-stdout", "stdout", 1L, "Build Completed", null))
    }
}
