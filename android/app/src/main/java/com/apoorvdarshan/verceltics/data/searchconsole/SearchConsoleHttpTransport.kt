package com.apoorvdarshan.verceltics.data.searchconsole

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
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal enum class SearchConsoleGoogleOrigin(val baseUrl: String) {
    WEBMASTERS("https://www.googleapis.com/"),
    INSPECTION("https://searchconsole.googleapis.com/"),
    OAUTH_TOKEN("https://oauth2.googleapis.com/"),
    OPENID("https://openidconnect.googleapis.com/"),
}

internal class SearchConsoleHttpRequest(
    val origin: SearchConsoleGoogleOrigin,
    val method: String,
    val pathSegments: List<String>,
    val queryParameters: List<Pair<String, String>> = emptyList(),
    val literalColonInLastSegment: Boolean = false,
    requestBody: ByteArray? = null,
    val contentType: String? = null,
    val bearerToken: SecretValue? = null,
) {
    private val storedBody = requestBody?.copyOf()

    @Synchronized
    fun takeBody(): ByteArray? = storedBody?.copyOf().also { storedBody?.fill(0) }

    override fun toString(): String =
        "SearchConsoleHttpRequest(origin=$origin, method=$method, pathSegments=$pathSegments, " +
            "queryCount=${queryParameters.size}, requestBody=<redacted>, bearerToken=" +
            "${if (bearerToken == null) "null" else "<redacted>"})"
}

internal interface SearchConsoleHttpTransport {
    fun newCall(request: SearchConsoleHttpRequest): CancelableCall<HttpResponse>
}

/** Bounded cancellable HTTPS transport restricted to four exact Google origins. */
internal class SecureSearchConsoleHttpTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maximumResponseBytes: Int = 8 * 1_024 * 1_024,
    private val maximumRedirects: Int = 2,
) : SearchConsoleHttpTransport {
    private val policies = SearchConsoleGoogleOrigin.entries.associateWith {
        ProviderEndpointPolicy(it.baseUrl)
    }

    init {
        require(connectTimeoutMillis in 1..120_000 && readTimeoutMillis in 1..120_000)
        require(maximumResponseBytes in 1..8 * 1_024 * 1_024)
        require(maximumRedirects in 0..5)
    }

    override fun newCall(request: SearchConsoleHttpRequest): CancelableCall<HttpResponse> {
        require(request.method == "GET" || request.method == "POST") { "Unsupported Google method." }
        require(request.pathSegments.isNotEmpty() && request.pathSegments.size <= 16)
        require(request.pathSegments.all { it.isNotEmpty() && it.length <= MAX_SEGMENT_CHARACTERS })
        require(request.queryParameters.size <= 32)
        val policy = checkNotNull(policies[request.origin])
        val uri = fixedOriginUri(
            policy,
            request.pathSegments,
            request.queryParameters,
            request.literalColonInLastSegment,
        )
        val body = request.takeBody()
        return try {
            BoundedSearchConsoleHttpCall(
                request.method,
                uri,
                policy,
                body,
                request.contentType,
                request.bearerToken,
                connectTimeoutMillis,
                readTimeoutMillis,
                maximumResponseBytes,
                maximumRedirects,
            )
        } finally {
            body?.fill(0)
        }
    }

    private fun fixedOriginUri(
        policy: ProviderEndpointPolicy,
        segments: List<String>,
        query: List<Pair<String, String>>,
        literalColonInLastSegment: Boolean,
    ): URI {
        val rawPath = segments.mapIndexed { index, segment ->
            val encoded = encodePathSegment(segment)
            if (literalColonInLastSegment && index == segments.lastIndex) {
                encoded.replace("%3A", ":")
            } else {
                encoded
            }
        }.joinToString("/", prefix = "/")
        val resolved = URI(policy.baseUri.toASCIIString().trimEnd('/') + rawPath)
        val withQuery = if (query.isEmpty()) {
            resolved
        } else {
            // Reuse the shared policy's bounded query encoder, then transplant onto the encoded path.
            val encodedQuery = policy.resolve("query", query).rawQuery
            URI(resolved.toASCIIString() + "?" + encodedQuery)
        }
        check(policy.isSameOrigin(withQuery)) { "Google endpoint escaped its fixed origin." }
        return withQuery
    }

    companion object {
        private const val MAX_SEGMENT_CHARACTERS = 8_192

        internal fun encodePathSegment(value: String): String {
            val unreserved = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
                .toSet()
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return try {
                buildString(bytes.size) {
                    bytes.forEach { raw ->
                        val unsigned = raw.toInt() and 0xff
                        val character = unsigned.toChar()
                        if (character in unreserved) append(character) else append("%%%02X".format(unsigned))
                    }
                }
            } finally {
                bytes.fill(0)
            }
        }
    }
}

private class BoundedSearchConsoleHttpCall(
    private val method: String,
    private val initialUri: URI,
    private val endpointPolicy: ProviderEndpointPolicy,
    requestBody: ByteArray?,
    private val contentType: String?,
    private val bearerToken: SecretValue?,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
) : CancelableCall<HttpResponse> {
    private val storedRequestBody = requestBody?.copyOf()
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "A Google call can only execute once." }
        var uri = initialUri
        var redirects = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = openConnection(uri)
                activeConnection.set(connection)
                try {
                    writeBody(connection)
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (status in REDIRECT_CODES) {
                        if (redirects >= maximumRedirects) {
                            throw UnsafeRedirectException("Google returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("Google redirect omitted its location.")
                        uri = try {
                            endpointPolicy.resolveRedirect(uri, location)
                        } catch (error: Exception) {
                            throw UnsafeRedirectException("Google returned an unsafe redirect.", error)
                        }
                        redirects += 1
                        continue
                    }
                    if (connection.contentLengthLong > maximumResponseBytes) {
                        throw ResponseTooLargeException(maximumResponseBytes)
                    }
                    val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    val responseBytes = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        HttpResponse(status, responseBytes, safeHeaders(connection))
                    } finally {
                        responseBytes.fill(0)
                    }
                } catch (error: IOException) {
                    if (cancelled.get()) throw CancellationException("Google request was cancelled.")
                    throw error
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
        } finally {
            storedRequestBody?.fill(0)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
        storedRequestBody?.fill(0)
    }

    private fun openConnection(uri: URI): HttpsURLConnection {
        check(endpointPolicy.isSameOrigin(uri)) { "Refusing a Google URL outside its fixed origin." }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Google URL did not create a secure connection.")
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        bearerToken?.use { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun writeBody(connection: HttpsURLConnection) {
        storedRequestBody?.let { body ->
            throwIfCancelled()
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType ?: "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingSearchConsoleResponseStream(minOf(32 * 1_024, maximumResponseBytes)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumResponseBytes) throw ResponseTooLargeException(maximumResponseBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeHeaders(connection: HttpsURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            name?.takeUnless { it.lowercase(Locale.ROOT) in SENSITIVE_HEADERS }?.let { it to values.toList() }
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("Google request was cancelled.")
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_HEADERS = setOf("authorization", "set-cookie", "set-cookie2")
    }
}

private class WipingSearchConsoleResponseStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
