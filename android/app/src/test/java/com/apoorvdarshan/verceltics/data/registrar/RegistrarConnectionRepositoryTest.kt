package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrarConnectionRepositoryTest {
    @Test
    fun encryptedRecordRoundTripsCredentialsMetadataAndCachedPortfolio() {
        val fixture = RegistrarStorageFixture()
        val connection = connection(
            credentials(RegistrarProvider.NAMECHEAP, key = "sensitive-namecheap-key", username = "alice"),
            snapshot(RegistrarProvider.NAMECHEAP, listOf(domain("b.example"), domain("a.example", expiresAtMillis = null, autoRenew = null))),
        )

        fixture.repository.save(single(connection))

        val envelope = checkNotNull(fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes)
        assertFalse(String(envelope, StandardCharsets.UTF_8).contains("sensitive-namecheap-key"))
        val set = checkNotNull(fixture.repository.load(RegistrarProvider.NAMECHEAP))
        val loaded = set.active.connection
        assertEquals("account-1", set.activeAccountId)
        assertTrue(loaded.account.credentials.sameAs(connection.account.credentials))
        assertEquals(mapOf("username" to "alice", "clientIP" to "8.8.4.4"), loaded.account.credentials.metadata)
        assertEquals(connection.account.displayName, loaded.account.displayName)
        assertEquals(connection.account.createdAtMillis, loaded.account.createdAtMillis)
        assertEquals(connection.cachedSnapshot, loaded.cachedSnapshot)
        assertFalse(loaded.toString().contains("sensitive-namecheap-key"))
        assertFalse(loaded.account.toString().contains("sensitive-namecheap-key"))
        assertFalse(set.toString().contains("sensitive-namecheap-key"))
    }

    @Test
    fun secondaryCredentialRoundTripsForKeyAndSecretRegistrars() {
        val fixture = RegistrarStorageFixture()
        val credentials = credentials(RegistrarProvider.GO_DADDY, key = "gd-key", secret = "gd-secret")
        fixture.repository.save(single(connection(credentials, null)))

        val loaded = checkNotNull(fixture.repository.load(RegistrarProvider.GO_DADDY)).active.connection

        assertTrue(loaded.account.credentials.sameAs(credentials))
        assertNull(loaded.cachedSnapshot)
    }

    @Test
    fun severalAccountsRoundTripInOrderWithTheirOwnCachesAndTheActiveId() {
        val fixture = RegistrarStorageFixture()
        val first = RegistrarSavedAccount(
            "first",
            connection(credentials(RegistrarProvider.GANDI, key = "pat-1"), snapshot(RegistrarProvider.GANDI, listOf(domain("one.example")))),
        )
        val second = RegistrarSavedAccount(
            "second",
            connection(credentials(RegistrarProvider.GANDI, key = "pat-2"), snapshot(RegistrarProvider.GANDI, listOf(domain("two.example")))),
        )
        val third = RegistrarSavedAccount("third", connection(credentials(RegistrarProvider.GANDI, key = "pat-3"), null))

        fixture.repository.save(RegistrarAccountSet(RegistrarProvider.GANDI, listOf(first, second, third), "second"))

        val loaded = checkNotNull(fixture.repository.load(RegistrarProvider.GANDI))
        assertEquals(listOf("first", "second", "third"), loaded.accounts.map { it.id })
        assertEquals("second", loaded.activeAccountId)
        assertEquals(SecretValue.of("pat-2"), loaded.active.connection.account.credentials.primary)
        assertEquals(listOf("one.example"), loaded.account("first")?.connection?.cachedSnapshot?.domains?.map { it.name })
        assertEquals(listOf("two.example"), loaded.account("second")?.connection?.cachedSnapshot?.domains?.map { it.name })
        assertNull(loaded.account("third")?.connection?.cachedSnapshot)
    }

    // region Migration from the single-account (v1) record

    @Test
    fun legacySingleAccountRecordMigratesLosslesslyAsTheActiveAccount() {
        val fixture = RegistrarStorageFixture()
        val legacy = connection(
            credentials(RegistrarProvider.NAME_DOT_COM, key = "legacy-token", username = "ApoorvDarshan"),
            snapshot(RegistrarProvider.NAME_DOT_COM, listOf(domain("legacy.example"), domain("second.example"))),
        )
        val legacyEnvelope = writeLegacyRecord(fixture, legacy)

        val set = checkNotNull(fixture.repository.load(RegistrarProvider.NAME_DOT_COM))

        assertEquals(listOf(RegistrarAccountSet.MIGRATED_ACCOUNT_ID), set.accounts.map { it.id })
        assertEquals(RegistrarAccountSet.MIGRATED_ACCOUNT_ID, set.activeAccountId)
        val migrated = set.active.connection
        assertTrue(migrated.account.credentials.sameAs(legacy.account.credentials))
        assertEquals(legacy.account.displayName, migrated.account.displayName)
        assertEquals(legacy.account.createdAtMillis, migrated.account.createdAtMillis)
        assertEquals(legacy.account.updatedAtMillis, migrated.account.updatedAtMillis)
        assertEquals(legacy.cachedSnapshot, migrated.cachedSnapshot)
        // Reading never rewrites the legacy record.
        assertArrayEquals(legacyEnvelope, fixture.stores.getValue(RegistrarProvider.NAME_DOT_COM).bytes)
        assertEquals("accounts/registrar-namedotcom.account", RegistrarConnectionRepository.accountPath(RegistrarProvider.NAME_DOT_COM))
    }

    @Test
    fun legacyRecordRollbackRestoresTheExactLegacyEnvelope() {
        val fixture = RegistrarStorageFixture()
        val legacy = connection(credentials(RegistrarProvider.PORKBUN, key = "legacy"), null)
        val legacyEnvelope = writeLegacyRecord(fixture, legacy)
        val migrated = checkNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
        val added = RegistrarAccountSet(
            RegistrarProvider.PORKBUN,
            migrated.accounts + RegistrarSavedAccount("new-account", connection(credentials(RegistrarProvider.PORKBUN, key = "new"), null)),
            "new-account",
        )

        val commit = fixture.repository.saveWithRevision(added)
        assertEquals(2, checkNotNull(fixture.repository.load(RegistrarProvider.PORKBUN)).accounts.size)
        assertTrue(fixture.repository.rollbackIfRevisionMatches(commit))

        assertArrayEquals(legacyEnvelope, fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes)
        val restored = checkNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
        assertEquals(RegistrarAccountSet.MIGRATED_ACCOUNT_ID, restored.activeAccountId)
        assertEquals(SecretValue.of("legacy"), restored.active.connection.account.credentials.primary)
    }

    // endregion

    @Test
    fun eachRegistrarHasItsOwnNoBackupSlotAndAuthenticatedDataDomain() {
        val paths = RegistrarProvider.entries.map(RegistrarConnectionRepository::accountPath)
        val associatedData = RegistrarProvider.entries.map(RegistrarConnectionRepository::associatedData)

        assertEquals(paths.size, paths.toSet().size)
        assertEquals(associatedData.size, associatedData.toSet().size)
        assertEquals("accounts/registrar-namedotcom.account", RegistrarConnectionRepository.accountPath(RegistrarProvider.NAME_DOT_COM))
        assertEquals(
            "verceltics.account-envelope.v1:registrar-godaddy",
            RegistrarConnectionRepository.associatedData(RegistrarProvider.GO_DADDY),
        )
        assertEquals("verceltics.account-storage.registrar.v1", RegistrarConnectionRepository.KEY_ALIAS)
        assertFalse(paths.any { it.contains("netlify") || it.contains("vercel-personal-token") })
        assertFalse(RegistrarConnectionRepository.KEY_ALIAS == "verceltics.account-storage.v1")
    }

    @Test
    fun severalRegistrarsCanBeConnectedAndRemovedIndependently() {
        val fixture = RegistrarStorageFixture()
        val providers = listOf(RegistrarProvider.PORKBUN, RegistrarProvider.GANDI, RegistrarProvider.DYNADOT)
        providers.forEach { fixture.repository.save(single(connection(credentials(it), snapshot(it, listOf(domain("${it.id}.example")))))) }

        fixture.repository.delete(RegistrarProvider.GANDI)

        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
        assertNull(fixture.repository.load(RegistrarProvider.GANDI))
        assertEquals(
            listOf("dynadot.example"),
            checkNotNull(fixture.repository.load(RegistrarProvider.DYNADOT)).active.connection.cachedSnapshot?.domains?.map { it.name },
        )
        assertNull(fixture.repository.load(RegistrarProvider.NAMECHEAP))
    }

    @Test
    fun corruptionIsSurfacedWithoutDeletionAndDoesNotAffectOtherSlots() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN), null)))
        fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes = byteArrayOf(1, 2, 3)

        assertThrows(IllegalArgumentException::class.java) { fixture.repository.load(RegistrarProvider.NAMECHEAP) }

        assertEquals(3, fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes?.size)
        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
    }

    @Test
    fun anEnvelopeMovedToAnotherRegistrarSlotIsRejected() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN), null)))
        fixture.stores.getValue(RegistrarProvider.SPACESHIP).bytes = fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes

        assertThrows(Exception::class.java) { fixture.repository.load(RegistrarProvider.SPACESHIP) }
    }

    @Test
    fun rollbackRestoresTheExactPriorEnvelopeOnlyWhileItsRevisionIsCurrent() {
        val fixture = RegistrarStorageFixture()
        val store = fixture.stores.getValue(RegistrarProvider.GANDI)
        fixture.repository.save(single(connection(credentials(RegistrarProvider.GANDI, key = "original"), null)))
        val original = checkNotNull(store.bytes).copyOf()

        val commit = fixture.repository.saveWithRevision(single(connection(credentials(RegistrarProvider.GANDI, key = "replacement"), null)))
        assertTrue(fixture.repository.rollbackIfRevisionMatches(commit))
        assertArrayEquals(original, store.bytes)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(commit))

        val accepted = fixture.repository.saveWithRevision(single(connection(credentials(RegistrarProvider.GANDI, key = "accepted"), null)))
        fixture.repository.accept(accepted)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(accepted))
        assertEquals(
            SecretValue.of("accepted"),
            checkNotNull(fixture.repository.load(RegistrarProvider.GANDI)).active.connection.account.credentials.primary,
        )
    }

    @Test
    fun firstConnectionRollbackEmptiesTheSlot() {
        val fixture = RegistrarStorageFixture()
        val commit = fixture.repository.saveWithRevision(single(connection(credentials(RegistrarProvider.DYNADOT), null)))

        assertTrue(fixture.repository.rollbackIfRevisionMatches(commit))

        assertNull(fixture.stores.getValue(RegistrarProvider.DYNADOT).bytes)
    }

    @Test
    fun compareAndSwapRejectsStaleRevisionsAndPendingCommits() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN, key = "one"), null)))
        val versioned = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN, key = "two"), null)))

        assertFalse(
            fixture.repository.saveIfRevisionMatches(
                versioned.revision,
                single(connection(credentials(RegistrarProvider.PORKBUN, key = "stale"), null)),
            ),
        )
        val current = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        val pending = fixture.repository.saveWithRevision(single(connection(credentials(RegistrarProvider.PORKBUN, key = "three"), null)))
        assertFalse(
            fixture.repository.saveIfRevisionMatches(
                current.revision,
                single(connection(credentials(RegistrarProvider.PORKBUN, key = "blocked"), null)),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN, key = "blocked"), null)))
        }
        fixture.repository.accept(pending)
        val latest = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        assertTrue(
            fixture.repository.saveIfRevisionMatches(
                latest.revision,
                single(connection(credentials(RegistrarProvider.PORKBUN, key = "three"), snapshot(RegistrarProvider.PORKBUN, emptyList()))),
            ),
        )
    }

    @Test
    fun disconnectReleasesAPendingCommitAndErasesOnlyThatSlot() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(single(connection(credentials(RegistrarProvider.GANDI), null)))
        val pending = fixture.repository.saveWithRevision(single(connection(credentials(RegistrarProvider.PORKBUN), null)))

        fixture.repository.delete(RegistrarProvider.PORKBUN)

        assertNull(fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(pending))
        assertNotNull(fixture.repository.load(RegistrarProvider.GANDI))
        fixture.repository.save(single(connection(credentials(RegistrarProvider.PORKBUN), null)))
        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
    }

    @Test
    fun payloadCodecRejectsTrailingDataAndUnknownProviders() {
        val payload = RegistrarConnectionPayloadCodec.encode(single(connection(credentials(RegistrarProvider.GANDI), null)))

        assertThrows(IllegalArgumentException::class.java) {
            RegistrarConnectionPayloadCodec.decode(payload + byteArrayOf(0))
        }
        val tampered = payload.copyOf()
        // Provider id length is at offset 4; its first byte starts at offset 8 ("gandi" -> "xandi").
        tampered[8] = 'x'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { RegistrarConnectionPayloadCodec.decode(tampered) }

        val legacy = RegistrarConnectionPayloadCodec.encodeLegacy(connection(credentials(RegistrarProvider.GANDI), null))
        assertThrows(IllegalArgumentException::class.java) {
            RegistrarConnectionPayloadCodec.decode(legacy + byteArrayOf(0))
        }
        val unknownVersion = payload.copyOf().also { it[3] = 9 }
        assertThrows(IllegalArgumentException::class.java) { RegistrarConnectionPayloadCodec.decode(unknownVersion) }
    }

    @Test
    fun accountSetsRejectInvalidStructure() {
        val one = RegistrarSavedAccount("one", connection(credentials(RegistrarProvider.GANDI, key = "a"), null))
        val sameId = RegistrarSavedAccount("one", connection(credentials(RegistrarProvider.GANDI, key = "b"), null))
        val otherRegistrar = RegistrarSavedAccount("two", connection(credentials(RegistrarProvider.PORKBUN), null))

        assertThrows(IllegalArgumentException::class.java) { RegistrarAccountSet(RegistrarProvider.GANDI, emptyList(), "one") }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAccountSet(RegistrarProvider.GANDI, listOf(one, sameId), "one") }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAccountSet(RegistrarProvider.GANDI, listOf(one), "missing") }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAccountSet(RegistrarProvider.GANDI, listOf(one, otherRegistrar), "one") }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrarSavedAccount("not valid!", connection(credentials(RegistrarProvider.GANDI), null))
        }
        val tooMany = (0..RegistrarAccountSet.MAX_ACCOUNTS).map {
            RegistrarSavedAccount("a$it", connection(credentials(RegistrarProvider.GANDI, key = "k$it"), null))
        }
        assertThrows(IllegalArgumentException::class.java) { RegistrarAccountSet(RegistrarProvider.GANDI, tooMany, "a0") }
    }

    private fun writeLegacyRecord(fixture: RegistrarStorageFixture, legacy: RegistrarStoredConnection): ByteArray {
        val provider = legacy.account.provider
        val plaintext = RegistrarConnectionPayloadCodec.encodeLegacy(legacy)
        val envelope = AccountEnvelopeCodec.encode(
            TestAccountCipher().encrypt(
                plaintext,
                RegistrarConnectionRepository.associatedData(provider).toByteArray(StandardCharsets.UTF_8),
            ),
        )
        fixture.stores.getValue(provider).bytes = envelope
        return envelope.copyOf()
    }

    private fun single(connection: RegistrarStoredConnection) = RegistrarAccountSet(
        provider = connection.account.provider,
        accounts = listOf(RegistrarSavedAccount("account-1", connection)),
        activeAccountId = "account-1",
    )

    private fun connection(credentials: RegistrarCredentials, snapshot: RegistrarSnapshot?) = RegistrarStoredConnection(
        account = RegistrarAccount(
            displayName = RegistrarApi.accountName(credentials),
            credentials = credentials,
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
        ),
        cachedSnapshot = snapshot,
    )

    private fun snapshot(provider: RegistrarProvider, domains: List<RegistrarDomain>) = RegistrarSnapshot(
        provider = provider,
        accountName = "Account",
        domains = domains,
        fetchedAtMillis = 3_000L,
        domainsComplete = true,
        warnings = emptyList(),
    )
}
