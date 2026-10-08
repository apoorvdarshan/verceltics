package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthScopes
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The seven API-backed site services. Ids match `domain/IntegrationCatalog`. */
enum class SiteProvider(
    val id: String,
    val displayName: String,
    private val providerLifetimeMillis: Long,
    private val detailLifetimeMillis: Long,
) {
    GOOGLE_ANALYTICS("googleAnalytics", "Google Analytics", 5 * MINUTE, 15 * MINUTE),
    BING_WEBMASTER("bingWebmaster", "Bing Webmaster", 15 * MINUTE, 15 * MINUTE),
    CLARITY("clarity", "Microsoft Clarity", 6 * 60 * MINUTE, 6 * 60 * MINUTE),
    PLAUSIBLE("plausible", "Plausible", 5 * MINUTE, 5 * MINUTE),
    UMAMI("umami", "Umami", 5 * MINUTE, 5 * MINUTE),
    UPTIME_ROBOT("uptimeRobot", "UptimeRobot", 2 * MINUTE, 2 * MINUTE),
    BETTER_STACK("betterStack", "Better Stack", 2 * MINUTE, 2 * MINUTE),
    ;

    val usesGoogleOAuth: Boolean get() = this == GOOGLE_ANALYTICS

    /** iOS `SiteStore.cacheLifetime`: never shorter than the 15-minute inventory freshness. */
    val snapshotCacheLifetimeMillis: Long get() = maxOf(INVENTORY_FRESHNESS_MILLIS, providerLifetimeMillis)

    /** iOS detail workspace cache lifetime. */
    val detailCacheLifetimeMillis: Long get() = detailLifetimeMillis

    /** Singular resource noun used for counts ("Property", "Site", "Monitor"). */
    val resourceNoun: String
        get() = when (this) {
            GOOGLE_ANALYTICS -> "Property"
            UPTIME_ROBOT, BETTER_STACK -> "Monitor"
            else -> "Site"
        }

    /** Google scopes requested for OAuth providers; empty for API-key providers. */
    val oauthScopes: Set<String>
        get() = if (this == GOOGLE_ANALYTICS) GoogleOAuthScopes.GOOGLE_ANALYTICS_READ_ONLY else emptySet()

    companion object {
        const val INVENTORY_FRESHNESS_MILLIS: Long = 15 * MINUTE

        fun fromId(id: String): SiteProvider? = entries.firstOrNull { it.id == id }

        val ids: Set<String> = entries.mapTo(LinkedHashSet()) { it.id }
    }
}

private const val MINUTE = 60_000L

enum class SiteMetricUnit {
    COUNT,
    PERCENT,
    MILLISECONDS,
    SECONDS,
    BYTES,
    SCORE,
    RATIO,
    POSITION,
    NONE,
}

data class SiteMetric(
    val key: String,
    val label: String,
    val value: Double,
    val unit: SiteMetricUnit = SiteMetricUnit.NONE,
    val formattedValue: String? = null,
    val resourceId: String? = null,
) {
    val id: String get() = "${resourceId ?: "account"}|$key"
}

