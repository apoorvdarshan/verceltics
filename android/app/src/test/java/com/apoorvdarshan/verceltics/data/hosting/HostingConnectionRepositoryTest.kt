package com.apoorvdarshan.verceltics.data.hosting

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

class HostingConnectionRepositoryTest {
    @Test
    fun everyProviderRoundTripsCredentialsAndOfflineInventoryEncrypted() {
        val fixture = HostingStoreFixture()
        val credentials = listOf(
            HostingCredentials.Railway(SecretValue.of("secret-railway"), RailwayTokenType.PROJECT),
            HostingCredentials.Render(SecretValue.of("secret-render")),
            HostingCredentials.DigitalOcean(SecretValue.of("secret-do")),
            HostingCredentials.Heroku(SecretValue.of("secret-heroku")),
            HostingCredentials.Fly(SecretValue.of("secret-fly"), "studio-org"),
            HostingCredentials.Firebase("studio-prod"),
            HostingCredentials.AwsAmplify(
                "AKIAIOSFODNN7EXAMPLE",
                SecretValue.of("secret-aws"),
                "eu-west-1",
                SecretValue.of("secret-session"),
            ),
        )
        credentials.forEach { credential ->
            val connection = connection(credential, resources = listOf(resource("r1", metadata = mapOf("appName" to "r1"))))
            fixture.repository.save(connection)

            val stored = checkNotNull(fixture.stores.getValue(credential.provider).bytes)
            val storedText = String(stored, StandardCharsets.ISO_8859_1)
            assertFalse(storedText.contains("secret-"))
            assertFalse(storedText.contains("studio-org"))

            val loaded = checkNotNull(fixture.repository.load(credential.provider))
            assertEquals(credential.provider, loaded.account.provider)
            assertEquals(connection.account.profile, loaded.account.profile)
            assertEquals(connection.cachedSnapshot, loaded.cachedSnapshot)
            assertCredentialsEqual(credential, loaded.account.credentials)
            assertFalse(loaded.toString().contains("secret-"))
        }
        assertEquals(HostingProvider.entries.size, fixture.stores.values.count { it.bytes != null })
    }

    @Test
    fun awsSessionTokenIsOptional() {
        val fixture = HostingStoreFixture()
        val credential = HostingCredentials.AwsAmplify("AKIAIOSFODNN7EXAMPLE", SecretValue.of("s"), "us-east-1", null)
        fixture.repository.save(connection(credential, resources = emptyList()))
        val loaded = fixture.repository.load(HostingProvider.AWS_AMPLIFY)!!.account.credentials as HostingCredentials.AwsAmplify
        assertNull(loaded.sessionToken)
        assertEquals("us-east-1", loaded.region)
    }

    @Test
    fun slotsUseDedicatedNoBackupPathsProviderBoundAadAndHostingKeyAlias() {
        val paths = HostingProvider.entries.map(HostingConnectionRepository::accountPath)
        val aad = HostingProvider.entries.map(HostingConnectionRepository::associatedData)
        assertEquals(paths.size, paths.toSet().size)
        assertEquals(aad.size, aad.toSet().size)
        assertEquals("accounts/hosting-awsAmplify.account", HostingConnectionRepository.accountPath(HostingProvider.AWS_AMPLIFY))
        assertEquals("verceltics.account-envelope.v1:hosting-railway", HostingConnectionRepository.associatedData(HostingProvider.RAILWAY))
        assertEquals("verceltics.account-storage.hosting.v1", HostingConnectionRepository.KEY_ALIAS)
        assertFalse(paths.any { it.contains("netlify") || it.contains("vercel-personal-token") })
    }

    @Test
    fun envelopeCopiedIntoAnotherProviderSlotIsRejectedNotMisread() {
        val fixture = HostingStoreFixture()
        fixture.repository.save(connection(HostingCredentials.Render(SecretValue.of("render-key")), emptyList()))
        fixture.stores.getValue(HostingProvider.HEROKU).bytes = fixture.stores.getValue(HostingProvider.RENDER).bytes

        assertThrows(Exception::class.java) { fixture.repository.load(HostingProvider.HEROKU) }
        val restored = HostingConnectionStore(fixture.repository).restore(HostingProvider.HEROKU)
        assertEquals(HostingRestoreResult.Unavailable(HostingRestoreProblem.SAVED_RECORD_UNREADABLE), restored)
        assertNotNull(fixture.stores.getValue(HostingProvider.HEROKU).bytes)
    }

