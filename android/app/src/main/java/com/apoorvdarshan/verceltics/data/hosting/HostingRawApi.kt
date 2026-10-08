package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMethods
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawPath
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRequestSecurity
import com.apoorvdarshan.verceltics.data.apicatalog.RailwayGraphQLTemplateBuilder
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.net.ProtocolException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Where a Complete API request may go: one fixed provider origin plus its documented base path,
 * and how to authenticate. Nothing a user types can change [endpoint].
 */
internal class HostingRawTarget(
    val displayName: String,
    val providerId: String,
    val endpoint: HostingEndpoint,
    /** Documented API base path such as `/api/v1`; empty when paths are origin-relative. */
    val basePath: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    /** Railway's GraphQL endpoint is always POSTed, whatever method is selected (iOS). */
    val forcedMethod: HostingHttpMethod? = null,
    /** AWS SigV4 needs the wire path and query in strict canonical form. */
    val strictEncoding: Boolean = false,
    val auth: suspend () -> HostingAuth,
) {
    override fun toString(): String = "HostingRawTarget(provider=$providerId, endpoint=$endpoint)"

    companion object {
        fun netlify(token: SecretValue) = HostingRawTarget(
            displayName = "Netlify",
            providerId = NETLIFY_PROVIDER_ID,
            endpoint = HostingEndpoint.NETLIFY,
            basePath = "/api/v1",
            auth = { HostingAuth.Bearer(token) },
        )

        fun of(credentials: HostingCredentials, tokenSource: GoogleAccessTokenSource): HostingRawTarget = when (credentials) {
            is HostingCredentials.Railway -> HostingRawTarget(
                displayName = credentials.provider.displayName,
                providerId = credentials.provider.id,
                endpoint = HostingEndpoint.RAILWAY,
                basePath = "",
                forcedMethod = HostingHttpMethod.POST,
                auth = {
                    if (credentials.tokenType == RailwayTokenType.PROJECT) {
                        HostingAuth.RailwayProjectToken(credentials.token)
                    } else {
                        HostingAuth.Bearer(credentials.token)
                    }
                },
            )
            is HostingCredentials.Render -> bearer(credentials, HostingEndpoint.RENDER, "/v1") { credentials.apiKey }
            is HostingCredentials.DigitalOcean -> bearer(credentials, HostingEndpoint.DIGITAL_OCEAN, "") { credentials.token }
            is HostingCredentials.Heroku -> HostingRawTarget(
                displayName = credentials.provider.displayName,
                providerId = credentials.provider.id,
                endpoint = HostingEndpoint.HEROKU,
                basePath = "",
                defaultHeaders = mapOf("Accept" to "application/vnd.heroku+json; version=3"),
                auth = { HostingAuth.Bearer(credentials.token) },
            )
            is HostingCredentials.Fly -> bearer(credentials, HostingEndpoint.FLY, "/v1") { credentials.token }
            is HostingCredentials.Firebase -> HostingRawTarget(
                displayName = credentials.provider.displayName,
                providerId = credentials.provider.id,
                endpoint = HostingEndpoint.FIREBASE,
                basePath = "/v1beta1",
                auth = { HostingAuth.Bearer(firebaseToken(tokenSource, credentials.googleSlot)) },
            )
            is HostingCredentials.AwsAmplify -> HostingRawTarget(
                displayName = credentials.provider.displayName,
                providerId = credentials.provider.id,
                endpoint = HostingEndpoint.amplify(credentials.region),
                basePath = "",
                strictEncoding = true,
                auth = {
                    HostingAuth.AwsSigV4(
                        accessKeyId = credentials.accessKeyId,
                        secretAccessKey = credentials.secretAccessKey,
                        sessionToken = credentials.sessionToken,
                        region = credentials.region,
                        service = "amplify",
                    )
                },
            )
        }

        private fun bearer(
            credentials: HostingCredentials,
            endpoint: HostingEndpoint,
            basePath: String,
            token: () -> SecretValue,
        ) = HostingRawTarget(
            displayName = credentials.provider.displayName,
            providerId = credentials.provider.id,
            endpoint = endpoint,
            basePath = basePath,
            auth = { HostingAuth.Bearer(token()) },
        )

        private suspend fun firebaseToken(tokenSource: GoogleAccessTokenSource, slot: String): SecretValue {
            val raw = try {
                tokenSource.accessToken(slot, GoogleAccessTokenSource.FIREBASE_HOSTING_SCOPES)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw HostingApiException(
                    HostingFailure(
                        HostingFailureKind.NETWORK,
                        "Google authorization could not be refreshed. Check your connection and try again.",
                    ),
                )
            }
            return raw?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { SecretValue.of(it) }.getOrNull() }
                ?: throw HostingApiException(
                    HostingFailure(HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED, "Continue with Google to connect Firebase Hosting."),
                )
        }

        const val NETLIFY_PROVIDER_ID: String = "netlify"
    }
}

