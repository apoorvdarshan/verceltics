package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

/** The HTTP verbs the Cloudflare v4 REST API uses. Every verb except GET changes provider state. */
enum class CloudflareHttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    ;

    val isMutation: Boolean get() = this != GET
}

/**
 * Proof that the app showed a confirmation naming exactly [resourceId] before a mutation.
 *
 * Mirrors iOS `CloudflareMutationConfirmation`: construct one only from a confirmation dialog's
 * confirm action. Every mutating API call checks it against the resource it changes and refuses to
 * send anything when they differ.
 */
class CloudflareMutationConfirmation(val resourceId: String) {
    override fun equals(other: Any?): Boolean = other is CloudflareMutationConfirmation && other.resourceId == resourceId

    override fun hashCode(): Int = resourceId.hashCode()

    override fun toString(): String = "CloudflareMutationConfirmation(resourceId=$resourceId)"
}

/** Throws [CloudflareOperationException] unless [confirmation] names exactly [resourceId]. */
fun requireCloudflareConfirmation(confirmation: CloudflareMutationConfirmation?, resourceId: String) {
    if (confirmation == null || confirmation.resourceId != resourceId) {
        throw CloudflareOperationException.confirmationRequired(resourceId)
    }
}

/** How a request authenticates. Secrets are only materialized by the transport while sending. */
sealed interface CloudflareRequestAuth {
    /** The connected account's scoped API token. */
    data object AccountToken : CloudflareRequestAuth

    /** Pages asset endpoints use a short-lived project upload JWT instead of the account token. */
    class PagesUploadToken(val jwt: SecretValue) : CloudflareRequestAuth {
        override fun toString(): String = "PagesUploadToken(<redacted>)"
    }
}

/**
 * One request to `https://api.cloudflare.com/client/v4`. The origin is fixed: callers only provide
 * raw path segments, which are percent-encoded here, so no input can change the host, add
 * credentials, or traverse out of `/client/v4`.
 */
