package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

/** A request captured by [FakeCloudflareRestTransport], with the credential the client chose. */
class RecordedCloudflareRequest(
    val request: CloudflareRestRequest,
    val credential: String,
) {
    val method: CloudflareHttpMethod get() = request.method
    val path: String get() = request.apiPath
    val query: List<Pair<String, String>> get() = request.query
    val bodyText: String? get() = request.bodyText()
    val bodyJson: ProviderJsonValue? get() = request.bodyText()?.let { ProviderJsonParser.parse(it) }
    val bearer: String
        get() = when (val auth = request.auth) {
            CloudflareRequestAuth.AccountToken -> credential
            is CloudflareRequestAuth.PagesUploadToken -> auth.jwt.use { it }
        }

    override fun toString(): String = "${request.method} ${request.apiPath}"
}

/**
 * Scriptable in-memory transport for Cloudflare operations tests. Responses are matched by
 * `METHOD /path` (path relative to `/client/v4`, percent-encoded) and consumed in order; an unmatched
 * request fails the test loudly.
 */
class FakeCloudflareRestTransport : CloudflareRestTransport {
    val requests = mutableListOf<RecordedCloudflareRequest>()
    private val scripted = LinkedHashMap<String, ArrayDeque<CloudflareRestResponse>>()
    private val fallbacks = LinkedHashMap<String, CloudflareRestResponse>()

    /** Queues a one-shot response for `METHOD /path`. */
    fun enqueue(method: CloudflareHttpMethod, path: String, response: CloudflareRestResponse) {
        scripted.getOrPut("$method $path") { ArrayDeque() }.addLast(response)
    }

    /** A response returned every time for `METHOD /path` once queued ones are used up. */
    fun always(method: CloudflareHttpMethod, path: String, response: CloudflareRestResponse) {
        fallbacks["$method $path"] = response
    }

    fun enqueueJson(method: CloudflareHttpMethod, path: String, json: String, status: Int = 200) =
        enqueue(method, path, response(json, status))

    fun alwaysJson(method: CloudflareHttpMethod, path: String, json: String, status: Int = 200) =
        always(method, path, response(json, status))

    /** Requests that change Cloudflare state (read-only `POST /graphql` analytics queries excluded). */
    fun mutations(): List<RecordedCloudflareRequest> =
        requests.filter { it.method.isMutation && !CloudflareRestClient.isReadOnlyGraphQL(it.request) }

    override fun newCall(request: CloudflareRestRequest, credential: SecretValue): CancelableCall<CloudflareRestResponse> {
        val token = credential.use { it }
        return object : CancelableCall<CloudflareRestResponse> {
            override fun execute(): CloudflareRestResponse {
                check(CloudflareRestRequest.isPinnedCloudflareApiUri(request.uri())) { "Unpinned Cloudflare request." }
                requests += RecordedCloudflareRequest(request, token)
                val key = "${request.method} ${request.apiPath}"
                return scripted[key]?.removeFirstOrNull() ?: fallbacks[key]
                    ?: throw AssertionError("No scripted Cloudflare response for $key")
            }

            override fun cancel() = Unit
        }
    }

    companion object {
        const val TOKEN: String = "test-cloudflare-token"

        fun response(json: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
            CloudflareRestResponse(status, headers, json.toByteArray(StandardCharsets.UTF_8))

        fun raw(bytes: ByteArray, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
            CloudflareRestResponse(status, headers, bytes)

        /** `{"success":true,"errors":[],"messages":[],"result":<result>}` plus optional result_info. */
        fun envelope(result: String, resultInfo: String? = null): String =
            "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":$result" +
                (resultInfo?.let { ",\"result_info\":$it" } ?: "") + "}"

        fun failure(message: String, code: Int = 1000): String =
            "{\"success\":false,\"errors\":[{\"code\":$code,\"message\":\"$message\"}],\"messages\":[],\"result\":null}"

        /** A client that runs calls inline on the calling thread with a fixed test token. */
        fun client(transport: FakeCloudflareRestTransport): CloudflareRestClient = CloudflareRestClient(
            credentialProvider = { SecretValue.of(TOKEN) },
            executor = Executor { it.run() },
            transport = transport,
        )
    }
}
