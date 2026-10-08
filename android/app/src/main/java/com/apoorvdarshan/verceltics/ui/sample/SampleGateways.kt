package com.apoorvdarshan.verceltics.ui.sample

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.ui.*
import com.apoorvdarshan.verceltics.ui.cloudflare.*
import com.apoorvdarshan.verceltics.ui.netlify.*
import com.apoorvdarshan.verceltics.ui.pagespeed.*
import com.apoorvdarshan.verceltics.ui.searchconsole.*
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountOptionUi
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi
import java.time.LocalDate

/** Offline fixtures only. These gateways never access provider APIs or saved credentials. */
object SampleVercelGateway : VercelUiGateway {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private val team = VercelProjectScopeUi("Studio", "studio-sample", isTeam = true)
    private val personal = VercelProjectScopeUi("Personal", null, isTeam = false)
    private val sampleAccounts = listOf(
        VercelAccountUi("Apoorv · Sample workspace", "apoorv@example.com", username = "apoorv-sample", id = "sample-apoorv"),
        VercelAccountUi("Studio · Sample team token", "studio@example.com", username = "studio-sample", id = "sample-studio"),
    )
    private val lock = Any()

    // An in-memory preview of switching and removal; restore() puts both sample accounts back.
    private var savedIds = sampleAccounts.map(VercelAccountUi::id)
    private var activeId: String? = sampleAccounts.first().id

    private fun accounts(): VercelAccountsUi = synchronized(lock) {
        VercelAccountsUi(sampleAccounts.filter { it.id in savedIds }, activeId)
    }

    private fun dashboard(): VercelDashboardUi {
        val now = System.currentTimeMillis()
        fun project(index: Int, name: String, framework: String?, domain: String?, repository: String?, scope: VercelProjectScopeUi, commit: String?, deployedAgo: Long?) =
            VercelProjectUi("sample-$index", name, framework, now - (index + 1) * HOUR, teamId = if (scope.isTeam) "team_sample" else null, primaryDomain = domain,
                repository = repository, scope = scope, lastDeployment = deployedAgo?.let { VercelProjectDeploymentUi(commit, now - it) })
        val account = accounts().activeAccount ?: sampleAccounts.first()
        val teamProjects = listOf(
            project(0, "studio-web", "nextjs", "studio.example", "studio/web", team, "Ship the pricing page refresh", 12 * MINUTE),
            project(1, "commerce-store", "nextjs", "commerce.example", "studio/commerce", team, "Fix cart totals rounding", 3 * HOUR),
            project(2, "docs-site", "astro", "docs.studio.example", "studio/docs", team, "Document webhook retries", 26 * HOUR),
        )
        val projects = if (account.id == "sample-studio") {
            teamProjects + project(5, "studio-admin", "remix", "admin.studio.example", "studio/admin", team, "Add audit log export", 5 * HOUR)
        } else {
            teamProjects + listOf(
                project(3, "portfolio", "vite", "portfolio-sample.vercel.app", "apoorv/portfolio", personal, "Update case studies", 96 * HOUR),
                project(4, "api-gateway", null, null, null, personal, null, null),
            )
        }
        return VercelDashboardUi(account, projects)
    }

