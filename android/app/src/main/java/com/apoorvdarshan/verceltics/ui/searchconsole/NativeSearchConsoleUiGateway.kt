package com.apoorvdarshan.verceltics.ui.searchconsole

import android.content.Context
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.searchconsole.NativeSearchConsoleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsQuery
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsResponse
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAggregationType
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleConnectionRepository
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleConnectionStore
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDataSource
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDateRange
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDimension
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDimensionFilter
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDimensionFilterGroup
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFilterDimension
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFilterOperator
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFailureKind
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleFetchResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsolePropertySummary
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleOAuthCredential
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleProperty
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRestoreProblem
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRestoreResult
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSitemap
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSnapshot
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSearchType
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDataState
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleUrlInspectionResult
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Production bridge from encrypted Google credentials to bounded, display-only Compose models. */
class NativeSearchConsoleUiGateway internal constructor(
    private val connectionStore: SearchConsoleConnectionStore,
    private val dataSource: SearchConsoleDataSource,
    private val authorizer: SearchConsoleOAuthAuthorizer,
    private val networkExecutor: ExecutorService,
    private val storageExecutor: ExecutorService,
    private val clock: Clock = Clock.systemUTC(),
    private val beforeAcceptValidatedConnection: suspend () -> Unit = {},
    private val afterAcceptValidatedConnection: suspend () -> Unit = {},
) : SearchConsoleUiGateway {
    override val oauthReadiness: SearchConsoleOAuthReadinessUi =
        if (authorizer.configuration == null) {
            SearchConsoleOAuthReadinessUi.ConfigurationNeeded(
                "Google sign-in is unavailable in this version. Contact support for help connecting.",
            )
        } else {
            SearchConsoleOAuthReadinessUi.Ready
        }

    override suspend fun restore(): Result<SearchConsoleRestoreUi> = capture {
        when (val restored = executeAwait(storageExecutor, connectionStore::restore)) {
            SearchConsoleRestoreResult.NotConnected -> SearchConsoleRestoreUi.NotConnected
            is SearchConsoleRestoreResult.Restored -> {
                val account = SearchConsoleAccountUi(restored.id, restored.email)
                restored.cachedSnapshot?.let { snapshot ->
                    SearchConsoleRestoreUi.Available(
                        snapshot.toDashboardUi(
                            account,
                            if (restored.cacheIsStale) {
                                SearchConsoleCacheState.CACHED_STALE
                            } else {
                                SearchConsoleCacheState.CACHED_FRESH
                            },
                        ),
                    )
                } ?: SearchConsoleRestoreUi.SavedWithoutInventory(account)
            }
            is SearchConsoleRestoreResult.Unavailable -> SearchConsoleRestoreUi.SavedUnavailable(
                when (restored.problem) {
                    SearchConsoleRestoreProblem.SAVED_RECORD_UNREADABLE ->
                        "The saved Google connection could not be opened. It was not deleted or replaced."
                    SearchConsoleRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                        "Secure storage is unavailable. Unlock the device and try again."
                },
            )
        }
    }

    override suspend fun connect(): Result<SearchConsoleDashboardUi> = capture {
        val credential = authorizer.authorize()
        val result = dataSource.newPropertiesCall(credential).executeAwait(networkExecutor)
        val snapshot = result.valueOrThrow()
        var pendingCommit = null as com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRecordCommit?
        try {
            val commit = persistValidatedConnectionAwait(
                storageExecutor,
                connectionStore,
                credential,
                result,
            )
            pendingCommit = commit
            beforeAcceptValidatedConnection()
            withContext(NonCancellable) {
                executeAwait(storageExecutor) { connectionStore.acceptValidatedConnection(commit) }
                pendingCommit = null
                afterAcceptValidatedConnection()
                snapshot.toDashboardUi(credential.toAccountUi(), SearchConsoleCacheState.LIVE)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                pendingCommit?.let { commit ->
                    executeAwait(storageExecutor) {
                        connectionStore.rollbackValidatedConnection(commit)
                    }
                }
                executeAwait(storageExecutor) {}
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) {
                pendingCommit?.let { commit ->
                    executeAwait(storageExecutor) {
                        connectionStore.rollbackValidatedConnection(commit)
                    }
                }
            }
            throw error
        }
    }

    override suspend fun refresh(): Result<SearchConsoleDashboardUi> = capture {
        val request = currentCredential()
        val result = dataSource.newPropertiesCall(request.credential).executeAwait(networkExecutor)
        val snapshot = result.valueOrThrow()
        val persisted = executeAwait(storageExecutor) {
            connectionStore.persistSnapshotRefresh(request.source, result)
        }
        if (!persisted) {
            throw SearchConsoleUiException(
                "The saved Google connection changed while properties were refreshing. Try again.",
            )
        }
        snapshot.toDashboardUi(request.account, SearchConsoleCacheState.LIVE)
    }

    override suspend fun loadProperty(
        property: SearchConsolePropertyUi,
        performanceQuery: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsolePropertyWorkspaceUi> = capture {
        val credential = currentCredential().credential
        val siteUrl = property.siteUrl
        coroutineScope {
            val performance = async {
                loadPerformance(credential, siteUrl, performanceQuery)
            }
            val sitemaps = async {
                dataSource.newSitemapsCall(credential, siteUrl)
                    .executeAwait(networkExecutor)
                    .toSitemapsResourceUi()
            }
            SearchConsolePropertyWorkspaceUi(
                property = property,
                performance = performance.await(),
                sitemaps = sitemaps.await(),
            )
        }
    }

    override suspend fun loadPerformance(
        siteUrl: String,
        query: SearchConsolePerformanceQueryUi,
    ): Result<SearchConsoleResourceUi<SearchConsolePerformanceUi>> = capture {
        loadPerformance(currentCredential().credential, siteUrl, query)
    }

    override suspend fun inspect(
        siteUrl: String,
        inspectionUrl: String,
    ): Result<SearchConsoleInspectionUi> = capture {
        val credential = currentCredential().credential
        when (val result = dataSource.newInspectionCall(credential, inspectionUrl, siteUrl)
            .executeAwait(networkExecutor)
        ) {
            is SearchConsoleFetchResult.Complete -> result.value.toUi(inspectionUrl)
            is SearchConsoleFetchResult.Partial -> result.value.toUi(inspectionUrl)
            is SearchConsoleFetchResult.Failure -> throw SearchConsoleUiException(result.failure.message)
        }
    }

    override suspend fun loadPropertySummaries(
        siteUrls: List<String>,
        onSummary: suspend (SearchConsolePropertySummaryUi) -> Unit,
    ): Result<Unit> = capture {
        val unique = siteUrls.distinct().take(MAXIMUM_VISIBLE_PROPERTIES)
        if (unique.isEmpty()) return@capture
        val endDate = java.time.LocalDate.now(clock)
        // iOS loads property details four at a time; keep one network thread free for detail work.
        unique.chunked(MAXIMUM_CONCURRENT_SUMMARIES).forEach { batch ->
            // Long inventories can outlive an access token, so refresh it between batches.
            val credential = currentCredential().credential
            coroutineScope {
                batch.map { siteUrl ->
                    async {
                        dataSource.newPropertySummaryCall(credential, siteUrl, endDate)
                            .executeAwait(networkExecutor)
                    }
                }.forEach { pending ->
                    when (val result = pending.await()) {
                        is SearchConsoleFetchResult.Complete -> onSummary(result.value.toUi(isPartial = false))
                        is SearchConsoleFetchResult.Partial -> onSummary(result.value.toUi(isPartial = true))
                        is SearchConsoleFetchResult.Failure -> throw SearchConsoleUiException(result.failure.message)
                    }
                }
            }
        }
    }

    override suspend fun disconnect(): Result<Unit> = capture {
        executeAwait(storageExecutor, connectionStore::disconnect)
    }

    private suspend fun loadPerformance(
        credential: SearchConsoleOAuthCredential,
        siteUrl: String,
        requestedQuery: SearchConsolePerformanceQueryUi,
    ): SearchConsoleResourceUi<SearchConsolePerformanceUi> = coroutineScope {
        // Enforce Google's combinations at the boundary too (Hour needs hourly_all, and so on), so a
        // query from any entry point can never be rejected for an avoidable rule.
        val query = requestedQuery.normalizedForGoogle()
        val timelineDimension = if (query.dataState == SearchConsoleDataStateUi.HOURLY_ALL) {
            SearchConsoleDimension.HOUR
        } else {
            SearchConsoleDimension.DATE
        }
        val timeline = async {
            dataSource.newAllAnalyticsCall(
                credential = credential,
                siteUrl = siteUrl,
                query = query.toDataQuery(listOf(timelineDimension), rowLimit = PAGE_ROWS),
                maximumRows = PAGE_ROWS,
            ).executeAwait(networkExecutor)
        }
        val breakdown = async {
            dataSource.newAllAnalyticsCall(
                credential = credential,
                siteUrl = siteUrl,
                // Google pages at 25,000 rows; the data source walks pages up to the 100,000 ceiling.
                query = query.toDataQuery(
                    dimensions = query.dimensions.map(SearchConsoleDimensionUi::toDataDimension),
                    rowLimit = PAGE_ROWS,
                ),
                maximumRows = MAXIMUM_BREAKDOWN_ROWS,
            ).executeAwait(networkExecutor)
        }
        combinePerformance(timeline.await(), breakdown.await(), query)
    }

    private suspend fun currentCredential(): RequestCredential {
        while (true) {
            var saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
                ?: throw SearchConsoleUiException("Connect a Google Search Console account first.")
            if (!saved.connection.credential.needsRefresh(clock.millis())) {
                return RequestCredential(
                    saved.connection.credential,
                    SearchConsoleAccountUi(saved.connection.id, saved.connection.credential.email),
                    saved,
                )
            }
            val refreshed = authorizer.refresh(saved.connection.credential)
            val persisted = executeAwait(storageExecutor) {
                connectionStore.persistRefreshedCredential(saved, refreshed)
            }
            if (persisted) {
                val refreshedRecord = executeAwait(storageExecutor, connectionStore::loadForRefresh)
                    ?: throw SearchConsoleUiException("The Google account was disconnected during refresh.")
                if (refreshedRecord.connection.id == saved.connection.id &&
                    refreshedRecord.connection.credential.accessToken == refreshed.accessToken
                ) {
                    return RequestCredential(
                        refreshed,
                        SearchConsoleAccountUi(saved.connection.id, refreshed.email),
                        refreshedRecord,
                    )
                }
                // A replacement won after the credential CAS. Restart with that exact record.
                continue
            }
            saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
                ?: throw SearchConsoleUiException("The Google account was disconnected during refresh.")
            if (saved.connection.credential.needsRefresh(clock.millis())) {
                throw SearchConsoleUiException("The saved Google credential could not be refreshed. Reconnect the account.")
            }
            return RequestCredential(
                saved.connection.credential,
                SearchConsoleAccountUi(saved.connection.id, saved.connection.credential.email),
                saved,
            )
        }
    }

    private fun SearchConsoleFetchResult<SearchConsoleSnapshot>.valueOrThrow(): SearchConsoleSnapshot =
        when (this) {
            is SearchConsoleFetchResult.Complete -> value
            is SearchConsoleFetchResult.Partial -> value
            is SearchConsoleFetchResult.Failure -> throw SearchConsoleUiException(failure.message)
        }

    private fun SearchConsoleSnapshot.toDashboardUi(
        account: SearchConsoleAccountUi,
        cacheState: SearchConsoleCacheState,
    ): SearchConsoleDashboardUi {
        val visible = properties.take(MAXIMUM_VISIBLE_PROPERTIES)
        return SearchConsoleDashboardUi(
            account = account,
            properties = visible.map(SearchConsoleProperty::toUi),
            loadedPropertyCount = properties.size,
            providerInventoryComplete = propertiesComplete,
            inventoryTruncatedForDisplay = properties.size > visible.size,
            warnings = warnings,
            fetchedAtMillis = fetchedAtMillis,
            cacheState = cacheState,
        )
    }

    companion object {
        const val MAXIMUM_VISIBLE_PROPERTIES = 250
        const val MAXIMUM_BREAKDOWN_ROWS = 100_000
        const val MAXIMUM_CONCURRENT_SUMMARIES = 3
        private const val PAGE_ROWS = 25_000

        fun create(context: Context): NativeSearchConsoleUiGateway {
            val applicationContext = context.applicationContext
            return NativeSearchConsoleUiGateway(
                connectionStore = SearchConsoleConnectionStore(
                    SearchConsoleConnectionRepository.create(applicationContext),
                ),
                dataSource = SearchConsoleDataSource(),
                authorizer = NativeSearchConsoleOAuthAuthorizer(applicationContext),
                networkExecutor = Executors.newFixedThreadPool(4) { runnable ->
                    Thread(runnable, "verceltics-search-console").apply { isDaemon = true }
                },
                storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "verceltics-search-console-storage").apply { isDaemon = true }
                },
            )
        }
    }
}

