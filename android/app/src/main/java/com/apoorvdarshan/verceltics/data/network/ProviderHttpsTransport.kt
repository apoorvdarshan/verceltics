package com.apoorvdarshan.verceltics.data.network

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One HTTPS request to an absolute provider URL. Credentials travel only as [bearerToken] or
 * [secretHeaders]; `toString()` never prints them, the query string, or the body.
 */
class ProviderHttpsRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val bearerToken: SecretValue? = null,
    val secretHeaders: Map<String, SecretValue> = emptyMap(),
    body: ByteArray? = null,
    val contentType: String? = null,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) {
    private val storedBody = body?.copyOf()

    init {
        require(method == "GET" || method == "POST") { "Unsupported provider HTTP method." }
        require(uri.scheme.equals("https", ignoreCase = true)) { "Provider requests must use HTTPS." }
        require(!uri.host.isNullOrBlank()) { "Provider requests need a host." }
        require(uri.rawUserInfo == null) { "Provider URLs cannot include user information." }
        require(uri.rawFragment == null) { "Provider URLs cannot include a fragment." }
        require(storedBody == null || method == "POST") { "Only POST requests carry a body." }
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS && readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "Invalid provider timeout."
        }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) { "Invalid provider response limit." }
        contentType?.let { require(it.none { c -> c == '\r' || c == '\n' }) { "Invalid content type." } }
        (headers.keys + secretHeaders.keys).forEach { name ->
            require(HEADER_NAME.matches(name)) { "Invalid HTTP header name." }
            require(name.lowercase(Locale.ROOT) !in PROTECTED_HEADERS) {
                "The $name header is controlled by the secure HTTP client."
            }
        }
        require(secretHeaders.keys.none { it.equals("authorization", ignoreCase = true) } || bearerToken == null) {
            "Use either a bearer token or an Authorization secret header."
        }
        headers.forEach { (name, value) ->
            require(!name.equals("authorization", ignoreCase = true)) {
                "Authorization headers must be passed as secrets."
            }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' } && value.length <= 8_192) {
                "Invalid HTTP header value."
            }
        }
    }

    @Synchronized
    fun takeBody(): ByteArray? = storedBody?.copyOf().also { storedBody?.fill(0) }

    /** Exposes the request body for tests and fakes without erasing it. */
    @Synchronized
    fun peekBody(): ByteArray? = storedBody?.copyOf()

    val secrets: List<SecretValue>
        get() = listOfNotNull(bearerToken) + secretHeaders.values

    override fun toString(): String =
        "ProviderHttpsRequest(method=$method, origin=${uri.scheme}://${uri.host}, path=${uri.rawPath}, " +
            "query=${if (uri.rawQuery == null) "none" else "<redacted>"}, headerNames=${headers.keys}, " +
            "secretHeaders=${secretHeaders.keys}, bearer=${if (bearerToken == null) "none" else "<redacted>"}, " +
            "body=${if (storedBody == null) "none" else "<redacted>"})"

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 30_000
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES: Int = 8 * 1_024 * 1_024
        const val HARD_MAXIMUM_RESPONSE_BYTES: Int = 16 * 1_024 * 1_024
        private const val MAX_TIMEOUT_MILLIS = 120_000
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val PROTECTED_HEADERS = setOf("host", "content-length", "content-type", "accept-encoding")
    }
}

interface ProviderHttpsTransport {
    fun newCall(request: ProviderHttpsRequest): CancelableCall<HttpResponse>
}

/**
 * Bounded, cancellable HTTPS transport. GET requests follow at most [maximumRedirects]
 * same-origin HTTPS redirects; POST responses are never redirected with their body.
 */
class SecureProviderHttpsTransport(
    private val maximumRedirects: Int = 3,
) : ProviderHttpsTransport {
    init {
        require(maximumRedirects in 0..5) { "Invalid redirect limit." }
    }

    override fun newCall(request: ProviderHttpsRequest): CancelableCall<HttpResponse> =
        ProviderHttpsCall(request, maximumRedirects)
}

private class ProviderHttpsCall(
    private val request: ProviderHttpsRequest,
    private val maximumRedirects: Int,
) : CancelableCall<HttpResponse> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()
    private val body = request.takeBody()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "A provider call can only execute once." }
        var uri = request.uri
        var redirects = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = open(uri)
                activeConnection.set(connection)
                try {
                    writeBody(connection)
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (request.method == "GET" && status in REDIRECT_CODES) {
                        if (redirects >= maximumRedirects) {
                            throw UnsafeRedirectException("The provider returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("The provider redirect omitted its location.")
                        uri = sameOriginRedirect(uri, location)
                        redirects += 1
                        continue
                    }
                    if (connection.contentLengthLong > request.maximumResponseBytes) {
                        throw ResponseTooLargeException(request.maximumResponseBytes)
                    }
                    val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    val bytes = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        HttpResponse(status, bytes, safeHeaders(connection))
                    } finally {
                        bytes.fill(0)
                    }
                } catch (error: IOException) {
                    if (cancelled.get()) throw CancellationException("The provider request was cancelled.")
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

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun open(uri: URI): HttpURLConnection {
        require(uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null) {
            "Refusing a non-HTTPS provider URL."
        }
        val connection = uri.toURL().openConnection() as? javax.net.ssl.HttpsURLConnection
            ?: throw IOException("The provider URL did not create a secure connection.")
        connection.requestMethod = request.method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = request.connectTimeoutMillis
        connection.readTimeout = request.readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        request.headers.forEach(connection::setRequestProperty)
        request.secretHeaders.forEach { (name, secret) ->
            secret.use { connection.setRequestProperty(name, it) }
        }
        request.bearerToken?.use { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun writeBody(connection: HttpURLConnection) {
        val payload = body ?: return
        throwIfCancelled()
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", request.contentType ?: "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
    }

    private fun sameOriginRedirect(current: URI, location: String): URI {
        val target = try {
            current.resolve(URI(location)).normalize()
        } catch (error: Exception) {
            throw UnsafeRedirectException("The provider returned an unsafe redirect.", error)
        }
        val sameOrigin = target.scheme.equals("https", ignoreCase = true) &&
            target.host.equals(current.host, ignoreCase = true) &&
            effectivePort(target) == effectivePort(current) &&
            target.rawUserInfo == null
        if (!sameOrigin) throw UnsafeRedirectException("Cross-origin provider redirects are not allowed.")
        return target
    }

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else 443

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingResponseStream(minOf(64 * 1_024, request.maximumResponseBytes)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > request.maximumResponseBytes) {
                        throw ResponseTooLargeException(request.maximumResponseBytes)
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun safeHeaders(connection: HttpURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            name?.takeUnless { it.lowercase(Locale.ROOT) in SENSITIVE_HEADERS }?.let { it to values.toList() }
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The provider request was cancelled.")
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authenticate", "set-cookie", "set-cookie2")
    }
}

private class WipingResponseStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}

/**
 * Runs a blocking provider call on [executor] and suspends until it completes. Coroutine
 * cancellation cancels the in-flight call.
 */
suspend fun <T> CancelableCall<T>.awaitProviderCall(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        executor.execute {
            try {
                val value = execute()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: CancellationException) {
                if (continuation.isActive) continuation.cancel(error)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

/** Executes [block] on [executor]; used for encrypted storage and CPU-bound parsing. */
suspend fun <T> runOnProviderExecutor(executor: Executor, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        continuation.invokeOnCancellation { cancelled.set(true) }
        executor.execute {
            if (cancelled.get()) return@execute
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
