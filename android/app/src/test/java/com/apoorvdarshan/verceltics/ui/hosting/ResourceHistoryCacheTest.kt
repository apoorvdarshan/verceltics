package com.apoorvdarshan.verceltics.ui.hosting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceHistoryCacheTest {
    @Test
    fun entriesAreFreshForExactlyOneHundredEightySeconds() {
        var now = 10_000L
        val cache = ResourceHistoryCache<String>(nowMillis = { now })
        val key = ResourceHistoryCache.key("render", "acct", "srv-1")

        assertNull(cache.get(key))
        cache.put(key, "history")
        assertTrue(cache.get(key)!!.isFresh)

        now += ResourceHistoryCache.LIFETIME_MILLIS - 1
        assertTrue(cache.get(key)!!.isFresh)

        now += 1
        val stale = cache.get(key)!!
        assertFalse(stale.isFresh)
        assertEquals("history", stale.value)
        assertEquals(180_000L, ResourceHistoryCache.LIFETIME_MILLIS)
    }

    @Test
    fun invalidationIsScopedToProviderAndAccount() {
        val cache = ResourceHistoryCache<String>(nowMillis = { 0L })
        cache.put(ResourceHistoryCache.key("render", "a", "1"), "a1")
        cache.put(ResourceHistoryCache.key("render", "b", "1"), "b1")
        cache.put(ResourceHistoryCache.key("railway", "a", "1"), "r1")

        cache.invalidate(ResourceHistoryCache.key("render", "a", "1"))
        assertNull(cache.get(ResourceHistoryCache.key("render", "a", "1")))

        cache.invalidateScope(ResourceHistoryCache.scope("render"))
        assertNull(cache.get(ResourceHistoryCache.key("render", "b", "1")))
        assertEquals("r1", cache.get(ResourceHistoryCache.key("railway", "a", "1"))?.value)
    }

    @Test
    fun leastRecentlyUsedEntriesAreEvictedToBoundMemory() {
        val cache = ResourceHistoryCache<Int>(nowMillis = { 0L }, maximumEntries = 3)
        (1..3).forEach { cache.put("k$it", it) }
        cache.get("k1")
        cache.put("k4", 4)

        assertEquals(3, cache.size)
        assertNull(cache.get("k2"))
        assertEquals(1, cache.get("k1")?.value)
        assertEquals(4, cache.get("k4")?.value)
    }
}
