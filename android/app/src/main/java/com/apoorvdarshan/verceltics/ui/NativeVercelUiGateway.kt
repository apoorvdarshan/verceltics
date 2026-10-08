package com.apoorvdarshan.verceltics.ui

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.account.VercelAccount
import com.apoorvdarshan.verceltics.data.account.VercelAccountRepository
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.vercel.HttpsVercelFaviconTransport
import com.apoorvdarshan.verceltics.data.vercel.VercelAnalyticsOverview
import com.apoorvdarshan.verceltics.data.vercel.VercelAnalyticsPoint
import com.apoorvdarshan.verceltics.data.vercel.VercelAnalyticsTimeseries
import com.apoorvdarshan.verceltics.data.vercel.VercelApi
import com.apoorvdarshan.verceltics.data.vercel.VercelApiException
import com.apoorvdarshan.verceltics.data.vercel.VercelDeployment
import com.apoorvdarshan.verceltics.data.vercel.VercelDeploymentEvent
import com.apoorvdarshan.verceltics.data.vercel.VercelFaviconBitmapDecoder
import com.apoorvdarshan.verceltics.data.vercel.VercelFaviconLoader
import com.apoorvdarshan.verceltics.data.vercel.VercelProject
import com.apoorvdarshan.verceltics.data.vercel.VercelProjectScope
import com.apoorvdarshan.verceltics.data.vercel.VercelTeam
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Production bridge between Compose state and the native Android Vercel implementation.
 *
 * Blocking provider calls run outside the main thread and are explicitly cancelled when the
 * requesting coroutine goes away. Credentials only cross this boundary as [SecretValue] and are
 * persisted by the Android Keystore-backed repository. Every read-modify-write of the saved
 * account happens under [accountMutex] and is dropped if the account was disconnected meanwhile.
 */
