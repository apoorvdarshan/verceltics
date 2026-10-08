package com.apoorvdarshan.verceltics.data.registrar

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

        fixture.repository.save(connection)

        val envelope = checkNotNull(fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes)
        assertFalse(String(envelope, StandardCharsets.UTF_8).contains("sensitive-namecheap-key"))
        val loaded = checkNotNull(fixture.repository.load(RegistrarProvider.NAMECHEAP))
        assertTrue(loaded.account.credentials.sameAs(connection.account.credentials))
        assertEquals(mapOf("username" to "alice", "clientIP" to "8.8.4.4"), loaded.account.credentials.metadata)
        assertEquals(connection.account.displayName, loaded.account.displayName)
        assertEquals(connection.account.createdAtMillis, loaded.account.createdAtMillis)
        assertEquals(connection.cachedSnapshot, loaded.cachedSnapshot)
        assertFalse(loaded.toString().contains("sensitive-namecheap-key"))
        assertFalse(loaded.account.toString().contains("sensitive-namecheap-key"))
    }

    @Test
    fun secondaryCredentialRoundTripsForKeyAndSecretRegistrars() {
        val fixture = RegistrarStorageFixture()
        val credentials = credentials(RegistrarProvider.GO_DADDY, key = "gd-key", secret = "gd-secret")
        fixture.repository.save(connection(credentials, null))

        val loaded = checkNotNull(fixture.repository.load(RegistrarProvider.GO_DADDY))

        assertTrue(loaded.account.credentials.sameAs(credentials))
        assertNull(loaded.cachedSnapshot)
    }

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
        providers.forEach { fixture.repository.save(connection(credentials(it), snapshot(it, listOf(domain("${it.id}.example"))))) }

        fixture.repository.delete(RegistrarProvider.GANDI)

        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
        assertNull(fixture.repository.load(RegistrarProvider.GANDI))
        assertEquals(
            listOf("dynadot.example"),
            checkNotNull(fixture.repository.load(RegistrarProvider.DYNADOT)).cachedSnapshot?.domains?.map { it.name },
        )
        assertNull(fixture.repository.load(RegistrarProvider.NAMECHEAP))
    }

    @Test
    fun corruptionIsSurfacedWithoutDeletionAndDoesNotAffectOtherSlots() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN), null))
        fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes = byteArrayOf(1, 2, 3)

        assertThrows(IllegalArgumentException::class.java) { fixture.repository.load(RegistrarProvider.NAMECHEAP) }

        assertEquals(3, fixture.stores.getValue(RegistrarProvider.NAMECHEAP).bytes?.size)
        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
    }

    @Test
    fun anEnvelopeMovedToAnotherRegistrarSlotIsRejected() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN), null))
        fixture.stores.getValue(RegistrarProvider.SPACESHIP).bytes = fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes

        assertThrows(Exception::class.java) { fixture.repository.load(RegistrarProvider.SPACESHIP) }
    }

    @Test
    fun rollbackRestoresTheExactPriorEnvelopeOnlyWhileItsRevisionIsCurrent() {
        val fixture = RegistrarStorageFixture()
        val store = fixture.stores.getValue(RegistrarProvider.GANDI)
        fixture.repository.save(connection(credentials(RegistrarProvider.GANDI, key = "original"), null))
        val original = checkNotNull(store.bytes).copyOf()

        val commit = fixture.repository.saveWithRevision(connection(credentials(RegistrarProvider.GANDI, key = "replacement"), null))
        assertTrue(fixture.repository.rollbackIfRevisionMatches(commit))
        assertArrayEquals(original, store.bytes)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(commit))

        val accepted = fixture.repository.saveWithRevision(connection(credentials(RegistrarProvider.GANDI, key = "accepted"), null))
        fixture.repository.accept(accepted)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(accepted))
        assertEquals(
            com.apoorvdarshan.verceltics.data.account.SecretValue.of("accepted"),
            checkNotNull(fixture.repository.load(RegistrarProvider.GANDI)).account.credentials.primary,
        )
    }

    @Test
    fun firstConnectionRollbackEmptiesTheSlot() {
        val fixture = RegistrarStorageFixture()
        val commit = fixture.repository.saveWithRevision(connection(credentials(RegistrarProvider.DYNADOT), null))

        assertTrue(fixture.repository.rollbackIfRevisionMatches(commit))

        assertNull(fixture.stores.getValue(RegistrarProvider.DYNADOT).bytes)
    }

    @Test
    fun compareAndSwapRejectsStaleRevisionsAndPendingCommits() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN, key = "one"), null))
        val versioned = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN, key = "two"), null))

        assertFalse(
            fixture.repository.saveIfRevisionMatches(
                versioned.revision,
                connection(credentials(RegistrarProvider.PORKBUN, key = "stale"), null),
            ),
        )
        val current = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        val pending = fixture.repository.saveWithRevision(connection(credentials(RegistrarProvider.PORKBUN, key = "three"), null))
        assertFalse(
            fixture.repository.saveIfRevisionMatches(
                current.revision,
                connection(credentials(RegistrarProvider.PORKBUN, key = "blocked"), null),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN, key = "blocked"), null))
        }
        fixture.repository.accept(pending)
        val latest = checkNotNull(fixture.repository.loadWithRevision(RegistrarProvider.PORKBUN))
        assertTrue(
            fixture.repository.saveIfRevisionMatches(
                latest.revision,
                connection(credentials(RegistrarProvider.PORKBUN, key = "three"), snapshot(RegistrarProvider.PORKBUN, emptyList())),
            ),
        )
    }

    @Test
    fun disconnectReleasesAPendingCommitAndErasesOnlyThatSlot() {
        val fixture = RegistrarStorageFixture()
        fixture.repository.save(connection(credentials(RegistrarProvider.GANDI), null))
        val pending = fixture.repository.saveWithRevision(connection(credentials(RegistrarProvider.PORKBUN), null))

        fixture.repository.delete(RegistrarProvider.PORKBUN)

        assertNull(fixture.stores.getValue(RegistrarProvider.PORKBUN).bytes)
        assertFalse(fixture.repository.rollbackIfRevisionMatches(pending))
        assertNotNull(fixture.repository.load(RegistrarProvider.GANDI))
        fixture.repository.save(connection(credentials(RegistrarProvider.PORKBUN), null))
        assertNotNull(fixture.repository.load(RegistrarProvider.PORKBUN))
    }

    @Test
    fun payloadCodecRejectsTrailingDataAndUnknownProviders() {
        val payload = RegistrarConnectionPayloadCodec.encode(connection(credentials(RegistrarProvider.GANDI), null))

        assertThrows(IllegalArgumentException::class.java) {
            RegistrarConnectionPayloadCodec.decode(payload + byteArrayOf(0))
        }
        val tampered = payload.copyOf()
        // Provider id length is at offset 4; its first byte starts at offset 8 ("gandi" -> "xandi").
        tampered[8] = 'x'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { RegistrarConnectionPayloadCodec.decode(tampered) }
    }

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
