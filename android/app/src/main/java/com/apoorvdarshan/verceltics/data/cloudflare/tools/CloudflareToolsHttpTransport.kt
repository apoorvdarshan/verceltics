package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Sends one authenticated Cloudflare tools request. Implementations must stay on api.cloudflare.com. */
fun interface CloudflareToolsTransport {
    fun newCall(request: CloudflareToolsHttpRequest, token: SecretValue): CancelableCall<HttpResponse>
}

/**
 * Bounded, cancellable HTTPS transport for GET/POST/PUT/PATCH/DELETE. Only GET follows redirects,
 * and only to the same pinned Cloudflare origin; writes are never replayed with their body.
 */
class SecureCloudflareToolsTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val maximumResponseBytes: Int = DEFAULT_MAXIMUM_RESPONSE_BYTES,
    private val maximumRedirects: Int = 3,
) : CloudflareToolsTransport {
    init {
        require(connectTimeoutMillis in 1..120_000 && readTimeoutMillis in 1..120_000) { "Invalid timeout." }
        require(maximumResponseBytes in 1..HARD_MAXIMUM_RESPONSE_BYTES) { "Invalid response limit." }
        require(maximumRedirects in 0..5) { "Invalid redirect limit." }
    }

    override fun newCall(request: CloudflareToolsHttpRequest, token: SecretValue): CancelableCall<HttpResponse> =
        CloudflareToolsHttpCall(
            request = request,
            token = token,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
            maximumRedirects = maximumRedirects,
        )

    companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 8 * 1_024 * 1_024
        private const val HARD_MAXIMUM_RESPONSE_BYTES = 32 * 1_024 * 1_024
    }
}

private class CloudflareToolsHttpCall(
    private val request: CloudflareToolsHttpRequest,
    private val token: SecretValue,
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maximumResponseBytes: Int,
    private val maximumRedirects: Int,
) : CancelableCall<HttpResponse> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override fun execute(): HttpResponse {
        check(started.compareAndSet(false, true)) { "A Cloudflare call can only execute once." }
        val body = request.bodyCopy()
        var uri = request.uri
        var redirects = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = open(uri)
                activeConnection.set(connection)
                try {
                    writeBody(connection, body)
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (request.method == CloudflareHttpMethod.GET && status in REDIRECT_CODES) {
                        if (redirects >= maximumRedirects) {
                            throw UnsafeRedirectException("Cloudflare returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("Cloudflare's redirect omitted its location.")
                        uri = pinnedRedirect(uri, location)
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
                    val bytes = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        HttpResponse(status, bytes, safeHeaders(connection))
                    } finally {
                        bytes.fill(0)
                    }
                } catch (error: IOException) {
                    if (cancelled.get()) throw CancellationException("The Cloudflare request was cancelled.")
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
        check(CloudflareExplorerRequestBuilder.isPinnedCloudflareUri(uri)) {
            "Refusing to open a URL outside api.cloudflare.com/client/v4."
        }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("Cloudflare did not create a secure HTTPS connection.")
        try {
            connection.requestMethod = request.method.name
        } catch (_: ProtocolException) {
            throw IOException("This device cannot send ${request.method.name} requests.")
        }
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        request.headers.forEach { (name, value) ->
            if (!CloudflareExplorerRequestBuilder.isProtectedHeader(name)) connection.setRequestProperty(name, value)
        }
        token.use { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun writeBody(connection: HttpURLConnection, body: ByteArray?) {
        val payload = body ?: return
        throwIfCancelled()
        connection.doOutput = true
        request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use { it.write(payload) }
    }

    private fun pinnedRedirect(current: URI, location: String): URI {
        val target = try {
            current.resolve(URI(location)).normalize()
        } catch (error: Exception) {
            throw UnsafeRedirectException("Cloudflare returned an unsafe redirect.", error)
        }
        if (!CloudflareExplorerRequestBuilder.isPinnedCloudflareUri(target)) {
            throw UnsafeRedirectException("Cloudflare redirects outside api.cloudflare.com/client/v4 are blocked.")
        }
        return target
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingStream(minOf(64 * 1_024, maximumResponseBytes)).use { output ->
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

    private fun safeHeaders(connection: HttpURLConnection): Map<String, List<String>> =
        connection.headerFields.entries.mapNotNull { (name, values) ->
            name?.takeUnless { it.lowercase(Locale.ROOT) in SENSITIVE_HEADERS }?.let { it to values.toList() }
        }.toMap()

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The Cloudflare request was cancelled.")
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authenticate", "set-cookie", "set-cookie2")
    }
}

private class WipingStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
