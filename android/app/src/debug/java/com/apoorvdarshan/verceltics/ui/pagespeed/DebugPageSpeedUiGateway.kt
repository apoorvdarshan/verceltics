package com.apoorvdarshan.verceltics.ui.pagespeed

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.jsonObjectOf
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedAudit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCategoryScore
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxBin
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxHistory
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxHistoryBin
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxHistoryMetric
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxMetric
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxPeriod
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxRecord
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCruxUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReport
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReportField
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedStrategy
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedStrategyReport
import java.time.LocalDate

enum class DebugPageSpeedScenario {
    DISCONNECTED,
    CONNECTED,
    OFFLINE_SAVED,
}

/** Deterministic, in-process PageSpeed backend used only by the debug instrumentation host. */
object DebugPageSpeedGatewayController {
    @Volatile
    var scenario: DebugPageSpeedScenario = DebugPageSpeedScenario.DISCONNECTED
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

    @Synchronized
    fun configure(scenario: DebugPageSpeedScenario) {
        this.scenario = scenario
        restoreCalls = 0
        connectCalls = 0
        refreshCalls = 0
        disconnectCalls = 0
    }

    internal fun restored(): PageSpeedRestoreUi {
        restoreCalls += 1
        return when (scenario) {
            DebugPageSpeedScenario.DISCONNECTED -> PageSpeedRestoreUi.NotConnected
            DebugPageSpeedScenario.CONNECTED -> PageSpeedRestoreUi.Available(cachedDashboard)
            DebugPageSpeedScenario.OFFLINE_SAVED -> PageSpeedRestoreUi.Available(
                cachedDashboard.copy(cacheState = PageSpeedCacheState.CACHED_STALE),
            )
        }
    }

    internal fun connected(siteUrl: String): PageSpeedDashboardUi {
        connectCalls += 1
        scenario = DebugPageSpeedScenario.CONNECTED
        return liveDashboard.copy(
            siteUrl = siteUrl,
            siteName = siteUrl.substringAfter("://").substringBefore('/'),
            report = report(),
        )
    }

    /** Deterministic full report so instrumentation can exercise the Pro breakdown offline. */
    fun report(): PageSpeedReport {
        val periods = (0 until 40).map { index ->
            val end = LocalDate.of(2026, 8, 30).minusWeeks((39 - index).toLong())
            PageSpeedCruxPeriod(end.minusDays(27), end)
        }
        return PageSpeedReport(
            strategies = PageSpeedStrategy.entries.map { strategy ->
                PageSpeedStrategyReport(
                    strategy = strategy,
                    categories = listOf(
                        PageSpeedCategoryScore("performance", "Performance", if (strategy == PageSpeedStrategy.MOBILE) 0.96 else 0.92, listOf("largest-contentful-paint", "unused-javascript")),
                        PageSpeedCategoryScore("accessibility", "Accessibility", 1.0, listOf("color-contrast")),
                        PageSpeedCategoryScore("best-practices", "Best Practices", 0.96, emptyList()),
                        PageSpeedCategoryScore("seo", "SEO", 1.0, emptyList()),
                    ),
                    metadata = listOf(
                        PageSpeedReportField("finalUrl", "Final URL", "https://example.com/"),
                        PageSpeedReportField("lighthouseVersion", "Lighthouse version", "12.6.0"),
                        PageSpeedReportField("fetchTime", "Fetch time", "2026-08-30T10:15:00.000Z"),
                    ),
                    runWarnings = emptyList(),
                    audits = listOf(
                        PageSpeedAudit("largest-contentful-paint", "Largest Contentful Paint", "LCP marks when the largest text or image is painted.", 0.98, "numeric", "1.5 s", 1_480.0, "millisecond", null, emptyList(), null, null, listOf("performance")),
                        PageSpeedAudit("unused-javascript", "Reduce unused JavaScript", "Reduce unused JavaScript and defer loading scripts.", 0.45, "metricSavings", "Est savings of 120 KiB", 120.0, "byte", null, emptyList(), "opportunity", 3, listOf("performance")),
                        PageSpeedAudit("color-contrast", "Background and foreground colors have a sufficient contrast ratio", null, 1.0, "binary", null, null, null, null, emptyList(), "table", 0, listOf("accessibility")),
                    ),
                )
            },
            crux = PageSpeedCruxRecord(
                key = listOf(PageSpeedReportField("url", "Url", "https://example.com/")),
                firstDate = LocalDate.of(2026, 8, 3),
                lastDate = LocalDate.of(2026, 8, 30),
                metrics = listOf(
                    PageSpeedCruxMetric(
                        "largest_contentful_paint", "Largest Contentful Paint (LCP)", PageSpeedCruxUnit.MILLISECONDS,
                        listOf("p75" to 1_480.0),
                        listOf(PageSpeedCruxBin(0.0, 2_500.0, 0.86), PageSpeedCruxBin(2_500.0, 4_000.0, 0.09), PageSpeedCruxBin(4_000.0, null, 0.05)),
                        emptyList(),
                    ),
                    PageSpeedCruxMetric("form_factors", "Form factors", PageSpeedCruxUnit.MILLISECONDS, emptyList(), emptyList(), listOf("phone" to 0.62, "desktop" to 0.36, "tablet" to 0.02)),
                ),
            ),
            cruxHistory = PageSpeedCruxHistory(
                periods = periods,
                metrics = listOf(
                    PageSpeedCruxHistoryMetric(
                        "largest_contentful_paint", "Largest Contentful Paint (LCP)", PageSpeedCruxUnit.MILLISECONDS,
                        p75s = periods.indices.map { 1_400.0 + (it % 7) * 30 },
                        histogram = listOf(
                            PageSpeedCruxHistoryBin(0.0, 2_500.0, periods.map { 0.86 }),
                            PageSpeedCruxHistoryBin(2_500.0, 4_000.0, periods.map { 0.09 }),
                            PageSpeedCruxHistoryBin(4_000.0, null, periods.map { 0.05 }),
                        ),
                        fractions = emptyList(),
                    ),
                ),
            ),
            rawResponses = mapOf("crux.current" to jsonObjectOf("record" to mapOf("key" to mapOf("url" to "https://example.com/")))),
            warnings = emptyList(),
            fetchedAtMillis = 1_700_000_000_000,
        )
    }

