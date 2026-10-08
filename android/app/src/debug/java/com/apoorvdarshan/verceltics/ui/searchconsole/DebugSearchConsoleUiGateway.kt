package com.apoorvdarshan.verceltics.ui.searchconsole

import kotlinx.coroutines.CompletableDeferred

enum class DebugSearchConsoleScenario {
    CONFIGURATION_NEEDED,
    DISCONNECTED,
    CONNECTED,
    OFFLINE_SAVED,
}

/** Deterministic Search Console backend used only by debug instrumentation hosts. */
object DebugSearchConsoleGatewayController {
    @Volatile
    var scenario: DebugSearchConsoleScenario = DebugSearchConsoleScenario.DISCONNECTED
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
    var inspectionCalls: Int = 0
        private set

    @Volatile
    var disconnectCalls: Int = 0
        private set

    @Volatile
    private var connectStarted = CompletableDeferred<Unit>()

    @Volatile
    private var connectRelease = CompletableDeferred<Unit>().apply { complete(Unit) }

    @Synchronized
    fun configure(scenario: DebugSearchConsoleScenario, blockConnect: Boolean = false) {
        this.scenario = scenario
        restoreCalls = 0
        connectCalls = 0
        refreshCalls = 0
        inspectionCalls = 0
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

    internal fun restored(): SearchConsoleRestoreUi {
        restoreCalls += 1
        return when (scenario) {
            DebugSearchConsoleScenario.CONFIGURATION_NEEDED,
            DebugSearchConsoleScenario.DISCONNECTED,
            -> SearchConsoleRestoreUi.NotConnected
            DebugSearchConsoleScenario.CONNECTED -> SearchConsoleRestoreUi.Available(
                dashboard().copy(cacheState = SearchConsoleCacheState.CACHED_FRESH),
            )
            DebugSearchConsoleScenario.OFFLINE_SAVED -> SearchConsoleRestoreUi.Available(
                dashboard().copy(
                    cacheState = SearchConsoleCacheState.CACHED_STALE,
                    providerInventoryComplete = false,
                    warnings = listOf("Offline debug cache; refresh online for current properties."),
                ),
            )
        }
    }

    internal suspend fun connected(): SearchConsoleDashboardUi {
        connectCalls += 1
        connectStarted.complete(Unit)
        connectRelease.await()
        scenario = DebugSearchConsoleScenario.CONNECTED
        return dashboard()
    }

    internal fun refreshed(): Result<SearchConsoleDashboardUi> {
        refreshCalls += 1
        return when (scenario) {
            DebugSearchConsoleScenario.CONNECTED -> Result.success(dashboard())
            DebugSearchConsoleScenario.OFFLINE_SAVED -> Result.failure(
                SearchConsoleUiException("Google is offline in this deterministic scenario."),
            )
            else -> Result.failure(SearchConsoleUiException("Connect Search Console first."))
        }
    }

    internal fun disconnected() {
        disconnectCalls += 1
        scenario = DebugSearchConsoleScenario.DISCONNECTED
    }

    internal fun inspected(): SearchConsoleInspectionUi {
        inspectionCalls += 1
        return inspection()
    }

    fun dashboard(): SearchConsoleDashboardUi = SearchConsoleDashboardUi(
        account = SearchConsoleAccountUi("debug-google-subject", "apoorvdarshan@gmail.com"),
        properties = properties,
        loadedPropertyCount = properties.size,
        providerInventoryComplete = true,
        inventoryTruncatedForDisplay = false,
        warnings = emptyList(),
        fetchedAtMillis = 1_700_000_000_000,
        cacheState = SearchConsoleCacheState.LIVE,
    )

    fun workspace(property: SearchConsolePropertyUi = properties.first()) =
        SearchConsolePropertyWorkspaceUi(
            property = property,
            performance = SearchConsoleResourceUi.Available(
                SearchConsolePerformanceUi(
                    clicks = 184.0,
                    impressions = 8_420.0,
                    ctr = 0.02185,
                    position = 11.4,
                    timeline = listOf(
                        SearchConsoleTimelinePointUi("2026-08-23", 28.0, 1_120.0, 0.025, 10.8),
                        SearchConsoleTimelinePointUi("2026-08-24", 34.0, 1_340.0, 0.025, 10.3),
                        SearchConsoleTimelinePointUi("2026-08-25", 31.0, 1_500.0, 0.020, 11.7),
                        SearchConsoleTimelinePointUi("2026-08-26", 42.0, 1_780.0, 0.024, 11.1),
                        SearchConsoleTimelinePointUi("2026-08-27", 49.0, 2_680.0, 0.018, 12.4),
                    ),
                    breakdownRows = listOf(
                        SearchConsoleBreakdownRowUi(
                            keys = listOf("verceltics"),
                            clicks = 74.0,
                            impressions = 2_980.0,
                            ctr = 0.0248,
                            position = 8.6,
                        ),
                        SearchConsoleBreakdownRowUi(
                            keys = listOf("swiftui analytics"),
                            clicks = 43.0,
                            impressions = 2_120.0,
                            ctr = 0.0203,
                            position = 12.1,
                        ),
                    ),
                    firstIncompleteDate = null,
                    firstIncompleteHour = null,
                    timelineAggregationType = "byProperty",
                    breakdownAggregationType = "byProperty",
                ),
            ),
            sitemaps = SearchConsoleResourceUi.Available(
                listOf(
                    SearchConsoleSitemapUi(
                        path = "https://apoorvdarshan.com/sitemap.xml",
                        lastSubmitted = "2026-08-26T13:30:00Z",
                        isPending = false,
                        isIndex = true,
                        type = "sitemap",
                        lastDownloaded = "2026-08-27T06:40:00Z",
                        warnings = 0,
                        errors = 0,
                        contents = listOf(SearchConsoleSitemapContentUi("web", 128, 121)),
                    ),
                ),
            ),
        )

    fun summary(siteUrl: String) = SearchConsolePropertySummaryUi(
        siteUrl = siteUrl,
        clicks = 184.0,
        impressions = 8_420.0,
        ctr = 0.02185,
        position = 11.4,
        sitemapCount = 1,
        indexStatus = "Indexed",
        indexVerdict = "PASS",
        lastCrawlTime = "2026-08-26T09:12:00Z",
        isPartial = false,
    )

    fun inspection() = SearchConsoleInspectionUi(
        inspectionResultLink = "https://search.google.com/search-console/inspect",
        verdict = "PASS",
        coverageState = "Submitted and indexed",
        indexingState = "INDEXING_ALLOWED",
        robotsTxtState = "ALLOWED",
        pageFetchState = "SUCCESSFUL",
        lastCrawlTime = "2026-08-26T09:12:00Z",
        googleCanonical = "https://apoorvdarshan.com/",
        userCanonical = "https://apoorvdarshan.com/",
        crawledAs = "DESKTOP",
        sitemaps = listOf("https://apoorvdarshan.com/sitemap.xml"),
        referringUrls = emptyList(),
        ampVerdict = null,
        mobileVerdict = "PASS",
        richResultsVerdict = "PASS",
        issues = emptyList(),
        inspectedUrl = "https://apoorvdarshan.com/",
        richResultTypes = listOf(
            SearchConsoleRichResultTypeUi(
                type = "Breadcrumbs",
                items = listOf(SearchConsoleRichResultItemUi(name = "Unnamed item", issues = emptyList())),
            ),
        ),
    )

    private val properties = listOf(
        SearchConsolePropertyUi("sc-domain:apoorvdarshan.com", "apoorvdarshan.com", "Owner"),
        SearchConsolePropertyUi("https://verceltics.app/", "https://verceltics.app", "Full user"),
        SearchConsolePropertyUi("sc-domain:bsmash.app", "bsmash.app", "Owner"),
    )
}

class DebugSearchConsoleUiGateway : SearchConsoleUiGateway {
    override val oauthReadiness: SearchConsoleOAuthReadinessUi
        get() = if (DebugSearchConsoleGatewayController.scenario ==
            DebugSearchConsoleScenario.CONFIGURATION_NEEDED
        ) {
            SearchConsoleOAuthReadinessUi.ConfigurationNeeded(
                "Debug fixture intentionally has no OAuth client configuration.",
            )
        } else {
            SearchConsoleOAuthReadinessUi.Ready
        }

