package com.apoorvdarshan.verceltics.data.hosting

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingPaginationTest {
    @Test
    fun railwayGuardRequiresAndDeduplicatesContinuationCursors() {
        // iOS testRailwayPaginationRequiresAndDeduplicatesContinuationCursors
        val pagination = RailwayPaginationGuard()
        assertEquals("next-page", pagination.continuation(hasNextPage = true, endCursor = "next-page"))
        assertThrows(HostingApiException::class.java) { pagination.continuation(hasNextPage = true, endCursor = "next-page") }

        val missingCursor = RailwayPaginationGuard()
        assertThrows(HostingApiException::class.java) { missingCursor.continuation(hasNextPage = true, endCursor = null) }
        assertThrows(HostingApiException::class.java) { RailwayPaginationGuard().continuation(true, "") }
        assertNull(missingCursor.continuation(hasNextPage = false, endCursor = null))
    }

    @Test
    fun railwayGuardEnforcesMaximumPages() {
        // iOS testRailwayPaginationEnforcesMaximumPages
        val pagination = RailwayPaginationGuard(maximumPages = 1)
        assertEquals("one", pagination.continuation(hasNextPage = true, endCursor = "one"))
        val error = assertThrows(HostingApiException::class.java) {
            pagination.continuation(hasNextPage = false, endCursor = null)
        }
        assertEquals(HostingFailureKind.INVALID_RESPONSE, error.failure.kind)
        assertEquals("Railway pagination exceeded 1 pages.", error.failure.message)
    }

    @Test
    fun railwayConnectionFollowsCursorsAndDropsDuplicateNodes() = runBlocking {
        val cursors = mutableListOf<String?>()
        val nodes = collectRailwayConnection { cursor ->
            cursors += cursor
            when (cursor) {
                null -> connection(listOf("""{"id":"p1"}""", """{"id":"p2"}"""), hasNext = true, endCursor = "c1")
                "c1" -> connection(listOf("""{"id":"p2"}""", """{"id":"p3"}"""), hasNext = false, endCursor = null)
                else -> error("unexpected cursor")
            }
        }
        assertEquals(listOf(null, "c1"), cursors)
        assertEquals(listOf("p1", "p2", "p3"), nodes.map { it.string("id") })
    }

    @Test
    fun numberedPagesStopOnShortPageAndRejectRepeatedPages() = runBlocking {
        val pages = mapOf(
            1 to listOf("""{"id":1}""", """{"id":2}"""),
            2 to listOf("""{"id":3}"""),
        )
        val requested = mutableListOf<Int>()
        val items = collectNumberedPages(pageSize = 2) { page ->
            requested += page
            pages.getValue(page).map(HostingJson::parse)
        }
        assertEquals(listOf(1, 2), requested)
        assertEquals(3, items.size)

        val repeated = assertThrows(HostingApiException::class.java) {
            runBlocking {
                collectNumberedPages(pageSize = 2) { listOf(HostingJson.parse("""{"id":1}"""), HostingJson.parse("""{"id":2}""")) }
            }
        }
        assertEquals("Pagination repeated a page without returning new items.", repeated.failure.message)

        val bounded = assertThrows(HostingApiException::class.java) {
            runBlocking {
                collectNumberedPages(pageSize = 1, maximumPages = 3) { page -> listOf(HostingJson.parse("""{"id":$page}""")) }
            }
        }
        assertEquals("Pagination exceeded 3 pages.", bounded.failure.message)
    }

    @Test
    fun liveValuesKeepOrderBoundConcurrencyAndTurnFailuresIntoUnknown() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val values = fetchLiveValues((1..10).map { "app-$it" }, maximumConcurrent = 3) { id ->
            val now = active.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            delay(10)
            active.decrementAndGet()
            if (id == "app-4") throw IOException("denied")
            id.uppercase()
        }
        assertEquals(10, values.size)
        assertEquals("APP-1", values[0])
        assertNull(values[3])
        assertEquals("APP-10", values[9])
        assertTrue(peak.get() <= 3)
    }

    @Test
    fun liveValuesPropagateCancellation() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                fetchLiveValues(listOf("a")) { throw CancellationException("stop") }
            }
        }
    }

    private suspend fun fetchLiveValues(ids: List<String>, fetch: suspend (String) -> String) =
        fetchLiveValues(ids, 2, fetch)

    private fun connection(nodes: List<String>, hasNext: Boolean, endCursor: String?): JsonObject = HostingJson.parse(
        """{"edges":[${nodes.joinToString(",") { """{"node":$it}""" }}],
           "pageInfo":{"hasNextPage":$hasNext,"endCursor":${endCursor?.let { "\"$it\"" } ?: "null"}}}""",
    ).asObject()
}