    override suspend fun restore(): Result<VercelRestoreUi> {
        synchronized(lock) {
            savedIds = sampleAccounts.map(VercelAccountUi::id)
            activeId = sampleAccounts.first().id
        }
        return Result.success(VercelRestoreUi.Available(dashboard(), accounts()))
    }
    override suspend fun connect(personalToken: String) = Result.success(dashboard())
    override suspend fun refresh() = Result.success(dashboard())
    override suspend fun loadAccounts() = Result.success(accounts())
    override suspend fun refreshAccountProfiles() = Result.success(accounts())
    override suspend fun switchAccount(accountId: String): Result<VercelAccountsUi> {
        synchronized(lock) { if (accountId in savedIds) activeId = accountId }
        return Result.success(accounts())
    }
    override suspend fun removeAccount(accountId: String): Result<VercelAccountsUi> {
        synchronized(lock) {
            savedIds = savedIds - accountId
            if (activeId == accountId) activeId = savedIds.firstOrNull()
        }
        return Result.success(accounts())
    }
    override suspend fun removeAllAccounts(): Result<Unit> {
        synchronized(lock) {
            savedIds = emptyList()
            activeId = null
        }
        return Result.success(Unit)
    }
    override suspend fun disconnect(): Result<Unit> = removeAccount(accounts().activeAccountId.orEmpty()).map { }
    override suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> {
        val now = System.currentTimeMillis()
        val deployments = listOf(
            Triple("READY", "Production", project.lastDeployment?.commitMessage ?: "Initial deployment"),
            Triple("BUILDING", "Preview", "Try the new navigation"),
            Triple("ERROR", "Preview", "Experiment with edge caching"),
            Triple("READY", "Production", "Tune image sizes"),
            Triple("CANCELED", "Preview", "Draft the changelog"),
        ).mapIndexed { i, (state, target, message) ->
            VercelDeploymentUi("${project.id}-dpl-$i", "dpl_sample$i", project.name, "${project.name}-git-$i-sample.vercel.app",
                "https://vercel.com/${project.scope?.slug ?: "apoorv-sample"}/${project.name}", state, target, now - (i * 7 + 1) * HOUR,
                message, if (target == "Production") "main" else "feature-$i", "9f3c2a1b7e6d5c4b3a29180716f5e4d3c2b1a0f$i", project.repository, "apoorv-sample")
        }
        val domains = listOfNotNull(project.primaryDomain, "${project.name}-sample.vercel.app").distinct()
        return Result.success(VercelProjectContextUi(project, domains, deployments))
    }
    override suspend fun loadDeploymentEvents(project: VercelProjectUi, deployment: VercelDeploymentUi): Result<List<VercelDeploymentEventUi>> {
        val start = (deployment.createdAtMillis ?: System.currentTimeMillis()) - 2 * MINUTE
        val lines = listOf("command" to "Running \"npm run build\"", "stdout" to "Creating an optimized production build...", "stdout" to "Compiled successfully",
            "stdout" to "Collecting page data", "stdout" to "Generating static pages (24/24)", "stdout" to "Build Completed in /vercel/output [41s]",
            if (deployment.state == "ERROR") "stderr" to "Error: Command \"npm run build\" exited with 1" else "ready" to "Deployment ready")
        return Result.success(lines.mapIndexed { i, (type, text) -> VercelDeploymentEventUi("$i-${deployment.id}", type, start + i * 7_000L, text, null) }.reversed())
    }
    override suspend fun loadProjectAnalytics(project: VercelProjectUi, range: VercelAnalyticsRange, environment: VercelAnalyticsEnvironment): Result<VercelAnalyticsLoadUi> {
        val rangeDays = (range.durationMillis / 86_400_000L).toInt()
        val days = if (rangeDays == 1) 24 else rangeDays.coerceAtMost(31)
        val scale = if (environment == VercelAnalyticsEnvironment.PREVIEW) 0.12 else 1.0
        val points = (0 until days).map { i ->
            val views = ((2_100 + (i * 173 % 1_700)) * scale * rangeDays / days).toLong()
            VercelAnalyticsPointUi(java.time.Instant.ofEpochMilli(System.currentTimeMillis() - range.durationMillis + i * range.durationMillis / days).toString(), views, views * 68 / 100, (30 + i * 7 % 15).toDouble())
        }
        fun breakdown(vararg names: String): List<VercelAnalyticsBreakdownUi> {
            val totalWeight = names.indices.sumOf { 1.0 / (it + 1) }
            return names.mapIndexed { i, name ->
                val fraction = 1.0 / (i + 1) / totalWeight
                VercelAnalyticsBreakdownUi(name, (points.sumOf { it.pageViews } * fraction).toLong(), (points.sumOf { it.visitors } * fraction).toLong())
            }
        }
        return Result.success(VercelAnalyticsLoadUi.Available(VercelAnalyticsDataUi(
            overview = VercelAnalyticsOverviewUi(points.sumOf { it.pageViews }, points.sumOf { it.visitors }, 34.0),
            previousOverview = VercelAnalyticsOverviewUi(points.sumOf { it.pageViews } * 84 / 100, points.sumOf { it.visitors } * 88 / 100, 38.0),
            timeseries = points,
            pages = breakdown("/", "/pricing", "/docs", "/blog", "/contact"),
            referrers = breakdown("google.com", "Direct", "github.com", "x.com"),
            countries = breakdown("IN", "US", "GB", "DE"),
            devices = breakdown("mobile", "desktop", "tablet"),
            browsers = breakdown("Chrome", "Safari", "Firefox"),
            operatingSystems = breakdown("Android", "iOS", "Windows", "macOS"),
            utmSources = breakdown("newsletter", "social", "launch"),
            routes = breakdown("/", "/blog/[slug]"),
            hostnames = breakdown("studio.example", "www.studio.example"),
            events = breakdown("signup", "checkout", "download"),
        )))
    }
}

