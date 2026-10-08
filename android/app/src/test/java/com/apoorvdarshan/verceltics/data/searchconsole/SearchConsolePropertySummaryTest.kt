package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.searchconsole.SearchConsolePropertySummaryUi
import com.apoorvdarshan.verceltics.ui.searchconsole.aggregateSearchConsoleSummaries
import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConsolePropertySummaryTest {
    @Test
    fun overviewLoadsTwentyEightDayByPropertyTotalsSitemapsAndIndexStatus() {
        val api = SummaryApi().apply {
            analytics = SearchConsoleAnalyticsResponse(
                rows = listOf(SearchConsoleAnalyticsRow(emptyList(), 120.0, 4_000.0, 0.03, 8.5)),
                responseAggregationType = "byProperty",
                metadata = null,
            )
            sitemapCount = 3
            index = indexStatus(verdict = "PASS", coverage = "Submitted and indexed")
        }

        val result = SearchConsoleDataSource(api)
            .newPropertySummaryCall(credential(), "sc-domain:example.com", LocalDate.parse("2026-10-09"))
            .execute() as SearchConsoleFetchResult.Complete
        val summary = result.value

        val query = api.lastQuery!!
        assertEquals("2026-09-12", query.dateRange.startDate)
        assertEquals("2026-10-09", query.dateRange.endDate)
        assertEquals(SearchConsoleAggregationType.BY_PROPERTY, query.aggregationType)
        assertEquals(SearchConsoleDataState.ALL, query.dataState)
        assertEquals(1, query.rowLimit)
        assertTrue(query.dimensions.isEmpty())
        assertEquals("https://example.com/", api.lastInspectionUrl)
        assertEquals(120.0, summary.clicks!!, 0.0)
        assertEquals(4_000.0, summary.impressions!!, 0.0)
        assertEquals(0.03, summary.ctr!!, 0.0)
        assertEquals(8.5, summary.position!!, 0.0)
        assertEquals(3, summary.sitemapCount)
        assertEquals("Indexed", summary.indexStatus)
        assertFalse(summary.metricsArePartial)
    }

    @Test
    fun noTrafficIsZeroClicksWhileFailedPartsStayPartialWithWarnings() {
        val api = SummaryApi().apply {
            analytics = SearchConsoleAnalyticsResponse(emptyList(), "byProperty", null)
            sitemapFailure = IOException("offline")
            index = indexStatus(verdict = "NEUTRAL", coverage = "Discovered - currently not indexed")
        }

        val result = SearchConsoleDataSource(api)
            .newPropertySummaryCall(credential(), "https://example.com/blog/", LocalDate.parse("2026-10-09"))
            .execute() as SearchConsoleFetchResult.Partial
        val summary = result.value

        assertEquals(0.0, summary.clicks!!, 0.0)
        assertEquals(0.0, summary.impressions!!, 0.0)
        assertNull(summary.ctr)
        assertNull(summary.sitemapCount)
        assertTrue(summary.metricsArePartial)
        assertEquals("https://example.com/blog/", api.lastInspectionUrl)
        assertEquals("Discovered - currently not indexed", summary.indexStatus)
        assertTrue(summary.warnings.single().startsWith("Sitemaps could not load"))
        assertEquals(SearchConsoleFailureKind.NETWORK, result.failure.kind)
    }

    @Test
    fun rejectedCredentialFailsTheWholeOverview() {
        val api = SummaryApi().apply {
            analyticsFailure = SearchConsoleApiException(
                SearchConsoleFailure(SearchConsoleFailureKind.AUTHENTICATION, "Google rejected this access token.", 401),
            )
        }

        val result = SearchConsoleDataSource(api)
            .newPropertySummaryCall(credential(), "sc-domain:example.com", LocalDate.parse("2026-10-09"))
            .execute()

        assertTrue(result is SearchConsoleFetchResult.Failure)
        assertEquals(0, api.sitemapCalls)
    }

    @Test
    fun indexStatusLabelAndInspectionUrlMatchIos() {
        assertEquals("Indexed", searchConsoleIndexStatusLabel("PASS", "Submitted and indexed"))
        assertEquals("Crawled - currently not indexed", searchConsoleIndexStatusLabel("NEUTRAL", "Crawled - currently not indexed"))
        assertEquals("Fail", searchConsoleIndexStatusLabel("FAIL", null))
        assertNull(searchConsoleIndexStatusLabel(null, null))
        assertEquals("https://example.com/", searchConsoleOverviewInspectionUrl("sc-domain:example.com"))
        assertEquals("http://example.com/", searchConsoleOverviewInspectionUrl("http://example.com/"))
        assertNull(searchConsoleOverviewInspectionUrl("sc-domain:"))
        assertNull(searchConsoleOverviewInspectionUrl("android-app://com.example/"))
    }

    @Test
    fun accountTotalsWeightPositionByImpressionsLikeIos() {
        val totals = aggregateSearchConsoleSummaries(
            propertyCount = 3,
            summaries = listOf(
                summary("a", clicks = 10.0, impressions = 100.0, position = 2.0, sitemaps = 1),
                summary("b", clicks = 30.0, impressions = 300.0, position = 6.0, sitemaps = 2),
            ),
        )

        assertEquals(40.0, totals.clicks, 0.0)
        assertEquals(400.0, totals.impressions, 0.0)
        assertEquals(0.1, totals.ctr, 1e-9)
        assertEquals(5.0, totals.position!!, 1e-9)
        assertEquals(3, totals.sitemaps)
        assertTrue("One property has not loaded yet", totals.isPartial)
    }

    private fun summary(siteUrl: String, clicks: Double, impressions: Double, position: Double, sitemaps: Int) =
        SearchConsolePropertySummaryUi(siteUrl, clicks, impressions, clicks / impressions, position, sitemaps, "Indexed", "PASS", null, false)

    private fun indexStatus(verdict: String?, coverage: String?) = SearchConsoleIndexStatus(
        emptyList(), emptyList(), verdict, coverage, null, null, "2026-10-01T10:00:00Z", null, null, null, null,
    )

    private fun credential() = SearchConsoleOAuthCredential(
        SecretValue.of("access"), SecretValue.of("refresh"), "Bearer",
        SearchConsoleOAuthCredential.REQUIRED_SCOPES, Long.MAX_VALUE / 2, null, null,
    )

    private class SummaryApi : SearchConsoleReadApi {
        var analytics: SearchConsoleAnalyticsResponse? = null
        var analyticsFailure: Exception? = null
        var sitemapCount = 0
        var sitemapFailure: Exception? = null
        var index: SearchConsoleIndexStatus? = null
        var lastQuery: SearchConsoleAnalyticsQuery? = null
        var lastInspectionUrl: String? = null
        var sitemapCalls = 0

        override fun newListVerifiedPropertiesCall(credential: SearchConsoleOAuthCredential) =
            call { SearchConsolePropertyList(emptyList()) }

        override fun newAnalyticsPageCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            query: SearchConsoleAnalyticsQuery,
        ): CancelableCall<SearchConsoleAnalyticsResponse> {
            SearchConsoleApi.validateQuery(query)
            lastQuery = query
            return call { analyticsFailure?.let { throw it } ?: checkNotNull(analytics) }
        }

        override fun newListSitemapsCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            sitemapIndex: String?,
        ): CancelableCall<List<SearchConsoleSitemap>> {
            sitemapCalls += 1
            return call {
                sitemapFailure?.let { throw it }
                List(sitemapCount) { SearchConsoleSitemap("https://example.com/s$it.xml", null, false, false, null, null, 0, 0, emptyList()) }
            }
        }

        override fun newGetSitemapCall(credential: SearchConsoleOAuthCredential, siteUrl: String, feedPath: String) =
            call { SearchConsoleSitemap(feedPath, null, false, false, null, null, 0, 0, emptyList()) }

        override fun newInspectUrlCall(
            credential: SearchConsoleOAuthCredential,
            inspectionUrl: String,
            siteUrl: String,
            languageCode: String,
        ): CancelableCall<SearchConsoleUrlInspectionResult> {
            lastInspectionUrl = inspectionUrl
            return call { SearchConsoleUrlInspectionResult(null, index, null, null, null) }
        }

        private fun <T> call(block: () -> T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = block()
            override fun cancel() = Unit
        }
    }
}
