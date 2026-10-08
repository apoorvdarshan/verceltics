package com.apoorvdarshan.verceltics.data.account

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelAccountRepositoryTest {
    @Test
    fun savesEncryptedEnvelopeAndLoadsAccount() {
        val store = MemoryAtomicBytesStore()
        val repository = VercelAccountRepository(store, TestAccountCipher())
        val token = "sensitive-vercel-token"
        val account = testAccount(token)

        repository.save(account)

        val stored = checkNotNull(store.bytes)
        assertFalse(String(stored, StandardCharsets.ISO_8859_1).contains(token))
        val loaded = checkNotNull(repository.load())
        assertEquals(account.id, loaded.id)
        assertEquals(account.token, loaded.token)
        assertEquals("vercel", loaded.providerId)
    }

    @Test
    fun missingAccountReturnsNullAndDeleteIsExplicit() {
        val store = MemoryAtomicBytesStore()
        val repository = VercelAccountRepository(store, TestAccountCipher())

        assertNull(repository.load())
        assertTrue(repository.loadAll().isEmpty)
        repository.save(testAccount("token"))
        repository.delete()
        assertNull(repository.load())
        assertNull(store.bytes)
    }

    @Test
    fun corruptedDataIsSurfacedWithoutDeletion() {
        val store = MemoryAtomicBytesStore().apply { bytes = byteArrayOf(1, 2, 3) }
        val repository = VercelAccountRepository(store, TestAccountCipher())

        assertThrows(IllegalArgumentException::class.java) { repository.load() }
        assertEquals(3, store.bytes?.size)
    }

    @Test
    fun severalAccountsAndTheActiveIdArePersistedTogether() {
        val store = MemoryAtomicBytesStore()
        val repository = VercelAccountRepository(store, TestAccountCipher())
        val accounts = VercelAccounts.EMPTY
            .connect(testAccount("token-a", id = "user_a"), 1L)
            .connect(testAccount("token-b", id = "user_b"), 2L)
            .switchTo("user_a")

        repository.saveAll(accounts)

        val reloaded = VercelAccountRepository(store, TestAccountCipher()).loadAll()
        assertEquals(listOf("user_a", "user_b"), reloaded.accounts.map(VercelAccount::id))
        assertEquals("user_a", reloaded.activeAccountId)
        val stored = String(checkNotNull(store.bytes), StandardCharsets.ISO_8859_1)
        assertFalse(stored.contains("token-a") || stored.contains("token-b"))
    }

    @Test
    fun savingTheSameIdentityAgainRotatesItsTokenInPlace() {
        val repository = VercelAccountRepository(MemoryAtomicBytesStore(), TestAccountCipher())
        repository.save(testAccount("first", id = "user_a"))
        repository.save(testAccount("other", id = "user_b"))

        repository.save(testAccount("rotated", id = "user_a", updatedAt = 99L))

        val saved = repository.loadAll()
        assertEquals(listOf("user_a", "user_b"), saved.accounts.map(VercelAccount::id))
        assertEquals("user_a", saved.activeAccountId)
        assertEquals(SecretValue.of("rotated"), saved.find("user_a")?.token)
    }

    @Test
    fun savingAnEmptyListRemovesEveryCredential() {
        val store = MemoryAtomicBytesStore()
        val legacy = MemoryAtomicBytesStore()
        val repository = VercelAccountRepository(store, TestAccountCipher(), legacy)
        repository.save(testAccount("token"))

        repository.saveAll(VercelAccounts.EMPTY)

        assertNull(store.bytes)
        assertNull(legacy.bytes)
        assertTrue(repository.loadAll().isEmpty)
    }

    @Test
    fun aVersionTwoLegacyAccountMigratesLosslesslyAsTheActiveAccount() {
        val store = MemoryAtomicBytesStore()
        val legacy = MemoryAtomicBytesStore().apply {
            bytes = legacyEnvelope(
                versionTwoPayload(
                    id = "user_legacy",
                    displayName = "Legacy Apoorv",
                    email = "legacy@example.com",
                    token = "legacy-v2-token",
                    createdAt = 1_000L,
                    updatedAt = 2_000L,
                    username = "apoorvdarshan",
                    hasLongAnalyticsHistory = true,
                ),
            )
        }
        val repository = VercelAccountRepository(store, TestAccountCipher(), legacy)

        val migrated = repository.loadAll()

        assertEquals("user_legacy", migrated.activeAccountId)
        val account = checkNotNull(migrated.active)
        assertEquals("Legacy Apoorv", account.displayName)
        assertEquals("legacy@example.com", account.email)
        assertEquals(SecretValue.of("legacy-v2-token"), account.token)
        assertEquals(1_000L, account.createdAtMillis)
        assertEquals(2_000L, account.updatedAtMillis)
        assertEquals("apoorvdarshan", account.username)
        assertTrue(account.hasLongAnalyticsHistory)
        assertNull("The legacy record is deleted once the list is written.", legacy.bytes)
        assertNotNull(store.bytes)
        assertEquals(
            "The migrated list is read back without the legacy record.",
            SecretValue.of("legacy-v2-token"),
            VercelAccountRepository(store, TestAccountCipher()).load()?.token,
        )
    }

    @Test
    fun aVersionOneLegacyAccountMigratesLosslesslyAsTheActiveAccount() {
        val legacy = MemoryAtomicBytesStore().apply {
            bytes = legacyEnvelope(versionOnePayload(id = "user_v1", token = "legacy-v1-token", email = null))
        }
        val repository = VercelAccountRepository(MemoryAtomicBytesStore(), TestAccountCipher(), legacy)

        val migrated = repository.loadAll()

        val account = checkNotNull(migrated.active)
        assertEquals("user_v1", account.id)
        assertEquals("Legacy One", account.displayName)
        assertNull(account.email)
        assertEquals(SecretValue.of("legacy-v1-token"), account.token)
        assertEquals(10L, account.createdAtMillis)
        assertEquals(20L, account.updatedAtMillis)
        assertNull(account.username)
        assertFalse(account.hasLongAnalyticsHistory)
        assertNull(legacy.bytes)
    }

    @Test
    fun anInterruptedMigrationIsRepeatedWithoutDuplicatingTheAccount() {
        val store = MemoryAtomicBytesStore()
        val legacyBytes = legacyEnvelope(versionOnePayload(id = "user_v1", token = "legacy-v1-token", email = null))
        // The list was written but the process died before the legacy record was deleted.
        VercelAccountRepository(store, TestAccountCipher(), MemoryAtomicBytesStore().apply { bytes = legacyBytes.copyOf() })
            .loadAll()
        val leftover = MemoryAtomicBytesStore().apply { bytes = legacyBytes.copyOf() }

        val reloaded = VercelAccountRepository(store, TestAccountCipher(), leftover).loadAll()

        assertEquals(listOf("user_v1"), reloaded.accounts.map(VercelAccount::id))
        assertNull(leftover.bytes)
    }

    @Test
    fun aLegacyAccountWrittenByAnOlderBuildJoinsAnExistingListWithoutReplacingTheActiveAccount() {
        val store = MemoryAtomicBytesStore()
        VercelAccountRepository(store, TestAccountCipher()).save(testAccount("current-token", id = "user_current"))
        val legacy = MemoryAtomicBytesStore().apply {
            bytes = legacyEnvelope(versionOnePayload(id = "user_v1", token = "legacy-v1-token", email = null))
        }

        val merged = VercelAccountRepository(store, TestAccountCipher(), legacy).loadAll()

        assertEquals(listOf("user_current", "user_v1"), merged.accounts.map(VercelAccount::id))
        assertEquals("user_current", merged.activeAccountId)
        assertNull(legacy.bytes)
    }

    @Test
    fun aCorruptLegacyAccountIsSurfacedAndKept() {
        val legacy = MemoryAtomicBytesStore().apply { bytes = byteArrayOf(9, 9, 9) }
        val store = MemoryAtomicBytesStore()
        val repository = VercelAccountRepository(store, TestAccountCipher(), legacy)

        assertThrows(IllegalArgumentException::class.java) { repository.loadAll() }
        assertEquals(3, legacy.bytes?.size)
        assertNull(store.bytes)
    }

    @Test
    fun theLegacyAccountIsKeptWhenTheListCannotBeWritten() {
        val legacyBytes = legacyEnvelope(versionOnePayload(id = "user_v1", token = "legacy-v1-token", email = null))
        val legacy = MemoryAtomicBytesStore().apply { bytes = legacyBytes.copyOf() }
        val failing = MemoryAtomicBytesStore().apply { failWrites = true }
        val repository = VercelAccountRepository(failing, TestAccountCipher(), legacy)

        assertThrows(IOException::class.java) { repository.loadAll() }
        assertEquals("Nothing is lost when the list write fails.", legacyBytes.size, legacy.bytes?.size)
    }

    @Test
    fun deletingEverythingAlsoRemovesALegacyRecord() {
        val store = MemoryAtomicBytesStore()
        val legacy = MemoryAtomicBytesStore().apply {
            bytes = legacyEnvelope(versionOnePayload(id = "user_v1", token = "legacy-v1-token", email = null))
        }
        val repository = VercelAccountRepository(store, TestAccountCipher(), legacy)

        repository.deleteAll()

        assertNull(store.bytes)
        assertNull(legacy.bytes)
        assertTrue("A removed legacy token is never resurrected.", repository.loadAll().isEmpty)
    }

    private fun legacyEnvelope(payload: ByteArray): ByteArray {
        val associatedData = "verceltics.account-envelope.v1:vercel".toByteArray(StandardCharsets.UTF_8)
        return AccountEnvelopeCodec.encode(TestAccountCipher().encrypt(payload, associatedData))
    }

    private fun versionOnePayload(id: String, token: String, email: String?): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUtf8("vercel")
                output.writeUtf8(id)
                output.writeUtf8("Legacy One")
                output.writeBoolean(email != null)
                email?.let { output.writeUtf8(it) }
                output.writeUtf8(token)
                output.writeLong(10L)
                output.writeLong(20L)
            }
        }.toByteArray()

    @Suppress("SameParameterValue")
    private fun versionTwoPayload(
        id: String,
        displayName: String,
        email: String,
        token: String,
        createdAt: Long,
        updatedAt: Long,
        username: String,
        hasLongAnalyticsHistory: Boolean,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(2)
            output.writeUtf8("vercel")
            output.writeUtf8(id)
            output.writeUtf8(displayName)
            output.writeBoolean(true)
            output.writeUtf8(email)
            output.writeUtf8(token)
            output.writeLong(createdAt)
            output.writeLong(updatedAt)
            output.writeBoolean(true)
            output.writeUtf8(username)
            output.writeBoolean(hasLongAnalyticsHistory)
        }
    }.toByteArray()

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun testAccount(token: String, id: String = "user_123", updatedAt: Long = 11L) = VercelAccount(
        id = id,
        displayName = "Apoorv",
        email = "apoorv@example.com",
        token = SecretValue.of(token),
        createdAtMillis = 10L,
        updatedAtMillis = updatedAt,
    )

    private class MemoryAtomicBytesStore : AtomicBytesStore {
        var bytes: ByteArray? = null
        var failWrites = false

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("Disk full")
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            bytes = null
        }
    }

    /** Test-only reversible cipher; production always constructs AndroidKeystoreAccountCipher. */
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
}
