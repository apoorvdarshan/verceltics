package com.apoorvdarshan.verceltics.ui.apiexplorer

import com.apoorvdarshan.verceltics.data.apicatalog.GraphQLRequestClassifier
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalogIds
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMethods
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.hosting.HostingApiDefaults
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingRawApi
import com.apoorvdarshan.verceltics.data.hosting.HostingRawTarget
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRawApi
import kotlinx.coroutines.CancellationException

/** Hosting explorers and registrar explorers use different iOS copy and write rules. */
enum class ProviderApiKind {
    HOSTING,
    REGISTRAR,
}

/**
 * Everything the shared Complete API screens need to know about one provider: its catalog id,
 * iOS defaults, protected headers and write-confirmation rule. Contains no credentials.
 */
class ProviderApiProfile private constructor(
    val kind: ProviderApiKind,
    /** Provider id as used by `IntegrationCatalog` (`render`, `netlify`, `goDaddy`, …). */
    val providerId: String,
    val catalogId: String,
    val displayName: String,
    /** Lower-cased header names Verceltics attaches itself; typed values are ignored. */
    val managedHeaders: Set<String>,
    private val isProtectedHeader: (String) -> Boolean,
    val defaultMethod: String,
    val defaultBody: String,
    /** Railway always POSTs its GraphQL endpoint whatever the method picker says (iOS). */
    val forcedMethod: String? = null,
    val isGraphQL: Boolean = false,
    private val likelyWrite: (method: String, path: String, body: String) -> Boolean,
) {
    /** Placeholder for the request-path field (iOS copy). */
    val pathPlaceholder: String
        get() = if (kind == ProviderApiKind.REGISTRAR) "/registrar-relative/path" else "/provider-relative/path"

    fun isProtected(headerName: String): Boolean = isProtectedHeader(headerName)

    /**
     * iOS write confirmation: hosting explorers confirm every non-GET request; registrars confirm
     * write methods and detected write or purchase commands; Railway confirms anything that is not
     * provably a read-only GraphQL query.
     */
    fun requiresConfirmation(method: String, path: String, body: String): Boolean = likelyWrite(method, path, body)

    /** iOS `bodyIsOptional` semantics plus Android's no-body-on-GET/HEAD rule. */
    fun allowsBody(method: String): Boolean = forcedMethod != null || ProviderApiMethods.allowsBody(method)

    fun bodyIsOptional(method: String): Boolean = forcedMethod == null && ProviderApiMethods.bodyIsOptional(method)

    override fun toString(): String = "ProviderApiProfile(kind=$kind, providerId=$providerId)"

    companion object {
        fun hosting(provider: HostingProvider): ProviderApiProfile {
            val isRailway = provider == HostingProvider.RAILWAY
            return ProviderApiProfile(
                kind = ProviderApiKind.HOSTING,
                providerId = provider.id,
                catalogId = ProviderApiCatalogIds.hosting(provider.id),
                displayName = provider.displayName,
                managedHeaders = HostingRawApi.PROTECTED_HEADERS,
                isProtectedHeader = HostingRawApi::isProtectedHeader,
                defaultMethod = if (isRailway) "POST" else "GET",
                defaultBody = if (isRailway) HostingApiDefaults.RAILWAY_DEFAULT_BODY else "",
                forcedMethod = if (isRailway) "POST" else null,
                isGraphQL = isRailway,
                likelyWrite = if (isRailway) {
                    { _, _, body -> !GraphQLRequestClassifier.isReadOnlyQuery(body) }
                } else {
                    { method, _, _ -> ProviderApiMethods.isWrite(method) }
                },
            )
        }

        fun netlify(): ProviderApiProfile = ProviderApiProfile(
            kind = ProviderApiKind.HOSTING,
            providerId = HostingRawTarget.NETLIFY_PROVIDER_ID,
            catalogId = ProviderApiCatalogIds.hosting(HostingRawTarget.NETLIFY_PROVIDER_ID),
            displayName = "Netlify",
            managedHeaders = HostingRawApi.PROTECTED_HEADERS,
            isProtectedHeader = HostingRawApi::isProtectedHeader,
            defaultMethod = "GET",
            defaultBody = "",
            likelyWrite = { method, _, _ -> ProviderApiMethods.isWrite(method) },
        )

        fun registrar(provider: RegistrarProvider): ProviderApiProfile = ProviderApiProfile(
            kind = ProviderApiKind.REGISTRAR,
            providerId = provider.id,
            catalogId = ProviderApiCatalogIds.registrar(provider.id),
            displayName = provider.displayName,
            managedHeaders = RegistrarRawApi.PROTECTED_HEADERS,
            isProtectedHeader = RegistrarRawApi::isProtectedHeader,
            defaultMethod = RegistrarRawApi.defaultMethod(provider),
            defaultBody = RegistrarRawApi.defaultBody(provider),
            likelyWrite = { method, path, _ -> RegistrarRawApi.isLikelyWrite(provider, method, path) },
        )
    }
}

/**
 * What a provider route plugs into the shared workspace: the live catalog augmentation (Railway)
 * and the credentialed raw request. Implementations return user-safe failure messages only.
 */
interface ProviderApiBackend {
    /** The catalog to show; [bundled] loads this build's catalog for the provider. */
    suspend fun loadCatalog(bundled: suspend () -> ProviderApiCatalog, forceRefresh: Boolean): Result<ProviderApiCatalog> = try {
        Result.success(bundled())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun send(request: ProviderRawRequest): Result<ProviderRawResponse>
}
