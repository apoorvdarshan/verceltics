package com.apoorvdarshan.verceltics.ui.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSection
import com.apoorvdarshan.verceltics.data.sites.SiteDetailSeries
import com.apoorvdarshan.verceltics.data.sites.SiteDetailTable
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import java.time.LocalDate

/**
 * UI-only boundary for the seven API-backed site services (Google Analytics, Bing Webmaster,
 * Microsoft Clarity, Plausible, Umami, UptimeRobot, Better Stack). Credentials enter only through
 * [connect] as [SecretValue]s and never appear in observable screen state.
 */
interface SiteServicesUiGateway {
    val googleOAuthReadiness: SiteGoogleOAuthReadinessUi

    /** Offline restore of every saved service; never performs network requests. */
    suspend fun restore(): Result<SiteServicesRestoreUi>

    /** Validates an API-key provider against its official API, then saves it encrypted. */
    suspend fun connect(providerId: String, input: SiteServiceConnectionInputUi): Result<SiteServiceDashboardUi>

    /** Runs Google sign-in (PKCE) for an OAuth provider, validates it, then saves it. */
    suspend fun connectGoogle(providerId: String): Result<SiteServiceDashboardUi>

    suspend fun refresh(providerId: String): Result<SiteServiceDashboardUi>

    /**
     * Loads a provider detail workspace. Cached workspaces are returned while fresh unless
     * [forceRefresh]; Google Analytics streams an early partial payload through [onPartial].
     */
    suspend fun loadDetail(
        request: SiteServiceDetailRequestUi,
        forceRefresh: Boolean = false,
        onPartial: suspend (SiteServiceDetailUi) -> Unit = {},
    ): Result<SiteServiceDetailUi>

    suspend fun disconnect(providerId: String): Result<Unit>
}

sealed interface SiteGoogleOAuthReadinessUi {
    data object Ready : SiteGoogleOAuthReadinessUi

    data class ConfigurationNeeded(val message: String) : SiteGoogleOAuthReadinessUi
}

data class SiteServicesRestoreUi(
    val services: Map<String, SiteServiceRestoreUi>,
)

sealed interface SiteServiceRestoreUi {
    data object NotConnected : SiteServiceRestoreUi

    data class Available(val dashboard: SiteServiceDashboardUi) : SiteServiceRestoreUi

    data class SavedWithoutInventory(val accountName: String) : SiteServiceRestoreUi

    data class SavedUnavailable(val message: String) : SiteServiceRestoreUi
}

enum class SiteServiceCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class SiteMetricUi(
    val key: String,
    val label: String,
    val value: Double,
    val unit: SiteMetricUnit,
    val formattedValue: String? = null,
)

data class SiteResourceUi(
    val id: String,
    val name: String,
    val subtitle: String?,
    val url: String?,
    val status: String?,
    val updatedAtMillis: Long?,
    val metrics: List<SiteMetricUi>,
    val metadata: Map<String, String> = emptyMap(),
)

data class SiteServiceDashboardUi(
    val providerId: String,
    val accountName: String,
    /** Secondary identity such as the Google email or Umami username. */
    val accountDetail: String?,
    val status: String?,
    val resources: List<SiteResourceUi>,
    val metrics: List<SiteMetricUi>,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: SiteServiceCacheState,
    val loadedResourceCount: Int = resources.size,
    val resourcesTruncatedForDisplay: Boolean = false,
)

/** Pasted credential plus non-secret connection fields (see [SiteServiceFieldKeys]). */
class SiteServiceConnectionInputUi(
    val credential: SecretValue,
    val fields: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "SiteServiceConnectionInputUi(credential=<redacted>, fields=${fields.keys})"
}

object SiteServiceFieldKeys {
    const val SITE_URL: String = "siteURL"
    const val PROJECT_NAME: String = "projectName"
    const val SITE_ID: String = "siteID"
    const val BASE_URL: String = "baseURL"
    const val AUTH_MODE: String = "authMode"
}

enum class SiteDetailRangePresetUi(val label: String, val days: Int?) {
    DAYS_7("7D", 7),
    DAYS_30("30D", 30),
    DAYS_90("90D", 90),
    CUSTOM("Custom", null),
}

data class SiteServiceDetailQueryUi(
    val preset: SiteDetailRangePresetUi = SiteDetailRangePresetUi.DAYS_30,
    /** ISO dates (`yyyy-MM-dd`) used by the custom preset. */
    val customStartDate: String = LocalDate.now().minusDays(29).toString(),
    val customEndDate: String = LocalDate.now().toString(),
    val clarityDays: Int = 3,
    val clarityDimensions: List<String> = emptyList(),
) {
    init {
        require(clarityDays in 1..3) { "Clarity history must be one to three days." }
        require(clarityDimensions.size <= 3 && clarityDimensions.distinct().size == clarityDimensions.size) {
            "Choose up to three distinct Clarity dimensions."
        }
        LocalDate.parse(customStartDate)
        LocalDate.parse(customEndDate)
    }

    /** Stable identity for caching and request de-duplication (iOS `queryIdentity`). */
    val identity: String
        get() = listOf(preset.name, customStartDate, customEndDate, clarityDays.toString(), clarityDimensions.joinToString(","))
            .joinToString("|")
}

data class SiteServiceDetailRequestUi(
    val providerId: String,
    val resourceId: String?,
    val query: SiteServiceDetailQueryUi,
)

data class SiteServiceDetailUi(
    val providerId: String,
    val resourceId: String,
    val title: String,
    val sections: List<SiteDetailSection>,
    val series: List<SiteDetailSeries>,
    val tables: List<SiteDetailTable>,
    /** Sanitized provider responses (secrets redacted) for the raw response explorer. */
    val rawResponses: Map<String, ProviderJsonValue>,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val isPartial: Boolean = false,
)

/** Only redacted, user-safe messages cross the data/UI boundary. */
class SiteServicesUiException(message: String) : Exception(message) {
    override fun toString(): String = "SiteServicesUiException(message=$message)"
}

/** The site services handled by this stack, in Sites-tab order. */
val SiteServiceProviderIds: List<String> = SiteProvider.entries.map(SiteProvider::id)
