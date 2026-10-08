package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredential
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthScopes
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteConnectionStoreTest {
    private val files = HashMap<String, MemoryBytesStore>()
    private val cipher = AesGcmTestCipher()
    private val oauthCipher = AesGcmTestCipher()
    private val repository = SiteConnectionRepository(::file, cipher)
    private var now = 1_000_000L
    private val store = SiteConnectionStore(repository) { now }

    private fun file(path: String): MemoryBytesStore = files.getOrPut(path) { MemoryBytesStore() }

    @Test
    fun validatedConnectionRoundTripsEncryptedWithFullSnapshot() {
        val saved = store.saveValidatedConnection(
            SiteProvider.PLAUSIBLE,
            null,
            "example.com",
            SecretValue.of("plausible-secret-key"),
            mapOf("siteID" to "example.com"),
            snapshot(SiteProvider.PLAUSIBLE, resources = 2),
        )

        val raw = String(checkNotNull(file(SiteConnectionRepository.recordPath(SiteProvider.PLAUSIBLE, saved.accountId)).bytes), StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("plausible-secret-key"))
        assertFalse(raw.contains("example.com"))
        val index = String(checkNotNull(file(SiteConnectionRepository.indexPath(SiteProvider.PLAUSIBLE)).bytes), StandardCharsets.ISO_8859_1)
        assertFalse(index.contains("example.com"))
        val loaded = checkNotNull(store.loadForRequest(SiteProvider.PLAUSIBLE)).connection
        assertEquals("plausible-secret-key", loaded.credential?.use { it })
        assertEquals("example.com", loaded.name)
        assertEquals(mapOf("siteID" to "example.com"), loaded.metadata)
        assertEquals(snapshot(SiteProvider.PLAUSIBLE, resources = 2), loaded.cachedSnapshot)
        assertEquals(1_000_000L, loaded.createdAtMillis)
        assertFalse(loaded.toString().contains("plausible-secret-key"))
        assertEquals(SiteAccountIndex(listOf(SiteAccountEntry(saved.accountId, "example.com")), saved.accountId), store.accounts(SiteProvider.PLAUSIBLE))
    }

    @Test
    fun restoreReportsNotConnectedFreshStaleAndUnreadableStates() {
        assertEquals(SiteRestoreResult.NotConnected, store.restore(SiteProvider.UMAMI))
        val saved = store.saveValidatedConnection(SiteProvider.UMAMI, null, "Umami Cloud", SecretValue.of("k"), emptyMap(), snapshot(SiteProvider.UMAMI, fetchedAt = now))

        val fresh = store.restore(SiteProvider.UMAMI) as SiteRestoreResult.Restored
        assertFalse(fresh.cacheIsStale)
        assertEquals("Umami Cloud", fresh.name)
        assertEquals(saved.accountId, fresh.accountId)
        now += SiteProvider.UMAMI.snapshotCacheLifetimeMillis
        assertTrue((store.restore(SiteProvider.UMAMI) as SiteRestoreResult.Restored).cacheIsStale)

        file(SiteConnectionRepository.recordPath(SiteProvider.UMAMI, saved.accountId)).bytes = byteArrayOf(1, 2, 3)
        val unreadable = store.restore(SiteProvider.UMAMI) as SiteRestoreResult.Unavailable
        assertEquals(SiteRestoreProblem.SAVED_RECORD_UNREADABLE, unreadable.problem)
        // The account list stays readable so the user can switch away or remove the broken account.
        assertEquals(listOf(saved.accountId), unreadable.accounts.ids)

        file(SiteConnectionRepository.indexPath(SiteProvider.UMAMI)).bytes = byteArrayOf(9)
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore(SiteProvider.UMAMI))

        val locked = SiteConnectionStore(SiteConnectionRepository({ MemoryBytesStore().apply { bytes = validEnvelope() } }, ThrowingCipher()))
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SECURE_STORAGE_UNAVAILABLE), locked.restore(SiteProvider.CLARITY))
    }

    @Test
    fun recordsAreBoundToTheirProviderAndAccount() {
        val first = store.saveValidatedConnection(SiteProvider.BING_WEBMASTER, null, "Bing", SecretValue.of("k1"), emptyMap(), snapshot(SiteProvider.BING_WEBMASTER))
        val second = store.saveValidatedConnection(SiteProvider.BING_WEBMASTER, null, "Bing 2", SecretValue.of("k2"), emptyMap(), snapshot(SiteProvider.BING_WEBMASTER))
        assertNotEquals(first.accountId, second.accountId)

        // Replaying one account's envelope into another account's slot is rejected.
        file(SiteConnectionRepository.recordPath(SiteProvider.BING_WEBMASTER, second.accountId)).bytes =
            file(SiteConnectionRepository.recordPath(SiteProvider.BING_WEBMASTER, first.accountId)).bytes
        assertTrue(store.restore(SiteProvider.BING_WEBMASTER) is SiteRestoreResult.Unavailable)

        // Replaying a provider's index into another provider is rejected too.
        file(SiteConnectionRepository.indexPath(SiteProvider.UPTIME_ROBOT)).bytes =
            file(SiteConnectionRepository.indexPath(SiteProvider.BING_WEBMASTER)).bytes
        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore(SiteProvider.UPTIME_ROBOT))
    }

    @Test
    fun refreshPersistsOnlyWhileTheSourceRevisionIsCurrent() {
        store.saveValidatedConnection(SiteProvider.CLARITY, null, "Studio", SecretValue.of("k"), mapOf("projectName" to "Studio"), snapshot(SiteProvider.CLARITY))
        val source = checkNotNull(store.loadForRequest(SiteProvider.CLARITY))

        now = 2_000_000L
        assertTrue(store.persistRefresh(source, "Studio site", snapshot(SiteProvider.CLARITY, fetchedAt = now), mapOf("extra" to "1")))
        val refreshed = checkNotNull(store.loadForRequest(SiteProvider.CLARITY)).connection
        assertEquals(now, refreshed.cachedSnapshot?.fetchedAtMillis)
        assertEquals(mapOf("projectName" to "Studio", "extra" to "1"), refreshed.metadata)
        assertEquals(1_000_000L, refreshed.createdAtMillis)
        assertEquals("k", refreshed.credential?.use { it })
        // The menu label follows the refreshed name.
        assertEquals("Studio site", store.accounts(SiteProvider.CLARITY).active?.name)

        assertFalse(store.persistRefresh(source, "Stale", snapshot(SiteProvider.CLARITY, fetchedAt = 1L)))
        assertEquals(now, checkNotNull(store.loadForRequest(SiteProvider.CLARITY)).connection.cachedSnapshot?.fetchedAtMillis)

        store.disconnect(SiteProvider.CLARITY)
        assertFalse(store.persistRefresh(source, "Gone", snapshot(SiteProvider.CLARITY)))
        assertNull(store.loadForRequest(SiteProvider.CLARITY))
    }

    @Test
    fun sameIdentityRotatesTheCredentialInPlaceAndKeepsTheCreationTime() {
        val first = store.saveValidatedConnection(
            SiteProvider.PLAUSIBLE, null, "example.com", SecretValue.of("old-key"),
            mapOf("siteID" to "Example.COM/blog"), snapshot(SiteProvider.PLAUSIBLE),
        )
        now = 5_000_000L
        val rotated = store.saveValidatedConnection(
            SiteProvider.PLAUSIBLE, null, "example.com", SecretValue.of("new-key"),
            mapOf("siteID" to "example.com/blog"), snapshot(SiteProvider.PLAUSIBLE),
        )

        assertEquals(first.accountId, rotated.accountId)
        assertEquals(1, store.accounts(SiteProvider.PLAUSIBLE).accounts.size)
        val loaded = checkNotNull(store.loadForRequest(SiteProvider.PLAUSIBLE)).connection
        assertEquals(1_000_000L, loaded.createdAtMillis)
        assertEquals(5_000_000L, loaded.updatedAtMillis)
        assertEquals("new-key", loaded.credential?.use { it })

        // A path that differs only by case is another Plausible site.
        val other = store.saveValidatedConnection(
            SiteProvider.PLAUSIBLE, null, "example.com/Blog", SecretValue.of("new-key"),
            mapOf("siteID" to "example.com/Blog"), snapshot(SiteProvider.PLAUSIBLE),
        )
        assertNotEquals(first.accountId, other.accountId)
    }

    @Test
    fun identityRulesMatchIosForEveryProvider() {
        val bing = store.saveValidatedConnection(SiteProvider.BING_WEBMASTER, null, "Bing", SecretValue.of("same"), emptyMap(), snapshot(SiteProvider.BING_WEBMASTER))
        assertEquals(bing.accountId, store.matchingAccountId(SiteProvider.BING_WEBMASTER, SecretValue.of("same"), emptyMap()))
        assertNull(store.matchingAccountId(SiteProvider.BING_WEBMASTER, SecretValue.of("different"), emptyMap()))

        val clarity = store.saveValidatedConnection(
            SiteProvider.CLARITY, null, "Studio", SecretValue.of("token-a"),
            mapOf("projectName" to "Studio", "siteURL" to "https://Studio.example/path"), snapshot(SiteProvider.CLARITY),
        )
        assertEquals(
            clarity.accountId,
            store.matchingAccountId(SiteProvider.CLARITY, SecretValue.of("token-b"), mapOf("projectName" to "studio", "siteURL" to "https://studio.example/other")),
        )
        assertNull(store.matchingAccountId(SiteProvider.CLARITY, SecretValue.of("token-b"), mapOf("projectName" to "Docs", "siteURL" to "https://studio.example")))

        val umami = store.saveValidatedConnection(
            SiteProvider.UMAMI, null, "Umami Cloud", SecretValue.of("key-1"),
            mapOf("authMode" to "cloud", "umamiUserID" to "user-1", "umamiEndpoint" to "https://api.umami.is/v1/"), snapshot(SiteProvider.UMAMI),
        )
        // A new key for the same Umami user resolves to the same account through /me identity.
        assertEquals(
            umami.accountId,
            store.matchingAccountId(SiteProvider.UMAMI, SecretValue.of("key-2"), mapOf("authMode" to "cloud", "umamiUserID" to "user-1", "umamiEndpoint" to "https://API.umami.is/v1")),
        )
        assertNull(store.matchingAccountId(SiteProvider.UMAMI, SecretValue.of("key-2"), mapOf("authMode" to "cloud", "umamiUserID" to "user-2", "umamiEndpoint" to "https://api.umami.is/v1/")))

        val google = store.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, SiteAccountIds.newId(), "GA", null,
            mapOf("googleSubject" to "subject-1", "googleEmail" to "a@example.com"), snapshot(SiteProvider.GOOGLE_ANALYTICS),
        )
        assertEquals(google.accountId, store.matchingAccountId(SiteProvider.GOOGLE_ANALYTICS, null, mapOf("googleSubject" to "subject-1")))
        assertNull(store.matchingAccountId(SiteProvider.GOOGLE_ANALYTICS, null, mapOf("googleSubject" to "subject-2")))
        assertNull(store.matchingAccountId(SiteProvider.GOOGLE_ANALYTICS, null, emptyMap()))
    }

    @Test
    fun addSwitchRemoveCurrentAndRemoveAllManageTheActiveAccount() {
        val first = store.saveValidatedConnection(SiteProvider.UPTIME_ROBOT, null, "First", SecretValue.of("a"), emptyMap(), snapshot(SiteProvider.UPTIME_ROBOT))
        val second = store.saveValidatedConnection(SiteProvider.UPTIME_ROBOT, null, "Second", SecretValue.of("b"), emptyMap(), snapshot(SiteProvider.UPTIME_ROBOT, resources = 3))
        val third = store.saveValidatedConnection(SiteProvider.UPTIME_ROBOT, null, "Third", SecretValue.of("c"), emptyMap(), snapshot(SiteProvider.UPTIME_ROBOT))

        // Adding never disconnects: every account stays saved and the newest is active.
        assertEquals(listOf(first.accountId, second.accountId, third.accountId), store.accounts(SiteProvider.UPTIME_ROBOT).ids)
        assertEquals(third.accountId, store.accounts(SiteProvider.UPTIME_ROBOT).activeId)

        assertNotNull(store.switchAccount(SiteProvider.UPTIME_ROBOT, second.accountId))
        val restored = store.restore(SiteProvider.UPTIME_ROBOT) as SiteRestoreResult.Restored
        assertEquals(second.accountId, restored.accountId)
        assertEquals(3, restored.cachedSnapshot?.resources?.size)
        assertEquals("b", store.loadForRequest(SiteProvider.UPTIME_ROBOT)?.connection?.credential?.use { it })
        assertNull(store.switchAccount(SiteProvider.UPTIME_ROBOT, "missing-account"))

        // Removing the active account activates the first remaining one, like iOS.
        val remaining = store.removeAccount(SiteProvider.UPTIME_ROBOT, second.accountId)
        assertEquals(listOf(first.accountId, third.accountId), remaining.ids)
        assertEquals(first.accountId, remaining.activeId)
        assertNull(file(SiteConnectionRepository.recordPath(SiteProvider.UPTIME_ROBOT, second.accountId)).bytes)

        // Removing an inactive account keeps the active one.
        assertEquals(first.accountId, store.removeAccount(SiteProvider.UPTIME_ROBOT, third.accountId).activeId)

        store.saveValidatedConnection(SiteProvider.UPTIME_ROBOT, null, "Again", SecretValue.of("d"), emptyMap(), snapshot(SiteProvider.UPTIME_ROBOT))
        val removed = store.removeAllAccounts(SiteProvider.UPTIME_ROBOT)
        assertEquals(2, removed.size)
        assertEquals(SiteRestoreResult.NotConnected, store.restore(SiteProvider.UPTIME_ROBOT))
        removed.forEach { assertNull(file(SiteConnectionRepository.recordPath(SiteProvider.UPTIME_ROBOT, it)).bytes) }
    }

    @Test
    fun offlineCachesAreIsolatedPerAccount() {
        val first = store.saveValidatedConnection(SiteProvider.BETTER_STACK, null, "First", SecretValue.of("a"), emptyMap(), snapshot(SiteProvider.BETTER_STACK, resources = 1))
        val second = store.saveValidatedConnection(SiteProvider.BETTER_STACK, null, "Second", SecretValue.of("b"), emptyMap(), snapshot(SiteProvider.BETTER_STACK, resources = 4))

        val source = checkNotNull(store.loadForRequest(SiteProvider.BETTER_STACK, first.accountId))
        assertTrue(store.persistRefresh(source, "First", snapshot(SiteProvider.BETTER_STACK, resources = 2, fetchedAt = 77L)))

        assertEquals(2, store.loadForRequest(SiteProvider.BETTER_STACK, first.accountId)?.connection?.cachedSnapshot?.resources?.size)
        assertEquals(4, store.loadForRequest(SiteProvider.BETTER_STACK, second.accountId)?.connection?.cachedSnapshot?.resources?.size)
        // Refreshing a background account never changes which account is active.
        assertEquals(second.accountId, store.accounts(SiteProvider.BETTER_STACK).activeId)
    }

    @Test
    fun legacySingleAccountRecordMigratesLosslesslyAsTheActiveAccount() {
        SiteProvider.entries.filterNot(SiteProvider::usesGoogleOAuth).forEach { provider ->
            writeLegacy(provider, "Legacy ${provider.displayName}", SecretValue.of("legacy-${provider.id}"), mapOf("siteID" to "legacy.example"))
        }

        SiteProvider.entries.filterNot(SiteProvider::usesGoogleOAuth).forEach { provider ->
            val restored = store.restore(provider) as SiteRestoreResult.Restored
            val migratedId = SiteAccountIds.migrated("site-service:${provider.id}")
            assertEquals(migratedId, restored.accountId)
            assertEquals("Legacy ${provider.displayName}", restored.name)
            assertEquals(mapOf("siteID" to "legacy.example"), restored.metadata)
            assertEquals(snapshot(provider, resources = 2, fetchedAt = 500L), restored.cachedSnapshot)
            assertEquals(SiteAccountIndex(listOf(SiteAccountEntry(migratedId, "Legacy ${provider.displayName}")), migratedId), restored.accounts)
            val connection = checkNotNull(store.loadForRequest(provider)).connection
            assertEquals("legacy-${provider.id}", connection.credential?.use { it })
            assertEquals(123L, connection.createdAtMillis)
            assertEquals(456L, connection.updatedAtMillis)
            // The legacy envelope is gone once the migrated index is durable.
            assertNull(file(SiteConnectionRepository.accountPath(provider)).bytes)
        }
    }

    @Test
    fun interruptedMigrationRepeatsIntoTheSameAccountWithoutDuplicates() {
        writeLegacy(SiteProvider.PLAUSIBLE, "example.com", SecretValue.of("legacy-key"), mapOf("siteID" to "example.com"))
        val legacyEnvelope = checkNotNull(file(SiteConnectionRepository.accountPath(SiteProvider.PLAUSIBLE)).bytes).copyOf()
        store.accounts(SiteProvider.PLAUSIBLE)

        // Simulate a crash after the record write but before the index write and legacy delete.
        file(SiteConnectionRepository.indexPath(SiteProvider.PLAUSIBLE)).bytes = null
        file(SiteConnectionRepository.accountPath(SiteProvider.PLAUSIBLE)).bytes = legacyEnvelope
        val fresh = SiteConnectionStore(SiteConnectionRepository(::file, cipher)) { now }

        val index = fresh.accounts(SiteProvider.PLAUSIBLE)
        assertEquals(listOf(SiteAccountIds.migrated("site-service:plausible")), index.ids)
        assertEquals(1, files.keys.count { it.startsWith("accounts/site-services/plausible/") && it.endsWith(".account") && files.getValue(it).bytes != null })
        assertNull(file(SiteConnectionRepository.accountPath(SiteProvider.PLAUSIBLE)).bytes)
    }

    @Test
    fun aLeftoverLegacyRecordIsDeletedOnceTheIndexExists() {
        val saved = store.saveValidatedConnection(SiteProvider.CLARITY, null, "Current", SecretValue.of("k"), emptyMap(), snapshot(SiteProvider.CLARITY))
        writeLegacy(SiteProvider.CLARITY, "Leftover", SecretValue.of("old"), emptyMap())

        val fresh = SiteConnectionStore(SiteConnectionRepository(::file, cipher)) { now }
        assertEquals(listOf(saved.accountId), fresh.accounts(SiteProvider.CLARITY).ids)
        assertNull(file(SiteConnectionRepository.accountPath(SiteProvider.CLARITY)).bytes)
    }

    @Test
    fun unreadableLegacyRecordIsNeitherMigratedNorDeleted() {
        file(SiteConnectionRepository.accountPath(SiteProvider.UMAMI)).bytes = byteArrayOf(4, 5, 6)

        assertEquals(SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE), store.restore(SiteProvider.UMAMI))
        assertArrayEquals(byteArrayOf(4, 5, 6), file(SiteConnectionRepository.accountPath(SiteProvider.UMAMI)).bytes)
        assertNull(file(SiteConnectionRepository.indexPath(SiteProvider.UMAMI)).bytes)
    }

    @Test
    fun disconnectDiscardsAnUndecodableAccountListButNeverALockedOne() {
        store.saveValidatedConnection(SiteProvider.PLAUSIBLE, null, "a", SecretValue.of("k"), mapOf("siteID" to "a"), snapshot(SiteProvider.PLAUSIBLE))
        file(SiteConnectionRepository.indexPath(SiteProvider.PLAUSIBLE)).bytes = byteArrayOf(1, 2, 3)
        file(SiteConnectionRepository.accountPath(SiteProvider.PLAUSIBLE)).bytes = byteArrayOf(4, 5, 6)

        assertNull(store.disconnect(SiteProvider.PLAUSIBLE))
        assertEquals(SiteRestoreResult.NotConnected, store.restore(SiteProvider.PLAUSIBLE))
        assertNull(file(SiteConnectionRepository.accountPath(SiteProvider.PLAUSIBLE)).bytes)

        val lockedFiles = MemoryBytesStore().apply { bytes = validEnvelope() }
        val locked = SiteConnectionStore(SiteConnectionRepository({ lockedFiles }, ThrowingCipher()))
        assertThrows(SecurityException::class.java) { locked.disconnect(SiteProvider.PLAUSIBLE) }
        assertNotNull(lockedFiles.bytes)
    }

    @Test
    fun googleAnalyticsMigrationMovesTheLegacyOAuthSlotToTheFirstAccount() {
        val slots = HashMap<String, MemoryBytesStore>()
        val credentialStore = { slot: String ->
            EncryptedGoogleOAuthCredentialStore(slot, slots.getOrPut(slot) { MemoryBytesStore() }, oauthCipher)
        }
        val migrating = SiteConnectionStore(SiteConnectionRepository(::file, cipher, SiteGoogleSlotMigrator(credentialStore))) { now }
        credentialStore(SiteGoogleSlots.LEGACY_GOOGLE_ANALYTICS).save(googleCredential("legacy-token"))
        writeLegacy(SiteProvider.GOOGLE_ANALYTICS, "Google Analytics · 2 properties", null, mapOf("googleSubject" to "subject-1", "googleEmail" to "a@example.com"))

        val restored = migrating.restore(SiteProvider.GOOGLE_ANALYTICS) as SiteRestoreResult.Restored

        val accountId = SiteAccountIds.migrated("site-service:googleAnalytics")
        assertEquals(accountId, restored.accountId)
        assertEquals("a@example.com", restored.accounts.active?.detail)
        val slot = SiteGoogleSlots.forAccount(SiteProvider.GOOGLE_ANALYTICS, accountId)
        assertEquals("site.google-analytics.$accountId", slot)
        assertEquals("legacy-token", credentialStore(slot).load()?.accessToken?.use { it })
        assertNull(slots.getValue(SiteGoogleSlots.LEGACY_GOOGLE_ANALYTICS).bytes)
        assertNull(file(SiteConnectionRepository.accountPath(SiteProvider.GOOGLE_ANALYTICS)).bytes)
    }

    @Test
    fun googleConnectionsStoreNoCredentialButApiKeyProvidersMust() {
        store.saveValidatedConnection(
            SiteProvider.GOOGLE_ANALYTICS, null, "Google Analytics · 1 property", null,
            mapOf("googleSubject" to "sub", "googleEmail" to "a@example.com"), snapshot(SiteProvider.GOOGLE_ANALYTICS),
        )
        assertNull(checkNotNull(store.loadForRequest(SiteProvider.GOOGLE_ANALYTICS)).connection.credential)
        assertThrows(IllegalArgumentException::class.java) {
            store.saveValidatedConnection(SiteProvider.UMAMI, null, "Umami", null, emptyMap(), snapshot(SiteProvider.UMAMI))
        }
    }

    @Test
    fun offlineCacheIsBoundedAndDisclosed() {
        store.saveValidatedConnection(
            SiteProvider.UPTIME_ROBOT, null, "UptimeRobot · 300 monitors", SecretValue.of("k"), emptyMap(),
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
            SiteProvider.GOOGLE_ANALYTICS, null, "GA", null, emptyMap(),
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

    /** Writes a record exactly as builds before multi-account support did. */
    private fun writeLegacy(provider: SiteProvider, name: String, credential: SecretValue?, metadata: Map<String, String>) {
        val legacy = StoredSiteConnection(
            accountId = SiteAccountIds.migrated("site-service:${provider.id}"),
            provider = provider,
            name = name,
            credential = credential,
            metadata = metadata,
            createdAtMillis = 123L,
            updatedAtMillis = 456L,
            cachedSnapshot = snapshot(provider, resources = 2, fetchedAt = 500L),
        )
        val plaintext = SiteConnectionPayloadCodec.encode(legacy)
        file(SiteConnectionRepository.accountPath(provider)).bytes =
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, SiteConnectionRepository.legacyAssociatedData(provider)))
    }

    private fun validEnvelope(): ByteArray = AccountEnvelopeCodec.encode(cipher.encrypt(byteArrayOf(1, 2, 3), byteArrayOf(4)))

    private fun googleCredential(token: String) = GoogleOAuthCredential(
        accessToken = SecretValue.of(token),
        refreshToken = SecretValue.of("refresh"),
        tokenType = "Bearer",
        scopes = GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY.toList(),
        expiresAtMillis = 9_000_000L,
        subject = "subject-1",
        email = "a@example.com",
    )

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