private data class RequestCredential(
    val credential: SearchConsoleOAuthCredential,
    val account: SearchConsoleAccountUi,
    val source: com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleVersionedConnection,
)

private fun SearchConsoleOAuthCredential.toAccountUi(): SearchConsoleAccountUi =
    SearchConsoleAccountUi(subject ?: email ?: "google-account", email)

private fun SearchConsoleProperty.toUi(): SearchConsolePropertyUi {
    val name = siteUrl.removePrefix("sc-domain:").removeSuffix("/")
    return SearchConsolePropertyUi(
        siteUrl = siteUrl,
        displayName = name.ifBlank { siteUrl },
        permission = permissionLevel
            .removePrefix("site")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
            .ifBlank { permissionLevel }
            .replaceFirstChar { it.uppercase() },
    )
}

internal fun SearchConsolePerformanceQueryUi.toDataQuery(
    dimensions: List<SearchConsoleDimension>,
    rowLimit: Int,
): SearchConsoleAnalyticsQuery = SearchConsoleAnalyticsQuery(
    dateRange = SearchConsoleDateRange(startDate, endDate),
    dimensions = dimensions,
    searchType = searchType.toDataSearchType(),
    dimensionFilterGroups = filters
        .takeIf { it.isNotEmpty() }
        ?.let { selected ->
            listOf(
                SearchConsoleDimensionFilterGroup(
                    selected.map { filter ->
                        SearchConsoleDimensionFilter(
                            dimension = filter.dimension.toDataFilterDimension(),
                            operator = filter.operator.toDataOperator(),
                            expression = filter.googleExpression(),
                        )
                    },
                ),
            )
        }
        .orEmpty(),
    aggregationType = aggregation.toDataAggregation(),
    rowLimit = rowLimit,
    dataState = dataState.toDataState(),
)