    @Test
    fun restoreAllIsOfflineIndependentPerSlotAndReportsStaleness() {
        val fixture = HostingStoreFixture()
        var now = 10_000L
        val store = HostingConnectionStore(fixture.repository) { now }
        fixture.repository.save(connection(HostingCredentials.Render(SecretValue.of("k")), listOf(resource("srv")), fetchedAt = 9_000L))
        fixture.repository.save(connection(HostingCredentials.Firebase("studio-prod"), emptyList(), withSnapshot = false))
        fixture.stores.getValue(HostingProvider.HEROKU).bytes = byteArrayOf(1, 2, 3)

        val restored = store.restoreAll()
        val render = restored.getValue(HostingProvider.RENDER) as HostingRestoreResult.Restored
        assertFalse(render.cacheIsStale)
        assertEquals("srv", render.cachedSnapshot!!.resources.single().id)
        val firebase = restored.getValue(HostingProvider.FIREBASE) as HostingRestoreResult.Restored
        assertNull(firebase.cachedSnapshot)
        assertTrue(firebase.cacheIsStale)
        assertEquals("studio-prod", firebase.linkContext.firebaseProjectId)
        assertEquals(
            HostingRestoreResult.Unavailable(HostingRestoreProblem.SAVED_RECORD_UNREADABLE),
            restored.getValue(HostingProvider.HEROKU),
        )
        assertEquals(HostingRestoreResult.NotConnected, restored.getValue(HostingProvider.FLY))
        assertEquals(3, fixture.stores.getValue(HostingProvider.HEROKU).bytes?.size)

        now = 9_000L + HostingConnectionStore.CACHE_LIFETIME_MILLIS
        assertTrue((store.restore(HostingProvider.RENDER) as HostingRestoreResult.Restored).cacheIsStale)
    }

