package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderEndpointPolicy
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * One read-only registrar request. Credentials live only in [secretHeaders] and
 * [secretQueryParameters] (some registrars, such as Namecheap, Dynadot and NameSilo, authenticate
 * through query parameters by design). The request is deliberately non-printable.
 */
internal class RegistrarHttpRequest(
    val origin: String,
    val path: String,
    val queryParameters: List<Pair<String, String>> = emptyList(),
    val secretQueryParameters: List<Pair<String, SecretValue>> = emptyList(),
    val secretHeaders: List<Pair<String, SecretValue>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val accept: String = "application/json",
    val maximumResponseBytes: Int? = null,
) {
    override fun toString(): String =
        "RegistrarHttpRequest(origin=$origin, path=$path, queryNames=${queryParameters.map { it.first }}, " +
            "secretQueryNames=${secretQueryParameters.map { it.first }}, " +
            "secretHeaderNames=${secretHeaders.map { it.first }}, values=<redacted>)"
}

/**
 * One Complete API explorer request (iOS `RegistrarAPI.rawRequest`). [encodedPath] already holds
 * the registrar's documented path prefix; [encodedQuery] is the query exactly as typed and the
 * authentication parameters are appended after it. Deliberately non-printable.
 */
internal class RegistrarRawHttpRequest(
    val origin: String,
    val method: String,
    val encodedPath: String,
    val encodedQuery: String?,
    val queryParameters: List<Pair<String, String>> = emptyList(),
    val secretQueryParameters: List<Pair<String, SecretValue>> = emptyList(),
    val secretHeaders: List<Pair<String, SecretValue>> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val accept: String = "application/json",
    body: ByteArray? = null,
    val contentType: String? = null,
) {
    private val storedBody = body?.copyOf()

    fun bodyCopy(): ByteArray? = storedBody?.copyOf()

    override fun toString(): String =
        "RegistrarRawHttpRequest(origin=$origin, method=$method, path=<redacted>, " +
            "queryNames=${queryParameters.map { it.first }}, secretQueryNames=${secretQueryParameters.map { it.first }}, " +
            "secretHeaderNames=${secretHeaders.map { it.first }}, body=${if (storedBody == null) "none" else "<redacted>"})"
}

internal interface RegistrarHttpTransport {
    fun newGetCall(request: RegistrarHttpRequest): CancelableCall<HttpResponse>

    /** Complete API requests; transports without explorer support refuse them. */
    fun newRawCall(request: RegistrarRawHttpRequest): CancelableCall<HttpResponse> =
        throw UnsupportedOperationException("This registrar transport cannot send Complete API requests.")
}

/**
 * HTTPS GET transport restricted to the eight documented registrar origins plus the credential-free
 * public IPv4 service. Redirects must stay on the same origin, responses are size-bounded, and
 * calls cancel by disconnecting the active connection.
 */