internal fun combinePerformance(
    timelineResult: SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>,
    breakdownResult: SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>,
    query: SearchConsolePerformanceQueryUi,
): SearchConsoleResourceUi<SearchConsolePerformanceUi> {
    val timeline = timelineResult.valueOrNull()
    val breakdown = breakdownResult.valueOrNull()
    if (timeline == null && breakdown == null) {
        return SearchConsoleResourceUi.Unavailable(
            listOfNotNull(timelineResult.failureMessage(), breakdownResult.failureMessage())
                .distinct()
                .joinToString(" ")
                .ifBlank { "Search performance is unavailable." },
        )
    }
    val metricRows = timeline?.rows?.takeIf(List<*>::isNotEmpty) ?: breakdown?.rows.orEmpty()
    val clicks = metricRows.sumOf { it.clicks }
    val impressions = metricRows.sumOf { it.impressions }
    val weightedPosition = metricRows.sumOf { it.position * it.impressions }
    val breakdownRows = breakdown?.rows.orEmpty()
    // Reaching the bounded row ceiling is expected for large sites; it gets its own banner (iOS
    // "Showing the first 100,000 rows") instead of reading as a failed request.
    val limitReached = breakdownResult.isLimitReached() ||
        breakdownRows.size >= NativeSearchConsoleUiGateway.MAXIMUM_BREAKDOWN_ROWS
    val timelineError = timelineResult.failureMessage()
    val breakdownError = breakdownResult.failureMessage().takeUnless { breakdownResult.isLimitReached() }
    val warnings = listOfNotNull(timelineError, breakdownError).distinct()
    val metadata = timeline?.metadata ?: breakdown?.metadata
    return SearchConsoleResourceUi.Available(
        value = SearchConsolePerformanceUi(
            clicks = clicks,
            impressions = impressions,
            ctr = if (impressions > 0.0) clicks / impressions else 0.0,
            position = if (impressions > 0.0) weightedPosition / impressions else 0.0,
            timeline = sortSearchConsoleTimeline(
                timeline?.rows.orEmpty().map { row ->
                    SearchConsoleTimelinePointUi(
                        label = row.keys.firstOrNull() ?: "Unknown",
                        clicks = row.clicks,
                        impressions = row.impressions,
                        ctr = row.ctr,
                        position = row.position,
                    )
                },
            ),
            breakdownRows = breakdownRows.map { row ->
                SearchConsoleBreakdownRowUi(
                    keys = row.keys,
                    clicks = row.clicks,
                    impressions = row.impressions,
                    ctr = row.ctr,
                    position = row.position,
                )
            },
            firstIncompleteDate = metadata?.firstIncompleteDate,
            firstIncompleteHour = metadata?.firstIncompleteHour,
            timelineIsHourly = query.dataState == SearchConsoleDataStateUi.HOURLY_ALL,
            timelineAggregationType = timeline?.responseAggregationType,
            breakdownAggregationType = breakdown?.responseAggregationType,
            breakdownLimitReached = limitReached,
            timelineError = timelineError,
            breakdownError = breakdownError,
        ),
        isPartial = warnings.isNotEmpty() || limitReached,
        warning = warnings.joinToString(" ").ifBlank { null },
    )
}

