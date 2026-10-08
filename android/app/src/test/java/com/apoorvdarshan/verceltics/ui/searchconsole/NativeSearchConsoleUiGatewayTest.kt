package com.apoorvdarshan.verceltics.ui.searchconsole

import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAggregationType
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAmpResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDetectedRichResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleIndexStatus
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleInspectionIssue
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRichResultItem
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRichResultsResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleUrlInspectionResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsResponse
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsRow
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDataState
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDimension
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFailure
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFailureKind
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFetchResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFilterDimension
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFilterOperator
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSearchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSearchConsoleUiGatewayTest {
    @Test
    fun performanceQueryMapsEveryGoogleControlWithoutSecrets() {
        val query = SearchConsolePerformanceQueryUi(
            preset = SearchConsoleDatePresetUi.CUSTOM,
            startDate = "2026-01-02",
            endDate = "2026-03-04",
            searchType = SearchConsoleSearchTypeUi.IMAGE,
            dataState = SearchConsoleDataStateUi.HOURLY_ALL,
            aggregation = SearchConsoleAggregationUi.BY_PAGE,
            dimensions = listOf(SearchConsoleDimensionUi.PAGE, SearchConsoleDimensionUi.COUNTRY),
            filters = listOf(
                SearchConsoleFilterUi(
                    SearchConsoleDimensionUi.QUERY,
                    SearchConsoleFilterOperatorUi.INCLUDING_REGEX,
                    "swift|compose",
                ),
            ),
            sortField = SearchConsoleSortFieldUi.IMPRESSIONS,
            pageSize = 50,
        )

        val mapped = query.toDataQuery(
            dimensions = listOf(SearchConsoleDimension.PAGE, SearchConsoleDimension.COUNTRY),
            rowLimit = 25_000,
        )

        assertEquals("2026-01-02", mapped.dateRange.startDate)
        assertEquals("2026-03-04", mapped.dateRange.endDate)
        assertEquals(SearchConsoleSearchType.IMAGE, mapped.searchType)
        assertEquals(SearchConsoleDataState.HOURLY_ALL, mapped.dataState)
        assertEquals(SearchConsoleAggregationType.BY_PAGE, mapped.aggregationType)
        assertEquals(listOf(SearchConsoleDimension.PAGE, SearchConsoleDimension.COUNTRY), mapped.dimensions)
        assertEquals(SearchConsoleFilterDimension.QUERY, mapped.dimensionFilterGroups.single().filters.single().dimension)
        assertEquals(SearchConsoleFilterOperator.INCLUDING_REGEX, mapped.dimensionFilterGroups.single().filters.single().operator)
        assertFalse(mapped.toString().contains("access_token"))
    }

    @Test
    fun combinedPerformanceKeepsEveryRowForOnDeviceSortingAndFlagsTheRowCeiling() {
        val timeline = SearchConsoleFetchResult.Complete(
            SearchConsoleAnalyticsResponse(
                rows = listOf(
                    SearchConsoleAnalyticsRow(listOf("2026-08-02"), 3.0, 30.0, 0.1, 4.0),
                    SearchConsoleAnalyticsRow(listOf("2026-08-01"), 2.0, 20.0, 0.1, 5.0),
                ),
                responseAggregationType = "byProperty",
                metadata = null,
            ),
        )
        val breakdown = SearchConsoleFetchResult.Partial(
            value = SearchConsoleAnalyticsResponse(
                rows = (0 until 30).map { index ->
                    SearchConsoleAnalyticsRow(
                        keys = listOf("query-$index"),
                        clicks = index.toDouble(),
                        impressions = (index * 10).toDouble(),
                        ctr = 0.1,
                        position = index.toDouble(),
                    )
                },
                responseAggregationType = "byPage",
                metadata = null,
            ),
            failure = SearchConsoleFailure(
                SearchConsoleFailureKind.LIMIT_REACHED,
                "Bounded test result.",
            ),
        )
        val query = SearchConsolePerformanceQueryUi.default().copy(page = 1, pageSize = 10)

        val available = combinePerformance(timeline, breakdown, query) as SearchConsoleResourceUi.Available
        val performance = available.value

        assertEquals(30, performance.breakdownRows.size)
        assertTrue(performance.breakdownLimitReached)
        assertEquals(null, performance.breakdownError)
        assertEquals("byProperty", performance.returnedAggregationType)
        assertEquals(listOf("2026-08-01", "2026-08-02"), performance.timeline.map { it.label })
        assertEquals(5.0, performance.clicks, 0.0)

        val sorted = sortSearchConsoleBreakdown(
            performance.breakdownRows,
            query.dimensions,
            query.sortField,
            query.sortAscending,
        )
        val page = searchConsoleBreakdownPage(sorted, query.page, query.pageSize)
        assertEquals("query-19", page.rows.first().keys.single())
        assertEquals("query-10", page.rows.last().keys.single())
        assertTrue(page.hasPrevious)
        assertTrue(page.hasNext)
        assertEquals(11, page.firstRow)
        assertEquals(20, page.lastRow)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun hourlyBreakdownFromAnyEntryPointIsSentWithHourlyAllData() {
        val fromDialog = SearchConsolePerformanceQueryUi.default().copy(
            dataState = SearchConsoleDataStateUi.FINAL,
            dimensions = listOf(SearchConsoleDimensionUi.HOUR, SearchConsoleDimensionUi.QUERY),
        )

        val normalized = fromDialog.normalizedForGoogle()
        val mapped = normalized.toDataQuery(listOf(SearchConsoleDimension.HOUR), rowLimit = 25_000)

        assertEquals(SearchConsoleDataState.HOURLY_ALL, mapped.dataState)
        com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleApi.validateQuery(mapped)
    }

    @Test
    fun newsShowcaseFilterIsSentInGooglesCanonicalCase() {
        val query = SearchConsolePerformanceQueryUi.default()
            .withSearchType(SearchConsoleSearchTypeUi.DISCOVER)
            .withFilters(
                listOf(
                    SearchConsoleFilterUi(
                        SearchConsoleDimensionUi.SEARCH_APPEARANCE,
                        SearchConsoleFilterOperatorUi.EQUALS,
                        "news_showcase",
                    ),
                ),
            )
            .withAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL)

        val mapped = query.toDataQuery(listOf(SearchConsoleDimension.QUERY), rowLimit = 100)

        assertEquals(SearchConsoleAggregationType.BY_NEWS_SHOWCASE_PANEL, mapped.aggregationType)
        assertEquals("NEWS_SHOWCASE", mapped.dimensionFilterGroups.single().filters.single().expression)
        com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleApi.validateQuery(mapped)
    }

    @Test
    fun inspectionKeepsAmpDetailsAndRichTypesWithoutIssues() {
        val result = SearchConsoleUrlInspectionResult(
            inspectionResultLink = "https://search.google.com/search-console/inspect?resource_id=x",
            indexStatus = SearchConsoleIndexStatus(
                sitemaps = listOf("https://example.com/sitemap.xml"),
                referringUrls = emptyList(),
                verdict = "PASS",
                coverageState = "Submitted and indexed",
                robotsTxtState = "ALLOWED",
                indexingState = "INDEXING_ALLOWED",
                lastCrawlTime = "2026-08-26T09:12:00Z",
                pageFetchState = "SUCCESSFUL",
                googleCanonical = "https://example.com/",
                userCanonical = "https://example.com/",
                crawledAs = "MOBILE",
            ),
            ampResult = SearchConsoleAmpResult(
                issues = listOf(SearchConsoleInspectionIssue(null, "ERROR", "Referenced AMP URL is not an AMP")),
                verdict = "FAIL",
                ampUrl = "https://example.com/amp",
                robotsTxtState = "ALLOWED",
                indexingState = "INDEXING_ALLOWED",
                ampIndexStatusVerdict = "PASS",
                lastCrawlTime = "2026-08-25T08:00:00Z",
                pageFetchState = "SUCCESSFUL",
            ),
            mobileUsabilityResult = null,
            richResultsResult = SearchConsoleRichResultsResult(
                detectedItems = listOf(
                    SearchConsoleDetectedRichResult(
                        "Breadcrumbs",
                        listOf(SearchConsoleRichResultItem("Unnamed item", emptyList())),
                    ),
                    SearchConsoleDetectedRichResult(
                        "FAQ",
                        listOf(
                            SearchConsoleRichResultItem("Q1", listOf(SearchConsoleInspectionIssue(null, "WARNING", "Missing field"))),
                            SearchConsoleRichResultItem(null, emptyList()),
                        ),
                    ),
                ),
                verdict = "PASS",
            ),
        )

        val ui = result.toUi("https://example.com/")

        assertEquals("https://example.com/", ui.inspectedUrl)
        assertTrue(ui.hasIndexStatus)
        assertEquals("https://example.com/amp", ui.amp?.ampUrl)
        assertEquals("PASS", ui.amp?.indexStatusVerdict)
        assertEquals("2026-08-25T08:00:00Z", ui.amp?.lastCrawlTime)
        assertEquals("Referenced AMP URL is not an AMP", ui.amp?.issues?.single()?.title)
        assertFalse(ui.hasMobileResult)
        assertEquals(listOf("Breadcrumbs", "FAQ"), ui.richResultTypes.map { it.type })
        assertEquals(listOf(1, 2), ui.richResultTypes.map { it.items.size })
        assertEquals(listOf(0, 1), ui.richResultTypes.map { it.issueCount })
        assertTrue(ui.hasRichResults)
    }
}
