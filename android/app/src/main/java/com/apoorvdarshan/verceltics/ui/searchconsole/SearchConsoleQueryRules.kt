package com.apoorvdarshan.verceltics.ui.searchconsole

import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil

/*
 * Search Analytics query rules shared by the on-page controls, the advanced report dialog and the
 * gateway. They mirror iOS `SearchConsoleDetailViewModel` so both apps send Google the same
 * combinations: choosing Hour switches to hourly data, leaving hourly data drops Hour, and an
 * aggregation that Google would reject falls back to Automatic.
 */

private const val NEWS_SHOWCASE = "NEWS_SHOWCASE"

internal fun searchConsoleAggregationAllowed(
    aggregation: SearchConsoleAggregationUi,
    searchType: SearchConsoleSearchTypeUi,
    dimensions: List<SearchConsoleDimensionUi>,
    filters: List<SearchConsoleFilterUi>,
): Boolean {
    val groupsByPage = SearchConsoleDimensionUi.PAGE in dimensions ||
        filters.any { it.dimension == SearchConsoleDimensionUi.PAGE }
    val discoverOrNews = searchType == SearchConsoleSearchTypeUi.DISCOVER ||
        searchType == SearchConsoleSearchTypeUi.GOOGLE_NEWS
    return when (aggregation) {
        SearchConsoleAggregationUi.AUTO,
        SearchConsoleAggregationUi.BY_PAGE,
        -> true
        // Google does not aggregate Discover or Google News by property, nor page-grouped rows.
        SearchConsoleAggregationUi.BY_PROPERTY -> !groupsByPage && !discoverOrNews
        SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL -> !groupsByPage && discoverOrNews &&
            filters.any {
                it.dimension == SearchConsoleDimensionUi.SEARCH_APPEARANCE &&
                    it.expression.trim().equals(NEWS_SHOWCASE, ignoreCase = true)
            }
    }
}

internal fun SearchConsolePerformanceQueryUi.canSelectAggregation(
    aggregation: SearchConsoleAggregationUi,
): Boolean = searchConsoleAggregationAllowed(aggregation, searchType, dimensions, filters)

/** Why an aggregation is unavailable, so the disabled control explains itself. */
internal fun SearchConsolePerformanceQueryUi.aggregationRestriction(
    aggregation: SearchConsoleAggregationUi,
): String? = when {
    canSelectAggregation(aggregation) -> null
    aggregation == SearchConsoleAggregationUi.BY_PROPERTY &&
        (searchType == SearchConsoleSearchTypeUi.DISCOVER || searchType == SearchConsoleSearchTypeUi.GOOGLE_NEWS) ->
        "Discover and Google News cannot be aggregated by property."
    aggregation == SearchConsoleAggregationUi.BY_PROPERTY ->
        "Page grouping or a page filter needs page or automatic aggregation."
    else -> "News Showcase needs Discover or Google News, a NEWS_SHOWCASE search-appearance filter, and no page grouping."
}

/**
 * Builds a query with every Google rule applied in one step, so the constructor's invariants
 * (no by-property page grouping) never see an intermediate invalid combination.
 */
private fun SearchConsolePerformanceQueryUi.rebuilt(
    searchType: SearchConsoleSearchTypeUi = this.searchType,
    dataState: SearchConsoleDataStateUi = this.dataState,
    aggregation: SearchConsoleAggregationUi = this.aggregation,
    dimensions: List<SearchConsoleDimensionUi> = this.dimensions,
    filters: List<SearchConsoleFilterUi> = this.filters,
): SearchConsolePerformanceQueryUi {
    val safeDimensions = dimensions.distinct().ifEmpty { listOf(SearchConsoleDimensionUi.QUERY) }
    // Google rejects hourly rows unless the request uses the hourly_all data state.
    val safeDataState = if (SearchConsoleDimensionUi.HOUR in safeDimensions) {
        SearchConsoleDataStateUi.HOURLY_ALL
    } else {
        dataState
    }
    val safeAggregation = if (searchConsoleAggregationAllowed(aggregation, searchType, safeDimensions, filters)) {
        aggregation
    } else {
        SearchConsoleAggregationUi.AUTO
    }
    return copy(
        searchType = searchType,
        dataState = safeDataState,
        aggregation = safeAggregation,
        dimensions = safeDimensions,
        filters = filters,
        page = 0,
    )
}

