package com.apoorvdarshan.verceltics.ui.pagespeed

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReport
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi

/** UI boundary for the native PageSpeed & CrUX slice. API keys never enter UI state models. */
interface PageSpeedUiGateway {
    suspend fun restore(): Result<PageSpeedRestoreUi>

    suspend fun connect(apiKey: SecretValue, siteUrl: String): Result<PageSpeedDashboardUi>

    suspend fun refresh(): Result<PageSpeedDashboardUi>

    /** Removes the active site (the single-site "Disconnect"). */
    suspend fun disconnect(): Result<Unit>

    /** Saved sites and the active one, read offline. */
    suspend fun accounts(): Result<SiteAccountsUi> = Result.success(SiteAccountsUi.EMPTY)

    /** Makes a saved site active and returns its offline restore (no audit request). */
    suspend fun switchAccount(accountId: String): Result<PageSpeedRestoreUi> =
        Result.failure(PageSpeedUiException("Switching PageSpeed sites is not available."))

    /** Removes one saved site and returns the restore of the site that becomes active. */
    suspend fun removeAccount(accountId: String): Result<PageSpeedRestoreUi> =
        disconnect().map { PageSpeedRestoreUi.NotConnected }

    /** Removes every saved PageSpeed site. */
    suspend fun removeAllAccounts(): Result<Unit> = disconnect()
}

sealed interface PageSpeedRestoreUi {
    data object NotConnected : PageSpeedRestoreUi

    data class Available(val dashboard: PageSpeedDashboardUi) : PageSpeedRestoreUi

    data class SavedWithoutSnapshot(val siteUrl: String) : PageSpeedRestoreUi

    data class SavedUnavailable(
        val message: String,
        val canDisconnect: Boolean = true,
    ) : PageSpeedRestoreUi
}

enum class PageSpeedCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

enum class PageSpeedSourceUiState {
    AVAILABLE,
    UNAVAILABLE,
}

data class PageSpeedSourcesUi(
    val mobile: PageSpeedSourceUiState,
    val desktop: PageSpeedSourceUiState,
    val crux: PageSpeedSourceUiState,
)

data class PageSpeedMetricUi(
    val key: String,
    val label: String,
    val value: Double,
    val unit: PageSpeedMetricUnit,
    val formattedValue: String?,
)

data class PageSpeedDashboardUi(
    val siteUrl: String,
    val siteName: String,
    val status: String,
    val metrics: List<PageSpeedMetricUi>,
    val fetchedAtMillis: Long,
    val sources: PageSpeedSourcesUi,
    val warnings: List<String>,
    val cacheState: PageSpeedCacheState,
    /** Full Lighthouse + CrUX breakdown from the latest live audit; null for a restored cache. */
    val report: PageSpeedReport? = null,
    /** The saved site (account) this dashboard belongs to; null for fixtures. */
    val accountId: String? = null,
) {
    val isPartial: Boolean
        get() = sources.desktop == PageSpeedSourceUiState.UNAVAILABLE ||
            sources.crux == PageSpeedSourceUiState.UNAVAILABLE
}

/** Only safe, already-redacted messages may cross the gateway boundary. */
class PageSpeedUiException(message: String) : Exception(message) {
    override fun toString(): String = "PageSpeedUiException(message=$message)"
}
