package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.account.SecretValue

/** OAuth credential material. Secret fields are deliberately non-printable. */
class SearchConsoleOAuthCredential(
    val accessToken: SecretValue,
    val refreshToken: SecretValue?,
    val tokenType: String,
    val scopes: List<String>,
    val expiresAtMillis: Long,
    val subject: String?,
    val email: String?,
) {
    init {
        require(tokenType.equals("Bearer", ignoreCase = true)) { "Unsupported Google token type." }
        require(scopes.size <= MAX_SCOPES && scopes.distinct().size == scopes.size) {
            "Invalid Google OAuth scopes."
        }
        require(scopes.all { it.isNotBlank() && it.length <= MAX_SCOPE_CHARACTERS }) {
            "Invalid Google OAuth scope."
        }
        require(expiresAtMillis >= 0L) { "Invalid Google credential expiration." }
        require(subject == null || subject.isNotBlank() && subject.length <= MAX_ID_CHARACTERS) {
            "Invalid Google subject."
        }
        require(email == null || email.isNotBlank() && email.length <= MAX_EMAIL_CHARACTERS) {
            "Invalid Google email."
        }
    }

    fun needsRefresh(nowMillis: Long, leewayMillis: Long = REFRESH_LEEWAY_MILLIS): Boolean {
        require(nowMillis >= 0L && leewayMillis >= 0L) { "Invalid refresh clock." }
        return expiresAtMillis <= nowMillis + leewayMillis
    }

    fun hasReadOnlyScope(): Boolean = READ_ONLY_SCOPE in scopes

    override fun toString(): String =
        "SearchConsoleOAuthCredential(accessToken=<redacted>, refreshToken=" +
            "${if (refreshToken == null) "null" else "<redacted>"}, tokenType=$tokenType, " +
            "scopeCount=${scopes.size}, expiresAtMillis=$expiresAtMillis, subject=$subject, email=$email)"

    companion object {
        const val READ_ONLY_SCOPE = "https://www.googleapis.com/auth/webmasters.readonly"
        val REQUIRED_SCOPES = listOf("openid", "email", READ_ONLY_SCOPE)
        private const val REFRESH_LEEWAY_MILLIS = 90_000L
    }
}

data class SearchConsoleProperty(
    val siteUrl: String,
    val permissionLevel: String,
) {
    init {
        require(siteUrl.isNotBlank() && siteUrl.length <= MAX_URL_CHARACTERS) {
            "Invalid Search Console property URL."
        }
        require(permissionLevel.isNotBlank() && permissionLevel.length <= MAX_STATUS_CHARACTERS) {
            "Invalid Search Console permission."
        }
    }

    val isVerified: Boolean get() = permissionLevel != "siteUnverifiedUser"
}

data class SearchConsolePropertyList(
    val properties: List<SearchConsoleProperty>,
    val skippedEntries: Int = 0,
) {
    init {
        require(properties.size <= MAX_PROPERTIES_PER_RESPONSE) { "Too many Search Console properties." }
        require(skippedEntries >= 0) { "Invalid skipped property count." }
    }
}

data class SearchConsoleDateRange(val startDate: String, val endDate: String)

enum class SearchConsoleSearchType(val wireValue: String) {
    WEB("web"), IMAGE("image"), VIDEO("video"), NEWS("news"),
    DISCOVER("discover"), GOOGLE_NEWS("googleNews"),
}

enum class SearchConsoleDimension(val wireValue: String) {
    DATE("date"), HOUR("hour"), QUERY("query"), PAGE("page"), COUNTRY("country"),
    DEVICE("device"), SEARCH_APPEARANCE("searchAppearance"),
}

enum class SearchConsoleFilterDimension(val wireValue: String) {
    QUERY("query"), PAGE("page"), COUNTRY("country"), DEVICE("device"),
    SEARCH_APPEARANCE("searchAppearance"),
}

enum class SearchConsoleFilterOperator(val wireValue: String) {
    CONTAINS("contains"), EQUALS("equals"), NOT_CONTAINS("notContains"),
    NOT_EQUALS("notEquals"), INCLUDING_REGEX("includingRegex"),
    EXCLUDING_REGEX("excludingRegex"),
}

data class SearchConsoleDimensionFilter(
    val dimension: SearchConsoleFilterDimension,
    val operator: SearchConsoleFilterOperator = SearchConsoleFilterOperator.EQUALS,
    val expression: String,
)

data class SearchConsoleGoogleIdentity(
    val subject: String,
    val email: String?,
) {
    init {
        require(subject.isNotBlank() && subject.length <= MAX_ID_CHARACTERS) {
            "Invalid Google subject."
        }
        require(email == null || email.isNotBlank() && email.length <= MAX_EMAIL_CHARACTERS) {
            "Invalid Google email."
        }
    }
}

data class SearchConsoleDimensionFilterGroup(
    val filters: List<SearchConsoleDimensionFilter>,
)

enum class SearchConsoleAggregationType(val wireValue: String) {
    AUTO("auto"), BY_PAGE("byPage"), BY_PROPERTY("byProperty"),
    BY_NEWS_SHOWCASE_PANEL("byNewsShowcasePanel"),
}

