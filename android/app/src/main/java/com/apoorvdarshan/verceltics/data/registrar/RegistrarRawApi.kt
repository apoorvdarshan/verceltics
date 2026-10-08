package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMethods
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestEncoding
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawPath
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRequestSecurity
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import com.apoorvdarshan.verceltics.data.network.UnsafeRedirectException
import java.io.IOException
import java.net.ProtocolException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Complete API raw requests for the eight registrars (iOS `RegistrarAPI.rawRequest`,
 * `isLikelyWrite`, `suggestedPath`, `protectedHeaders`). Authentication is attached privately,
 * the path can never leave the registrar's API host, and echoed credentials are redacted.
 */
class RegistrarRawApi internal constructor(
    private val transport: RegistrarHttpTransport,
) {
    constructor() : this(SecureRegistrarHttpTransport())

    fun newRawCall(credentials: RegistrarCredentials, request: ProviderRawRequest): CancelableCall<ProviderRawResponse> =
        RegistrarRawCall(credentials.provider) {
            val prepared = prepare(credentials, request)
            val response = executeChild(transport.newRawCall(prepared.request))
            val bytes = response.takeBody()
            try {
                ProviderRawResponse.fromWire(response.statusCode, response.headers, bytes, prepared.secrets)
            } finally {
                bytes.fill(0)
            }
        }

    internal class PreparedRegistrarRawRequest(val request: RegistrarRawHttpRequest, val secrets: List<String>) {
        override fun toString(): String = "PreparedRegistrarRawRequest(request=$request, secrets=<redacted>)"
    }

    companion object {
        /** iOS `RegistrarAPI.protectedHeaders` plus headers Android's transport owns. */
        val PROTECTED_HEADERS: Set<String> = setOf(
            "authorization",
            "host",
            "content-length",
            "content-type",
            "x-api-key",
            "x-secret-api-key",
            "x-api-secret",
            "cookie",
            "proxy-authorization",
            "accept-encoding",
            "transfer-encoding",
            "connection",
        )

        fun isProtectedHeader(name: String): Boolean = name.lowercase(Locale.ROOT) in PROTECTED_HEADERS

        /** Query names that carry or bind a credential; typed duplicates are dropped. */
        private fun protectedQueryNames(provider: RegistrarProvider): Set<String> = when (provider) {
            RegistrarProvider.NAMECHEAP -> setOf("apiuser", "apikey", "username", "clientip")
            RegistrarProvider.DYNADOT, RegistrarProvider.NAME_SILO -> setOf("key")
            else -> emptySet()
        }

        /** iOS `isLikelyWrite`: any non-read method, or a write/purchase command in the path. */
        fun isLikelyWrite(provider: RegistrarProvider, method: String, path: String): Boolean {
            if (ProviderApiMethods.isWrite(method)) return true
            val value = path.lowercase(Locale.ROOT)
            val markers = when (provider) {
                RegistrarProvider.NAMECHEAP -> listOf(
                    ".create", ".set", ".renew", ".reactivate", ".delete", ".update", ".change", ".enable", ".disable",
                    ".activate", ".reissue", ".resend", ".purchase", ".revoke", ".edit", ".reset",
                )
                RegistrarProvider.DYNADOT -> listOf(
                    "command=register", "command=delete", "command=restore", "command=renew", "command=set_",
                    "command=transfer", "command=push", "command=buy_", "command=make_", "command=place_",
                    "command=create_", "command=edit_", "command=clear_", "command=modify_",
                )
                RegistrarProvider.NAME_SILO -> listOf(
                    "registerdomain", "renewdomain", "transferdomain", "transferupdate", "changedns", "contactadd",
                    "contactupdate", "domainupdate", "addprivacy", "removeprivacy", "addautorenewal", "removeautorenewal",
                    "addregistrylock", "removeregistrylock", "dnsaddrecord", "dnsupdaterecord", "dnsdeleterecord",
                    "domainforward", "addregisterednameserver", "modifyregisterednameserver",
                    "deleteregisterednameserver", "portfolioadd", "portfoliodelete",
                )
                else -> emptyList()
            }
            return markers.any { it in value }
        }

        /** iOS `suggestedPath(for:)`: the domain itself, or the first page of the portfolio. */
        fun suggestedPath(provider: RegistrarProvider, domainName: String? = null): String {
            val domain = domainName?.trim()?.takeIf(String::isNotEmpty)
            fun segment(value: String) = ProviderApiRequestEncoding.pathParameter(value, allowReserved = false)
            return when (provider) {
                RegistrarProvider.NAME_DOT_COM -> domain?.let { "/core/v1/domains/${segment(it)}" } ?: "/core/v1/domains?perPage=250"
                RegistrarProvider.NAMECHEAP -> domain?.let {
                    "/xml.response?Command=namecheap.domains.getInfo&DomainName=${segment(it)}"
                } ?: "/xml.response?Command=namecheap.domains.getList&ListType=ALL&PageSize=100"
                RegistrarProvider.PORKBUN -> domain?.let { "/dns/retrieve/${segment(it)}" } ?: "/domain/listAll"
                RegistrarProvider.SPACESHIP -> domain?.let { "/v1/domains/${segment(it)}" } ?: "/v1/domains?take=100&skip=0"
                RegistrarProvider.DYNADOT -> domain?.let { "/api3.json?command=domain_info&domain=${segment(it)}" }
                    ?: "/api3.json?command=list_domain"
                RegistrarProvider.NAME_SILO -> domain?.let { "/api/getDomainInfo?domain=${segment(it)}" } ?: "/api/listDomains"
                RegistrarProvider.GANDI -> domain?.let { "/v5/domain/domains/${segment(it)}" } ?: "/v5/domain/domains?per_page=100&page=1"
                RegistrarProvider.GO_DADDY -> domain?.let { "/v1/domains/${segment(it)}" } ?: "/v1/domains?limit=1000&includes=nameServers"
            }
        }

        /** iOS explorer defaults: Porkbun's API is POST-only with a JSON body. */
        fun defaultMethod(provider: RegistrarProvider): String = if (provider == RegistrarProvider.PORKBUN) "POST" else "GET"

        fun defaultBody(provider: RegistrarProvider): String = if (provider == RegistrarProvider.PORKBUN) "{}" else ""

        /** Port of the iOS `rawRequest` validation and authentication (exposed for tests). */
        internal fun prepare(credentials: RegistrarCredentials, request: ProviderRawRequest): PreparedRegistrarRawRequest {
            val provider = credentials.provider
            val method = ProviderApiMethods.requireSupported(request.method)
            if (provider == RegistrarProvider.NAME_SILO && method != "GET") {
                throw ProviderApiRequestException("NameSilo requires every API operation to use GET.")
            }
            val target = ProviderRawPath.parse(request.path, "Enter a registrar-relative path beginning with /.")
            val protectedQuery = protectedQueryNames(provider)
            val typedQuery = target.encodedQuery?.split('&')?.filter { item ->
                item.isNotEmpty() && ProviderRawPath.percentDecode(item.substringBefore('=')).lowercase(Locale.ROOT) !in protectedQuery
            }?.joinToString("&")?.takeIf(String::isNotEmpty)

            val publicQuery = mutableListOf<Pair<String, String>>()
            val secretQuery = mutableListOf<Pair<String, SecretValue>>()
            val secretHeaders = mutableListOf<Pair<String, SecretValue>>()
            val secrets = mutableListOf<String>()
            credentials.primary.use { secrets += it }
            credentials.secondary?.use { secrets += it }
            when (provider) {
                RegistrarProvider.NAMECHEAP -> {
                    val username = requiredMetadata(credentials, RegistrarMetadataKeys.USERNAME, "Namecheap API username")
                    val rawClientIp = requiredMetadata(credentials, RegistrarMetadataKeys.CLIENT_IP, "whitelisted client IP")
                    val clientIp = PublicIpv4Lookup.normalizedPublicIpv4(rawClientIp)
                        ?: throw ProviderApiRequestException("Enter a valid public IPv4 address that is whitelisted in Namecheap.")
                    publicQuery += "ApiUser" to username
                    secretQuery += "ApiKey" to credentials.primary
                    publicQuery += "UserName" to username
                    publicQuery += "ClientIp" to clientIp
                }
                RegistrarProvider.DYNADOT -> secretQuery += "key" to credentials.primary
                RegistrarProvider.NAME_SILO -> {
                    publicQuery += "version" to "1"
                    publicQuery += "type" to "json"
                    secretQuery += "key" to credentials.primary
                }
                RegistrarProvider.NAME_DOT_COM -> {
                    val username = requiredMetadata(credentials, RegistrarMetadataKeys.USERNAME, "Name.com API username")
                    val authorization = credentials.primary.use { token ->
                        val raw = "$username:$token".toByteArray(StandardCharsets.UTF_8)
                        try {
                            "Basic " + Base64.getEncoder().encodeToString(raw)
                        } finally {
                            raw.fill(0)
                        }
                    }
                    secrets += authorization.removePrefix("Basic ")
                    secretHeaders += "Authorization" to SecretValue.of(authorization)
                }
                RegistrarProvider.PORKBUN -> {
                    secretHeaders += "X-API-Key" to credentials.primary
                    secretHeaders += "X-Secret-API-Key" to requiredSecret(credentials)
                }
                RegistrarProvider.SPACESHIP -> {
                    secretHeaders += "X-API-Key" to credentials.primary
                    secretHeaders += "X-API-Secret" to requiredSecret(credentials)
                }
                RegistrarProvider.GANDI -> secretHeaders += "Authorization" to credentials.primary.use { SecretValue.of("Bearer $it") }
                RegistrarProvider.GO_DADDY -> {
                    val secret = requiredSecret(credentials)
                    secretHeaders += "Authorization" to credentials.primary.use { key ->
                        secret.use { value -> SecretValue.of("sso-key $key:$value") }
                    }
                }
            }

            val custom = ProviderRequestSecurity.validatedHeaders(request.headers, ::isProtectedHeader)
            val accept = custom.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value ?: "application/json"
            val body = request.bodyBytes()
            if (body != null && !ProviderApiMethods.allowsBody(method)) {
                body.fill(0)
                throw ProviderApiRequestException(
                    "$method requests can't include a body on Android. Clear the request body or choose another method.",
                )
            }
            val contentType = if (body != null) {
                ProviderRequestSecurity.validatedContentType(request.contentType) ?: "application/json"
            } else {
                null
            }
            return try {
                PreparedRegistrarRawRequest(
                    request = RegistrarRawHttpRequest(
                        origin = provider.origin,
                        method = method,
                        encodedPath = provider.apiPathPrefix + target.encodedPath,
                        encodedQuery = typedQuery,
                        queryParameters = publicQuery,
                        secretQueryParameters = secretQuery,
                        secretHeaders = secretHeaders,
                        headers = custom.filterKeys { !it.equals("Accept", ignoreCase = true) },
                        accept = accept,
                        body = body,
                        contentType = contentType,
                    ),
                    secrets = secrets,
                )
            } finally {
                body?.fill(0)
            }
        }

        private fun requiredMetadata(credentials: RegistrarCredentials, key: String, label: String): String =
            credentials.metadataValue(key) ?: throw ProviderApiRequestException("Enter the $label.")

        private fun requiredSecret(credentials: RegistrarCredentials): SecretValue =
            credentials.secondary ?: throw ProviderApiRequestException("Enter the API secret.")
    }
}