class NativeVercelUiGateway private constructor(
    private val applicationContext: Context,
    private val api: VercelApi,
    private val executor: ExecutorService,
    private val favicons: VercelFaviconLoader<ImageBitmap>,
) : VercelUiGateway {
    private val accountRepository: VercelAccountRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VercelAccountRepository.create(applicationContext)
    }
    private val accountMutex = Mutex()

    override suspend fun restore(): Result<VercelRestoreUi> = capture {
        val account = loadAccount() ?: return@capture VercelRestoreUi.NoSavedAccount
        try {
            VercelRestoreUi.Available(dashboard(account))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            VercelRestoreUi.DashboardUnavailable(
                account = account.toUi(),
                error = error,
            )
        }
    }

    override suspend fun connect(personalToken: String): Result<VercelDashboardUi> = capture {
        val secret = SecretValue.of(personalToken.trim())
        val user = api.newValidatePersonalTokenCall(secret).executeAwait(executor)
        val account = api.accountForValidatedUser(user = user, token = secret)
        val connectedDashboard = dashboard(account)
        withAccountLock { accountRepository.save(account) }
        favicons.clear()
        connectedDashboard
    }

    override suspend fun refresh(): Result<VercelDashboardUi> = capture {
        val account = loadAccount() ?: throw IllegalStateException("Connect a Vercel account first.")
        dashboard(account)
    }

    override suspend fun loadProjectAnalytics(
        project: VercelProjectUi,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
    ): Result<VercelAnalyticsLoadUi> = capture {
        val account = loadAccount() ?: throw IllegalStateException("Connect a Vercel account first.")
        try {
            VercelAnalyticsLoadUi.Available(
                analytics(
                    token = account.token,
                    project = project,
                    range = range,
                    environment = environment,
                ),
            )
        } catch (error: VercelApiException) {
            if (error.statusCode == 401 || error.statusCode == 403) throw error
            VercelAnalyticsLoadUi.Unavailable(
                message = if (error.statusCode == 400 || error.statusCode == 404) {
                    "Vercel Web Analytics is not available through token access right now. " +
                        "Project details, domains, and deployments are still shown below."
                } else {
                    "Vercel Web Analytics returned HTTP ${error.statusCode}. " +
                        "Project details, domains, and deployments are still shown below."
                },
            )
        }
    }

    override suspend fun loadProjectContext(project: VercelProjectUi): Result<VercelProjectContextUi> = capture {
        val account = loadAccount() ?: throw IllegalStateException("Connect a Vercel account first.")
        coroutineScope {
            val details = async {
                optional { api.newProjectCall(account.token, project.id, project.teamId).executeAwait(executor) }
            }
            val domains = async {
                optional { api.newProjectDomainsCall(account.token, project.id, project.teamId).executeAwait(executor) }
            }
            val deployments = async {
                optional { api.newDeploymentsCall(account.token, project.id, project.teamId).executeAwait(executor) }
            }
            VercelProjectContextUi(
                project = details.await()?.let { detail ->
                    val merged = detail.toUi()
                    merged.copy(
                        teamId = project.teamId ?: merged.teamId,
                        framework = merged.framework ?: project.framework,
                        repository = merged.repository ?: project.repository,
                        lastDeployment = merged.lastDeployment ?: project.lastDeployment,
                        scope = project.scope ?: merged.scope,
                        // The listed project was already enriched with verified domains.
                        primaryDomain = project.primaryDomain ?: merged.primaryDomain,
                    )
                },
                domains = domains.await(),
                deployments = deployments.await()?.map(VercelDeployment::toUi),
            )
        }
    }

    override suspend fun loadDeploymentEvents(
        project: VercelProjectUi,
        deployment: VercelDeploymentUi,
    ): Result<List<VercelDeploymentEventUi>> = capture {
        val identifier = deployment.eventsIdentifier
            ?: throw IllegalStateException("This deployment does not include an event identifier.")
        val account = loadAccount() ?: throw IllegalStateException("Connect a Vercel account first.")
        api.newDeploymentEventsCall(account.token, identifier, project.teamId)
            .executeAwait(executor)
            .take(VercelApi.DEFAULT_EVENT_LIMIT)
            .mapIndexed { index, event -> event.toUi(index) }
    }

    override suspend fun loadFavicon(domain: String): ImageBitmap? = favicons.load(domain)

    override fun cachedFavicon(domain: String): ImageBitmap? = favicons.cached(domain)

    override suspend fun markLongAnalyticsHistoryAvailable(): Result<Unit> = capture {
        withAccountLock {
            val current = accountRepository.load() ?: return@withAccountLock
            if (!current.hasLongAnalyticsHistory) accountRepository.save(current.withLongAnalyticsHistory())
        }
    }

    override suspend fun disconnect(): Result<Unit> = capture {
        withAccountLock { accountRepository.delete() }
        favicons.clear()
    }

    /**
     * Runs a blocking account read-modify-write under [accountMutex]. The section is
     * non-cancellable, so the lock is never released while the executor is still writing (which
     * could let a disconnect's delete interleave with a save and resurrect the token).
     */
    private suspend fun withAccountLock(operation: () -> Unit) = withContext(NonCancellable) {
        accountMutex.withLock { executeAwait(executor, operation) }
    }

    private suspend fun loadAccount(): VercelAccount? = executeAwait(executor) { accountRepository.load() }

    private suspend fun dashboard(account: VercelAccount): VercelDashboardUi {
        val loaded = loadAllProjects(account.token)
        val current = backfillUsername(account)
        return VercelDashboardUi(
            account = current.toUi(),
            projects = loaded.projects.map(VercelProject::toUi),
            warning = loaded.warning,
        )
    }

    /** Accounts saved before usernames were stored learn theirs once, for project links. */
    private suspend fun backfillUsername(account: VercelAccount): VercelAccount {
        if (account.username != null) return account
        val user = optional { api.newValidatePersonalTokenCall(account.token).executeAwait(executor) }
            ?: return account
        val updated = account.withUsername(user.username)
        withAccountLock {
            val saved = accountRepository.load()
            if (saved != null && saved.id == account.id && saved.token == account.token && saved.username == null) {
                accountRepository.save(saved.withUsername(user.username))
            }
        }
        return updated
    }

    private suspend fun loadAllProjects(token: SecretValue): ProjectLoadResult = coroutineScope {
        val personalProjects = fetchAllProjects(token = token, teamId = null)
            .map { it.withSourceScope(VercelProjectScope.PERSONAL) }
        val teams = try {
            fetchAllTeams(token).filter(VercelTeam::isConfirmedMember)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@coroutineScope ProjectLoadResult(
                projects = enrichProjectsNeedingDomains(token, personalProjects),
                warning = "Personal projects loaded, but the Vercel team list could not be refreshed.",
            )
        }

        val teamResults = teams.map { team ->
            async {
                val scope = VercelProjectScope(
                    id = team.id,
                    name = team.displayName,
                    slug = team.slug,
                    isTeam = true,
                )
                try {
                    TeamProjectLoad.Success(
                        projects = fetchAllProjects(token, team.id).map { project ->
                            val scoped = if (project.teamId == team.id) project else project.copy(teamId = team.id)
                            scoped.withSourceScope(scope)
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    TeamProjectLoad.Failure(team.displayName)
                }
            }
        }.awaitAll()

        val failedTeams = teamResults.filterIsInstance<TeamProjectLoad.Failure>()
            .map(TeamProjectLoad.Failure::teamName)
            .sorted()
        val allProjects = buildList {
            addAll(personalProjects)
            teamResults.filterIsInstance<TeamProjectLoad.Success>().forEach { addAll(it.projects) }
        }.distinctBy(VercelProject::id)
        val warning = failedTeams.takeIf(List<String>::isNotEmpty)?.let { names ->
            val visibleNames = names.take(3).joinToString(", ")
            val remainder = if (names.size > 3) " and ${names.size - 3} more" else ""
            "Some Vercel teams could not be refreshed: $visibleNames$remainder."
        }
        ProjectLoadResult(projects = enrichProjectsNeedingDomains(token, allProjects), warning = warning)
    }

    /**
     * Projects whose listing names no custom domain get their full record and verified domains,
     * so cards and favicons use the real site rather than a `*.vercel.app` alias. Failures keep
     * the listed project unchanged.
     */
    private suspend fun enrichProjectsNeedingDomains(
        token: SecretValue,
        projects: List<VercelProject>,
    ): List<VercelProject> = coroutineScope {
        val candidates = projects.filter(VercelProject::needsPrimaryDomainRefresh)
        if (candidates.isEmpty()) return@coroutineScope projects
        val permits = Semaphore(ENRICHMENT_CONCURRENCY)
        val refreshed = candidates.map { project ->
            async {
                permits.withPermit {
                    val detail = async { optional { api.newProjectCall(token, project.id, project.teamId).executeAwait(executor) } }
                    val domains = async {
                        optional { api.newProjectDomainsCall(token, project.id, project.teamId).executeAwait(executor) }
                    }
                    val resolved = (detail.await() ?: project).let { loaded ->
                        loaded.copy(
                            teamId = project.teamId ?: loaded.teamId,
                            framework = loaded.framework ?: project.framework,
                            link = loaded.link ?: project.link,
                            latestDeployments = loaded.latestDeployments.ifEmpty { project.latestDeployments },
                            sourceScope = project.sourceScope,
                        )
                    }
                    project.id to resolved.withAdditionalDomains(domains.await().orEmpty())
                }
            }
        }.awaitAll().toMap()
        projects.map { refreshed[it.id] ?: it }
    }

    private suspend fun fetchAllProjects(token: SecretValue, teamId: String?): List<VercelProject> {
        val projects = mutableListOf<VercelProject>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            val page = api.newListProjectsCall(
                token = token,
                limit = PAGE_LIMIT,
                until = cursor,
                teamId = teamId,
            ).executeAwait(executor)
            projects += page.projects
            cursor = page.nextCursor
            pageCount += 1
            if (cursor != null && !seenCursors.add(cursor)) {
                throw IllegalStateException("Vercel project pagination repeated a cursor.")
            }
            if (cursor != null && pageCount >= MAXIMUM_PAGES) {
                throw IllegalStateException("Vercel project pagination exceeded $MAXIMUM_PAGES pages.")
            }
        } while (cursor != null)
        return projects.distinctBy(VercelProject::id)
    }

    private suspend fun fetchAllTeams(token: SecretValue): List<VercelTeam> {
        val teams = mutableListOf<VercelTeam>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            val page = api.newListTeamsCall(
                token = token,
                limit = PAGE_LIMIT,
                until = cursor,
            ).executeAwait(executor)
            teams += page.teams
            cursor = page.nextCursor
            pageCount += 1
            if (cursor != null && !seenCursors.add(cursor)) {
                throw IllegalStateException("Vercel team pagination repeated a cursor.")
            }
            if (cursor != null && pageCount >= MAXIMUM_PAGES) {
                throw IllegalStateException("Vercel team pagination exceeded $MAXIMUM_PAGES pages.")
            }
        } while (cursor != null)
        return teams.distinctBy(VercelTeam::id)
    }

    private suspend fun analytics(
        token: SecretValue,
        project: VercelProjectUi,
        range: VercelAnalyticsRange,
        environment: VercelAnalyticsEnvironment,
    ): VercelAnalyticsDataUi = coroutineScope {
        val nowMillis = System.currentTimeMillis()
        val fromMillis = nowMillis - range.durationMillis
        val previousFromMillis = fromMillis - range.durationMillis
        val from = Instant.ofEpochMilli(fromMillis).toString()
        val to = Instant.ofEpochMilli(nowMillis).toString()
        val previousFrom = Instant.ofEpochMilli(previousFromMillis).toString()
        val previousTo = from
        val environmentQuery = environment.queryValue

        val overview = async {
            api.newAnalyticsOverviewCall(
                token = token,
                projectId = project.id,
                teamId = project.teamId,
                from = from,
                to = to,
                environment = environmentQuery,
            ).executeAwait(executor)
        }
        val previousOverview = async {
            optional {
                api.newAnalyticsOverviewCall(
                    token = token,
                    projectId = project.id,
                    teamId = project.teamId,
                    from = previousFrom,
                    to = previousTo,
                    environment = environmentQuery,
                ).executeAwait(executor)
            }
        }
        val timeseries = async {
            analyticsTimeseries(token, project, from, to, environmentQuery, groupBy = null)
        }
        val pages = async {
            analyticsTimeseries(token, project, from, to, environmentQuery, groupBy = "path")
        }
        val referrers = async {
            analyticsTimeseries(token, project, from, to, environmentQuery, groupBy = "referrer")
        }
        val countries = async {
            analyticsTimeseries(token, project, from, to, environmentQuery, groupBy = "country")
        }
        val devices = optionalBreakdown(token, project, from, to, environmentQuery, "device_type")
        val browsers = optionalBreakdown(token, project, from, to, environmentQuery, "client_name")
        val operatingSystems = optionalBreakdown(token, project, from, to, environmentQuery, "os_name")
        val utmSources = optionalBreakdown(token, project, from, to, environmentQuery, "utm")
        val routes = optionalBreakdown(token, project, from, to, environmentQuery, "route")
        val hostnames = optionalBreakdown(token, project, from, to, environmentQuery, "hostname")
        val events = optionalBreakdown(token, project, from, to, environmentQuery, "event_name")
        val flags = optionalBreakdown(token, project, from, to, environmentQuery, "flags")
        val queryParameters = optionalBreakdown(
            token,
            project,
            from,
            to,
            environmentQuery,
            "query_params",
        )

        VercelAnalyticsDataUi(
            overview = overview.await().toUi(),
            previousOverview = previousOverview.await()?.toUi(),
            timeseries = timeseries.await().groups["all"].orEmpty().map(VercelAnalyticsPoint::toUi),
            pages = pages.await().toBreakdownUi(),
            referrers = referrers.await().toBreakdownUi(),
            countries = countries.await().toBreakdownUi(),
            devices = devices.await(),
            browsers = browsers.await(),
            operatingSystems = operatingSystems.await(),
            utmSources = utmSources.await(),
            routes = routes.await(),
            hostnames = hostnames.await(),
            events = events.await(),
            flags = flags.await(),
            queryParameters = queryParameters.await(),
        )
    }

    private fun CoroutineScope.optionalBreakdown(
        token: SecretValue,
        project: VercelProjectUi,
        from: String,
        to: String,
        environment: String?,
        groupBy: String,
    ) = async {
        optional {
            analyticsTimeseries(token, project, from, to, environment, groupBy).toBreakdownUi()
        }.orEmpty()
    }

    private suspend fun analyticsTimeseries(
        token: SecretValue,
        project: VercelProjectUi,
        from: String,
        to: String,
        environment: String?,
        groupBy: String?,
    ): VercelAnalyticsTimeseries = api.newAnalyticsTimeseriesCall(
        token = token,
        projectId = project.id,
        teamId = project.teamId,
        from = from,
        to = to,
        environment = environment,
        groupBy = groupBy,
    ).executeAwait(executor)

    private suspend fun <T> optional(operation: suspend () -> T): T? = try {
        operation()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend inline fun <T> capture(crossinline operation: suspend () -> T): Result<T> =
        try {
            Result.success(operation())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    companion object {
        private const val PAGE_LIMIT = 100
        private const val MAXIMUM_PAGES = 200
        private const val ENRICHMENT_CONCURRENCY = 6

        fun create(context: Context): NativeVercelUiGateway {
            val executor = Executors.newFixedThreadPool(6) { work ->
                Thread(work, "verceltics-provider").apply { isDaemon = true }
            }
            val faviconExecutor = Executors.newFixedThreadPool(4) { work ->
                Thread(work, "verceltics-favicon").apply { isDaemon = true }
            }
            return NativeVercelUiGateway(
                applicationContext = context.applicationContext,
                api = VercelApi(),
                executor = executor,
                favicons = VercelFaviconLoader(
                    transport = HttpsVercelFaviconTransport(),
                    executor = faviconExecutor,
                    decode = { bytes -> VercelFaviconBitmapDecoder.decode(bytes)?.asImageBitmap() },
                ),
            )
        }
    }

    private data class ProjectLoadResult(
        val projects: List<VercelProject>,
        val warning: String?,
    )

    private sealed interface TeamProjectLoad {
        data class Success(val projects: List<VercelProject>) : TeamProjectLoad
        data class Failure(val teamName: String) : TeamProjectLoad
    }
}

private suspend fun <T> CancelableCall<T>.executeAwait(executor: ExecutorService): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        executor.execute {
            try {
                val value = execute()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

private suspend fun <T> executeAwait(
    executor: ExecutorService,
    operation: () -> T,
): T = suspendCancellableCoroutine { continuation ->
    executor.execute {
        try {
            val value = operation()
            if (continuation.isActive) continuation.resume(value)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

private fun VercelAccount.toUi(): VercelAccountUi = VercelAccountUi(
    displayName = displayName,
    email = email,
    username = username,
    hasLongAnalyticsHistory = hasLongAnalyticsHistory,
)

internal fun VercelProject.toUi(): VercelProjectUi = VercelProjectUi(
    id = id,
    name = name,
    framework = framework,
    updatedAtMillis = updatedAtMillis,
    teamId = teamId,
    primaryDomain = primaryDomain,
    repository = link?.fullName,
    scope = sourceScope?.let { VercelProjectScopeUi(name = it.name, slug = it.slug, isTeam = it.isTeam) },
    lastDeployment = lastDeployment?.let {
        VercelProjectDeploymentUi(commitMessage = it.commitMessage, createdAtMillis = it.createdAtMillis)
    },
)

internal fun VercelDeployment.toUi(): VercelDeploymentUi = VercelDeploymentUi(
    id = stableId,
    eventsIdentifier = eventsIdentifier,
    name = name,
    url = url,
    inspectorUrl = inspectorUrl,
    state = displayState,
    target = displayTarget,
    createdAtMillis = createdAtMillis,
    commitMessage = meta.commitMessage,
    branch = meta.commitRef,
    commitSha = meta.commitSha,
    repository = meta.repository,
    creator = creatorUsername ?: creatorEmail,
)

internal fun VercelDeploymentEvent.toUi(index: Int): VercelDeploymentEventUi = VercelDeploymentEventUi(
    id = "$index-$stableId",
    type = type,
    createdAtMillis = createdAtMillis,
    message = message,
    statusCode = statusCode,
)

private fun VercelAnalyticsOverview.toUi(): VercelAnalyticsOverviewUi =
    VercelAnalyticsOverviewUi(
        pageViews = pageViews,
        visitors = visitors,
        bounceRate = bounceRate,
    )

private fun VercelAnalyticsPoint.toUi(): VercelAnalyticsPointUi = VercelAnalyticsPointUi(
    key = key,
    pageViews = pageViews,
    visitors = visitors,
    bounceRate = bounceRate,
)

private fun VercelAnalyticsTimeseries.toBreakdownUi(): List<VercelAnalyticsBreakdownUi> = groups
    .asSequence()
    .filter { (key, _) -> key != "all" }
    .map { (key, points) ->
        VercelAnalyticsBreakdownUi(
            key = key,
            pageViews = points.sumOf(VercelAnalyticsPoint::pageViews),
            visitors = points.sumOf(VercelAnalyticsPoint::visitors),
        )
    }
    .sortedByDescending(VercelAnalyticsBreakdownUi::visitors)
    .toList()
