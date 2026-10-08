package com.apoorvdarshan.verceltics.ui

import androidx.compose.ui.graphics.ImageBitmap

/**
 * UI-facing boundary for the first Android provider slice.
 *
 * The Compose layer deliberately knows nothing about tokens at rest, HTTP clients, or JSON. A
 * native data adapter can implement this contract with Android Keystore-backed persistence and
 * the Vercel API without leaking those concerns into screen state.
 *
 * Methods added after the first slice have conservative defaults so fixture gateways keep
 * compiling; the native gateway overrides every one of them.
 */
interface VercelUiGateway {
    suspend fun restore(): Result<VercelRestoreUi>

    suspend fun connect(personalToken: String): Result<VercelDashboardUi>

    suspend fun refresh(): Result<VercelDashboardUi>

    suspend fun loadProjectAnalytics(
        project: VercelProjectUi,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
    ): Result<VercelAnalyticsLoadUi>

    /**
     * Project details, verified domains and recent deployments, loaded independently: a `null`
     * part means that request failed and the screen should keep showing what it already has.
     */
    suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> =
        Result.success(VercelProjectContextUi(project = null, domains = null, deployments = null))

    /** Up to 80 of the newest build events for [deployment]. */
    suspend fun loadDeploymentEvents(
        project: VercelProjectUi,
        deployment: VercelDeploymentUi,
    ): Result<List<VercelDeploymentEventUi>> = Result.success(emptyList())

    /**
     * The favicon served by [domain] itself, or null to show the letter tile. Implementations
     * must only contact the project's own public HTTPS origin, without cookies.
     */
    suspend fun loadFavicon(domain: String): ImageBitmap? = null

    /** A favicon already in memory, so recycled list rows render it without a placeholder. */
    fun cachedFavicon(domain: String): ImageBitmap? = null

    /** Records that a 3- or 12-month analytics request succeeded for the connected account. */
    suspend fun markLongAnalyticsHistoryAvailable(): Result<Unit> = Result.success(Unit)

    suspend fun disconnect(): Result<Unit>
}

sealed interface VercelRestoreUi {
    data object NoSavedAccount : VercelRestoreUi

    data class Available(val dashboard: VercelDashboardUi) : VercelRestoreUi

    /** The encrypted account is intact, but its live dashboard could not be refreshed. */
    data class DashboardUnavailable(
        val account: VercelAccountUi,
        val error: Throwable,
    ) : VercelRestoreUi
}

data class VercelDashboardUi(
    val account: VercelAccountUi,
    val projects: List<VercelProjectUi>,
    val warning: String? = null,
)

data class VercelAccountUi(
    val displayName: String,
    val email: String?,
    /** Personal scope slug used for `vercel.com/{scope}/{project}` links. */
    val username: String? = null,
    val hasLongAnalyticsHistory: Boolean = false,
)

/** Where a project was listed from; team scopes show their name on the project card. */
data class VercelProjectScopeUi(
    val name: String,
    val slug: String?,
    val isTeam: Boolean,
)

/** The newest entry of a project's `latestDeployments`. */
data class VercelProjectDeploymentUi(
    val commitMessage: String?,
    val createdAtMillis: Long?,
)

data class VercelProjectUi(
    val id: String,
    val name: String,
    val framework: String?,
    val updatedAtMillis: Long?,
    val teamId: String? = null,
    val primaryDomain: String? = null,
    /** `org/repo` of the linked Git repository. */
    val repository: String? = null,
    val scope: VercelProjectScopeUi? = null,
    val lastDeployment: VercelProjectDeploymentUi? = null,
) {
    /** Sort key matching iOS: the latest deployment, then the project's last update. */
    val latestActivityMillis: Long
        get() = lastDeployment?.createdAtMillis ?: updatedAtMillis ?: 0L
}