enum class SearchConsoleDataState(val wireValue: String) {
    FINAL("final"), ALL("all"), HOURLY_ALL("hourly_all"),
}

data class SearchConsoleAnalyticsQuery(
    val dateRange: SearchConsoleDateRange,
    val dimensions: List<SearchConsoleDimension> = emptyList(),
    val searchType: SearchConsoleSearchType = SearchConsoleSearchType.WEB,
    val dimensionFilterGroups: List<SearchConsoleDimensionFilterGroup> = emptyList(),
    val aggregationType: SearchConsoleAggregationType = SearchConsoleAggregationType.AUTO,
    val rowLimit: Int = 1_000,
    val startRow: Int = 0,
    val dataState: SearchConsoleDataState = SearchConsoleDataState.FINAL,
) {
    fun page(startingAt: Int): SearchConsoleAnalyticsQuery = copy(startRow = startingAt)
}

data class SearchConsoleAnalyticsRow(
    val keys: List<String>,
    val clicks: Double,
    val impressions: Double,
    val ctr: Double,
    val position: Double,
) {
    init {
        require(keys.size <= MAX_ANALYTICS_KEYS && keys.all { it.length <= MAX_URL_CHARACTERS }) {
            "Invalid Search Console row keys."
        }
        require(listOf(clicks, impressions, ctr, position).all(Double::isFinite)) {
            "Invalid Search Console metric."
        }
    }
}

data class SearchConsoleAnalyticsMetadata(
    val firstIncompleteDate: String?,
    val firstIncompleteHour: String?,
)

data class SearchConsoleAnalyticsResponse(
    val rows: List<SearchConsoleAnalyticsRow>,
    val responseAggregationType: String?,
    val metadata: SearchConsoleAnalyticsMetadata?,
) {
    init {
        require(rows.size <= MAX_ANALYTICS_AGGREGATE_ROWS) { "Too many Search Console rows." }
    }
}

data class SearchConsoleSitemapContent(
    val type: String,
    val submitted: Long,
    val indexed: Long?,
)

data class SearchConsoleSitemap(
    val path: String,
    val lastSubmitted: String?,
    val isPending: Boolean,
    val isSitemapsIndex: Boolean,
    val type: String?,
    val lastDownloaded: String?,
    val warnings: Long,
    val errors: Long,
    val contents: List<SearchConsoleSitemapContent>,
)

data class SearchConsoleIndexStatus(
    val sitemaps: List<String>,
    val referringUrls: List<String>,
    val verdict: String?,
    val coverageState: String?,
    val robotsTxtState: String?,
    val indexingState: String?,
    val lastCrawlTime: String?,
    val pageFetchState: String?,
    val googleCanonical: String?,
    val userCanonical: String?,
    val crawledAs: String?,
)

data class SearchConsoleInspectionIssue(
    val type: String?,
    val severity: String?,
    val message: String?,
)

data class SearchConsoleAmpResult(
    val issues: List<SearchConsoleInspectionIssue>,
    val verdict: String?,
    val ampUrl: String?,
    val robotsTxtState: String?,
    val indexingState: String?,
    val ampIndexStatusVerdict: String?,
    val lastCrawlTime: String?,
    val pageFetchState: String?,
)

data class SearchConsoleMobileUsabilityResult(
    val issues: List<SearchConsoleInspectionIssue>,
    val verdict: String?,
)

data class SearchConsoleRichResultItem(
    val name: String?,
    val issues: List<SearchConsoleInspectionIssue>,
)

data class SearchConsoleDetectedRichResult(
    val richResultType: String,
    val items: List<SearchConsoleRichResultItem>,
)

data class SearchConsoleRichResultsResult(
    val detectedItems: List<SearchConsoleDetectedRichResult>,
    val verdict: String?,
)

data class SearchConsoleUrlInspectionResult(
    val inspectionResultLink: String?,
    val indexStatus: SearchConsoleIndexStatus?,
    val ampResult: SearchConsoleAmpResult?,
    val mobileUsabilityResult: SearchConsoleMobileUsabilityResult?,
    val richResultsResult: SearchConsoleRichResultsResult?,
)

/**
 * Free 28-day overview for one property: by-property Search Analytics totals, submitted sitemap
 * count and the property URL's index status. Mirrors iOS `fetchSearchConsoleProperty`. A null
 * metric means that part could not be loaded; Google omitting every row means zero traffic.
 */
data class SearchConsolePropertySummary(
    val siteUrl: String,
    val startDate: String,
    val endDate: String,
    val clicks: Double?,
    val impressions: Double?,
    val ctr: Double?,
    val position: Double?,
    val sitemapCount: Int?,
    val inspectedUrl: String?,
    val indexVerdict: String?,
    val coverageState: String?,
    val lastCrawlTime: String?,
    val analyticsLoaded: Boolean,
    val sitemapsLoaded: Boolean,
    val inspectionLoaded: Boolean,
    val warnings: List<String>,
) {
    init {
        require(listOfNotNull(clicks, impressions, ctr, position).all(Double::isFinite)) {
            "Invalid Search Console summary metric."
        }
        require(sitemapCount == null || sitemapCount >= 0) { "Invalid sitemap count." }
        require(warnings.size <= MAX_WARNINGS) { "Too many Search Console summary warnings." }
    }

    /** Traffic or sitemap counts are missing, so aggregate totals are a lower bound. */
    val metricsArePartial: Boolean get() = !analyticsLoaded || !sitemapsLoaded

    /** iOS status: "Indexed" for a passing verdict, else Google's coverage text, else the verdict. */
    val indexStatus: String? get() = searchConsoleIndexStatusLabel(indexVerdict, coverageState)
}

