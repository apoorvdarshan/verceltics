package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cancellable read orchestration with explicit complete, partial, and failure results. */
class SearchConsoleDataSource internal constructor(
    private val api: SearchConsoleReadApi = SearchConsoleApi(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun newPropertiesCall(
        credential: SearchConsoleOAuthCredential,
    ): CancelableCall<SearchConsoleFetchResult<SearchConsoleSnapshot>> =
        SearchConsoleSingleCall { tracker ->
            val parsed = tracker.executeChild(api.newListVerifiedPropertiesCall(credential))
            val snapshot = if (parsed.skippedEntries == 0) {
                SearchConsoleSnapshot(parsed.properties, nowMillis(), true, emptyList())
            } else {
                SearchConsoleSnapshot(
                    parsed.properties,
                    nowMillis(),
                    false,
                    listOf(
                        "Google returned ${parsed.skippedEntries} malformed property " +
                            "${if (parsed.skippedEntries == 1) "entry" else "entries"}; the list is incomplete.",
                    ),
                )
            }
            if (snapshot.propertiesComplete) {
                SearchConsoleFetchResult.Complete(snapshot)
            } else {
                SearchConsoleFetchResult.Partial(
                    snapshot,
                    SearchConsoleFailure(
                        SearchConsoleFailureKind.INVALID_RESPONSE,
                        "Some Search Console properties could not be read.",
                    ),
                )
            }
        }

    fun newAllAnalyticsCall(
        credential: SearchConsoleOAuthCredential,
        siteUrl: String,
        query: SearchConsoleAnalyticsQuery,
        maximumRows: Int = SearchConsoleApi.MAXIMUM_ALL_ROWS,
    ): CancelableCall<SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>> {
        require(maximumRows in 1..SearchConsoleApi.MAXIMUM_ALL_ROWS) {
            "Maximum Search Analytics rows must be between 1 and ${SearchConsoleApi.MAXIMUM_ALL_ROWS}."
        }
        SearchConsoleApi.validateQuery(query)
        return SearchConsoleAnalyticsCall(api, credential, siteUrl, query, maximumRows)
    }

    fun newSitemapsCall(
        credential: SearchConsoleOAuthCredential,
        siteUrl: String,
        sitemapIndex: String? = null,
    ): CancelableCall<SearchConsoleFetchResult<List<SearchConsoleSitemap>>> =
        SearchConsoleSingleCall { tracker ->
            SearchConsoleFetchResult.Complete(
                tracker.executeChild(api.newListSitemapsCall(credential, siteUrl, sitemapIndex)),
            )
        }

    fun newSitemapCall(
        credential: SearchConsoleOAuthCredential,
        siteUrl: String,
        feedPath: String,
    ): CancelableCall<SearchConsoleFetchResult<SearchConsoleSitemap>> =
        SearchConsoleSingleCall { tracker ->
            SearchConsoleFetchResult.Complete(
                tracker.executeChild(api.newGetSitemapCall(credential, siteUrl, feedPath)),
            )
        }

    /**
     * One property's free overview, loaded like iOS `fetchSearchConsoleProperty`: 28-day
     * by-property totals, the submitted sitemap count and the root URL's index status. A rejected
     * or expired credential fails the whole call; every other part degrades to a partial summary.
     */
    fun newPropertySummaryCall(
        credential: SearchConsoleOAuthCredential,
        siteUrl: String,
        endDate: LocalDate,
    ): CancelableCall<SearchConsoleFetchResult<SearchConsolePropertySummary>> =
        SearchConsoleSingleCall { tracker ->
            val startDate = endDate.minusDays(SUMMARY_DAYS - 1)
            val warnings = mutableListOf<String>()
            var firstFailure: SearchConsoleFailure? = null
            fun <V> attempt(part: String, load: () -> V): V? = try {
                load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = safeFailure(error)
                if (failure.kind in CREDENTIAL_FAILURES) throw SearchConsoleApiException(failure)
                if (firstFailure == null) firstFailure = failure
                warnings += "$part could not load: ${failure.message}"
                null
            }

            val analytics = attempt("Search performance") {
                tracker.executeChild(
                    api.newAnalyticsPageCall(
                        credential,
                        siteUrl,
                        SearchConsoleAnalyticsQuery(
                            dateRange = SearchConsoleDateRange(startDate.toString(), endDate.toString()),
                            searchType = SearchConsoleSearchType.WEB,
                            aggregationType = SearchConsoleAggregationType.BY_PROPERTY,
                            rowLimit = 1,
                            dataState = SearchConsoleDataState.ALL,
                        ),
                    ),
                )
            }
            val sitemapCount = attempt("Sitemaps") {
                tracker.executeChild(api.newListSitemapsCall(credential, siteUrl)).size
            }
            val inspectionUrl = searchConsoleOverviewInspectionUrl(siteUrl)
            val index = inspectionUrl?.let { url ->
                attempt("Index status") {
                    tracker.executeChild(api.newInspectUrlCall(credential, url, siteUrl)).indexStatus
                        ?: MISSING_INDEX_STATUS
                }
            }
            val row = analytics?.rows?.firstOrNull()
            val summary = SearchConsolePropertySummary(
                siteUrl = siteUrl,
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                // Google omits rows entirely for a property with no search traffic in the window.
                clicks = row?.clicks ?: analytics?.let { 0.0 },
                impressions = row?.impressions ?: analytics?.let { 0.0 },
                ctr = row?.ctr,
                position = row?.position,
                sitemapCount = sitemapCount,
                inspectedUrl = inspectionUrl,
                indexVerdict = index?.verdict,
                coverageState = index?.coverageState,
                lastCrawlTime = index?.lastCrawlTime,
                analyticsLoaded = analytics != null,
                sitemapsLoaded = sitemapCount != null,
                inspectionLoaded = index != null,
                warnings = warnings.take(MAX_WARNINGS),
            )
            firstFailure?.let { SearchConsoleFetchResult.Partial(summary, it) }
                ?: SearchConsoleFetchResult.Complete(summary)
        }

    fun newInspectionCall(
        credential: SearchConsoleOAuthCredential,
        inspectionUrl: String,
        siteUrl: String,
        languageCode: String = "en-US",
    ): CancelableCall<SearchConsoleFetchResult<SearchConsoleUrlInspectionResult>> =
        SearchConsoleSingleCall { tracker ->
            SearchConsoleFetchResult.Complete(
                tracker.executeChild(
                    api.newInspectUrlCall(credential, inspectionUrl, siteUrl, languageCode),
                ),
            )
        }
}

private const val SUMMARY_DAYS = 28L

private val CREDENTIAL_FAILURES = setOf(
    SearchConsoleFailureKind.AUTHENTICATION,
    SearchConsoleFailureKind.EXPIRED_CREDENTIAL,
)

private val MISSING_INDEX_STATUS = SearchConsoleIndexStatus(
    sitemaps = emptyList(),
    referringUrls = emptyList(),
    verdict = null,
    coverageState = null,
    robotsTxtState = null,
    indexingState = null,
    lastCrawlTime = null,
    pageFetchState = null,
    googleCanonical = null,
    userCanonical = null,
    crawledAs = null,
)

private class SearchConsoleAnalyticsCall(
    private val api: SearchConsoleReadApi,
    private val credential: SearchConsoleOAuthCredential,
    private val siteUrl: String,
    private val query: SearchConsoleAnalyticsQuery,
    private val maximumRows: Int,
) : TrackedSearchConsoleCall<SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>>() {
    override fun executeTracked(): SearchConsoleFetchResult<SearchConsoleAnalyticsResponse> {
        val rows = mutableListOf<SearchConsoleAnalyticsRow>()
        var aggregation: String? = null
        var metadata: SearchConsoleAnalyticsMetadata? = null
        var offset = query.startRow
        while (rows.size < maximumRows) {
            throwIfCancelled()
            val page = try {
                executeChild(api.newAnalyticsPageCall(credential, siteUrl, query.page(offset)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return failedOrPartial(rows, aggregation, metadata, safeFailure(error))
            }
            aggregation = page.responseAggregationType ?: aggregation
            metadata = page.metadata ?: metadata
            val room = maximumRows - rows.size
            rows += page.rows.take(room)
            val reachedNaturalEnd = page.rows.size < query.rowLimit
            if (reachedNaturalEnd) {
                return SearchConsoleFetchResult.Complete(
                    SearchConsoleAnalyticsResponse(rows.toList(), aggregation, metadata),
                )
            }
            if (rows.size >= maximumRows) {
                val failure = SearchConsoleFailure(
                    SearchConsoleFailureKind.LIMIT_REACHED,
                    "Search Analytics reached the bounded $maximumRows-row limit; more rows may exist.",
                )
                return SearchConsoleFetchResult.Partial(
                    SearchConsoleAnalyticsResponse(rows.toList(), aggregation, metadata),
                    failure,
                )
            }
            if (page.rows.isEmpty()) {
                return SearchConsoleFetchResult.Complete(
                    SearchConsoleAnalyticsResponse(rows.toList(), aggregation, metadata),
                )
            }
            offset = Math.addExact(offset, page.rows.size)
        }
        error("Analytics loop exited without a result.")
    }

    private fun failedOrPartial(
        rows: List<SearchConsoleAnalyticsRow>,
        aggregation: String?,
        metadata: SearchConsoleAnalyticsMetadata?,
        failure: SearchConsoleFailure,
    ): SearchConsoleFetchResult<SearchConsoleAnalyticsResponse> = if (rows.isEmpty()) {
        SearchConsoleFetchResult.Failure(failure)
    } else {
        SearchConsoleFetchResult.Partial(
            SearchConsoleAnalyticsResponse(rows.toList(), aggregation, metadata),
            failure,
        )
    }
}

private class SearchConsoleSingleCall<T>(
    private val block: (TrackedSearchConsoleCall<SearchConsoleFetchResult<T>>) -> SearchConsoleFetchResult<T>,
) : TrackedSearchConsoleCall<SearchConsoleFetchResult<T>>() {
    override fun executeTracked(): SearchConsoleFetchResult<T> = try {
        block(this)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SearchConsoleFetchResult.Failure(safeFailure(error))
    }
}

private abstract class TrackedSearchConsoleCall<T> : CancelableCall<T> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeChild = AtomicReference<CancelableCall<*>?>()

    final override fun execute(): T {
        check(started.compareAndSet(false, true)) { "A Search Console call can only execute once." }
        throwIfCancelled()
        return executeTracked()
    }

    protected abstract fun executeTracked(): T

    final override fun cancel() {
        cancelled.set(true)
        activeChild.getAndSet(null)?.cancel()
    }

    fun <V> executeChild(call: CancelableCall<V>): V {
        throwIfCancelled()
        activeChild.set(call)
        if (cancelled.get()) {
            activeChild.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("Search Console request was cancelled.")
        }
        return try {
            call.execute().also { throwIfCancelled() }
        } finally {
            activeChild.compareAndSet(call, null)
        }
    }

    protected fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("Search Console request was cancelled.")
    }
}

private fun safeFailure(error: Exception): SearchConsoleFailure = when (error) {
    is SearchConsoleApiException -> error.failure
    is SearchConsoleResponseFormatException -> SearchConsoleFailure(
        SearchConsoleFailureKind.INVALID_RESPONSE,
        error.message ?: "Google returned an invalid Search Console response.",
    )
    is IOException -> SearchConsoleFailure(
        SearchConsoleFailureKind.NETWORK,
        "Google Search Console could not be reached. Check your connection and try again.",
    )
    is IllegalArgumentException -> SearchConsoleFailure(
        SearchConsoleFailureKind.CONFIGURATION,
        "The Search Console request configuration is invalid.",
    )
    else -> SearchConsoleFailure(
        SearchConsoleFailureKind.INVALID_RESPONSE,
        "Google returned an invalid Search Console response.",
    )
}