    internal fun refreshed(): Result<PageSpeedDashboardUi> {
        refreshCalls += 1
        return when (scenario) {
            DebugPageSpeedScenario.CONNECTED -> Result.success(liveDashboard.copy(report = report()))
            DebugPageSpeedScenario.OFFLINE_SAVED -> Result.failure(
                PageSpeedUiException("The test provider is offline. The saved audit remains available."),
            )
            DebugPageSpeedScenario.DISCONNECTED -> Result.failure(
                PageSpeedUiException("Connect PageSpeed & CrUX first."),
            )
        }
    }

    internal fun disconnected() {
        disconnectCalls += 1
        scenario = DebugPageSpeedScenario.DISCONNECTED
    }

    private val liveDashboard = PageSpeedDashboardUi(
        siteUrl = "https://example.com",
        siteName = "example.com",
        status = "Good",
        metrics = listOf(
            PageSpeedMetricUi(
                key = "pagespeed.mobile.performance",
                label = "Mobile Performance",
                value = 96.0,
                unit = PageSpeedMetricUnit.SCORE,
                formattedValue = "96",
            ),
            PageSpeedMetricUi(
                key = "pagespeed.desktop.performance",
                label = "Desktop Performance",
                value = 92.0,
                unit = PageSpeedMetricUnit.SCORE,
                formattedValue = "92",
            ),
            PageSpeedMetricUi(
                key = "crux.largest_contentful_paint",
                label = "LCP (Page field p75)",
                value = 1_480.0,
                unit = PageSpeedMetricUnit.MILLISECONDS,
                formattedValue = "1.48 s",
            ),
        ),
        fetchedAtMillis = 1_700_000_000_000,
        sources = PageSpeedSourcesUi(
            mobile = PageSpeedSourceUiState.AVAILABLE,
            desktop = PageSpeedSourceUiState.AVAILABLE,
            crux = PageSpeedSourceUiState.AVAILABLE,
        ),
        warnings = emptyList(),
        cacheState = PageSpeedCacheState.LIVE,
    )

    private val cachedDashboard = liveDashboard.copy(cacheState = PageSpeedCacheState.CACHED_FRESH)
}

class DebugPageSpeedUiGateway : PageSpeedUiGateway {
    override suspend fun restore(): Result<PageSpeedRestoreUi> =
        Result.success(DebugPageSpeedGatewayController.restored())

    override suspend fun connect(
        apiKey: SecretValue,
        siteUrl: String,
    ): Result<PageSpeedDashboardUi> {
        apiKey.use { require(it.isNotBlank()) }
        return Result.success(DebugPageSpeedGatewayController.connected(siteUrl))
    }

    override suspend fun refresh(): Result<PageSpeedDashboardUi> =
        DebugPageSpeedGatewayController.refreshed()

    override suspend fun disconnect(): Result<Unit> = Result.success(
        DebugPageSpeedGatewayController.disconnected(),
    )
}