internal class SecureRegistrarHttpTransport(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
) : RegistrarHttpTransport {
    private val policies: Map<String, ProviderEndpointPolicy> =
        ALLOWED_ORIGINS.associateWith(::ProviderEndpointPolicy)

    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid connect timeout." }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid read timeout." }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) {
            "Invalid maximum response size."
        }
        require(maximumRedirects in 0..HARD_MAXIMUM_REDIRECTS) { "Invalid redirect limit." }
    }

    override fun newGetCall(request: RegistrarHttpRequest): CancelableCall<HttpResponse> {
        val policy = policyFor(request)
        val uri = prepareUri(request)
        val headers = validatedHeaders(request)
        val limit = request.maximumResponseBytes?.also {
            require(it in 1..maximumResponseBytes) { "Invalid response limit." }
        } ?: maximumResponseBytes
        return BoundedRegistrarHttpCall(
            initialUri = uri,
            endpointPolicy = policy,
            accept = request.accept,
            headers = headers,
            secretHeaders = request.secretHeaders.toList(),
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = limit,
            maximumRedirects = maximumRedirects,
        )
    }

    override fun newRawCall(request: RegistrarRawHttpRequest): CancelableCall<HttpResponse> {
        val policy = requireNotNull(policies[request.origin]) { "The registrar origin is not allow-listed." }
        require(request.origin != IPIFY_ORIGIN) { "The registrar origin is not allow-listed." }
        require(request.method in RAW_METHODS) { "Unsupported registrar HTTP method." }
        val uri = prepareRawUri(request)
        require(request.accept.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid Accept header." }
        request.headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name." }
            require(name.lowercase(Locale.ROOT) !in RAW_PROTECTED_HEADERS) {
                "The $name header is controlled by the registrar transport."
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP header value." }
        }
        request.secretHeaders.forEach { (name, _) ->
            require(name.lowercase(Locale.ROOT) in SECRET_HEADER_NAMES) { "Unsupported registrar credential header." }
        }
        require(request.contentType == null || request.contentType.none { it == '\r' || it == '\n' }) { "Invalid content type." }
        val body = request.bodyCopy()
        require(body == null || request.method != "GET" && request.method != "HEAD") {
            "${request.method} requests cannot carry a body."
        }
        return BoundedRegistrarHttpCall(
            initialUri = uri,
            endpointPolicy = policy,
            accept = request.accept,
            headers = request.headers.toMap(),
            secretHeaders = request.secretHeaders.toList(),
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
            maximumRedirects = if (request.method == "GET") maximumRedirects else 0,
            method = request.method,
            body = body,
            contentType = request.contentType,
            returnUnfollowedRedirects = true,
        )
    }

    /**
     * The explorer URI: fixed registrar origin + encoded path + typed query + authentication
     * parameters. Secret query values are materialized only into this URI.
     */
    internal fun prepareRawUri(request: RegistrarRawHttpRequest): URI {
        val policy = requireNotNull(policies[request.origin]) { "The registrar origin is not allow-listed." }
        val path = request.encodedPath
        require(path.startsWith("/") && !path.startsWith("//") && path.length <= MAX_RAW_TARGET_CHARACTERS) {
            "Enter a registrar-relative path beginning with /."
        }
        require(path.all { it in RAW_URL_CHARACTERS && it != '?' }) { "The API path is invalid." }
        require(path.split('/').none { segment -> segment.replace("%2E", ".", ignoreCase = true).let { it == "." || it == ".." } }) {
            "Provider path traversal is not allowed."
        }
        val typedQuery = request.encodedQuery?.takeIf(String::isNotEmpty)
        require(typedQuery == null || (typedQuery.length <= MAX_RAW_TARGET_CHARACTERS && typedQuery.all { it in RAW_URL_CHARACTERS })) {
            "The API path is invalid."
        }
        val appended = (request.queryParameters + request.secretQueryParameters.map { (name, secret) -> name to secret.use { it } })
            .joinToString("&") { (name, value) -> "${strictEncode(name)}=${strictEncode(value)}" }
            .takeIf(String::isNotEmpty)
        val query = listOfNotNull(typedQuery, appended).joinToString("&").takeIf(String::isNotEmpty)
        val origin = policy.baseUri
        val uri = URI(origin.scheme + "://" + origin.rawAuthority + path + (query?.let { "?$it" } ?: ""))
        check(policy.isSameOrigin(uri) && uri.rawPath == path) { "Registrar request escaped its provider origin." }
        return uri
    }

    /** Resolves the request URI; secret query values are materialized only into this URI. */
    internal fun prepareUri(request: RegistrarHttpRequest): URI {
        val policy = policyFor(request)
        val secretQuery = request.secretQueryParameters.map { (name, secret) ->
            name to secret.use { it }
        }
        return policy.resolve(request.path, request.queryParameters + secretQuery)
    }

    private fun policyFor(request: RegistrarHttpRequest): ProviderEndpointPolicy =
        requireNotNull(policies[request.origin]) { "The registrar origin is not allow-listed." }

    private fun validatedHeaders(request: RegistrarHttpRequest): Map<String, String> {
        require(request.accept.none { it == '\r' || it == '\n' }) { "Invalid Accept header." }
        request.headers.forEach { (name, value) ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name." }
            require(name.lowercase(Locale.ROOT) !in PROTECTED_HEADERS) {
                "The $name header is controlled by the registrar transport."
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Invalid HTTP header value." }
        }
        request.secretHeaders.forEach { (name, _) ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name." }
            require(name.lowercase(Locale.ROOT) in SECRET_HEADER_NAMES) {
                "Unsupported registrar credential header."
            }
        }
        return request.headers.toMap()
    }

    companion object {
        const val IPIFY_ORIGIN: String = "https://api.ipify.org/"
        val ALLOWED_ORIGINS: Set<String> =
            RegistrarProvider.entries.map(RegistrarProvider::origin).toSet() + IPIFY_ORIGIN

        internal const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val DEFAULT_MAXIMUM_REDIRECTS = 2
        private const val MAX_TIMEOUT_MILLIS = 120_000
        private const val HARD_MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val HARD_MAXIMUM_REDIRECTS = 5
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val PROTECTED_HEADERS = setOf(
            "authorization",
            "host",
            "content-length",
            "content-type",
            "accept",
            "accept-encoding",
            "user-agent",
            "x-api-key",
            "x-secret-api-key",
            "x-api-secret",
        )
        internal val SECRET_HEADER_NAMES = setOf(
            "authorization",
            "x-api-key",
            "x-secret-api-key",
            "x-api-secret",
        )
        private val RAW_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

        /** Explorer requests may set Accept (as on iOS) but never credentials or framing headers. */
        internal val RAW_PROTECTED_HEADERS = setOf(
            "authorization",
            "host",
            "content-length",
            "content-type",
            "accept",
            "accept-encoding",
            "x-api-key",
            "x-secret-api-key",
            "x-api-secret",
            "cookie",
            "proxy-authorization",
            "transfer-encoding",
            "connection",
        )
        private const val MAX_RAW_TARGET_CHARACTERS = 8_192
        private const val RAW_URL_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?%"
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        private fun strictEncode(value: String): String {
            val output = StringBuilder()
            value.toByteArray(Charsets.UTF_8).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val character = unsigned.toChar()
                if (unsigned < 0x80 && character in UNRESERVED) {
                    output.append(character)
                } else {
                    output.append('%').append("%02X".format(unsigned))
                }
            }
            return output.toString()
        }
    }
}

