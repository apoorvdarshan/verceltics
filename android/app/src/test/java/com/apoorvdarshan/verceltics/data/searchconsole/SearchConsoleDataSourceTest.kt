package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SearchConsoleDataSourceTest {
    @Test
    fun propertyParserDropsAreTruthfullyReportedAsPartial() {
        val api = FakeApi().apply {
            propertyList = SearchConsolePropertyList(listOf(property(1)), skippedEntries = 2)
        }
        val result = SearchConsoleDataSource(api, nowMillis = { 500L })
            .newPropertiesCall(credential())
            .execute() as SearchConsoleFetchResult.Partial

        val snapshot = result.value
        assertEquals(1, snapshot.properties.size)
        assertEquals(500L, snapshot.fetchedAtMillis)
        assertTrue(snapshot.warnings.single().contains("2 malformed"))
    }

    @Test
    fun analyticsPaginatesByStartRowAndReturnsCompleteAtNaturalEnd() {
        val api = FakeApi().apply {
            analyticsPages += SearchConsoleAnalyticsResponse(List(2) { row("a$it") }, "byPage", null)
            analyticsPages += SearchConsoleAnalyticsResponse(listOf(row("b")), "byPage", null)
        }
        val query = query(rowLimit = 2)
        val result = SearchConsoleDataSource(api)
            .newAllAnalyticsCall(credential(), "sc-domain:example.com", query, maximumRows = 10)
            .execute() as SearchConsoleFetchResult.Complete

        assertEquals(3, result.value.rows.size)
        assertEquals(listOf(0, 2), api.analyticsOffsets)
    }

    @Test
    fun analyticsAggregateOverSinglePageLimitCanCompleteTruthfully() {
        val api = FakeApi().apply {
            analyticsPages += SearchConsoleAnalyticsResponse(
                List(MAX_ANALYTICS_ROWS_PER_PAGE) { row("first-$it") },
                "byPage",
                null,
            )
            analyticsPages += SearchConsoleAnalyticsResponse(listOf(row("last")), "byPage", null)
        }
        val result = SearchConsoleDataSource(api)
            .newAllAnalyticsCall(
                credential(),
                "sc-domain:example.com",
                query(MAX_ANALYTICS_ROWS_PER_PAGE),
            )
            .execute() as SearchConsoleFetchResult.Complete

        assertEquals(MAX_ANALYTICS_ROWS_PER_PAGE + 1, result.value.rows.size)
        assertEquals(listOf(0, MAX_ANALYTICS_ROWS_PER_PAGE), api.analyticsOffsets)
    }

    @Test
    fun analyticsAggregateOverSinglePageLimitCanReturnBoundedPartial() {
        val api = FakeApi().apply {
            analyticsPages += SearchConsoleAnalyticsResponse(
                List(MAX_ANALYTICS_ROWS_PER_PAGE) { row("first-$it") },
                null,
                null,
            )
            analyticsPages += SearchConsoleAnalyticsResponse(
                List(MAX_ANALYTICS_ROWS_PER_PAGE) { row("second-$it") },
                null,
                null,
            )
        }
        val aggregateLimit = MAX_ANALYTICS_ROWS_PER_PAGE + 5_000
        val result = SearchConsoleDataSource(api)
            .newAllAnalyticsCall(
                credential(),
                "sc-domain:example.com",
                query(MAX_ANALYTICS_ROWS_PER_PAGE),
                maximumRows = aggregateLimit,
            )
            .execute() as SearchConsoleFetchResult.Partial

        assertEquals(aggregateLimit, result.value.rows.size)
        assertEquals(SearchConsoleFailureKind.LIMIT_REACHED, result.failure.kind)
        assertEquals(listOf(0, MAX_ANALYTICS_ROWS_PER_PAGE), api.analyticsOffsets)
    }

    @Test
    fun laterPageFailureReturnsPartialButFirstPageFailureReturnsFailure() {
        val later = FakeApi().apply {
            analyticsPages += SearchConsoleAnalyticsResponse(List(2) { row("a$it") }, null, null)
            failAnalyticsAtCall = 2
        }
        val partial = SearchConsoleDataSource(later)
            .newAllAnalyticsCall(credential(), "sc-domain:example.com", query(2), 10)
            .execute() as SearchConsoleFetchResult.Partial
        assertEquals(2, partial.value.rows.size)
        assertEquals(SearchConsoleFailureKind.NETWORK, partial.failure.kind)

        val first = FakeApi().apply { failAnalyticsAtCall = 1 }
        val failure = SearchConsoleDataSource(first)
            .newAllAnalyticsCall(credential(), "sc-domain:example.com", query(2), 10)
            .execute() as SearchConsoleFetchResult.Failure
        assertEquals(SearchConsoleFailureKind.NETWORK, failure.failure.kind)
    }

    @Test
    fun exactBoundIsMarkedPartialBecauseMoreRowsMayExist() {
        val api = FakeApi().apply {
            analyticsPages += SearchConsoleAnalyticsResponse(List(2) { row("a$it") }, null, null)
        }
        val result = SearchConsoleDataSource(api)
            .newAllAnalyticsCall(credential(), "sc-domain:example.com", query(2), maximumRows = 2)
            .execute() as SearchConsoleFetchResult.Partial

        assertEquals(SearchConsoleFailureKind.LIMIT_REACHED, result.failure.kind)
        assertEquals(2, result.value.rows.size)
    }

    @Test
    fun cancellingParentCancelsActiveChildAndPropagatesCancellation() {
        val blocking = BlockingCall<SearchConsolePropertyList>()
        val api = FakeApi().apply { propertyCall = blocking }
        val call = SearchConsoleDataSource(api).newPropertiesCall(credential())
        val thrown = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                call.execute()
            } catch (error: Throwable) {
                thrown.set(error)
            }
        }
        thread.start()
        assertTrue(blocking.started.await(2, TimeUnit.SECONDS))
        call.cancel()
        thread.join(2_000)

        assertTrue(blocking.cancelled)
        assertTrue(thrown.get() is CancellationException)
    }

    private fun query(rowLimit: Int) = SearchConsoleAnalyticsQuery(
        SearchConsoleDateRange("2026-08-01", "2026-08-27"),
        rowLimit = rowLimit,
    )

    private fun row(key: String) = SearchConsoleAnalyticsRow(
        listOf(key), 1.0, 2.0, 0.5, 3.0,
    )

    private fun property(index: Int) = SearchConsoleProperty("sc-domain:example$index.com", "siteOwner")

    private fun credential() = SearchConsoleOAuthCredential(
        SecretValue.of("access"), SecretValue.of("refresh"), "Bearer",
        SearchConsoleOAuthCredential.REQUIRED_SCOPES, Long.MAX_VALUE / 2, null, null,
    )

    private class FakeApi : SearchConsoleReadApi {
        var propertyList = SearchConsolePropertyList(emptyList())
        var propertyCall: CancelableCall<SearchConsolePropertyList>? = null
        val analyticsPages = mutableListOf<SearchConsoleAnalyticsResponse>()
        val analyticsOffsets = mutableListOf<Int>()
        var failAnalyticsAtCall: Int? = null

        override fun newListVerifiedPropertiesCall(
            credential: SearchConsoleOAuthCredential,
        ): CancelableCall<SearchConsolePropertyList> = propertyCall ?: valueCall(propertyList)

        override fun newAnalyticsPageCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            query: SearchConsoleAnalyticsQuery,
        ): CancelableCall<SearchConsoleAnalyticsResponse> {
            analyticsOffsets += query.startRow
            val callNumber = analyticsOffsets.size
            if (failAnalyticsAtCall == callNumber) return failingCall(IOException("offline"))
            return valueCall(analyticsPages.removeAt(0))
        }

        override fun newListSitemapsCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            sitemapIndex: String?,
        ) = valueCall(emptyList<SearchConsoleSitemap>())

        override fun newGetSitemapCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            feedPath: String,
        ) = valueCall(
            SearchConsoleSitemap(feedPath, null, false, false, null, null, 0, 0, emptyList()),
        )

        override fun newInspectUrlCall(
            credential: SearchConsoleOAuthCredential,
            inspectionUrl: String,
            siteUrl: String,
            languageCode: String,
        ) = valueCall(SearchConsoleUrlInspectionResult(null, null, null, null, null))
    }

    private class BlockingCall<T> : CancelableCall<T> {
        val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        var cancelled = false
        override fun execute(): T {
            started.countDown()
            released.await(2, TimeUnit.SECONDS)
            throw CancellationException("cancelled")
        }
        override fun cancel() {
            cancelled = true
            released.countDown()
        }
    }

    companion object {
        private fun <T> valueCall(value: T): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = value
            override fun cancel() = Unit
        }

        private fun <T> failingCall(error: Exception): CancelableCall<T> = object : CancelableCall<T> {
            override fun execute(): T = throw error
            override fun cancel() = Unit
        }
    }
}