object SampleCloudflareGateway : CloudflareUiGateway {
    private fun dashboard(preferredAccountId: String? = null): CloudflareDashboardUi {
        val accounts = listOf(CloudflareAccountUi("sample-studio", "Studio workspace", "standard"), CloudflareAccountUi("sample-personal", "Personal projects", "standard"))
        val selected = accounts.firstOrNull { it.id == preferredAccountId } ?: accounts.first()
        val zones = listOf("studio.example", "commerce.example", "docs.example").mapIndexed { i, name ->
            CloudflareZoneUi("sample-zone-$i", name, "active", "full", false, selected.name, if (i == 0) "Pro" else "Free")
        }
        val pages = listOf("studio-docs", "design-system", "landing-page").mapIndexed { i, name ->
            CloudflarePagesProjectUi("sample-page-$i", name, "$name.pages.example", listOf("${name}.example"), "main", "success")
        }
        val workers = listOf("edge-api", "image-proxy", "scheduled-backup").map { name ->
            CloudflareWorkerUi(name, "2026-09-29T12:30:00Z", "2026-09-01", if (name == "scheduled-backup") listOf("scheduled") else listOf("fetch"), false, true)
        }
        return CloudflareDashboardUi(
            CloudflareProfileUi("sample-profile", "Apoorv · Sample account", "active"), accounts, accounts.size, true, false,
            selected.id, CloudflareInventoryUi(selected.id, zones, pages, workers, zones.size, pages.size, workers.size, true, true, true, false, false, false, emptyList()),
            emptyList(), System.currentTimeMillis(), CloudflareCacheState.LIVE,
        )
    }
    override suspend fun restore() = Result.success<CloudflareRestoreUi>(CloudflareRestoreUi.Available(dashboard()))
    override suspend fun connect(credential: com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential) = Result.success(dashboard())
    override suspend fun refresh(preferredAccountId: String?) = Result.success(dashboard(preferredAccountId))
    override suspend fun disconnect() = Result.success(Unit)
}