private fun SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>.isLimitReached(): Boolean =
    this is SearchConsoleFetchResult.Partial && failure.kind == SearchConsoleFailureKind.LIMIT_REACHED

private fun SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>.valueOrNull():
    SearchConsoleAnalyticsResponse? = when (this) {
    is SearchConsoleFetchResult.Complete -> value
    is SearchConsoleFetchResult.Partial -> value
    is SearchConsoleFetchResult.Failure -> null
}

private fun SearchConsoleFetchResult<SearchConsoleAnalyticsResponse>.failureMessage(): String? =
    when (this) {
        is SearchConsoleFetchResult.Complete -> null
        is SearchConsoleFetchResult.Partial -> failure.message
        is SearchConsoleFetchResult.Failure -> failure.message
    }

private fun SearchConsoleDimensionUi.toDataDimension(): SearchConsoleDimension = when (this) {
    SearchConsoleDimensionUi.DATE -> SearchConsoleDimension.DATE
    SearchConsoleDimensionUi.HOUR -> SearchConsoleDimension.HOUR
    SearchConsoleDimensionUi.QUERY -> SearchConsoleDimension.QUERY
    SearchConsoleDimensionUi.PAGE -> SearchConsoleDimension.PAGE
    SearchConsoleDimensionUi.COUNTRY -> SearchConsoleDimension.COUNTRY
    SearchConsoleDimensionUi.DEVICE -> SearchConsoleDimension.DEVICE
    SearchConsoleDimensionUi.SEARCH_APPEARANCE -> SearchConsoleDimension.SEARCH_APPEARANCE
}

