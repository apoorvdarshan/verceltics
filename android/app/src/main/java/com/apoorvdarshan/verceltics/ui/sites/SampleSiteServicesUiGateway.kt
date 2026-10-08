package com.apoorvdarshan.verceltics.ui.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.jsonObjectOf
import com.apoorvdarshan.verceltics.data.sites.SiteDetailField
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSection
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeries
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeriesPoint
import com.apoorvdarshan.verceltics.data.sites.SiteDetailTable
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import java.time.LocalDate

/**
 * Offline fixtures for sample-data preview mode. Every account, site and number is fictional;
 * this gateway never touches provider APIs or saved credentials.
 */
object SampleSiteServicesUiGateway : SiteServicesUiGateway {
    override val googleOAuthReadiness: SiteGoogleOAuthReadinessUi = SiteGoogleOAuthReadinessUi.Ready

    /** The active fictional account per provider (Plausible has two sites to demo switching). */
    private val activeAccounts = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun restore(): Result<SiteServicesRestoreUi> = Result.success(
        SiteServicesRestoreUi(
            services = SiteServiceProviderIds.associateWith { SiteServiceRestoreUi.Available(dashboard(it)) },
            accounts = SiteServiceProviderIds.associateWith(::sampleAccounts),
        ),
    )

    override suspend fun accounts(providerId: String): Result<SiteAccountsUi> = Result.success(sampleAccounts(providerId))

    override suspend fun switchAccount(providerId: String, accountId: String): Result<SiteServiceRestoreUi> {
        if (sampleAccounts(providerId).accounts.none { it.id == accountId }) return unavailable(providerId)
        activeAccounts[providerId] = accountId
        return Result.success(SiteServiceRestoreUi.Available(dashboard(providerId)))
    }

    private fun sampleAccounts(providerId: String): SiteAccountsUi {
        val options = if (providerId == "plausible") {
            listOf(
                SiteAccountOptionUi(PLAUSIBLE_STUDIO, "studio.example"),
                SiteAccountOptionUi(PLAUSIBLE_DOCS, "docs.studio.example"),
            )
        } else {
            val sample = dashboard(providerId)
            listOf(SiteAccountOptionUi(checkNotNull(sample.accountId), sample.accountName, sample.accountDetail))
        }
        return SiteAccountsUi(options, activeAccounts[providerId] ?: options.first().id)
    }

    override suspend fun connect(
        providerId: String,
        input: SiteServiceConnectionInputUi,
    ): Result<SiteServiceDashboardUi> = unavailable(providerId)

    override suspend fun connectGoogle(providerId: String): Result<SiteServiceDashboardUi> = unavailable(providerId)

    override suspend fun refresh(providerId: String): Result<SiteServiceDashboardUi> = Result.success(dashboard(providerId))

    override suspend fun loadDetail(
        request: SiteServiceDetailRequestUi,
        forceRefresh: Boolean,
        onPartial: suspend (SiteServiceDetailUi) -> Unit,
    ): Result<SiteServiceDetailUi> {
        val dashboard = dashboard(request.providerId)
        val resource = dashboard.resources.firstOrNull { it.id == request.resourceId } ?: dashboard.resources.first()
        return Result.success(detail(request, resource))
    }

    override suspend fun disconnect(providerId: String): Result<Unit> = Result.success(Unit)

    private fun <T> unavailable(providerId: String): Result<T> {
        val name = siteCatalogName(providerId)
        return Result.failure(SiteServicesUiException("Exit sample data to connect $name."))
    }

    private fun siteCatalogName(providerId: String) =
        com.apoorvdarshan.verceltics.domain.IntegrationCatalog.provider(providerId)?.displayName ?: "this service"

    private fun count(key: String, label: String, value: Double) = SiteMetricUi(key, label, value, SiteMetricUnit.COUNT)

    private fun metric(key: String, label: String, value: Double, unit: SiteMetricUnit) = SiteMetricUi(key, label, value, unit)