object SampleSearchConsoleGateway : SearchConsoleUiGateway {
    override val oauthReadiness = SearchConsoleOAuthReadinessUi.Ready
    private val properties = listOf(
        SearchConsolePropertyUi("sc-domain:studio.example", "studio.example", "siteOwner"),
        SearchConsolePropertyUi("https://docs.studio.example/", "docs.studio.example", "siteFullUser"),
        SearchConsolePropertyUi("sc-domain:commerce.example", "commerce.example", "siteOwner"),
    )
    private fun dashboard() = SearchConsoleDashboardUi(SearchConsoleAccountUi("sample-google", "apoorv@example.com"), properties, properties.size, true, false, emptyList(), System.currentTimeMillis(), SearchConsoleCacheState.LIVE)
    override suspend fun restore() = Result.success<SearchConsoleRestoreUi>(SearchConsoleRestoreUi.Available(dashboard()))
    override suspend fun connect() = Result.success(dashboard())
    override suspend fun refresh() = Result.success(dashboard())
    override suspend fun disconnect() = Result.success(Unit)
    override suspend fun accounts() = Result.success(
        SiteAccountsUi(listOf(SiteAccountOptionUi("sample-google", "apoorv@example.com")), "sample-google"),
    )
    private fun performance(query: SearchConsolePerformanceQueryUi): SearchConsolePerformanceUi {
        val start = LocalDate.parse(query.startDate)
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.parse(query.endDate)).toInt() + 1
        val timeline = (0 until days).map { i ->
            val clicks = (140 + i * 37 % 160).toDouble()
            SearchConsoleTimelinePointUi(start.plusDays(i.toLong()).toString(), clicks, clicks * 22, 1.0 / 22, 8.2 - (i % 8) * 0.3)
        }
        val labels = when (query.dimensions.first()) {
            SearchConsoleDimensionUi.PAGE -> listOf("https://studio.example/", "https://studio.example/pricing", "https://studio.example/docs")
            SearchConsoleDimensionUi.COUNTRY -> listOf("ind", "usa", "gbr", "deu")
            SearchConsoleDimensionUi.DEVICE -> listOf("MOBILE", "DESKTOP", "TABLET")
            SearchConsoleDimensionUi.DATE, SearchConsoleDimensionUi.HOUR -> timeline.map { it.label }
            SearchConsoleDimensionUi.SEARCH_APPEARANCE -> listOf("RICH_RESULTS", "AMP_BLUE_LINK")
            else -> listOf("studio hosting", "deploy a website", "web analytics", "cloud hosting", "domain management", "studio documentation")
        }
        val rows = labels.mapIndexed { i, label -> SearchConsoleBreakdownRowUi(query.dimensions.map { dimension -> if (dimension == query.dimensions.first()) label else "Sample ${dimension.name.lowercase()}" }, 960.0 / (i + 1), 18_400.0 / (i + 1), 960.0 / 18_400, 3.4 + i) }
            .filter { row -> query.filters.all { filter ->
                val value = row.keys.getOrNull(query.dimensions.indexOf(filter.dimension)).orEmpty()
                when (filter.operator) {
                    SearchConsoleFilterOperatorUi.EQUALS -> value.equals(filter.expression, true)
                    SearchConsoleFilterOperatorUi.NOT_EQUALS -> !value.equals(filter.expression, true)
                    SearchConsoleFilterOperatorUi.NOT_CONTAINS -> !value.contains(filter.expression, true)
                    SearchConsoleFilterOperatorUi.INCLUDING_REGEX -> runCatching { Regex(filter.expression).containsMatchIn(value) }.getOrDefault(false)
                    SearchConsoleFilterOperatorUi.EXCLUDING_REGEX -> !runCatching { Regex(filter.expression).containsMatchIn(value) }.getOrDefault(false)
                    else -> value.contains(filter.expression, true)
                }
            } }
        // Sorting and paging happen on device, so the sample returns every row like Google does.
        return SearchConsolePerformanceUi(
            clicks = timeline.sumOf { it.clicks },
            impressions = timeline.sumOf { it.impressions },
            ctr = 1.0 / 22,
            position = 7.4,
            timeline = timeline,
            breakdownRows = rows,
            firstIncompleteDate = null,
            firstIncompleteHour = null,
            timelineIsHourly = query.dataState == SearchConsoleDataStateUi.HOURLY_ALL,
            timelineAggregationType = "byProperty",
            breakdownAggregationType = "byProperty",
        )
    }
    override suspend fun loadPropertySummaries(
        siteUrls: List<String>,
        onSummary: suspend (SearchConsolePropertySummaryUi) -> Unit,
    ): Result<Unit> {
        siteUrls.forEachIndexed { index, siteUrl ->
            onSummary(SearchConsolePropertySummaryUi(siteUrl, 4_820.0 / (index + 1), 96_400.0 / (index + 1), 0.05, 7.4 + index, 2, "Indexed", "PASS", "2026-09-29T08:30:00Z", isPartial = false))
        }
        return Result.success(Unit)
    }
    override suspend fun loadProperty(property: SearchConsolePropertyUi, performanceQuery: SearchConsolePerformanceQueryUi) = Result.success(SearchConsolePropertyWorkspaceUi(
        property, SearchConsoleResourceUi.Available(performance(performanceQuery)), SearchConsoleResourceUi.Available(listOf(
            SearchConsoleSitemapUi("https://studio.example/sitemap.xml", "2026-09-28T10:00:00Z", false, false, "sitemap", "2026-09-29T08:00:00Z", 0, 0, listOf(SearchConsoleSitemapContentUi("web", 128, 124))),
            SearchConsoleSitemapUi("https://studio.example/blog-sitemap.xml", "2026-09-27T10:00:00Z", false, false, "sitemap", "2026-09-29T08:00:00Z", 1, 0, listOf(SearchConsoleSitemapContentUi("web", 42, 40))),
        ))))
    override suspend fun loadPerformance(siteUrl: String, query: SearchConsolePerformanceQueryUi) = Result.success<SearchConsoleResourceUi<SearchConsolePerformanceUi>>(SearchConsoleResourceUi.Available(performance(query)))
    override suspend fun inspect(siteUrl: String, inspectionUrl: String) = Result.success(SearchConsoleInspectionUi(
        null, "PASS", "Submitted and indexed", "INDEXING_ALLOWED", "ALLOWED", "SUCCESSFUL", "2026-09-29T08:30:00Z", inspectionUrl, inspectionUrl, "MOBILE", listOf("https://studio.example/sitemap.xml"), listOf("https://studio.example/"), null, "PASS", "PASS", emptyList(),
    ))
}