data class VercelDeploymentUi(
    val id: String,
    /** Deployment uid or URL accepted by the events endpoint; null disables build events. */
    val eventsIdentifier: String?,
    val name: String?,
    val url: String?,
    val inspectorUrl: String?,
    val state: String,
    val target: String,
    val createdAtMillis: Long?,
    val commitMessage: String? = null,
    val branch: String? = null,
    val commitSha: String? = null,
    val repository: String? = null,
    val creator: String? = null,
) {
    val title: String
        get() = commitMessage ?: name ?: url ?: "Deployment"
}

data class VercelDeploymentEventUi(
    val id: String,
    val type: String,
    val createdAtMillis: Long,
    val message: String,
    val statusCode: String?,
)

data class VercelProjectContextUi(
    val project: VercelProjectUi?,
    val domains: List<String>?,
    val deployments: List<VercelDeploymentUi>?,
) {
    val isComplete: Boolean
        get() = project != null && domains != null && deployments != null
}

enum class VercelAnalyticsRange(
    val shortLabel: String,
    val controlLabel: String,
    val menuLabel: String,
    val durationMillis: Long,
    /** iOS `TimeRange.isPro`: needs a Vercel plan with long Web Analytics history. */
    val requiresLongHistory: Boolean,
) {
    DAY("24h", "24 Hours", "Last 24 Hours", 86_400_000L, false),
    WEEK("7d", "7 Days", "Last 7 Days", 604_800_000L, false),
    MONTH("30d", "30 Days", "Last 30 Days", 2_592_000_000L, false),
    QUARTER("3mo", "3 Months", "Last 3 Months", 7_776_000_000L, true),
    YEAR("12mo", "12 Months", "Last 12 Months", 31_536_000_000L, true),
}

enum class VercelAnalyticsEnvironment(
    val controlLabel: String,
    val menuLabel: String,
    val queryValue: String?,
) {
    PRODUCTION("Production", "Production", "production"),
    PREVIEW("Preview", "Preview", "preview"),
    ALL("All", "All Environments", null),
}

sealed interface VercelAnalyticsLoadUi {
    data class Available(val data: VercelAnalyticsDataUi) : VercelAnalyticsLoadUi

    data class Unavailable(val message: String) : VercelAnalyticsLoadUi
}

data class VercelAnalyticsDataUi(
    val overview: VercelAnalyticsOverviewUi,
    val previousOverview: VercelAnalyticsOverviewUi?,
    val timeseries: List<VercelAnalyticsPointUi>,
    val pages: List<VercelAnalyticsBreakdownUi>,
    val referrers: List<VercelAnalyticsBreakdownUi>,
    val countries: List<VercelAnalyticsBreakdownUi>,
    val devices: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val browsers: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val operatingSystems: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val utmSources: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val routes: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val hostnames: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val events: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val flags: List<VercelAnalyticsBreakdownUi> = emptyList(),
    val queryParameters: List<VercelAnalyticsBreakdownUi> = emptyList(),
)

data class VercelAnalyticsOverviewUi(
    val pageViews: Long,
    val visitors: Long,
    val bounceRate: Double?,
)

data class VercelAnalyticsPointUi(
    val key: String,
    val pageViews: Long,
    val visitors: Long,
    val bounceRate: Double? = null,
)

data class VercelAnalyticsBreakdownUi(
    val key: String,
    val pageViews: Long,
    val visitors: Long,
)

/** Safe production fallback while the data adapter is being composed by the app entry point. */
object UnconfiguredVercelUiGateway : VercelUiGateway {
    override suspend fun restore(): Result<VercelRestoreUi> =
        Result.success(VercelRestoreUi.NoSavedAccount)

    override suspend fun connect(personalToken: String): Result<VercelDashboardUi> =
        Result.failure(IllegalStateException("Vercel connections are unavailable in this version."))

    override suspend fun refresh(): Result<VercelDashboardUi> =
        Result.failure(IllegalStateException("Connect a Vercel account first."))

    override suspend fun loadProjectAnalytics(
        project: VercelProjectUi,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
    ): Result<VercelAnalyticsLoadUi> =
        Result.failure(IllegalStateException("Vercel connections are unavailable in this version."))

    override suspend fun disconnect(): Result<Unit> = Result.success(Unit)
}
