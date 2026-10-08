package com.apoorvdarshan.verceltics.data.sites

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAccountVaultTest {
    private val files = HashMap<String, MemoryBytesStore>()
    private val cipher = AesGcmTestCipher()

    private fun vault(domain: String = "test-service") = SiteAccountVault(
        domain = domain,
        indexStore = files.getOrPut("$domain/index") { MemoryBytesStore() },
        recordStoreFor = { id -> files.getOrPut("$domain/$id") { MemoryBytesStore() } },
        cipher = cipher,
        codec = TextCodec,
    )

    @Test
    fun indexRulesMatchIosAccountManagement() {
        val a = SiteAccountEntry("a", "Alpha")
        val b = SiteAccountEntry("b", "Beta", "detail")
        val c = SiteAccountEntry("c", "Gamma")
        val index = SiteAccountIndex.EMPTY.upserting(a).upserting(b, activate = false).upserting(c)

        assertEquals(listOf("a", "b", "c"), index.ids)
        assertEquals("c", index.activeId)
        // Updating keeps the account's position.
        assertEquals(listOf("a", "b", "c"), index.upserting(SiteAccountEntry("b", "Beta 2")).ids)
        // Removing the active account activates the first remaining one; others keep the active.
        assertEquals("a", index.removing("c").activeId)
        assertEquals("c", index.removing("b").activeId)
        assertEquals(SiteAccountIndex.EMPTY, SiteAccountIndex(listOf(a), "a").removing("a"))

        assertThrows(IllegalArgumentException::class.java) { SiteAccountIndex(listOf(a, a), "a") }
        assertThrows(IllegalArgumentException::class.java) { SiteAccountIndex(listOf(a), "missing") }
        assertThrows(IllegalArgumentException::class.java) { SiteAccountIndex(listOf(a), null) }
        assertThrows(IllegalArgumentException::class.java) { SiteAccountEntry("Not Valid", "x") }
        assertTrue(SiteAccountIds.isValid(SiteAccountIds.newId()))
        assertEquals(SiteAccountIds.migrated("x"), SiteAccountIds.migrated("x"))
        assertFalse(SiteAccountIds.migrated("x") == SiteAccountIds.migrated("y"))
    }

    @Test
    fun indexAndRecordsRoundTripEncryptedAndStayBoundToTheirDomain() {
        val vault = vault()
        vault.write("a", "secret record")
        vault.writeIndex(SiteAccountIndex(listOf(SiteAccountEntry("a", "Visible name")), "a"))

        assertFalse(String(files.getValue("test-service/a").bytes!!, StandardCharsets.ISO_8859_1).contains("secret record"))
        assertFalse(String(files.getValue("test-service/index").bytes!!, StandardCharsets.ISO_8859_1).contains("Visible name"))
        assertEquals("secret record", vault.read("a"))
        assertEquals("a", vault.readIndex()?.activeId)

        val other = vault("other-service")
        files.getOrPut("other-service/index") { MemoryBytesStore() }.bytes = files.getValue("test-service/index").bytes
        files.getOrPut("other-service/a") { MemoryBytesStore() }.bytes = files.getValue("test-service/a").bytes
        assertThrows(Exception::class.java) { other.readIndex() }
        assertThrows(Exception::class.java) { other.read("a") }
    }

    @Test
    fun rollbackOfANewAccountAfterTheIndexMovedOnOnlyForgetsThatAccount() {
        val vault = vault()
        vault.write("a", "A")
        vault.writeIndex(SiteAccountIndex(listOf(SiteAccountEntry("a", "A")), "a"))
        val commit = vault.commit("x", "X", vault.readIndex()!!.upserting(SiteAccountEntry("x", "X")))
        // Someone switched back to A before the cancelled connect was compensated.
        vault.writeIndex(vault.readIndex()!!.activating("a"))

        assertTrue(vault.rollbackIfCurrent(commit))

        assertNull(vault.read("x"))
        assertEquals(SiteAccountIndex(listOf(SiteAccountEntry("a", "A")), "a"), vault.readIndex())
        assertFalse(vault.rollbackIfCurrent(commit))
    }

    @Test
    fun rollbackNeverUndoesANewerWriteOfTheSameRecord() {
        val vault = vault()
        val commit = vault.commit("a", "first", SiteAccountIndex(listOf(SiteAccountEntry("a", "A")), "a"))
        val current = vault.readVersioned("a")!!
        assertTrue(vault.writeIfRevisionMatches("a", current.revision, "refreshed"))

        assertFalse(vault.rollbackIfCurrent(commit))
        assertEquals("refreshed", vault.read("a"))
        assertEquals("a", vault.readIndex()?.activeId)
        assertFalse(vault.writeIfRevisionMatches("a", current.revision, "stale"))
    }

    @Test
    fun acceptedCommitsCannotBeRolledBack() {
        val vault = vault()
        val commit = vault.commit("a", "first", SiteAccountIndex(listOf(SiteAccountEntry("a", "A")), "a"))
        vault.accept(commit)
        assertFalse(vault.rollbackIfCurrent(commit))
        assertEquals("first", vault.read("a"))
    }

    @Test
    fun migrationWithoutALegacyRecordWritesNothing() {
        val vault = vault()
        val legacy = object : SiteLegacyAccountSource<String> {
            var deletes = 0

            override fun load(): String? = null

            override fun delete() {
                deletes += 1
            }
        }

        assertEquals(SiteAccountIndex.EMPTY, vault.migratedIndex(legacy, entry = { record, id -> SiteAccountEntry(id, record) }))
        assertNull(files["test-service/index"]?.bytes)
        assertEquals(0, legacy.deletes)
    }

    @Test
    fun migrationCopiesSecretsBeforeTheIndexAndDeletesTheLegacyCopyLast() {
        val vault = vault()
        val steps = ArrayList<String>()
        val legacy = object : SiteLegacyAccountSource<String> {
            override fun load(): String = "Legacy".also { steps += "load" }

            override fun migrateSecrets(record: String, accountId: String) {
                assertNull(vault.readIndex())
                assertEquals("Legacy", vault.read(accountId))
                steps += "secrets"
            }

            override fun delete() {
                assertEquals(accountId(), vault.readIndex()?.activeId)
                steps += "delete"
            }

            fun accountId() = SiteAccountIds.migrated("test-service")
        }

        val index = vault.migratedIndex(legacy, entry = { record, id -> SiteAccountEntry(id, record) })

        assertEquals(listOf("load", "secrets", "delete"), steps)
        assertEquals(listOf(SiteAccountIds.migrated("test-service")), index.ids)
        // Later reads only finish cleanup; they never re-import.
        vault.migratedIndex(legacy, entry = { record, id -> SiteAccountEntry(id, record) })
        assertEquals(listOf("load", "secrets", "delete", "delete"), steps)
    }

    private object TextCodec : SiteAccountRecordCodec<String> {
        override fun encode(record: String): ByteArray = record.toByteArray(StandardCharsets.UTF_8)

        override fun decode(bytes: ByteArray, accountId: String): String = String(bytes, StandardCharsets.UTF_8)
    }
}