object SamplePageSpeedGateway : PageSpeedUiGateway {
    private fun dashboard() = PageSpeedDashboardUi(
        "https://studio.example/", "Studio website", "Good",
        buildList {
            listOf("mobile" to 94.0, "desktop" to 99.0).forEach { (strategy, score) ->
                listOf("performance" to score, "accessibility" to 100.0, "best-practices" to 96.0, "seo" to 100.0).forEach { (key, value) ->
                    add(PageSpeedMetricUi("pagespeed.$strategy.$key", key.replace('-', ' ').replaceFirstChar { it.uppercase() }, value, PageSpeedMetricUnit.SCORE, null))
                }
                listOf("largest-contentful-paint" to 1_420.0, "first-contentful-paint" to 780.0, "speed-index" to 1_260.0, "total-blocking-time" to 60.0).forEach { (key, value) ->
                    add(PageSpeedMetricUi("pagespeed.$strategy.$key", key.replace('-', ' '), value, PageSpeedMetricUnit.MILLISECONDS, null))
                }
                add(PageSpeedMetricUi("pagespeed.$strategy.cumulative-layout-shift", "Cumulative layout shift", 0.02, PageSpeedMetricUnit.RATIO, null))
            }
            add(PageSpeedMetricUi("crux.largest_contentful_paint", "LCP (Page field p75)", 1_800.0, PageSpeedMetricUnit.MILLISECONDS, null))
            add(PageSpeedMetricUi("crux.interaction_to_next_paint", "INP (Page field p75)", 120.0, PageSpeedMetricUnit.MILLISECONDS, null))
            add(PageSpeedMetricUi("crux.cumulative_layout_shift", "CLS (Page field p75)", 0.03, PageSpeedMetricUnit.RATIO, null))
        }, System.currentTimeMillis(), PageSpeedSourcesUi(PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE, PageSpeedSourceUiState.AVAILABLE), emptyList(), PageSpeedCacheState.LIVE,
        accountId = "sample-pagespeed",
    )
    override suspend fun restore() = Result.success<PageSpeedRestoreUi>(PageSpeedRestoreUi.Available(dashboard()))
    override suspend fun connect(apiKey: SecretValue, siteUrl: String) = Result.success(dashboard())
    override suspend fun refresh() = Result.success(dashboard())
    override suspend fun disconnect() = Result.success(Unit)
    override suspend fun accounts() = Result.success(
        SiteAccountsUi(listOf(SiteAccountOptionUi("sample-pagespeed", "studio.example", "Studio website")), "sample-pagespeed"),
    )
}

/** Keep unrequested providers disconnected in sample mode, without using their real gateway. */
object SampleNetlifyGateway : NetlifyUiGateway {
    override suspend fun restore() = Result.success<NetlifyRestoreUi>(NetlifyRestoreUi.NotConnected)
    override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> = unavailable()
    override suspend fun refresh(): Result<NetlifyDashboardUi> = unavailable()
    override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> = unavailable()
    override suspend fun disconnect() = Result.success(Unit)
    private fun <T> unavailable(): Result<T> = Result.failure(NetlifyUiException("Exit sample data to connect Netlify."))
}