/**
 * Complete API raw requests for the hosting providers and Netlify (iOS
 * `HostingProviderAPI.rawRequest`). HTTP errors and redirects come back as responses so the
 * explorer can show them; credentials are attached privately and redacted from responses.
 */
class HostingRawApi(
    private val transport: HostingHttpTransport,
    private val googleAccessTokenSource: GoogleAccessTokenSource = GoogleAccessTokenSource.Unavailable,
) {
    suspend fun send(credentials: HostingCredentials, request: ProviderRawRequest): ProviderRawResponse =
        send(HostingRawTarget.of(credentials, googleAccessTokenSource), request)

    suspend fun sendNetlify(token: SecretValue, request: ProviderRawRequest): ProviderRawResponse =
        send(HostingRawTarget.netlify(token), request)

    /**
     * iOS `liveRailwayCatalog`: introspects the authenticated GraphQL schema and turns every query
     * and mutation into an editable starter request.
     */
    suspend fun railwayLiveCatalog(credentials: HostingCredentials.Railway): ProviderApiCatalog {
        val target = HostingRawTarget.of(credentials, googleAccessTokenSource)
        val body = HostingJson.write(JsonObject(mapOf("query" to JsonString(RailwayGraphQLTemplateBuilder.INTROSPECTION_QUERY))))
        val prepared = prepare(target, ProviderRawRequest("POST", "/graphql/v2", body = body))
        val response = execute(target, prepared.request)
        val bytes = response.takeBody()
        val root = try {
            HostingJson.parse(bytes).asObject()
        } catch (_: HostingResponseFormatException) {
            throw invalid("Railway did not return its GraphQL schema.")
        } finally {
            bytes.fill(0)
        }
        root["errors"].asArray().firstOrNull()?.asObject()?.string("message")?.let { message ->
            throw invalid(safeProviderMessage(message, prepared.secrets))
        }
        if (response.statusCode !in 200..299) throw invalid("Railway did not return its GraphQL schema (HTTP ${response.statusCode}).")
        val schema = root["data"].asObject()["__schema"] as? JsonObject ?: throw invalid("Railway did not return its GraphQL schema.")
        return RailwayGraphQLTemplateBuilder.liveCatalog(schema)
    }

    internal suspend fun send(target: HostingRawTarget, request: ProviderRawRequest): ProviderRawResponse {
        val prepared = prepare(target, request)
        val response = execute(target, prepared.request)
        val bytes = response.takeBody()
        return try {
            ProviderRawResponse.fromWire(response.statusCode, response.headers, bytes, prepared.secrets)
        } finally {
            bytes.fill(0)
        }
    }

    internal class PreparedRawRequest(val request: HostingHttpRequest, val secrets: List<String>) {
        override fun toString(): String = "PreparedRawRequest(request=$request, secrets=<redacted>)"
    }

    /** Validates and builds the exact wire request (exposed for tests). */
    internal suspend fun prepare(target: HostingRawTarget, request: ProviderRawRequest): PreparedRawRequest {
        val selected = HostingHttpMethod.fromName(ProviderApiMethods.requireSupported(request.method))
            ?: throw ProviderApiRequestException("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.")
        val method = target.forcedMethod ?: selected
        val parsed = ProviderRawPath.parse(normalizedRequestPath(target.providerId, request.path.trim()))
        val encodedPath = target.basePath + if (target.strictEncoding) ProviderRawPath.strictPath(parsed) else parsed.encodedPath
        val custom = ProviderRequestSecurity.validatedHeaders(request.headers, ::isProtectedHeader)
        val headers = target.defaultHeaders.filterKeys { name -> custom.keys.none { it.equals(name, ignoreCase = true) } } + custom
        val contentType = ProviderRequestSecurity.validatedContentType(request.contentType) ?: HostingHttpRequest.JSON_CONTENT_TYPE
        var body = request.bodyBytes()
        if (body != null && !method.allowsBody) {
            body.fill(0)
            throw ProviderApiRequestException(
                "${method.name} requests can't include a body on Android. Clear the request body or choose another method.",
            )
        }
        if (body == null && method in BODY_METHODS) body = ByteArray(0)
        if (body != null && body.size > HostingHttpRequest.MAX_RAW_REQUEST_BODY_BYTES) {
            body.fill(0)
            throw ProviderApiRequestException("The request body must be 25 MB or smaller.")
        }
        val auth = target.auth()
        return try {
            PreparedRawRequest(
                request = HostingHttpRequest.raw(
                    endpoint = target.endpoint,
                    method = method,
                    encodedPath = encodedPath,
                    encodedQuery = if (target.strictEncoding) null else parsed.encodedQuery,
                    query = parsed.queryParameters,
                    headers = headers,
                    body = body,
                    contentType = contentType,
                    auth = auth,
                ),
                secrets = auth.secretStrings(),
            )
        } catch (error: ProviderApiRequestException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw ProviderApiRequestException(error.message ?: "The request is invalid.")
        } finally {
            body?.fill(0)
        }
    }

    private suspend fun execute(target: HostingRawTarget, request: HostingHttpRequest) = try {
        transport.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: HostingApiException) {
        throw error
    } catch (_: ResponseTooLargeException) {
        throw HostingApiException(
            HostingFailure(HostingFailureKind.INVALID_RESPONSE, "The ${target.displayName} response exceeded the safe 8 MB limit."),
        )
    } catch (_: UnsafeRedirectException) {
        throw HostingApiException(HostingFailure(HostingFailureKind.INVALID_RESPONSE, "${target.displayName} returned an unsafe redirect."))
    } catch (_: ProtocolException) {
        throw HostingApiException(
            HostingFailure(HostingFailureKind.CONFIGURATION, "This device can't send ${request.method.name} requests to ${target.displayName}."),
        )
    } catch (_: IOException) {
        throw HostingApiException(
            HostingFailure(HostingFailureKind.NETWORK, "${target.displayName} could not be reached. Check your connection and try again."),
        )
    }

    companion object {
        private val BODY_METHODS = setOf(HostingHttpMethod.POST, HostingHttpMethod.PUT, HostingHttpMethod.PATCH)

        /** iOS `HostingProviderAPI.protectedHeaders` plus headers Android's transport owns. */
        val PROTECTED_HEADERS: Set<String> = setOf(
            "authorization",
            "project-access-token",
            "host",
            "content-length",
            "content-type",
            "x-amz-date",
            "x-amz-security-token",
            "cookie",
            "proxy-authorization",
            "accept-encoding",
            "transfer-encoding",
            "connection",
        )

        /** Lower-cased [name] is attached by Verceltics and silently ignored when typed. */
        fun isProtectedHeader(name: String): Boolean {
            val lowercase = name.lowercase(Locale.ROOT)
            return lowercase in PROTECTED_HEADERS || lowercase.startsWith("x-amz-")
        }

        /**
         * iOS `normalizedRequestPath`: first-class DigitalOcean calls use provider-relative paths
         * while the bundled catalog already includes `/v2`; both become exactly one prefix.
         */
        fun normalizedRequestPath(providerId: String, path: String): String {
            if (providerId != HostingProvider.DIGITAL_OCEAN.id) return path
            if (path == "/v2" || path.startsWith("/v2/") || path.startsWith("/v2?")) return path
            return "/v2$path"
        }

        private fun HostingAuth.secretStrings(): List<String> = when (this) {
            HostingAuth.None -> emptyList()
            is HostingAuth.Bearer -> listOf(token.use { it })
            is HostingAuth.RailwayProjectToken -> listOf(token.use { it })
            is HostingAuth.AwsSigV4 -> listOfNotNull(secretAccessKey.use { it }, sessionToken?.use { it })
        }

        private fun safeProviderMessage(message: String, secrets: List<String>): String =
            ProviderRequestSecurity.redact(message, secrets)
                .map { if (it.isISOControl()) ' ' else it }
                .joinToString("")
                .trim()
                .take(300)
                .ifEmpty { "Railway did not return its GraphQL schema." }

        private fun invalid(message: String) = HostingApiException(HostingFailure(HostingFailureKind.INVALID_RESPONSE, message))
    }
}