data class SiteResource(
    val id: String,
    val provider: SiteProvider,
    val name: String,
    val subtitle: String? = null,
    val url: String? = null,
    val status: String? = null,
    val updatedAtMillis: Long? = null,
    val metrics: List<SiteMetric> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

data class SiteSnapshot(
    val provider: SiteProvider,
    val resources: List<SiteResource> = emptyList(),
    val metrics: List<SiteMetric> = emptyList(),
    val status: String? = null,
    val fetchedAtMillis: Long,
    val warnings: List<String> = emptyList(),
)

/** A validated connection: display name, first snapshot, and discovered non-secret identity. */
data class SiteValidatedConnection(
    val name: String,
    val snapshot: SiteSnapshot,
    val connectionMetadata: Map<String, String>,
)

/**
 * Request-time account. Credentials are [SecretValue]s; Google providers receive a fresh
 * [googleAccessToken] from `GoogleOAuthSession` instead of a pasted credential.
 */
class SiteServiceAccount(
    val provider: SiteProvider,
    val credential: SecretValue?,
    val metadata: Map<String, String> = emptyMap(),
    val googleAccessToken: SecretValue? = null,
) {
    override fun toString(): String =
        "SiteServiceAccount(provider=${provider.id}, credential=${if (credential == null) "none" else "<redacted>"}, " +
            "metadataKeys=${metadata.keys}, googleAccessToken=${if (googleAccessToken == null) "none" else "<redacted>"})"
}

/** User-safe provider failure. Messages mirror the iOS `SiteIntegrationsAPIError` copy. */
class SiteServiceException(
    val kind: Kind,
    message: String,
    val statusCode: Int? = null,
) : Exception(message) {
    enum class Kind {
        INVALID_CONFIGURATION,
        OAUTH_NOT_CONFIGURED,
        INVALID_RESPONSE,
        REQUEST_FAILED,
        DECODING,
        NETWORK,
    }

    val isUnauthorized: Boolean get() = kind == Kind.REQUEST_FAILED && statusCode == 401

    override fun toString(): String = "SiteServiceException(kind=$kind, status=$statusCode, message=$message)"

    companion object {
        fun invalidConfiguration(message: String) = SiteServiceException(Kind.INVALID_CONFIGURATION, message)

        fun oauthNotConfigured(provider: SiteProvider, scopes: Collection<String>) = SiteServiceException(
            Kind.OAUTH_NOT_CONFIGURED,
            "${provider.displayName} requires the Verceltics Android OAuth client to be configured before " +
                "accounts can connect. Required scope: ${scopes.joinToString(", ")}.",
        )

        fun invalidResponse() = SiteServiceException(Kind.INVALID_RESPONSE, "The provider returned an invalid response.")

        fun requestFailed(status: Int, message: String) = SiteServiceException(
            Kind.REQUEST_FAILED,
            if (message.isEmpty()) "Request failed (HTTP $status)." else "Request failed (HTTP $status): $message",
            status,
        )

        fun detailRequestFailed(status: Int) = SiteServiceException(
            Kind.REQUEST_FAILED,
            "The provider request failed (HTTP $status).",
            status,
        )

        fun decoding(message: String) = SiteServiceException(Kind.DECODING, "Could not read the provider response: $message")

        fun detailDecoding(message: String) = SiteServiceException(Kind.DECODING, message)

        fun network(message: String) = SiteServiceException(Kind.NETWORK, message)
    }
}

// MARK: - Detail models (iOS SiteIntegrationDetailModels)

data class SiteDetailField(
    val key: String,
    val label: String,
    val value: ProviderJsonValue,
)

data class SiteDetailSection(
    val id: String,
    val title: String,
    val fields: List<SiteDetailField>,
)

data class SiteDetailSeriesPoint(
    val x: String,
    val values: Map<String, Double>,
)

data class SiteDetailSeries(
    val id: String,
    val title: String,
    val metricLabels: Map<String, String>,
    val points: List<SiteDetailSeriesPoint>,
)

data class SiteDetailTable(
    val id: String,
    val title: String,
    val columns: List<String>,
    val rows: List<Map<String, ProviderJsonValue>>,
    val nextCursor: String? = null,
)

data class SiteDetailPayload(
    val provider: SiteProvider,
    val resourceId: String,
    val title: String,
    val sections: List<SiteDetailSection> = emptyList(),
    val series: List<SiteDetailSeries> = emptyList(),
    val tables: List<SiteDetailTable> = emptyList(),
    val rawResponses: Map<String, ProviderJsonValue> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val fetchedAtMillis: Long,
)

/**
 * Inclusive report range. Day strings use the user's selected calendar day in [zone] rather than
 * converting to UTC, matching iOS `SiteIntegrationDetailRange.dateString`.
 */
class SiteDetailRange private constructor(
    val startMillis: Long,
    val endMillis: Long,
    val zone: ZoneId,
) {
    val startDate: String get() = dateString(startMillis, zone)
    val endDate: String get() = dateString(endMillis, zone)
    val startSeconds: Long get() = Math.floorDiv(startMillis, 1_000L)
    val endSeconds: Long get() = Math.floorDiv(endMillis, 1_000L)

    /** Calendar days covered, inclusive of both ends (iOS timeline row budget). */
    val dayCount: Int
        get() {
            val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
            return (ChronoUnit.DAYS.between(start, end) + 1).toInt().coerceAtLeast(1)
        }

    fun withStart(startMillis: Long): SiteDetailRange = of(startMillis, endMillis, zone)

    override fun equals(other: Any?): Boolean =
        other is SiteDetailRange && other.startMillis == startMillis && other.endMillis == endMillis && other.zone == zone

    override fun hashCode(): Int = (startMillis * 31 + endMillis).hashCode() * 31 + zone.hashCode()

    override fun toString(): String = "SiteDetailRange($startDate–$endDate)"

    companion object {
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun of(startMillis: Long, endMillis: Long, zone: ZoneId): SiteDetailRange =
            SiteDetailRange(minOf(startMillis, endMillis), maxOf(startMillis, endMillis), zone)

        /** The last [days] calendar days ending now (iOS 7D/30D/90D presets). */
        fun lastDays(days: Int, nowMillis: Long, zone: ZoneId): SiteDetailRange {
            require(days >= 1) { "A report range needs at least one day." }
            val end = Instant.ofEpochMilli(nowMillis).atZone(zone)
            return of(end.minusDays((days - 1).toLong()).toInstant().toEpochMilli(), nowMillis, zone)
        }

        /** A custom calendar range; the end is clamped to now. */
        fun custom(startDate: LocalDate, endDate: LocalDate, nowMillis: Long, zone: ZoneId): SiteDetailRange {
            val first = minOf(startDate, endDate)
            val last = maxOf(startDate, endDate)
            val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val endOfDay = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val end = minOf(endOfDay, nowMillis).coerceAtLeast(start)
            return of(start, end, zone)
        }

        fun dateString(millis: Long, zone: ZoneId): String =
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DAY_FORMAT)
    }
}

