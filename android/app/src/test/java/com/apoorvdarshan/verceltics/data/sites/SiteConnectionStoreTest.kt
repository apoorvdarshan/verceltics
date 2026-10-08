package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteConnectionStoreTest {
    private val stores = HashMap<SiteProvider, MemoryBytesStore>()
    private val repository = SiteConnectionRepository({ provider -> stores.getOrPut(provider) { MemoryBytesStore() } }, AesGcmTestCipher())
    private var now = 1_000_000L
    private val store = SiteConnectionStore(repository) { now }

    @Test
    fun validatedConnectionRoundTripsEncryptedWithFullSnapshot() {
        store.saveValidatedConnection(
            SiteProvider.PLAUSIBLE,
            "example.com",
            SecretValue.of("plausible-secret-key"),
            mapOf("siteID" to "example.com"),
            snapshot(SiteProvider.PLAUSIBLE, resources = 2),
        )

        val raw = String(checkNotNull(stores.getValue(SiteProvider.PLAUSIBLE).bytes), StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("plausible-secret-key"))
        assertFalse(raw.contains("example.com"))
        val loaded = checkNotNull(store.loadForRequest(SiteProvider.PLAUSIBLE)).connection
        assertEquals("plausible-secret-key", loaded.credential?.use { it })
        assertEquals("example.com", loaded.name)
        assertEquals(mapOf("siteID" to "example.com"), loaded.metadata)
        assertEquals(snapshot(SiteProvider.PLAUSIBLE, resources = 2), loaded.cachedSnapshot)
        assertEquals(1_000_000L, loaded.createdAtMillis)
        assertFalse(loaded.toString().contains("plausible-secret-key"))
    }

    @Test
    fun restoreReportsNotConnectedFreshStaleAndUnreadableStates() {
        assertEquals(SiteRestoreResult.NotConnected, store.restore(SiteProvider.UMAMI))
        store.saveValidatedConnection(SiteProvider.UMAMI, "Umami Cloud", SecretValue.of("k"), emptyMap(), snapshot(SiteProvider.UMAMI, fetchedAt = now))

        val fresh = store.restore(SiteProvider.UMAMI) as SiteRestoreResult.Restored
        assertFalse(fresh.cacheIsStale)
        assertEquals("Umami Cloud", fresh.name)
        now += SiteProvider.UMAMI.snapshotCacheLifetimeMillis
        assertTrue((store.restore(SiteProvider.UMAMI) as SiteRestoreResult.Restored).cacheIsStale)

        stores.getValue(SiteProvider.UMAMI).bytes = byteArrayOf(1, 2, 3)
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore(SiteProvider.UMAMI))

        val locked = SiteConnectionStore(SiteConnectionRepository({ MemoryBytesStore().apply { bytes = byteArrayOf(9) } }, ThrowingCipher()))
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), locked.restore(SiteProvider.CLARITY))
    }

    @Test
    fun recordsAreBoundToTheirProviderSlot() {
        store.saveValidatedConnection(SiteProvider.BING_WEBMASTER, "Bing", SecretValue.of("k"), emptyMap(), snapshot(SiteProvider.BING_WEBMASTER))
        stores.getOrPut(SiteProvider.UPTIME_ROBOT) { MemoryBytesStore() }.bytes = stores.getValue(SiteProvider.BING_WEBMASTER).bytes
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore(SiteProvider.UPTIME_ROBOT))
        assertTrue(store.restore(SiteProvider.BING_WEBMASTER) is SiteRestoreResult.Restored)
    }

    @Test
    fun refreshPersistsOnlyWhileTheSourceRevisionIsCurrent() {
        store.saveValidatedConnection(SiteProvider.CLARITY, "Studio", SecretValue.of("k"), mapOf("projectName" to "Studio"), snapshot(SiteProvider.CLARITY))
        val source = checkNotNull(store.loadForRequest(SiteProvider.CLARITY))

        now = 2_000_000L
        assertTrue(store.persistRefresh(source, "Studio", snapshot(SiteProvider.CLARITY, fetchedAt = now), mapOf("extra" to "1")))
        val refreshed = checkNotNull(store.loadForRequest(SiteProvider.CLARITY)).connection
        assertEquals(now, refreshed.cachedSnapshot?.fetchedAtMillis)
        assertEquals(mapOf("projectName" to "Studio", "extra" to "1"), refreshed.metadata)
        assertEquals(1_000_000L, refreshed.createdAtMillis)
        assertEquals("k", refreshed.credential?.use { it })

        assertFalse(store.persistRefresh(source, "Stale", snapshot(SiteProvider.CLARITY, fetchedAt = 1L)))
        assertEquals(now, checkNotNull(store.loadForRequest(SiteProvider.CLARITY)).connection.cachedSnapshot?.fetchedAtMillis)

        store.disconnect(SiteProvider.CLARITY)
        assertFalse(store.persistRefresh(source, "Gone", snapshot(SiteProvider.CLARITY)))
        assertNull(store.loadForRequest(SiteProvider.CLARITY))
    }

    @Test
    fun reconnectingKeepsTheOriginalCreationTime() {
        store.saveValidatedConnection(SiteProvider.BETTER_STACK, "A", SecretValue.of("one"), emptyMap(), snapshot(SiteProvider.BETTER_STACK))
        now = 5_000_000L
        store.saveValidatedConnection(SiteProvider.BETTER_STACK, "B", SecretValue.of("two"), emptyMap(), snapshot(SiteProvider.BETTER_STACK))
        val loaded = checkNotNull(store.loadForRequest(SiteProvider.BETTER_STACK)).connection
        assertEquals(1_000_000L, loaded.createdAtMillis)
        assertEquals(5_000_000L, loaded.updatedAtMillis)
        assertEquals("two", loaded.credential?.use { it })
    }

    @Test
    fun googleConnectionsStoreNoCredentialButApiKeyProvidersMust() {
        store.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, "Google Analytics · 1 property", null,
            mapOf("googleSubject" to "sub", "googleEmail" to "a@example.com"), snapshot(SiteProvider.GOOGLE_ANALYTICS),
        )
        assertNull(checkNotNull(store.loadForRequest(SiteProvider.GOOGLE_ANALYTICS)).connection.credential)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveValidatedConnection(SiteProvider.UMAMI, "Umami", null, emptyMap(), snapshot(SiteProvider.UMAMI))
        }
    }

    @Test
    fun offlineCacheIsBoundedAndDisclosed() {
        store.saveValidatedConnection(
            SiteProvider.UPTIME_ROBOT, "UptimeRobot · 300 monitors", SecretValue.of("k"), emptyMap(),
            snapshot(SiteProvider.UPTIME_ROBOT, resources = 300),
        )
        val cached = checkNotNull(store.loadForRequest(SiteProvider.UPTIME_ROBOT)).connection.cachedSnapshot
        assertEquals(SiteConnectionStore.MAX_CACHED_RESOURCES, cached?.resources?.size)
        assertTrue(cached!!.warnings.any { it.contains("cache is intentionally bounded") })
    }

    @Test
    fun oversizedCachesShrinkUntilTheEnvelopeFits() {
        val huge = "x".repeat(2_000)
        val resources = (0 until 200).map { index ->
            SiteResource(
                id = "r$index",
                provider = SiteProvider.GOOGLE_ANALYTICS,
                name = "Resource $index",
                metadata = (0 until 30).associate { "key$it" to huge },
            )
        }
        store.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, "GA", null, emptyMap(),
            SiteSnapshot(SiteProvider.GOOGLE_ANALYTICS, resources, fetchedAtMillis = now),
        )
        val cached = checkNotNull(store.loadForRequest(SiteProvider.GOOGLE_ANALYTICS)).connection.cachedSnapshot!!
        assertTrue(cached.resources.size in 1 until 200)
        assertTrue(cached.warnings.any { it.contains("intentionally bounded") })
    }

    @Test
    fun detailBudgetBoundsRowsAndRawBytesAndKeepsSmallPayloadsIntact() {
        val small = SiteDetailPayload(
            provider = SiteProvider.PLAUSIBLE,
            resourceId = "a",
            title = "A",
            tables = listOf(SiteDetailTable("t", "T", listOf("x"), listOf(mapOf("x" to ProviderJsonValue.Num.of(1))))),
            rawResponses = mapOf("r" to ProviderJsonParser.parse("""{"a":[1,2,3]}""")),
            fetchedAtMillis = 1,
        )
        assertEquals(small, SiteDetailSupport.boundedForDevice(small))

        val rows = List(25_000) { mapOf("x" to ProviderJsonValue.Num.of(it)) }
        val bigRaw = ProviderJsonValue.from(List(20_000) { mapOf("value" to "some text $it") })
        val bounded = SiteDetailSupport.boundedForDevice(
            small.copy(tables = listOf(SiteDetailTable("t", "T", listOf("x"), rows)), rawResponses = mapOf("big" to bigRaw)),
        )
        assertEquals(20_000, bounded.tables.single().rows.size)
        assertTrue(bounded.tables.single().nextCursor!!.contains("device limit"))
        assertTrue(bounded.rawResponses.getValue("big").arrayValue!!.size < 20_000)
        assertTrue(bounded.warnings.contains(SiteDetailSupport.MEMORY_LIMIT_WARNING))
    }

    @Test
    fun detailNormalizationHelpersMatchIos() {
        val value = ProviderJsonParser.parse("""{"a":{"b":{"c":{"d":1}}},"list":[1,2],"plain":"x"}""")
        assertEquals(listOf("a.b.c", "list", "plain"), SiteDetailSupport.flattenedFields(value, 3).map { it.key })
        assertEquals(setOf("a.b.c.d", "list", "plain"), SiteDetailSupport.flattenedObject(value).keys)
        assertEquals(mapOf("value" to ProviderJsonValue.Num.of(5)), SiteDetailSupport.flattenedObject(ProviderJsonValue.Num.of(5)))
        assertEquals(2, SiteDetailSupport.rows(ProviderJsonParser.parse("[{\"x\":1},3]")).size)
        assertEquals(ProviderJsonValue.Num.of(1), SiteDetailSupport.rows(ProviderJsonParser.parse("[{\"x\":1},3]"))[1]["index"])
        assertTrue(SiteDetailSupport.rows(ProviderJsonValue.Null).isEmpty())
        assertEquals(listOf("Date", "a", "b"), SiteDetailSupport.orderedColumns(listOf(mapOf("b" to ProviderJsonValue.Null, "Date" to ProviderJsonValue.Null, "a" to ProviderJsonValue.Null)), listOf("Date", "missing")))
        assertEquals(listOf(4, 4, 3), SiteDetailSupport.distributedLimits(11, 3))
        assertTrue(SiteDetailSupport.distributedLimits(5, 0).isEmpty())
        assertEquals("insight-1", SiteDetailSupport.slug(" Insight #1 "))
    }

    private fun snapshot(provider: SiteProvider, resources: Int = 1, fetchedAt: Long = 1_000L) = SiteSnapshot(
        provider = provider,
        resources = (0 until resources).map { index ->
            SiteResource(
                id = "resource-$index",
                provider = provider,
                name = "Resource $index",
                subtitle = "https://r$index.example",
                url = "https://r$index.example",
                status = "Up",
                updatedAtMillis = 99L,
                metrics = listOf(SiteMetric("m.$index", "Metric", index.toDouble(), SiteMetricUnit.PERCENT, "$index%", "resource-$index")),
                metadata = mapOf("index" to index.toString()),
            )
        },
        metrics = listOf(SiteMetric("total", "Total", resources.toDouble(), SiteMetricUnit.COUNT)),
        status = "Connected",
        fetchedAtMillis = fetchedAt,
        warnings = listOf("note"),
    )

    private class ThrowingCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload = throw SecurityException("locked")

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray = throw SecurityException("locked")
    }
}
