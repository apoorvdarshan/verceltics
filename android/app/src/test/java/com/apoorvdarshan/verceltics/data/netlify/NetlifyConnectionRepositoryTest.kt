package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.SealedPayload
import com.apoorvdarshan.verceltics.data.account.SecretValue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NetlifyConnectionRepositoryTest {
    @Test
    fun encryptedProviderRecordRoundTripsTokenAndOfflineInventory() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val connection = connection(token = "sensitive-netlify-token", snapshot = snapshot())

        repository.save(connection)

        val encrypted = checkNotNull(store.bytes)
        assertFalse(String(encrypted, StandardCharsets.UTF_8).contains("sensitive-netlify-token"))
        val loaded = checkNotNull(repository.load())
        assertEquals(connection.account.id, loaded.account.id)
        assertEquals(connection.account.personalToken, loaded.account.personalToken)
        assertEquals(connection.cachedSnapshot, loaded.cachedSnapshot)
        assertFalse(loaded.toString().contains("sensitive-netlify-token"))
        assertFalse(loaded.account.toString().contains("sensitive-netlify-token"))
    }

    @Test
    fun recordUsesDedicatedNoBackupSlotAadAndKeyAlias() {
        assertEquals("accounts/netlify-personal-token.account", NetlifyConnectionRepository.ACCOUNT_PATH)
        assertEquals(
            "verceltics.account-envelope.v1:netlify-personal-token",
            NetlifyConnectionRepository.ASSOCIATED_DATA,
        )
        assertEquals("verceltics.account-storage.netlify.v1", NetlifyConnectionRepository.KEY_ALIAS)
        assertFalse(NetlifyConnectionRepository.ACCOUNT_PATH.contains("vercel-personal-token"))
        assertFalse(NetlifyConnectionRepository.ASSOCIATED_DATA.endsWith(":vercel"))
        assertFalse(NetlifyConnectionRepository.KEY_ALIAS == "verceltics.account-storage.v1")
    }

    @Test
    fun missingReturnsNullAndCorruptionIsSurfacedWithoutDeletion() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        assertNull(repository.load())

        store.bytes = byteArrayOf(1, 2, 3)
        assertThrows(IllegalArgumentException::class.java, repository::load)
        assertEquals(3, store.bytes?.size)
    }

    @Test
    fun restoreIsOfflineRedactedAndFailedRefreshPreservesLastSnapshot() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val original = connection(token = "token", snapshot = snapshot(fetchedAt = 100L))
        repository.save(original)
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 2_000_000L })

        val restored = connectionStore.restore() as NetlifyRestoreResult.Restored
        assertEquals(original.account.profile(), restored.profile)
        assertEquals(original.cachedSnapshot, restored.cachedSnapshot)
        assertTrue(restored.cacheIsStale)

        assertFalse(
            connectionStore.persistRefreshResult(
                NetlifyFetchResult.Failure(
                    NetlifyFailure(NetlifyFailureKind.NETWORK, "Netlify could not be reached."),
                ),
            ),
        )
        assertEquals(original.cachedSnapshot, repository.load()?.cachedSnapshot)
    }

    @Test
    fun incompleteRefreshCannotReplaceExistingFullerOfflineInventory() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val original = connection(token = "token", snapshot = snapshot(siteCount = 2))
        repository.save(original)
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 500L })
        val failure = NetlifyFailure(NetlifyFailureKind.NETWORK, "Netlify could not be reached.")
        val partialSnapshot = NetlifySnapshot(
            profile = PROFILE,
            sites = listOf(site(1)),
            fetchedAtMillis = 500L,
            sitesComplete = false,
            warnings = listOf("The Netlify site list is incomplete: ${failure.message}"),
        )

        assertFalse(
            connectionStore.persistRefreshResult(NetlifyFetchResult.Partial(partialSnapshot, failure)),
        )
        assertEquals(2, repository.load()?.cachedSnapshot?.sites?.size)
    }

    @Test
    fun persistedCacheIsBoundedAndTruthfullyMarkedIncomplete() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 100L })
        val liveSnapshot = snapshot(siteCount = NetlifyConnectionStore.MAX_CACHED_SITES + 5)

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("token"),
            NetlifyFetchResult.Complete(liveSnapshot),
        )
        connectionStore.acceptValidatedConnection(commit)

        val cached = checkNotNull(repository.load()?.cachedSnapshot)
        assertEquals(NetlifyConnectionStore.MAX_CACHED_SITES, cached.sites.size)
        assertFalse(cached.sitesComplete)
        assertTrue(cached.warnings.single().contains("bounded"))
    }

    @Test
    fun cancelledValidatedSaveIntoEmptySlotDeletesOnlyItsOwnRevision() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 200L })

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("cancelled-token"),
            NetlifyFetchResult.Complete(snapshot()),
        )

        assertTrue(repository.load() != null)
        assertTrue(connectionStore.rollbackValidatedConnection(commit))
        assertNull(repository.load())
        assertNull(store.bytes)
    }

    @Test
    fun cancelledReplacementRestoresExactPriorEncryptedAccountAndCache() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val prior = connection(token = "prior-token", snapshot = snapshot(siteCount = 2))
        repository.save(prior)
        val exactPriorEnvelope = checkNotNull(store.bytes).copyOf()
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 300L })

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("replacement-token"),
            NetlifyFetchResult.Complete(snapshot(fetchedAt = 300L, siteCount = 3)),
        )

        assertTrue(connectionStore.rollbackValidatedConnection(commit))
        assertArrayEquals(exactPriorEnvelope, store.bytes)
        val restored = checkNotNull(repository.load())
        assertEquals(prior.account.personalToken, restored.account.personalToken)
        assertEquals(prior.cachedSnapshot, restored.cachedSnapshot)
        exactPriorEnvelope.fill(0)
    }

    @Test
    fun overlappingPendingReplacementsAreRejectedSoRollbackRestoresOriginalRevision() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        repository.save(connection(token = "base-token", snapshot = snapshot()))
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 400L })
        val pendingCommit = connectionStore.saveValidatedConnection(
            SecretValue.of("pending-token"),
            NetlifyFetchResult.Complete(snapshot(fetchedAt = 400L, siteCount = 2)),
        )

        assertThrows(IllegalStateException::class.java) {
            connectionStore.saveValidatedConnection(
                SecretValue.of("overlapping-token"),
                NetlifyFetchResult.Complete(snapshot(fetchedAt = 401L, siteCount = 3)),
            )
        }
        assertEquals(SecretValue.of("pending-token"), repository.load()?.account?.personalToken)
        assertTrue(connectionStore.rollbackValidatedConnection(pendingCommit))
        assertEquals(SecretValue.of("base-token"), repository.load()?.account?.personalToken)
        assertEquals(1, repository.load()?.cachedSnapshot?.sites?.size)
    }

    @Test
    fun staleRefreshCompareAndSwapCannotResurrectAfterDisconnect() {
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection(token = "base-token", snapshot = snapshot()))
        val stale = checkNotNull(repository.loadWithRevision())

        repository.delete()

        assertFalse(
            repository.saveIfRevisionMatches(
                stale.revision,
                connection(token = "stale-refresh-token", snapshot = snapshot(fetchedAt = 600L)),
            ),
        )
        assertNull(repository.load())
    }

    @Test
    fun staleRefreshCompareAndSwapCannotOverwriteNewerSave() {
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        repository.save(connection(token = "base-token", snapshot = snapshot()))
        val stale = checkNotNull(repository.loadWithRevision())
        repository.save(connection(token = "newer-token", snapshot = snapshot(fetchedAt = 700L, siteCount = 3)))

        assertFalse(
            repository.saveIfRevisionMatches(
                stale.revision,
                connection(token = "stale-refresh-token", snapshot = snapshot(fetchedAt = 650L)),
            ),
        )
        assertEquals(SecretValue.of("newer-token"), repository.load()?.account?.personalToken)
        assertEquals(3, repository.load()?.cachedSnapshot?.sites?.size)
    }

    @Test
    fun persistRefreshResultCannotResurrectRecordDisconnectedAfterItsLoad() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        repository.save(connection(token = "base-token", snapshot = snapshot()))
        store.resetReadInterception { readNumber ->
            if (readNumber == 2) store.bytes = null
        }
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 750L })

        val persisted = connectionStore.persistRefreshResult(
            NetlifyFetchResult.Complete(snapshot(fetchedAt = 750L, siteCount = 2)),
        )

        store.clearReadInterception()
        assertFalse(persisted)
        assertNull(repository.load())
    }

    @Test
    fun persistRefreshResultCannotOverwriteRecordSavedAfterItsLoad() {
        val newerStore = MemoryAtomicBytesStore()
        NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, newerStore), TestAccountCipher()).save(
            connection(token = "newer-token", snapshot = snapshot(fetchedAt = 760L, siteCount = 3)),
        )
        val newerEnvelope = checkNotNull(newerStore.bytes).copyOf()
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        repository.save(connection(token = "base-token", snapshot = snapshot()))
        store.resetReadInterception { readNumber ->
            if (readNumber == 2) store.bytes = newerEnvelope.copyOf()
        }
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 770L })

        val persisted = connectionStore.persistRefreshResult(
            NetlifyFetchResult.Complete(snapshot(fetchedAt = 770L, siteCount = 2)),
        )

        store.clearReadInterception()
        assertFalse(persisted)
        assertEquals(SecretValue.of("newer-token"), repository.load()?.account?.personalToken)
        assertEquals(3, repository.load()?.cachedSnapshot?.sites?.size)
        newerEnvelope.fill(0)
    }

    @Test
    fun sameAccountPartialReconnectPreservesFullerExistingCache() {
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, MemoryAtomicBytesStore()), TestAccountCipher())
        val fuller = snapshot(fetchedAt = 100L, siteCount = 3)
        repository.save(connection(token = "prior-token", snapshot = fuller))
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 800L })
        val failure = NetlifyFailure(NetlifyFailureKind.NETWORK, "Netlify could not be reached.")
        val partial = NetlifySnapshot(
            profile = PROFILE,
            sites = listOf(site(0)),
            fetchedAtMillis = 800L,
            sitesComplete = false,
            warnings = listOf("The Netlify site list is incomplete: ${failure.message}"),
        )

        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("replacement-token"),
            NetlifyFetchResult.Partial(partial, failure),
        )
        val replacement = checkNotNull(repository.load())

        assertEquals(SecretValue.of("replacement-token"), replacement.account.personalToken)
        assertEquals(fuller.sites, replacement.cachedSnapshot?.sites)
        assertEquals(fuller.fetchedAtMillis, replacement.cachedSnapshot?.fetchedAtMillis)
        assertTrue(replacement.cachedSnapshot?.sitesComplete == true)
        connectionStore.acceptValidatedConnection(commit)
    }

    @Test
    fun acceptedCommitMakesLateRollbackANoOp() {
        val store = MemoryAtomicBytesStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, store), TestAccountCipher())
        val connectionStore = NetlifyConnectionStore(repository, nowMillis = { 500L })
        val commit = connectionStore.saveValidatedConnection(
            SecretValue.of("accepted-token"),
            NetlifyFetchResult.Complete(snapshot(fetchedAt = 500L, siteCount = 2)),
        )

        connectionStore.acceptValidatedConnection(commit)

        assertFalse(connectionStore.rollbackValidatedConnection(commit))
        assertEquals(SecretValue.of("accepted-token"), repository.load()?.account?.personalToken)
        assertEquals(2, repository.load()?.cachedSnapshot?.sites?.size)
    }

    @Test
    fun unreadableAndUnavailableSavedRecordsAreNotMisreportedAsDisconnected() {
        val corruptStore = MemoryAtomicBytesStore().apply { bytes = byteArrayOf(9, 8, 7) }
        assertEquals(
            NetlifyRestoreResult.Unavailable(NetlifyRestoreProblem.SAVED_RECORD_UNREADABLE),
            NetlifyConnectionStore(
                NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, corruptStore), TestAccountCipher()),
            ).restore(),
        )
        assertEquals(3, corruptStore.bytes?.size)

        val secureStore = MemoryAtomicBytesStore()
        NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, secureStore), TestAccountCipher()).save(
            connection(token = "token", snapshot = null),
        )
        val securityCipher = object : AccountCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
                throw SecurityException("keystore unavailable")

            override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
                throw SecurityException("keystore unavailable")
        }
        assertEquals(
            NetlifyRestoreResult.Unavailable(NetlifyRestoreProblem.SECURE_STORAGE_UNAVAILABLE),
            NetlifyConnectionStore(
                NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, secureStore), securityCipher),
            ).restore(),
        )
    }

    private fun connection(token: String, snapshot: NetlifySnapshot?) = NetlifyStoredConnection(
        account = NetlifyAccount(
            id = PROFILE.id,
            displayName = PROFILE.displayName,
            email = PROFILE.email,
            avatarUrl = PROFILE.avatarUrl,
            personalToken = SecretValue.of(token),
            createdAtMillis = 10L,
            updatedAtMillis = 100L,
        ),
        cachedSnapshot = snapshot,
    )

    private fun snapshot(fetchedAt: Long = 100L, siteCount: Int = 1) = NetlifySnapshot(
        profile = PROFILE,
        sites = List(siteCount) { site(it) },
        fetchedAtMillis = fetchedAt,
        sitesComplete = true,
        warnings = emptyList(),
    )

    private fun site(index: Int) = NetlifySite(
        id = "site-$index",
        name = "Site $index",
        subtitle = "site-$index.example.com",
        url = "https://site-$index.example.com",
        status = "current",
        updatedAtMillis = 50L,
        adminUrl = "https://app.netlify.com/sites/site-$index",
    )

    private class MemoryAtomicBytesStore : AtomicBytesStore {
        var bytes: ByteArray? = null
        private var readCount = 0
        private var onRead: ((Int) -> Unit)? = null

        override fun read(): ByteArray? {
            readCount += 1
            onRead?.invoke(readCount)
            return bytes?.copyOf()
        }

        override fun write(bytes: ByteArray) {
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }

        fun resetReadInterception(block: (Int) -> Unit) {
            readCount = 0
            onRead = block
        }

        fun clearReadInterception() {
            onRead = null
            readCount = 0
        }
    }

    private class TestAccountCipher : AccountCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedPayload =
            SealedPayload(ByteArray(12) { 7 }, transform(plaintext, associatedData))

        override fun decrypt(payload: SealedPayload, associatedData: ByteArray): ByteArray =
            transform(payload.ciphertext(), associatedData)

        private fun transform(input: ByteArray, associatedData: ByteArray): ByteArray =
            ByteArray(input.size) { index ->
                (input[index].toInt() xor associatedData[index % associatedData.size].toInt()).toByte()
            }
    }

    companion object {
        private val PROFILE = NetlifyProfile("user-123", "Apoorv", "a@example.com", null)
    }
}
