package com.apoorvdarshan.verceltics.ui

/**
 * UI-facing boundary for the first Android provider slice.
 *
 * The Compose layer deliberately knows nothing about tokens at rest, HTTP clients, or JSON. A
 * native data adapter can implement this contract with Android Keystore-backed persistence and
 * the Vercel API without leaking those concerns into screen state.
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
)

data class VercelProjectUi(
    val id: String,
    val name: String,
    val framework: String?,
    val updatedAtMillis: Long?,
    val teamId: String? = null,
)

enum class VercelAnalyticsRange(
    val shortLabel: String,
    val controlLabel: String,
    val durationMillis: Long,
) {
    DAY("24h", "24 Hours", 86_400_000L),
    WEEK("7d", "7 Days", 604_800_000L),
    MONTH("30d", "30 Days", 2_592_000_000L),
    QUARTER("3mo", "3 Months", 7_776_000_000L),
    YEAR("12mo", "12 Months", 31_536_000_000L),
}

enum class VercelAnalyticsEnvironment(
    val controlLabel: String,
    val queryValue: String?,
) {
    PRODUCTION("Production", "production"),
    PREVIEW("Preview", "preview"),
    ALL("All", null),
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
