package com.apoorvdarshan.verceltics.data.googleoauth

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue

/** Google scope constants and scope-set helpers shared by every Google-backed integration. */
object GoogleOAuthScopes {
    const val OPENID: String = "openid"
    const val EMAIL: String = "email"
    const val PROFILE: String = "profile"
    const val USERINFO_EMAIL: String = "https://www.googleapis.com/auth/userinfo.email"
    const val USERINFO_PROFILE: String = "https://www.googleapis.com/auth/userinfo.profile"
    const val ANALYTICS_READONLY: String = "https://www.googleapis.com/auth/analytics.readonly"
    const val FIREBASE_HOSTING: String = "https://www.googleapis.com/auth/firebase.hosting"

    /** The read-only GA4 scope set requested by iOS (`SiteIntegrationsAPI.googleAnalyticsOAuth`). */
    val GOOGLE_ANALYTICS_READ_ONLY: Set<String> = linkedSetOf(OPENID, EMAIL, ANALYTICS_READONLY)

    /** The Firebase Hosting scope set requested by iOS (`GoogleOAuthService.firebaseHostingScopes`). */
    val FIREBASE_HOSTING_SCOPES: Set<String> = linkedSetOf(OPENID, EMAIL, FIREBASE_HOSTING)

    /** Google reports `email`/`profile` grants with their userinfo URLs; compare canonically. */
    fun canonical(scope: String): String = when (val trimmed = scope.trim()) {
        USERINFO_EMAIL -> EMAIL
        USERINFO_PROFILE -> PROFILE
        else -> trimmed
    }

    fun covers(granted: Collection<String>, requested: Collection<String>): Boolean {
        val canonicalGrants = granted.mapTo(HashSet(), ::canonical)
        return requested.all { canonical(it) in canonicalGrants }
    }

    fun validate(scopes: Set<String>) {
        require(scopes.isNotEmpty() && scopes.size <= MAX_SCOPES) { "Request at least one Google OAuth scope." }
        require(scopes.all { it.isNotBlank() && it.length <= MAX_SCOPE_CHARACTERS && it.none(Char::isWhitespace) }) {
            "Invalid Google OAuth scope."
        }
    }

    internal const val MAX_SCOPES = 64
    internal const val MAX_SCOPE_CHARACTERS = 512
}

/**
 * The installed-app OAuth client compiled into this build. The redirect uses the reverse client
 * id scheme with the dedicated `/oauth2redirect` path so it never overlaps the Search Console
 * callback (`/oauthredirect`).
 */
data class GoogleOAuthClientConfiguration(
    val clientId: String,
    val redirectScheme: String,
) {
    init {
        require(CLIENT_ID.matches(clientId) && clientId.endsWith(GOOGLE_CLIENT_ID_SUFFIX)) {
            "Invalid Google OAuth client id."
        }
        require(URI_SCHEME.matches(redirectScheme)) { "Invalid Google OAuth redirect scheme." }
        require(redirectScheme == expectedRedirectScheme(clientId)) {
            "The Google OAuth redirect scheme must match the client id."
        }
    }

    val redirectUri: String get() = "$redirectScheme:$REDIRECT_PATH"

    companion object {
        const val REDIRECT_PATH: String = "/oauth2redirect"
        const val AUTHORIZATION_ENDPOINT: String = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
        const val USERINFO_ENDPOINT: String = "https://openidconnect.googleapis.com/v1/userinfo"
        private val CLIENT_ID = Regex("[A-Za-z0-9._:-]{1,2048}")
        private val URI_SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]{1,255}")
        private const val GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"

        private fun expectedRedirectScheme(clientId: String): String =
            "com.googleusercontent.apps.${clientId.removeSuffix(GOOGLE_CLIENT_ID_SUFFIX)}"

        /** Null when the build has no `VERCELTICS_GOOGLE_OAUTH_CLIENT_ID`. */
        fun current(): GoogleOAuthClientConfiguration? {
            val clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.trim()
            val redirectScheme = BuildConfig.GOOGLE_OAUTH_REDIRECT_SCHEME.trim()
            if (clientId.isEmpty() || redirectScheme.isEmpty() ||
                redirectScheme == "verceltics-oauth-unconfigured"
            ) {
                return null
            }
            return runCatching { GoogleOAuthClientConfiguration(clientId, redirectScheme) }.getOrNull()
        }
    }
}

data class GoogleAccountIdentity(
    val subject: String,
    val email: String?,
)

/**
 * A Google installed-app credential. Tokens are [SecretValue]s and never appear in `toString()`.
 */
class GoogleOAuthCredential(
    val accessToken: SecretValue,
    val refreshToken: SecretValue?,
    val tokenType: String,
    val scopes: List<String>,
    val expiresAtMillis: Long,
    val subject: String?,
    val email: String?,
) {
    init {
        require(tokenType.equals("Bearer", ignoreCase = true)) { "Unsupported Google token type." }
        require(scopes.size <= GoogleOAuthScopes.MAX_SCOPES && scopes.distinct().size == scopes.size) {
            "Invalid Google OAuth scopes."
        }
        require(scopes.all { it.isNotBlank() && it.length <= GoogleOAuthScopes.MAX_SCOPE_CHARACTERS }) {
            "Invalid Google OAuth scope."
        }
        require(expiresAtMillis >= 0L) { "Invalid Google credential expiration." }
        require(subject == null || subject.isNotBlank() && subject.length <= MAX_IDENTITY_CHARACTERS) {
            "Invalid Google subject."
        }
        require(email == null || email.isNotBlank() && email.length <= MAX_IDENTITY_CHARACTERS) {
            "Invalid Google email."
        }
    }

    /** Matches iOS: refresh when fewer than 90 seconds remain. */
    fun needsRefresh(nowMillis: Long, leewayMillis: Long = REFRESH_LEEWAY_MILLIS): Boolean =
        expiresAtMillis <= nowMillis + leewayMillis

    fun covers(requestedScopes: Collection<String>): Boolean = GoogleOAuthScopes.covers(scopes, requestedScopes)

    val identity: GoogleAccountIdentity? get() = subject?.let { GoogleAccountIdentity(it, email) }

    fun withIdentity(subject: String, email: String?): GoogleOAuthCredential = GoogleOAuthCredential(
        accessToken, refreshToken, tokenType, scopes, expiresAtMillis, subject, email,
    )

    override fun toString(): String =
        "GoogleOAuthCredential(accessToken=<redacted>, refreshToken=" +
            "${if (refreshToken == null) "null" else "<redacted>"}, tokenType=$tokenType, " +
            "scopeCount=${scopes.size}, expiresAtMillis=$expiresAtMillis, subject=$subject, email=$email)"

    companion object {
        const val REFRESH_LEEWAY_MILLIS: Long = 90_000L
        internal const val MAX_IDENTITY_CHARACTERS = 512
    }
}

/** User-safe OAuth failure. [isRevoked] marks an `invalid_grant` refresh (reconnect required). */
class GoogleOAuthException(
    message: String,
    val isRevoked: Boolean = false,
    val isCancelled: Boolean = false,
) : Exception(message) {
    override fun toString(): String = "GoogleOAuthException(message=$message, revoked=$isRevoked)"
}
