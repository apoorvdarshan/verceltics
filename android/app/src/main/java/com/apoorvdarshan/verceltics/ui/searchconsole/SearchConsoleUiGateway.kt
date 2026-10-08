package com.apoorvdarshan.verceltics.ui.searchconsole

import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi

sealed interface SearchConsoleOAuthReadinessUi {
    data object Ready : SearchConsoleOAuthReadinessUi
    data class ConfigurationNeeded(val message: String) : SearchConsoleOAuthReadinessUi
}

interface SearchConsoleUiGateway {
    val oauthReadiness: SearchConsoleOAuthReadinessUi

    suspend fun restore(): Result<SearchConsoleRestoreUi>

    suspend fun connect(): Result<SearchConsoleDashboardUi>

    suspend fun refresh(): Result<SearchConsoleDashboardUi>

    suspend fun loadProperty(
        property: SearchConsolePropertyUi,
        performanceQuery: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsolePropertyWorkspaceUi>

    suspend fun loadPerformance(
        siteUrl: String,
        query: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsoleResourceUi<SearchConsolePerformanceUi>>

    suspend fun inspect(siteUrl: String, inspectionUrl: String): Result<SearchConsoleInspectionUi>

    /**
     * Loads the free 28-day overview for each property (iOS resource-card metrics), delivering
     * each summary as soon as it is ready. A failure means the credential or account could not be
     * used at all; per-property problems arrive as partial summaries instead.
     */
    suspend fun loadPropertySummaries(
        siteUrls: List<String>,
        onSummary: suspend (SearchConsolePropertySummaryUi) -> Unit,
    ): Result<Unit> = Result.success(Unit)

    /** Removes the active account (the single-account "Disconnect"). */
    suspend fun disconnect(): Result<Unit>

    /** Saved Google accounts and the active one, read offline. */
    suspend fun accounts(): Result<SiteAccountsUi> = Result.success(SiteAccountsUi.EMPTY)

    /** Makes a saved account active and returns its offline restore (no network request). */
    suspend fun switchAccount(accountId: String): Result<SearchConsoleRestoreUi> =
        Result.failure(SearchConsoleUiException("Switching Google accounts is not available."))

    /** Removes one saved account and returns the restore of the account that becomes active. */
    suspend fun removeAccount(accountId: String): Result<SearchConsoleRestoreUi> =
        disconnect().map { SearchConsoleRestoreUi.NotConnected }

    /** Removes every saved Google account. */
    suspend fun removeAllAccounts(): Result<Unit> = disconnect()
}

sealed interface SearchConsoleRestoreUi {
    data object NotConnected : SearchConsoleRestoreUi
    data class Available(val dashboard: SearchConsoleDashboardUi) : SearchConsoleRestoreUi
    data class SavedWithoutInventory(val account: SearchConsoleAccountUi) : SearchConsoleRestoreUi
    data class SavedUnavailable(val message: String) : SearchConsoleRestoreUi
}

enum class SearchConsoleCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class SearchConsoleAccountUi(
    val id: String,
    val email: String?,
) {
    val displayName: String get() = email ?: "Google account"
}

data class SearchConsolePropertyUi(
    val siteUrl: String,
    val displayName: String,
    val permission: String,
)

/** One property's free 28-day overview. CTR is a 0–1 ratio; null values were not loaded. */
data class SearchConsolePropertySummaryUi(
    val siteUrl: String,
    val clicks: Double?,
    val impressions: Double?,
    val ctr: Double?,
    val position: Double?,
    val sitemapCount: Int?,
    val indexStatus: String?,
    val indexVerdict: String?,
    val lastCrawlTime: String?,
    val isPartial: Boolean,
    val warnings: List<String> = emptyList(),
)

/** Account-wide overview totals, aggregated like iOS `aggregateSearchConsoleMetrics`. */
data class SearchConsoleOverviewTotalsUi(
    val properties: Int,
    val loadedProperties: Int,
    val clicks: Double,
    val impressions: Double,
    val ctr: Double,
    val sitemaps: Int,
    val position: Double?,
    val isPartial: Boolean,
)

fun aggregateSearchConsoleSummaries(
    propertyCount: Int,
    summaries: Collection<SearchConsolePropertySummaryUi>,
): SearchConsoleOverviewTotalsUi {
    val clicks = summaries.sumOf { it.clicks ?: 0.0 }
    val impressions = summaries.sumOf { it.impressions ?: 0.0 }
    val weighted = summaries.mapNotNull { summary ->
        val position = summary.position ?: return@mapNotNull null
        val weight = summary.impressions?.takeIf { it > 0.0 } ?: return@mapNotNull null
        position to weight
    }
    val weight = weighted.sumOf { it.second }
    return SearchConsoleOverviewTotalsUi(
        properties = propertyCount,
        loadedProperties = summaries.size,
        clicks = clicks,
        impressions = impressions,
        ctr = if (impressions > 0.0) clicks / impressions else 0.0,
        sitemaps = summaries.sumOf { it.sitemapCount ?: 0 },
        position = if (weight > 0.0) weighted.sumOf { it.first * it.second } / weight else null,
        isPartial = summaries.size < propertyCount || summaries.any(SearchConsolePropertySummaryUi::isPartial),
    )
}

data class SearchConsoleDashboardUi(
    val account: SearchConsoleAccountUi,
    val properties: List<SearchConsolePropertyUi>,
    val loadedPropertyCount: Int,
    val providerInventoryComplete: Boolean,
    val inventoryTruncatedForDisplay: Boolean,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: SearchConsoleCacheState,
) {
    val isPartial: Boolean
        get() = !providerInventoryComplete || inventoryTruncatedForDisplay
}

sealed interface SearchConsoleResourceUi<out T> {
    data class Available<T>(
        val value: T,
        val isPartial: Boolean = false,
        val warning: String? = null,
    ) : SearchConsoleResourceUi<T>

    data class Unavailable(val message: String) : SearchConsoleResourceUi<Nothing>
}

data class SearchConsoleTimelinePointUi(
    val label: String,
    val clicks: Double,
    val impressions: Double,
    val ctr: Double,
    val position: Double,
)

enum class SearchConsoleDatePresetUi(val days: Long) {
    DAYS_7(7),
    DAYS_28(28),
    DAYS_90(90),
    DAYS_180(180),
    DAYS_365(365),
    DAYS_480(480),
    CUSTOM(0),
}

enum class SearchConsoleSearchTypeUi {
    WEB,
    IMAGE,
    VIDEO,
    NEWS,
    DISCOVER,
    GOOGLE_NEWS,
}

enum class SearchConsoleDataStateUi {
    FINAL,
    ALL,
    HOURLY_ALL,
}

enum class SearchConsoleAggregationUi {
    AUTO,
    BY_PAGE,
    BY_PROPERTY,
    BY_NEWS_SHOWCASE_PANEL,
}

enum class SearchConsoleDimensionUi {
    DATE,
    HOUR,
    QUERY,
    PAGE,
    COUNTRY,
    DEVICE,
    SEARCH_APPEARANCE,
}

enum class SearchConsoleFilterOperatorUi {
    CONTAINS,
    EQUALS,
    NOT_CONTAINS,
    NOT_EQUALS,
    INCLUDING_REGEX,
    EXCLUDING_REGEX,
}

enum class SearchConsoleSortFieldUi {
    DIMENSION,
    CLICKS,
    IMPRESSIONS,
    CTR,
    POSITION,
}

enum class SearchConsoleMetricUi {
    CLICKS,
    IMPRESSIONS,
    CTR,
    POSITION,
}

data class SearchConsoleFilterUi(
    val dimension: SearchConsoleDimensionUi,
    val operator: SearchConsoleFilterOperatorUi,
    val expression: String,
) {
    init {
        require(dimension != SearchConsoleDimensionUi.DATE && dimension != SearchConsoleDimensionUi.HOUR)
    }
}

data class SearchConsolePerformanceQueryUi(
    val preset: SearchConsoleDatePresetUi,
    val startDate: String,
    val endDate: String,
    val searchType: SearchConsoleSearchTypeUi = SearchConsoleSearchTypeUi.WEB,
    val dataState: SearchConsoleDataStateUi = SearchConsoleDataStateUi.ALL,
    val aggregation: SearchConsoleAggregationUi = SearchConsoleAggregationUi.AUTO,
    val dimensions: List<SearchConsoleDimensionUi> = listOf(SearchConsoleDimensionUi.QUERY),
    val filters: List<SearchConsoleFilterUi> = emptyList(),
    val sortField: SearchConsoleSortFieldUi = SearchConsoleSortFieldUi.CLICKS,
    val sortAscending: Boolean = false,
    val page: Int = 0,
    val pageSize: Int = 25,
) {
    init {
        require(startDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        require(endDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        val parsedStart = java.time.LocalDate.parse(startDate)
        val parsedEnd = java.time.LocalDate.parse(endDate)
        require(!parsedStart.isAfter(parsedEnd))
        require(dimensions.isNotEmpty() && dimensions.distinct().size == dimensions.size)
        require(filters.size <= 32 && filters.all {
            it.expression.isNotBlank() && it.expression.length <= 4_096
        })
        require(page >= 0 && pageSize in 10..100)
        require(
            aggregation != SearchConsoleAggregationUi.BY_PROPERTY ||
                SearchConsoleDimensionUi.PAGE !in dimensions &&
                filters.none { it.dimension == SearchConsoleDimensionUi.PAGE },
        )
    }

    companion object {
        fun default(
            today: java.time.LocalDate = java.time.LocalDate.now(java.time.Clock.systemUTC()),
        ): SearchConsolePerformanceQueryUi {
            val end = today.minusDays(1)
            return SearchConsolePerformanceQueryUi(
                preset = SearchConsoleDatePresetUi.DAYS_28,
                startDate = end.minusDays(27).toString(),
                endDate = end.toString(),
            )
        }
    }
}

data class SearchConsoleBreakdownRowUi(
    val keys: List<String>,
    val clicks: Double,
    val impressions: Double,
    val ctr: Double,
    val position: Double,
)

/**
 * A loaded performance report. [breakdownRows] holds every loaded row in Google's order; sorting
 * and paging happen on device (iOS `sortedBreakdownRows`/`pageRows`) without another request.
 */
data class SearchConsolePerformanceUi(
    val clicks: Double,
    val impressions: Double,
    val ctr: Double,
    val position: Double,
    val timeline: List<SearchConsoleTimelinePointUi>,
    val breakdownRows: List<SearchConsoleBreakdownRowUi>,
    val firstIncompleteDate: String?,
    val firstIncompleteHour: String?,
    val timelineIsHourly: Boolean = false,
    val timelineAggregationType: String? = null,
    val breakdownAggregationType: String? = null,
    val breakdownLimitReached: Boolean = false,
    val timelineError: String? = null,
    val breakdownError: String? = null,
) {
    /** iOS label: the aggregation Google actually applied, timeline first. */
    val returnedAggregationType: String? get() = timelineAggregationType ?: breakdownAggregationType
}

data class SearchConsoleSitemapContentUi(
    val type: String,
    val submitted: Long,
    val indexed: Long?,
)

data class SearchConsoleSitemapUi(
    val path: String,
    val lastSubmitted: String?,
    val isPending: Boolean,
    val isIndex: Boolean,
    val type: String?,
    val lastDownloaded: String?,
    val warnings: Long,
    val errors: Long,
    val contents: List<SearchConsoleSitemapContentUi>,
)

data class SearchConsolePropertyWorkspaceUi(
    val property: SearchConsolePropertyUi,
    val performance: SearchConsoleResourceUi<SearchConsolePerformanceUi>,
    val sitemaps: SearchConsoleResourceUi<List<SearchConsoleSitemapUi>>,
)

data class SearchConsoleInspectionIssueUi(
    val area: SearchConsoleInspectionAreaUi,
    val title: String,
    val severity: String?,
    val detail: String?,
)

enum class SearchConsoleInspectionAreaUi {
    AMP,
    MOBILE,
    RICH_RESULTS,
}

data class SearchConsoleAmpInspectionUi(
    val ampUrl: String?,
    val verdict: String?,
    val indexStatusVerdict: String?,
    val indexingState: String?,
    val robotsTxtState: String?,
    val pageFetchState: String?,
    val lastCrawlTime: String?,
    val issues: List<SearchConsoleInspectionIssueUi>,
)

data class SearchConsoleRichResultItemUi(
    val name: String?,
    val issues: List<SearchConsoleInspectionIssueUi>,
)

/** A detected rich-result type, kept even when Google reports no issues for it. */
data class SearchConsoleRichResultTypeUi(
    val type: String,
    val items: List<SearchConsoleRichResultItemUi>,
) {
    val issueCount: Int get() = items.sumOf { it.issues.size }
}

data class SearchConsoleInspectionUi(
    val inspectionResultLink: String?,
    val verdict: String?,
    val coverageState: String?,
    val indexingState: String?,
    val robotsTxtState: String?,
    val pageFetchState: String?,
    val lastCrawlTime: String?,
    val googleCanonical: String?,
    val userCanonical: String?,
    val crawledAs: String?,
    val sitemaps: List<String>,
    val referringUrls: List<String>,
    val ampVerdict: String?,
    val mobileVerdict: String?,
    val richResultsVerdict: String?,
    val issues: List<SearchConsoleInspectionIssueUi>,
    val inspectedUrl: String? = null,
    val hasIndexStatus: Boolean = true,
    val amp: SearchConsoleAmpInspectionUi? = null,
    val hasMobileResult: Boolean = mobileVerdict != null ||
        issues.any { it.area == SearchConsoleInspectionAreaUi.MOBILE },
    val richResultTypes: List<SearchConsoleRichResultTypeUi> = emptyList(),
    val hasRichResults: Boolean = richResultsVerdict != null || richResultTypes.isNotEmpty(),
)

class SearchConsoleUiException(message: String) : Exception(message) {
    override fun toString(): String = "SearchConsoleUiException(message=$message)"
}
