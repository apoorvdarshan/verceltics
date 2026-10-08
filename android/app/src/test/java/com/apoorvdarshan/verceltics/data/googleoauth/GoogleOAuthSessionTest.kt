package com.apoorvdarshan.verceltics.data.googleoauth

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.sites.AesGcmTestCipher
import com.apoorvdarshan.verceltics.data.sites.DirectExecutor
import com.apoorvdarshan.verceltics.data.sites.FakeProviderTransport
import com.apoorvdarshan.verceltics.data.sites.MemoryBytesStore
import com.apoorvdarshan.verceltics.data.sites.json
import com.apoorvdarshan.verceltics.data.sites.ok
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleOAuthSessionTest {
    private val analytics = GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY

    @Test
    fun accessTokenIsNullUntilACoveringCredentialIsSaved() = runTest {
        val session = session(FakeAuthorizer())

        assertNull(session.accessToken(analytics))
        session.save(credential(scopes = listOf("openid", "email", GoogleOAuthScopes.FIREBASE_HOSTING)))
        assertNull(session.accessToken(analytics))
        session.save(credential(accessToken = "ga-token"))
        assertEquals("ga-token", session.accessToken(analytics))
    }

    @Test
    fun expiringTokenIsRefreshedPersistedAndReused() = runTest {
        var now = 1_000_000L
        val authorizer = FakeAuthorizer(refreshed = { credential(accessToken = "refreshed", expiresAtMillis = now + 3_600_000) })
        val store = MemoryCredentialStore()
        val session = session(authorizer, store) { now }
        session.save(credential(accessToken = "old", expiresAtMillis = now + 30_000))

        assertEquals("refreshed", session.accessToken(analytics))
        assertEquals(1, authorizer.refreshCalls)
        assertEquals("refreshed", store.credential?.accessToken?.use { it })
        now += 60_000
        assertEquals("refreshed", session.accessToken(analytics))
        assertEquals(1, authorizer.refreshCalls)
    }

    @Test
    fun forceRefreshBypassesAFreshTokenAfterA401() = runTest {
        val authorizer = FakeAuthorizer(refreshed = { credential(accessToken = "forced") })
        val session = session(authorizer)
        session.save(credential(accessToken = "fresh"))

        assertEquals("fresh", session.accessTokenSecret(analytics)?.use { it })
        assertEquals("forced", session.accessTokenSecret(analytics, forceRefresh = true)?.use { it })
        assertEquals(1, authorizer.refreshCalls)
    }

    @Test
    fun revokedRefreshReturnsNullButNetworkFailuresPropagate() = runTest {
        val revoked = session(FakeAuthorizer(refreshError = GoogleOAuthException("revoked", isRevoked = true)))
        revoked.save(credential(expiresAtMillis = 0))
        assertNull(revoked.accessToken(analytics))

        val offline = session(FakeAuthorizer(refreshError = IOException("offline")))
        offline.save(credential(expiresAtMillis = 0))
        val error = runCatching { offline.accessToken(analytics) }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test
    fun concurrentReadersShareOneRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val authorizer = FakeAuthorizer(refreshed = { credential(accessToken = "shared") }, refreshGate = gate)
        val session = session(authorizer)
        session.save(credential(expiresAtMillis = 0))

        val first = async { session.accessToken(analytics) }
        val second = async { session.accessToken(analytics) }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals("shared", first.await())
        assertEquals("shared", second.await())
        assertEquals(1, authorizer.refreshCalls)
    }

    @Test
    fun saveReturnsPreviousCredentialSoCallersCanCompensate() = runTest {
        val store = MemoryCredentialStore()
        val session = session(FakeAuthorizer(), store)

        assertNull(session.save(credential(accessToken = "first")))
        val previous = session.save(credential(accessToken = "second"))
        assertEquals("first", previous?.accessToken?.use { it })
        session.restore(previous)
        assertEquals("first", store.credential?.accessToken?.use { it })
        session.restore(null)
        assertNull(store.credential)
    }

    @Test
    fun signInRunsAuthorizationSavesAndReportsIdentity() = runTest {
        val store = MemoryCredentialStore()
        val authorizer = FakeAuthorizer(authorized = { credential(accessToken = "signed-in") })
        val session = session(authorizer, store)

        val identity = session.signIn(analytics)

        assertEquals(GoogleAccountIdentity("subject-1", "owner@example.com"), identity)
        assertEquals(listOf(analytics), authorizer.authorizedScopes)
        assertEquals("signed-in", session.accessToken(analytics))
        assertEquals(identity, session.identity())
        assertTrue(session.hasCredential(analytics))
        session.signOut()
        assertNull(store.credential)
        assertFalse(session.hasCredential(analytics))
    }

    @Test
    fun nativeAuthorizerRunsBrowserCallbackExchangeAndIdentity() = runTest {
        val configuration = GoogleOAuthClientConfiguration(
            "777-native.apps.googleusercontent.com",
            "com.googleusercontent.apps.777-native",
        )
        val transport = FakeProviderTransport { request ->
            when (request.uri.host) {
                "oauth2.googleapis.com" -> ok(
                    json(
                        "access_token" to "exchanged",
                        "refresh_token" to "refresh",
                        "expires_in" to 3600,
                        "scope" to "openid https://www.googleapis.com/auth/userinfo.email ${GoogleOAuthScopes.ANALYTICS_READONLY}",
                    ),
                )
                else -> ok(json("sub" to "google-sub", "email" to "analyst@example.com"))
            }
        }
        var openedUri: URI? = null
        val browser = GoogleOAuthBrowser { uri ->
            openedUri = uri
            val state = query(uri).getValue("state")
            assertTrue(GoogleOAuthCallbackBroker.deliver(URI("${configuration.redirectUri}?code=browser-code&state=$state")))
        }
        val authorizer = NativeGoogleOAuthAuthorizer(browser, configuration, GoogleOAuthApi(transport), DirectExecutor)

        val credential = authorizer.authorize(analytics)

        assertEquals("exchanged", credential.accessToken.use { it })
        assertEquals("google-sub", credential.subject)
        assertEquals("analyst@example.com", credential.email)
        assertEquals("browser-code", transport.requests.first().form["code"])
        assertEquals(query(openedUri!!)["redirect_uri"], transport.requests.first().form["redirect_uri"])
        assertEquals("exchanged", transport.requests.last().bearer)
        assertFalse(GoogleOAuthCallbackBroker.hasPendingAuthorization)
    }

    @Test
    fun nativeAuthorizerRejectsGrantsMissingRequestedScopes() = runTest {
        val configuration = GoogleOAuthClientConfiguration("888-x.apps.googleusercontent.com", "com.googleusercontent.apps.888-x")
        val transport = FakeProviderTransport {
            ok(json("access_token" to "a", "refresh_token" to "r", "expires_in" to 3600, "scope" to "openid email"))
        }
        val browser = GoogleOAuthBrowser { uri ->
            GoogleOAuthCallbackBroker.deliver(URI("${configuration.redirectUri}?code=c&state=${query(uri).getValue("state")}"))
        }
        val error = runCatching {
            NativeGoogleOAuthAuthorizer(browser, configuration, GoogleOAuthApi(transport), DirectExecutor).authorize(analytics)
        }.exceptionOrNull()
        assertTrue(error is GoogleOAuthException)
        assertFalse(GoogleOAuthCallbackBroker.hasPendingAuthorization)
    }

    @Test
    fun unconfiguredAuthorizerExplainsConfigurationIsNeeded() = runTest {
        val authorizer = NativeGoogleOAuthAuthorizer(GoogleOAuthBrowser { error("no browser") }, configuration = null)
        val session = session(authorizer)
        assertFalse(session.isConfigured)
        val error = runCatching { session.authorize(analytics) }.exceptionOrNull()
        assertEquals(NativeGoogleOAuthAuthorizer.CONFIGURATION_MISSING_MESSAGE, error?.message)
    }

    @Test
    fun encryptedStoreRoundTripsAndBindsRecordsToTheirSlot() {
        val bytes = MemoryBytesStore()
        val cipher = AesGcmTestCipher()
        val analyticsStore = EncryptedGoogleOAuthCredentialStore("site.google-analytics", bytes, cipher)
        analyticsStore.save(credential(accessToken = "encrypted-token", refreshToken = "encrypted-refresh"))

        val raw = String(checkNotNull(bytes.bytes), StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("encrypted-token"))
        assertFalse(raw.contains("encrypted-refresh"))
        val loaded = checkNotNull(analyticsStore.load())
        assertEquals("encrypted-token", loaded.accessToken.use { it })
        assertEquals("encrypted-refresh", loaded.refreshToken?.use { it })
        assertEquals(credential().scopes, loaded.scopes)
        assertEquals("owner@example.com", loaded.email)

        val firebaseView = EncryptedGoogleOAuthCredentialStore("hosting.firebase", bytes, cipher)
        assertThrows(Exception::class.java) { firebaseView.load() }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedGoogleOAuthCredentialStore("../escape", bytes, cipher)
        }
        analyticsStore.delete()
        assertNull(analyticsStore.load())
    }

    private fun session(
        authorizer: GoogleOAuthAuthorizer,
        store: GoogleOAuthCredentialStore = MemoryCredentialStore(),
        nowMillis: () -> Long = { 1_000_000L },
    ) = GoogleOAuthSession("site.google-analytics", store, authorizer, DirectExecutor, nowMillis)

    private class MemoryCredentialStore : GoogleOAuthCredentialStore {
        var credential: GoogleOAuthCredential? = null

        override fun load(): GoogleOAuthCredential? = credential

        override fun save(credential: GoogleOAuthCredential) {
            this.credential = credential
        }

        override fun delete() {
            credential = null
        }
    }

    private class FakeAuthorizer(
        private val authorized: () -> GoogleOAuthCredential = { credential() },
        private val refreshed: () -> GoogleOAuthCredential = { credential(accessToken = "refreshed") },
        private val refreshError: Exception? = null,
        private val refreshGate: CompletableDeferred<Unit>? = null,
    ) : GoogleOAuthAuthorizer {
        var refreshCalls = 0
        val authorizedScopes = ArrayList<Set<String>>()

        override val configuration: GoogleOAuthClientConfiguration? = GoogleOAuthClientConfiguration(
            "1-test.apps.googleusercontent.com",
            "com.googleusercontent.apps.1-test",
        )

        override suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential {
            authorizedScopes += scopes
            return authorized()
        }

        override suspend fun refresh(credential: GoogleOAuthCredential): GoogleOAuthCredential {
            refreshCalls += 1
            refreshGate?.await()
            refreshError?.let { throw it }
            return refreshed()
        }
    }

    private companion object {
        fun credential(
            accessToken: String = "access",
            refreshToken: String? = "refresh",
            expiresAtMillis: Long = 10_000_000L,
            scopes: List<String> = GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY.toList(),
        ) = GoogleOAuthCredential(
            accessToken = SecretValue.of(accessToken),
            refreshToken = refreshToken?.let(SecretValue::of),
            tokenType = "Bearer",
            scopes = scopes,
            expiresAtMillis = expiresAtMillis,
            subject = "subject-1",
            email = "owner@example.com",
        )

        fun query(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { pair ->
            val (name, value) = pair.split('=', limit = 2)
            URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }
    }
}
