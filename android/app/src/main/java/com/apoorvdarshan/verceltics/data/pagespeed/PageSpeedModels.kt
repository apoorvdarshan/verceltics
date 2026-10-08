package com.apoorvdarshan.verceltics.data.pagespeed

import java.net.URI

enum class PageSpeedStrategy(val wireValue: String, val label: String) {
    MOBILE("mobile", "Mobile"),
    DESKTOP("desktop", "Desktop"),
}

enum class PageSpeedMetricUnit {
    SCORE,
    MILLISECONDS,
    RATIO,
}

data class PageSpeedMetric(
    val key: String,
    val label: String,
    val value: Double,
    val unit: PageSpeedMetricUnit,
    val formattedValue: String? = null,
) {
    init {
        require(key.isNotBlank() && label.isNotBlank()) { "A PageSpeed metric needs an identity." }
        require(value.isFinite()) { "A PageSpeed metric value must be finite." }
    }
}

enum class PageSpeedSourceState {
    AVAILABLE,
    UNAVAILABLE,
}

data class PageSpeedSourceAvailability(
    val mobile: PageSpeedSourceState = PageSpeedSourceState.AVAILABLE,
    val desktop: PageSpeedSourceState,
    val crux: PageSpeedSourceState,
) {
    val isPartial: Boolean
        get() = desktop != PageSpeedSourceState.AVAILABLE || crux != PageSpeedSourceState.AVAILABLE
}

data class PageSpeedSnapshot(
    val siteUrl: URI,
    val siteName: String,
    val status: String,
    val metrics: List<PageSpeedMetric>,
    val fetchedAtMillis: Long,
    val availability: PageSpeedSourceAvailability,
    val warnings: List<String>,
) {
    init {
        require(siteUrl.scheme.equals("https", ignoreCase = true) && siteUrl.host != null) {
            "A PageSpeed snapshot requires an HTTPS site URL."
        }
        require(siteName.isNotBlank() && status.isNotBlank()) {
            "A PageSpeed snapshot requires a name and status."
        }
        require(fetchedAtMillis >= 0) { "A PageSpeed snapshot timestamp is invalid." }
        require(warnings.isNotEmpty() == availability.isPartial) {
            "PageSpeed warnings and source availability disagree."
        }
    }
}

enum class PageSpeedFailureKind {
    AUTHENTICATION,
    RATE_LIMITED,
    NOT_FOUND,
    TEMPORARY,
    NETWORK,
    INVALID_RESPONSE,
    CONFIGURATION,
}

data class PageSpeedFailure(
    val kind: PageSpeedFailureKind,
    val message: String,
    val statusCode: Int? = null,
) {
    init {
        require(message.isNotBlank()) { "A PageSpeed failure needs a safe message." }
    }
}

/**
 * [report] carries the full in-memory breakdown built from the same responses. It is never
 * persisted, so a restored connection shows the cached summary until the next live audit.
 */
sealed interface PageSpeedFetchResult {
    data class Complete(
        val snapshot: PageSpeedSnapshot,
        val report: PageSpeedReport? = null,
    ) : PageSpeedFetchResult

    data class Partial(
        val snapshot: PageSpeedSnapshot,
        val report: PageSpeedReport? = null,
    ) : PageSpeedFetchResult {
        init {
            require(snapshot.availability.isPartial && snapshot.warnings.isNotEmpty()) {
                "A partial PageSpeed result must explain its missing sources."
            }
        }
    }

    data class Failure(val failure: PageSpeedFailure) : PageSpeedFetchResult
}

data class PageSpeedStoredConnection(
    val id: String,
    val credentials: PageSpeedCredentials,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val cachedSnapshot: PageSpeedSnapshot? = null,
) {
    init {
        require(id.isNotBlank()) { "A PageSpeed connection id is required." }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) {
            "PageSpeed connection timestamps are invalid."
        }
        require(cachedSnapshot == null || cachedSnapshot.siteUrl == credentials.siteUrl) {
            "A cached snapshot belongs to a different PageSpeed site."
        }
    }

    override fun toString(): String =
        "PageSpeedStoredConnection(id=$id, siteUrl=${credentials.siteUrl}, " +
            "createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis, " +
            "cachedSnapshot=${cachedSnapshot != null}, apiKey=<redacted>)"
}

enum class PageSpeedRestoreProblem {
    SAVED_RECORD_UNREADABLE,
    SECURE_STORAGE_UNAVAILABLE,
}

/** Offline-only restore state. It never performs a provider request and never exposes the API key. */
sealed interface PageSpeedRestoreResult {
    data object NotConnected : PageSpeedRestoreResult

    data class Restored(
        val connectionId: String,
        val siteUrl: URI,
        val cachedSnapshot: PageSpeedSnapshot?,
        val cacheIsStale: Boolean,
    ) : PageSpeedRestoreResult

    data class Unavailable(val problem: PageSpeedRestoreProblem) : PageSpeedRestoreResult
}
