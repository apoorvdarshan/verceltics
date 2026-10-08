package com.apoorvdarshan.verceltics.data.hosting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** iOS `RailwayPaginationGuard`: requires, de-duplicates and bounds continuation cursors. */
class RailwayPaginationGuard(private val maximumPages: Int = DEFAULT_MAXIMUM_PAGES) {
    private var pageCount = 0
    private val seenCursors = mutableSetOf<String>()

    init {
        require(maximumPages > 0) { "Railway pagination needs at least one page." }
    }

    /** Returns the next cursor, or null when the connection is complete. */
    fun continuation(hasNextPage: Boolean, endCursor: String?): String? {
        pageCount += 1
        if (pageCount > maximumPages) throw paginationFailure("Railway pagination exceeded $maximumPages pages.")
        if (!hasNextPage) return null
        if (endCursor.isNullOrEmpty()) {
            throw paginationFailure("Railway pagination indicated another page without a cursor.")
        }
        if (!seenCursors.add(endCursor)) throw paginationFailure("Railway pagination repeated a cursor.")
        return endCursor
    }

    companion object {
        const val DEFAULT_MAXIMUM_PAGES: Int = 200
    }
}

internal const val DEFAULT_MAXIMUM_PAGES = 200

internal fun paginationFailure(message: String): HostingApiException =
    HostingApiException(HostingFailure(HostingFailureKind.INVALID_RESPONSE, message))

/** Accumulates page items, dropping exact duplicates as iOS does with `jsonFingerprint`. */
private class UniqueItems {
    private val seen = mutableSetOf<String>()
    val items = mutableListOf<JsonValue>()

    /** Returns how many previously unseen items were added. */
    fun addAll(values: List<JsonValue>): Int {
        var added = 0
        values.forEach { value ->
            if (seen.add(jsonFingerprint(value))) {
                items += value
                added += 1
            }
        }
        return added
    }
}

/** iOS `collectNumberedPages` (Netlify-style `page`/`per_page`, DigitalOcean). */
internal suspend fun collectNumberedPages(
    pageSize: Int,
    maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
    fetchPage: suspend (page: Int) -> List<JsonValue>,
): List<JsonValue> {
    val unique = UniqueItems()
    for (page in 1..maximumPages) {
        currentCoroutineContext().ensureActive()
        val items = fetchPage(page)
        if (items.isEmpty()) return unique.items
        if (unique.addAll(items) == 0) {
            throw paginationFailure("Pagination repeated a page without returning new items.")
        }
        if (items.size < pageSize) return unique.items
    }
    throw paginationFailure("Pagination exceeded $maximumPages pages.")
}

/**
 * iOS `fetchTokenPages` (Firebase `pageToken`/`nextPageToken`, Amplify `nextToken`). The
 * continuation token is re-sent as a query parameter on the original request.
 */
internal suspend fun HostingRequestClient.fetchTokenPages(
    path: List<String>,
    query: List<Pair<String, String>>,
    itemKey: String,
    tokenQueryName: String,
    operation: String,
    maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
): List<JsonValue> {
    val responseTokenName = if (tokenQueryName == "pageToken") "nextPageToken" else tokenQueryName
    val unique = UniqueItems()
    val seenTokens = mutableSetOf<String>()
    var token: String? = null
    repeat(maximumPages) {
        currentCoroutineContext().ensureActive()
        val pageQuery = if (token == null) query else query + (tokenQueryName to token)
        val root = json(path = path, query = pageQuery, operation = operation).asObject()
        unique.addAll(root[itemKey].asArray())
        val next = root.string(responseTokenName)?.takeIf(String::isNotEmpty) ?: return unique.items
        if (!seenTokens.add(next)) throw paginationFailure("Pagination repeated a continuation token.")
        token = next
    }
    throw paginationFailure("Pagination exceeded $maximumPages pages.")
}

