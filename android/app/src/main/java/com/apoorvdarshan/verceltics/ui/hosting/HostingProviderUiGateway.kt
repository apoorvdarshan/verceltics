package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials

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
)

data class HostingDashboardUi(
    val providerId: String,
    val account: HostingAccountUi,
    /** Intentionally bounded inventory suitable for Compose rendering. */
    val resources: List<HostingResourceUi>,
    val loadedResourceCount: Int,
    val truncatedForDisplay: Boolean,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: HostingCacheState,
    /** Provider console home (iOS "Dashboard" action, Pro). */
    val dashboardUrl: String,
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
    val deployments: List<HostingDeploymentUi>,
    val loadedDeploymentCount: Int,
    val truncatedForDisplay: Boolean,
)

/** Only redacted, app-authored messages cross the data/UI boundary. */
class HostingUiException(
    message: String,
    /** True when Firebase needs the app shell to run Google sign-in before retrying. */
    val requiresGoogleSignIn: Boolean = false,
) : Exception(message) {
    override fun toString(): String = "HostingUiException(message=$message, requiresGoogleSignIn=$requiresGoogleSignIn)"
}