private fun SearchConsoleDimensionUi.toDataFilterDimension(): SearchConsoleFilterDimension = when (this) {
    SearchConsoleDimensionUi.DATE,
    SearchConsoleDimensionUi.HOUR,
    -> throw SearchConsoleUiException("Date and hour cannot be used as Search Console filters.")
    SearchConsoleDimensionUi.QUERY -> SearchConsoleFilterDimension.QUERY
    SearchConsoleDimensionUi.PAGE -> SearchConsoleFilterDimension.PAGE
    SearchConsoleDimensionUi.COUNTRY -> SearchConsoleFilterDimension.COUNTRY
    SearchConsoleDimensionUi.DEVICE -> SearchConsoleFilterDimension.DEVICE
    SearchConsoleDimensionUi.SEARCH_APPEARANCE -> SearchConsoleFilterDimension.SEARCH_APPEARANCE
}

private fun SearchConsoleFilterOperatorUi.toDataOperator(): SearchConsoleFilterOperator = when (this) {
    SearchConsoleFilterOperatorUi.CONTAINS -> SearchConsoleFilterOperator.CONTAINS
    SearchConsoleFilterOperatorUi.EQUALS -> SearchConsoleFilterOperator.EQUALS
    SearchConsoleFilterOperatorUi.NOT_CONTAINS -> SearchConsoleFilterOperator.NOT_CONTAINS
    SearchConsoleFilterOperatorUi.NOT_EQUALS -> SearchConsoleFilterOperator.NOT_EQUALS
    SearchConsoleFilterOperatorUi.INCLUDING_REGEX -> SearchConsoleFilterOperator.INCLUDING_REGEX
    SearchConsoleFilterOperatorUi.EXCLUDING_REGEX -> SearchConsoleFilterOperator.EXCLUDING_REGEX
}

