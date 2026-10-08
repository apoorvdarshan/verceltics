package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Authenticated Cloudflare tooling calls: the raw API explorer, GraphQL Analytics and account
 * operations. Every request is built by [CloudflareExplorerRequestBuilder] and pinned to
 * `https://api.cloudflare.com/client/v4`.
 */
class CloudflareToolsApi(
    private val transport: CloudflareToolsTransport = SecureCloudflareToolsTransport(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /**
     * iOS `rawRequest`: any status code is returned as a response for the viewer. Invalid drafts
     * and unconfirmed writes throw [CloudflareToolsException] before anything is sent.
     */
    fun newRawRequestCall(
        token: SecretValue,
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray? = null,
    ): CancelableCall<CloudflareRawResponse> {
        val request = CloudflareExplorerRequestBuilder.build(draft, confirmation, attachedBody)
        return SequentialCloudflareCall {
            val started = nanoTime()
            val response = child(transport.newCall(request, token))
            val elapsed = ((nanoTime() - started) / 1_000_000L).coerceAtLeast(0)
            response.toRawResponse(elapsed)
        }
    }

    /** iOS `rawGraphQLQuery`: HTTP failures throw token-scope-aware errors. */
    fun newGraphQLCall(
        token: SecretValue,
        query: String,
        variables: Map<String, ProviderJsonValue> = emptyMap(),
        requiredPermission: String? = "Analytics Read",
    ): CancelableCall<CloudflareRawResponse> {
        if (query.isBlank()) {
            throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_REQUEST, "Enter a GraphQL query.")
        }
        val body = ProviderJsonWriter.writeBytes(
            ProviderJsonValue.Obj(
                linkedMapOf(
                    "query" to ProviderJsonValue.Str(query),
                    "variables" to ProviderJsonValue.Obj(variables),
                ),
            ),
        )
        val request = CloudflareToolsHttpRequest(
            method = CloudflareHttpMethod.POST,
            uri = CloudflareExplorerRequestBuilder.buildUri("/graphql"),
            headers = emptyMap(),
            body = body,
            contentType = "application/json",
        )
        return SequentialCloudflareCall {
            val response = child(transport.newCall(request, token)).toRawResponse(null)
            if (!response.isSuccess) {
                throw CloudflareToolsErrors.forStatus(
                    statusCode = response.statusCode,
                    providerMessages = CloudflareToolsErrors.providerMessages(response),
                    subject = "Cloudflare GraphQL Analytics",
                    requiredPermission = requiredPermission,
                )
            }
            response
        }
    }

    fun newAccountDetailCall(token: SecretValue, accountId: String): CancelableCall<CloudflareAccountDetail> {
        val request = getRequest("/accounts/${segment(accountId)}")
        return SequentialCloudflareCall {
            val response = child(transport.newCall(request, token)).toRawResponse(null)
            val result = envelopeResult(response, "this account", ACCOUNT_SETTINGS_READ)
            CloudflareAccountOperationsParser.accountDetail(result)
        }
    }

    fun newMembersCall(token: SecretValue, accountId: String): CancelableCall<List<CloudflareAccountMember>> =
        allPagesCall(token, "/accounts/${segment(accountId)}/members", "account members") {
            CloudflareAccountOperationsParser.member(it)
        }

    fun newRolesCall(token: SecretValue, accountId: String): CancelableCall<List<CloudflareAccountRole>> =
        allPagesCall(token, "/accounts/${segment(accountId)}/roles", "account roles") {
            CloudflareAccountOperationsParser.role(it)
        }

    /** iOS `fetchAccountAuditEvents`: newest-first, bounded to [limit] events. */
    fun newAuditEventsCall(
        token: SecretValue,
        accountId: String,
        since: Instant,
        before: Instant,
        limit: Int = 50,
    ): CancelableCall<List<CloudflareAccountAuditEvent>> {
        if (!since.isBefore(before)) {
            throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_REQUEST,
                "The audit-log start date must be before the end date.",
            )
        }
        val request = getRequest(
            path = "/accounts/${segment(accountId)}/logs/audit",
            query = listOf(
                "before" to isoSeconds(before),
                "direction" to "desc",
                "limit" to limit.coerceIn(1, 1_000).toString(),
                "since" to isoSeconds(since),
            ),
        )
        return SequentialCloudflareCall {
            val response = child(transport.newCall(request, token)).toRawResponse(null)
            val result = envelopeResult(response, "the account audit log", ACCOUNT_SETTINGS_READ)
            result.arrayValue?.map(CloudflareAccountOperationsParser::auditEvent)
                ?: throw CloudflareToolsException(
                    CloudflareToolsFailureKind.INVALID_RESPONSE,
                    "Cloudflare returned data the app could not parse.",
                )
        }
    }

    /** iOS `cloudflareOperationsAllPages`: follows `total_pages` with a [CloudflarePaginationGuard]. */
    private fun <T> allPagesCall(
        token: SecretValue,
        path: String,
        subject: String,
        perPage: Int = 50,
        transform: (ProviderJsonValue) -> T,
    ): CancelableCall<List<T>> = SequentialCloudflareCall {
        val items = ArrayList<T>()
        val guard = CloudflarePaginationGuard()
        var page = 1
        while (true) {
            val request = getRequest(path, listOf("page" to page.toString(), "per_page" to perPage.toString()))
            val response = child(transport.newCall(request, token)).toRawResponse(null)
            val envelope = envelope(response, subject, ACCOUNT_SETTINGS_READ)
            val batch = envelope.result?.arrayValue.orEmpty()
            guard.record(batch.size, response.bodyBytes().contentHashCode())
            batch.forEach { items += transform(it) }
            val totalPages = (envelope.resultInfo?.get("total_pages") as? ProviderJsonValue.Num)?.decimal?.toInt()
            if (totalPages == null || page >= totalPages) break
            page += 1
        }
        items
    }

    private fun getRequest(path: String, query: List<Pair<String, String>> = emptyList()) = CloudflareToolsHttpRequest(
        method = CloudflareHttpMethod.GET,
        uri = CloudflareExplorerRequestBuilder.buildUri(path, query),
        headers = emptyMap(),
        body = null,
        contentType = null,
    )

    private class Envelope(val result: ProviderJsonValue?, val resultInfo: ProviderJsonValue?)

    /** iOS `cloudflareOperationsDecode`. */
    private fun envelope(response: CloudflareRawResponse, subject: String, permission: String?): Envelope {
        if (!response.isSuccess) {
            throw CloudflareToolsErrors.forStatus(
                response.statusCode,
                CloudflareToolsErrors.providerMessages(response),
                subject,
                permission,
            )
        }
        val root = response.parsedJson() as? ProviderJsonValue.Obj
            ?: throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "Cloudflare returned data the app could not parse.",
            )
        val success = (root["success"] as? ProviderJsonValue.Bool)?.value ?: false
        if (!success) {
            val messages = CloudflareToolsErrors.providerMessages(response)
            throw CloudflareToolsException(
                CloudflareToolsFailureKind.PROVIDER,
                if (messages.isEmpty()) {
                    "Cloudflare request failed (${response.statusCode})."
                } else {
                    "Cloudflare request failed (${response.statusCode}): ${messages.joinToString("\n")}"
                },
                response.statusCode,
            )
        }
        return Envelope(root["result"]?.takeUnless { it.isNull }, root["result_info"])
    }

    private fun envelopeResult(response: CloudflareRawResponse, subject: String, permission: String?): ProviderJsonValue =
        envelope(response, subject, permission).result ?: throw CloudflareToolsException(
            CloudflareToolsFailureKind.INVALID_RESPONSE,
            "Cloudflare returned a successful response without a result.",
        )

    private fun segment(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 256) {
            throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_REQUEST, "A Cloudflare id is required.")
        }
        return CloudflareExplorerRequestBuilder.encodeQueryComponent(trimmed)
    }

    private fun isoSeconds(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    companion object {
        const val ACCOUNT_SETTINGS_READ = "Account Settings Read"
    }
}

