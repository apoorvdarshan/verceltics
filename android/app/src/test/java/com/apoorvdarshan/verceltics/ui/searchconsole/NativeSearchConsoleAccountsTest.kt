package com.apoorvdarshan.verceltics.ui.searchconsole

import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthClientConfiguration
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredential
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthSession
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsQuery
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleAnalyticsResponse
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleConnectionPayloadCodec
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleConnectionRepository
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleConnectionStore
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleDataSource
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleOAuthClientConfiguration
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleOAuthCredential
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleProperty
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsolePropertyList
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleReadApi
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSitemap
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleSnapshot
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleStoredConnection
import com.apoorvdarshan.verceltics.data.searchconsole.SearchConsoleUrlInspectionResult
import com.apoorvdarshan.verceltics.data.sites.AesGcmTestCipher
import com.apoorvdarshan.verceltics.data.sites.MemoryBytesStore
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-account Search Console: per-account OAuth slots, rotation, switching, removal, migration. */
class NativeSearchConsoleAccountsTest {
    private val now = 1_000_000L
    private val files = HashMap<String, MemoryBytesStore>()
    private val slotFiles = HashMap<String, MemoryBytesStore>()
    private val cipher = AesGcmTestCipher()
    private val oauthCipher = AesGcmTestCipher()
    private val api = FakeReadApi()
    private val authorizer = FakeSearchConsoleAuthorizer()
    private val refresher = FakeRefresher()
    private val sessions = HashMap<String, GoogleOAuthSession>()
    private val network = Executors.newFixedThreadPool(2)
    private val storage = Executors.newSingleThreadExecutor()
    private val store = SearchConsoleConnectionStore(
        SearchConsoleConnectionRepository(::file, cipher, ::credentialStore),
    ) { now }

    private fun file(path: String): MemoryBytesStore = synchronized(files) { files.getOrPut(path) { MemoryBytesStore() } }

    private fun credentialStore(slot: String) =
        EncryptedGoogleOAuthCredentialStore(slot, synchronized(slotFiles) { slotFiles.getOrPut(slot) { MemoryBytesStore() } }, oauthCipher)

    private fun slotToken(accountId: String): String? =
        credentialStore(SearchConsoleConnectionRepository.credentialSlot(accountId)).load()?.accessToken?.use { it }

    private fun gateway(beforeAccept: suspend () -> Unit = {}) = NativeSearchConsoleUiGateway(
        connectionStore = store,
        dataSource = SearchConsoleDataSource(api) { now },
        authorizer = authorizer,
        sessionFor = { accountId ->
            val slot = SearchConsoleConnectionRepository.credentialSlot(accountId)
            synchronized(sessions) {
                sessions.getOrPut(slot) { GoogleOAuthSession(slot, credentialStore(slot), refresher, storage, { now }) }
            }
        },
        networkExecutor = network,
        storageExecutor = storage,
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        beforeAcceptValidatedConnection = beforeAccept,
    )

    @After
    fun shutDown() {
        network.shutdownNow()
        storage.shutdownNow()
    }

    @Test
    fun eachGoogleAccountGetsItsOwnSlotAndTheSameIdentityRotatesInPlace() = runBlocking {
        val gateway = gateway()
        authorizer.next = credential("token-a1", "subject-a", "a@example.com")
        val first = gateway.connect().getOrThrow()
        authorizer.next = credential("token-b1", "subject-b", "b@example.com")
        val second = gateway.connect().getOrThrow()

        val accounts = gateway.accounts().getOrThrow()
        assertEquals(listOf("a@example.com", "b@example.com"), accounts.accounts.map { it.title })
        assertEquals(second.account.id, accounts.activeAccountId)
        assertEquals("token-a1", slotToken(first.account.id))
        assertEquals("token-b1", slotToken(second.account.id))

        authorizer.next = credential("token-a2", "subject-a", "a@example.com")
        val rotated = gateway.connect().getOrThrow()
        assertEquals(first.account.id, rotated.account.id)
        assertEquals(2, gateway.accounts().getOrThrow().accounts.size)
        assertEquals("token-a2", slotToken(first.account.id))
        assertEquals("token-b1", slotToken(second.account.id))
    }