/** iOS `HostingAPIExplorerView.defaultPath`: where the manual explorer starts. */
object HostingApiDefaults {
    /** iOS `HostingAPIExplorerView` Railway starter body. */
    const val RAILWAY_DEFAULT_BODY: String = "{\"query\":\"query { me { id name email } }\"}"

    fun explorerPath(link: HostingLinkContext, resource: HostingResource? = null): String {
        fun segment(value: String) = AwsSigV4Signer.encode(value)
        return when (link.provider) {
            HostingProvider.RAILWAY -> "/graphql/v2"
            HostingProvider.RENDER -> resource?.let { "/services/${segment(it.id)}" } ?: "/services?limit=100"
            HostingProvider.DIGITAL_OCEAN -> resource?.let { "/apps/${segment(it.id)}" } ?: "/apps?per_page=200"
            HostingProvider.HEROKU -> resource?.let { "/apps/${segment(it.id)}" } ?: "/apps"
            HostingProvider.FLY -> resource?.let { "/apps/${segment(it.metadata["appName"] ?: it.name)}/machines" }
                ?: "/apps?org_slug=${segment(link.flyOrganization?.takeIf(String::isNotBlank) ?: "personal")}"
            HostingProvider.FIREBASE -> resource?.let { "/sites/${segment(it.id)}/releases?pageSize=50" }
                ?: "/projects/${segment(link.firebaseProjectId?.takeIf(String::isNotBlank) ?: "PROJECT_ID")}/sites"
            HostingProvider.AWS_AMPLIFY -> resource?.let { "/apps/${segment(it.id)}" } ?: "/apps?maxResults=100"
        }
    }

    /** Netlify's starting path: the site itself, or the first page of sites. */
    fun netlifyExplorerPath(siteId: String? = null): String =
        siteId?.let { "/sites/${AwsSigV4Signer.encode(it)}" } ?: "/sites?per_page=100"
}