sealed interface UmamiAuthentication {
    class CloudApiKey(val key: SecretValue) : UmamiAuthentication {
        override fun toString(): String = "CloudApiKey(<redacted>)"
    }

    class BearerToken(val token: SecretValue) : UmamiAuthentication {
        override fun toString(): String = "BearerToken(<redacted>)"
    }
}

/** Typed, read-only detail workspace requests (iOS `SiteIntegrationDetailRequest`). */
sealed interface SiteDetailRequest {
    val provider: SiteProvider

    class GoogleAnalytics(val propertyId: String, val accessToken: SecretValue, val range: SiteDetailRange) :
        SiteDetailRequest {
        override val provider get() = SiteProvider.GOOGLE_ANALYTICS
    }

    class BingWebmaster(val siteUrl: String, val apiKey: SecretValue) : SiteDetailRequest {
        override val provider get() = SiteProvider.BING_WEBMASTER
    }

    class Clarity(val apiToken: SecretValue, val days: Int, val dimensions: List<String>) : SiteDetailRequest {
        override val provider get() = SiteProvider.CLARITY
    }

    class Plausible(val siteId: String, val apiKey: SecretValue, val range: SiteDetailRange) : SiteDetailRequest {
        override val provider get() = SiteProvider.PLAUSIBLE
    }

    class Umami(
        val websiteId: String,
        val baseUrl: URI,
        val authentication: UmamiAuthentication,
        val range: SiteDetailRange,
    ) : SiteDetailRequest {
        override val provider get() = SiteProvider.UMAMI
    }

    class UptimeRobot(val monitorId: String, val readOnlyApiKey: SecretValue, val range: SiteDetailRange) :
        SiteDetailRequest {
        override val provider get() = SiteProvider.UPTIME_ROBOT
    }

    class BetterStack(val monitorId: String, val token: SecretValue, val range: SiteDetailRange) :
        SiteDetailRequest {
        override val provider get() = SiteProvider.BETTER_STACK
    }
}

/** Clarity export dimensions accepted by the live-insights endpoint (max three). */
val ClarityDimensionOptions: List<String> = listOf(
    "Browser", "Device", "Country/Region", "OS", "Source", "Medium", "Campaign", "Channel", "URL",
)
