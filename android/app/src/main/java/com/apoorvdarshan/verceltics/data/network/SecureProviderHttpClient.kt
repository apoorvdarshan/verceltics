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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

interface ProviderHttpClient {
    fun newGetCall(
        relativePath: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
        bearerToken: SecretValue? = null,
        headers: Map<String, String> = emptyMap(),
    ): CancelableCall<HttpResponse>
}

class SecureProviderHttpClient(
    baseUrl: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
) : ProviderHttpClient {
    private val endpointPolicy = ProviderEndpointPolicy(baseUrl)

    init {
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid connect timeout." }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Invalid read timeout." }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) {
            "Invalid maximum response size."
        }
        require(maximumRedirects in 0..HARD_MAXIMUM_REDIRECTS) { "Invalid redirect limit." }
    }

    override fun newGetCall(
        relativePath: String,
        queryParameters: List<Pair<String, String>>,
        bearerToken: SecretValue?,
        headers: Map<String, String>,
    ): CancelableCall<HttpResponse> {
        endpointPolicy.validateUnprivilegedHeaders(headers)
        val initialUri = endpointPolicy.resolve(relativePath, queryParameters)
        return HttpUrlConnectionCall(
            initialUri = initialUri,
            endpointPolicy = endpointPolicy,
            bearerToken = bearerToken,
            headers = headers.toMap(),
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
            maximumRedirects = maximumRedirects,
        )
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val DEFAULT_MAXIMUM_REDIRECTS = 3
        private const val MAX_TIMEOUT_MILLIS = 120_000
        private const val HARD_MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val HARD_MAXIMUM_REDIRECTS = 5
    }
}

class HttpResponse(
    val statusCode: Int,
    body: ByteArray,
    val headers: Map<String, List<String>>,
) {
    private val storedBody = body.copyOf()

    /** Returns the body once and erases the response-owned copy. */
    @Synchronized
    fun takeBody(): ByteArray = storedBody.copyOf().also { storedBody.fill(0) }

    override fun toString(): String =
        "HttpResponse(statusCode=$statusCode, bodyBytes=${storedBody.size}, " +
            "headerNames=${headers.keys})"
}

class ResponseTooLargeException(maximumBytes: Int) : IOException(
    "The provider response exceeded the $maximumBytes-byte safety limit.",
)

class UnsafeRedirectException(message: String, cause: Throwable? = null) : IOException(message, cause)

private class HttpUrlConnectionCall(
    private val initialUri: URI,
    private val endpointPolicy: ProviderEndpointPolicy,
    private val bearerToken: SecretValue?,
    private val headers: Map<String, String>,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
) : CancelableCall<HttpResponse> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpsURLConnection?>()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "An HTTP call can only be executed once." }
        var uri = initialUri
        var redirectCount = 0
        while (true) {
            throwIfCancelled()
            val connection = openConnection(uri)
            activeConnection.set(connection)
            try {
                throwIfCancelled()
                val statusCode = connection.responseCode
                throwIfCancelled()
                if (statusCode in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= maximumRedirects) {
                        throw UnsafeRedirectException("The provider returned too many redirects.")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: throw UnsafeRedirectException("The provider redirect omitted its location.")
                    uri = try {
                        endpointPolicy.resolveRedirect(uri, location)
                    } catch (error: Exception) {
                        throw UnsafeRedirectException("The provider returned an unsafe redirect.", error)
                    }
                    redirectCount += 1
                    continue
                }

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
            } catch (error: IOException) {
                if (cancelled.get()) throw CancellationException("The provider request was cancelled.")
                throw error
            } finally {
                activeConnection.compareAndSet(connection, null)
                connection.disconnect()
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun openConnection(uri: URI): HttpsURLConnection {
        check(endpointPolicy.isSameOrigin(uri)) { "Refusing to open a URL outside the provider origin." }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Provider URL did not create a secure HTTPS connection.")
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        headers.forEach(connection::setRequestProperty)
        bearerToken?.use { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        return connection
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingByteArrayOutputStream(minOf(maximumResponseBytes, 32 * 1024)).use { output ->
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
        if (cancelled.get()) throw CancellationException("The provider request was cancelled.")
    }

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

private class WipingByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
