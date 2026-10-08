package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiCatalog
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import kotlinx.coroutines.CancellationException

/**
 * UI-only boundary for the seven generic hosting providers (Railway, Render, DigitalOcean,
 * Heroku, Fly.io, Firebase Hosting, AWS Amplify). Credentials flow in once through [connect] and
 * never come back out; every model returned here is safe to keep in observable screen state.
 */
interface HostingProviderUiGateway {
    /** Offline restore of every provider slot, keyed by provider id. Never contacts a provider. */
    suspend fun restore(): Result<Map<String, HostingRestoreUi>>

    /** Validates [credentials] online, then saves them encrypted with a first inventory. */
    suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi>

    suspend fun refresh(providerId: String): Result<HostingDashboardUi>

    /** Loads the resource history (deployments, releases, Machines or build jobs). */
    suspend fun loadResource(providerId: String, resource: HostingResourceUi): Result<HostingResourceWorkspaceUi>

    /**
     * Sends the provider's real write request (redeploy, restart or release). Returns a
     * user-facing confirmation message.
     */
    suspend fun performPrimaryAction(
        providerId: String,
        resource: HostingResourceUi,
        latestDeploymentId: String?,
    ): Result<String>

    suspend fun disconnect(providerId: String): Result<Unit>

    /**
     * Complete API catalog for [providerId]. [bundled] loads this build's catalog; Railway's live
     * gateway replaces it with operations discovered from the authenticated GraphQL schema.
     */
    suspend fun loadApiCatalog(
        providerId: String,
        bundled: suspend () -> ProviderApiCatalog,
        forceRefresh: Boolean,
    ): Result<ProviderApiCatalog> = try {
        Result.success(bundled())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(HostingUiException(error.message ?: "The complete provider API catalog could not be loaded."))
    }

    /**
     * Sends one Complete API raw request with the saved credentials. HTTP errors are returned as
     * responses; only validation, transport and credential problems fail.
     */
    suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> =
        Result.failure(HostingUiException(SAMPLE_API_UNAVAILABLE))

    companion object {
        const val SAMPLE_API_UNAVAILABLE: String =
            "Sample data can’t send live API requests. Connect an account to use the Complete API."
    }
}

sealed interface HostingRestoreUi {
    data object NotConnected : HostingRestoreUi

    data class Available(val dashboard: HostingDashboardUi) : HostingRestoreUi

    data class SavedWithoutInventory(val account: HostingAccountUi, val dashboardUrl: String) : HostingRestoreUi

    data class SavedUnavailable(val message: String) : HostingRestoreUi
}

enum class HostingCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class HostingAccountUi(
    val id: String,
    val displayName: String,
    val email: String?,
)

data class HostingResourceUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val url: String?,
    val status: String?,
    val region: String?,
    val kind: String?,
    val updatedAtMillis: Long?,
    /** Provider console link for this resource (Pro). */
    val dashboardUrl: String,
    /** Non-secret provider hints the adapters need (Fly app name, Amplify branch, …). */
    val metadata: Map<String, String> = emptyMap(),
    /** Where the Complete API manual request starts for this resource (iOS `defaultPath`). */
    val apiExplorerPath: String? = null,
)

data class HostingDashboardUi(
    val providerId: String,
    val account: HostingAccountUi,
    /** Every resource the provider returned (iOS pages through the whole account). */
    val resources: List<HostingResourceUi>,
    val loadedResourceCount: Int,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: HostingCacheState,
    /** Provider console home (iOS "Dashboard" action, Pro). */
    val dashboardUrl: String,
    /** Where the Complete API manual request starts (iOS `defaultPath`). */
    val apiExplorerPath: String? = null,
)

data class HostingDeploymentUi(
    val id: String,
    val title: String,
    val status: String,
    val createdAtMillis: Long?,
    val url: String?,
    val branch: String?,
    val commitMessage: String?,
)

data class HostingResourceWorkspaceUi(
    val providerId: String,
    val resourceId: String,
    /** The complete history the provider returned (no display cap). */
    val deployments: List<HostingDeploymentUi>,
    val loadedDeploymentCount: Int,
)

/** Only redacted, app-authored messages cross the data/UI boundary. */
class HostingUiException(
    message: String,
    /** True when Firebase needs the app shell to run Google sign-in before retrying. */
    val requiresGoogleSignIn: Boolean = false,
) : Exception(message) {
    override fun toString(): String = "HostingUiException(message=$message, requiresGoogleSignIn=$requiresGoogleSignIn)"
}
