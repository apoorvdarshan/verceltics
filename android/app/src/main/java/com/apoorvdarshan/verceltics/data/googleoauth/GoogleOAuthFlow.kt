package com.apoorvdarshan.verceltics.data.googleoauth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsRequest
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.SecureProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import com.apoorvdarshan.verceltics.data.network.map
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One-use PKCE (S256) authorization transaction for a configurable scope set. The verifier and
 * state stay private and never enter Compose state, saved state, intents, or logs.
 */
class GooglePkceTransaction private constructor(
    val authorizationUri: URI,
    val requestedScopes: Set<String>,
    private val expectedState: SecretValue,
    internal val codeVerifier: SecretValue,
    private val redirectUri: URI,
) {
    private val consumed = AtomicBoolean(false)

    fun authorizationCode(callbackUri: URI): SecretValue {
        if (!consumed.compareAndSet(false, true)) {
            throw GoogleOAuthException("This Google authorization callback was already handled.")
        }
        if (!callbackUri.matchesGoogleOAuthRedirect(redirectUri)) {
            throw GoogleOAuthException("Google returned an invalid authorization callback.")
        }
        val values = decodeQuery(callbackUri.rawQuery)
        values["error"]?.takeIf(String::isNotBlank)?.let { providerError ->
            val detail = values["error_description"]?.takeIf(String::isNotBlank) ?: providerError
            throw GoogleOAuthException(detail.take(300), isCancelled = providerError == "access_denied")
        }
        val returnedState = values["state"]?.takeIf(String::isNotBlank)
            ?: throw GoogleOAuthException("Google authorization could not be verified. Please try again.")
        val matches = expectedState.use { expected ->
            val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
            val returnedBytes = returnedState.toByteArray(StandardCharsets.UTF_8)
            try {
                MessageDigest.isEqual(expectedBytes, returnedBytes)
            } finally {
                expectedBytes.fill(0)
                returnedBytes.fill(0)
            }
        }
        if (!matches) throw GoogleOAuthException("Google authorization could not be verified. Please try again.")
        return values["code"]
            ?.takeIf { it.isNotBlank() && it.length <= MAX_AUTHORIZATION_CODE_CHARACTERS }
            ?.let(SecretValue::of)
            ?: throw GoogleOAuthException("Google returned an invalid authorization response.")
    }

    override fun toString(): String = "GooglePkceTransaction(scopes=$requestedScopes, secrets=<redacted>)"

    companion object {
        private const val MAX_AUTHORIZATION_CODE_CHARACTERS = 16_384

        fun create(
            configuration: GoogleOAuthClientConfiguration,
            scopes: Set<String>,
            randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
        ): GooglePkceTransaction {
            GoogleOAuthScopes.validate(scopes)
            val verifier = randomBytes(64).toBase64Url()
            val state = randomBytes(32).toBase64Url()
            val challengeBytes = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            val challenge = challengeBytes.toBase64Url()
            val query = formEncode(
                listOf(
                    "client_id" to configuration.clientId,
                    "redirect_uri" to configuration.redirectUri,
                    "response_type" to "code",
                    "scope" to scopes.joinToString(" "),
                    "access_type" to "offline",
                    "include_granted_scopes" to "true",
                    "prompt" to "consent",
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                    "state" to state,
                ),
            )
            return GooglePkceTransaction(
                authorizationUri = URI("${GoogleOAuthClientConfiguration.AUTHORIZATION_ENDPOINT}?$query"),
                requestedScopes = LinkedHashSet(scopes),
                expectedState = SecretValue.of(state),
                codeVerifier = SecretValue.of(verifier),
                redirectUri = URI(configuration.redirectUri),
            )
        }

        private fun decodeQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            rawQuery.split('&').forEach { pair ->
                val separator = pair.indexOf('=')
                val rawName = if (separator < 0) pair else pair.substring(0, separator)
                val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
                val name = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                if (name.isBlank() || result.put(name, value) != null) {
                    throw GoogleOAuthException("Google returned an invalid authorization response.")
                }
            }
            return result
        }
    }
}