private class BoundedRegistrarHttpCall(
    private val initialUri: URI,
    private val endpointPolicy: ProviderEndpointPolicy,
    private val accept: String,
    private val headers: Map<String, String>,
    private val secretHeaders: List<Pair<String, SecretValue>>,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
    private val method: String = "GET",
    private val body: ByteArray? = null,
    private val contentType: String? = null,
    /** Complete API calls hand redirects they cannot follow back as the response. */
    private val returnUnfollowedRedirects: Boolean = false,
) : CancelableCall<HttpResponse> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "An HTTP call can only be executed once." }
        var uri = initialUri
        var redirectCount = 0
        var pendingBody = body
        try {
            while (true) {
                throwIfCancelled()
                val connection = openConnection(uri)
                activeConnection.set(connection)
                try {
                    throwIfCancelled()
                    pendingBody?.let { payload ->
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                        connection.setFixedLengthStreamingMode(payload.size)
                        connection.outputStream.use { it.write(payload) }
                    }
                    val statusCode = connection.responseCode
                    throwIfCancelled()
                    val redirect = if (statusCode in REDIRECT_STATUS_CODES) redirectTarget(connection, uri, redirectCount) else null
                    if (redirect != null) {
                        uri = redirect
                        redirectCount += 1
                        pendingBody = null
                        continue
                    }
                    return readResponse(connection, statusCode)
                } catch (error: IOException) {
                    if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
                    throw error
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
        } finally {
            body?.fill(0)
        }
    }

    private fun redirectTarget(connection: HttpsURLConnection, uri: URI, redirectCount: Int): URI? {
        if (redirectCount >= maximumRedirects) {
            if (returnUnfollowedRedirects) return null
            throw UnsafeRedirectException("The registrar returned too many redirects.")
        }
        val location = connection.getHeaderField("Location")
            ?: if (returnUnfollowedRedirects) return null else throw UnsafeRedirectException("The registrar redirect omitted its location.")
        return try {
            endpointPolicy.resolveRedirect(uri, location)
        } catch (error: Exception) {
            if (returnUnfollowedRedirects) return null
            throw UnsafeRedirectException("The registrar returned an unsafe redirect.", error)
        }
    }

    private fun readResponse(connection: HttpsURLConnection, statusCode: Int): HttpResponse {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maximumResponseBytes) {
            throw ResponseTooLargeException(maximumResponseBytes)
        }
        val stream = if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.errorStream
        } else {
            connection.inputStream
        }
        val responseBody = stream?.use(::readBounded) ?: ByteArray(0)
        return try {
            HttpResponse(
                statusCode = statusCode,
                body = responseBody,
                headers = safeResponseHeaders(connection),
            )
        } finally {
            responseBody.fill(0)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun openConnection(uri: URI): HttpsURLConnection {
        check(endpointPolicy.isSameOrigin(uri)) { "Refusing to open a URL outside the registrar origin." }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Registrar URL did not create a secure HTTPS connection.")
        // A followed redirect is always re-requested with GET and no body.
        connection.requestMethod = if (uri === initialUri) method else "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        headers.forEach(connection::setRequestProperty)
        secretHeaders.forEach { (name, secret) ->
            secret.use { value -> connection.setRequestProperty(name, value) }
        }
        return connection
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingRegistrarByteArrayOutputStream(minOf(maximumResponseBytes, 32 * 1024)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumResponseBytes) {
                        throw ResponseTooLargeException(maximumResponseBytes)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeResponseHeaders(connection: HttpsURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            val headerName = name ?: return@mapNotNull null
            if (headerName.lowercase(Locale.ROOT) in SENSITIVE_RESPONSE_HEADERS) {
                return@mapNotNull null
            }
            headerName to values.toList()
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
    }

    override fun toString(): String = "BoundedRegistrarHttpCall(<redacted>)"

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_RESPONSE_HEADERS = setOf(
            "authorization",
            "proxy-authenticate",
            "set-cookie",
            "set-cookie2",
        )
    }
}

private class WipingRegistrarByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
