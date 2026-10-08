package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient

/** UI-only Cloudflare boundary. API tokens never enter observable Compose state. */
interface CloudflareUiGateway {
    suspend fun restore(): Result<CloudflareRestoreUi>

    suspend fun connect(apiToken: SecretValue): Result<CloudflareDashboardUi>

    suspend fun refresh(preferredAccountId: String? = null): Result<CloudflareDashboardUi>

    suspend fun disconnect(): Result<Unit>

    /**
     * Authenticated client for zone, Pages, Worker and storage operations. It resolves the saved
     * token per request inside the data layer, so the token still never reaches UI state. Null for
     * sample data and fakes, which keeps those screens read-only.
     */
    fun operationsClient(): CloudflareRestClient? = null
}

sealed interface CloudflareRestoreUi {
    data object NotConnected : CloudflareRestoreUi

    data class Available(val dashboard: CloudflareDashboardUi) : CloudflareRestoreUi

    data class SavedWithoutInventory(val profile: CloudflareProfileUi) : CloudflareRestoreUi

    data class SavedUnavailable(val message: String) : CloudflareRestoreUi
}

enum class CloudflareCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class CloudflareProfileUi(
    val id: String,
    val displayName: String,
    val tokenStatus: String,
)

data class CloudflareAccountUi(
    val id: String,
    val name: String,
    val type: String?,
)

data class CloudflareZoneUi(
    val id: String,
    val name: String,
    val status: String?,
    val type: String?,
    val paused: Boolean?,
    val accountName: String?,
    val planName: String?,
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true) && paused != true
}

data class CloudflarePagesProjectUi(
    val id: String,
    val name: String,
    val subdomain: String?,
    val domains: List<String>,
    val productionBranch: String?,
    val latestDeploymentStatus: String?,
)

data class CloudflareWorkerUi(
    val id: String,
    val modifiedOn: String?,
    val compatibilityDate: String?,
    val handlers: List<String>,
    val hasAssets: Boolean?,
    val hasModules: Boolean?,
)

data class CloudflareInventoryUi(
    val accountId: String,
    val zones: List<CloudflareZoneUi>,
    val pagesProjects: List<CloudflarePagesProjectUi>,
    val workers: List<CloudflareWorkerUi>,
    val loadedZoneCount: Int,
    val loadedPagesProjectCount: Int,
    val loadedWorkerCount: Int,
    val zonesComplete: Boolean,
    val pagesComplete: Boolean,
    val workersComplete: Boolean,
    val zonesTruncatedForDisplay: Boolean,
    val pagesTruncatedForDisplay: Boolean,
    val workersTruncatedForDisplay: Boolean,
    val warnings: List<String>,
) {
    val isPartial: Boolean
        get() = !zonesComplete || !pagesComplete || !workersComplete ||
            zonesTruncatedForDisplay || pagesTruncatedForDisplay || workersTruncatedForDisplay
}

data class CloudflareDashboardUi(
    val profile: CloudflareProfileUi,
    val accounts: List<CloudflareAccountUi>,
    val loadedAccountCount: Int,
    val accountsComplete: Boolean,
    val accountsTruncatedForDisplay: Boolean,
    val selectedAccountId: String?,
    val inventory: CloudflareInventoryUi?,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: CloudflareCacheState,
) {
    val selectedAccount: CloudflareAccountUi?
        get() = selectedAccountId?.let { selected -> accounts.firstOrNull { it.id == selected } }

    val isPartial: Boolean
        get() = !accountsComplete || accountsTruncatedForDisplay || inventory?.isPartial == true
}

enum class CloudflareResourceKind {
    ZONE,
    PAGES,
    WORKER,
}

data class CloudflareResourceSelection(
    val kind: CloudflareResourceKind,
    val id: String,
)

/** Only redacted provider-safe messages cross the Cloudflare data/UI boundary. */
class CloudflareUiException(message: String) : Exception(message) {
    override fun toString(): String = "CloudflareUiException(message=$message)"
}
