package com.apoorvdarshan.verceltics.ui.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse

/** UI-only boundary for Netlify. Personal tokens never enter observable screen state. */
interface NetlifyUiGateway {
    suspend fun restore(): Result<NetlifyRestoreUi>

    suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi>

    suspend fun refresh(): Result<NetlifyDashboardUi>

    suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi>

    suspend fun disconnect(): Result<Unit>

    /**
     * Sends one Complete API raw request with the saved personal token. HTTP errors are returned
     * as responses; only validation, transport and credential problems fail.
     */
    suspend fun sendApiRequest(request: ProviderRawRequest): Result<ProviderRawResponse> =
        Result.failure(NetlifyUiException(SAMPLE_API_UNAVAILABLE))

    companion object {
        const val SAMPLE_API_UNAVAILABLE: String =
            "Sample data can’t send live API requests. Connect an account to use the Complete API."
    }
}

sealed interface NetlifyRestoreUi {
    data object NotConnected : NetlifyRestoreUi

    data class Available(val dashboard: NetlifyDashboardUi) : NetlifyRestoreUi

    data class SavedWithoutInventory(val account: NetlifyAccountUi) : NetlifyRestoreUi

    data class SavedUnavailable(val message: String) : NetlifyRestoreUi
}

enum class NetlifyCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class NetlifyAccountUi(
    val id: String,
    val displayName: String,
    val email: String?,
)

data class NetlifySiteUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val url: String?,
    val status: String?,
    val updatedAtMillis: Long?,
)

data class NetlifyDashboardUi(
    val account: NetlifyAccountUi,
    /** Intentionally bounded inventory suitable for Compose rendering. */
    val sites: List<NetlifySiteUi>,
    val loadedSiteCount: Int,
    val providerInventoryComplete: Boolean,
    val inventoryTruncatedForDisplay: Boolean,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: NetlifyCacheState,
) {
    val isPartial: Boolean
        get() = !providerInventoryComplete || inventoryTruncatedForDisplay
}

data class NetlifyDomainUi(
    val name: String,
    val kind: String,
)

data class NetlifyBuildControlsUi(
    val buildsStopped: Boolean?,
    val repositoryUrl: String?,
    val repositoryPath: String?,
    val repositoryBranch: String?,
    val baseDirectory: String?,
    val publishDirectory: String?,
    val functionsDirectory: String?,
    val buildCommand: String?,
    val allowedBranches: List<String>,
    val provider: String?,
)

data class NetlifySiteDetailsUi(
    val site: NetlifySiteUi,
    val domains: List<NetlifyDomainUi>,
    val buildControls: NetlifyBuildControlsUi?,
    val publishedDeployment: NetlifyDeploymentUi?,
)

data class NetlifyDeploymentUi(
    val id: String,
    val title: String,
    val status: String,
    val createdAtMillis: Long?,
    val url: String?,
    val branch: String?,
    val commitMessage: String?,
)

data class NetlifyBuildUi(
    val id: String,
    val deploymentId: String?,
    val commitSha: String?,
    val isDone: Boolean?,
    val error: String?,
    val createdAtMillis: Long?,
)

sealed interface NetlifyResourceUi<out T> {
    data class Available<T>(val value: T) : NetlifyResourceUi<T>

    data class Unavailable(val message: String) : NetlifyResourceUi<Nothing>
}

data class NetlifyCollectionUi<T>(
    val items: List<T>,
    val loadedItemCount: Int,
    val providerCollectionComplete: Boolean,
    val truncatedForDisplay: Boolean,
    val warning: String?,
)

data class NetlifySiteWorkspaceUi(
    val siteId: String,
    val details: NetlifyResourceUi<NetlifySiteDetailsUi>,
    val deployments: NetlifyCollectionUi<NetlifyDeploymentUi>,
    val builds: NetlifyCollectionUi<NetlifyBuildUi>,
)

/** Only redacted, user-safe messages cross the data/UI boundary. */
class NetlifyUiException(message: String) : Exception(message) {
    override fun toString(): String = "NetlifyUiException(message=$message)"
}
