package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import kotlinx.coroutines.CompletableDeferred

enum class DebugCloudflareScenario {
    DISCONNECTED,
    CONNECTED,
    OFFLINE_SAVED,
}

/** Deterministic in-process Cloudflare provider used only by debug instrumentation hosts. */
object DebugCloudflareGatewayController {
    @Volatile
    var scenario: DebugCloudflareScenario = DebugCloudflareScenario.DISCONNECTED
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
    fun configure(scenario: DebugCloudflareScenario, blockConnect: Boolean = false) {
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

    internal fun restored(): CloudflareRestoreUi {
        restoreCalls += 1
        return when (scenario) {
            DebugCloudflareScenario.DISCONNECTED -> CloudflareRestoreUi.NotConnected
            DebugCloudflareScenario.CONNECTED -> CloudflareRestoreUi.Available(
                dashboard().copy(cacheState = CloudflareCacheState.CACHED_FRESH),
            )
            DebugCloudflareScenario.OFFLINE_SAVED -> CloudflareRestoreUi.Available(
                dashboard().copy(
                    cacheState = CloudflareCacheState.CACHED_STALE,
                    accountsComplete = false,
                    warnings = listOf("Offline debug cache; refresh online for current Cloudflare data."),
                ),
            )
        }
    }

    internal suspend fun connected(): CloudflareDashboardUi {
        connectCalls += 1
        connectStarted.complete(Unit)
        connectRelease.await()
        scenario = DebugCloudflareScenario.CONNECTED
        return dashboard()
    }

    internal fun refreshed(accountId: String?): Result<CloudflareDashboardUi> {
        refreshCalls += 1
        return when (scenario) {
            DebugCloudflareScenario.CONNECTED -> Result.success(dashboard(accountId ?: PRIMARY_ACCOUNT_ID))
            DebugCloudflareScenario.OFFLINE_SAVED -> Result.failure(
                CloudflareUiException("Cloudflare is offline in this deterministic scenario."),
            )
            DebugCloudflareScenario.DISCONNECTED -> Result.failure(
                CloudflareUiException("Connect a Cloudflare account first."),
            )
        }
    }

    internal fun disconnected() {
        disconnectCalls += 1
        scenario = DebugCloudflareScenario.DISCONNECTED
    }

    fun dashboard(selectedAccountId: String = PRIMARY_ACCOUNT_ID): CloudflareDashboardUi {
        val inventory = if (selectedAccountId == SECONDARY_ACCOUNT_ID) secondaryInventory else primaryInventory
        return CloudflareDashboardUi(
            profile = CloudflareProfileUi("debug-token", "Apoorv Cloudflare", "active"),
            accounts = accounts,
            loadedAccountCount = accounts.size,
            accountsComplete = true,
            accountsTruncatedForDisplay = false,
            selectedAccountId = selectedAccountId,
            inventory = inventory,
            warnings = emptyList(),
            fetchedAtMillis = 1_700_000_000_000,
            cacheState = CloudflareCacheState.LIVE,
        )
    }

    private val accounts = listOf(
        CloudflareAccountUi(PRIMARY_ACCOUNT_ID, "Apoorv Production", "standard"),
        CloudflareAccountUi(SECONDARY_ACCOUNT_ID, "Apoorv Labs", "standard"),
    )

    private val primaryInventory = CloudflareInventoryUi(
        accountId = PRIMARY_ACCOUNT_ID,
        zones = listOf(
            CloudflareZoneUi(
                id = "zone-apoorv",
                name = "apoorvdarshan.com",
                status = "active",
                type = "full",
                paused = false,
                accountName = "Apoorv Production",
                planName = "Free Website",
            ),
            CloudflareZoneUi(
                id = "zone-verceltics",
                name = "verceltics.app",
                status = "active",
                type = "full",
                paused = false,
                accountName = "Apoorv Production",
                planName = "Pro",
            ),
        ),
        pagesProjects = listOf(
            CloudflarePagesProjectUi(
                id = "pages-docs",
                name = "verceltics-docs",
                subdomain = "verceltics-docs.pages.dev",
                domains = listOf("docs.verceltics.app"),
                productionBranch = "main",
                latestDeploymentStatus = "success",
            ),
        ),
        workers = listOf(
            CloudflareWorkerUi(
                id = "analytics-proxy",
                modifiedOn = "2026-08-26T15:04:05Z",
                compatibilityDate = "2026-08-01",
                handlers = listOf("fetch"),
                hasAssets = false,
                hasModules = true,
            ),
        ),
        loadedZoneCount = 2,
        loadedPagesProjectCount = 1,
        loadedWorkerCount = 1,
        zonesComplete = true,
        pagesComplete = true,
        workersComplete = true,
        zonesTruncatedForDisplay = false,
        pagesTruncatedForDisplay = false,
        workersTruncatedForDisplay = false,
        warnings = emptyList(),
    )

    private val secondaryInventory = CloudflareInventoryUi(
        accountId = SECONDARY_ACCOUNT_ID,
        zones = listOf(
            CloudflareZoneUi(
                id = "zone-labs",
                name = "labs.example",
                status = "active",
                type = "full",
                paused = false,
                accountName = "Apoorv Labs",
                planName = "Free Website",
            ),
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
    )

    private const val PRIMARY_ACCOUNT_ID = "cf-account-production"
    private const val SECONDARY_ACCOUNT_ID = "cf-account-labs"
}

class DebugCloudflareUiGateway : CloudflareUiGateway {
    override suspend fun restore(): Result<CloudflareRestoreUi> =
        Result.success(DebugCloudflareGatewayController.restored())

    override suspend fun connect(credential: CloudflareCredential): Result<CloudflareDashboardUi> {
        val secret: SecretValue = when (credential) {
            is CloudflareCredential.ApiToken -> credential.token
            is CloudflareCredential.GlobalApiKey -> credential.key
        }
        secret.use { require(it.isNotBlank()) }
        return Result.success(DebugCloudflareGatewayController.connected())
    }

    override suspend fun refresh(preferredAccountId: String?): Result<CloudflareDashboardUi> =
        DebugCloudflareGatewayController.refreshed(preferredAccountId)

    override suspend fun disconnect(): Result<Unit> = Result.success(
        DebugCloudflareGatewayController.disconnected(),
    )

    /** One deterministic saved login whenever the debug scenario is connected. */
    override suspend fun savedLogins(): Result<List<com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi>> = Result.success(
        if (DebugCloudflareGatewayController.scenario == DebugCloudflareScenario.DISCONNECTED) {
            emptyList()
        } else {
            listOf(com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi("debug-login", "Debug Cloudflare login", "Scoped API token", isActive = true))
        },
    )
}