/** Applies Hour ↔ hourly and aggregation rules to a query built elsewhere (dialog, restore). */
internal fun SearchConsolePerformanceQueryUi.normalizedForGoogle(): SearchConsolePerformanceQueryUi =
    rebuilt().copy(page = page.coerceAtLeast(0))

/** iOS `selectDimension`: a single breakdown dimension; Hour also switches to hourly data. */
internal fun SearchConsolePerformanceQueryUi.withSingleDimension(
    dimension: SearchConsoleDimensionUi,
): SearchConsolePerformanceQueryUi = rebuilt(
    dimensions = listOf(dimension),
    dataState = if (dimension == SearchConsoleDimensionUi.HOUR) SearchConsoleDataStateUi.HOURLY_ALL else dataState,
)

/** iOS `toggleDimension`: adds or removes a dimension, never leaving the breakdown empty. */
internal fun SearchConsolePerformanceQueryUi.withToggledDimension(
    dimension: SearchConsoleDimensionUi,
): SearchConsolePerformanceQueryUi {
    val updated = if (dimension in dimensions) {
        if (dimensions.size == 1) return this
        dimensions - dimension
    } else {
        dimensions + dimension
    }
    return rebuilt(dimensions = updated)
}

/** iOS `selectDataState`: hourly data adds the Hour dimension; any other state removes it. */
internal fun SearchConsolePerformanceQueryUi.withDataState(
    state: SearchConsoleDataStateUi,
): SearchConsolePerformanceQueryUi {
    val updated = if (state == SearchConsoleDataStateUi.HOURLY_ALL) {
        if (SearchConsoleDimensionUi.HOUR in dimensions) dimensions else listOf(SearchConsoleDimensionUi.HOUR) + dimensions
    } else {
        (dimensions - SearchConsoleDimensionUi.HOUR).ifEmpty { listOf(SearchConsoleDimensionUi.QUERY) }
    }
    return rebuilt(dataState = state, dimensions = updated)
}

internal fun SearchConsolePerformanceQueryUi.withSearchType(
    type: SearchConsoleSearchTypeUi,
): SearchConsolePerformanceQueryUi = rebuilt(searchType = type)

/** Ignored when Google would reject the aggregation for the current query. */
internal fun SearchConsolePerformanceQueryUi.withAggregation(
    aggregation: SearchConsoleAggregationUi,
): SearchConsolePerformanceQueryUi =
    if (canSelectAggregation(aggregation)) rebuilt(aggregation = aggregation) else this

internal fun SearchConsolePerformanceQueryUi.withFilters(
    filters: List<SearchConsoleFilterUi>,
): SearchConsolePerformanceQueryUi = rebuilt(filters = filters.take(32))

internal fun SearchConsolePerformanceQueryUi.withPreset(
    preset: SearchConsoleDatePresetUi,
    today: LocalDate = LocalDate.now(),
): SearchConsolePerformanceQueryUi {
    require(preset != SearchConsoleDatePresetUi.CUSTOM) { "Custom ranges need explicit dates." }
    val end = today.minusDays(1)
    return copy(
        preset = preset,
        startDate = end.minusDays(preset.days - 1).toString(),
        endDate = end.toString(),
        page = 0,
    )
}

/** iOS `toggleSort`: the same column flips direction; a new column picks its natural order. */
internal fun SearchConsolePerformanceQueryUi.withSortToggled(
    field: SearchConsoleSortFieldUi,
): SearchConsolePerformanceQueryUi = if (sortField == field) {
    copy(sortAscending = !sortAscending, page = 0)
} else {
    copy(
        sortField = field,
        sortAscending = field == SearchConsoleSortFieldUi.DIMENSION || field == SearchConsoleSortFieldUi.POSITION,
        page = 0,
    )
}

