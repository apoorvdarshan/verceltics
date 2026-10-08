package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingMultiAccountStoreTest {
    private val legacyCredentials = listOf(
        HostingCredentials.Railway(SecretValue.of("legacy-railway"), RailwayTokenType.ACCOUNT),
        HostingCredentials.Render(SecretValue.of("legacy-render")),
        HostingCredentials.DigitalOcean(SecretValue.of("legacy-do")),
        HostingCredentials.Heroku(SecretValue.of("legacy-heroku")),
        HostingCredentials.Fly(SecretValue.of("legacy-fly"), "studio-org"),
        HostingCredentials.Firebase("studio-prod"),
        HostingCredentials.AwsAmplify("AKIAIOSFODNN7EXAMPLE", SecretValue.of("legacy-aws"), "eu-west-1", null),
    )

    /** Exactly the bytes the single-account repository wrote before multi-account support. */
    private fun HostingStoreFixture.writeLegacyRecord(connection: HostingStoredConnection) {
        val provider = connection.account.provider
        val plaintext = HostingConnectionPayloadCodec.encode(connection)
        val sealed = TestAccountCipher().encrypt(
            plaintext,
            HostingConnectionRepository.associatedData(provider).toByteArray(StandardCharsets.UTF_8),
        )
        stores.getValue(provider).bytes = AccountEnvelopeCodec.encode(sealed)
    }

    private fun legacyConnection(credentials: HostingCredentials) = HostingStoredConnection(
        account = HostingAccount(profile("legacy-${credentials.provider.id}", "Legacy"), credentials, 10L, 20L),
        cachedSnapshot = snapshot(credentials.provider, "legacy-${credentials.provider.id}", listOf(resource("legacy-resource"))),
    )

    @Test
    fun everyProvidersLegacySingleAccountRecordBecomesTheActiveAccountLosslessly() {
        val fixture = HostingStoreFixture()
        legacyCredentials.forEach { fixture.writeLegacyRecord(legacyConnection(it)) }
        val before = fixture.stores.mapValues { (_, store) -> checkNotNull(store.bytes).copyOf() }
        val store = HostingConnectionStore(fixture.repository) { 30L }

        val restored = store.restoreAll()

        HostingProvider.entries.forEach { provider ->
            val result = restored.getValue(provider) as HostingRestoreResult.Restored
            assertEquals("legacy-${provider.id}", result.profile.id)
            assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, result.accountId)
            assertEquals("legacy-resource", result.cachedSnapshot?.resources?.single()?.id)
            val accounts = store.accounts(provider)
            assertEquals(listOf(HostingSavedAccount("primary", result.profile, isActive = true)), accounts)
            // Upgrading never rewrites (or deletes) the pre-multi-account file.
            assertArrayEquals(before.getValue(provider), fixture.stores.getValue(provider).bytes)
        }
        assertTrue(fixture.files.extra.values.all { it.bytes == null })
        val loadedAws = fixture.repository.load(HostingProvider.AWS_AMPLIFY)!!.account.credentials as HostingCredentials.AwsAmplify
        assertEquals(SecretValue.of("legacy-aws"), loadedAws.secretAccessKey)
    }

    @Test
    fun legacyFirebaseAccountKeepsTheOriginalGoogleSlotAndNewAccountsGetTheirOwn() {
        val fixture = HostingStoreFixture()
        fixture.writeLegacyRecord(legacyConnection(HostingCredentials.Firebase("studio-prod")))
        val store = HostingConnectionStore(fixture.repository) { 30L }

        val legacy = fixture.repository.load(HostingProvider.FIREBASE)!!.account.credentials as HostingCredentials.Firebase
        assertEquals("hosting.firebase", legacy.googleSlot)
        assertEquals(FirebaseGoogleSlots.LEGACY, FirebaseGoogleSlots.forAccount(AccountVaultLayout.PRIMARY_ACCOUNT_ID))

        val commit = store.saveValidatedConnection(
            HostingCredentials.Firebase("docs-prod"),
            snapshot(HostingProvider.FIREBASE, "sub:docs-prod", emptyList()),
        )
        store.acceptValidatedConnection(commit)
        assertTrue(commit.isNewAccount)
        val added = fixture.repository.load(HostingProvider.FIREBASE)!!.account.credentials as HostingCredentials.Firebase
        assertEquals("docs-prod", added.projectId)
        assertEquals("hosting.firebase.${commit.accountId}", added.googleSlot)
        assertTrue(FirebaseGoogleSlots.isValid(added.googleSlot))
        assertNotEquals(FirebaseGoogleSlots.SIGN_IN, added.googleSlot)
        // The legacy account still reads its original slot.
        val primary = fixture.repository.load(HostingProvider.FIREBASE, "primary")!!.account.credentials as HostingCredentials.Firebase
        assertEquals("hosting.firebase", primary.googleSlot)
    }

    @Test
    fun addingADifferentIdentityKeepsBothAccountsAndActivatesTheNewOne() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("first")), snapshot(HostingProvider.RENDER, "tea_1")),
        )
        val second = store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("second")), snapshot(HostingProvider.RENDER, "tea_2"))
        store.acceptValidatedConnection(second)

        assertTrue(second.isNewAccount)
        val accounts = store.accounts(HostingProvider.RENDER)
        assertEquals(listOf("tea_1", "tea_2"), accounts.map { it.profile?.id })
        assertEquals(listOf(false, true), accounts.map { it.isActive })
        assertEquals("tea_2", (store.restore(HostingProvider.RENDER) as HostingRestoreResult.Restored).profile.id)
        // The first account's credential was not touched.
        val first = fixture.repository.load(HostingProvider.RENDER, "primary")!!.account.credentials as HostingCredentials.Render
        assertEquals(SecretValue.of("first"), first.apiKey)
    }

    @Test
    fun reconnectingTheSameIdentityRotatesItsCredentialsInPlace() {
        val fixture = HostingStoreFixture()
        var now = 100L
        val store = HostingConnectionStore(fixture.repository) { now }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("first")), snapshot(HostingProvider.RENDER, "tea_1")),
        )
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("other")), snapshot(HostingProvider.RENDER, "tea_2")),
        )
        now = 500L

        val rotation = store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("rotated")), snapshot(HostingProvider.RENDER, "tea_1"))
        store.acceptValidatedConnection(rotation)

        assertFalse(rotation.isNewAccount)
        assertEquals("primary", rotation.accountId)
        assertEquals(2, store.accounts(HostingProvider.RENDER).size)
        val rotated = checkNotNull(fixture.repository.load(HostingProvider.RENDER))
        assertEquals("tea_1", rotated.account.profile.id)
        assertEquals(SecretValue.of("rotated"), (rotated.account.credentials as HostingCredentials.Render).apiKey)
        assertEquals(100L, rotated.account.createdAtMillis)
        assertEquals(500L, rotated.account.updatedAtMillis)
    }

    @Test
    fun cancelledRotationRestoresTheOldCredentialAndKeepsTheOtherAccountActive() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("first")), snapshot(HostingProvider.HEROKU, "user-1")),
        )
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("second")), snapshot(HostingProvider.HEROKU, "user-2")),
        )

        val rotation = store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("rotated")), snapshot(HostingProvider.HEROKU, "user-1"))
        assertEquals("user-1", fixture.repository.load(HostingProvider.HEROKU)?.account?.profile?.id)
        assertTrue(store.rollbackValidatedConnection(rotation))

        assertEquals("user-2", fixture.repository.load(HostingProvider.HEROKU)?.account?.profile?.id)
        val first = fixture.repository.load(HostingProvider.HEROKU, "primary")!!.account.credentials as HostingCredentials.Heroku
        assertEquals(SecretValue.of("first"), first.token)
    }

    @Test
    fun switchingRemovingAndRemovingAllAccounts() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        val ids = listOf("acct-a", "acct-b", "acct-c").map { profileId ->
            store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of(profileId), profileId), snapshot(HostingProvider.FLY, profileId))
                .also(store::acceptValidatedConnection)
                .accountId
        }
        assertEquals("acct-c", (store.restore(HostingProvider.FLY) as HostingRestoreResult.Restored).profile.id)

        assertTrue(store.switchAccount(HostingProvider.FLY, ids[0]))
        assertFalse(store.switchAccount(HostingProvider.FLY, "missing-account"))
        val restored = store.restore(HostingProvider.FLY) as HostingRestoreResult.Restored
        assertEquals("acct-a", restored.profile.id)
        assertEquals(ids[0], restored.accountId)
        assertEquals("acct-a", restored.linkContext.flyOrganization)

        // Removing the active account activates the first remaining one.
        assertEquals(ids[1], store.removeAccount(HostingProvider.FLY, ids[0]))
        assertEquals(listOf("acct-b", "acct-c"), store.accounts(HostingProvider.FLY).map { it.profile?.id })
        assertEquals("acct-b", (store.restore(HostingProvider.FLY) as HostingRestoreResult.Restored).profile.id)

        store.disconnect(HostingProvider.FLY)
        assertEquals(HostingRestoreResult.NotConnected, store.restore(HostingProvider.FLY))
        assertTrue(store.accounts(HostingProvider.FLY).isEmpty())
        assertNull(fixture.stores.getValue(HostingProvider.FLY).bytes)
        assertTrue(fixture.files.extra.values.all { it.bytes == null })
    }

    @Test
    fun refreshAfterASwitchIsStoredInTheAccountItStartedFor() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("a")), snapshot(HostingProvider.RENDER, "tea_a")),
        )
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("b")), snapshot(HostingProvider.RENDER, "tea_b")),
        )
        val refreshingA = checkNotNull(store.loadAccount(HostingProvider.RENDER, "primary"))
        store.switchAccount(HostingProvider.RENDER, checkNotNull(store.accounts(HostingProvider.RENDER).last().accountId))

        assertTrue(
            store.persistRefreshResult(
                refreshingA.accountId,
                snapshot(HostingProvider.RENDER, "tea_a", listOf(resource("fresh-a"))),
            ),
        )
        // A snapshot for another identity can never land in this account.
        assertFalse(store.persistRefreshResult("primary", snapshot(HostingProvider.RENDER, "tea_b", listOf(resource("wrong")))))

        assertEquals("fresh-a", fixture.repository.load(HostingProvider.RENDER, "primary")?.cachedSnapshot?.resources?.single()?.id)
        assertEquals("tea_b", fixture.repository.load(HostingProvider.RENDER)?.account?.profile?.id)
        assertEquals(listOf("r1"), fixture.repository.load(HostingProvider.RENDER)?.cachedSnapshot?.resources?.map { it.id })
    }

    @Test
    fun launchProfileRefreshUpdatesNamesButNeverChangesIdentityOrStaleRecords() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("a")), snapshot(HostingProvider.RENDER, "tea_a")),
        )
        val saved = store.loadAllAccounts(HostingProvider.RENDER).single()

        assertFalse(store.persistRefreshedProfile(saved, HostingProfile("someone-else", "Other", null, null)))
        assertTrue(store.persistRefreshedProfile(saved, HostingProfile("tea_a", "Renamed team", "new@studio.example", "https://avatar.example/a.png")))
        // The record changed, so the same (now stale) expectation cannot write again.
        assertFalse(store.persistRefreshedProfile(saved, HostingProfile("tea_a", "Stale", null, null)))

        val account = store.accounts(HostingProvider.RENDER).single()
        assertEquals("Renamed team", account.profile?.name)
        assertEquals("https://avatar.example/a.png", account.profile?.avatarUrl)
        assertEquals("Renamed team", fixture.repository.load(HostingProvider.RENDER)?.cachedSnapshot?.profile?.name)
        assertEquals(SecretValue.of("a"), (fixture.repository.load(HostingProvider.RENDER)!!.account.credentials as HostingCredentials.Render).apiKey)
    }

    @Test
    fun anUnreadableSecondAccountIsListedAndCanBeRemovedWithoutTouchingTheFirst() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("a")), snapshot(HostingProvider.HEROKU, "user-a")),
        )
        val second = store.saveValidatedConnection(HostingCredentials.Heroku(SecretValue.of("b")), snapshot(HostingProvider.HEROKU, "user-b"))
        store.acceptValidatedConnection(second)
        val secondPath = HostingConnectionRepository.layout(HostingProvider.HEROKU).recordPath(second.accountId)
        fixture.files(secondPath).write(byteArrayOf(1, 2, 3))

        val accounts = store.accounts(HostingProvider.HEROKU)
        assertEquals(listOf("user-a", null), accounts.map { it.profile?.id })
        assertEquals(
            HostingRestoreResult.Unavailable(HostingRestoreProblem.SAVED_RECORD_UNREADABLE),
            store.restore(HostingProvider.HEROKU),
        )
        assertEquals("primary", store.removeAccount(HostingProvider.HEROKU, second.accountId))
        assertEquals("user-a", (store.restore(HostingProvider.HEROKU) as HostingRestoreResult.Restored).profile.id)
    }

    @Test
    fun twoFlyUsersSharingTheirPersonalOrganizationNeverOverwriteEachOther() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        // Fly.io only reports the organization slug, so both users' profiles are "personal".
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("first-user"), "personal"), snapshot(HostingProvider.FLY, "personal")),
        )
        val second = store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("second-user"), "personal"), snapshot(HostingProvider.FLY, "personal"))
        store.acceptValidatedConnection(second)

        assertTrue(second.isNewAccount)
        assertEquals(2, store.accounts(HostingProvider.FLY).size)
        assertEquals(SecretValue.of("first-user"), (fixture.repository.load(HostingProvider.FLY, "primary")!!.account.credentials as HostingCredentials.Fly).token)

        // The same token again is the same account.
        val again = store.saveValidatedConnection(HostingCredentials.Fly(SecretValue.of("second-user"), "personal"), snapshot(HostingProvider.FLY, "personal"))
        store.acceptValidatedConnection(again)
        assertFalse(again.isNewAccount)
        assertEquals(2, store.accounts(HostingProvider.FLY).size)
    }

    @Test
    fun amplifyIsIdentifiedByTheFullAccessKeyAndRegion() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        fun amplify(keyId: String, secret: String) = HostingCredentials.AwsAmplify(keyId, SecretValue.of(secret), "us-east-1", null)
        // Both keys end in "MPLE", so their profile ids ("MPLE-us-east-1") collide.
        val first = store.saveValidatedConnection(amplify("AKIAIOSFODNN7EXAMPLE", "a"), snapshot(HostingProvider.AWS_AMPLIFY, "MPLE-us-east-1"))
        store.acceptValidatedConnection(first)
        val other = store.saveValidatedConnection(amplify("AKIAI44QH8DHBEXAMPLE", "b"), snapshot(HostingProvider.AWS_AMPLIFY, "MPLE-us-east-1"))
        store.acceptValidatedConnection(other)
        assertTrue(other.isNewAccount)

        val rotated = store.saveValidatedConnection(amplify("AKIAIOSFODNN7EXAMPLE", "a-rotated"), snapshot(HostingProvider.AWS_AMPLIFY, "MPLE-us-east-1"))
        store.acceptValidatedConnection(rotated)
        assertFalse(rotated.isNewAccount)
        assertEquals("primary", rotated.accountId)
        assertEquals(2, store.accounts(HostingProvider.AWS_AMPLIFY).size)
    }

    @Test
    fun removeActiveAccountRemovesOnlyTheActiveOne() {
        val fixture = HostingStoreFixture()
        val store = HostingConnectionStore(fixture.repository) { 100L }
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("a")), snapshot(HostingProvider.RENDER, "tea_a")),
        )
        store.acceptValidatedConnection(
            store.saveValidatedConnection(HostingCredentials.Render(SecretValue.of("b")), snapshot(HostingProvider.RENDER, "tea_b")),
        )

        assertEquals("primary", store.removeActiveAccount(HostingProvider.RENDER))
        assertEquals(listOf("tea_a"), store.accounts(HostingProvider.RENDER).map { it.profile?.id })
    }

    private fun snapshot(
        provider: HostingProvider,
        profileId: String,
        resources: List<HostingResource> = listOf(resource("r1")),
    ) = HostingSnapshot(provider, profile(profileId, "Studio $profileId"), resources, 50L)
}
