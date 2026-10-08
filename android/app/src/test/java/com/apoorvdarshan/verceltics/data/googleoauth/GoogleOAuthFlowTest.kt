package com.apoorvdarshan.verceltics.data.googleoauth

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.sites.FakeProviderTransport
import com.apoorvdarshan.verceltics.data.sites.FakeResponse
import com.apoorvdarshan.verceltics.data.sites.json
import com.apoorvdarshan.verceltics.data.sites.ok
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleOAuthFlowTest {
    private val configuration = GoogleOAuthClientConfiguration(
        clientId = "12345-example.apps.googleusercontent.com",
        redirectScheme = "com.googleusercontent.apps.12345-example",
    )

    @Test
    fun authorizationRequestUsesPkceS256OfflineConsentAndRequestedScopes() {
        val transaction = GooglePkceTransaction.create(
            configuration,
            GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY,
            randomBytes = { size -> ByteArray(size) { if (size == 64) 0x21 else 0x42 } },
        )
        val uri = transaction.authorizationUri
        val query = decodedQuery(uri)
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64) { 0x21 })
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.UTF_8)),
        )

        assertEquals("accounts.google.com", uri.host)
        assertEquals("/o/oauth2/v2/auth", uri.path)
        assertEquals(configuration.clientId, query["client_id"])
        assertEquals("com.googleusercontent.apps.12345-example:/oauth2redirect", query["redirect_uri"])
        assertEquals("code", query["response_type"])
        assertEquals("openid email https://www.googleapis.com/auth/analytics.readonly", query["scope"])
        assertEquals("offline", query["access_type"])
        assertEquals("consent", query["prompt"])
        assertEquals("true", query["include_granted_scopes"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(expectedChallenge, query["code_challenge"])
        assertEquals(verifier, transaction.codeVerifier.use { it })
        assertFalse(transaction.toString().contains(query.getValue("state")))
    }

    @Test
    fun scopesAreConfigurableForOtherGoogleConsumers() {
        val transaction = GooglePkceTransaction.create(configuration, GoogleOAuthScopes.FIREBASE_HOSTING_SCOPES)
        assertEquals(
            "openid email https://www.googleapis.com/auth/firebase.hosting",
            decodedQuery(transaction.authorizationUri)["scope"],
        )
        assertThrows(IllegalArgumentException::class.java) { GooglePkceTransaction.create(configuration, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) {
            GooglePkceTransaction.create(configuration, setOf("two scopes"))
        }
    }

    @Test
    fun callbackRequiresExactRedirectMatchingStateAndSingleUse() {
        val transaction = GooglePkceTransaction.create(configuration, setOf("openid"))
        val state = decodedQuery(transaction.authorizationUri).getValue("state")

        assertEquals(
            "one-use-code",
            transaction.authorizationCode(URI("${configuration.redirectUri}?code=one-use-code&state=$state")).use { it },
        )
        val replay = runCatching {
            transaction.authorizationCode(URI("${configuration.redirectUri}?code=one-use-code&state=$state"))
        }.exceptionOrNull()
        assertTrue(replay is GoogleOAuthException)

        val mismatch = GooglePkceTransaction.create(configuration, setOf("openid"))
        val failure = runCatching {
            mismatch.authorizationCode(URI("${configuration.redirectUri}?code=secret-code&state=wrong"))
        }.exceptionOrNull()
        assertTrue(failure is GoogleOAuthException)
        assertFalse(failure.toString().contains("secret-code"))
    }

    @Test
    fun searchConsoleRedirectPathAndDuplicateFieldsAreRejected() {
        val transaction = GooglePkceTransaction.create(configuration, setOf("openid"))
        val state = decodedQuery(transaction.authorizationUri).getValue("state")
        val wrongPath = runCatching {
            transaction.authorizationCode(URI("${configuration.redirectScheme}:/oauthredirect?code=x&state=$state"))
        }.exceptionOrNull()
        assertTrue(wrongPath is GoogleOAuthException)

        val duplicate = GooglePkceTransaction.create(configuration, setOf("openid"))
        val duplicateState = decodedQuery(duplicate.authorizationUri).getValue("state")
        val duplicateFailure = runCatching {
            duplicate.authorizationCode(URI("${configuration.redirectUri}?code=a&code=b&state=$duplicateState"))
        }.exceptionOrNull()
        assertTrue(duplicateFailure is GoogleOAuthException)
    }

    @Test
    fun providerErrorsSurfaceDescriptionAndMarkUserCancellation() {
        val transaction = GooglePkceTransaction.create(configuration, setOf("openid"))
        val state = decodedQuery(transaction.authorizationUri).getValue("state")
        val error = runCatching {
            transaction.authorizationCode(
                URI("${configuration.redirectUri}?error=access_denied&error_description=User%20denied&state=$state"),
            )
        }.exceptionOrNull() as GoogleOAuthException
        assertEquals("User denied", error.message)
        assertTrue(error.isCancelled)
    }

    @Test
    fun redirectMatchingRejectsAuthorityPortsAndFragments() {
        val expected = URI(configuration.redirectUri)
        assertTrue(URI("${configuration.redirectUri}?code=x&state=y").matchesGoogleOAuthRedirect(expected))
        assertFalse(URI("${configuration.redirectScheme}://attacker/oauth2redirect?code=x").matchesGoogleOAuthRedirect(expected))
        assertFalse(URI("${configuration.redirectScheme}:/oauthredirect?code=x").matchesGoogleOAuthRedirect(expected))
        assertFalse(URI("${configuration.redirectUri}#fragment").matchesGoogleOAuthRedirect(expected))
        assertFalse(URI("https:/oauth2redirect?code=x").matchesGoogleOAuthRedirect(expected))
    }

    @Test
    fun configurationRequiresReverseClientIdSchemeAndDedicatedPath() {
        listOf("https", "http", "file", "intent", "com.googleusercontent.apps.other").forEach { scheme ->
            assertThrows(IllegalArgumentException::class.java) {
                GoogleOAuthClientConfiguration(configuration.clientId, scheme)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoogleOAuthClientConfiguration("not-a-google-client", "com.googleusercontent.apps.not-a-google-client")
        }
        assertEquals("/oauth2redirect", GoogleOAuthClientConfiguration.REDIRECT_PATH)
    }

    @Test
    fun scopeCoverageTreatsUserinfoAliasesAsEquivalent() {
        val granted = listOf("openid", GoogleOAuthScopes.USERINFO_EMAIL, GoogleOAuthScopes.ANALYTICS_READONLY)
        assertTrue(GoogleOAuthScopes.covers(granted, GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY))
        assertFalse(GoogleOAuthScopes.covers(granted, GoogleOAuthScopes.FIREBASE_HOSTING_SCOPES))
    }

    @Test
    fun codeExchangePostsFormAndParsesScopesExpiryAndRefreshToken() {
        val transport = FakeProviderTransport {
            ok(
                json(
                    "access_token" to "access-1",
                    "refresh_token" to "refresh-1",
                    "expires_in" to 3599,
                    "token_type" to "Bearer",
                    "scope" to "openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/analytics.readonly",
                ),
            )
        }
        val api = GoogleOAuthApi(transport, nowMillis = { 1_000L })

        val credential = api.newExchangeAuthorizationCodeCall(
            SecretValue.of("auth-code"),
            SecretValue.of("verifier-123"),
            configuration.clientId,
            configuration.redirectUri,
            GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY,
        ).execute()

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://oauth2.googleapis.com/token", request.uri.toString())
        assertEquals("authorization_code", request.form["grant_type"])
        assertEquals("auth-code", request.form["code"])
        assertEquals("verifier-123", request.form["code_verifier"])
        assertEquals(configuration.redirectUri, request.form["redirect_uri"])
        assertEquals("access-1", credential.accessToken.use { it })
        assertEquals("refresh-1", credential.refreshToken?.use { it })
        assertEquals(1_000L + 3_599_000L, credential.expiresAtMillis)
        assertTrue(credential.covers(GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY))
        assertFalse(credential.toString().contains("access-1"))
        assertFalse(credential.toString().contains("refresh-1"))
    }

    @Test
    fun refreshKeepsExistingRefreshTokenAndIdentityWhenGoogleOmitsThem() {
        val transport = FakeProviderTransport { ok(json("access_token" to "access-2", "expires_in" to "3600")) }
        val original = credential(accessToken = "access-1", refreshToken = "refresh-1")

        val refreshed = GoogleOAuthApi(transport, nowMillis = { 5_000L }).newRefreshCall(original, configuration.clientId).execute()

        assertEquals("refresh_token", transport.requests.single().form["grant_type"])
        assertEquals("refresh-1", transport.requests.single().form["refresh_token"])
        assertEquals("access-2", refreshed.accessToken.use { it })
        assertEquals("refresh-1", refreshed.refreshToken?.use { it })
        assertEquals(original.scopes, refreshed.scopes)
        assertEquals("subject-1", refreshed.subject)
        assertEquals(5_000L + 3_600_000L, refreshed.expiresAtMillis)
    }

    @Test
    fun invalidGrantIsReportedAsRevokedWithoutLeakingTokens() {
        val transport = FakeProviderTransport {
            FakeResponse(400, json("error" to "invalid_grant", "error_description" to "Token has been expired or revoked."))
        }
        val error = runCatching {
            GoogleOAuthApi(transport).newRefreshCall(credential(refreshToken = "refresh-secret"), configuration.clientId).execute()
        }.exceptionOrNull() as GoogleOAuthException

        assertTrue(error.isRevoked)
        assertTrue(error.message!!.contains("Token has been expired or revoked."))
        assertFalse(error.message!!.contains("refresh-secret"))
    }

    @Test
    fun malformedTokenResponsesAreRejected() {
        listOf(
            json("expires_in" to 3600),
            json("access_token" to "a"),
            json("access_token" to "a", "expires_in" to 3600, "token_type" to "MAC"),
            "not json",
        ).forEach { body ->
            val error = runCatching {
                GoogleOAuthApi(FakeProviderTransport { ok(body) }).newRefreshCall(credential(), configuration.clientId).execute()
            }.exceptionOrNull()
            assertTrue(body, error is GoogleOAuthException)
        }
    }

    @Test
    fun identityCallUsesBearerTokenAndRequiresSubject() {
        val transport = FakeProviderTransport { ok(json("sub" to "subject-9", "email" to "owner@example.com")) }
        val identity = GoogleOAuthApi(transport).newIdentityCall(SecretValue.of("token-9")).execute()

        assertEquals(GoogleAccountIdentity("subject-9", "owner@example.com"), identity)
        assertEquals("token-9", transport.requests.single().bearer)
        assertEquals("openidconnect.googleapis.com", transport.requests.single().uri.host)
        val missing = runCatching {
            GoogleOAuthApi(FakeProviderTransport { ok(json("email" to "x@example.com")) })
                .newIdentityCall(SecretValue.of("t")).execute()
        }.exceptionOrNull()
        assertTrue(missing is GoogleOAuthException)
    }

    @Test
    fun missingRefreshTokenFailsBeforeNetwork() {
        val transport = FakeProviderTransport { error("must not be called") }
        val error = runCatching {
            GoogleOAuthApi(transport).newRefreshCall(credential(refreshToken = null), configuration.clientId)
        }.exceptionOrNull() as GoogleOAuthException
        assertTrue(error.isRevoked)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun credentialNeedsRefreshWithinNinetySeconds() {
        val credential = credential(expiresAtMillis = 100_000L)
        assertFalse(credential.needsRefresh(nowMillis = 9_999L))
        assertTrue(credential.needsRefresh(nowMillis = 10_000L))
        assertNull(credential(subject = null).identity)
    }

    private fun credential(
        accessToken: String = "access",
        refreshToken: String? = "refresh",
        expiresAtMillis: Long = 10_000_000L,
        subject: String? = "subject-1",
    ) = GoogleOAuthCredential(
        accessToken = SecretValue.of(accessToken),
        refreshToken = refreshToken?.let(SecretValue::of),
        tokenType = "Bearer",
        scopes = GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY.toList(),
        expiresAtMillis = expiresAtMillis,
        subject = subject,
        email = "owner@example.com",
    )

    private fun decodedQuery(uri: URI): Map<String, String> = uri.rawQuery.split('&').associate { pair ->
        val (name, value) = pair.split('=', limit = 2)
        URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