/** True when two queries ask Google for the same rows; only sort and paging differ. */
internal fun SearchConsolePerformanceQueryUi.requestsSameData(other: SearchConsolePerformanceQueryUi): Boolean =
    startDate == other.startDate && endDate == other.endDate && searchType == other.searchType &&
        dataState == other.dataState && aggregation == other.aggregation &&
        dimensions == other.dimensions && filters == other.filters

/** Google matches NEWS_SHOWCASE exactly; accept any case on device but send the canonical value. */
internal fun SearchConsoleFilterUi.googleExpression(): String =
    if (dimension == SearchConsoleDimensionUi.SEARCH_APPEARANCE && expression.trim().equals(NEWS_SHOWCASE, ignoreCase = true)) {
        NEWS_SHOWCASE
    } else {
        expression
    }

// MARK: - Breakdown presentation

internal data class SearchConsoleBreakdownPageUi(
    val rows: List<SearchConsoleBreakdownRowUi>,
    val page: Int,
    val totalPages: Int,
    val firstRow: Int,
    val lastRow: Int,
    val totalRows: Int,
) {
    val hasPrevious: Boolean get() = page > 0
    val hasNext: Boolean get() = page < totalPages - 1
}

/** iOS `SearchConsoleFormatting.humanized`: MOBILE → Mobile, richResultType → Rich Result Type. */
internal fun searchConsoleHumanized(value: String): String {
    val spaced = StringBuilder()
    value.replace('_', ' ').replace('-', ' ').forEach { character ->
        val previous = spaced.lastOrNull()
        if (character.isUpperCase() && previous != null && !previous.isWhitespace() && !previous.isUpperCase()) {
            spaced.append(' ')
        }
        spaced.append(character)
    }
    return spaced.split(' ').filter(String::isNotEmpty).joinToString(" ") { word ->
        word.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

internal fun parseSearchConsoleInstant(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()

/** "Aug 26, 2026, 9:12 AM" in the device zone; Google's raw text when it is not a timestamp. */
internal fun formatSearchConsoleTimestamp(
    value: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (value.isNullOrBlank()) return "Not reported"
    val instant = parseSearchConsoleInstant(value) ?: return value
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
        .format(instant)
}

/** Timeline axis and scrub labels: "Aug 1" for days, "Aug 1, 1 PM" for hours. */
internal fun formatSearchConsoleTimelineLabel(
    key: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    runCatching { LocalDate.parse(key) }.getOrNull()?.let { date ->
        return DateTimeFormatter.ofPattern("MMM d", locale).format(date)
    }
    val instant = parseSearchConsoleInstant(key) ?: return key
    return DateTimeFormatter.ofPattern("MMM d, h a", locale).withZone(zone).format(instant)
}

/** iOS `dimensionValue(for:)`: each key formatted for its dimension, joined with " · ". */
internal fun searchConsoleDimensionValue(
    keys: List<String>,
    dimensions: List<SearchConsoleDimensionUi>,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (keys.isEmpty()) return "Property total"
    return keys.mapIndexed { index, raw ->
        when (dimensions.getOrNull(index)) {
            SearchConsoleDimensionUi.DATE -> runCatching { LocalDate.parse(raw) }.getOrNull()
                ?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it) }
                ?: raw
            SearchConsoleDimensionUi.HOUR -> parseSearchConsoleInstant(raw)
                ?.let { DateTimeFormatter.ofPattern("MMM d, h a", locale).withZone(zone).format(it) }
                ?: raw
            SearchConsoleDimensionUi.DEVICE,
            SearchConsoleDimensionUi.SEARCH_APPEARANCE,
            -> searchConsoleHumanized(raw)
            SearchConsoleDimensionUi.COUNTRY -> raw.uppercase(Locale.ROOT)
            SearchConsoleDimensionUi.QUERY,
            SearchConsoleDimensionUi.PAGE,
            null,
            -> raw
        }
    }.joinToString(" · ")
}

/** iOS `sortedBreakdownRows`: metric or dimension order, ties broken by dimension text. */
internal fun sortSearchConsoleBreakdown(
    rows: List<SearchConsoleBreakdownRowUi>,
    dimensions: List<SearchConsoleDimensionUi>,
    field: SearchConsoleSortFieldUi,
    ascending: Boolean,
    locale: Locale = Locale.getDefault(),
): List<SearchConsoleBreakdownRowUi> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val labels = HashMap<SearchConsoleBreakdownRowUi, String>(rows.size * 2)
    fun label(row: SearchConsoleBreakdownRowUi): String =
        labels.getOrPut(row) { searchConsoleDimensionValue(row.keys, dimensions, locale = locale) }
    val metric: ((SearchConsoleBreakdownRowUi) -> Double)? = when (field) {
        SearchConsoleSortFieldUi.DIMENSION -> null
        SearchConsoleSortFieldUi.CLICKS -> { row -> row.clicks }
        SearchConsoleSortFieldUi.IMPRESSIONS -> { row -> row.impressions }
        SearchConsoleSortFieldUi.CTR -> { row -> row.ctr }
        SearchConsoleSortFieldUi.POSITION -> { row -> row.position }
    }
    return rows.sortedWith { left, right ->
        val primary = if (metric == null) {
            collator.compare(label(left), label(right))
        } else {
            metric(left).compareTo(metric(right))
        }
        when {
            primary == 0 -> collator.compare(label(left), label(right))
            ascending -> primary
            else -> -primary
        }
    }
}

