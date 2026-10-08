package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarConnectionStoreTest {
    private var now = 10_000_000L
    private val fixture = RegistrarStorageFixture()
    private val store = RegistrarConnectionStore(fixture.repository) { now }

    @Test
    fun restoreIsOfflinePerRegistrarAndReportsFreshness() {
        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.PORKBUN),
            RegistrarValidation("Porkbun · abcd", listOf(domain("a.example"))),
        )
        store.acceptValidatedConnection(commit)

        val fresh = store.restoreAll()
        assertEquals(RegistrarProvider.entries.toSet(), fresh.keys)
        val restored = fresh.getValue(RegistrarProvider.PORKBUN) as RegistrarRestoreResult.Restored
        assertEquals("Porkbun · abcd", restored.accountName)
        assertFalse(restored.cacheIsStale)
        assertEquals(listOf("a.example"), restored.cachedSnapshot?.domains?.map { it.name })
        assertSame(RegistrarRestoreResult.NotConnected, fresh.getValue(RegistrarProvider.GANDI))

        now += RegistrarConnectionStore.CACHE_LIFETIME_MILLIS
        val stale = store.restore(RegistrarProvider.PORKBUN) as RegistrarRestoreResult.Restored
        assertTrue(stale.cacheIsStale)
    }

    @Test
    fun unreadableRecordsAreReportedWithoutBeingReplaced() {
        fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes = byteArrayOf(9, 9, 9)

        assertEquals(
            RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SAVED_RECORD_UNREADABLE),
            store.restore(RegistrarProvider.NAMECHEAP),
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.saveValidatedConnection(
                credentials(RegistrarProvider.NAMECHEAP),
                RegistrarValidation("alice", emptyList()),
            )
        }
        assertEquals(3, fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes?.size)
    }

    @Test
    fun secureStorageFailuresAreDistinguished() {
        val throwingRepository = RegistrarConnectionRepository(
            storeFactory = { object : com.apoorvdarshan.verceltics.data.account.AtomicBytesStore {
                override fun read(): ByteArray = throw SecurityException("locked")
                override fun write(bytes: ByteArray) = Unit
                override fun delete() = Unit
            } },
            cipher = TestAccountCipher(),
        )

        assertEquals(
            RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SECURE_STORAGE_UNAVAILABLE),
            RegistrarConnectionStore(throwingRepository).restore(RegistrarProvider.GANDI),
        )
    }

    @Test
    fun refreshPersistsOnlyForTheSameCredentialsAndSlotRevision() {
        val original = credentials(RegistrarProvider.GANDI, key = "pat-1")
        store.acceptValidatedConnection(
            store.saveValidatedConnection(original, RegistrarValidation("Gandi Account", listOf(domain("old.example")))),
        )
        now += 60_000L

        val refreshed = store.persistRefreshResult(original, listOf(domain("new.example")))

        refreshed as RegistrarRefreshOutcome.Refreshed
        assertTrue(refreshed.cached)
        assertEquals(now, refreshed.snapshot.fetchedAtMillis)
        assertEquals(listOf("new.example"), refreshed.snapshot.domains.map { it.name })
        val saved = checkNotNull(fixture.repository.load(RegistrarProvider.GANDI))
        assertEquals(listOf("new.example"), saved.cachedSnapshot?.domains?.map { it.name })
        assertEquals(10_000_000L, saved.account.createdAtMillis)

        assertSame(
            RegistrarRefreshOutcome.Replaced,
            store.persistRefreshResult(credentials(RegistrarProvider.GANDI, key = "pat-2"), listOf(domain("x.example"))),
        )
        store.disconnect(RegistrarProvider.GANDI)
        assertSame(RegistrarRefreshOutcome.Disconnected, store.persistRefreshResult(original, emptyList()))
    }

    @Test
    fun offlineCacheIsBoundedAndHonestAboutIt() {
        val domains = (0 until 1_250).map { domain("d$it.example") } +
            domain("x".repeat(300) + ".example")
        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.GO_DADDY),
            RegistrarValidation("GoDaddy · 1234", domains),
        )
        store.acceptValidatedConnection(commit)

        assertEquals(domains.size, commit.snapshot.domains.size)
        assertTrue(commit.snapshot.domainsComplete)
        val cached = checkNotNull(fixture.repository.load(RegistrarProvider.GO_DADDY)?.cachedSnapshot)
        assertEquals(RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS, cached.domains.size)
        assertFalse(cached.domainsComplete)
        assertEquals(listOf(RegistrarConnectionStore.OFFLINE_CACHE_WARNING), cached.warnings)
    }

    @Test
    fun offlineCacheTrimsOversizedFieldsWithinTheEncryptedBudget() {
        val noisy = RegistrarDomain(
            name = "noisy.example",
            status = "s".repeat(500),
            createdAtMillis = null,
            expiresAtMillis = null,
            autoRenew = null,
            locked = null,
            privacyEnabled = null,
            nameservers = (0 until 40).map { "ns$it.example.net" } + "n".repeat(400),
            metadata = (0 until 10).associate { "key$it" to "v".repeat(1_000) },
        )
        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.DYNADOT), RegistrarValidation("Dynadot · 1", listOf(noisy))),
        )

        val cached = checkNotNull(fixture.repository.load(RegistrarProvider.DYNADOT)?.cachedSnapshot)
        val domain = cached.domains.single()
        assertEquals(128, domain.status?.length)
        assertEquals(RegistrarConnectionPayloadCodec.MAX_NAMESERVERS, domain.nameservers.size)
        assertTrue(domain.metadata.size <= 4)
        assertTrue(domain.metadata.values.all { it.length <= 256 })
        assertFalse(cached.domainsComplete)
    }

    @Test
    fun reconnectingTheSameAccountKeepsItsCreationTime() {
        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.NAME_DOT_COM, key = "old", username = "ApoorvDarshan"), RegistrarValidation("ApoorvDarshan", emptyList())),
        )
        now += 5_000L
        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.NAME_DOT_COM, key = "rotated", username = " apoorvdarshan "), RegistrarValidation("apoorvdarshan", emptyList())),
        )
        assertEquals(10_000_000L, checkNotNull(fixture.repository.load(RegistrarProvider.NAME_DOT_COM)).account.createdAtMillis)

        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "old"), RegistrarValidation("Porkbun", emptyList())),
        )
        now += 5_000L
        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "new"), RegistrarValidation("Porkbun", emptyList())),
        )
        assertEquals(now, checkNotNull(fixture.repository.load(RegistrarProvider.PORKBUN)).account.createdAtMillis)
    }

    // region Port of iOS AccountPersistenceLogicTests

    @Test
    fun registrarCredentialRotationMatchesStableUsername() {
        val original = account(credentials(RegistrarProvider.NAME_DOT_COM, key = "old-token", username = "ApoorvDarshan"))

        assertTrue(
            RegistrarAccountMatching.matches(
                original,
                RegistrarProvider.NAME_DOT_COM,
                SecretValue.of("new-token"),
                mapOf("username" to " apoorvdarshan "),
            ),
        )
    }

    @Test
    fun registrarCredentialRotationMatchesStableOrganization() {
        val original = account(credentials(RegistrarProvider.GANDI, key = "old-token", organization = "Example-Org"))

        assertTrue(
            RegistrarAccountMatching.matches(
                original,
                RegistrarProvider.GANDI,
                SecretValue.of("new-token"),
                mapOf("organization" to "example-org"),
            ),
        )
    }

    @Test
    fun registrarWithoutStableIdentityDoesNotMergeRotatedKeys() {
        val original = account(credentials(RegistrarProvider.PORKBUN, key = "old-key", secret = "old-secret"))

        assertFalse(
            RegistrarAccountMatching.matches(original, RegistrarProvider.PORKBUN, SecretValue.of("new-key"), emptyMap()),
        )
    }

    @Test
    fun samePrimaryCredentialStillMatchesWithoutMetadata() {
        val original = account(credentials(RegistrarProvider.PORKBUN, key = "same-key", secret = "old-secret"))

        assertTrue(
            RegistrarAccountMatching.matches(original, RegistrarProvider.PORKBUN, SecretValue.of("same-key"), emptyMap()),
        )
        assertFalse(
            RegistrarAccountMatching.matches(original, RegistrarProvider.SPACESHIP, SecretValue.of("same-key"), emptyMap()),
        )
    }

    @Test
    fun blankStableIdentityNeverMatches() {
        val original = account(credentials(RegistrarProvider.GANDI, key = "old"))

        assertFalse(
            RegistrarAccountMatching.matches(original, RegistrarProvider.GANDI, SecretValue.of("new"), mapOf("organization" to "  ")),
        )
    }

    // endregion

    private fun account(credentials: RegistrarCredentials) = RegistrarAccount(
        displayName = "Account",
        credentials = credentials,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
