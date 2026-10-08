package com.apoorvdarshan.verceltics.ui.hosting

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsRequest
import com.apoorvdarshan.verceltics.data.network.SecureProviderHttpsTransport
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Circular account avatar (iOS `ProviderAccountMenu.providerBadge`): the provider's HTTPS avatar
 * when one was returned, otherwise the provider mark. Loading is best effort and credential-free.
 */
@Composable
fun ProviderAccountAvatar(
    provider: IntegrationProvider,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val inPreview = LocalInspectionMode.current
    val url = avatarUrl?.takeIf { !inPreview && ProviderAvatarLoader.isLoadable(it) }
    // produceState keeps its value across key changes, so the state is keyed by the URL itself:
    // switching accounts never shows the previous account's avatar.
    val bitmap by key(url) {
        produceState(initialValue = url?.let(ProviderAvatarLoader::cached), url) {
            value = url?.let { ProviderAvatarLoader.cached(it) ?: ProviderAvatarLoader.load(it) }
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        ProviderLogo(provider, modifier.size(size), monochrome = true)
    }
}

/** Bounded in-memory avatar cache. Only absolute HTTPS URLs without user info are fetched. */
internal object ProviderAvatarLoader {
    private const val MAX_URL_CHARACTERS = 2_048
    private const val MAX_RESPONSE_BYTES = 1_024 * 1_024
    private const val MAX_SOURCE_DIMENSION = 4_096
    private const val TARGET_DIMENSION = 128
    private val cache = LruCache<String, ImageBitmap>(48)
    private val failed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val transport = SecureProviderHttpsTransport(maximumRedirects = 2)

    fun isLoadable(url: String): Boolean {
        if (url.length > MAX_URL_CHARACTERS) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun load(url: String): ImageBitmap? {
        if (!isLoadable(url) || url in failed) return null
        cache.get(url)?.let { return it }
        return try {
            withContext(Dispatchers.IO) { fetch(url) }?.also { cache.put(url, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: run {
            failed += url
            null
        }
    }

    private fun fetch(url: String): ImageBitmap? {
        val request = ProviderHttpsRequest(
            method = "GET",
            uri = URI(url),
            maximumResponseBytes = MAX_RESPONSE_BYTES,
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 15_000,
        )
        val response = transport.newCall(request).execute()
        if (response.statusCode !in 200..299) return null
        val bytes = response.takeBody()
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..MAX_SOURCE_DIMENSION || bounds.outHeight !in 1..MAX_SOURCE_DIMENSION) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_DIMENSION && bounds.outHeight / (sample * 2) >= TARGET_DIMENSION) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        } finally {
            bytes.fill(0)
        }
    }
}
