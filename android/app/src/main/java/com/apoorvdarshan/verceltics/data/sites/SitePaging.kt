package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.net.URI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One detail request: a single attempt, detail error copy, larger body limit, secrets redacted. */
internal suspend fun SiteApiContext.detailJson(request: SiteHttpRequest): ProviderJsonValue =
    SiteDetailSupport.sanitize(
        http.requestJson(
            request.withMaximumResponseBytes(SiteDetailSupport.MAXIMUM_DETAIL_RESPONSE_BYTES),
            maximumAttempts = 1,
            style = SiteErrorStyle.DETAIL,
        ),
    )

internal class SitePagedCollection(
    val pages: List<ProviderJsonValue>,
    val items: List<ProviderJsonValue>,
    val truncated: Boolean,
)

/** Bounded cursor and link pagination shared by the detail adapters. */
internal object SitePaging {
    /** Google `nextPageToken` paging with repeated-token and page/row guards. */
    suspend fun googleTokenPages(
        context: SiteApiContext,
        initialUrl: URI,
        itemKey: String,
        token: SecretValue,
    ): SitePagedCollection {
        var nextUrl: URI? = initialUrl
        val pages = ArrayList<ProviderJsonValue>()
        val items = ArrayList<ProviderJsonValue>()
        val seenTokens = HashSet<String>()
        var pageCount = 0
        var truncated = false
        while (true) {
            val url = nextUrl ?: break
            currentCoroutineContext().ensureActive()
            val response = context.detailJson(SiteHttpRequest(url, bearerToken = token))
            pageCount += 1
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            val pageItems = response[itemKey].arr()
            val remaining = (SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS - items.size).coerceAtLeast(0)
            items += pageItems.take(remaining)
            if (pageItems.size > remaining) truncated = true
            val pageToken = response["nextPageToken"].str()?.takeIf(String::isNotEmpty)
            if (pageToken == null) {
                nextUrl = null
                continue
            }
            if (pageCount >= SiteDetailSupport.MAXIMUM_PAGINATION_PAGES ||
                items.size >= SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS
            ) {
                truncated = true
                break
            }
            if (!seenTokens.add(pageToken)) {
                throw SiteServiceException.detailDecoding("Google Analytics returned a repeated data-stream page token.")
            }
            val baseQuery = initialUrl.rawQuery?.split('&')
                ?.filter { it.isNotEmpty() && !it.startsWith("pageToken=") }
                .orEmpty()
            val base = initialUrl.toASCIIString().substringBefore('?')
            val query = baseQuery + SiteApiSupport.encodeQuery(listOf("pageToken" to pageToken))
            nextUrl = URI("$base?${query.joinToString("&")}")
        }
        return SitePagedCollection(pages, items, truncated)
    }

    /**
     * Follows `pagination.next` / `links.next` only while the link stays on the same HTTPS origin
     * without credentials (iOS `fetchSameOriginLinkedPages`).
     */
    suspend fun sameOriginLinkedPages(
        context: SiteApiContext,
        initialUrl: URI,
        token: SecretValue,
        itemKey: String,
    ): SitePagedCollection {
        var nextUrl: URI? = initialUrl
        val pages = ArrayList<ProviderJsonValue>()
        val items = ArrayList<ProviderJsonValue>()
        val visited = HashSet<String>()
        var pageCount = 0
        var truncated = false
        while (true) {
            val url = nextUrl ?: break
            currentCoroutineContext().ensureActive()
            if (!visited.add(url.toASCIIString())) {
                throw SiteServiceException.detailDecoding("The provider returned a repeated pagination link.")
            }
            val response = context.detailJson(SiteHttpRequest(url, bearerToken = token))
            pageCount += 1
            if (pages.size < SiteDetailSupport.MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT) pages += response
            val pageItems = response[itemKey].arr()
            val remaining = (SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS - items.size).coerceAtLeast(0)
            items += pageItems.take(remaining)
            if (pageItems.size > remaining) truncated = true
            val link = (response["pagination"]?.get("next").str() ?: response["links"]?.get("next").str())
                ?.takeIf(String::isNotEmpty)
            if (link == null) {
                nextUrl = null
                continue
            }
            if (items.size >= SiteDetailSupport.MAXIMUM_PAGED_COLLECTION_ROWS ||
                pageCount >= SiteDetailSupport.MAXIMUM_PAGINATION_PAGES
            ) {
                truncated = true
                break
            }
            nextUrl = sameOrigin(url, link, initialUrl)
                ?: throw SiteServiceException.invalidConfiguration("The provider returned an unsafe pagination URL.")
        }
        return SitePagedCollection(pages, items, truncated)
    }

    fun sameOrigin(current: URI, link: String, origin: URI): URI? {
        val candidate = runCatching { current.resolve(URI(link)) }.getOrNull() ?: return null
        val sameOrigin = candidate.scheme.equals("https", ignoreCase = true) &&
            candidate.host.equals(origin.host, ignoreCase = true) &&
            effectivePort(candidate) == effectivePort(origin) &&
            candidate.rawUserInfo == null
        return candidate.takeIf { sameOrigin }
    }

    private fun effectivePort(uri: URI): Int = if (uri.port >= 0) uri.port else 443
}