/** Single-use cancellable wrapper mapping transport failures to user-safe registrar errors. */
private class RegistrarRawCall<T>(
    private val provider: RegistrarProvider,
    private val body: RegistrarRawCall<T>.() -> T,
) : CancelableCall<T> {
    private val started = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val activeChild = AtomicReference<CancelableCall<*>?>()

    override fun execute(): T {
        check(started.compareAndSet(false, true)) { "A registrar call can only execute once." }
        if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
        return try {
            body()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RegistrarApiException) {
            throw error
        } catch (error: ProviderApiRequestException) {
            throw RegistrarApiException.invalidConfiguration(error.message ?: "The request is invalid.")
        } catch (_: ResponseTooLargeException) {
            throw RegistrarApiException(RegistrarFailureKind.INVALID_RESPONSE, "The response exceeded the safe 8 MB limit.")
        } catch (_: UnsafeRedirectException) {
            throw RegistrarApiException(RegistrarFailureKind.INVALID_RESPONSE, "${provider.displayName} returned an unsafe redirect.")
        } catch (_: ProtocolException) {
            throw RegistrarApiException.invalidConfiguration("This device can't send that request method to ${provider.displayName}.")
        } catch (_: IOException) {
            if (cancelled.get()) throw CancellationException("The registrar request was cancelled.")
            throw RegistrarApiException.network(provider)
        } catch (error: IllegalArgumentException) {
            throw RegistrarApiException.invalidConfiguration(error.message ?: "The registrar request configuration is invalid.")
        } catch (_: Exception) {
            throw RegistrarApiException.invalidResponse()
        }
    }

    override fun cancel() {
        cancelled.set(true)
        activeChild.getAndSet(null)?.cancel()
    }

    fun <R> executeChild(call: CancelableCall<R>): R {
        activeChild.set(call)
        if (cancelled.get()) {
            activeChild.compareAndSet(call, null)
            call.cancel()
            throw CancellationException("The registrar request was cancelled.")
        }
        return try {
            call.execute().also { if (cancelled.get()) throw CancellationException("The registrar request was cancelled.") }
        } finally {
            activeChild.compareAndSet(call, null)
        }
    }

    override fun toString(): String = "RegistrarRawCall(provider=${provider.id})"
}
