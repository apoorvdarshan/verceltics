package com.apoorvdarshan.verceltics.ui.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue
import kotlinx.coroutines.CompletableDeferred

enum class DebugNetlifyScenario {
    DISCONNECTED,
    CONNECTED,
    OFFLINE_SAVED,
}

/** Deterministic in-process Netlify provider used only by the debug instrumentation host. */
object DebugNetlifyGatewayController {
    @Volatile
    var scenario: DebugNetlifyScenario = DebugNetlifyScenario.DISCONNECTED
        private set

    @Volatile
    var restoreCalls: Int = 0
        private set

    @Volatile
    var connectCalls: Int = 0
        private set

    @Volatile
    var refreshCalls: Int = 0
        private set

    @Volatile
    var disconnectCalls: Int = 0
        private set

    @Volatile
    private var connectStarted = CompletableDeferred<Unit>()

    @Volatile
    private var connectRelease = CompletableDeferred<Unit>().apply { complete(Unit) }

    @Synchronized
    fun configure(scenario: DebugNetlifyScenario, blockConnect: Boolean = false) {
        this.scenario = scenario
        restoreCalls = 0
        connectCalls = 0
        refreshCalls = 0
        disconnectCalls = 0
        connectStarted = CompletableDeferred()
        connectRelease = CompletableDeferred<Unit>().also { gate ->
            if (!blockConnect) gate.complete(Unit)
        }
    }

    fun isConnectStarted(): Boolean = connectStarted.isCompleted

    fun releaseConnect() {
        connectRelease.complete(Unit)
    }

    internal fun restored(): NetlifyRestoreUi {
        restoreCalls += 1
        return when (scenario) {
            DebugNetlifyScenario.DISCONNECTED -> NetlifyRestoreUi.NotConnected
            DebugNetlifyScenario.CONNECTED -> NetlifyRestoreUi.Available(
                dashboard.copy(cacheState = NetlifyCacheState.CACHED_FRESH),
            )
            DebugNetlifyScenario.OFFLINE_SAVED -> NetlifyRestoreUi.Available(
                dashboard.copy(
                    cacheState = NetlifyCacheState.CACHED_STALE,
                    providerInventoryComplete = false,
                    warnings = listOf("Offline debug cache; refresh online for current Netlify data."),
                ),
            )
        }
    }

    internal suspend fun connected(): NetlifyDashboardUi {
        connectCalls += 1
        connectStarted.complete(Unit)
        connectRelease.await()
        scenario = DebugNetlifyScenario.CONNECTED
        return dashboard
    }

    internal fun refreshed(): Result<NetlifyDashboardUi> {
        refreshCalls += 1
        return when (scenario) {
            DebugNetlifyScenario.CONNECTED -> Result.success(dashboard)
            DebugNetlifyScenario.OFFLINE_SAVED -> Result.failure(
                NetlifyUiException("Netlify is offline in this deterministic scenario."),
            )
            DebugNetlifyScenario.DISCONNECTED -> Result.failure(
                NetlifyUiException("Connect a Netlify account first."),
            )
        }
    }

    internal fun disconnected() {
        disconnectCalls += 1
        scenario = DebugNetlifyScenario.DISCONNECTED
    }

    val dashboard = NetlifyDashboardUi(
        account = NetlifyAccountUi(
            id = "netlify-test-account",
            displayName = "Apoorv Netlify",
            email = "apoorv@example.com",
        ),
        sites = listOf(
            NetlifySiteUi(
                id = "netlify-test-site",
                name = "verceltics-netlify",
                subtitle = "verceltics.netlify.app",
                url = "https://verceltics.netlify.app",
                status = "current",
                updatedAtMillis = 1_700_000_000_000,
            ),
        ),
        loadedSiteCount = 1,
        providerInventoryComplete = true,
        warnings = emptyList(),
        fetchedAtMillis = 1_700_000_000_000,
        cacheState = NetlifyCacheState.LIVE,
    )

    val siteWorkspace = NetlifySiteWorkspaceUi(
        siteId = "netlify-test-site",
        details = NetlifyResourceUi.Available(
            NetlifySiteDetailsUi(
                site = dashboard.sites.single(),
                domains = listOf(
                    NetlifyDomainUi("verceltics.netlify.app", "NETLIFY_SUBDOMAIN"),
                    NetlifyDomainUi("example.com", "CUSTOM"),
                ),
                buildControls = NetlifyBuildControlsUi(
                    buildsStopped = false,
                    repositoryUrl = "https://github.com/example/verceltics",
                    repositoryPath = null,
                    repositoryBranch = "main",
                    baseDirectory = null,
                    publishDirectory = "dist",
                    functionsDirectory = "netlify/functions",
                    buildCommand = "npm run build",
                    allowedBranches = listOf("main"),
                    provider = "github",
                ),
                publishedDeployment = NetlifyDeploymentUi(
                    id = "debug-deploy",
                    title = "Production deploy",
                    status = "ready",
                    createdAtMillis = 1_700_000_000_000,
                    url = "https://verceltics.netlify.app",
                    branch = "main",
                    commitMessage = "Ship Android Netlify workspace",
                ),
            ),
        ),
        deployments = NetlifyCollectionUi(
            items = listOf(
                NetlifyDeploymentUi(
                    id = "debug-deploy",
                    title = "Production deploy",
                    status = "ready",
                    createdAtMillis = 1_700_000_000_000,
                    url = "https://verceltics.netlify.app",
                    branch = "main",
                    commitMessage = "Ship Android Netlify workspace",
                ),
            ),
            loadedItemCount = 1,
            providerCollectionComplete = true,
            warning = null,
        ),
        builds = NetlifyCollectionUi(
            items = listOf(
                NetlifyBuildUi(
                    id = "debug-build",
                    deploymentId = "debug-deploy",
                    commitSha = "abc123def456",
                    isDone = true,
                    error = null,
                    createdAtMillis = 1_700_000_000_000,
                ),
            ),
            loadedItemCount = 1,
            providerCollectionComplete = true,
            warning = null,
        ),
    )
}

class DebugNetlifyUiGateway : NetlifyUiGateway {
    override suspend fun restore(): Result<NetlifyRestoreUi> =
        Result.success(DebugNetlifyGatewayController.restored())

    override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> {
        personalToken.use { require(it.isNotBlank()) }
        return Result.success(DebugNetlifyGatewayController.connected())
    }

    override suspend fun refresh(): Result<NetlifyDashboardUi> =
        DebugNetlifyGatewayController.refreshed()

    override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> =
        if (siteId == DebugNetlifyGatewayController.siteWorkspace.siteId) {
            Result.success(DebugNetlifyGatewayController.siteWorkspace)
        } else {
            Result.failure(NetlifyUiException("The debug Netlify site was not found."))
        }

    override suspend fun disconnect(): Result<Unit> = Result.success(
        DebugNetlifyGatewayController.disconnected(),
    )

    /** One deterministic saved account whenever the debug scenario is connected. */
    override suspend fun accounts(): Result<List<com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi>> = Result.success(
        if (DebugNetlifyGatewayController.scenario == DebugNetlifyScenario.DISCONNECTED) {
            emptyList()
        } else {
            val account = DebugNetlifyGatewayController.dashboard.account
            listOf(com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi(account.id, account.displayName, account.email, isActive = true))
        },
    )
}