    fun dashboard(providerId: String): SiteServiceDashboardUi {
        val now = System.currentTimeMillis()
        val (name, detail, status, resources, metrics) = when (providerId) {
            "googleAnalytics" -> Quintuple(
                "Google Analytics · 2 properties", "apoorv@example.com", "Connected",
                listOf(
                    resource(
                        "properties/100200300", "Studio website", "https://studio.example", "Reporting",
                        metric("ga4.activeUsers", "Active Users", 18_420.0, SiteMetricUnit.COUNT),
                        metric("ga4.sessions", "Sessions", 26_910.0, SiteMetricUnit.COUNT),
                        metadata = mapOf("propertyID" to "100200300"),
                    ),
                    resource(
                        "properties/100200301", "Docs portal", "2 web streams · Studio", "Reporting · Property-wide",
                        metric("ga4.activeUsers", "Active Users", 6_210.0, SiteMetricUnit.COUNT),
                        metric("ga4.engagementRate", "Engagement Rate", 63.4, SiteMetricUnit.PERCENT),
                        metadata = mapOf("propertyID" to "100200301"),
                        url = null,
                    ),
                ),
                listOf(
                    count("ga4.properties", "Properties", 2.0),
                    count("ga4.activeUsers", "Active Users", 24_630.0),
                    count("ga4.sessions", "Sessions", 35_880.0),
                ),
            )
            "bingWebmaster" -> Quintuple(
                "Bing Webmaster · 2 sites", null, "Connected",
                listOf(
                    resource(
                        "https://studio.example/", "studio.example", "https://studio.example/", "Verified",
                        metric("bing.clicks", "Clicks · 30d", 1_284.0, SiteMetricUnit.COUNT),
                        metric("bing.impressions", "Impressions · 30d", 41_900.0, SiteMetricUnit.COUNT),
                    ),
                    resource(
                        "https://docs.studio.example/", "docs.studio.example", "https://docs.studio.example/", "Verified",
                        metric("bing.clicks", "Clicks · 30d", 312.0, SiteMetricUnit.COUNT),
                        metric("bing.impressions", "Impressions · 30d", 9_870.0, SiteMetricUnit.COUNT),
                    ),
                ),
                listOf(count("bing.sites", "Sites", 2.0), count("bing.verified", "Verified", 2.0), count("bing.clicks", "Clicks", 1_596.0)),
            )
            "clarity" -> Quintuple(
                "Studio website", null, "Live insights · 3d",
                listOf(
                    resource(
                        "https://studio.example", "Studio website", "https://studio.example", "Connected",
                        metric("clarity.traffic.totalsessioncount", "Total Session Count", 4_812.0, SiteMetricUnit.COUNT),
                        metric("clarity.scroll-depth.averagescrolldepth", "Average Scroll Depth", 58.2, SiteMetricUnit.PERCENT),
                    ),
                ),
                listOf(
                    count("clarity.traffic.totalsessioncount", "Total Session Count", 4_812.0),
                    metric("clarity.scroll-depth.averagescrolldepth", "Average Scroll Depth", 58.2, SiteMetricUnit.PERCENT),
                ),
            )
            "plausible" -> if (activeAccounts[providerId] == PLAUSIBLE_DOCS) Quintuple(
                "docs.studio.example", null, "Connected",
                listOf(
                    resource(
                        "https://docs.studio.example", "docs.studio.example", "30d", "Connected",
                        metric("plausible.visitors", "Visitors", 4_120.0, SiteMetricUnit.COUNT),
                        metric("plausible.pageviews", "Page Views", 15_206.0, SiteMetricUnit.COUNT),
                        url = "https://docs.studio.example",
                    ),
                ),
                listOf(
                    count("plausible.visitors", "Visitors", 4_120.0),
                    count("plausible.pageviews", "Page Views", 15_206.0),
                    metric("plausible.bounce_rate", "Bounce Rate", 36.0, SiteMetricUnit.PERCENT),
                ),
            ) else Quintuple(
                "studio.example", null, "Connected",
                listOf(
                    resource(
                        "https://studio.example", "studio.example", "30d", "Connected",
                        metric("plausible.visitors", "Visitors", 12_840.0, SiteMetricUnit.COUNT),
                        metric("plausible.pageviews", "Page Views", 31_402.0, SiteMetricUnit.COUNT),
                        url = "https://studio.example",
                    ),
                ),
                listOf(
                    count("plausible.visitors", "Visitors", 12_840.0),
                    count("plausible.pageviews", "Page Views", 31_402.0),
                    metric("plausible.bounce_rate", "Bounce Rate", 41.0, SiteMetricUnit.PERCENT),
                ),
            )
            "umami" -> Quintuple(
                "Umami Cloud", "studio-team", "Connected",
                listOf(
                    resource(
                        "8f6b2f8e-0000-4000-8000-000000000001", "Studio website", "studio.example", "Connected",
                        metric("umami.pageviews", "Page Views", 22_118.0, SiteMetricUnit.COUNT),
                        metric("umami.visitors", "Visitors", 9_402.0, SiteMetricUnit.COUNT),
                    ),
                    resource(
                        "8f6b2f8e-0000-4000-8000-000000000002", "Commerce store", "commerce.example", "Connected",
                        metric("umami.pageviews", "Page Views", 7_660.0, SiteMetricUnit.COUNT),
                        metric("umami.visitors", "Visitors", 3_105.0, SiteMetricUnit.COUNT),
                    ),
                ),
                listOf(count("umami.pageviews", "Page Views", 29_778.0), count("umami.visitors", "Visitors", 12_507.0)),
            )
            "uptimeRobot" -> Quintuple(
                "UptimeRobot · 3 monitors", null, "All operational",
                listOf(
                    resource(
                        "790001", "Studio homepage", "https://studio.example", "Up",
                        metric("uptimerobot.uptime.30d", "30d Uptime", 99.98, SiteMetricUnit.PERCENT),
                        metric("uptimerobot.response_time", "Response Time", 182.0, SiteMetricUnit.MILLISECONDS),
                    ),
                    resource(
                        "790002", "Docs", "https://docs.studio.example", "Up",
                        metric("uptimerobot.uptime.30d", "30d Uptime", 100.0, SiteMetricUnit.PERCENT),
                        metric("uptimerobot.response_time", "Response Time", 95.0, SiteMetricUnit.MILLISECONDS),
                    ),
                    resource(
                        "790003", "Commerce API", "https://api.commerce.example/health", "Up",
                        metric("uptimerobot.uptime.30d", "30d Uptime", 99.91, SiteMetricUnit.PERCENT),
                        metric("uptimerobot.response_time", "Response Time", 240.0, SiteMetricUnit.MILLISECONDS),
                    ),
                ),
                listOf(
                    count("uptimerobot.monitors", "Monitors", 3.0),
                    count("uptimerobot.up", "Up", 3.0),
                    count("uptimerobot.down", "Down", 0.0),
                ),
            )
            else -> Quintuple(
                "Better Stack · 2 monitors", null, "All operational",
                listOf(
                    resource(
                        "2450001", "Studio website", "https://studio.example", "Up",
                        metric("betterstack.check_frequency", "Check Frequency", 30.0, SiteMetricUnit.SECONDS),
                    ),
                    resource(
                        "2450002", "Edge API", "https://edge.studio.example/health", "Up",
                        metric("betterstack.check_frequency", "Check Frequency", 60.0, SiteMetricUnit.SECONDS),
                    ),
                ),
                listOf(count("betterstack.monitors", "Monitors", 2.0), count("betterstack.up", "Up", 2.0), count("betterstack.down", "Down", 0.0)),
            )
        }
        return SiteServiceDashboardUi(
            providerId = providerId,
            accountId = if (providerId == "plausible") activeAccounts[providerId] ?: PLAUSIBLE_STUDIO else "sample-${providerId.lowercase()}",
            accountName = name,
            accountDetail = detail,
            status = status,
            resources = resources,
            metrics = metrics,
            warnings = emptyList(),
            fetchedAtMillis = now,
            cacheState = SiteServiceCacheState.LIVE,
        )
    }

