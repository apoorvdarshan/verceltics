package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.SecureProviderHttpsTransport
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Read-only API surface for all seven site services, with one adapter per provider. Mirrors iOS
 * `SiteIntegrationsAPI` (overview snapshots) and `SiteIntegrationDetailClient` (detail workspaces).
 */
class SiteServicesApi(private val context: SiteApiContext) {
    constructor(
        executor: Executor,
        transport: ProviderHttpsTransport = SecureProviderHttpsTransport(),
        sleep: suspend (Long) -> Unit = { delay(it) },
        nowMillis: () -> Long = System::currentTimeMillis,
        zone: () -> ZoneId = ZoneId::systemDefault,
    ) : this(SiteApiContext(SiteHttpClient(transport, executor, sleep), nowMillis, zone))

    private val googleAnalytics = GoogleAnalyticsSiteAdapter(context)
    private val bing = BingWebmasterSiteAdapter(context)
    private val clarity = ClaritySiteAdapter(context)
    private val plausible = PlausibleSiteAdapter(context)
    private val umami = UmamiSiteAdapter(context)
    private val uptimeRobot = UptimeRobotSiteAdapter(context)
    private val betterStack = BetterStackSiteAdapter(context)

    suspend fun snapshot(account: SiteServiceAccount): SiteSnapshot = when (account.provider) {
        SiteProvider.GOOGLE_ANALYTICS -> googleAnalytics.snapshot(account)
        SiteProvider.BING_WEBMASTER -> bing.snapshot(account)
        SiteProvider.CLARITY -> clarity.snapshot(account)
        SiteProvider.PLAUSIBLE -> plausible.snapshot(account)
        SiteProvider.UMAMI -> umami.snapshot(account)
        SiteProvider.UPTIME_ROBOT -> uptimeRobot.snapshot(account)
        SiteProvider.BETTER_STACK -> betterStack.snapshot(account)
    }

    /** Non-secret identity discovered while connecting (only Umami exposes `/me`). */
    suspend fun connectionMetadata(account: SiteServiceAccount): Map<String, String> =
        if (account.provider == SiteProvider.UMAMI) umami.connectionMetadata(account) else emptyMap()

    /** Fetches the first snapshot and identity together, then names the connection like iOS. */
    suspend fun validatedConnection(account: SiteServiceAccount): SiteValidatedConnection = coroutineScope {
        val metadata = async { connectionMetadata(account) }
        val snapshot = snapshot(account)
        SiteValidatedConnection(connectionName(account, snapshot), snapshot, metadata.await())
    }

    fun connectionName(account: SiteServiceAccount, snapshot: SiteSnapshot): String {
        val count = snapshot.resources.size
        return when (account.provider) {
            SiteProvider.GOOGLE_ANALYTICS -> "Google Analytics · $count ${if (count == 1) "property" else "properties"}"
            SiteProvider.BING_WEBMASTER -> "Bing Webmaster · $count site${if (count == 1) "" else "s"}"
            SiteProvider.CLARITY -> account.metadata["projectName"].nonEmpty() ?: "Microsoft Clarity"
            SiteProvider.PLAUSIBLE -> account.requiredMetadata("siteID", "Plausible site ID")
            SiteProvider.UMAMI -> {
                val host = UmamiSiteAdapter.apiBaseUrl(account.metadata).host
                if (host == "api.umami.is") "Umami Cloud" else "Umami · ${host ?: "Self-hosted"}"
            }
            SiteProvider.UPTIME_ROBOT -> "UptimeRobot · $count monitor${if (count == 1) "" else "s"}"
            SiteProvider.BETTER_STACK -> "Better Stack · $count monitor${if (count == 1) "" else "s"}"
        }
    }

    /**
     * Loads a detail workspace and applies the shared device budget. Google Analytics reports an
     * early partial payload (overview + timeline) through [onPartial] before the breakdowns finish.
     */
    suspend fun detail(
        request: SiteDetailRequest,
        onPartial: (suspend (SiteDetailPayload) -> Unit)? = null,
    ): SiteDetailPayload {
        val payload = when (request) {
            is SiteDetailRequest.GoogleAnalytics -> googleAnalytics.detail(request, onPartial)
            is SiteDetailRequest.BingWebmaster -> bing.detail(request)
            is SiteDetailRequest.Clarity -> clarity.detail(request)
            is SiteDetailRequest.Plausible -> plausible.detail(request)
            is SiteDetailRequest.Umami -> umami.detail(request)
            is SiteDetailRequest.UptimeRobot -> uptimeRobot.detail(request)
            is SiteDetailRequest.BetterStack -> betterStack.detail(request)
        }
        return SiteDetailSupport.boundedForDevice(payload)
    }
}