/** Token-scope-aware, credential-free failure messages for the Cloudflare tools. */
object CloudflareToolsErrors {
    private const val MAXIMUM_MESSAGE_CHARACTERS = 300
    private const val MAXIMUM_MESSAGES = 3

    fun forStatus(
        statusCode: Int,
        providerMessages: List<String>,
        subject: String,
        requiredPermission: String?,
    ): CloudflareToolsException {
        val detail = providerMessages.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\nCloudflare: ").orEmpty()
        return when (statusCode) {
            401 -> CloudflareToolsException(
                CloudflareToolsFailureKind.AUTHENTICATION,
                "Cloudflare rejected this API token. Reconnect Cloudflare with an active token.",
                statusCode,
            )
            403 -> CloudflareToolsException(
                CloudflareToolsFailureKind.AUTHORIZATION,
                "This API token can’t access $subject." + (
                    requiredPermission?.let { " Add the “$it” permission to the token, then try again." }
                        ?: " Check the token’s permissions and resources."
                    ) + detail,
                statusCode,
            )
            404 -> CloudflareToolsException(
                CloudflareToolsFailureKind.NOT_FOUND,
                "Cloudflare could not find $subject.$detail",
                statusCode,
            )
            429 -> CloudflareToolsException(
                CloudflareToolsFailureKind.RATE_LIMITED,
                "Cloudflare is rate limiting requests. Please try again shortly.",
                statusCode,
            )
            else -> CloudflareToolsException(
                CloudflareToolsFailureKind.PROVIDER,
                if (providerMessages.isEmpty()) {
                    "Cloudflare request failed ($statusCode)."
                } else {
                    "Cloudflare request failed ($statusCode): ${providerMessages.joinToString("\n")}"
                },
                statusCode,
            )
        }
    }