/** Exact redirect check: scheme, opaque path, and no authority, user info, port or fragment. */
fun URI.matchesGoogleOAuthRedirect(expected: URI): Boolean =
    scheme.equals(expected.scheme, ignoreCase = true) &&
        path == expected.path && host == expected.host &&
        rawUserInfo == null && port == -1 && rawFragment == null

/** Opens the system browser. Abstracted so the PKCE flow can be tested without Android. */
fun interface GoogleOAuthBrowser {
    fun open(authorizationUri: URI)
}

class AndroidGoogleOAuthBrowser(private val context: Context) : GoogleOAuthBrowser {
    override fun open(authorizationUri: URI) {
        context.applicationContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUri.toASCIIString())).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/**
 * Process-local handoff from `GoogleOAuthCallbackActivity` to the single active PKCE coroutine.
 * Only one Google authorization can be pending at a time; cancellation frees the slot.
 */
object GoogleOAuthCallbackBroker {
    private val lock = Any()

    private class PendingCallback(
        val continuation: CancellableContinuation<URI>,
        val redirectUri: URI,
    )

    private var pending: PendingCallback? = null

    val hasPendingAuthorization: Boolean get() = synchronized(lock) { pending != null }

    suspend fun awaitCallback(browser: GoogleOAuthBrowser, authorizationUri: URI, redirectUri: URI): URI =
        suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (pending != null) {
                    continuation.resumeWithException(
                        GoogleOAuthException("A Google authorization request is already open."),
                    )
                    return@suspendCancellableCoroutine
                }
                pending = PendingCallback(continuation, redirectUri)
            }
            continuation.invokeOnCancellation {
                synchronized(lock) {
                    if (pending?.continuation === continuation) pending = null
                }
            }
            try {
                browser.open(authorizationUri)
            } catch (_: Exception) {
                val claimed = synchronized(lock) {
                    (pending?.continuation === continuation).also { if (it) pending = null }
                }
                if (claimed && continuation.isActive) {
                    continuation.resumeWithException(
                        GoogleOAuthException("No browser is available for Google authorization."),
                    )
                }
            }
        }

    /** Returns true when the callback matched and resumed the pending authorization. */
    fun deliver(callbackUri: URI): Boolean {
        val claimed = synchronized(lock) {
            val candidate = pending ?: return@synchronized null
            if (!callbackUri.matchesGoogleOAuthRedirect(candidate.redirectUri)) return@synchronized null
            pending = null
            candidate
        } ?: return false
        if (claimed.continuation.isActive) claimed.continuation.resume(callbackUri)
        return true
    }
}

internal class GoogleTokenResponse(
    val accessToken: SecretValue,
    val refreshToken: SecretValue?,
    val tokenType: String?,
    val scopes: List<String>?,
    val expiresInSeconds: Long,
) {
    override fun toString(): String = "GoogleTokenResponse(<redacted>, expiresInSeconds=$expiresInSeconds)"
}

