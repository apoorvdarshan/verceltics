package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
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

/**
 * Sends one authenticated Cloudflare tools request. Implementations must stay on api.cloudflare.com
 * and authenticate with [credential] (bearer token or `X-Auth-Email` + `X-Auth-Key`).
 */
fun interface CloudflareToolsTransport {
    fun newCall(request: CloudflareToolsHttpRequest, credential: CloudflareCredential): CancelableCall<HttpResponse>
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

    override fun newCall(request: CloudflareToolsHttpRequest, credential: CloudflareCredential): CancelableCall<HttpResponse> =
        CloudflareToolsHttpCall(
            request = request,
            credential = credential,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maximumResponseBytes = maximumResponseBytes,
            maximumRedirects = maximumRedirects,
        )

    companion object {
        /** iOS `CloudflareAPI.execute` accepts responses up to 32 MB. */
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 32 * 1_024 * 1_024
        const val HARD_MAXIMUM_RESPONSE_BYTES = 32 * 1_024 * 1_024
    }
}

private class CloudflareToolsHttpCall(
    private val request: CloudflareToolsHttpRequest,
    private val credential: CloudflareCredential,
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
        credential.applyHeaders(connection::setRequestProperty)
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

    /**
     * Reads into fixed-size segments and joins them once at the end. Unlike a doubling
     * `ByteArrayOutputStream`, a 32 MB response never holds more than about twice its own size,
     * and nothing is preallocated beyond what Cloudflare actually sends.
     */
    private fun readBounded(input: InputStream): ByteArray = CloudflareSegmentedBuffer().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            while (true) {
                throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size.toLong() + count > maximumResponseBytes) throw ResponseTooLargeException(maximumResponseBytes)
                output.write(buffer, count)
            }
            output.toByteArray()
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

/** Append-only byte storage in 256 KB segments that are wiped on close. */
internal class CloudflareSegmentedBuffer(private val segmentSize: Int = 256 * 1_024) : java.io.Closeable {
    private val segments = ArrayList<ByteArray>()
    private var lastFill = 0

    var size: Int = 0
        private set

    fun write(source: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            if (segments.isEmpty() || lastFill == segmentSize) {
                segments += ByteArray(segmentSize)
                lastFill = 0
            }
            val chunk = minOf(count - offset, segmentSize - lastFill)
            System.arraycopy(source, offset, segments.last(), lastFill, chunk)
            lastFill += chunk
            offset += chunk
            size += chunk
        }
    }

    fun toByteArray(): ByteArray {
        val output = ByteArray(size)
        var position = 0
        segments.forEachIndexed { index, segment ->
            val length = if (index == segments.lastIndex) lastFill else segmentSize
            System.arraycopy(segment, 0, output, position, length)
            position += length
        }
        return output
    }

    override fun close() {
        segments.forEach { it.fill(0) }
        segments.clear()
        lastFill = 0
        size = 0
    }
}
