package com.apoorvdarshan.verceltics.ui.vercel

import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsBreakdownUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsDataUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsEnvironment
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsLoadUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsOverviewUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsPointUi
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsRange
import com.apoorvdarshan.verceltics.ui.VercelDashboardUi
import com.apoorvdarshan.verceltics.ui.VercelDeploymentEventUi
import com.apoorvdarshan.verceltics.ui.VercelDeploymentUi
import com.apoorvdarshan.verceltics.ui.VercelProjectContextUi
import com.apoorvdarshan.verceltics.ui.VercelProjectDeploymentUi
import com.apoorvdarshan.verceltics.ui.VercelProjectScopeUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.VercelRestoreUi
import com.apoorvdarshan.verceltics.ui.VercelUiGateway
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic Vercel fixtures for Compose tests; nothing here touches the network. */
internal object VercelTestFixtures {
    private val now = System.currentTimeMillis()
    private const val MINUTE = 60_000L

    val TEAM = VercelProjectScopeUi("Studio", "studio", isTeam = true)

    val FRESH = VercelProjectUi(
        id = "prj_fresh",
        name = "studio-web",
        framework = "nextjs",
        updatedAtMillis = now - 600 * MINUTE,
        teamId = "team_studio",
        primaryDomain = "studio.example",
        repository = "acme/web",
        scope = TEAM,
        lastDeployment = VercelProjectDeploymentUi("Ship the pricing refresh", now - 5 * MINUTE),
    )
    val DOCS = VercelProjectUi(
        id = "prj_docs",
        name = "docs",
        framework = "astro",
        updatedAtMillis = now - 60 * MINUTE,
        primaryDomain = "handbook.example",
        lastDeployment = VercelProjectDeploymentUi("Document webhooks", now - 120 * MINUTE),
    )
    val BARE = VercelProjectUi(id = "prj_bare", name = "api", framework = null, updatedAtMillis = null)

    val DASHBOARD = VercelDashboardUi(
        account = VercelAccountUi("Apoorv", "apoorv@example.com", username = "apoorv"),
        projects = listOf(BARE, DOCS, FRESH),
    )

    val DEPLOYMENT = VercelDeploymentUi(
        id = "dpl_ready",
        eventsIdentifier = "dpl_ready",
        name = "studio-web",
        url = "studio-web-abc.vercel.app",
        inspectorUrl = "https://vercel.com/studio/studio-web/abc",
        state = "READY",
        target = "Production",
        createdAtMillis = now - 5 * MINUTE,
        commitMessage = "Ship the pricing refresh",
        branch = "main",
        commitSha = "0f1e2d3c4b5a69788796",
        repository = "acme/web",
        creator = "apoorv",
    )

    val EVENTS = listOf(
        VercelDeploymentEventUi("0", "command", now - 4 * MINUTE, "Running \"npm run build\"", null),
        VercelDeploymentEventUi("1", "stdout", now - 3 * MINUTE, "Compiled successfully", null),
        VercelDeploymentEventUi("2", "ready", now - 2 * MINUTE, "Deployment ready", null),
    )

    fun analytics(
        bounce: Double? = 45.0,
        previousBounce: Double? = 40.0,
    ) = VercelAnalyticsDataUi(
        overview = VercelAnalyticsOverviewUi(pageViews = 12_806, visitors = 2_104, bounceRate = bounce),
        previousOverview = VercelAnalyticsOverviewUi(pageViews = 11_000, visitors = 1_900, bounceRate = previousBounce),
        timeseries = listOf(
            VercelAnalyticsPointUi("2026-08-24", 1_320, 240, 41.0),
            VercelAnalyticsPointUi("2026-08-25", 2_920, 480, 47.0),
            VercelAnalyticsPointUi("2026-08-26", 2_146, 324, 44.0),
        ),
        pages = listOf(VercelAnalyticsBreakdownUi("/", 7_840, 1_380), VercelAnalyticsBreakdownUi("/pricing", 2_946, 510)),
        referrers = listOf(VercelAnalyticsBreakdownUi("", 4_040, 720)),
        countries = listOf(VercelAnalyticsBreakdownUi("IN", 5_500, 920), VercelAnalyticsBreakdownUi("US", 3_920, 640)),
    )
}

internal class FakeVercelGateway(
    var restoreResult: VercelRestoreUi = VercelRestoreUi.Available(VercelTestFixtures.DASHBOARD),
) : VercelUiGateway {
    var refreshResult: Result<com.apoorvdarshan.verceltics.ui.VercelDashboardUi> = Result.success(VercelTestFixtures.DASHBOARD)
    val refreshCalls = AtomicInteger()
    val eventCalls = AtomicInteger()

    override suspend fun restore(): Result<VercelRestoreUi> = Result.success(restoreResult)

    override suspend fun connect(personalToken: String) = Result.success(VercelTestFixtures.DASHBOARD)

    override suspend fun refresh(): Result<com.apoorvdarshan.verceltics.ui.VercelDashboardUi> {
        refreshCalls.incrementAndGet()
        return refreshResult
    }

    override suspend fun loadProjectAnalytics(
        project: VercelProjectUi,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
    ): Result<VercelAnalyticsLoadUi> = Result.success(VercelAnalyticsLoadUi.Available(VercelTestFixtures.analytics()))

    override suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> =
        Result.success(
            VercelProjectContextUi(
                project = project,
                domains = listOfNotNull(project.primaryDomain, "${project.name}.vercel.app"),
                deployments = listOf(VercelTestFixtures.DEPLOYMENT),
            ),
        )

    override suspend fun loadDeploymentEvents(
        project: VercelProjectUi,
        deployment: VercelDeploymentUi,
    ): Result<List<VercelDeploymentEventUi>> {
        eventCalls.incrementAndGet()
        return Result.success(VercelTestFixtures.EVENTS)
    }

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
}