    override suspend fun restore(): Result<SearchConsoleRestoreUi> =
        Result.success(DebugSearchConsoleGatewayController.restored())

    override suspend fun connect(): Result<SearchConsoleDashboardUi> = runCatching {
        DebugSearchConsoleGatewayController.connected()
    }

    override suspend fun refresh(): Result<SearchConsoleDashboardUi> =
        DebugSearchConsoleGatewayController.refreshed()

    override suspend fun loadProperty(
        property: SearchConsolePropertyUi,
        performanceQuery: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsolePropertyWorkspaceUi> = Result.success(
        DebugSearchConsoleGatewayController.workspace(property),
    )

    override suspend fun loadPerformance(
        siteUrl: String,
        query: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsoleResourceUi<SearchConsolePerformanceUi>> = Result.success(
        DebugSearchConsoleGatewayController.workspace(
            SearchConsolePropertyUi(siteUrl, siteUrl, "Owner"),
        ).performance,
    )

    override suspend fun inspect(
        siteUrl: String,
        inspectionUrl: String,
    ): Result<SearchConsoleInspectionUi> =
        Result.success(DebugSearchConsoleGatewayController.inspected())

    override suspend fun loadPropertySummaries(
        siteUrls: List<String>,
        onSummary: suspend (SearchConsolePropertySummaryUi) -> Unit,
    ): Result<Unit> = runCatching {
        siteUrls.forEach { onSummary(DebugSearchConsoleGatewayController.summary(it)) }
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        DebugSearchConsoleGatewayController.disconnected()
    }
}