internal fun searchConsoleIndexStatusLabel(verdict: String?, coverageState: String?): String? = when {
    verdict == "PASS" -> "Indexed"
    !coverageState.isNullOrBlank() -> coverageState
    !verdict.isNullOrBlank() -> verdict.lowercase().replaceFirstChar { it.uppercase() }
    else -> null
}

/** The page iOS inspects for a property overview: the domain root or the URL-prefix itself. */
internal fun searchConsoleOverviewInspectionUrl(siteUrl: String): String? {
    if (siteUrl.startsWith("sc-domain:")) {
        val domain = siteUrl.removePrefix("sc-domain:").trim()
        return if (domain.isEmpty() || domain.any { it == '/' || it.isWhitespace() }) null else "https://$domain/"
    }
    val uri = runCatching { java.net.URI(siteUrl) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    return if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()) siteUrl else null
}

data class SearchConsoleSnapshot(
    val properties: List<SearchConsoleProperty>,
    val fetchedAtMillis: Long,
    val propertiesComplete: Boolean,
    val warnings: List<String>,
) {
    init {
        require(fetchedAtMillis >= 0L) { "Invalid Search Console snapshot time." }
        require(warnings.isEmpty() == propertiesComplete) { "Snapshot completeness and warnings disagree." }
        require(warnings.size <= MAX_WARNINGS && warnings.all { it.isNotBlank() && it.length <= MAX_WARNING_CHARACTERS }) {
            "Invalid Search Console warning."
        }
    }
}

enum class SearchConsoleFailureKind {
    AUTHENTICATION, AUTHORIZATION, EXPIRED_CREDENTIAL, RATE_LIMITED, NOT_FOUND,
    INVALID_REQUEST, TEMPORARY, NETWORK, INVALID_RESPONSE, CONFIGURATION,
    SECURE_STORAGE, LIMIT_REACHED,
}

data class SearchConsoleFailure(
    val kind: SearchConsoleFailureKind,
    val message: String,
    val statusCode: Int? = null,
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_WARNING_CHARACTERS) {
            "A Search Console failure needs a safe message."
        }
    }
}

sealed interface SearchConsoleFetchResult<out T> {
    data class Complete<T>(val value: T) : SearchConsoleFetchResult<T>
    data class Partial<T>(val value: T, val failure: SearchConsoleFailure) : SearchConsoleFetchResult<T>
    data class Failure(val failure: SearchConsoleFailure) : SearchConsoleFetchResult<Nothing>
}

data class SearchConsoleStoredConnection(
    val id: String,
    val credential: SearchConsoleOAuthCredential,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val cachedSnapshot: SearchConsoleSnapshot?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS) { "Invalid Search Console account id." }
        require(createdAtMillis >= 0L && updatedAtMillis >= createdAtMillis) { "Invalid account timestamps." }
    }

    override fun toString(): String =
        "SearchConsoleStoredConnection(id=$id, credential=<redacted>, createdAtMillis=$createdAtMillis, " +
            "updatedAtMillis=$updatedAtMillis, cachedSnapshot=${cachedSnapshot != null})"
}

enum class SearchConsoleRestoreProblem { SAVED_RECORD_UNREADABLE, SECURE_STORAGE_UNAVAILABLE }

sealed interface SearchConsoleRestoreResult {
    data object NotConnected : SearchConsoleRestoreResult
    data class Restored(
        val id: String,
        val subject: String?,
        val email: String?,
        val cachedSnapshot: SearchConsoleSnapshot?,
        val cacheIsStale: Boolean,
        val credentialNeedsRefresh: Boolean,
    ) : SearchConsoleRestoreResult
    data class Unavailable(val problem: SearchConsoleRestoreProblem) : SearchConsoleRestoreResult
}

internal const val MAX_ID_CHARACTERS = 1_024
internal const val MAX_EMAIL_CHARACTERS = 2_048
internal const val MAX_SCOPE_CHARACTERS = 2_048
internal const val MAX_SCOPES = 32
internal const val MAX_URL_CHARACTERS = 8_192
internal const val MAX_STATUS_CHARACTERS = 512
internal const val MAX_WARNING_CHARACTERS = 2_048
internal const val MAX_WARNINGS = 16
internal const val MAX_PROPERTIES_PER_RESPONSE = 10_000
internal const val MAX_ANALYTICS_ROWS_PER_PAGE = 25_000
internal const val MAX_ANALYTICS_AGGREGATE_ROWS = 100_000
internal const val MAX_ANALYTICS_KEYS = 7
internal const val MAX_INSPECTION_ITEMS = 256