/** Token exchange, refresh, and OpenID identity calls against Google's fixed endpoints. */
class GoogleOAuthApi(
    private val transport: ProviderHttpsTransport = SecureProviderHttpsTransport(maximumRedirects = 0),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun newExchangeAuthorizationCodeCall(
        authorizationCode: SecretValue,
        codeVerifier: SecretValue,
        clientId: String,
        redirectUri: String,
        requestedScopes: Set<String>,
    ): CancelableCall<GoogleOAuthCredential> {
        GoogleOAuthScopes.validate(requestedScopes)
        val body = authorizationCode.use { code ->
            codeVerifier.use { verifier ->
                formEncode(
                    listOf(
                        "client_id" to clientId,
                        "code" to code,
                        "code_verifier" to verifier,
                        "grant_type" to "authorization_code",
                        "redirect_uri" to redirectUri,
                    ),
                ).toByteArray(StandardCharsets.UTF_8)
            }
        }
        return tokenCall(body, "exchange the Google authorization code").map { token ->
            GoogleOAuthCredential(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                tokenType = token.tokenType ?: "Bearer",
                scopes = token.scopes ?: requestedScopes.toList(),
                expiresAtMillis = safeExpiry(nowMillis(), token.expiresInSeconds),
                subject = null,
                email = null,
            )
        }
    }

    fun newRefreshCall(credential: GoogleOAuthCredential, clientId: String): CancelableCall<GoogleOAuthCredential> {
        val refreshToken = credential.refreshToken
            ?: throw GoogleOAuthException(
                "Google did not provide a refresh token. Reconnect the account to continue.",
                isRevoked = true,
            )
        val body = refreshToken.use { token ->
            formEncode(
                listOf(
                    "client_id" to clientId,
                    "refresh_token" to token,
                    "grant_type" to "refresh_token",
                ),
            ).toByteArray(StandardCharsets.UTF_8)
        }
        return tokenCall(body, "refresh the Google credential").map { token ->
            GoogleOAuthCredential(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: refreshToken,
                tokenType = token.tokenType ?: credential.tokenType,
                scopes = token.scopes ?: credential.scopes,
                expiresAtMillis = safeExpiry(nowMillis(), token.expiresInSeconds),
                subject = credential.subject,
                email = credential.email,
            )
        }
    }

    fun newIdentityCall(accessToken: SecretValue): CancelableCall<GoogleAccountIdentity> =
        transport.newCall(
            ProviderHttpsRequest(
                method = "GET",
                uri = URI(GoogleOAuthClientConfiguration.USERINFO_ENDPOINT),
                bearerToken = accessToken,
            ),
        ).map { response ->
            if (response.statusCode !in 200..299) {
                throw GoogleOAuthException("Google returned an invalid token response.")
            }
            val root = response.json()
            val subject = root["sub"]?.stringValue?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= GoogleOAuthCredential.MAX_IDENTITY_CHARACTERS
            } ?: throw GoogleOAuthException("Google returned an invalid token response.")
            val email = root["email"]?.stringValue?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= GoogleOAuthCredential.MAX_IDENTITY_CHARACTERS
            }
            GoogleAccountIdentity(subject, email)
        }

    private fun tokenCall(body: ByteArray, operation: String): CancelableCall<GoogleTokenResponse> {
        val request = try {
            ProviderHttpsRequest(
                method = "POST",
                uri = URI(GoogleOAuthClientConfiguration.TOKEN_ENDPOINT),
                body = body,
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
            )
        } finally {
            body.fill(0)
        }
        return transport.newCall(request).map { response ->
            if (response.statusCode !in 200..299) throw tokenFailure(response, operation)
            parseTokenResponse(response.json())
        }
    }

    private fun tokenFailure(response: HttpResponse, operation: String): GoogleOAuthException {
        val root = runCatching { response.json() }.getOrNull()
        val code = root?.get("error")?.stringValue
        val description = root?.get("error_description")?.stringValue ?: code
        val message = description?.trim()?.take(300)?.takeIf(String::isNotEmpty)
        val text = if (message == null) {
            "Google could not $operation (HTTP ${response.statusCode})."
        } else {
            "Google could not $operation (HTTP ${response.statusCode}): $message"
        }
        return GoogleOAuthException(text, isRevoked = code == "invalid_grant")
    }

    private fun parseTokenResponse(root: ProviderJsonValue): GoogleTokenResponse {
        val accessToken = root["access_token"]?.stringValue?.takeIf(String::isNotBlank)
            ?: throw GoogleOAuthException("Google returned an invalid token response.")
        val expiresIn = root["expires_in"]?.numberValue?.toLong()
            ?.takeIf { it in 1..MAXIMUM_EXPIRY_SECONDS }
            ?: throw GoogleOAuthException("Google returned an invalid token response.")
        val scopes = root["scope"]?.stringValue
            ?.split(' ')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.takeIf(List<String>::isNotEmpty)
        val tokenType = root["token_type"]?.stringValue?.takeIf(String::isNotBlank)
        if (tokenType != null && !tokenType.equals("Bearer", ignoreCase = true)) {
            throw GoogleOAuthException("Google returned an unsupported token type.")
        }
        return GoogleTokenResponse(
            accessToken = SecretValue.of(accessToken),
            refreshToken = root["refresh_token"]?.stringValue?.takeIf(String::isNotBlank)?.let(SecretValue::of),
            tokenType = tokenType,
            scopes = scopes,
            expiresInSeconds = expiresIn,
        )
    }

    private fun HttpResponse.json(): ProviderJsonValue {
        val bytes = takeBody()
        return try {
            ProviderJsonParser.parse(bytes)
        } catch (_: Exception) {
            throw GoogleOAuthException("Google returned an invalid token response.")
        } finally {
            bytes.fill(0)
        }
    }

    private fun safeExpiry(now: Long, seconds: Long): Long = Math.addExact(now, Math.multiplyExact(seconds, 1_000L))

    private companion object {
        const val MAXIMUM_EXPIRY_SECONDS = 366L * 24 * 60 * 60
    }
}

