package com.apoorvdarshan.verceltics.ui.netlify

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultLayout
import com.apoorvdarshan.verceltics.data.hosting.SimpleMemoryStore
import com.apoorvdarshan.verceltics.data.hosting.TestAccountCipher
import com.apoorvdarshan.verceltics.data.hosting.primaryFiles
import com.apoorvdarshan.verceltics.data.netlify.NetlifyAccount
import com.apoorvdarshan.verceltics.data.netlify.NetlifyBuild
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionPayloadCodec
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionRepository
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionStore
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDataSource
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDeployment
import com.apoorvdarshan.verceltics.data.netlify.NetlifyFetchResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifyProfile
import com.apoorvdarshan.verceltics.data.netlify.NetlifyReadApi
import com.apoorvdarshan.verceltics.data.netlify.NetlifyRestoreResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifySite
import com.apoorvdarshan.verceltics.data.netlify.NetlifySiteDetails
import com.apoorvdarshan.verceltics.data.netlify.NetlifySnapshot
import com.apoorvdarshan.verceltics.data.netlify.NetlifyStoredConnection
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-account Netlify: lossless upgrade, add, switch, rotate, remove and launch profile refresh. */
class NetlifyMultiAccountTest {
    @Test
    fun legacySingleTokenRecordBecomesTheActiveAccountWithoutRewrite() = runBlocking {
        Fixture().use { fixture ->
            fixture.writeLegacyRecord("legacy-token", NetlifyProfile("user-legacy", "Legacy user", "legacy@example.com", null))
            val before = checkNotNull(fixture.primary.bytes).copyOf()

            val restored = fixture.connectionStore.restore() as NetlifyRestoreResult.Restored
            assertEquals("user-legacy", restored.profile.id)
            assertEquals(AccountVaultLayout.PRIMARY_ACCOUNT_ID, restored.accountId)
            val accounts = fixture.gateway.accounts().getOrThrow()
            assertEquals(listOf("Legacy user"), accounts.map { it.displayName })
            assertTrue(accounts.single().isActive)
            assertArrayEquals(before, fixture.primary.bytes)

            // Adding a second account keeps the legacy record byte-for-byte.
            fixture.gateway.connect(SecretValue.of("token-two")).getOrThrow()
            assertArrayEquals(before, fixture.primary.bytes)
            assertEquals(listOf("Legacy user", "User two"), fixture.gateway.accounts().getOrThrow().map { it.displayName })
            assertEquals(SecretValue.of("legacy-token"), fixture.repository.load("primary")?.account?.personalToken)
        }
    }

    @Test
    fun addSwitchRotateAndRemoveUseTheActiveTokenOnly() = runBlocking {
        Fixture().use { fixture ->
            val gateway = fixture.gateway
            gateway.connect(SecretValue.of("token-one")).getOrThrow()
            val two = gateway.connect(SecretValue.of("token-two")).getOrThrow()
            assertEquals("User two", two.account.displayName)
            assertEquals("https://avatars.example/two.png", two.account.avatarUrl)

            val accounts = gateway.accounts().getOrThrow()
            assertEquals(listOf(false, true), accounts.map { it.isActive })
            assertEquals("https://avatars.example/one.png", accounts.first().avatarUrl)

            val switched = gateway.switchAccount(accounts.first().id).getOrThrow() as NetlifyRestoreUi.Available
            assertEquals("User one", switched.dashboard.account.displayName)
            fixture.api.tokens.clear()
            gateway.refresh().getOrThrow()
            gateway.loadSite("site-1").getOrThrow()
            assertTrue(fixture.api.tokens.isNotEmpty())
            assertTrue(fixture.api.tokens.all { it == "token-one" })

            // The same Netlify user with a new token is rotated in place.
            gateway.connect(SecretValue.of("token-one-rotated")).getOrThrow()
            assertEquals(2, gateway.accounts().getOrThrow().size)
            assertEquals(SecretValue.of("token-one-rotated"), fixture.repository.load()?.account?.personalToken)

            val afterRemoval = gateway.removeAccount(accounts.first().id).getOrThrow() as NetlifyRestoreUi.Available
            assertEquals("User two", afterRemoval.dashboard.account.displayName)
            assertEquals(listOf("User two"), gateway.accounts().getOrThrow().map { it.displayName })

            gateway.disconnect().getOrThrow()
            assertTrue(gateway.accounts().getOrThrow().isEmpty())
            assertEquals(NetlifyRestoreUi.NotConnected, gateway.restore().getOrThrow())
        }
    }

