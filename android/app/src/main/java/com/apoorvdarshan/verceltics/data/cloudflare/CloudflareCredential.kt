package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.util.Locale

/**
 * How a Cloudflare connection authenticates (iOS `CloudflareAuthenticationMode`). iOS offers the
 * Global API Key first, so it is the first case here too.
 */
enum class CloudflareAuthMode(val storageId: String, val displayName: String) {
    GLOBAL_API_KEY("global-api-key", "Global key"),
    API_TOKEN("api-token", "API token"),
    ;

    companion object {
        fun fromStorageId(value: String): CloudflareAuthMode =
            entries.firstOrNull { it.storageId == value }
                ?: throw IllegalArgumentException("Unsupported Cloudflare authentication mode.")
    }
}

/**
 * The secret a Cloudflare connection signs requests with.
 *
 * Scoped API tokens travel as `Authorization: Bearer`; email + Global API Key connections travel as
 * `X-Auth-Email` and `X-Auth-Key` (iOS `CloudflareAPI.execute`). Neither form is printable: the
 * secret only leaves [SecretValue] while a transport writes it onto a connection.
 */
sealed class CloudflareCredential {
    abstract val authMode: CloudflareAuthMode

    /** Writes this credential's authentication headers through [setHeader]. */
    abstract fun applyHeaders(setHeader: (name: String, value: String) -> Unit)

    class ApiToken(val token: SecretValue) : CloudflareCredential() {
        override val authMode: CloudflareAuthMode get() = CloudflareAuthMode.API_TOKEN

        override fun applyHeaders(setHeader: (name: String, value: String) -> Unit) {
            token.use { setHeader(AUTHORIZATION_HEADER, "Bearer $it") }
        }

        override fun equals(other: Any?): Boolean = other is ApiToken && other.token == token

        override fun hashCode(): Int = 1

        override fun toString(): String = "CloudflareCredential.ApiToken(<redacted>)"
    }

    class GlobalApiKey(email: String, val key: SecretValue) : CloudflareCredential() {
        /** The Cloudflare login email, trimmed and lowercased like iOS stores it. */
        val email: String = normalizeEmail(email)

        init {
            require(validateEmail(this.email) == null) { "Enter the email address used for your Cloudflare account." }
        }

        override val authMode: CloudflareAuthMode get() = CloudflareAuthMode.GLOBAL_API_KEY

        override fun applyHeaders(setHeader: (name: String, value: String) -> Unit) {
            setHeader(EMAIL_HEADER, email)
            key.use { setHeader(KEY_HEADER, it) }
        }

        override fun equals(other: Any?): Boolean = other is GlobalApiKey && other.email == email && other.key == key

        override fun hashCode(): Int = email.hashCode()

        override fun toString(): String = "CloudflareCredential.GlobalApiKey(email=<redacted>, key=<redacted>)"
    }

    companion object {
        const val AUTHORIZATION_HEADER: String = "Authorization"
        const val EMAIL_HEADER: String = "X-Auth-Email"
        const val KEY_HEADER: String = "X-Auth-Key"
        private const val MAXIMUM_EMAIL_CHARACTERS = 320

        /** Every header name a Cloudflare credential may write; custom request headers can never set them. */
        val AUTHENTICATION_HEADERS: Set<String> = setOf("authorization", "x-auth-email", "x-auth-key")

        fun apiToken(token: String): ApiToken = ApiToken(SecretValue.of(token.trim()))

        fun globalApiKey(email: String, key: String): GlobalApiKey = GlobalApiKey(email, SecretValue.of(key.trim()))

        fun normalizeEmail(value: String): String = value.trim().lowercase(Locale.ROOT)

        /**
         * iOS `validateConfiguredCredentials` plus header-injection safety: null when [email] can be
         * sent as `X-Auth-Email`, otherwise the message to show.
         */
        fun validateEmail(email: String): String? {
            val normalized = normalizeEmail(email)
            val local = normalized.substringBefore('@', missingDelimiterValue = "")
            val domain = normalized.substringAfter('@', missingDelimiterValue = "")
            return when {
                normalized.isEmpty() || !normalized.contains('@') || local.isEmpty() || domain.isEmpty() ->
                    "Enter the email address used for your Cloudflare account."
                normalized.length > MAXIMUM_EMAIL_CHARACTERS ||
                    normalized.any { it.isWhitespace() || it.isISOControl() } ->
                    "Enter the email address used for your Cloudflare account."
                else -> null
            }
        }
    }
}
