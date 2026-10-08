package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pads values like an AEAD tag would, since sealed envelopes hold at least 16 ciphertext bytes. */
private fun encodeValue(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8) + ByteArray(16) { '#'.code.toByte() }

private fun decodeValue(bytes: ByteArray): String {
    require(bytes.size >= 16 && bytes.takeLast(16).all { it == '#'.code.toByte() }) { "Not a test record." }
    return String(bytes, 0, bytes.size - 16, StandardCharsets.UTF_8)
}

class ProviderAccountVaultTest {
    private val layout = AccountVaultLayout(
        domain = "test-provider",
        primaryPath = "accounts/test-provider.account",
        primaryAssociatedData = "verceltics.account-envelope.v1:test-provider",
    )
    private val cipher = TestAccountCipher()

    private inner class Fixture {
        val primary = SimpleMemoryStore()
        val files = primaryFiles(layout.primaryPath, primary)
        private var nextId = 0
        val vault = ProviderAccountVault(
            layout = layout,
            storeFactory = files,
            cipher = cipher,
            encode = ::encodeValue,
            decode = { accountId, bytes -> "$accountId=" + decodeValue(bytes) },
            newAccountId = { "account-${++nextId}" },
            storedAccountIds = {
                files.extra.filter { (path, store) -> path.endsWith(".account") && store.bytes != null }
                    .keys.map { it.substringAfterLast('/').removeSuffix(".account") }
            },
        )

        /** Writes a record exactly like the pre-multi-account repositories did (legacy path + AAD). */
        fun writeLegacyRecord(value: String) {
            val sealed = cipher.encrypt(
                encodeValue(value),
                layout.primaryAssociatedData.toByteArray(StandardCharsets.UTF_8),
            )
            primary.write(AccountEnvelopeCodec.encode(sealed))
        }
    }

    @Test
    fun legacySingleRecordIsTheActivePrimaryAccountWithoutAnyRewrite() {
        val fixture = Fixture()
        fixture.writeLegacyRecord("legacy-account")
        val legacyBytes = checkNotNull(fixture.primary.bytes).copyOf()

        assertEquals(AccountVaultIndex(listOf("primary"), "primary"), fixture.vault.index())
        assertEquals("primary", fixture.vault.activeAccountId())
        assertEquals("primary=legacy-account", fixture.vault.loadActive()?.value)
        // Restoring never writes: the upgrade is lossless by construction.
        assertArrayEquals(legacyBytes, fixture.primary.bytes)
        assertEquals(setOf(layout.primaryPath), fixture.files.occupiedPaths())
    }

    @Test
    fun addingAnAccountKeepsTheLegacyRecordAndWritesAnEncryptedIndex() {
        val fixture = Fixture()
        fixture.writeLegacyRecord("legacy-account")
        val legacyBytes = checkNotNull(fixture.primary.bytes).copyOf()

        val commit = fixture.vault.saveWithRevision(null, "second")
        fixture.vault.accept(commit)

        assertTrue(commit.isNewAccount)
        assertEquals("account-1", commit.accountId)
        assertArrayEquals(legacyBytes, fixture.primary.bytes)
        assertEquals(AccountVaultIndex(listOf("primary", "account-1"), "account-1"), fixture.vault.index())
        assertEquals("account-1=second", fixture.vault.loadActive()?.value)
        assertEquals("primary=legacy-account", fixture.vault.load("primary")?.value)
        val indexBytes = checkNotNull(fixture.files.bytes(layout.indexPath))
        assertFalse(String(indexBytes, StandardCharsets.ISO_8859_1).contains("account-1"))
        assertNotNull(fixture.files.bytes("accounts/test-provider/account-1.account"))
    }

    @Test
    fun firstSaveIntoAnEmptyVaultUsesThePrimarySlot() {
        val fixture = Fixture()
        assertEquals(AccountVaultIndex.EMPTY, fixture.vault.index())
        assertNull(fixture.vault.loadActive())

        val id = fixture.vault.save("only")

        assertEquals("primary", id)
        assertNotNull(fixture.primary.bytes)
        assertEquals("primary=only", fixture.vault.loadActive()?.value)
    }

