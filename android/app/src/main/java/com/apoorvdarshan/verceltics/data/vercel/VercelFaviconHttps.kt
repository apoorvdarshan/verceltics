package com.apoorvdarshan.verceltics.data.vercel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Cookieless, cache-less favicon transport for a project's own HTTPS origin.
 *
 * Two independent public-address checks run for every hop: the host's DNS answers must all be
 * public before connecting, and the socket HttpsURLConnection actually connected must point at a
 * public address before TLS starts (the DNS-rebinding guard iOS performs with task metrics).
 * Redirects are followed manually and only within the same HTTPS host and port.
 */
class HttpsVercelFaviconTransport(
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
    private val maximumRedirects: Int = 3,
) : VercelFaviconTransport {
    override fun newCall(
        uri: URI,
        host: String,
        accept: String,
        maximumBytes: Int,
    ): CancelableCall<VercelFaviconResponse> = FaviconCall(uri, host, accept, maximumBytes)

    private inner class FaviconCall(
        private val initialUri: URI,
        private val host: String,
        private val accept: String,
        private val maximumBytes: Int,
    ) : CancelableCall<VercelFaviconResponse> {
        private val started = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val activeConnection = AtomicReference<HttpsURLConnection?>()

        override fun execute(): VercelFaviconResponse {
            check(started.compareAndSet(false, true)) { "A favicon call can only execute once." }
            var uri = initialUri
            var redirects = 0
            while (true) {
                throwIfCancelled()
                if (!VercelFaviconPolicy.isSameOrigin(uri, host)) {
                    throw UnsafeRedirectException("Favicon requests must stay on the project origin.")
                }
                if (!VercelFaviconPolicy.allPublic(resolver(host))) {
                    throw IOException("The project host does not resolve only to public addresses.")
                }
                val connection = uri.toURL().openConnection() as? HttpsURLConnection
                    ?: throw IOException("The favicon URL did not create a secure connection.")
                activeConnection.set(connection)
                try {
                    configure(connection)
                    val status = connection.responseCode
                    throwIfCancelled()
                    if (status in REDIRECT_CODES) {
                        if (redirects >= maximumRedirects) throw UnsafeRedirectException("Too many favicon redirects.")
                        val location = connection.getHeaderField("Location")
                            ?: throw UnsafeRedirectException("The favicon redirect omitted its location.")
                        uri = VercelFaviconPolicy.sameOriginRedirect(uri, location, host)
                            ?: throw UnsafeRedirectException("Cross-origin favicon redirects are not followed.")
                        redirects += 1
                        continue
                    }
                    if (connection.contentLengthLong > maximumBytes) throw ResponseTooLargeException(maximumBytes)
                    val body = if (status in 200..299) {
                        connection.inputStream.use(::readBounded)
                    } else {
                        ByteArray(0)
                    }
                    return VercelFaviconResponse(status, connection.contentType, body)
                } catch (error: IOException) {
                    if (cancelled.get()) throw CancellationException("The favicon request was cancelled.")
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

        private fun configure(connection: HttpsURLConnection) {
            connection.sslSocketFactory = PublicEndpointSSLSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doInput = true
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)
        }

        private fun readBounded(input: InputStream): ByteArray {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1_024))
            var total = 0
            while (true) {
                throwIfCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximumBytes) throw ResponseTooLargeException(maximumBytes)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun throwIfCancelled() {
            if (cancelled.get()) throw CancellationException("The favicon request was cancelled.")
        }
    }

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Verceltics"
    }
}

/**
 * Layers TLS only over sockets whose connected remote address is public. HttpsURLConnection
 * connects the plain socket itself, then asks this factory to wrap it, so the check sees the
 * address actually in use and runs before any request byte is written.
 */
internal class PublicEndpointSSLSocketFactory(
    private val delegate: SSLSocketFactory,
    private val isAllowed: (InetAddress) -> Boolean = VercelFaviconPolicy::isPublicAddress,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(socket: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
        requirePublic(socket)
        return delegate.createSocket(socket, host, port, autoClose)
    }

    override fun createSocket(): Socket =
        throw SocketException("Unconnected favicon sockets are not supported.")

    override fun createSocket(host: String?, port: Int): Socket =
        requirePublic(delegate.createSocket(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        requirePublic(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        requirePublic(delegate.createSocket(host, port))

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = requirePublic(delegate.createSocket(address, port, localAddress, localPort))

    private fun requirePublic(socket: Socket): Socket {
        val remote = socket.inetAddress
        if (remote == null || !isAllowed(remote)) {
            runCatching { socket.close() }
            throw SocketException("Refusing a favicon connection to a non-public address.")
        }
        return socket
    }
}

/** Decodes PNG, JPEG, WebP, GIF and ICO favicons into a small, matte-free bitmap. */
object VercelFaviconBitmapDecoder {
    private const val MAX_SOURCE_DIMENSION = 8_192

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (maxOf(width, height) < VercelFaviconPolicy.MIN_ICON_PIXELS) return null
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = VercelFaviconPolicy.sampleSizeFor(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = scaleToFit(decoded)
        val mutable = if (scaled.isMutable && scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== scaled) scaled.recycle() }
        } ?: return null
        val pixels = IntArray(mutable.width * mutable.height)
        mutable.getPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        if (VercelFaviconPolicy.removeWhiteBackground(pixels, mutable.width, mutable.height)) {
            mutable.setHasAlpha(true)
            mutable.setPixels(pixels, 0, mutable.width, 0, 0, mutable.width, mutable.height)
        }
        return mutable
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= VercelFaviconPolicy.MAX_ICON_PIXELS) return bitmap
        val scale = VercelFaviconPolicy.MAX_ICON_PIXELS.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