class CloudflareRestRequest(
    val method: CloudflareHttpMethod,
    /** Raw, unencoded segments after `/client/v4`, e.g. `["zones", zoneId, "dns_records"]`. */
    val pathSegments: List<String>,
    val query: List<Pair<String, String>> = emptyList(),
    body: ByteArray? = null,
    val contentType: String? = if (body == null) null else JSON_CONTENT_TYPE,
    val accept: String = "application/json",
    /** Non-credential headers such as R2's `cf-r2-jurisdiction`. */
    val headers: Map<String, String> = emptyMap(),
    val auth: CloudflareRequestAuth = CloudflareRequestAuth.AccountToken,
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    private val storedBody: ByteArray? = body?.copyOf()

    init {
        require(pathSegments.isNotEmpty() && pathSegments.size <= MAX_PATH_SEGMENTS) {
            "The Cloudflare API path is invalid."
        }
        pathSegments.forEach { segment ->
            require(segment.isNotEmpty() && segment.length <= MAX_SEGMENT_CHARACTERS) {
                "The Cloudflare API path is invalid."
            }
            require(segment != "." && segment != "..") { "Parent path components are not allowed." }
            require(segment.none { it == '\u0000' }) { "The Cloudflare API path is invalid." }
        }
        require(query.size <= MAX_QUERY_PARAMETERS) { "Too many Cloudflare query parameters." }
        query.forEach { (name, value) ->
            require(name.isNotBlank() && name.length <= MAX_QUERY_CHARACTERS && value.length <= MAX_QUERY_CHARACTERS) {
                "A Cloudflare query parameter is invalid."
            }
        }
        headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "The custom header $name is not allowed." }
            require(name.lowercase(Locale.ROOT) !in PROTECTED_HEADERS) { "The custom header $name is not allowed." }
            require(value.length <= MAX_HEADER_CHARACTERS && value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "The custom header $name is not allowed."
            }
        }
        require(method != CloudflareHttpMethod.GET || storedBody == null) { "GET requests cannot carry a body." }
        require(storedBody == null || storedBody.size <= MAXIMUM_REQUEST_BODY_BYTES) {
            "The request body is larger than the app can safely send."
        }
        require(contentType == null || contentType.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "The Content-Type header is invalid."
        }
        require(accept.none { it == '\r' || it == '\n' || it == '\u0000' }) { "The Accept header is invalid." }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) { "Invalid Cloudflare response limit." }
        require(readTimeoutMillis in 1..MAX_READ_TIMEOUT_MILLIS) { "Invalid Cloudflare timeout." }
    }

    /** The path relative to `/client/v4`, e.g. `/zones/abc/dns_records`; used for events and copy. */
    val apiPath: String = pathSegments.joinToString("/", prefix = "/") { percentEncodeSegment(it) }

    val encodedPath: String = API_PREFIX + apiPath

    val encodedQuery: String?
        get() = query.takeIf { it.isNotEmpty() }?.joinToString("&") { (name, value) ->
            "${percentEncodeSegment(name)}=${percentEncodeSegment(value)}"
        }

    val hasBody: Boolean get() = storedBody != null

    fun bodyCopy(): ByteArray? = storedBody?.copyOf()

    fun bodyText(): String? = storedBody?.let { String(it, StandardCharsets.UTF_8) }

    fun uri(): URI {
        val uri = URI(ORIGIN + encodedPath + (encodedQuery?.let { "?$it" } ?: ""))
        check(isPinnedCloudflareApiUri(uri)) { "Cloudflare request escaped the official API origin." }
        return uri
    }

    override fun toString(): String =
        "CloudflareRestRequest(method=$method, path=$apiPath, query=${if (query.isEmpty()) "none" else "<redacted>"}, " +
            "headerNames=${headers.keys}, auth=$auth, body=${if (storedBody == null) "none" else "<redacted>"})"

    companion object {
        const val HOST: String = "api.cloudflare.com"
        const val ORIGIN: String = "https://api.cloudflare.com"
        const val API_PREFIX: String = "/client/v4"
        const val JSON_CONTENT_TYPE: String = "application/json"

        /** iOS caps Cloudflare responses at 32 MB. */
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Int = 32 * 1_024 * 1_024
        const val HARD_MAXIMUM_RESPONSE_BYTES: Int = 32 * 1_024 * 1_024
        const val MAXIMUM_REQUEST_BODY_BYTES: Int = 100 * 1_024 * 1_024
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 60_000
        private const val MAX_READ_TIMEOUT_MILLIS = 300_000
        private const val MAX_PATH_SEGMENTS = 32
        private const val MAX_SEGMENT_CHARACTERS = 2_048
        private const val MAX_QUERY_PARAMETERS = 64
        private const val MAX_QUERY_CHARACTERS = 8_192
        private const val MAX_HEADER_CHARACTERS = 4_096
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val PROTECTED_HEADERS = setOf(
            "authorization",
            "host",
            "content-length",
            "content-type",
            "accept",
            "accept-encoding",
            "x-auth-email",
            "x-auth-key",
            "cookie",
        )

        /** Builds a JSON request from a [ProviderJsonValue] body. */
        fun json(
            method: CloudflareHttpMethod,
            pathSegments: List<String>,
            body: ProviderJsonValue,
            query: List<Pair<String, String>> = emptyList(),
            headers: Map<String, String> = emptyMap(),
        ): CloudflareRestRequest = CloudflareRestRequest(
            method = method,
            pathSegments = pathSegments,
            query = query,
            body = ProviderJsonWriter.writeBytes(body),
            contentType = JSON_CONTENT_TYPE,
            headers = headers,
        )

        /** RFC 3986 unreserved characters stay literal; everything else is UTF-8 percent-encoded. */
        fun percentEncodeSegment(value: String): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            val output = StringBuilder(bytes.size)
            bytes.forEach { byte ->
                val code = byte.toInt() and 0xFF
                val character = code.toChar()
                if (code < 0x80 && (character.isLetterOrDigit() || character in "-._~")) {
                    output.append(character)
                } else {
                    output.append('%')
                    output.append(HEX[code shr 4])
                    output.append(HEX[code and 0x0F])
                }
            }
            return output.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        /** The only origin Cloudflare operations may contact. */
        fun isPinnedCloudflareApiUri(uri: URI): Boolean =
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(HOST, ignoreCase = true) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                (uri.rawPath ?: "").startsWith("$API_PREFIX/")
    }
}

/** A complete Cloudflare response. Bodies are copied defensively. */
class CloudflareRestResponse(
    val statusCode: Int,
    headers: Map<String, List<String>>,
    body: ByteArray,
) {
    private val storedBody = body.copyOf()
    val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase(Locale.ROOT) }

    val isSuccessful: Boolean get() = statusCode in 200..299

    val size: Int get() = storedBody.size

    fun bodyBytes(): ByteArray = storedBody.copyOf()

    val text: String get() = String(storedBody, StandardCharsets.UTF_8)

    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()

    override fun toString(): String = "CloudflareRestResponse(statusCode=$statusCode, bodyBytes=${storedBody.size})"
}

/** Emitted after every successful mutation so open screens can reconcile (iOS `cloudflareDataDidChange`). */
data class CloudflareMutationEvent(
    val method: CloudflareHttpMethod,
    val apiPath: String,
)
