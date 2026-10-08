package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.sites.AesGcmTestCipher
import com.apoorvdarshan.verceltics.data.sites.MemoryBytesStore
import com.apoorvdarshan.verceltics.data.sites.SiteAccountEntry
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSpeedConnectionRepositoryTest {
    private val files = HashMap<String, MemoryBytesStore>()
    private val cipher = AesGcmTestCipher()
    private var now = 1_000L
    private val repository = PageSpeedConnectionRepository(::file, cipher)
    private val store = PageSpeedConnectionStore(repository) { now }

    private fun file(path: String): MemoryBytesStore = files.getOrPut(path) { MemoryBytesStore() }

    @Test
    fun encryptedSiteRecordRoundTripsCredentialAndOfflineSnapshot() {
        val connection = testConnection(apiKey = "sensitive-google-key")
        val commit = save(connection)

        val encrypted = checkNotNull(file(PageSpeedConnectionRepository.recordPath(commit.connectionId)).bytes)
        assertFalse(String(encrypted, StandardCharsets.ISO_8859_1).contains("sensitive-google-key"))
        val loaded = checkNotNull(repository.load(commit.connectionId))
        assertEquals(connection.credentials.siteUrl, loaded.credentials.siteUrl)
        assertEquals(connection.credentials.apiKey, loaded.credentials.apiKey)
        assertEquals(connection.cachedSnapshot, loaded.cachedSnapshot)
        assertFalse(loaded.toString().contains("sensitive-google-key"))
        assertEquals(
            SiteAccountIndex(listOf(SiteAccountEntry(commit.connectionId, "example.com/path", "example.com")), commit.connectionId),
            store.accounts(),
        )
    }

    @Test
    fun recordsUseProviderSpecificPathsAndAuthenticatedContext() {
        assertEquals("accounts/pagespeed-crux-api-key.account", PageSpeedConnectionRepository.ACCOUNT_PATH)
        assertEquals("verceltics.account-envelope.v1:pagespeed-crux", PageSpeedConnectionRepository.ASSOCIATED_DATA)
        assertEquals("accounts/pagespeed-crux/accounts.index", PageSpeedConnectionRepository.INDEX_PATH)
        assertEquals("accounts/pagespeed-crux/a-1.account", PageSpeedConnectionRepository.recordPath("a-1"))
        assertFalse(PageSpeedConnectionRepository.ACCOUNT_PATH.contains("vercel-personal-token"))
        assertFalse(PageSpeedConnectionRepository.ASSOCIATED_DATA.endsWith(":vercel"))

        val first = save(testConnection("k1", site = "https://one.example/"))
        val second = save(testConnection("k2", site = "https://two.example/"))
        file(PageSpeedConnectionRepository.recordPath(second.connectionId)).bytes =
            file(PageSpeedConnectionRepository.recordPath(first.connectionId)).bytes
        assertTrue(store.restore() is PageSpeedRestoreResult.Unavailable)
    }

    @Test
    fun restoreIsOfflineAndKeepsLastSnapshotWhenRefreshFails() {
        val original = testConnection(apiKey = "key")
        val commit = save(original)
        now = 2_000_000L

        val restored = store.restore() as PageSpeedRestoreResult.Restored
        assertEquals(commit.connectionId, restored.connectionId)
        assertEquals(original.cachedSnapshot, restored.cachedSnapshot)
        assertTrue(restored.cacheIsStale)
        assertEquals(listOf(commit.connectionId), restored.accounts.ids)

        val persisted = store.persistRefreshResult(
            checkNotNull(store.loadForRefresh()),
            PageSpeedFetchResult.Failure(PageSpeedFailure(PageSpeedFailureKind.NETWORK, "Provider unavailable.")),
        )
        assertFalse(persisted)
        assertEquals(original.cachedSnapshot, repository.load(commit.connectionId)?.cachedSnapshot)
    }

    @Test
    fun unreadableSavedRecordIsNotMisreportedAsDisconnected() {
        val commit = save(testConnection("key"))
        file(PageSpeedConnectionRepository.recordPath(commit.connectionId)).bytes = byteArrayOf(9, 8, 7)

        val unavailable = store.restore() as PageSpeedRestoreResult.Unavailable
        assertEquals(PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE, unavailable.problem)
        assertEquals(listOf(commit.connectionId), unavailable.accounts.ids)
        assertEquals(3, file(PageSpeedConnectionRepository.recordPath(commit.connectionId)).bytes?.size)
    }

    @Test
    fun cancelledCommitRollbackCannotDeleteANewerEncryptedRevision() {
        val first = testConnection(apiKey = "first-key")
        val second = testConnection(apiKey = "second-key")

        val firstCommit = store.saveValidatedConnection(first.credentials, PageSpeedFetchResult.Complete(checkNotNull(first.cachedSnapshot)))
        store.acceptValidatedConnection(firstCommit)
        // The same URL rotates its key in place.
        val secondCommit = store.saveValidatedConnection(second.credentials, PageSpeedFetchResult.Complete(checkNotNull(second.cachedSnapshot)))
        assertEquals(firstCommit.connectionId, secondCommit.connectionId)

        assertFalse(store.rollbackValidatedConnection(firstCommit))
        assertEquals(second.credentials.apiKey, repository.load(secondCommit.connectionId)?.credentials?.apiKey)
        assertTrue(store.rollbackValidatedConnection(secondCommit))
        assertEquals(first.credentials.apiKey, repository.load(firstCommit.connectionId)?.credentials?.apiKey)
        assertEquals(1, store.accounts().accounts.size)
    }

    @Test
    fun cancelledNewSiteRollbackRemovesOnlyThatSite() {
        val kept = save(testConnection("kept", site = "https://kept.example/"))
        val priorIndex = checkNotNull(file(PageSpeedConnectionRepository.INDEX_PATH).bytes).copyOf()
        val added = testConnection("added", site = "https://added.example/")
        val commit = store.saveValidatedConnection(added.credentials, PageSpeedFetchResult.Complete(checkNotNull(added.cachedSnapshot)))
        assertNotEquals(kept.connectionId, commit.connectionId)

        assertTrue(store.rollbackValidatedConnection(commit))
        assertNull(file(PageSpeedConnectionRepository.recordPath(commit.connectionId)).bytes)
        assertArrayEquals(priorIndex, file(PageSpeedConnectionRepository.INDEX_PATH).bytes)
        assertEquals(kept.connectionId, (store.restore() as PageSpeedRestoreResult.Restored).connectionId)
    }

    @Test
    fun acceptedCommitMakesALateCancellationRollbackANoOp() {
        val connection = testConnection(apiKey = "accepted-key")
        val commit = store.saveValidatedConnection(connection.credentials, PageSpeedFetchResult.Complete(checkNotNull(connection.cachedSnapshot)))

        store.acceptValidatedConnection(commit)

        assertFalse(store.rollbackValidatedConnection(commit))
        assertEquals(connection.credentials.apiKey, repository.load(commit.connectionId)?.credentials?.apiKey)
        assertEquals(connection.cachedSnapshot, repository.load(commit.connectionId)?.cachedSnapshot)
    }

    @Test
    fun severalSitesCanBeSavedSwitchedRefreshedAndRemoved() {
        val one = save(testConnection("k1", site = "https://one.example/"))
        val two = save(testConnection("k2", site = "https://two.example/"))
        assertEquals(listOf(one.connectionId, two.connectionId), store.accounts().ids)
        assertEquals(two.connectionId, store.accounts().activeId)

        store.switchAccount(one.connectionId)
        assertEquals("https://one.example/", (store.restore() as PageSpeedRestoreResult.Restored).siteUrl.toASCIIString())

        // A refresh writes only to the site it was fetched for, even after a switch.
        val source = checkNotNull(store.loadForRefresh())
        store.switchAccount(two.connectionId)
        val refreshed = snapshot(source.connection.credentials.siteUrl.toASCIIString(), fetchedAt = 777L)
        assertTrue(store.persistRefreshResult(source, PageSpeedFetchResult.Complete(refreshed)))
        assertEquals(777L, repository.load(one.connectionId)?.cachedSnapshot?.fetchedAtMillis)
        assertEquals(100L, repository.load(two.connectionId)?.cachedSnapshot?.fetchedAtMillis)
        assertFalse(store.persistRefreshResult(source, PageSpeedFetchResult.Complete(refreshed)))

        val remaining = store.removeAccount(two.connectionId)
        assertEquals(listOf(one.connectionId), remaining.ids)
        assertEquals(one.connectionId, remaining.activeId)

        save(testConnection("k3", site = "https://three.example/"))
        assertEquals(2, store.removeAllAccounts().size)
        assertEquals(PageSpeedRestoreResult.NotConnected, store.restore())
    }

    @Test
    fun legacySingleSiteRecordMigratesLosslesslyAsTheActiveSite() {
        writeLegacy(testConnection("legacy-key").copy(id = "6f1f8f1e-0a61-4b19-9d1c-2f6c1b0c2d3e"))

        val restored = store.restore() as PageSpeedRestoreResult.Restored

        assertEquals("6f1f8f1e-0a61-4b19-9d1c-2f6c1b0c2d3e", restored.connectionId)
        assertEquals("https://example.com/path", restored.siteUrl.toASCIIString())
        assertEquals(testConnection("x").cachedSnapshot, restored.cachedSnapshot)
        assertEquals(listOf(restored.connectionId), restored.accounts.ids)
        val loaded = checkNotNull(store.loadForRefresh()).connection
        assertEquals("legacy-key", loaded.credentials.apiKey.use { it })
        assertEquals(10L, loaded.createdAtMillis)
        assertEquals(100L, loaded.updatedAtMillis)
        assertNull(file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes)
    }

    @Test
    fun legacyIdThatIsNotAValidAccountIdGetsTheDeterministicMigratedId() {
        writeLegacy(testConnection("legacy-key"))
        val legacy = checkNotNull(file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes).copyOf()

        val first = store.accounts()
        assertEquals(listOf(SiteAccountIds.migrated("pagespeed-crux")), first.ids)

        // An interrupted migration repeats into the same account.
        file(PageSpeedConnectionRepository.INDEX_PATH).bytes = null
        file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes = legacy
        val again = PageSpeedConnectionStore(PageSpeedConnectionRepository(::file, cipher)) { now }.accounts()
        assertEquals(first, again)
    }

    @Test
    fun unreadableLegacyRecordIsNeitherMigratedNorDeletedUntilTheUserDisconnects() {
        file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes = byteArrayOf(9, 8, 7)

        assertEquals(PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore())
        assertEquals(3, file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes?.size)
        assertNull(file(PageSpeedConnectionRepository.INDEX_PATH).bytes)

        // An explicit disconnect discards the unreadable record, as the single-site build did.
        store.disconnect()
        assertNull(file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes)
        assertEquals(PageSpeedRestoreResult.NotConnected, store.restore())
    }

    private fun save(connection: PageSpeedStoredConnection): PageSpeedConnectionCommit =
        store.saveValidatedConnection(connection.credentials, PageSpeedFetchResult.Complete(checkNotNull(connection.cachedSnapshot)))
            .also(store::acceptValidatedConnection)

    private fun writeLegacy(connection: PageSpeedStoredConnection) {
        file(PageSpeedConnectionRepository.ACCOUNT_PATH).bytes = AccountEnvelopeCodec.encode(
            cipher.encrypt(
                PageSpeedConnectionPayloadCodec.encode(connection),
                PageSpeedConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    private fun snapshot(site: String, fetchedAt: Long = 100L) = PageSpeedSnapshot(
        siteUrl = PageSpeedCredentials.normalizeSiteUrl(site),
        siteName = "example.com",
        status = "Good",
        metrics = listOf(
            PageSpeedMetric(
                key = "pagespeed.mobile.performance",
                label = "Mobile Performance",
                value = 96.0,
                unit = PageSpeedMetricUnit.SCORE,
                formattedValue = "96",
            ),
        ),
        fetchedAtMillis = fetchedAt,
        availability = PageSpeedSourceAvailability(
            desktop = PageSpeedSourceState.UNAVAILABLE,
            crux = PageSpeedSourceState.AVAILABLE,
        ),
        warnings = listOf("Desktop PageSpeed data is unavailable: temporary."),
    )

    private fun testConnection(apiKey: String, site: String = "https://example.com/path"): PageSpeedStoredConnection {
        val credentials = PageSpeedCredentials.create(apiKey, site)
        return PageSpeedStoredConnection(
            id = "pagespeed_123",
            credentials = credentials,
            createdAtMillis = 10L,
            updatedAtMillis = 100L,
            cachedSnapshot = snapshot(site),
        )
    }
}