    /** `errors[].message` (with code), sanitized and bounded. */
    fun providerMessages(response: CloudflareRawResponse): List<String> {
        val root = response.parsedJson() ?: return emptyList()
        val errors = root["errors"]?.arrayValue.orEmpty()
        return errors.mapNotNull { error ->
            val message = (error["message"] as? ProviderJsonValue.Str)?.value?.let(::sanitize)
                ?: return@mapNotNull null
            if (message.isEmpty()) return@mapNotNull null
            val code = (error["code"] as? ProviderJsonValue.Num)?.text
            if (code == null) message else "$message [code $code]"
        }.take(MAXIMUM_MESSAGES)
    }

    /**
     * Guidance shown above an explorer response when Cloudflare refused the token, naming the
     * permissions Cloudflare's schema lists for the operation when they are known.
     */
    fun explorerHint(statusCode: Int, permissions: List<String>): String? = when (statusCode) {
        401 -> "Cloudflare rejected this API token. Reconnect Cloudflare with an active token."
        403 -> buildString {
            append("This API token is missing a permission or resource for this endpoint.")
            if (permissions.isNotEmpty()) {
                val shown = permissions.take(4)
                append(" Cloudflare accepts any of: ")
                append(shown.joinToString(", "))
                if (permissions.size > shown.size) append(" and ${permissions.size - shown.size} more")
                append('.')
            } else {
                append(" Add the matching permission to the token, then try again.")
            }
        }
        else -> null
    }

    /** Maps any tools failure to a safe sentence for the UI. */
    fun message(error: Throwable): String = when (error) {
        is CloudflareToolsException -> error.message ?: "Cloudflare could not complete this request."
        is ResponseTooLargeException -> "Cloudflare returned more data than the app can process safely."
        is UnsafeRedirectException -> "Cloudflare returned an unsafe redirect, so the request stopped."
        is IOException -> error.message?.takeIf { it.startsWith("This device cannot send") }
            ?: "Cloudflare could not be reached. Check your connection and try again."
        is IllegalArgumentException -> "The Cloudflare request is invalid."
        else -> "Cloudflare could not complete this request."
    }

    private fun sanitize(value: String): String = value
        .filterNot { it.isISOControl() && it != '\n' }
        .trim()
        .let { if (it.length > MAXIMUM_MESSAGE_CHARACTERS) it.take(MAXIMUM_MESSAGE_CHARACTERS) + "…" else it }
}

internal fun HttpResponse.toRawResponse(elapsedMillis: Long?): CloudflareRawResponse {
    val bytes = takeBody()
    return try {
        CloudflareRawResponse(
            statusCode = statusCode,
            headers = headers.mapValues { (_, values) -> values.joinToString(", ") },
            body = bytes,
            elapsedMillis = elapsedMillis,
        )
    } finally {
        bytes.fill(0)
    }
}

/** A cancellable call that runs child calls one at a time, forwarding cancellation to the active one. */
internal class SequentialCloudflareCall<T>(
    private val block: SequentialCloudflareCall<T>.() -> T,
) : CancelableCall<T> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeChild = AtomicReference<CancelableCall<*>?>()

    override fun execute(): T {
        check(started.compareAndSet(false, true)) { "A Cloudflare call can only execute once." }
        throwIfCancelled()
        return block()
    }

    fun <V> child(call: CancelableCall<V>): V {
        throwIfCancelled()
        activeChild.set(call)
        if (cancelled.get()) {
            activeChild.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("The Cloudflare request was cancelled.")
        }
        return try {
            call.execute().also { throwIfCancelled() }
        } finally {
            activeChild.compareAndSet(call, null)
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeChild.getAndSet(null)?.cancel()
    }

    private fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("The Cloudflare request was cancelled.")
    }
}