    @Test
    fun aRefreshThatFinishesAfterASwitchIsStoredInItsOwnAccount() {
        val primary = SimpleMemoryStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, primary), TestAccountCipher())
        val store = NetlifyConnectionStore(repository) { 100L }
        store.acceptValidatedConnection(store.saveValidatedConnection(SecretValue.of("a"), complete(profile("one"))))
        store.acceptValidatedConnection(store.saveValidatedConnection(SecretValue.of("b"), complete(profile("two"))))
        store.switchAccount("primary")

        val otherId = store.accounts().last().accountId
        assertTrue(store.persistRefreshResult(otherId, complete(profile("two"), siteIds = listOf("fresh"))))
        assertFalse(store.persistRefreshResult(otherId, complete(profile("one"))))

        assertEquals(listOf("fresh"), repository.load(otherId)?.cachedSnapshot?.sites?.map { it.id })
        assertEquals("user-one", repository.load()?.account?.id)
    }

    @Test
    fun launchProfileRefreshUpdatesEverySavedAccountAndSkipsFailures() = runBlocking {
        Fixture().use { fixture ->
            fixture.gateway.connect(SecretValue.of("token-one")).getOrThrow()
            fixture.gateway.connect(SecretValue.of("token-two")).getOrThrow()
            fixture.api.renamed = true
            fixture.api.failingTokens += "token-one"

            val refreshed = fixture.gateway.refreshAccountProfiles().getOrThrow()

            assertEquals(listOf("User one", "User two (renamed)"), refreshed.map { it.displayName })
            val active = fixture.gateway.restore().getOrThrow() as NetlifyRestoreUi.Available
            assertEquals("User two (renamed)", active.dashboard.account.displayName)
        }
    }

    @Test
    fun removingTheActiveAccountWithoutItsIdKeepsTheOthers() = runBlocking {
        Fixture().use { fixture ->
            fixture.gateway.connect(SecretValue.of("token-one")).getOrThrow()
            fixture.gateway.connect(SecretValue.of("token-two")).getOrThrow()

            val next = fixture.gateway.removeActiveAccount().getOrThrow() as NetlifyRestoreUi.Available

            assertEquals("User one", next.dashboard.account.displayName)
            assertEquals(listOf("User one"), fixture.gateway.accounts().getOrThrow().map { it.displayName })
        }
    }

    private fun profile(name: String) = NetlifyProfile("user-$name", "User $name", "$name@example.com", null)

    private fun complete(profile: NetlifyProfile, siteIds: List<String> = listOf("site-1")) = NetlifyFetchResult.Complete(
        NetlifySnapshot(
            profile = profile,
            sites = siteIds.map(::site),
            fetchedAtMillis = 50L,
            sitesComplete = true,
            warnings = emptyList(),
        ),
    )

    private class Fixture : AutoCloseable {
        val primary = SimpleMemoryStore()
        val repository = NetlifyConnectionRepository(primaryFiles(NetlifyConnectionRepository.ACCOUNT_PATH, primary), TestAccountCipher())
        val connectionStore = NetlifyConnectionStore(repository) { 42L }
        val api = TokenAwareApi()
        private val networkExecutor = Executors.newFixedThreadPool(4)
        private val storageExecutor = Executors.newSingleThreadExecutor()
        val gateway = NativeNetlifyUiGateway(
            connectionStore = connectionStore,
            dataSource = NetlifyDataSource(api) { 42L },
            networkExecutor = networkExecutor,
            storageExecutor = storageExecutor,
            profileApi = api,
        )

        /** The exact envelope the single-account repository wrote before multi-account support. */
        fun writeLegacyRecord(token: String, profile: NetlifyProfile) {
            val connection = NetlifyStoredConnection(
                account = NetlifyAccount(profile.id, profile.displayName, profile.email, profile.avatarUrl, SecretValue.of(token), 1L, 2L),
                cachedSnapshot = null,
            )
            val sealed = TestAccountCipher().encrypt(
                NetlifyConnectionPayloadCodec.encode(connection),
                NetlifyConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8),
            )
            primary.bytes = AccountEnvelopeCodec.encode(sealed)
        }

        override fun close() {
            networkExecutor.shutdownNow()
            storageExecutor.shutdownNow()
        }
    }

    /** Each token is a different Netlify user; `-rotated` tokens belong to the same user. */
    private class TokenAwareApi : NetlifyReadApi {
        val tokens = CopyOnWriteArrayList<String>()
        val failingTokens = CopyOnWriteArrayList<String>()

        @Volatile
        var renamed = false

        private fun user(token: SecretValue): NetlifyProfile {
            val raw = token.use { it }
            tokens += raw
            if (raw in failingTokens) throw IOException("offline")
            val name = raw.removePrefix("token-").removeSuffix("-rotated")
            val suffix = if (renamed) " (renamed)" else ""
            return NetlifyProfile("user-$name", "User $name$suffix", "$name@example.com", "https://avatars.example/$name.png")
        }

        override fun newValidatePersonalTokenCall(token: SecretValue): CancelableCall<NetlifyProfile> = Call { user(token) }

        override fun newListSitesPageCall(token: SecretValue, page: Int, perPage: Int): CancelableCall<List<NetlifySite>> =
            Call {
                tokens += token.use { it }
                if (page == 1) listOf(site("site-1")) else emptyList()
            }

        override fun newSiteDetailsCall(token: SecretValue, siteId: String): CancelableCall<NetlifySiteDetails> = Call {
            tokens += token.use { it }
            NetlifySiteDetails(site(siteId), emptyList(), null, null)
        }

        override fun newListDeploymentsPageCall(
            token: SecretValue,
            siteId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<List<NetlifyDeployment>> = Call {
            tokens += token.use { it }
            emptyList()
        }

        override fun newListBuildsPageCall(
            token: SecretValue,
            siteId: String,
            page: Int,
            perPage: Int,
        ): CancelableCall<List<NetlifyBuild>> = Call {
            tokens += token.use { it }
            emptyList()
        }

        override fun newBuildCall(token: SecretValue, buildId: String): CancelableCall<NetlifyBuild> =
            Call { error("not used") }
    }

    private class Call<T>(private val block: () -> T) : CancelableCall<T> {
        override fun execute(): T = block()

        override fun cancel() = Unit
    }

    private companion object {
        fun site(id: String) = NetlifySite(
            id = id,
            name = id,
            subtitle = "$id.example.com",
            url = "https://$id.example.com",
            status = "current",
            updatedAtMillis = 50L,
            adminUrl = "https://app.netlify.com/sites/$id",
        )
    }
}
