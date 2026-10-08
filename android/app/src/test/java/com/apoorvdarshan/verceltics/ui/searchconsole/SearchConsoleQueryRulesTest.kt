package com.apoorvdarshan.verceltics.ui.searchconsole

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConsoleQueryRulesTest {
    private val base = SearchConsolePerformanceQueryUi.default(LocalDate.parse("2026-10-09"))

    @Test
    fun choosingHourSwitchesToHourlyDataLikeIos() {
        val single = base.withSingleDimension(SearchConsoleDimensionUi.HOUR)
        assertEquals(listOf(SearchConsoleDimensionUi.HOUR), single.dimensions)
        assertEquals(SearchConsoleDataStateUi.HOURLY_ALL, single.dataState)

        val toggled = base.withToggledDimension(SearchConsoleDimensionUi.HOUR)
        assertEquals(listOf(SearchConsoleDimensionUi.QUERY, SearchConsoleDimensionUi.HOUR), toggled.dimensions)
        assertEquals(SearchConsoleDataStateUi.HOURLY_ALL, toggled.dataState)
    }

    @Test
    fun hourlyDataAddsHourAndLeavingItRemovesHour() {
        val hourly = base.withDataState(SearchConsoleDataStateUi.HOURLY_ALL)
        assertEquals(listOf(SearchConsoleDimensionUi.HOUR, SearchConsoleDimensionUi.QUERY), hourly.dimensions)

        val final = hourly.withDataState(SearchConsoleDataStateUi.FINAL)
        assertEquals(listOf(SearchConsoleDimensionUi.QUERY), final.dimensions)
        assertEquals(SearchConsoleDataStateUi.FINAL, final.dataState)

        val onlyHour = base.withSingleDimension(SearchConsoleDimensionUi.HOUR).withDataState(SearchConsoleDataStateUi.ALL)
        assertEquals("An empty breakdown falls back to Query", listOf(SearchConsoleDimensionUi.QUERY), onlyHour.dimensions)
    }

    @Test
    fun normalizationRepairsAnHourQueryThatDidNotAskForHourlyData() {
        val broken = base.copy(
            dataState = SearchConsoleDataStateUi.ALL,
            dimensions = listOf(SearchConsoleDimensionUi.HOUR),
            page = 3,
        )

        val normalized = broken.normalizedForGoogle()

        assertEquals(SearchConsoleDataStateUi.HOURLY_ALL, normalized.dataState)
        assertEquals(3, normalized.page)
    }

    @Test
    fun aggregationRulesForDiscoverNewsAndPages() {
        val discover = base.withSearchType(SearchConsoleSearchTypeUi.DISCOVER)
        assertFalse(discover.canSelectAggregation(SearchConsoleAggregationUi.BY_PROPERTY))
        assertFalse(discover.canSelectAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL))
        assertTrue(discover.canSelectAggregation(SearchConsoleAggregationUi.BY_PAGE))
        assertTrue(base.canSelectAggregation(SearchConsoleAggregationUi.BY_PROPERTY))
        assertFalse(base.canSelectAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL))

        val showcase = discover.withFilters(
            listOf(
                SearchConsoleFilterUi(
                    SearchConsoleDimensionUi.SEARCH_APPEARANCE,
                    SearchConsoleFilterOperatorUi.EQUALS,
                    "NEWS_SHOWCASE",
                ),
            ),
        )
        assertTrue(showcase.canSelectAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL))
        assertEquals(
            SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL,
            showcase.withAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL).aggregation,
        )
        assertTrue(base.aggregationRestriction(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL)!!.contains("NEWS_SHOWCASE"))
        assertTrue(discover.aggregationRestriction(SearchConsoleAggregationUi.BY_PROPERTY)!!.contains("Discover"))
    }

    @Test
    fun rejectedAggregationsFallBackToAutomatic() {
        val byProperty = base.withAggregation(SearchConsoleAggregationUi.BY_PROPERTY)
        assertEquals(SearchConsoleAggregationUi.BY_PROPERTY, byProperty.aggregation)

        // Page grouping and Discover cannot be aggregated by property; the switch never throws.
        assertEquals(SearchConsoleAggregationUi.AUTO, byProperty.withSingleDimension(SearchConsoleDimensionUi.PAGE).aggregation)
        assertEquals(SearchConsoleAggregationUi.AUTO, byProperty.withSearchType(SearchConsoleSearchTypeUi.GOOGLE_NEWS).aggregation)

        val showcase = base.withSearchType(SearchConsoleSearchTypeUi.DISCOVER)
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
        assertEquals(SearchConsoleAggregationUi.AUTO, showcase.withFilters(emptyList()).aggregation)
        assertSame("A disallowed choice is ignored", base, base.withAggregation(SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL))
    }

    @Test
    fun sortingAndPagingDoNotRequireNewGoogleData() {
        val sorted = base.withSortToggled(SearchConsoleSortFieldUi.POSITION).copy(page = 2)
        assertTrue(base.requestsSameData(sorted))
        assertTrue(sorted.sortAscending)
        assertFalse(sorted.withSortToggled(SearchConsoleSortFieldUi.POSITION).sortAscending)
        assertFalse(base.withSortToggled(SearchConsoleSortFieldUi.IMPRESSIONS).sortAscending)
        assertTrue(base.withSortToggled(SearchConsoleSortFieldUi.DIMENSION).sortAscending)
        assertFalse(base.requestsSameData(base.withSearchType(SearchConsoleSearchTypeUi.IMAGE)))
        assertFalse(base.requestsSameData(base.withPreset(SearchConsoleDatePresetUi.DAYS_7, LocalDate.parse("2026-10-09"))))
    }

    @Test
    fun presetsEndYesterdayAndSpanTheirDays() {
        val week = base.withPreset(SearchConsoleDatePresetUi.DAYS_7, LocalDate.parse("2026-10-09"))
        assertEquals("2026-10-02", week.startDate)
        assertEquals("2026-10-08", week.endDate)
    }

    @Test
    fun breakdownSortsByDimensionTextAndBreaksTiesLikeIos() {
        val rows = listOf(
            row("b", 5.0, 3.0),
            row("a", 5.0, 9.0),
            row("c", 1.0, 1.0),
        )
        val dimensions = listOf(SearchConsoleDimensionUi.QUERY)

        assertEquals(listOf("a", "b", "c"), sortSearchConsoleBreakdown(rows, dimensions, SearchConsoleSortFieldUi.DIMENSION, true, Locale.US).map { it.keys.single() })
        assertEquals(listOf("a", "b", "c"), sortSearchConsoleBreakdown(rows, dimensions, SearchConsoleSortFieldUi.CLICKS, false, Locale.US).map { it.keys.single() })
        assertEquals(listOf("c", "b", "a"), sortSearchConsoleBreakdown(rows, dimensions, SearchConsoleSortFieldUi.POSITION, true, Locale.US).map { it.keys.single() })
    }

    @Test
    fun breakdownPageClampsToTheLastPage() {
        val rows = (1..23).map { row("q$it", it.toDouble(), 1.0) }

        val page = searchConsoleBreakdownPage(rows, page = 9, pageSize = 10)

        assertEquals(2, page.page)
        assertEquals(3, page.totalPages)
        assertEquals(21, page.firstRow)
        assertEquals(23, page.lastRow)
        assertFalse(page.hasNext)
        assertEquals(0, searchConsoleBreakdownPage(emptyList(), 0, 25).totalPages)
    }

    @Test
    fun dimensionValuesAreFormattedPerDimension() {
        val utc = ZoneId.of("UTC")
        val dimensions = listOf(
            SearchConsoleDimensionUi.COUNTRY,
            SearchConsoleDimensionUi.DEVICE,
            SearchConsoleDimensionUi.SEARCH_APPEARANCE,
            SearchConsoleDimensionUi.HOUR,
        )
        val value = searchConsoleDimensionValue(
            listOf("ind", "MOBILE", "AMP_BLUE_LINK", "2026-08-01T13:00:00Z"),
            dimensions,
            zone = utc,
            locale = Locale.US,
        )

        assertEquals("IND · Mobile · Amp Blue Link · Aug 1, 1 PM", value)
        assertEquals("Property total", searchConsoleDimensionValue(emptyList(), dimensions))
        assertEquals("Rich Result Type", searchConsoleHumanized("richResultType"))
    }

    @Test
    fun timelineLabelsAndTimestampsUseReadableDates() {
        val utc = ZoneId.of("UTC")
        assertEquals("Aug 1", formatSearchConsoleTimelineLabel("2026-08-01", utc, Locale.US))
        assertEquals("Aug 1, 8 PM", formatSearchConsoleTimelineLabel("2026-08-01T13:00:00-07:00", utc, Locale.US))
        assertTrue(formatSearchConsoleTimestamp("2026-08-26T09:12:00Z", utc, Locale.US).startsWith("Aug 26, 2026"))
        assertEquals("Not reported", formatSearchConsoleTimestamp(null))
        assertEquals("pending", formatSearchConsoleTimestamp("pending"))
    }

    @Test
    fun headlineValuesMatchIosWeightedTotals() {
        val timeline = listOf(
            SearchConsoleTimelinePointUi("2026-08-01", 10.0, 100.0, 0.1, 2.0),
            SearchConsoleTimelinePointUi("2026-08-02", 30.0, 300.0, 0.1, 6.0),
        )

        assertEquals(40.0, searchConsoleHeadlineValue(timeline, SearchConsoleMetricUi.CLICKS), 0.0)
        assertEquals(0.1, searchConsoleHeadlineValue(timeline, SearchConsoleMetricUi.CTR), 1e-9)
        assertEquals(5.0, searchConsoleHeadlineValue(timeline, SearchConsoleMetricUi.POSITION), 1e-9)
    }

    private fun row(key: String, clicks: Double, position: Double) =
        SearchConsoleBreakdownRowUi(listOf(key), clicks, clicks * 10, 0.1, position)
}