private fun SearchConsoleSearchTypeUi.toDataSearchType(): SearchConsoleSearchType = when (this) {
    SearchConsoleSearchTypeUi.WEB -> SearchConsoleSearchType.WEB
    SearchConsoleSearchTypeUi.IMAGE -> SearchConsoleSearchType.IMAGE
    SearchConsoleSearchTypeUi.VIDEO -> SearchConsoleSearchType.VIDEO
    SearchConsoleSearchTypeUi.NEWS -> SearchConsoleSearchType.NEWS
    SearchConsoleSearchTypeUi.DISCOVER -> SearchConsoleSearchType.DISCOVER
    SearchConsoleSearchTypeUi.GOOGLE_NEWS -> SearchConsoleSearchType.GOOGLE_NEWS
}

private fun SearchConsoleDataStateUi.toDataState(): SearchConsoleDataState = when (this) {
    SearchConsoleDataStateUi.FINAL -> SearchConsoleDataState.FINAL
    SearchConsoleDataStateUi.ALL -> SearchConsoleDataState.ALL
    SearchConsoleDataStateUi.HOURLY_ALL -> SearchConsoleDataState.HOURLY_ALL
}

private fun SearchConsoleAggregationUi.toDataAggregation(): SearchConsoleAggregationType = when (this) {
    SearchConsoleAggregationUi.AUTO -> SearchConsoleAggregationType.AUTO
    SearchConsoleAggregationUi.BY_PAGE -> SearchConsoleAggregationType.BY_PAGE
    SearchConsoleAggregationUi.BY_PROPERTY -> SearchConsoleAggregationType.BY_PROPERTY
    SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL -> SearchConsoleAggregationType.BY_NEWS_SHOWCASE_PANEL
}

private fun SearchConsoleFetchResult<List<SearchConsoleSitemap>>.toSitemapsResourceUi():
    SearchConsoleResourceUi<List<SearchConsoleSitemapUi>> = when (this) {
    is SearchConsoleFetchResult.Complete -> SearchConsoleResourceUi.Available(value.map(SearchConsoleSitemap::toUi))
    is SearchConsoleFetchResult.Partial -> SearchConsoleResourceUi.Available(
        value = value.map(SearchConsoleSitemap::toUi),
        isPartial = true,
        warning = failure.message,
    )
    is SearchConsoleFetchResult.Failure -> SearchConsoleResourceUi.Unavailable(failure.message)
}

private fun SearchConsoleSitemap.toUi() = SearchConsoleSitemapUi(
    path = path,
    lastSubmitted = lastSubmitted,
    isPending = isPending,
    isIndex = isSitemapsIndex,
    type = type,
    lastDownloaded = lastDownloaded,
    warnings = warnings,
    errors = errors,
    contents = contents.map {
        SearchConsoleSitemapContentUi(it.type, it.submitted, it.indexed)
    },
)