    @Test
    fun rotationInPlaceKeepsTheAccountIdAndOtherAccounts() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))

        val rotation = fixture.vault.saveWithRevision("primary", "first-rotated")
        fixture.vault.accept(rotation)

        assertFalse(rotation.isNewAccount)
        assertEquals(listOf("primary", "account-1"), fixture.vault.index().accountIds)
        assertEquals("primary", fixture.vault.activeAccountId())
        assertEquals("primary=first-rotated", fixture.vault.load("primary")?.value)
        assertEquals("account-1=second", fixture.vault.load("account-1")?.value)
    }

    @Test
    fun switchAndRemoveFollowIosOrder() {
        val fixture = Fixture()
        fixture.vault.save("a")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "b"))
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "c"))
        assertEquals("account-2", fixture.vault.activeAccountId())

        assertTrue(fixture.vault.activate("account-1"))
        assertFalse(fixture.vault.activate("missing"))
        assertEquals("account-1=b", fixture.vault.loadActive()?.value)

        // Removing the active account activates the first remaining one (iOS `removeAccount`).
        assertEquals("primary", fixture.vault.delete("account-1"))
        assertNull(fixture.files.bytes("accounts/test-provider/account-1.account"))
        assertEquals(AccountVaultIndex(listOf("primary", "account-2"), "primary"), fixture.vault.index())

        // Removing an inactive account keeps the active one.
        assertEquals("primary", fixture.vault.delete("account-2"))
        assertEquals(AccountVaultIndex(listOf("primary"), "primary"), fixture.vault.index())

        assertNull(fixture.vault.delete("primary"))
        assertEquals(AccountVaultIndex.EMPTY, fixture.vault.index())
        assertTrue(fixture.files.occupiedPaths().isEmpty())
    }

    @Test
    fun removeAllErasesEveryRecordAndTheIndex() {
        val fixture = Fixture()
        fixture.writeLegacyRecord("legacy")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "b"))
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "c"))

        fixture.vault.deleteAll()

        assertTrue(fixture.files.occupiedPaths().isEmpty())
        assertNull(fixture.vault.loadActive())
        assertEquals(AccountVaultIndex.EMPTY, fixture.vault.index())
    }

    @Test
    fun rollingBackANewAccountRestoresTheExactPriorFilesAndActiveAccount() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))
        fixture.vault.activate("primary")
        val indexBefore = checkNotNull(fixture.files.bytes(layout.indexPath))

        val commit = fixture.vault.saveWithRevision(null, "cancelled")
        assertEquals("account-2=cancelled", fixture.vault.loadActive()?.value)

        assertTrue(fixture.vault.rollbackIfRevisionMatches(commit))
        assertFalse(fixture.vault.rollbackIfRevisionMatches(commit))
        assertNull(fixture.files.bytes("accounts/test-provider/account-2.account"))
        assertArrayEquals(indexBefore, fixture.files.bytes(layout.indexPath))
        assertEquals("primary=first", fixture.vault.loadActive()?.value)
    }

    @Test
    fun rollingBackTheFirstAccountLeavesNothingBehind() {
        val fixture = Fixture()
        val commit = fixture.vault.saveWithRevision(null, "cancelled")
        assertTrue(fixture.vault.rollbackIfRevisionMatches(commit))
        assertTrue(fixture.files.occupiedPaths().isEmpty())
    }

    @Test
    fun rollingBackARotationRestoresThePreviousCredentialRecord() {
        val fixture = Fixture()
        fixture.vault.save("original")
        val before = checkNotNull(fixture.primary.bytes).copyOf()

        val commit = fixture.vault.saveWithRevision("primary", "rotated")
        assertTrue(fixture.vault.rollbackIfRevisionMatches(commit))

        assertArrayEquals(before, fixture.primary.bytes)
        assertEquals("primary=original", fixture.vault.loadActive()?.value)
    }

    @Test
    fun lateRollbackNeverOverwritesANewerWriteAndKeepsAnotherSwitch() {
        val fixture = Fixture()
        fixture.vault.save("first")
        val commit = fixture.vault.saveWithRevision(null, "pending")
        // The user switched back meanwhile: the index changed, so only our account is dropped.
        fixture.vault.activate("primary")

        assertTrue(fixture.vault.rollbackIfRevisionMatches(commit))
        assertEquals(AccountVaultIndex(listOf("primary"), "primary"), fixture.vault.index())

        val second = fixture.vault.saveWithRevision(null, "second")
        fixture.vault.accept(second)
        val stale = checkNotNull(fixture.vault.load(second.accountId)).revision
        fixture.vault.saveIfRevisionMatches(stale, "refreshed")
        assertFalse(fixture.vault.saveIfRevisionMatches(stale, "stale refresh"))
        assertEquals("${second.accountId}=refreshed", fixture.vault.load(second.accountId)?.value)
    }

    @Test
    fun refreshCompareAndSwapTargetsOnlyItsOwnAccount() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))
        val firstRevision = checkNotNull(fixture.vault.load("primary")).revision

        // A refresh that started on the first account lands in the first account even after a switch.
        fixture.vault.activate("account-1")
        assertTrue(fixture.vault.saveIfRevisionMatches(firstRevision, "first-refreshed"))
        assertEquals("primary=first-refreshed", fixture.vault.load("primary")?.value)
        assertEquals("account-1=second", fixture.vault.loadActive()?.value)

        // A refresh for a removed account cannot resurrect it.
        val secondRevision = checkNotNull(fixture.vault.load("account-1")).revision
        fixture.vault.delete("account-1")
        assertFalse(fixture.vault.saveIfRevisionMatches(secondRevision, "resurrected"))
        assertNull(fixture.files.bytes("accounts/test-provider/account-1.account"))
    }

    @Test
    fun pendingReplacementBlocksASecondSaveAndItsOwnRefresh() {
        val fixture = Fixture()
        fixture.vault.save("first")
        val revision = checkNotNull(fixture.vault.load("primary")).revision
        val pending = fixture.vault.saveWithRevision("primary", "rotating")

        assertThrows(IllegalStateException::class.java) { fixture.vault.saveWithRevision(null, "other") }
        assertFalse(fixture.vault.saveIfRevisionMatches(revision, "stale"))
        fixture.vault.accept(pending)
        assertEquals("primary=rotating", fixture.vault.loadActive()?.value)
    }

    @Test
    fun recordsAreBoundToTheirAccountAndUnreadableOnesAreListedNotDropped() {
        val fixture = Fixture()
        // Long enough that the XOR test cipher reaches the account-specific part of the AAD.
        fixture.vault.save("first-account-record-value-long")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))
        // Copying the primary envelope into the second slot fails authentication.
        fixture.files("accounts/test-provider/account-1.account").write(checkNotNull(fixture.primary.bytes))

        assertThrows(Exception::class.java) { fixture.vault.load("account-1") }
        val records = fixture.vault.records()
        assertEquals(listOf("primary", "account-1"), records.map { it.accountId })
        assertTrue(records[0] is AccountVaultRecord.Readable)
        assertEquals(AccountVaultRecord.Unreadable("account-1", secureStorageUnavailable = false), records[1])
        assertNotNull(fixture.files.bytes("accounts/test-provider/account-1.account"))
    }

    @Test
    fun indexIsAuthenticatedAndCannotBeSwappedBetweenProviders() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))
        val other = AccountVaultLayout("other-provider", "accounts/other.account", "verceltics.account-envelope.v1:other")
        val otherFiles = MemoryFiles()
        otherFiles(other.indexPath).write(checkNotNull(fixture.files.bytes(layout.indexPath)))
        val otherVault = ProviderAccountVault(other, otherFiles, cipher, ::encodeValue, { _, bytes -> decodeValue(bytes) })

        assertThrows(Exception::class.java) { otherVault.index() }
    }

    @Test
    fun accountIdsAndLayoutPathsAreValidated() {
        assertEquals("accounts/test-provider.index", layout.indexPath)
        assertEquals("verceltics.account-index.v1:test-provider", layout.indexAssociatedData)
        assertEquals(layout.primaryPath, layout.recordPath("primary"))
        assertEquals(layout.primaryAssociatedData, layout.recordAssociatedData("primary"))
        assertEquals("accounts/test-provider/0f6e2c7a-1b2c-4d5e-8f90-123456789abc.account", layout.recordPath("0f6e2c7a-1b2c-4d5e-8f90-123456789abc"))
        assertEquals(
            "verceltics.account-envelope.v2:test-provider:0f6e2c7a-1b2c-4d5e-8f90-123456789abc",
            layout.recordAssociatedData("0f6e2c7a-1b2c-4d5e-8f90-123456789abc"),
        )
        assertThrows(IllegalArgumentException::class.java) { layout.recordPath("../escape") }
        assertThrows(IllegalArgumentException::class.java) { layout.recordPath("UPPER") }
        assertThrows(IllegalArgumentException::class.java) { AccountVaultIndexCodec.decode(byteArrayOf(0, 0, 0, 9)) }
    }

    @Test
    fun accountCountIsBounded() {
        val fixture = Fixture()
        repeat(ProviderAccountVault.MAX_ACCOUNTS) { index ->
            fixture.vault.accept(fixture.vault.saveWithRevision(null, "account $index"))
        }
        assertThrows(IllegalStateException::class.java) { fixture.vault.saveWithRevision(null, "one too many") }
        assertEquals(ProviderAccountVault.MAX_ACCOUNTS, fixture.vault.index().accountIds.size)
    }

    @Test
    fun removeAllAlsoErasesRecordsTheIndexCanNoLongerName() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "third"))
        // An unreadable index must not leave the other accounts' encrypted records behind.
        fixture.files(layout.indexPath).write(byteArrayOf(9, 9, 9))

        val removed = fixture.vault.deleteAll()

        assertEquals(setOf("primary", "account-1", "account-2"), removed.toSet())
        assertTrue(fixture.files.occupiedPaths().isEmpty())
    }

    @Test
    fun removingTheActiveAccountResolvesItInTheDataLayer() {
        val fixture = Fixture()
        fixture.vault.save("first")
        fixture.vault.accept(fixture.vault.saveWithRevision(null, "second"))

        assertEquals("primary", fixture.vault.deleteActive())
        assertEquals(listOf("primary"), fixture.vault.index().accountIds)
        assertNull(fixture.vault.deleteActive())
        assertNull(Fixture().vault.deleteActive())
    }
}
