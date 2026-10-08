package com.apoorvdarshan.verceltics.data.vercel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import com.apoorvdarshan.verceltics.data.network.runOnProviderExecutor
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a Vercel profile avatar may come from. Like iOS `AuthManager.avatarURL(from:)`, an avatar
 * hash becomes `https://api.vercel.com/www/avatar/{hash}`; full URLs are only kept when they are
 * HTTPS on vercel.com, so a profile can never point the app at a third-party or private host.
 */
object VercelAvatarPolicy {
    const val MAX_IMAGE_BYTES: Int = 1_000_000
    const val FAILURE_RETRY_MILLIS: Long = 5 * 60 * 1_000L
    const val TIMEOUT_MILLIS: Long = 8_000L
    const val MAX_AVATAR_PIXELS: Int = 192
    private const val AVATAR_ENDPOINT = "https://api.vercel.com/www/avatar/"
    private val AVATAR_HASH = Regex("[A-Za-z0-9_-]{1,128}")

    /** The `/v2/user` avatar worth saving (a hash or an allowed URL), or null. */
    fun storableAvatar(raw: String?): String? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return value.takeIf { avatarUrl(it) != null }
    }

    /** The HTTPS image URL for a saved avatar, or null when it has none or it is not allowed. */
    fun avatarUrl(avatar: String?): String? {
        val value = avatar?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (AVATAR_HASH.matches(value)) return AVATAR_ENDPOINT + value
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        return value.takeIf { isAllowedAvatarUri(uri) }
    }

    /** HTTPS on the default port, without credentials or fragments, on vercel.com or a subdomain. */
    fun isAllowedAvatarUri(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.rawFragment != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removeSuffix(".") ?: return false
        return host == "vercel.com" || host.endsWith(".vercel.com")
    }
}

/**
 * Loads and caches account avatars through the same cookieless, public-address-only transport
 * used for favicons. No Vercel token is ever attached. Failures are remembered for five minutes.
 *
 * Generic over the decoded image type so the rules are unit tested on the JVM.
 */
class VercelAvatarLoader<T : Any>(
    private val transport: VercelFaviconTransport,
    private val executor: Executor,
    private val decode: (ByteArray) -> T?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = VercelAvatarPolicy.TIMEOUT_MILLIS,
    private val cacheLimit: Int = DEFAULT_CACHE_LIMIT,
) {
    private val lock = Any()
    private val images = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > cacheLimit
    }
    private val failedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > cacheLimit
    }

    init {
        require(cacheLimit > 0) { "The avatar cache needs at least one entry." }
    }

    fun cached(url: String): T? = synchronized(lock) { images[url] }

    suspend fun load(url: String): T? {
        val uri = parse(url) ?: return null
        synchronized(lock) {
            images[url]?.let { return it }
            val failure = failedAt[url]
            if (failure != null && nowMillis() - failure < VercelAvatarPolicy.FAILURE_RETRY_MILLIS) return null
        }
        val image = withTimeoutOrNull(timeoutMillis) { fetch(uri) }
        synchronized(lock) {
            if (image != null) {
                images[url] = image
                failedAt.remove(url)
            } else {
                failedAt[url] = nowMillis()
            }
        }
        return image
    }

    /** Drops every cached avatar and failure, e.g. after the last Vercel account is removed. */
    fun clear() {
        synchronized(lock) {
            images.clear()
            failedAt.clear()
        }
    }

    private suspend fun fetch(uri: URI): T? {
        val host = uri.host ?: return null
        val response = try {
            transport.newCall(uri, host, IMAGE_ACCEPT, VercelAvatarPolicy.MAX_IMAGE_BYTES).awaitProviderCall(executor)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode !in 200..299 || response.body.isEmpty()) return null
        if (VercelFaviconPolicy.looksLikeSvg(response.body, response.contentType)) return null
        return try {
            runOnProviderExecutor(executor) { decode(response.body) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(url: String): URI? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        return uri.takeIf(VercelAvatarPolicy::isAllowedAvatarUri)
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT: Int = 16
        internal const val IMAGE_ACCEPT = "image/png,image/jpeg,image/webp,image/*;q=0.8"
    }
}

/** Decodes a PNG, JPEG, WebP or GIF avatar into a small bitmap, without any matte removal. */
object VercelAvatarBitmapDecoder {
    private const val MAX_SOURCE_DIMENSION = 4_096

    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = VercelFaviconPolicy.sampleSizeFor(width, height, VercelAvatarPolicy.MAX_AVATAR_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
