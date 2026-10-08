package com.apoorvdarshan.verceltics.ui.vercel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelTimedCacheTest {
    private var now = 10_000_000L

    @Test
    fun windowsMatchTheIosRefreshPolicy() {
        assertEquals(15 * 60 * 1_000L, VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS)
        assertEquals(10 * 60 * 1_000L, VercelRefreshPolicy.REPORT_FRESHNESS_MILLIS)
        assertEquals(2 * 60 * 1_000L, VercelRefreshPolicy.EVENTS_FRESHNESS_MILLIS)
        assertEquals(32, VercelRefreshPolicy.CACHE_ENTRY_LIMIT)
    }

    @Test
    fun entriesStayFreshForExactlyTheirLifetime() {
        val cache = VercelTimedCache<String, String>(lifetimeMillis = VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS, nowMillis = { now })
        val entry = cache.put("projects", "cached")

        now += VercelRefreshPolicy.INVENTORY_FRESHNESS_MILLIS - 1
        assertTrue(cache.isFresh(entry))

        now += 1
        assertFalse("Stale at fifteen minutes; the value stays readable for display.", cache.isFresh(entry))
        assertEquals("cached", cache["projects"]?.value)
    }

    @Test
    fun clockSkewIntoThePastIsNotFresh() {
        assertFalse(VercelRefreshPolicy.isFresh(updatedAtMillis = 2_000L, nowMillis = 1_000L, lifetimeMillis = 10_000L))
        assertFalse(VercelRefreshPolicy.isFresh(updatedAtMillis = null, nowMillis = 1_000L, lifetimeMillis = 10_000L))
        assertTrue(VercelRefreshPolicy.isFresh(updatedAtMillis = 1_000L, nowMillis = 1_000L, lifetimeMillis = 10_000L))
    }

    @Test
    fun leastRecentlyUsedEntriesAreEvictedAtTheLimit() {
        val cache = VercelTimedCache<Int, String>(lifetimeMillis = 1_000L, limit = 2, nowMillis = { now })
        cache.put(1, "one")
        cache.put(2, "two")
        cache[1]
        cache.put(3, "three")

        assertEquals(2, cache.size)
        assertEquals("one", cache[1]?.value)
        assertNull(cache[2])
        assertEquals("three", cache[3]?.value)

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun projectsFirstLoadLatchFiresOncePerProcess() {
        VercelProjectsFirstLoad.resetForTesting()

        assertTrue(VercelProjectsFirstLoad.consume())
        assertFalse(VercelProjectsFirstLoad.consume())
        assertFalse(VercelProjectsFirstLoad.consume())

        VercelProjectsFirstLoad.resetForTesting()
    }
}