internal fun SearchConsoleUrlInspectionResult.toUi(inspectedUrl: String? = null): SearchConsoleInspectionUi {
    val ampIssues = ampResult?.issues.orEmpty().map { issue ->
        SearchConsoleInspectionIssueUi(
            area = SearchConsoleInspectionAreaUi.AMP,
            title = issue.message ?: issue.type?.let(::searchConsoleHumanized) ?: "AMP issue",
            severity = issue.severity,
            detail = issue.type?.let(::searchConsoleHumanized),
        )
    }
    val mobileIssues = mobileUsabilityResult?.issues.orEmpty().map { issue ->
        SearchConsoleInspectionIssueUi(
            area = SearchConsoleInspectionAreaUi.MOBILE,
            title = issue.message ?: issue.type?.let(::searchConsoleHumanized) ?: "Mobile issue",
            severity = issue.severity,
            detail = issue.type?.let(::searchConsoleHumanized),
        )
    }
    // Every detected type is kept, including types whose items all passed, so counts match iOS.
    val richTypes = richResultsResult?.detectedItems.orEmpty().map { detected ->
        SearchConsoleRichResultTypeUi(
            type = detected.richResultType,
            items = detected.items.map { item ->
                SearchConsoleRichResultItemUi(
                    name = item.name,
                    issues = item.issues.map { issue ->
                        SearchConsoleInspectionIssueUi(
                            area = SearchConsoleInspectionAreaUi.RICH_RESULTS,
                            title = issue.message ?: issue.type ?: "Rich-result issue",
                            severity = issue.severity,
                            detail = listOfNotNull(
                                searchConsoleHumanized(detected.richResultType),
                                item.name,
                            ).distinct().joinToString(" · "),
                        )
                    },
                )
            },
        )
    }
    return SearchConsoleInspectionUi(
        inspectionResultLink = inspectionResultLink,
        verdict = indexStatus?.verdict,
        coverageState = indexStatus?.coverageState,
        indexingState = indexStatus?.indexingState,
        robotsTxtState = indexStatus?.robotsTxtState,
        pageFetchState = indexStatus?.pageFetchState,
        lastCrawlTime = indexStatus?.lastCrawlTime,
        googleCanonical = indexStatus?.googleCanonical,
        userCanonical = indexStatus?.userCanonical,
        crawledAs = indexStatus?.crawledAs,
        sitemaps = indexStatus?.sitemaps.orEmpty(),
        referringUrls = indexStatus?.referringUrls.orEmpty(),
        ampVerdict = ampResult?.verdict,
        mobileVerdict = mobileUsabilityResult?.verdict,
        richResultsVerdict = richResultsResult?.verdict,
        issues = ampIssues + mobileIssues + richTypes.flatMap { type -> type.items.flatMap { it.issues } },
        inspectedUrl = inspectedUrl,
        hasIndexStatus = indexStatus != null,
        amp = ampResult?.let { amp ->
            SearchConsoleAmpInspectionUi(
                ampUrl = amp.ampUrl,
                verdict = amp.verdict,
                indexStatusVerdict = amp.ampIndexStatusVerdict,
                indexingState = amp.indexingState,
                robotsTxtState = amp.robotsTxtState,
                pageFetchState = amp.pageFetchState,
                lastCrawlTime = amp.lastCrawlTime,
                issues = ampIssues,
            )
        },
        hasMobileResult = mobileUsabilityResult != null,
        richResultTypes = richTypes,
        hasRichResults = richResultsResult != null,
    )
}

internal fun SearchConsolePropertySummary.toUi(isPartial: Boolean): SearchConsolePropertySummaryUi =
    SearchConsolePropertySummaryUi(
        siteUrl = siteUrl,
        clicks = clicks,
        impressions = impressions,
        ctr = ctr,
        position = position,
        sitemapCount = sitemapCount,
        indexStatus = indexStatus,
        indexVerdict = indexVerdict,
        lastCrawlTime = lastCrawlTime,
        isPartial = isPartial || metricsArePartial,
        warnings = warnings,
    )

private suspend fun <T> CancelableCall<T>.executeAwait(executor: ExecutorService): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        executor.execute {
            try {
                val value = execute()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: CancellationException) {
                if (continuation.isActive) continuation.cancel(error)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

private suspend fun <T> executeAwait(executor: ExecutorService, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val future = executor.submit {
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

private suspend fun persistValidatedConnectionAwait(
    executor: ExecutorService,
    connectionStore: SearchConsoleConnectionStore,
    credential: SearchConsoleOAuthCredential,
    result: SearchConsoleFetchResult<SearchConsoleSnapshot>,
): com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRecordCommit =
    suspendCancellableCoroutine { continuation ->
        val committed = AtomicReference<com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleRecordCommit?>()
        val cleanupScheduled = AtomicBoolean(false)

        fun scheduleRollback() {
            if (!cleanupScheduled.compareAndSet(false, true)) return
            executor.execute {
                committed.get()?.let { commit ->
                    runCatching { connectionStore.rollbackValidatedConnection(commit) }
                }
            }
        }

        continuation.invokeOnCancellation { scheduleRollback() }
        executor.execute {
            if (!continuation.isActive) return@execute
            try {
                val commit = connectionStore.saveValidatedConnection(credential, result)
                committed.set(commit)
                continuation.resume(commit) { _, _, _ -> scheduleRollback() }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

private suspend inline fun <T> capture(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: SearchConsoleUiException) {
    Result.failure(error)
} catch (_: SecurityException) {
    Result.failure(SearchConsoleUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(SearchConsoleUiException("Google Search Console could not complete this request."))
}
