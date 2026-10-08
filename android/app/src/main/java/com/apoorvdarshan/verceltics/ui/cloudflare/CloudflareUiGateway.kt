package com.apoorvdarshan.verceltics.ui.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** UI-only Cloudflare boundary. Tokens and Global API Keys never enter observable Compose state. */
interface CloudflareUiGateway {
    suspend fun restore(): Result<CloudflareRestoreUi>

    /** Validates [credential] (token verify, or `/user` for a Global API Key) and saves it. */
    suspend fun connect(credential: CloudflareCredential): Result<CloudflareDashboardUi>

    suspend fun connect(apiToken: SecretValue): Result<CloudflareDashboardUi> =
        connect(CloudflareCredential.ApiToken(apiToken))

    suspend fun refresh(preferredAccountId: String? = null): Result<CloudflareDashboardUi>

    /** Removes every saved Cloudflare login (iOS "Remove All Accounts"). */
    suspend fun disconnect(): Result<Unit>

    /**
     * Every saved Cloudflare login (scoped token or email + Global API Key) for the account menu,
     * offline. Not to be confused with the Cloudflare accounts inside one login.
     */
    suspend fun savedLogins(): Result<List<ProviderAccountUi>> = Result.success(emptyList())

    /** Makes [savedAccountId] the active login and restores it offline. */
    suspend fun switchLogin(savedAccountId: String): Result<CloudflareRestoreUi> =
        Result.failure(CloudflareUiException("Switching accounts is unavailable here."))

    /** Removes one saved login and restores the one that becomes active (or NotConnected). */
    suspend fun removeLogin(savedAccountId: String): Result<CloudflareRestoreUi> =
        disconnect().map { CloudflareRestoreUi.NotConnected }

    /**
     * "Remove Current Account" when the logins could not be listed: the data layer resolves the
     * active login, so this never removes the others. Single-login gateways disconnect.
     */
    suspend fun removeActiveLogin(): Result<CloudflareRestoreUi> =
        disconnect().map { CloudflareRestoreUi.NotConnected }

    /** iOS `refreshAccountProfiles`: refreshes saved login names and token status online. */
    suspend fun refreshLoginProfiles(): Result<List<ProviderAccountUi>> = savedLogins()

    /**
     * Authenticated client for zone, Pages, Worker and storage operations. It resolves the saved
     * token per request inside the data layer, so the token still never reaches UI state. Null for
     * sample data and fakes, which keeps those screens read-only.
     */
    fun operationsClient(): CloudflareRestClient? = null

    /**
     * Every successful Cloudflare write from operations screens and the API explorer, Complete API
     * and Product Center (iOS `cloudflareDataDidChange`). Empty for sample data and fakes.
     */
    fun mutationEvents(): Flow<CloudflareMutationEvent> = emptyFlow()
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
    val authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN,
    /** The login email of a Global API Key connection; null for scoped tokens. */
    val email: String? = null,
    /** The opaque saved-login (storage) id; null for sample fixtures. */
    val savedAccountId: String? = null,
) {
    /** iOS `credentialLabel`: `email ?? "Scoped API token"`. */
    val credentialLabel: String get() = email ?: "Scoped API token"
}

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
    val routes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    /** iOS `filteredWorkers`: the script name, a route pattern or a tag (plus handlers). */
    fun matches(query: String): Boolean {
        val needle = query.trim()
        return needle.isEmpty() ||
            id.contains(needle, ignoreCase = true) ||
            routes.any { it.contains(needle, ignoreCase = true) } ||
            tags.any { it.contains(needle, ignoreCase = true) } ||
            handlers.any { it.contains(needle, ignoreCase = true) }
    }
}

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

    val authMode: CloudflareAuthMode get() = profile.authMode

    /** iOS only offers R2 to scoped API tokens (`allowsR2: authenticationMode == .apiToken`). */
    val allowsR2: Boolean get() = profile.authMode == CloudflareAuthMode.API_TOKEN

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
