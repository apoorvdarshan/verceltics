package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.BuildConfig
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
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

/** Sends one [CloudflareRestRequest]. [credential] is the bearer secret chosen by the client. */
interface CloudflareRestTransport {
    fun newCall(request: CloudflareRestRequest, credential: SecretValue): CancelableCall<CloudflareRestResponse>
}

/**
 * HTTPS transport pinned to `api.cloudflare.com`. GET requests follow at most three redirects that
 * stay on the pinned origin (the iOS redirect guard); mutations never follow redirects, so a body or
 * credential can never be replayed somewhere else.
 */
class HttpsCloudflareRestTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val maximumRedirects: Int = 3,
) : CloudflareRestTransport {
    init {
        require(connectTimeoutMillis in 1..120_000) { "Invalid connect timeout." }
        require(maximumRedirects in 0..5) { "Invalid redirect limit." }
    }

    override fun newCall(request: CloudflareRestRequest, credential: SecretValue): CancelableCall<CloudflareRestResponse> =
        HttpsCloudflareCall(request, credential, connectTimeoutMillis, maximumRedirects)
}

private class HttpsCloudflareCall(
    private val request: CloudflareRestRequest,
    private val credential: SecretValue,
    private val connectTimeoutMillis: Int,
    private val maximumRedirects: Int,
) : CancelableCall<CloudflareRestResponse> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override fun execute(): CloudflareRestResponse {
        check(started.compareAndSet(false, true)) { "A Cloudflare call can only execute once." }
        val body = request.bodyCopy()
        var uri = request.uri()
        var redirects = 0
        try {
            while (true) {
                throwIfCancelled()
                val connection = open(uri)
                activeConnection.set(connection)
                try {
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", request.contentType ?: CloudflareRestRequest.JSON_CONTENT_TYPE)
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    } else if (request.method == CloudflareHttpMethod.POST || request.method == CloudflareHttpMethod.PUT ||
                        request.method == CloudflareHttpMethod.PATCH
                    ) {
                        // Some intermediaries reject body-capable verbs without a length.
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(0)
                        connection.outputStream.close()
                    }
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (status in REDIRECT_CODES && request.method == CloudflareHttpMethod.GET) {
                        if (redirects >= maximumRedirects) {
                            throw UnsafeRedirectException("Cloudflare returned too many redirects.")
                        }
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("Cloudflare's redirect omitted its location.")
                        uri = pinnedRedirect(uri, location)
                        redirects += 1
                        continue
                    }
                    if (connection.contentLengthLong > request.maximumResponseBytes) {
                        throw ResponseTooLargeException(request.maximumResponseBytes)
                    }
                    val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                    val bytes = stream?.use(::readBounded) ?: ByteArray(0)
                    return try {
                        CloudflareRestResponse(status, safeHeaders(connection), bytes)
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
        check(CloudflareRestRequest.isPinnedCloudflareApiUri(uri)) { "Refusing a URL outside the Cloudflare API origin." }
        val connection = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw IOException("The Cloudflare URL did not create a secure connection.")
        connection.setMethodCompat(request.method.name)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = request.readTimeoutMillis
        connection.useCaches = false
        connection.doInput = true
        connection.setRequestProperty("Accept", request.accept)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Verceltics-Android/${BuildConfig.VERSION_NAME}")
        request.headers.forEach(connection::setRequestProperty)
        val secret = when (val auth = request.auth) {
            CloudflareRequestAuth.AccountToken -> credential
            is CloudflareRequestAuth.PagesUploadToken -> auth.jwt
        }
        secret.use { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun pinnedRedirect(current: URI, location: String): URI {
        val target = try {
            current.resolve(URI(location)).normalize()
        } catch (error: Exception) {
            throw UnsafeRedirectException("Cloudflare returned an unsafe redirect.", error)
        }
        if (!CloudflareRestRequest.isPinnedCloudflareApiUri(target)) {
            throw UnsafeRedirectException("Cloudflare redirected outside its official API origin.")
        }
        return target
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        return try {
            WipingBuffer(minOf(64 * 1_024, request.maximumResponseBytes)).use { output ->
                var total = 0
                while (true) {
                    throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > request.maximumResponseBytes) throw ResponseTooLargeException(request.maximumResponseBytes)
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

/**
 * Android's OkHttp-backed `HttpURLConnection` accepts PATCH. The JVM implementation used by host
 * tooling does not, so fall back to its private method field there instead of failing the call.
 */
internal fun HttpURLConnection.setMethodCompat(method: String) {
    try {
        requestMethod = method
    } catch (error: ProtocolException) {
        val patched = runCatching {
            var target: Any = this
            runCatching {
                val delegate = javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(this)
                if (delegate is HttpURLConnection) target = delegate
            }
            HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(target, method)
            if (target !== this) {
                HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(this, method)
            }
        }.isSuccess
        if (!patched) throw error
    }
}

private class WipingBuffer(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