    private const val PLAUSIBLE_STUDIO = "sample-plausible-studio"
    private const val PLAUSIBLE_DOCS = "sample-plausible-docs"

    private data class Quintuple(
        val name: String,
        val detail: String?,
        val status: String,
        val resources: List<SiteResourceUi>,
        val metrics: List<SiteMetricUi>,
    )

    private fun resource(
        id: String,
        name: String,
        subtitle: String,
        status: String,
        vararg metrics: SiteMetricUi,
        metadata: Map<String, String> = emptyMap(),
        url: String? = subtitle.takeIf { it.startsWith("https://") },
    ) = SiteResourceUi(id, name, subtitle, url, status, System.currentTimeMillis(), metrics.toList(), metadata)

    private fun detail(request: SiteServiceDetailRequestUi, resource: SiteResourceUi): SiteServiceDetailUi {
        val today = LocalDate.now()
        val days = request.query.preset.days ?: 30
        val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
        val timeline = SiteDetailSeries(
            id = "${request.providerId}.timeline",
            title = if (request.providerId in setOf("uptimeRobot", "betterStack")) "Response time" else "Traffic over time",
            metricLabels = if (request.providerId in setOf("uptimeRobot", "betterStack")) {
                mapOf("responseTime" to "Response time (ms)")
            } else {
                mapOf("visitors" to "Visitors", "pageviews" to "Page views")
            },
            points = dates.mapIndexed { index, date ->
                val wave = 1 + (index * 37 % 23) / 23.0
                if (request.providerId in setOf("uptimeRobot", "betterStack")) {
                    SiteDetailSeriesPoint(date, mapOf("responseTime" to 140 + wave * 60))
                } else {
                    SiteDetailSeriesPoint(date, mapOf("visitors" to 380 * wave, "pageviews" to 910 * wave))
                }
            },
        )
        val overview = SiteDetailSection(
            id = "${request.providerId}.overview",
            title = "Overview · ${dates.first()} – ${dates.last()}",
            fields = resource.metrics.map { SiteDetailField(it.key.substringAfterLast('.'), it.label, ProviderJsonValue.Num.of(it.value)) } +
                SiteDetailField("status", "Status", ProviderJsonValue.Str(resource.status ?: "Connected")),
        )
        val rows = listOf("/", "/pricing", "/docs", "/blog/launch", "/contact").mapIndexed { index, path ->
            mapOf<String, ProviderJsonValue>(
                "path" to ProviderJsonValue.Str(path),
                "visitors" to ProviderJsonValue.Num.of(4_200 / (index + 1)),
                "pageviews" to ProviderJsonValue.Num.of(9_800 / (index + 1)),
            )
        }
        val table = SiteDetailTable("${request.providerId}.pages", "Pages", listOf("path", "visitors", "pageviews"), rows)
        return SiteServiceDetailUi(
            providerId = request.providerId,
            resourceId = resource.id,
            title = resource.name,
            sections = listOf(overview),
            series = listOf(timeline),
            tables = listOf(table),
            rawResponses = mapOf(
                "overview" to jsonObjectOf(
                    "resource" to resource.name,
                    "range" to mapOf("start" to dates.first(), "end" to dates.last()),
                    "sample" to true,
                ),
            ),
            warnings = emptyList(),
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }
}
