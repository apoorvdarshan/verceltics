package com.apoorvdarshan.verceltics.ui.pagespeed

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedReport

/** UI boundary for the native PageSpeed & CrUX slice. API keys never enter UI state models. */
interface PageSpeedUiGateway {
    suspend fun restore(): Result<PageSpeedRestoreUi>

    suspend fun connect(apiKey: SecretValue, siteUrl: String): Result<PageSpeedDashboardUi>

    suspend fun refresh(): Result<PageSpeedDashboardUi>

    suspend fun disconnect(): Result<Unit>
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
) {
    val isPartial: Boolean
        get() = sources.desktop == PageSpeedSourceUiState.UNAVAILABLE ||
            sources.crux == PageSpeedSourceUiState.UNAVAILABLE
}

/** Only safe, already-redacted messages may cross the gateway boundary. */
class PageSpeedUiException(message: String) : Exception(message) {
    override fun toString(): String = "PageSpeedUiException(message=$message)"
}