/** Browser PKCE sign-in and refresh. No credential material crosses the UI boundary. */
interface GoogleOAuthAuthorizer {
    val configuration: GoogleOAuthClientConfiguration?

    suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential

    suspend fun refresh(credential: GoogleOAuthCredential): GoogleOAuthCredential
}

class NativeGoogleOAuthAuthorizer(
    private val browser: GoogleOAuthBrowser,
    override val configuration: GoogleOAuthClientConfiguration? = GoogleOAuthClientConfiguration.current(),
    private val api: GoogleOAuthApi = GoogleOAuthApi(),
    private val executor: Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "verceltics-google-oauth").apply { isDaemon = true }
    },
    private val randomBytes: (Int) -> ByteArray = ::secureRandomBytes,
) : GoogleOAuthAuthorizer {
    override suspend fun authorize(scopes: Set<String>): GoogleOAuthCredential {
        val configured = configuration ?: throw GoogleOAuthException(CONFIGURATION_MISSING_MESSAGE)
        val transaction = GooglePkceTransaction.create(configured, scopes, randomBytes)
        val callback = GoogleOAuthCallbackBroker.awaitCallback(
            browser,
            transaction.authorizationUri,
            URI(configured.redirectUri),
        )
        val code = transaction.authorizationCode(callback)
        val credential = api.newExchangeAuthorizationCodeCall(
            authorizationCode = code,
            codeVerifier = transaction.codeVerifier,
            clientId = configured.clientId,
            redirectUri = configured.redirectUri,
            requestedScopes = scopes,
        ).awaitProviderCall(executor)
        if (credential.refreshToken == null) {
            throw GoogleOAuthException("Google did not provide a refresh token. Reconnect the account to continue.")
        }
        if (!credential.covers(scopes)) {
            throw GoogleOAuthException("Google did not grant every requested read-only permission. Try again and allow access.")
        }
        val identity = api.newIdentityCall(credential.accessToken).awaitProviderCall(executor)
        return credential.withIdentity(identity.subject, identity.email)
    }

    override suspend fun refresh(credential: GoogleOAuthCredential): GoogleOAuthCredential {
        val configured = configuration ?: throw GoogleOAuthException(
            "This saved Google credential needs refresh, but Google sign-in is not configured in this build.",
        )
        return api.newRefreshCall(credential, configured.clientId).awaitProviderCall(executor)
    }

    companion object {
        const val CONFIGURATION_MISSING_MESSAGE: String =
            "Google sign-in is unavailable in this version. Contact support for help connecting."
    }
}

internal fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

private fun ByteArray.toBase64Url(): String = try {
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)
} finally {
    fill(0)
}

private fun formEncode(values: List<Pair<String, String>>): String = values.joinToString("&") {
    URLEncoder.encode(it.first, StandardCharsets.UTF_8.name()) + "=" +
        URLEncoder.encode(it.second, StandardCharsets.UTF_8.name())
}