internal fun searchConsoleBreakdownPage(
    sortedRows: List<SearchConsoleBreakdownRowUi>,
    page: Int,
    pageSize: Int,
): SearchConsoleBreakdownPageUi {
    val size = pageSize.coerceAtLeast(1)
    val totalPages = if (sortedRows.isEmpty()) 0 else ceil(sortedRows.size / size.toDouble()).toInt()
    val safePage = page.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    val start = (safePage.toLong() * size).coerceAtMost(sortedRows.size.toLong()).toInt()
    val end = (start + size).coerceAtMost(sortedRows.size)
    return SearchConsoleBreakdownPageUi(
        rows = sortedRows.subList(start, end),
        page = safePage,
        totalPages = totalPages,
        firstRow = if (sortedRows.isEmpty()) 0 else start + 1,
        lastRow = end,
        totalRows = sortedRows.size,
    )
}

/** Totals shown above the chart, matching iOS `headlineValue`. */
internal fun searchConsoleHeadlineValue(
    timeline: List<SearchConsoleTimelinePointUi>,
    metric: SearchConsoleMetricUi,
): Double {
    val clicks = timeline.sumOf { it.clicks }
    val impressions = timeline.sumOf { it.impressions }
    return when (metric) {
        SearchConsoleMetricUi.CLICKS -> clicks
        SearchConsoleMetricUi.IMPRESSIONS -> impressions
        SearchConsoleMetricUi.CTR -> if (impressions > 0.0) clicks / impressions else 0.0
        SearchConsoleMetricUi.POSITION -> if (impressions > 0.0) {
            timeline.sumOf { it.position * it.impressions } / impressions
        } else {
            0.0
        }
    }
}

internal fun SearchConsoleTimelinePointUi.metricValue(metric: SearchConsoleMetricUi): Double = when (metric) {
    SearchConsoleMetricUi.CLICKS -> clicks
    SearchConsoleMetricUi.IMPRESSIONS -> impressions
    SearchConsoleMetricUi.CTR -> ctr
    SearchConsoleMetricUi.POSITION -> position
}

/** Chronological timeline order; hourly keys are compared as instants, not text. */
internal fun sortSearchConsoleTimeline(
    points: List<SearchConsoleTimelinePointUi>,
): List<SearchConsoleTimelinePointUi> = points.sortedWith(
    compareBy<SearchConsoleTimelinePointUi> { point ->
        runCatching { LocalDate.parse(point.label).atStartOfDay(ZoneId.of("UTC")).toInstant() }.getOrNull()
            ?: parseSearchConsoleInstant(point.label)
            ?: Instant.MAX
    }.thenBy { it.label },
)