/** iOS `fetchRenderCursorPages`: the cursor lives on the last item of each page. */
internal suspend fun HostingRequestClient.fetchRenderCursorPages(
    path: List<String>,
    query: List<Pair<String, String>>,
    operation: String,
    maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
): List<JsonValue> {
    val unique = UniqueItems()
    val seenCursors = mutableSetOf<String>()
    var cursor: String? = null
    repeat(maximumPages) {
        currentCoroutineContext().ensureActive()
        val pageQuery = if (cursor == null) query else query + ("cursor" to cursor)
        val items = json(path = path, query = pageQuery, operation = operation).asArray()
        if (items.isEmpty()) return unique.items
        unique.addAll(items)
        val next = items.last().asObject().string("cursor")?.takeIf(String::isNotEmpty) ?: return unique.items
        if (!seenCursors.add(next)) throw paginationFailure("Render pagination repeated a cursor.")
        cursor = next
    }
    throw paginationFailure("Render pagination exceeded $maximumPages pages.")
}

/** iOS `fetchHerokuRangePages`: `Range` request header and `Next-Range` response header. */
internal suspend fun HostingRequestClient.fetchHerokuRangePages(
    path: List<String>,
    operation: String,
    maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
): List<JsonValue> {
    val unique = UniqueItems()
    val seenRanges = mutableSetOf<String>()
    var range = HEROKU_INITIAL_RANGE
    repeat(maximumPages) {
        currentCoroutineContext().ensureActive()
        if (!seenRanges.add(range)) throw paginationFailure("Heroku pagination repeated a range.")
        val page = jsonWithHeaders(path = path, headers = mapOf("Range" to range), operation = operation)
        unique.addAll(page.value.asArray())
        val next = page.header("Next-Range")?.trim()
        if (next.isNullOrEmpty()) return unique.items
        if (next.length > MAX_RANGE_CHARACTERS || next.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw paginationFailure("Heroku returned an invalid pagination range.")
        }
        range = next
    }
    throw paginationFailure("Heroku pagination exceeded $maximumPages pages.")
}

internal const val HEROKU_INITIAL_RANGE = "id ..; max=200; order=asc"
private const val MAX_RANGE_CHARACTERS = 512

/** iOS `collectRailwayConnection`: GraphQL `edges { node }` + `pageInfo` connections. */
internal suspend fun collectRailwayConnection(
    maximumPages: Int = DEFAULT_MAXIMUM_PAGES,
    fetchPage: suspend (cursor: String?) -> JsonObject,
): List<JsonObject> {
    val guard = RailwayPaginationGuard(maximumPages)
    val seen = mutableSetOf<String>()
    val result = mutableListOf<JsonObject>()
    var cursor: String? = null
    repeat(maximumPages) {
        currentCoroutineContext().ensureActive()
        val connection = fetchPage(cursor)
        connection["edges"].asArray().forEach { edge ->
            val node = edge.asObject()["node"].asObject()
            if (seen.add(jsonFingerprint(node))) result += node
        }
        val pageInfo = connection["pageInfo"].asObject()
        cursor = guard.continuation(
            hasNextPage = pageInfo.bool("hasNextPage") == true,
            endCursor = pageInfo.string("endCursor"),
        ) ?: return result
    }
    // The guard is authoritative; reaching this point is defensive.
    throw paginationFailure("Railway pagination exceeded $maximumPages pages.")
}

/**
 * iOS `fetchLiveProviderValues`: per-resource live state with a small concurrency window. A
 * provider may deny one app; that app's value becomes null ("Unknown") instead of failing the
 * whole dashboard. Cancellation always propagates.
 */
internal suspend fun <T> fetchLiveValues(
    identifiers: List<String>,
    maximumConcurrent: Int,
    fetch: suspend (String) -> T,
): List<T?> {
    if (identifiers.isEmpty()) return emptyList()
    val semaphore = Semaphore(maximumConcurrent.coerceIn(1, identifiers.size))
    return coroutineScope {
        identifiers.map { identifier ->
            async {
                semaphore.withPermit {
                    try {
                        fetch(identifier)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll()
    }
}