    @Test
    fun requestsUseTheActiveAccountsSlotAndRefreshItsToken() = runBlocking {
        val gateway = gateway()
        authorizer.next = credential("token-a", "subject-a", "a@example.com")
        val first = gateway.connect().getOrThrow()
        authorizer.next = credential("token-b", "subject-b", "b@example.com", expiresAtMillis = now + 10_000L)
        val second = gateway.connect().getOrThrow()

        refresher.next = googleCredential("token-b-refreshed", "subject-b", "b@example.com")
        val refreshed = gateway.refresh().getOrThrow()
        assertEquals(second.account.id, refreshed.account.id)
        assertEquals("token-b-refreshed", api.tokens.last())
        assertEquals("token-b-refreshed", slotToken(second.account.id))

        val switched = gateway.switchAccount(first.account.id).getOrThrow() as SearchConsoleRestoreUi.Available
        assertEquals(first.account.id, switched.dashboard.account.id)
        assertEquals(SearchConsoleCacheState.CACHED_FRESH, switched.dashboard.cacheState)
        gateway.refresh().getOrThrow()
        assertEquals("token-a", api.tokens.last())
        assertTrue(gateway.switchAccount("missing").isFailure)
    }

    @Test
    fun failedValidationOrCancelledPersistenceLeavesNoAccountOrCredential() = runBlocking {
        api.failNextList = true
        authorizer.next = credential("token-a", "subject-a", "a@example.com")
        assertTrue(gateway().connect().isFailure)
        assertEquals(0, store.accounts().accounts.size)
        assertTrue(slotFiles.values.all { it.bytes == null })

        authorizer.next = credential("token-b", "subject-b", "b@example.com")
        val failing = gateway(beforeAccept = { throw IOException("interrupted") })
        assertTrue(failing.connect().isFailure)
        assertEquals(0, store.accounts().accounts.size)
        assertTrue(slotFiles.values.all { it.bytes == null })
    }

    @Test
    fun cancelledRotationRestoresThePreviousCredential() = runBlocking {
        authorizer.next = credential("token-a1", "subject-a", "a@example.com")
        val first = gateway().connect().getOrThrow()

        authorizer.next = credential("token-a2", "subject-a", "a@example.com")
        assertTrue(gateway(beforeAccept = { throw IOException("interrupted") }).connect().isFailure)

        assertEquals("token-a1", slotToken(first.account.id))
        assertEquals(listOf(first.account.id), store.accounts().ids)
    }

    @Test
    fun removingAnAccountClearsOnlyItsSlotAndRemoveAllClearsEverySlot() = runBlocking {
        val gateway = gateway()
        authorizer.next = credential("token-a", "subject-a", "a@example.com")
        val first = gateway.connect().getOrThrow()
        authorizer.next = credential("token-b", "subject-b", "b@example.com")
        val second = gateway.connect().getOrThrow()

        val next = gateway.removeAccount(second.account.id).getOrThrow() as SearchConsoleRestoreUi.Available
        assertEquals(first.account.id, next.dashboard.account.id)
        assertNull(slotToken(second.account.id))
        assertEquals("token-a", slotToken(first.account.id))

        authorizer.next = credential("token-c", "subject-c", "c@example.com")
        val third = gateway.connect().getOrThrow()
        gateway.removeAllAccounts().getOrThrow()
        assertNull(slotToken(first.account.id))
        assertNull(slotToken(third.account.id))
        assertEquals(SearchConsoleRestoreUi.NotConnected, gateway.restore().getOrThrow())
    }

    @Test
    fun aSavedAccountWithoutAUsableCredentialAsksToReconnect() = runBlocking {
        val gateway = gateway()
        authorizer.next = credential("token-a", "subject-a", "a@example.com")
        val connected = gateway.connect().getOrThrow()
        credentialStore(SearchConsoleConnectionRepository.credentialSlot(connected.account.id)).delete()

        assertEquals(
            "Google access for a@example.com expired or was revoked. Reconnect the account.",
            gateway.refresh().exceptionOrNull()?.message,
        )
    }

