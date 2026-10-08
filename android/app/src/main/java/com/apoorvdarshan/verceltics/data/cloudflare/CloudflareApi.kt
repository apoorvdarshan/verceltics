package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpClient
import com.apoorvdarshan.verceltics.data.network.SecureProviderHttpClient
import com.apoorvdarshan.verceltics.data.network.map

interface CloudflareReadApi {
    /** `/user/tokens/verify`, which only scoped API tokens can call (iOS `validateAPIToken`). */
    fun newVerifyTokenCall(token: SecretValue): CancelableCall<CloudflareTokenVerification>

    /** `/user`, the Global API Key identity check (iOS `validateCredentials`). */
    fun newUserCall(credential: CloudflareCredential.GlobalApiKey): CancelableCall<CloudflareUserIdentity>

    fun newAccountsPageCall(
        credential: CloudflareCredential,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflareAccountSummary>>

    fun newZonesPageCall(
        credential: CloudflareCredential,
        accountId: String,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflareZone>>

    fun newPagesProjectsPageCall(
        credential: CloudflareCredential,
        accountId: String,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflarePagesProject>>

    fun newWorkerScriptsCall(
        credential: CloudflareCredential,
        accountId: String,
    ): CancelableCall<List<CloudflareWorkerScript>>
}

/**
 * Cloudflare's fixed-origin, read-only dashboard surface.
 *
 * Scoped API tokens authenticate with `Authorization: Bearer` and validate with token verify plus
 * accounts. Email + Global API Key connections send `X-Auth-Email`/`X-Auth-Key` and validate with
 * `/user`, exactly like iOS `CloudflareAPI`. Mutations live in the operations and tools clients.
 */
class CloudflareApi(
    private val httpClient: ProviderHttpClient = SecureProviderHttpClient(ORIGIN),
    private val jsonParser: CloudflareJsonParser = AndroidCloudflareJsonParser(),
) : CloudflareReadApi {
    override fun newVerifyTokenCall(token: SecretValue): CancelableCall<CloudflareTokenVerification> =
        httpClient.newGetCall(
            relativePath = "$API_PREFIX/user/tokens/verify",
            bearerToken = token,
        ).map { response ->
            parseSuccessful(response, "verify the Cloudflare API token", CloudflareAuthMode.API_TOKEN, jsonParser::parseTokenVerification)
        }

    override fun newUserCall(credential: CloudflareCredential.GlobalApiKey): CancelableCall<CloudflareUserIdentity> =
        authenticatedGet(credential, "$API_PREFIX/user").map { response ->
            parseSuccessful(response, "verify the Cloudflare Global API Key", credential.authMode, jsonParser::parseUser)
        }

    override fun newAccountsPageCall(
        credential: CloudflareCredential,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflareAccountSummary>> {
        requirePage(page, perPage)
        return authenticatedGet(credential, "$API_PREFIX/accounts", paginationQuery(page, perPage)).map { response ->
            parseSuccessful(response, "load Cloudflare accounts", credential.authMode, jsonParser::parseAccountsPage)
        }
    }

    override fun newZonesPageCall(
        credential: CloudflareCredential,
        accountId: String,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflareZone>> {
        requirePage(page, perPage)
        val normalizedAccountId = safePathSegment(accountId, "account")
        return authenticatedGet(
            credential,
            "$API_PREFIX/zones",
            listOf("account.id" to normalizedAccountId) + paginationQuery(page, perPage),
        ).map { response ->
            parseSuccessful(response, "load Cloudflare zones", credential.authMode, jsonParser::parseZonesPage)
        }
    }

    override fun newPagesProjectsPageCall(
        credential: CloudflareCredential,
        accountId: String,
        page: Int,
        perPage: Int,
    ): CancelableCall<CloudflarePage<CloudflarePagesProject>> {
        requirePage(page, perPage)
        val normalizedAccountId = safePathSegment(accountId, "account")
        return authenticatedGet(
            credential,
            "$API_PREFIX/accounts/$normalizedAccountId/pages/projects",
            paginationQuery(page, perPage),
        ).map { response ->
            parseSuccessful(response, "load Cloudflare Pages projects", credential.authMode, jsonParser::parsePagesProjectsPage)
        }
    }

    override fun newWorkerScriptsCall(
        credential: CloudflareCredential,
        accountId: String,
    ): CancelableCall<List<CloudflareWorkerScript>> {
        val normalizedAccountId = safePathSegment(accountId, "account")
        return authenticatedGet(credential, "$API_PREFIX/accounts/$normalizedAccountId/workers/scripts").map { response ->
            parseSuccessful(response, "load Cloudflare Workers", credential.authMode, jsonParser::parseWorkerScripts)
        }
    }

    /**
     * Scoped tokens use the secure client's bearer slot; Global API Keys use `X-Auth-Email` and
     * `X-Auth-Key`, which the secure client sends as ordinary (never logged) request headers.
     */
    private fun authenticatedGet(
        credential: CloudflareCredential,
        relativePath: String,
        queryParameters: List<Pair<String, String>> = emptyList(),
    ): CancelableCall<HttpResponse> = when (credential) {
        is CloudflareCredential.ApiToken -> httpClient.newGetCall(
            relativePath = relativePath,
            queryParameters = queryParameters,
            bearerToken = credential.token,
        )
        is CloudflareCredential.GlobalApiKey -> {
            val headers = LinkedHashMap<String, String>(2)
            credential.applyHeaders { name, value -> headers[name] = value }
            httpClient.newGetCall(
                relativePath = relativePath,
                queryParameters = queryParameters,
                headers = headers,
            )
        }
    }

    private inline fun <T> parseSuccessful(
        response: HttpResponse,
        operation: String,
        authMode: CloudflareAuthMode,
        parser: (ByteArray) -> T,
    ): T {
        requireSuccessful(response, operation, authMode)
        return try {
            response.useBody(parser)
        } catch (error: CloudflareEnvelopeRejectedException) {
            throw CloudflareApiException(
                failure = CloudflareFailure(
                    CloudflareFailureKind.PROVIDER_REJECTED,
                    "Cloudflare rejected the request while trying to $operation.",
                ),
                errorCode = error.errorCode,
            )
        }
    }

    private fun requireSuccessful(response: HttpResponse, operation: String, authMode: CloudflareAuthMode) {
        if (response.statusCode in 200..299) return
        // Error metadata is optional. Malformed or reflected provider text must not mask the
        // reliable HTTP classification or escape into user-facing/loggable exception strings.
        val errorCode = response.useBody { body ->
            runCatching { jsonParser.parseErrorCode(body) }.getOrNull()
        }
        val failure = when (response.statusCode) {
            401 -> CloudflareFailure(
                CloudflareFailureKind.AUTHENTICATION,
                when (authMode) {
                    CloudflareAuthMode.API_TOKEN -> "Cloudflare rejected this API token."
                    CloudflareAuthMode.GLOBAL_API_KEY -> "Cloudflare rejected this email and Global API Key."
                },
                response.statusCode,
            )
            403 -> CloudflareFailure(
                CloudflareFailureKind.AUTHORIZATION,
                when (authMode) {
                    CloudflareAuthMode.API_TOKEN -> "This Cloudflare API token cannot access the requested resource."
                    CloudflareAuthMode.GLOBAL_API_KEY -> "This Cloudflare user cannot access the requested resource."
                },
                response.statusCode,
            )
            404 -> CloudflareFailure(
                CloudflareFailureKind.NOT_FOUND,
                "Cloudflare could not find the requested resource.",
                response.statusCode,
            )
            408 -> CloudflareFailure(
                CloudflareFailureKind.NETWORK,
                "Cloudflare timed out while trying to $operation.",
                response.statusCode,
            )
            429 -> CloudflareFailure(
                CloudflareFailureKind.RATE_LIMITED,
                "Cloudflare is rate limiting requests. Please try again shortly.",
                response.statusCode,
            )
            in 500..599 -> CloudflareFailure(
                CloudflareFailureKind.TEMPORARY,
                "Cloudflare is temporarily unavailable.",
                response.statusCode,
            )
            else -> CloudflareFailure(
                CloudflareFailureKind.TEMPORARY,
                "Unable to $operation (HTTP ${response.statusCode}).",
                response.statusCode,
            )
        }
        throw CloudflareApiException(
            failure = failure,
            errorCode = errorCode?.takeIf(SAFE_ERROR_CODE::matches),
        )
    }

    private inline fun <T> HttpResponse.useBody(block: (ByteArray) -> T): T {
        val bytes = takeBody()
        return try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun requirePage(page: Int, perPage: Int) {
        require(page in 1..MAXIMUM_PAGE_NUMBER) { "Invalid Cloudflare page." }
        require(perPage in 1..MAXIMUM_PAGE_SIZE) {
            "Cloudflare page size must be between 1 and $MAXIMUM_PAGE_SIZE."
        }
    }

    private fun safePathSegment(value: String, label: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= CF_MAX_ID_CHARACTERS) {
            "A Cloudflare $label id is required."
        }
        require(SAFE_PATH_SEGMENT.matches(trimmed)) {
            "The Cloudflare $label id is not safe for a provider path."
        }
        return trimmed
    }

    private fun paginationQuery(page: Int, perPage: Int): List<Pair<String, String>> = listOf(
        "page" to page.toString(),
        "per_page" to perPage.toString(),
    )

    companion object {
        const val ORIGIN = "https://api.cloudflare.com/"
        const val API_PREFIX = "/client/v4"
        const val MAXIMUM_PAGE_SIZE = 100
        const val MAXIMUM_PAGE_NUMBER = 500
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._~-]+")
        private val SAFE_ERROR_CODE = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

class CloudflareApiException(
    val failure: CloudflareFailure,
    val errorCode: String?,
) : RuntimeException(failure.message) {
    override fun toString(): String =
        "CloudflareApiException(kind=${failure.kind}, statusCode=${failure.statusCode}, " +
            "errorCode=${if (errorCode == null) "none" else "<redacted>"})"
}
