package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.sites.AesGcmTestCipher
import com.apoorvdarshan.verceltics.data.sites.MemoryBytesStore
import com.apoorvdarshan.verceltics.data.sites.SiteAccountEntry
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchConsoleConnectionRepositoryTest {
    private val files = HashMap<String, MemoryBytesStore>()
    private val slots = HashMap<String, MemoryBytesStore>()
    private val cipher = AesGcmTestCipher()
    private val oauthCipher = AesGcmTestCipher()
    private var now = 500L
    private val repository = newRepository()
    private val store = SearchConsoleConnectionStore(repository) { now }

    private fun file(path: String): MemoryBytesStore = files.getOrPut(path) { MemoryBytesStore() }

    private fun credentialStore(slot: String) =
        EncryptedGoogleOAuthCredentialStore(slot, slots.getOrPut(slot) { MemoryBytesStore() }, oauthCipher)

    private fun newRepository() = SearchConsoleConnectionRepository(::file, cipher, ::credentialStore)

    @Test
    fun accountRecordsHoldNoTokensAndRoundTripTheirIdentityAndCache() {
        val accountId = connect("subject-1", "owner@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))

        val raw = String(checkNotNull(file(SearchConsoleConnectionRepository.recordPath(accountId)).bytes), StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("owner@example.com"))
        assertFalse(raw.contains("example0.com"))
        val loaded = checkNotNull(repository.load(accountId))
        assertEquals("subject-1", loaded.subject)
        assertEquals("owner@example.com", loaded.email)
        assertEquals(snapshot(2), loaded.cachedSnapshot)
        assertEquals(SiteAccountIndex(listOf(SiteAccountEntry(accountId, "owner@example.com")), accountId), store.accounts())
    }

    @Test
    fun storageUsesDedicatedPathsKeysAadAndOAuthSlots() {
        assertEquals("accounts/google-search-console-oauth.account", SearchConsoleConnectionRepository.ACCOUNT_PATH)
        assertEquals("verceltics.account-envelope.v1:google-search-console-oauth", SearchConsoleConnectionRepository.ASSOCIATED_DATA)
        assertEquals("verceltics.account-storage.google-search-console.v1", SearchConsoleConnectionRepository.KEY_ALIAS)
        assertEquals("accounts/google-search-console/accounts.index", SearchConsoleConnectionRepository.INDEX_PATH)
        assertEquals("accounts/google-search-console/abc-1.account", SearchConsoleConnectionRepository.recordPath("abc-1"))
        assertEquals("search-console.abc-1", SearchConsoleConnectionRepository.credentialSlot("abc-1"))
        assertFalse(SearchConsoleConnectionRepository.INDEX_PATH.contains("vercel-personal"))
    }

    @Test
    fun cancelledFirstConnectRollsBackTheRecordAndTheIndex() {
        val accountId = SiteAccountIds.newId()
        val commit = store.saveValidatedConnection(accountId, "subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot()))

        assertTrue(store.rollbackValidatedConnection(commit))
        assertNull(file(SearchConsoleConnectionRepository.recordPath(accountId)).bytes)
        assertNull(file(SearchConsoleConnectionRepository.INDEX_PATH).bytes)
        assertEquals(SearchConsoleRestoreResult.NotConnected, store.restore())
    }

    @Test
    fun cancelledReconnectRestoresTheExactPreviousRecordAndIndex() {
        val accountId = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        val other = connect("subject-2", "b@example.com", SearchConsoleFetchResult.Complete(snapshot(3)))
        val priorRecord = checkNotNull(file(SearchConsoleConnectionRepository.recordPath(accountId)).bytes).copyOf()
        val priorIndex = checkNotNull(file(SearchConsoleConnectionRepository.INDEX_PATH).bytes).copyOf()

        now = 900L
        val replacement = store.saveValidatedConnection(accountId, "subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))
        assertEquals(accountId, store.accounts().activeId)
        assertTrue(store.rollbackValidatedConnection(replacement))

        assertArrayEquals(priorRecord, file(SearchConsoleConnectionRepository.recordPath(accountId)).bytes)
        assertArrayEquals(priorIndex, file(SearchConsoleConnectionRepository.INDEX_PATH).bytes)
        assertEquals(other, store.accounts().activeId)
    }

    @Test
    fun acceptedCommitCannotRollBackAndStaleCasCannotOverwriteANewerRecord() {
        val accountId = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        val stale = checkNotNull(store.loadForRefresh())
        val commit = store.saveValidatedConnection(accountId, "subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))
        store.acceptValidatedConnection(commit)

        assertFalse(store.rollbackValidatedConnection(commit))
        assertFalse(store.persistSnapshotRefresh(stale, SearchConsoleFetchResult.Complete(snapshot(3))))
        assertEquals(2, repository.load(accountId)?.cachedSnapshot?.properties?.size)
    }

    @Test
    fun connectionStoreBoundsCacheAndNeverReplacesCompleteCacheWithPartialData() {
        val full = snapshot(SearchConsoleConnectionStore.MAX_CACHED_PROPERTIES + 3)
        val accountId = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(full))
        val cached = checkNotNull(repository.load(accountId)?.cachedSnapshot)
        assertEquals(SearchConsoleConnectionStore.MAX_CACHED_PROPERTIES, cached.properties.size)
        assertFalse(cached.propertiesComplete)
        assertTrue(cached.warnings.single().contains("bounded"))

        val other = connect("subject-2", "b@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))
        val partial = SearchConsoleSnapshot(listOf(property(0)), 700L, false, listOf("Property list is incomplete."))
        assertFalse(
            store.persistSnapshotRefresh(checkNotNull(store.loadForRefresh()), SearchConsoleFetchResult.Partial(partial, failure())),
        )
        assertEquals(snapshot(2), repository.load(other)?.cachedSnapshot)
    }

    @Test
    fun longPropertyIdentifierPersistsAndRestoresWithoutMutation() {
        val longSiteUrl = "https://example.com/" + "long-path-segment/".repeat(250)
        assertTrue(longSiteUrl.length > 2_048)
        val snapshot = SearchConsoleSnapshot(listOf(SearchConsoleProperty(longSiteUrl, "siteOwner")), 400L, true, emptyList())

        connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot))

        val restored = store.restore() as SearchConsoleRestoreResult.Restored
        assertEquals(longSiteUrl, restored.cachedSnapshot?.properties?.single()?.siteUrl)
        assertTrue(checkNotNull(restored.cachedSnapshot).propertiesComplete)
    }

    @Test
    fun identifierTooLargeForEncryptedPayloadIsOmittedRatherThanRewritten() {
        val oversizedSiteUrl = "sc-domain:" + "€".repeat(6_000)
        assertTrue(oversizedSiteUrl.length <= MAX_URL_CHARACTERS)
        assertTrue(oversizedSiteUrl.toByteArray(Charsets.UTF_8).size > MAX_SEARCH_CONSOLE_STORED_STRING_BYTES)
        val snapshot = SearchConsoleSnapshot(listOf(SearchConsoleProperty(oversizedSiteUrl, "siteOwner")), 400L, true, emptyList())

        connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot))

        val cached = checkNotNull((store.restore() as SearchConsoleRestoreResult.Restored).cachedSnapshot)
        assertTrue(cached.properties.isEmpty())
        assertFalse(cached.propertiesComplete)
        assertTrue(cached.warnings.single().contains("bounded"))
    }

    @Test
    fun sameAccountPartialReconnectMergesRatherThanShrinkingOfflineCache() {
        val existing = partialSnapshot(listOf(property(0), property(1), property(2)), 400L, "Earlier property inventory was incomplete.")
        val accountId = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Partial(existing, failure()))
        val candidate = partialSnapshot(
            listOf(property(1).copy(permissionLevel = "siteRestrictedUser"), property(3)),
            700L,
            "Latest property inventory was incomplete.",
        )

        now = 800L
        assertEquals(accountId, store.matchingAccountId("subject-1", "a@example.com"))
        store.acceptValidatedConnection(
            store.saveValidatedConnection(accountId, "subject-1", "a@example.com", SearchConsoleFetchResult.Partial(candidate, failure())),
        )

        val cached = checkNotNull(repository.load(accountId)?.cachedSnapshot)
        assertEquals(
            listOf("sc-domain:example0.com", "sc-domain:example1.com", "sc-domain:example2.com", "sc-domain:example3.com"),
            cached.properties.map(SearchConsoleProperty::siteUrl),
        )
        assertEquals("siteRestrictedUser", cached.properties[1].permissionLevel)
        assertEquals(700L, cached.fetchedAtMillis)
        assertFalse(cached.propertiesComplete)
        assertEquals(500L, repository.load(accountId)?.createdAtMillis)
        assertEquals(1, store.accounts().accounts.size)
    }

    @Test
    fun partialRefreshPreservesFullerSameAccountPartialCache() {
        val existing = partialSnapshot(listOf(property(0), property(1), property(2)), 400L, "Earlier property inventory was incomplete.")
        val accountId = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Partial(existing, failure()))
        val candidate = partialSnapshot(listOf(property(1).copy(permissionLevel = "siteFullUser")), 700L, "Latest property inventory was incomplete.")

        now = 900L
        assertTrue(store.persistSnapshotRefresh(checkNotNull(store.loadForRefresh()), SearchConsoleFetchResult.Partial(candidate, failure())))

        val cached = checkNotNull(repository.load(accountId)?.cachedSnapshot)
        assertEquals(existing.properties.map(SearchConsoleProperty::siteUrl), cached.properties.map(SearchConsoleProperty::siteUrl))
        assertEquals("siteFullUser", cached.properties[1].permissionLevel)
        assertEquals(700L, cached.fetchedAtMillis)
        assertEquals(2, cached.warnings.size)
    }

    @Test
    fun refreshesStayInsideTheAccountTheyStartedWith() {
        val accountA = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        val sourceA = checkNotNull(store.loadForRefresh())
        val accountB = connect("subject-2", "b@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))

        // A refresh that started before B became active still lands in A's own cache only.
        assertTrue(store.persistSnapshotRefresh(sourceA, SearchConsoleFetchResult.Complete(snapshot(4))))
        assertEquals(4, repository.load(accountA)?.cachedSnapshot?.properties?.size)
        assertEquals(2, repository.load(accountB)?.cachedSnapshot?.properties?.size)
        assertEquals(accountB, store.accounts().activeId)

        // Once A is removed, its stale source can never resurrect it.
        store.removeAccount(accountA)
        assertFalse(store.persistSnapshotRefresh(sourceA, SearchConsoleFetchResult.Complete(snapshot(5))))
        assertNull(repository.load(accountA))
    }

    @Test
    fun identityMatchingSwitchingAndRemovalFollowIos() {
        val first = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        val second = connect("subject-2", "b@example.com", SearchConsoleFetchResult.Complete(snapshot(2)))
        assertEquals(first, store.matchingAccountId("subject-1", "other@example.com"))
        assertNull(store.matchingAccountId("subject-3", "a@example.com"))
        assertNull(store.matchingAccountId(null, null))

        assertEquals(second, store.accounts().activeId)
        assertNotNull(store.switchAccount(first))
        assertEquals(first, (store.restore() as SearchConsoleRestoreResult.Restored).accountId)
        assertNull(store.switchAccount("not-saved"))

        val remaining = store.removeAccount(first)
        assertEquals(listOf(second), remaining.ids)
        assertEquals(second, remaining.activeId)

        val third = connect("subject-3", "c@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        assertEquals(listOf(second, third), store.removeAllAccounts())
        assertEquals(SearchConsoleRestoreResult.NotConnected, store.restore())
    }

    @Test
    fun legacyRecordMigratesIntoTheFirstAccountAndItsOwnOAuthSlot() {
        writeLegacy()

        val restored = store.restore() as SearchConsoleRestoreResult.Restored

        val accountId = SiteAccountIds.migrated("google-search-console")
        assertEquals(accountId, restored.accountId)
        assertEquals("subject-legacy", restored.subject)
        assertEquals("legacy@example.com", restored.email)
        assertEquals(snapshot(3), restored.cachedSnapshot)
        assertEquals(SiteAccountIndex(listOf(SiteAccountEntry(accountId, "legacy@example.com")), accountId), restored.accounts)
        val record = checkNotNull(repository.load(accountId))
        assertEquals(10L, record.createdAtMillis)
        assertEquals(100L, record.updatedAtMillis)
        val credential = checkNotNull(credentialStore(SearchConsoleConnectionRepository.credentialSlot(accountId)).load())
        assertEquals("legacy-access", credential.accessToken.use { it })
        assertEquals("legacy-refresh", credential.refreshToken?.use { it })
        assertEquals(SearchConsoleOAuthCredential.REQUIRED_SCOPES, credential.scopes)
        assertEquals("subject-legacy", credential.subject)
        assertNull(file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes)
    }

    @Test
    fun interruptedMigrationRepeatsWithoutDuplicatingTheAccount() {
        writeLegacy()
        val legacy = checkNotNull(file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes).copyOf()
        store.accounts()
        file(SearchConsoleConnectionRepository.INDEX_PATH).bytes = null
        file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes = legacy

        val again = SearchConsoleConnectionStore(newRepository()) { now }.accounts()

        assertEquals(listOf(SiteAccountIds.migrated("google-search-console")), again.ids)
        assertNull(file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes)
    }

    @Test
    fun disconnectReturnsTheRemovedAccountAndDiscardsAnUndecodableList() {
        val first = connect("subject-1", "a@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        val second = connect("subject-2", "b@example.com", SearchConsoleFetchResult.Complete(snapshot(1)))
        assertEquals(second, store.disconnect())
        assertEquals(first, store.accounts().activeId)

        file(SearchConsoleConnectionRepository.INDEX_PATH).bytes = byteArrayOf(1, 2, 3)
        assertNull(store.disconnect())
        assertEquals(SearchConsoleRestoreResult.NotConnected, store.restore())
    }

    @Test
    fun unreadableLegacyRecordIsNeitherMigratedNorDeleted() {
        file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes = byteArrayOf(7, 7, 7)

        assertEquals(
            SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SAVED_RECORD_UNREADABLE),
            store.restore(),
        )
        assertArrayEquals(byteArrayOf(7, 7, 7), file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes)
        assertNull(file(SearchConsoleConnectionRepository.INDEX_PATH).bytes)
        assertTrue(slots.isEmpty())
    }

    private fun connect(subject: String, email: String, result: SearchConsoleFetchResult<SearchConsoleSnapshot>): String {
        val accountId = store.matchingAccountId(subject, email) ?: SiteAccountIds.newId()
        store.acceptValidatedConnection(store.saveValidatedConnection(accountId, subject, email, result))
        return accountId
    }

    /** Writes the single-account record exactly as builds before multi-account support did. */
    private fun writeLegacy() {
        val legacy = SearchConsoleStoredConnection(
            id = "subject-legacy",
            credential = SearchConsoleOAuthCredential(
                SecretValue.of("legacy-access"),
                SecretValue.of("legacy-refresh"),
                "Bearer",
                SearchConsoleOAuthCredential.REQUIRED_SCOPES,
                1_000_000L,
                "subject-legacy",
                "legacy@example.com",
            ),
            createdAtMillis = 10L,
            updatedAtMillis = 100L,
            cachedSnapshot = snapshot(3),
        )
        file(SearchConsoleConnectionRepository.ACCOUNT_PATH).bytes = AccountEnvelopeCodec.encode(
            cipher.encrypt(
                SearchConsoleConnectionPayloadCodec.encode(legacy),
                SearchConsoleConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    private fun snapshot(count: Int = 1) = SearchConsoleSnapshot(List(count) { property(it) }, 100L, true, emptyList())

    private fun partialSnapshot(properties: List<SearchConsoleProperty>, fetchedAtMillis: Long, warning: String) =
        SearchConsoleSnapshot(properties, fetchedAtMillis, false, listOf(warning))

    private fun property(index: Int) = SearchConsoleProperty("sc-domain:example$index.com", "siteOwner")

    private fun failure() = SearchConsoleFailure(SearchConsoleFailureKind.NETWORK, "Google Search Console could not be reached.")
}
