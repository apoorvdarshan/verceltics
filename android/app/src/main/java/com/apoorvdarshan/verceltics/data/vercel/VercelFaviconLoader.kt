package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import com.apoorvdarshan.verceltics.data.network.runOnProviderExecutor
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded response from the project's own origin. Never carries cookies or credentials. */
class VercelFaviconResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
)

/**
 * Fetches one URL on the project's origin. Implementations must refuse non-HTTPS URLs, other
 * hosts, non-public resolved or connected addresses, cookies, caches and oversized bodies.
 */
fun interface VercelFaviconTransport {
    fun newCall(
        uri: URI,
        host: String,
        accept: String,
        maximumBytes: Int,
    ): CancelableCall<VercelFaviconResponse>
}

/**
 * Discovers and caches a project's favicon the way iOS `ProjectIcon` does: race the two
 * conventional icon paths, then fall back to the home page's `<link rel="icon">` tags, all within
 * an overall timeout. Failures are remembered for five minutes so lists don't hammer a site.
 *
 * Generic over the decoded image type so the discovery rules are unit tested on the JVM.
 */
class VercelFaviconLoader<T : Any>(
    private val transport: VercelFaviconTransport,
    private val executor: Executor,
    private val decode: (ByteArray) -> T?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = VercelFaviconPolicy.OVERALL_TIMEOUT_MILLIS,
    private val cacheLimit: Int = DEFAULT_CACHE_LIMIT,
) {
    private val lock = Any()
    private val images = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > cacheLimit
    }
    private val failedAt = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > cacheLimit
    }

    init {
        require(cacheLimit > 0) { "The favicon cache needs at least one entry." }
    }

    /** Returns a cached image without touching the network, or null. */
    fun cached(domain: String): T? {
        val host = VercelFaviconPolicy.publicHost(domain) ?: return null
        return synchronized(lock) { images[host] }
    }

    /** True when [domain] can never have a favicon or recently failed, so callers show the letter. */
    fun isKnownUnavailable(domain: String?): Boolean {
        val host = domain?.let(VercelFaviconPolicy::publicHost) ?: return true
        return synchronized(lock) {
            failedAt[host]?.let { nowMillis() - it < VercelFaviconPolicy.FAILURE_RETRY_MILLIS } == true
        }
    }

    suspend fun load(domain: String): T? {
        val host = VercelFaviconPolicy.publicHost(domain) ?: return null
        synchronized(lock) {
            images[host]?.let { return it }
            val failure = failedAt[host]
            if (failure != null && nowMillis() - failure < VercelFaviconPolicy.FAILURE_RETRY_MILLIS) return null
        }
        val image = withTimeoutOrNull(timeoutMillis) { discover(host) }
        synchronized(lock) {
            if (image != null) {
                images[host] = image
                failedAt.remove(host)
            } else {
                failedAt[host] = nowMillis()
            }
        }
        return image
    }

    /** Drops every cached image and failure, e.g. when the Vercel account is disconnected. */
    fun clear() {
        synchronized(lock) {
            images.clear()
            failedAt.clear()
        }
    }

    private suspend fun discover(host: String): T? {
        raceFirst(VercelFaviconPolicy.directIconUris(host)) { uri -> fetchImage(uri, host) }
            ?.let { return it }
        val pageUri = VercelFaviconPolicy.homePageUri(host)
        val page = fetch(pageUri, host, HTML_ACCEPT, VercelFaviconPolicy.MAX_HTML_BYTES)
            ?.takeIf { it.statusCode in 200..299 }
            ?: return null
        val html = String(page.body, StandardCharsets.UTF_8)
        for (iconUri in VercelFaviconPolicy.scrapeIconUris(html, pageUri)) {
            fetchImage(iconUri, host)?.let { return it }
        }
        return null
    }

    private suspend fun fetchImage(uri: URI, host: String): T? {
        val response = fetch(uri, host, IMAGE_ACCEPT, VercelFaviconPolicy.MAX_IMAGE_BYTES) ?: return null
        if (response.statusCode !in 200..299) return null
        if (response.body.size < VercelFaviconPolicy.MIN_IMAGE_BYTES) return null
        if (VercelFaviconPolicy.looksLikeSvg(response.body, response.contentType)) return null
        return try {
            runOnProviderExecutor(executor) { decode(response.body) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(uri: URI, host: String, accept: String, maximumBytes: Int): VercelFaviconResponse? {
        if (!VercelFaviconPolicy.isSameOrigin(uri, host)) return null
        return try {
            transport.newCall(uri, host, accept, maximumBytes).awaitProviderCall(executor)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** First non-null result wins; the losing requests are cancelled. */
    private suspend fun raceFirst(uris: List<URI>, fetch: suspend (URI) -> T?): T? = coroutineScope {
        if (uris.isEmpty()) return@coroutineScope null
        val results = Channel<T?>(capacity = uris.size)
        val jobs: List<Job> = uris.map { uri -> launch { results.send(fetch(uri)) } }
        repeat(uris.size) {
            val result = results.receive()
            if (result != null) {
                jobs.forEach(Job::cancel)
                return@coroutineScope result
            }
        }
        null
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT: Int = 64
        internal const val IMAGE_ACCEPT = "image/png,image/jpeg,image/webp,image/x-icon,image/*;q=0.8"
        internal const val HTML_ACCEPT = "text/html,application/xhtml+xml"
    }
}
