package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingLinkContext
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingProviderApi

/**
 * Offline fixtures for the sample-data preview. Railway, Render and Fly.io appear connected with
 * fictional workspaces; the other providers stay disconnected. Nothing here touches a provider
 * API, saved credentials or the network, and write actions are simulated.
 */
object SampleHostingProviderUiGateway : HostingProviderUiGateway {
    private const val HOUR = 3_600_000L

    private class SampleResource(
        val id: String,
        val name: String,
        val subtitle: String?,
        val url: String?,
        val status: String,
        val region: String?,
        val kind: String,
        val ageHours: Long,
        val history: List<SampleDeployment>,
    )

    private class SampleDeployment(
        val title: String,
        val status: String,
        val ageHours: Long,
        val branch: String?,
        val message: String?,
    )

    private val accounts = mapOf(
        HostingProvider.RAILWAY to HostingAccountUi("sample-railway", "Apoorv · Sample workspace", "apoorv@example.com"),
        HostingProvider.RENDER to HostingAccountUi("sample-render", "Studio team", "team@studio.example"),
        HostingProvider.FLY to HostingAccountUi("personal", "Fly.io Personal", null),
    )

    private val resources: Map<HostingProvider, List<SampleResource>> = mapOf(
        HostingProvider.RAILWAY to listOf(
            SampleResource(
                "sample-railway-api", "studio-api", "GraphQL API and background workers", null, "Project", null,
                "Project", 2,
                listOf(
                    SampleDeployment("api · production", "SUCCESS", 2, "main", "Add rate limits to public endpoints"),
                    SampleDeployment("worker · production", "SUCCESS", 5, "main", "Retry failed webhook deliveries"),
                    SampleDeployment("api · staging", "BUILDING", 0, "feature/search", "Search index warm-up"),
                ),
            ),
            SampleResource(
                "sample-railway-queue", "worker-queue", "Redis-backed job queue", null, "Project", null, "Project", 26,
                listOf(SampleDeployment("queue · production", "SUCCESS", 26, "main", "Upgrade runtime")),
            ),
        ),
        HostingProvider.RENDER to listOf(
            SampleResource(
                "sample-render-web", "studio-web", "github.com/studio/web", "https://studio-web.example", "Active",
                "oregon", "Web Service", 1,
                listOf(
                    SampleDeployment("Ship pricing page refresh", "live", 1, null, "Ship pricing page refresh"),
                    SampleDeployment("Fix image cache headers", "deactivated", 20, null, "Fix image cache headers"),
                ),
            ),
            SampleResource(
                "sample-render-cron", "nightly-reports", "github.com/studio/reports", null, "Active", "frankfurt",
                "Cron Job", 12,
                listOf(SampleDeployment("Email weekly digest", "live", 12, null, "Email weekly digest")),
            ),
            SampleResource(
                "sample-render-preview", "studio-web-pr-142", "github.com/studio/web", null, "Suspended", "oregon",
                "Web Service", 72, emptyList(),
            ),
        ),
        HostingProvider.FLY to listOf(
            SampleResource(
                "sample-fly-edge", "edge-proxy", "3 Machines · 1 volumes", "https://edge-proxy.example", "Running",
                null, "App", 3,
                listOf(
                    SampleDeployment("edge-proxy-ams", "started", 3, "ams", "registry.fly.io/edge-proxy:v42"),
                    SampleDeployment("edge-proxy-sin", "started", 3, "sin", "registry.fly.io/edge-proxy:v42"),
                    SampleDeployment("edge-proxy-iad", "started", 3, "iad", "registry.fly.io/edge-proxy:v42"),
                ),
            ),
            SampleResource(
                "sample-fly-chat", "realtime-chat", "2 Machines · 0 volumes", "https://realtime-chat.example", "Degraded",
                null, "App", 8,
                listOf(
                    SampleDeployment("realtime-chat-1", "started", 8, "lhr", "registry.fly.io/realtime-chat:v7"),
                    SampleDeployment("realtime-chat-2", "failed", 8, "lhr", "registry.fly.io/realtime-chat:v7"),
                ),
            ),
        ),
    )

    override suspend fun restore(): Result<Map<String, HostingRestoreUi>> = Result.success(
        HostingProvider.entries.associate { provider ->
            provider.id to (dashboard(provider)?.let { HostingRestoreUi.Available(it) } ?: HostingRestoreUi.NotConnected)
        },
    )

    override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> =
        Result.failure(HostingUiException("Exit sample data to connect ${credentials.provider.displayName}."))

    override suspend fun refresh(providerId: String): Result<HostingDashboardUi> =
        HostingProvider.fromId(providerId)?.let(::dashboard)?.let { Result.success(it) }
            ?: Result.failure(HostingUiException("Exit sample data to connect this provider."))

    override suspend fun loadResource(providerId: String, resource: HostingResourceUi): Result<HostingResourceWorkspaceUi> {
        val provider = HostingProvider.fromId(providerId)
        val sample = resources[provider]?.firstOrNull { it.id == resource.id }
            ?: return Result.failure(HostingUiException("This sample resource is unavailable."))
        val now = System.currentTimeMillis()
        val deployments = sample.history.mapIndexed { index, deployment ->
            HostingDeploymentUi(
                id = "${sample.id}-deployment-$index",
                title = deployment.title,
                status = deployment.status,
                createdAtMillis = now - deployment.ageHours * HOUR,
                url = null,
                branch = deployment.branch,
                commitMessage = deployment.message,
            )
        }
        return Result.success(HostingResourceWorkspaceUi(providerId, sample.id, deployments, deployments.size))
    }

    override suspend fun performPrimaryAction(
        providerId: String,
        resource: HostingResourceUi,
        latestDeploymentId: String?,
    ): Result<String> {
        val provider = HostingProvider.fromId(providerId)
            ?: return Result.failure(HostingUiException("This sample resource is unavailable."))
        val label = provider.primaryActionLabel
            ?: return Result.failure(HostingUiException("${provider.displayName} has no safe one-tap action here."))
        return Result.success("$label simulated. Sample data never sends requests to ${provider.displayName}.")
    }

    override suspend fun disconnect(providerId: String): Result<Unit> = Result.success(Unit)

    private fun dashboard(provider: HostingProvider): HostingDashboardUi? {
        val account = accounts[provider] ?: return null
        val items = resources[provider].orEmpty()
        val now = System.currentTimeMillis()
        // Sample links open each provider's console home, never a fictional resource path.
        val consoleHome = HostingProviderApi.dashboardUrl(HostingLinkContext(provider))
        return HostingDashboardUi(
            providerId = provider.id,
            account = account,
            resources = items.map { sample ->
                HostingResourceUi(
                    id = sample.id,
                    name = sample.name,
                    subtitle = sample.subtitle,
                    url = sample.url,
                    status = sample.status,
                    region = sample.region,
                    kind = sample.kind,
                    updatedAtMillis = now - sample.ageHours * HOUR,
                    dashboardUrl = consoleHome,
                    metadata = if (provider == HostingProvider.FLY) mapOf("appName" to sample.name) else emptyMap(),
                )
            },
            loadedResourceCount = items.size,
            warnings = emptyList(),
            fetchedAtMillis = now,
            cacheState = HostingCacheState.LIVE,
            dashboardUrl = consoleHome,
        )
    }
}
