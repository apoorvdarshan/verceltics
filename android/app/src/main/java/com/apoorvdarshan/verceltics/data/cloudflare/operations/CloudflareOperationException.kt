package com.apoorvdarshan.verceltics.data.cloudflare.operations

/** One entry from a Cloudflare envelope's `errors` or `messages` array. */
data class CloudflareApiIssue(
    val code: Int?,
    val message: String,
    val pointer: String? = null,
) {
    /** iOS `CloudflareAPIError.api` formatting: `message [code 123 · /pointer]`. */
    val displayText: String
        get() {
            val context = buildList {
                code?.let { add("code $it") }
                pointer?.takeIf(String::isNotEmpty)?.let(::add)
            }
            return if (context.isEmpty()) message else "$message [${context.joinToString(" · ")}]"
        }
}

/**
 * Port of iOS `CloudflareAPIError`. [userMessage] uses the same copy as iOS `errorDescription`, with
 * provider text bounded and stripped of control characters before it can reach the UI.
 */
class CloudflareOperationException private constructor(
    val kind: Kind,
    val userMessage: String,
    val statusCode: Int? = null,
    val issues: List<CloudflareApiIssue> = emptyList(),
    cause: Throwable? = null,
) : Exception(userMessage, cause) {
    enum class Kind {
        INVALID_CREDENTIALS,
        FORBIDDEN,
        INVALID_REQUEST,
        CONFIRMATION_REQUIRED,
        REQUEST_FAILED,
        API,
        GRAPHQL,
        DECODING,
        NETWORK,
        NOT_CONNECTED,
    }

    override fun toString(): String = "CloudflareOperationException(kind=$kind, statusCode=$statusCode)"

    companion object {
        private const val MAXIMUM_PROVIDER_MESSAGE_CHARACTERS = 600
        private const val MAXIMUM_PROVIDER_MESSAGES = 6

        fun invalidCredentials() = CloudflareOperationException(
            Kind.INVALID_CREDENTIALS,
            "Cloudflare rejected these credentials.",
            statusCode = 401,
        )

        fun forbidden(message: String) = CloudflareOperationException(
            Kind.FORBIDDEN,
            safeProviderText(message).ifEmpty { "This Cloudflare user cannot access that resource." },
            statusCode = 403,
        )

        fun invalidRequest(message: String) = CloudflareOperationException(Kind.INVALID_REQUEST, message)

        fun confirmationRequired(resourceId: String) = CloudflareOperationException(
            Kind.CONFIRMATION_REQUIRED,
            "Confirm the change to $resourceId before continuing.",
        )

        fun requestFailed(statusCode: Int, message: String): CloudflareOperationException {
            val safe = safeProviderText(message)
            return CloudflareOperationException(
                Kind.REQUEST_FAILED,
                if (safe.isEmpty()) {
                    "Cloudflare request failed ($statusCode)."
                } else {
                    "Cloudflare request failed ($statusCode): $safe"
                },
                statusCode = statusCode,
            )
        }

        fun api(issues: List<CloudflareApiIssue>, statusCode: Int? = null): CloudflareOperationException {
            val bounded = issues.take(MAXIMUM_PROVIDER_MESSAGES).map {
                it.copy(message = safeProviderText(it.message), pointer = it.pointer?.let(::safeProviderText))
            }
            val text = bounded.map(CloudflareApiIssue::displayText).filter(String::isNotEmpty).joinToString("\n")
            return CloudflareOperationException(
                Kind.API,
                text.ifEmpty { "Cloudflare rejected the request." },
                statusCode = statusCode,
                issues = bounded,
            )
        }

        fun graphQL(messages: List<String>) = CloudflareOperationException(
            Kind.GRAPHQL,
            messages.take(MAXIMUM_PROVIDER_MESSAGES).map(::safeProviderText).filter(String::isNotEmpty)
                .joinToString("\n").ifEmpty { "Cloudflare GraphQL returned an error." },
        )

        fun decoding(cause: Throwable? = null) = CloudflareOperationException(
            Kind.DECODING,
            "Cloudflare returned data the app could not parse.",
            cause = cause,
        )

        fun network(message: String, cause: Throwable? = null) = CloudflareOperationException(
            Kind.NETWORK,
            message,
            cause = cause,
        )

        fun notConnected() = CloudflareOperationException(
            Kind.NOT_CONNECTED,
            "Connect a Cloudflare account first.",
        )

        /** Provider text is shown to the user, so keep it single-purpose, bounded and printable. */
        fun safeProviderText(value: String): String = value
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trim()
            .let { if (it.length > MAXIMUM_PROVIDER_MESSAGE_CHARACTERS) it.take(MAXIMUM_PROVIDER_MESSAGE_CHARACTERS) + "…" else it }
    }
}

/**
 * iOS `isOptionalProductUnavailable`: a product the plan or token does not include should render as
 * an empty, explained section instead of failing the whole screen.
 */
fun isCloudflareOptionalProductUnavailable(error: Throwable): Boolean {
    val operation = error as? CloudflareOperationException ?: return false
    return when (operation.kind) {
        CloudflareOperationException.Kind.FORBIDDEN -> true
        CloudflareOperationException.Kind.REQUEST_FAILED -> operation.statusCode in setOf(400, 403, 404)
        CloudflareOperationException.Kind.API -> operation.issues.isNotEmpty() && operation.issues.all { issue ->
            val message = issue.message.lowercase()
            listOf("permission", "not authorized", "not found", "not enabled", "unsupported", "jurisdiction")
                .any(message::contains)
        }
        else -> false
    }
}