    @Test
    fun legacySingleAccountConnectionKeepsWorkingAfterMigration() = runBlocking {
        val legacy = SearchConsoleStoredConnection(
            id = "subject-legacy",
            credential = credential("legacy-token", "subject-legacy", "legacy@example.com"),
            createdAtMillis = 10L,
            updatedAtMillis = 20L,
            cachedSnapshot = SearchConsoleSnapshot(listOf(SearchConsoleProperty("sc-domain:legacy.example", "siteOwner")), now, true, emptyList()),
        )
        file("accounts/google-search-console-oauth.account").bytes = AccountEnvelopeCodec.encode(
            cipher.encrypt(
                SearchConsoleConnectionPayloadCodec.encode(legacy),
                "verceltics.account-envelope.v1:google-search-console-oauth".toByteArray(StandardCharsets.UTF_8),
            ),
        )
        val gateway = gateway()

        val restored = gateway.restore().getOrThrow() as SearchConsoleRestoreUi.Available
        val accountId = SiteAccountIds.migrated("google-search-console")
        assertEquals(accountId, restored.dashboard.account.id)
        assertEquals("legacy@example.com", restored.dashboard.account.email)
        assertEquals(listOf("sc-domain:legacy.example"), restored.dashboard.properties.map { it.siteUrl })
        assertEquals(accountId, gateway.accounts().getOrThrow().activeAccountId)

        gateway.refresh().getOrThrow()
        assertEquals("legacy-token", api.tokens.last())
    }

    private fun credential(
        token: String,
        subject: String,
        email: String,
        expiresAtMillis: Long = now + 3_600_000L,
    ) = SearchConsoleOAuthCredential(
        SecretValue.of(token),
        SecretValue.of("refresh-$subject"),
        "Bearer",
        SearchConsoleOAuthCredential.REQUIRED_SCOPES,
        expiresAtMillis,
        subject,
        email,
    )

    private fun googleCredential(token: String, subject: String, email: String) = GoogleOAuthCredential(
        SecretValue.of(token),
        SecretValue.of("refresh-$subject"),
        "Bearer",
        SearchConsoleOAuthCredential.REQUIRED_SCOPES,
        now + 3_600_000L,
        subject,
        email,
    )

    private class FakeSearchConsoleAuthorizer : SearchConsoleOAuthAuthorizer {
        var next: SearchConsoleOAuthCredential? = null

        override val configuration = SearchConsoleOAuthClientConfiguration(
            "1-test.apps.googleusercontent.com",
            "com.googleusercontent.apps.1-test",
        )

        override suspend fun authorize(): SearchConsoleOAuthCredential = checkNotNull(next)

        override suspend fun refresh(credential: SearchConsoleOAuthCredential): SearchConsoleOAuthCredential =
            error("Search Console refreshes go through the account's GoogleOAuthSession slot.")
    }

    private class FakeRefresher : GoogleOAuthAuthorizer {
        var next: GoogleOAuthCredential? = null

        override val configuration =
            GoogleOAuthClientConfiguration("1-test.apps.googleusercontent.com", "com.googleusercontent.apps.1-test")

        override suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential = error("unused")

        override suspend fun refresh(credential: GoogleOAuthCredential): GoogleOAuthCredential = checkNotNull(next)
    }

    /** Lists one property per token so tests can see which account's token a request used. */
    private class FakeReadApi : SearchConsoleReadApi {
        val tokens: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        @Volatile
        var failNextList = false

        override fun newListVerifiedPropertiesCall(credential: SearchConsoleOAuthCredential): CancelableCall<SearchConsolePropertyList> =
            call {
                val token = credential.accessToken.use { it }
                tokens += token
                if (failNextList) {
                    failNextList = false
                    throw IOException("offline")
                }
                SearchConsolePropertyList(listOf(SearchConsoleProperty("sc-domain:$token.example", "siteOwner")), 0)
            }

        override fun newAnalyticsPageCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            query: SearchConsoleAnalyticsQuery,
        ): CancelableCall<SearchConsoleAnalyticsResponse> = call { error("unused") }

        override fun newListSitemapsCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            sitemapIndex: String?,
        ): CancelableCall<List<SearchConsoleSitemap>> = call { error("unused") }

        override fun newGetSitemapCall(
            credential: SearchConsoleOAuthCredential,
            siteUrl: String,
            feedPath: String,
        ): CancelableCall<SearchConsoleSitemap> = call { error("unused") }

        override fun newInspectUrlCall(
            credential: SearchConsoleOAuthCredential,
            inspectionUrl: String,
            siteUrl: String,
            languageCode: String,
        ): CancelableCall<SearchConsoleUrlInspectionResult> = call { error("unused") }

        private fun <T> call(block: () -> T) = object : CancelableCall<T> {
            override fun execute(): T = block()

            override fun cancel() = Unit
        }
    }
}