    @Test
    fun cancelledReplacementRestoresTheExactPriorEnvelope() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 50L }
        val original = HostingCredentials.Heroku(SecretValue.of("original"))
        store.acceptValidatedConnection(store.saveValidatedConnection(original, snapshot(HostingProvider.HEROKU, "acct")))
        val originalEnvelope = fixture.stores.getValue(HostingProvider.HEROKU).bytes!!.copyOf()

        val commit = store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("replacement")), snapshot(HostingProvider.HEROKU, "acct"))
        assertTrue(store.rollbackValidatedConnection(commit))
        assertArrayEquals(originalEnvelope, fixture.stores.getValue(HostingProvider.HEROKU).bytes)
        assertFalse(store.rollbackValidatedConnection(commit))

        val firstCommit = store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("first")), snapshot(HostingProvider.RENDER, "r"))
        assertTrue(store.rollbackValidatedConnection(firstCommit))
        assertNull(fixture.stores.getValue(HostingProvider.RENDER).bytes)
    }

    @Test
    fun lateRollbackNeverClobbersANewerRecordAndAcceptReleasesTheSlot() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 50L }
        val commit = store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("one"), "personal"), snapshot(HostingProvider.FLY, "personal"))
        store.acceptValidatedConnection(commit)
        assertFalse(store.rollbackValidatedConnection(commit))
        assertNotNull(fixture.repository.load(HostingProvider.FLY))

        val pending = store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("two"), "personal"), snapshot(HostingProvider.FLY, "personal"))
        assertThrows(IllegalStateException::class.java) {
            store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("three"), "personal"), snapshot(HostingProvider.FLY, "personal"))
        }
        // A pending replacement in one slot never blocks another provider.
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("r")), snapshot(HostingProvider.RENDER, "r")))
        store.disconnect(HostingProvider.FLY)
        assertFalse(store.rollbackValidatedConnection(pending))
        assertNull(fixture.repository.load(HostingProvider.FLY))
        assertNotNull(fixture.repository.load(HostingProvider.RENDER))
    }

    @Test
    fun reconnectingTheSameAccountPreservesCreationTime() {
        val fixture = HostingStoreFixture()
        var now = 100L
        val store = HostingConnectionStore(fixture.repository) { now }
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("a")), snapshot(HostingProvider.RENDER, "acct")))
        now = 500L
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("b")), snapshot(HostingProvider.RENDER, "acct")))
        val account = fixture.repository.load(HostingProvider.RENDER)!!.account
        assertEquals(100L, account.createdAtMillis)
        assertEquals(500L, account.updatedAtMillis)
        assertEquals(SecretValue.of("b"), (account.credentials as HostingCredentials.Render).apiKey)

        now = 900L
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("c")), snapshot(HostingProvider.RENDER, "other")))
        assertEquals(900L, fixture.repository.load(HostingProvider.RENDER)!!.account.createdAtMillis)
    }

    @Test
    fun refreshPersistsOnlyWhileTheSlotIsUnchangedAndKeepsCredentials() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 1_000L }
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.DigitalOcean(SecretValue.of("do")), snapshot(HostingProvider.DIGITAL_OCEAN, "acct")))

        val refreshed = snapshot(HostingProvider.DIGITAL_OCEAN, "acct", listOf(resource("new-app")), fetchedAt = 2_000L)
        assertTrue(store.persistRefreshResult(refreshed))
        val loaded = fixture.repository.load(HostingProvider.DIGITAL_OCEAN)!!
        assertEquals(listOf("new-app"), loaded.cachedSnapshot!!.resources.map { it.id })
        assertEquals(SecretValue.of("do"), (loaded.account.credentials as HostingCredentials.DigitalOcean).token)

        store.disconnect(HostingProvider.DIGITAL_OCEAN)
        assertFalse(store.persistRefreshResult(refreshed))
        assertNull(fixture.repository.load(HostingProvider.DIGITAL_OCEAN))
    }

    @Test
    fun offlineCacheIsBoundedAndDisclosesTruncation() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 1L }
        val many = (1..150).map { resource("app-$it") }
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("k")), snapshot(HostingProvider.HEROKU, "acct", many)))
        val cached = fixture.repository.load(HostingProvider.HEROKU)!!.cachedSnapshot!!
        assertEquals(HostingConnectionStore.MAX_CACHED_RESOURCES, cached.resources.size)
        assertTrue(HostingConnectionStore.CACHE_WARNING in cached.warnings)

        val huge = (1..100).map { index ->
            resource("big-$index").copy(
                subtitle = "s".repeat(8_000),
                metadata = (1..8).associate { "key-$it" to "✓".repeat(500) },
            )
        }
        store.acceptValidatedConnection(store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("k")), snapshot(HostingProvider.RENDER, "acct", huge)))
        val bounded = fixture.repository.load(HostingProvider.RENDER)!!.cachedSnapshot!!
        assertTrue(bounded.resources.size in 1..100)
        assertTrue(bounded.resources.all { (it.subtitle?.length ?: 0) <= 512 })
        assertTrue(HostingConnectionStore.CACHE_WARNING in bounded.warnings)
    }

    @Test
    fun corruptionIsSurfacedWithoutDeletionAndDeleteIsExplicit() {
        val fixture = HostingStoreFixture()
        assertNull(fixture.repository.load(HostingProvider.RAILWAY))
        fixture.stores.getValue(HostingProvider.RAILWAY).bytes = byteArrayOf(9, 9, 9)
        assertThrows(IllegalArgumentException::class.java) { fixture.repository.load(HostingProvider.RAILWAY) }
        assertEquals(3, fixture.stores.getValue(HostingProvider.RAILWAY).bytes?.size)
        fixture.repository.delete(HostingProvider.RAILWAY)
        assertNull(fixture.stores.getValue(HostingProvider.RAILWAY).bytes)
    }

    private fun connection(
        credentials: HostingCredentials,
        resources: List<HostingResource>,
        fetchedAt: Long = 1_000L,
        withSnapshot: Boolean = true,
    ) = HostingStoredConnection(
        account = HostingAccount(profile("acct-${credentials.provider.id}"), credentials, 10L, 20L),
        cachedSnapshot = if (withSnapshot) {
            HostingSnapshot(credentials.provider, profile("acct-${credentials.provider.id}"), resources, fetchedAt, listOf("warning"))
        } else {
            null
        },
    )

    private fun snapshot(
        provider: HostingProvider,
        profileId: String,
        resources: List<HostingResource> = listOf(resource("one")),
        fetchedAt: Long = 1_000L,
    ) = HostingSnapshot(provider, profile(profileId), resources, fetchedAt)

    private fun assertCredentialsEqual(expected: HostingCredentials, actual: HostingCredentials) {
        assertEquals(expected.provider, actual.provider)
        when (expected) {
            is HostingCredentials.Railway -> {
                actual as HostingCredentials.Railway
                assertEquals(expected.token, actual.token)
                assertEquals(expected.tokenType, actual.tokenType)
            }
            is HostingCredentials.Render -> assertEquals(expected.apiKey, (actual as HostingCredentials.Render).apiKey)
            is HostingCredentials.DigitalOcean -> assertEquals(expected.token, (actual as HostingCredentials.DigitalOcean).token)
            is HostingCredentials.Heroku -> assertEquals(expected.token, (actual as HostingCredentials.Heroku).token)
            is HostingCredentials.Fly -> {
                actual as HostingCredentials.Fly
                assertEquals(expected.token, actual.token)
                assertEquals(expected.organization, actual.organization)
            }
            is HostingCredentials.Firebase -> assertEquals(expected.projectId, (actual as HostingCredentials.Firebase).projectId)
            is HostingCredentials.AwsAmplify -> {
                actual as HostingCredentials.AwsAmplify
                assertEquals(expected.accessKeyId, actual.accessKeyId)
                assertEquals(expected.secretAccessKey, actual.secretAccessKey)
                assertEquals(expected.region, actual.region)
                assertEquals(expected.sessionToken, actual.sessionToken)
            }
        }
    }
}
