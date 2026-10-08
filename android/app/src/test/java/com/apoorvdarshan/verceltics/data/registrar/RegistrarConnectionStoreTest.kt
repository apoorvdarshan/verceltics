package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarConnectionStoreTest {
    private var now = 10_000_000L
    private var nextId = 0
    private val fixture = RegistrarStorageFixture()
    private val store = RegistrarConnectionStore(
        repository = fixture.repository,
        newAccountId = { "account-${++nextId}" },
        nowMillis = { now },
    )

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
        assertEquals("account-1", restored.accountId)
        assertEquals(listOf(RegistrarAccountSummary("account-1", "Porkbun · abcd")), restored.accounts)
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

        val refreshed = store.persistRefreshResult("account-1", original, listOf(domain("new.example")))

        refreshed as RegistrarRefreshOutcome.Refreshed
        assertTrue(refreshed.cached)
        assertEquals("account-1", refreshed.accountId)
        assertEquals(now, refreshed.snapshot.fetchedAtMillis)
        assertEquals(listOf("new.example"), refreshed.snapshot.domains.map { it.name })
        val saved = active(RegistrarProvider.GANDI)
        assertEquals(listOf("new.example"), saved.cachedSnapshot?.domains?.map { it.name })
        assertEquals(10_000_000L, saved.account.createdAtMillis)

        assertSame(
            RegistrarRefreshOutcome.Replaced,
            store.persistRefreshResult("account-1", credentials(RegistrarProvider.GANDI, key = "pat-2"), listOf(domain("x.example"))),
        )
        assertSame(RegistrarRefreshOutcome.Disconnected, store.persistRefreshResult("unknown", original, emptyList()))
        store.disconnect(RegistrarProvider.GANDI)
        assertSame(RegistrarRefreshOutcome.Disconnected, store.persistRefreshResult("account-1", original, emptyList()))
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
        val cached = checkNotNull(active(RegistrarProvider.GO_DADDY).cachedSnapshot)
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

        val cached = checkNotNull(active(RegistrarProvider.DYNADOT).cachedSnapshot)
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
        assertEquals(10_000_000L, active(RegistrarProvider.NAME_DOT_COM).account.createdAtMillis)

        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "old"), RegistrarValidation("Porkbun", emptyList())),
        )
        now += 5_000L
        store.acceptValidatedConnection(
            store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "new"), RegistrarValidation("Porkbun", emptyList())),
        )
        // A different Porkbun key without a stable identity is another account, not a rotation.
        assertEquals(now, active(RegistrarProvider.PORKBUN).account.createdAtMillis)
        assertEquals(2, accounts(RegistrarProvider.PORKBUN).accounts.size)
    }

    // region Multiple accounts per registrar (iOS RegistrarStore)

    @Test
    fun connectingAnotherIdentityAddsAnAccountWithoutDisconnectingTheFirst() {
        connect(RegistrarProvider.NAMECHEAP, key = "key-a", username = "alice", name = "alice", domains = listOf("a.example"))
        now += 1_000L

        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.NAMECHEAP, key = "key-b", username = "bob"),
            RegistrarValidation("bob", listOf(domain("b.example"))),
        )
        store.acceptValidatedConnection(commit)

        assertEquals("account-2", commit.accountId)
        assertEquals(
            listOf(RegistrarAccountSummary("account-1", "alice"), RegistrarAccountSummary("account-2", "bob")),
            commit.accounts,
        )
        val set = accounts(RegistrarProvider.NAMECHEAP)
        assertEquals("account-2", set.activeAccountId)
        assertEquals(SecretValue.of("key-a"), set.account("account-1")?.connection?.account?.credentials?.primary)
        assertEquals(listOf("a.example"), set.account("account-1")?.connection?.cachedSnapshot?.domains?.map { it.name })
        val restored = store.restore(RegistrarProvider.NAMECHEAP) as RegistrarRestoreResult.Restored
        assertEquals("bob", restored.accountName)
        assertEquals(listOf("b.example"), restored.cachedSnapshot?.domains?.map { it.name })
    }

    @Test
    fun reconnectingASavedIdentityRotatesCredentialsInPlaceAndActivatesIt() {
        connect(RegistrarProvider.NAME_DOT_COM, key = "alice-old", username = "Alice", name = "Alice", domains = listOf("a.example"))
        connect(RegistrarProvider.NAME_DOT_COM, key = "bob-key", username = "bob", name = "bob", domains = listOf("b.example"))
        now += 5_000L

        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.NAME_DOT_COM, key = "alice-new", username = " alice "),
            RegistrarValidation("Alice Renamed", listOf(domain("a2.example"))),
        )
        store.acceptValidatedConnection(commit)

        assertEquals("account-1", commit.accountId)
        val set = accounts(RegistrarProvider.NAME_DOT_COM)
        assertEquals(listOf("account-1", "account-2"), set.accounts.map { it.id })
        assertEquals("account-1", set.activeAccountId)
        val alice = checkNotNull(set.account("account-1")).connection
        assertEquals(SecretValue.of("alice-new"), alice.account.credentials.primary)
        assertEquals("Alice Renamed", alice.account.displayName)
        assertEquals(10_000_000L, alice.account.createdAtMillis)
        assertEquals(now, alice.account.updatedAtMillis)
        assertEquals(listOf("a2.example"), alice.cachedSnapshot?.domains?.map { it.name })
        assertEquals(SecretValue.of("bob-key"), set.account("account-2")?.connection?.account?.credentials?.primary)
    }

    @Test
    fun exactPrimaryMatchWinsOverStableIdentityMatch() {
        connect(RegistrarProvider.GANDI, key = "shared-org-1", organization = "Org", name = "First")
        connect(RegistrarProvider.GANDI, key = "exact", organization = "Other", name = "Second")

        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.GANDI, key = "exact", organization = "Org"),
            RegistrarValidation("Second again", emptyList()),
        )

        assertEquals("account-2", commit.accountId)
        assertEquals(2, commit.accounts.size)
    }

    @Test
    fun switchingActivatesAnotherAccountWithItsOwnCache() {
        connect(RegistrarProvider.PORKBUN, key = "one", name = "One", domains = listOf("one.example"))
        connect(RegistrarProvider.PORKBUN, key = "two", name = "Two", domains = listOf("two.example"))

        val switched = store.switchAccount(RegistrarProvider.PORKBUN, "account-1") as RegistrarRestoreResult.Restored

        assertEquals("account-1", switched.accountId)
        assertEquals("One", switched.accountName)
        assertEquals(listOf("one.example"), switched.cachedSnapshot?.domains?.map { it.name })
        assertEquals("account-1", accounts(RegistrarProvider.PORKBUN).activeAccountId)
        assertEquals(SecretValue.of("one"), checkNotNull(store.loadForRefresh(RegistrarProvider.PORKBUN)).connection.account.credentials.primary)
        val before = checkNotNull(fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes)
        assertEquals("account-1", (store.switchAccount(RegistrarProvider.PORKBUN, "account-1") as RegistrarRestoreResult.Restored).accountId)
        assertArrayEquals(before, fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes)
        assertThrows(RegistrarStoreException::class.java) { store.switchAccount(RegistrarProvider.PORKBUN, "missing") }
        assertThrows(RegistrarStoreException::class.java) { store.switchAccount(RegistrarProvider.GANDI, "account-1") }
    }

    @Test
    fun switchingIsRefusedWhileAConnectionIsPending() {
        connect(RegistrarProvider.PORKBUN, key = "one", name = "One")
        connect(RegistrarProvider.PORKBUN, key = "two", name = "Two")
        val pending = store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "three"), RegistrarValidation("Three", emptyList()))

        assertThrows(RegistrarStoreException::class.java) { store.switchAccount(RegistrarProvider.PORKBUN, "account-1") }
        store.acceptValidatedConnection(pending)
        assertEquals("account-1", (store.switchAccount(RegistrarProvider.PORKBUN, "account-1") as RegistrarRestoreResult.Restored).accountId)
    }

    @Test
    fun removingTheCurrentAccountActivatesTheFirstRemainingOne() {
        connect(RegistrarProvider.SPACESHIP, key = "one", name = "One", domains = listOf("one.example"))
        connect(RegistrarProvider.SPACESHIP, key = "two", name = "Two", domains = listOf("two.example"))
        connect(RegistrarProvider.SPACESHIP, key = "three", name = "Three", domains = listOf("three.example"))
        store.switchAccount(RegistrarProvider.SPACESHIP, "account-2")

        val afterCurrent = store.removeAccount(RegistrarProvider.SPACESHIP, "account-2") as RegistrarRestoreResult.Restored
        assertEquals("account-1", afterCurrent.accountId)
        assertEquals(listOf("account-1", "account-3"), afterCurrent.accounts.map { it.id })
        assertEquals(listOf("one.example"), afterCurrent.cachedSnapshot?.domains?.map { it.name })

        val afterOther = store.removeAccount(RegistrarProvider.SPACESHIP, "account-3") as RegistrarRestoreResult.Restored
        assertEquals("account-1", afterOther.accountId)
        assertEquals(listOf("account-1"), afterOther.accounts.map { it.id })
        assertEquals(listOf("account-1"), accounts(RegistrarProvider.SPACESHIP).accounts.map { it.id })
        assertEquals("account-1", (store.removeAccount(RegistrarProvider.SPACESHIP, "missing") as RegistrarRestoreResult.Restored).accountId)

        assertSame(RegistrarRestoreResult.NotConnected, store.removeAccount(RegistrarProvider.SPACESHIP, "account-1"))
        assertNull(fixture.stores.getValue(RegistrarProvider.SPACESHIP).bytes)
        assertSame(RegistrarRestoreResult.NotConnected, store.removeAccount(RegistrarProvider.SPACESHIP, "account-1"))
    }

    @Test
    fun removeAllErasesEveryAccountOfOnlyThatRegistrar() {
        connect(RegistrarProvider.DYNADOT, key = "one", name = "One")
        connect(RegistrarProvider.DYNADOT, key = "two", name = "Two")
        connect(RegistrarProvider.NAME_SILO, key = "silo", name = "Silo")

        store.disconnect(RegistrarProvider.DYNADOT)

        assertSame(RegistrarRestoreResult.NotConnected, store.restore(RegistrarProvider.DYNADOT))
        assertNull(fixture.stores.getValue(RegistrarProvider.DYNADOT).bytes)
        assertEquals("Silo", (store.restore(RegistrarProvider.NAME_SILO) as RegistrarRestoreResult.Restored).accountName)
    }

    @Test
    fun cancellingAnAddedAccountRestoresThePreviousAccountsAndActiveAccount() {
        connect(RegistrarProvider.GO_DADDY, key = "one", name = "One", domains = listOf("one.example"))
        val before = checkNotNull(fixture.stores.getValue(RegistrarProvider.GO_DADDY).bytes)

        val commit = store.saveValidatedConnection(credentials(RegistrarProvider.GO_DADDY, key = "two"), RegistrarValidation("Two", emptyList()))
        assertEquals(2, accounts(RegistrarProvider.GO_DADDY).accounts.size)
        assertTrue(store.rollbackValidatedConnection(commit))

        assertArrayEquals(before, fixture.stores.getValue(RegistrarProvider.GO_DADDY).bytes)
        val restored = store.restore(RegistrarProvider.GO_DADDY) as RegistrarRestoreResult.Restored
        assertEquals("account-1", restored.accountId)
        assertEquals(listOf("account-1"), restored.accounts.map { it.id })
    }

    @Test
    fun cancellingAnInPlaceRotationRestoresTheOldCredentials() {
        connect(RegistrarProvider.NAMECHEAP, key = "old", username = "alice", name = "alice")
        connect(RegistrarProvider.NAMECHEAP, key = "bob", username = "bob", name = "bob")

        val commit = store.saveValidatedConnection(
            credentials(RegistrarProvider.NAMECHEAP, key = "rotated", username = "alice"),
            RegistrarValidation("alice", emptyList()),
        )
        assertEquals("account-1", accounts(RegistrarProvider.NAMECHEAP).activeAccountId)
        assertTrue(store.rollbackValidatedConnection(commit))

        val set = accounts(RegistrarProvider.NAMECHEAP)
        assertEquals("account-2", set.activeAccountId)
        assertEquals(SecretValue.of("old"), set.account("account-1")?.connection?.account?.credentials?.primary)
    }

    @Test
    fun refreshCachesOnlyItsOwnAccountEvenAfterASwitch() {
        connect(RegistrarProvider.GANDI, key = "pat-1", organization = "One", name = "One", domains = listOf("one.example"))
        connect(RegistrarProvider.GANDI, key = "pat-2", organization = "Two", name = "Two", domains = listOf("two.example"))
        val refreshing = checkNotNull(store.loadForRefresh(RegistrarProvider.GANDI))
        assertEquals("account-2", refreshing.id)

        // The user switches while the refresh of account-2 is in flight.
        store.switchAccount(RegistrarProvider.GANDI, "account-1")
        now += 30_000L
        val outcome = store.persistRefreshResult(
            refreshing.id,
            refreshing.connection.account.credentials,
            listOf(domain("two.example"), domain("two-new.example")),
        ) as RegistrarRefreshOutcome.Refreshed

        assertTrue(outcome.cached)
        val set = accounts(RegistrarProvider.GANDI)
        assertEquals("account-1", set.activeAccountId)
        assertEquals(listOf("one.example"), set.account("account-1")?.connection?.cachedSnapshot?.domains?.map { it.name })
        assertEquals(
            listOf("two.example", "two-new.example"),
            set.account("account-2")?.connection?.cachedSnapshot?.domains?.map { it.name },
        )
        assertEquals(now, set.account("account-2")?.connection?.cachedSnapshot?.fetchedAtMillis)
        assertEquals(10_000_000L, set.account("account-1")?.connection?.cachedSnapshot?.fetchedAtMillis)
    }

    @Test
    fun refreshOfARemovedAccountReportsDisconnected() {
        connect(RegistrarProvider.PORKBUN, key = "one", name = "One")
        connect(RegistrarProvider.PORKBUN, key = "two", name = "Two")
        val refreshing = checkNotNull(store.loadForRefresh(RegistrarProvider.PORKBUN))

        store.removeAccount(RegistrarProvider.PORKBUN, refreshing.id)

        assertSame(
            RegistrarRefreshOutcome.Disconnected,
            store.persistRefreshResult(refreshing.id, refreshing.connection.account.credentials, emptyList()),
        )
        assertEquals(listOf("account-1"), accounts(RegistrarProvider.PORKBUN).accounts.map { it.id })
    }

    @Test
    fun accountsShareTheEncryptedBudgetWithoutStarvingSmallPortfolios() {
        val large = (0 until 1_000).map { domain("very-long-registrar-domain-name-$it.example", nameservers = longNameservers()) }
        connect(RegistrarProvider.GO_DADDY, key = "big-1", name = "Big One", domainList = large)
        connect(RegistrarProvider.GO_DADDY, key = "small", name = "Small", domains = listOf("small.example"))
        connect(RegistrarProvider.GO_DADDY, key = "big-2", name = "Big Two", domainList = large)

        val set = accounts(RegistrarProvider.GO_DADDY)
        val small = checkNotNull(set.account("account-2")?.connection?.cachedSnapshot)
        assertEquals(listOf("small.example"), small.domains.map { it.name })
        assertTrue(small.domainsComplete)
        val bigOne = checkNotNull(set.account("account-1")?.connection?.cachedSnapshot)
        val bigTwo = checkNotNull(set.account("account-3")?.connection?.cachedSnapshot)
        assertFalse(bigOne.domainsComplete)
        assertFalse(bigTwo.domainsComplete)
        assertEquals(listOf(RegistrarConnectionStore.OFFLINE_CACHE_WARNING), bigTwo.warnings)
        assertTrue(bigOne.domains.isNotEmpty() && bigTwo.domains.isNotEmpty())
        // Equal needs get equal shares (within one domain).
        assertTrue(kotlin.math.abs(bigOne.domains.size - bigTwo.domains.size) <= 1)
        val payload = RegistrarConnectionPayloadCodec.encode(set)
        assertTrue(payload.size <= RegistrarConnectionPayloadCodec.MAX_PLAINTEXT_BYTES)
    }

    @Test
    fun budgetSharingIsMaxMinFair() {
        assertEquals(listOf(10, 45, 45), RegistrarConnectionStore.shareCacheBudget(listOf(10, 200, 200), 100))
        assertEquals(listOf(10, 20, 30), RegistrarConnectionStore.shareCacheBudget(listOf(10, 20, 30), 100))
        assertEquals(listOf(0, 0), RegistrarConnectionStore.shareCacheBudget(listOf(5, 5), -10))
        assertEquals(listOf(0, 50, 50), RegistrarConnectionStore.shareCacheBudget(listOf(0, 80, 90), 100))
    }

    @Test
    fun theAccountLimitIsEnforcedButRotationStillWorksAtTheLimit() {
        repeat(RegistrarAccountSet.MAX_ACCOUNTS) { connect(RegistrarProvider.PORKBUN, key = "key-$it", name = "Account $it") }

        val error = assertThrows(RegistrarStoreException::class.java) {
            store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "one-too-many"), RegistrarValidation("Extra", emptyList()))
        }
        assertEquals(
            "You can save up to 16 Porkbun accounts on this device. Remove one before adding another.",
            error.message,
        )
        val rotated = store.saveValidatedConnection(credentials(RegistrarProvider.PORKBUN, key = "key-3"), RegistrarValidation("Account 3", emptyList()))
        assertEquals("account-4", rotated.accountId)
        assertEquals(RegistrarAccountSet.MAX_ACCOUNTS, rotated.accounts.size)
    }

    @Test
    fun aLegacyUserKeepsTheirAccountAsActiveWhenAddingAnother() {
        val legacy = RegistrarStoredConnection(
            account = RegistrarAccount(
                displayName = "ApoorvDarshan",
                credentials = credentials(RegistrarProvider.NAME_DOT_COM, key = "legacy-token", username = "ApoorvDarshan"),
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
            ),
            cachedSnapshot = RegistrarSnapshot(
                provider = RegistrarProvider.NAME_DOT_COM,
                accountName = "ApoorvDarshan",
                domains = listOf(domain("legacy.example")),
                fetchedAtMillis = 3_000L,
                domainsComplete = true,
                warnings = emptyList(),
            ),
        )
        fixture.stores.getValue(RegistrarProvider.NAME_DOT_COM).bytes = AccountEnvelopeCodec.encode(
            TestAccountCipher().encrypt(
                RegistrarConnectionPayloadCodec.encodeLegacy(legacy),
                RegistrarConnectionRepository.associatedData(RegistrarProvider.NAME_DOT_COM).toByteArray(StandardCharsets.UTF_8),
            ),
        )

        val restored = store.restore(RegistrarProvider.NAME_DOT_COM) as RegistrarRestoreResult.Restored
        assertEquals(RegistrarAccountSet.MIGRATED_ACCOUNT_ID, restored.accountId)
        assertEquals("ApoorvDarshan", restored.accountName)
        assertEquals(listOf("legacy.example"), restored.cachedSnapshot?.domains?.map { it.name })
        assertEquals(SecretValue.of("legacy-token"), checkNotNull(store.loadForRefresh(RegistrarProvider.NAME_DOT_COM)).connection.account.credentials.primary)

        connect(RegistrarProvider.NAME_DOT_COM, key = "work-token", username = "work", name = "work", domains = listOf("work.example"))
        val set = accounts(RegistrarProvider.NAME_DOT_COM)
        assertEquals(listOf(RegistrarAccountSet.MIGRATED_ACCOUNT_ID, "account-1"), set.accounts.map { it.id })
        val migrated = checkNotNull(set.account(RegistrarAccountSet.MIGRATED_ACCOUNT_ID)).connection
        assertTrue(migrated.account.credentials.sameAs(legacy.account.credentials))
        assertEquals(1_000L, migrated.account.createdAtMillis)
        assertEquals(legacy.cachedSnapshot, migrated.cachedSnapshot)
        val switched = store.switchAccount(RegistrarProvider.NAME_DOT_COM, RegistrarAccountSet.MIGRATED_ACCOUNT_ID) as RegistrarRestoreResult.Restored
        assertEquals(listOf("legacy.example"), switched.cachedSnapshot?.domains?.map { it.name })
    }

    // endregion

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

    @Test
    fun matchingIndexPrefersTheExactPrimaryCredential() {
        val accounts = listOf(
            account(credentials(RegistrarProvider.GANDI, key = "a", organization = "Org")),
            account(credentials(RegistrarProvider.GANDI, key = "b", organization = "Other")),
        )

        assertEquals(1, RegistrarAccountMatching.matchingIndex(accounts, RegistrarProvider.GANDI, SecretValue.of("b"), mapOf("organization" to "org")))
        assertEquals(0, RegistrarAccountMatching.matchingIndex(accounts, RegistrarProvider.GANDI, SecretValue.of("c"), mapOf("organization" to "ORG")))
        assertNull(RegistrarAccountMatching.matchingIndex(accounts, RegistrarProvider.GANDI, SecretValue.of("c"), mapOf("organization" to "new")))
    }

    // endregion

    private fun connect(
        provider: RegistrarProvider,
        key: String,
        name: String,
        username: String = "alice",
        organization: String = "",
        domains: List<String> = emptyList(),
        domainList: List<RegistrarDomain> = domains.map { domain(it) },
    ): RegistrarConnectionCommit = store.saveValidatedConnection(
        credentials(provider, key = key, username = username, organization = organization),
        RegistrarValidation(name, domainList),
    ).also(store::acceptValidatedConnection)

    private fun accounts(provider: RegistrarProvider): RegistrarAccountSet = checkNotNull(fixture.repository.load(provider))

    private fun active(provider: RegistrarProvider): RegistrarStoredConnection = accounts(provider).active.connection

    private fun longNameservers() = (0 until 4).map { "ns$it.a-rather-long-nameserver-hostname.example.net" }

    private fun account(credentials: RegistrarCredentials) = RegistrarAccount(
        displayName = "Account",
        credentials = credentials,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )
}
