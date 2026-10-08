package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import com.apoorvdarshan.verceltics.data.network.awaitProviderCall
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A parsed Cloudflare v4 envelope (`success`, `result`, `errors`, `messages`, `result_info`). */
class CloudflareEnvelope(
    val success: Boolean,
    val result: ProviderJsonValue?,
    val errors: List<CloudflareApiIssue>,
    val messages: List<CloudflareApiIssue>,
    val resultInfo: CloudflareResultInfo?,
)

data class CloudflareResultInfo(
    val page: Int?,
    val perPage: Int?,
    val count: Int?,
    val totalCount: Int?,
    val totalPages: Int?,
    val cursor: String?,
)

/**
 * Authenticated Cloudflare client shared by every operations screen.
 *
 * Credentials are resolved per request from encrypted storage, so a disconnect takes effect
 * immediately and the token never enters UI state. Calls are cancellable and run on [executor].
 */
class CloudflareRestClient(
    private val credentialProvider: suspend () -> SecretValue,
    private val executor: Executor,
    private val transport: CloudflareRestTransport = HttpsCloudflareRestTransport(),
) {
    private val mutationEvents = MutableSharedFlow<CloudflareMutationEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Successful mutations, for screens that reconcile after another screen changes data. */
    val mutations: SharedFlow<CloudflareMutationEvent> = mutationEvents.asSharedFlow()

    /** Sends [request] and returns the response for any HTTP status. Transport failures are mapped. */
    suspend fun execute(request: CloudflareRestRequest): CloudflareRestResponse {
        val credential = when (request.auth) {
            CloudflareRequestAuth.AccountToken -> credentialProvider()
            is CloudflareRequestAuth.PagesUploadToken -> PLACEHOLDER_CREDENTIAL
        }
        val response = try {
            transport.newCall(request, credential).awaitProviderCall(executor)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudflareOperationException) {
            throw error
        } catch (error: ResponseTooLargeException) {
            throw CloudflareOperationException.network("Cloudflare returned more data than the app can safely load.", error)
        } catch (error: UnsafeRedirectException) {
            throw CloudflareOperationException.network("Cloudflare returned an unsafe redirect, so the request stopped.", error)
        } catch (error: SocketTimeoutException) {
            throw CloudflareOperationException.network("Cloudflare took too long to respond. Try again.", error)
        } catch (error: IOException) {
            throw CloudflareOperationException.network(
                "Cloudflare could not be reached. Check your connection and try again.",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw CloudflareOperationException.invalidRequest(error.message ?: "The Cloudflare request is invalid.")
        } catch (error: IllegalStateException) {
            throw CloudflareOperationException.invalidRequest(error.message ?: "The Cloudflare request is invalid.")
        }
        if (request.method.isMutation && response.isSuccessful && !isReadOnlyGraphQL(request)) {
            mutationEvents.tryEmit(CloudflareMutationEvent(request.method, request.apiPath))
        }
        return response
    }

    /** Sends [request], requires a 2xx status, and returns the raw response (non-JSON endpoints). */
    suspend fun executeSuccessful(request: CloudflareRestRequest): CloudflareRestResponse =
        execute(request).also(::throwForHttpFailure)

    /** Sends [request] and returns its successful envelope. */
    suspend fun envelope(request: CloudflareRestRequest): CloudflareEnvelope {
        val response = execute(request)
        throwForHttpFailure(response)
        val envelope = parseEnvelope(response)
        if (!envelope.success) {
            if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
            throw CloudflareOperationException.requestFailed(
                response.statusCode,
                "Cloudflare reported an unsuccessful request.",
            )
        }
        return envelope
    }

    /** Sends [request] and returns the envelope's non-null `result`. */
    suspend fun result(request: CloudflareRestRequest): ProviderJsonValue =
        envelope(request).result?.takeUnless { it.isNull }
            ?: throw CloudflareOperationException.decoding()

    /** Sends a mutation whose result payload is not needed. */
    suspend fun send(request: CloudflareRestRequest) {
        envelope(request)
    }

    /**
     * Walks a `page`/`per_page` collection with the iOS pagination guard: at most 500 pages and
     * 100,000 items, and loading stops if Cloudflare repeats a page.
     */
    suspend fun allPages(
        pathSegments: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        perPage: Int = 50,
        headers: Map<String, String> = emptyMap(),
        stopWhenShortPage: Boolean = true,
    ): List<ProviderJsonValue> {
        require(perPage in 1..1_000) { "Invalid Cloudflare page size." }
        val guard = CloudflarePaginationGuard()
        val items = mutableListOf<ProviderJsonValue>()
        var page = 1
        val baseQuery = query.filterNot { it.first == "page" || it.first == "per_page" }
        while (true) {
            val envelope = envelope(
                CloudflareRestRequest(
                    method = CloudflareHttpMethod.GET,
                    pathSegments = pathSegments,
                    query = baseQuery + listOf("page" to page.toString(), "per_page" to perPage.toString()),
                    headers = headers,
                ),
            )
            val batch = envelope.result?.arrayValue ?: emptyList()
            guard.record(batch.size, if (batch.isEmpty()) null else batch.hashCode())
            items += batch
            val totalPages = envelope.resultInfo?.totalPages
            if (totalPages != null) {
                if (page >= totalPages) break
            } else if (!stopWhenShortPage || batch.size < perPage) {
                break
            }
            page += 1
        }
        return items
    }

    /**
     * Walks a cursor collection (KV keys, R2 objects): follows `result_info.cursor` until Cloudflare
     * stops returning one, under the same pagination guard.
     */
    suspend fun allCursorPages(
        pathSegments: List<String>,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        cursorParameter: String = "cursor",
        maximumItems: Int = CloudflarePaginationGuard.MAXIMUM_ITEMS,
        extractItems: (CloudflareEnvelope) -> List<ProviderJsonValue> = { it.result?.arrayValue ?: emptyList() },
    ): CloudflareCursorPage {
        val guard = CloudflarePaginationGuard()
        val items = mutableListOf<ProviderJsonValue>()
        var cursor: String? = null
        val baseQuery = query.filterNot { it.first == cursorParameter }
        while (true) {
            val envelope = envelope(
                CloudflareRestRequest(
                    method = CloudflareHttpMethod.GET,
                    pathSegments = pathSegments,
                    query = baseQuery + listOfNotNull(cursor?.let { cursorParameter to it }),
                    headers = headers,
                ),
            )
            val batch = extractItems(envelope)
            guard.record(batch.size, if (batch.isEmpty()) null else batch.hashCode())
            items += batch
            val next = envelope.resultInfo?.cursor?.takeIf(String::isNotEmpty)
            if (items.size >= maximumItems) {
                return CloudflareCursorPage(items.take(maximumItems), nextCursor = next ?: cursor, truncated = true)
            }
            if (next == null || next == cursor || batch.isEmpty()) break
            cursor = next
        }
        return CloudflareCursorPage(items, nextCursor = null, truncated = false)
    }

    companion object {
        /** Pages upload JWT requests never read the account token; the transport ignores this value. */
        private val PLACEHOLDER_CREDENTIAL = SecretValue.of("unused-account-credential")

        private val GRAPHQL_MUTATION = Regex("\\bmutation\\b", RegexOption.IGNORE_CASE)

        /**
         * iOS `isReadOnlyGraphQLBody`: a `POST /graphql` whose query has no `mutation` keyword only
         * reads analytics, so it must not tell other screens that data changed.
         */
        fun isReadOnlyGraphQL(request: CloudflareRestRequest): Boolean {
            if (request.method != CloudflareHttpMethod.POST || request.apiPath != "/graphql") return false
            val query = runCatching { ProviderJsonParser.parse(request.bodyText() ?: return false)["query"]?.stringValue }
                .getOrNull() ?: return false
            return !GRAPHQL_MUTATION.containsMatchIn(query)
        }

        /** iOS `throwForHTTPFailure`: 401 → credentials, 403 → forbidden, everything else → request failed. */
        fun throwForHttpFailure(response: CloudflareRestResponse) {
            if (response.isSuccessful) return
            val issues = runCatching { parseIssues(ProviderJsonParser.parse(response.bodyBytes())["errors"]) }
                .getOrDefault(emptyList())
            val message = issues.map(CloudflareApiIssue::message).filter(String::isNotEmpty).joinToString("\n")
            throw when (response.statusCode) {
                401 -> CloudflareOperationException.invalidCredentials()
                403 -> CloudflareOperationException.forbidden(message)
                else -> CloudflareOperationException.requestFailed(response.statusCode, message)
            }
        }

        fun parseEnvelope(response: CloudflareRestResponse): CloudflareEnvelope {
            val root = try {
                ProviderJsonParser.parse(response.bodyBytes())
            } catch (error: Exception) {
                throw CloudflareOperationException.decoding(error)
            }
            if (root !is ProviderJsonValue.Obj) throw CloudflareOperationException.decoding()
            val info = root["result_info"]?.takeIf { it is ProviderJsonValue.Obj }
            return CloudflareEnvelope(
                success = root["success"]?.booleanValue ?: false,
                result = root["result"],
                errors = parseIssues(root["errors"]),
                messages = parseIssues(root["messages"]),
                resultInfo = info?.let {
                    CloudflareResultInfo(
                        page = it.int("page"),
                        perPage = it.int("per_page"),
                        count = it.int("count"),
                        totalCount = it.int("total_count"),
                        totalPages = it.int("total_pages"),
                        cursor = it.str("cursor") ?: it["cursors"].str("after"),
                    )
                },
            )
        }

        fun parseIssues(value: ProviderJsonValue?): List<CloudflareApiIssue> =
            value?.arrayValue.orEmpty().mapNotNull { issue ->
                when (issue) {
                    is ProviderJsonValue.Obj -> CloudflareApiIssue(
                        code = issue.int("code"),
                        message = issue.str("message") ?: "Cloudflare rejected the request.",
                        pointer = issue["source"].str("pointer"),
                    )
                    is ProviderJsonValue.Str -> CloudflareApiIssue(null, issue.value)
                    else -> null
                }
            }
    }
}

data class CloudflareCursorPage(
    val items: List<ProviderJsonValue>,
    val nextCursor: String?,
    val truncated: Boolean,
)

/**
 * iOS `CloudflarePaginationGuard`: bounds every collection walk so a malformed or repeating response
 * cannot keep the device issuing requests or growing a list indefinitely.
 */
class CloudflarePaginationGuard {
    private var pages = 0
    private var items = 0
    private val signatures = HashSet<Int>()

    fun record(batchCount: Int, signature: Int?) {
        pages += 1
        items += batchCount
        if (pages > MAXIMUM_PAGES || items > MAXIMUM_ITEMS) {
            throw CloudflareOperationException.invalidRequest(
                "Cloudflare returned too many paginated results. Narrow the request and try again.",
            )
        }
        if (signature != null && batchCount > 0 && !signatures.add(signature)) {
            throw CloudflareOperationException.invalidRequest(
                "Cloudflare repeated a results page, so loading stopped safely.",
            )
        }
    }

    companion object {
        const val MAXIMUM_PAGES: Int = 500
        const val MAXIMUM_ITEMS: Int = 100_000
    }
}
