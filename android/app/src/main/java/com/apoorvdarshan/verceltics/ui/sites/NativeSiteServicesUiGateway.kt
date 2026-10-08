package com.apoorvdarshan.verceltics.ui.sites

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthException
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthSession
import com.apoorvdarshan.verceltics.data.googleoauth.NativeGoogleOAuthAuthorizer
import com.apoorvdarshan.verceltics.data.network.runOnProviderExecutor
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionRepository
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionStore
import com.apoorvdarshan.verceltics.data.sites.SiteDetailPayload
import com.apoorvdarshan.verceltics.data.sites.SiteDetailRange
import com.apoorvdarshan.verceltics.data.sites.SiteDetailRequest
import com.apoorvdarshan.verceltics.data.sites.SiteMetric
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import com.apoorvdarshan.verceltics.data.sites.SiteResource
import com.apoorvdarshan.verceltics.data.sites.SiteRestoreProblem
import com.apoorvdarshan.verceltics.data.sites.SiteRestoreResult
import com.apoorvdarshan.verceltics.data.sites.SiteServiceAccount
import com.apoorvdarshan.verceltics.data.sites.SiteServiceException
import com.apoorvdarshan.verceltics.data.sites.SiteServicesApi
import com.apoorvdarshan.verceltics.data.sites.SiteSnapshot
import com.apoorvdarshan.verceltics.data.sites.SiteVersionedConnection
import com.apoorvdarshan.verceltics.data.sites.StoredSiteConnection
import com.apoorvdarshan.verceltics.data.sites.UmamiAuthentication
import com.apoorvdarshan.verceltics.data.sites.UmamiSiteAdapter
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Production bridge from encrypted site-service connections to bounded, display-only Compose
 * models. Network results are parsed off the main thread; persistence runs on a single storage
 * executor and completes even if the requesting screen is cancelled mid-save.
 */
class NativeSiteServicesUiGateway internal constructor(
    private val store: SiteConnectionStore,
    private val api: SiteServicesApi,
    private val googleSessions: Map<SiteProvider, GoogleOAuthSession>,
    private val storageExecutor: Executor,
    private val workContext: CoroutineContext = Dispatchers.Default,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SiteServicesUiGateway {
    private val detailCache = SiteDetailMemoryCache(maximumEntries = 8)

    /**
     * The latest live inventory per provider. The encrypted offline cache is bounded, so detail
     * requests resolve resources here first and never silently fall back to another resource.
     */
    private val liveSnapshots = java.util.concurrent.ConcurrentHashMap<SiteProvider, SiteSnapshot>()

    override val googleOAuthReadiness: SiteGoogleOAuthReadinessUi
        get() = if (googleSessions[SiteProvider.GOOGLE_ANALYTICS]?.isConfigured == true) {
            SiteGoogleOAuthReadinessUi.Ready
        } else {
            SiteGoogleOAuthReadinessUi.ConfigurationNeeded(NativeGoogleOAuthAuthorizer.CONFIGURATION_MISSING_MESSAGE)
        }

    override suspend fun restore(): Result<SiteServicesRestoreUi> = capture(null) {
        val restored = runOnProviderExecutor(storageExecutor) {
            SiteProvider.entries.associateWith(store::restore)
        }
        SiteServicesRestoreUi(
            restored.entries.associate { (provider, result) -> provider.id to result.toUi(provider) },
        )
    }

    override suspend fun connect(
        providerId: String,
        input: SiteServiceConnectionInputUi,
    ): Result<SiteServiceDashboardUi> = capture(providerId) {
        val provider = provider(providerId)
        if (provider.usesGoogleOAuth) throw SiteServicesUiException("${provider.displayName} connects with Google sign-in.")
        val metadata = cleanedFields(provider, input.fields)
        val account = SiteServiceAccount(provider, input.credential, metadata)
        val validated = withContext(workContext) { api.validatedConnection(account) }
        val stored = withContext(NonCancellable) {
            runOnProviderExecutor(storageExecutor) {
                store.saveValidatedConnection(
                    provider = provider,
                    name = validated.name,
                    credential = input.credential,
                    metadata = metadata + validated.connectionMetadata,
                    snapshot = validated.snapshot,
                )
            }
        }
        detailCache.invalidate(provider)
        liveSnapshots[provider] = validated.snapshot
        dashboard(provider, stored.name, stored.metadata, validated.snapshot, SiteServiceCacheState.LIVE)
    }

    override suspend fun connectGoogle(providerId: String): Result<SiteServiceDashboardUi> = capture(providerId) {
        val provider = provider(providerId)
        val session = googleSessions[provider]
        if (!provider.usesGoogleOAuth || session == null) {
            throw SiteServicesUiException("${provider.displayName} does not use Google sign-in.")
        }
        if (!session.isConfigured) throw SiteServicesUiException(NativeGoogleOAuthAuthorizer.CONFIGURATION_MISSING_MESSAGE)
        val credential = session.authorize(provider.oauthScopes)
        val account = SiteServiceAccount(provider, credential = null, googleAccessToken = credential.accessToken)
        val validated = withContext(workContext) { api.validatedConnection(account) }
        val metadata = buildMap {
            credential.subject?.let { put("googleSubject", it) }
            credential.email?.let { put("googleEmail", it) }
        }
        val stored = withContext(NonCancellable) {
            val previous = session.save(credential)
            try {
                runOnProviderExecutor(storageExecutor) {
                    store.saveValidatedConnection(provider, validated.name, null, metadata, validated.snapshot)
                }
            } catch (error: Exception) {
                session.restore(previous)
                throw error
            }
        }
        detailCache.invalidate(provider)
        liveSnapshots[provider] = validated.snapshot
        dashboard(provider, stored.name, stored.metadata, validated.snapshot, SiteServiceCacheState.LIVE)
    }

    override suspend fun refresh(providerId: String): Result<SiteServiceDashboardUi> = capture(providerId) {
        val provider = provider(providerId)
        val source = loadSource(provider)
        val (account, snapshot) = withUnauthorizedRetry(provider, source.connection) { account ->
            account to withContext(workContext) { api.snapshot(account) }
        }
        val discovered = backfillUmamiIdentity(account, source.connection)
        val name = runCatching { api.connectionName(account, snapshot) }.getOrDefault(source.connection.name)
        val persisted = withContext(NonCancellable) {
            runOnProviderExecutor(storageExecutor) { store.persistRefresh(source, name, snapshot, discovered) }
        }
        if (!persisted) {
            throw SiteServicesUiException(
                "The saved ${provider.displayName} connection changed while it was refreshing. Try again.",
            )
        }
        liveSnapshots[provider] = snapshot
        dashboard(provider, name, source.connection.metadata + discovered, snapshot, SiteServiceCacheState.LIVE)
    }

    override suspend fun loadDetail(
        request: SiteServiceDetailRequestUi,
        forceRefresh: Boolean,
        onPartial: suspend (SiteServiceDetailUi) -> Unit,
    ): Result<SiteServiceDetailUi> = capture(request.providerId) {
        val provider = provider(request.providerId)
        val source = loadSource(provider)
        val resources = (liveSnapshots[provider] ?: source.connection.cachedSnapshot)?.resources.orEmpty()
        val resource = if (request.resourceId == null) {
            resources.firstOrNull()
        } else {
            resources.firstOrNull { it.id == request.resourceId } ?: throw SiteServicesUiException(
                "This ${provider.resourceNoun.lowercase()} is no longer in the saved ${provider.displayName} data. " +
                    "Refresh the service and try again.",
            )
        }
        val cacheKey = listOf(provider.id, resource?.id.orEmpty(), request.query.identity).joinToString("|")
        if (!forceRefresh) {
            detailCache.fresh(cacheKey, provider, nowMillis())?.let { return@capture it }
        }
        val payload = withUnauthorizedRetry(provider, source.connection) { account ->
            val detailRequest = detailRequest(provider, account, source.connection, resource, request.query)
            withContext(workContext) {
                api.detail(detailRequest) { partial -> onPartial(partial.toUi(isPartial = true)) }
            }
        }
        payload.toUi(isPartial = false).also { detailCache.put(cacheKey, provider, it, nowMillis()) }
    }

    override suspend fun disconnect(providerId: String): Result<Unit> = capture(providerId) {
        val provider = provider(providerId)
        withContext(NonCancellable) {
            runOnProviderExecutor(storageExecutor) { store.disconnect(provider) }
            googleSessions[provider]?.signOut()
        }
        detailCache.invalidate(provider)
        liveSnapshots.remove(provider)
    }

    // MARK: Requests

    private suspend fun loadSource(provider: SiteProvider): SiteVersionedConnection =
        runOnProviderExecutor(storageExecutor) { store.loadForRequest(provider) }
            ?: throw SiteServicesUiException("Connect ${provider.displayName} first.")

    /**
     * Builds a request account (refreshing Google tokens when needed) and retries exactly once
     * with a force-refreshed token when Google rejects it with HTTP 401 (iOS `SiteStore.refresh`).
     */
    private suspend fun <T> withUnauthorizedRetry(
        provider: SiteProvider,
        connection: StoredSiteConnection,
        block: suspend (SiteServiceAccount) -> T,
    ): T {
        val first = requestAccount(provider, connection, forceTokenRefresh = false)
        return try {
            block(first)
        } catch (error: SiteServiceException) {
            if (!provider.usesGoogleOAuth || !error.isUnauthorized) throw error
            block(requestAccount(provider, connection, forceTokenRefresh = true))
        }
    }

    private suspend fun requestAccount(
        provider: SiteProvider,
        connection: StoredSiteConnection,
        forceTokenRefresh: Boolean,
    ): SiteServiceAccount {
        if (!provider.usesGoogleOAuth) {
            return SiteServiceAccount(provider, connection.credential, connection.metadata)
        }
        val session = googleSessions[provider]
            ?: throw SiteServicesUiException(NativeGoogleOAuthAuthorizer.CONFIGURATION_MISSING_MESSAGE)
        val token = session.accessTokenSecret(provider.oauthScopes, forceRefresh = forceTokenRefresh)
            ?: throw SiteServicesUiException("Google access for ${provider.displayName} expired or was revoked. Reconnect the account.")
        return SiteServiceAccount(provider, null, connection.metadata, googleAccessToken = token)
    }

    private fun detailRequest(
        provider: SiteProvider,
        account: SiteServiceAccount,
        connection: StoredSiteConnection,
        resource: SiteResource?,
        query: SiteServiceDetailQueryUi,
    ): SiteDetailRequest {
        val range = range(query)
        return when (provider) {
            SiteProvider.GOOGLE_ANALYTICS -> {
                val propertyId = resource?.metadata?.get("propertyID")?.takeIf(String::isNotBlank)
                    ?: resource?.id?.removePrefix("properties/")?.takeIf(String::isNotBlank)
                    ?: throw SiteServicesUiException("Choose a GA4 property first.")
                SiteDetailRequest.GoogleAnalytics(propertyId, requireNotNull(account.googleAccessToken), range)
            }
            SiteProvider.BING_WEBMASTER -> {
                val siteUrl = resource?.subtitle?.takeIf(String::isNotBlank)
                    ?: resource?.url
                    ?: resource?.name?.takeIf(String::isNotBlank)
                    ?: throw SiteServicesUiException("Choose a Bing site first.")
                SiteDetailRequest.BingWebmaster(siteUrl, credential(connection))
            }
            SiteProvider.CLARITY -> SiteDetailRequest.Clarity(credential(connection), query.clarityDays, query.clarityDimensions)
            SiteProvider.PLAUSIBLE -> {
                val siteId = connection.metadata["siteID"]?.takeIf(String::isNotBlank)
                    ?: resource?.name?.takeIf(String::isNotBlank)
                    ?: throw SiteServicesUiException("The Plausible site ID is missing.")
                SiteDetailRequest.Plausible(siteId, credential(connection), range)
            }
            SiteProvider.UMAMI -> {
                val websiteId = resource?.id?.takeIf(String::isNotBlank)
                    ?: throw SiteServicesUiException("Choose an Umami site first.")
                val base: URI = try {
                    UmamiSiteAdapter.apiBaseUrl(connection.metadata)
                } catch (_: SiteServiceException) {
                    throw SiteServicesUiException("The self-hosted Umami API URL is invalid.")
                }
                val authentication = if (UmamiSiteAdapter.authMode(connection.metadata) == UmamiSiteAdapter.SELF_HOSTED) {
                    UmamiAuthentication.BearerToken(credential(connection))
                } else {
                    UmamiAuthentication.CloudApiKey(credential(connection))
                }
                SiteDetailRequest.Umami(websiteId, base, authentication, range)
            }
            SiteProvider.UPTIME_ROBOT -> SiteDetailRequest.UptimeRobot(
                resource?.id?.takeIf(String::isNotBlank) ?: throw SiteServicesUiException("Choose an UptimeRobot monitor first."),
                credential(connection),
                range,
            )
            SiteProvider.BETTER_STACK -> SiteDetailRequest.BetterStack(
                resource?.id?.takeIf(String::isNotBlank) ?: throw SiteServicesUiException("Choose a Better Stack monitor first."),
                credential(connection),
                range,
            )
        }
    }

    private fun credential(connection: StoredSiteConnection): SecretValue = connection.credential
        ?: throw SiteServicesUiException("The saved ${connection.provider.displayName} credential is empty.")

    private fun range(query: SiteServiceDetailQueryUi): SiteDetailRange {
        val days = query.preset.days
        return if (days != null) {
            SiteDetailRange.lastDays(days, nowMillis(), zone())
        } else {
            SiteDetailRange.custom(
                LocalDate.parse(query.customStartDate),
                LocalDate.parse(query.customEndDate),
                nowMillis(),
                zone(),
            )
        }
    }

    /** Best-effort identity backfill for Umami records connected without `/me` metadata. */
    private suspend fun backfillUmamiIdentity(
        account: SiteServiceAccount,
        connection: StoredSiteConnection,
    ): Map<String, String> {
        if (account.provider != SiteProvider.UMAMI ||
            (connection.metadata["umamiUserID"] != null && connection.metadata["umamiEndpoint"] != null)
        ) {
            return emptyMap()
        }
        return try {
            withContext(workContext) { api.connectionMetadata(account) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // MARK: Mapping

    private fun SiteRestoreResult.toUi(provider: SiteProvider): SiteServiceRestoreUi = when (this) {
        SiteRestoreResult.NotConnected -> SiteServiceRestoreUi.NotConnected
        is SiteRestoreResult.Restored -> cachedSnapshot?.let { snapshot ->
            SiteServiceRestoreUi.Available(
                dashboard(
                    provider, name, metadata, snapshot,
                    if (cacheIsStale) SiteServiceCacheState.CACHED_STALE else SiteServiceCacheState.CACHED_FRESH,
                ),
            )
        } ?: SiteServiceRestoreUi.SavedWithoutInventory(name)
        is SiteRestoreResult.Unavailable -> SiteServiceRestoreUi.SavedUnavailable(
            when (problem) {
                SiteRestoreProblem.SAVED_RECORD_UNREADABLE ->
                    "The saved ${provider.displayName} connection could not be opened. It was not deleted or replaced."
                SiteRestoreProblem.SECURE_STORAGE_UNAVAILABLE -> "Secure storage is unavailable. Unlock the device and try again."
            },
        )
    }

    private fun dashboard(
        provider: SiteProvider,
        name: String,
        metadata: Map<String, String>,
        snapshot: SiteSnapshot,
        cacheState: SiteServiceCacheState,
    ): SiteServiceDashboardUi {
        val visible = snapshot.resources.take(MAXIMUM_VISIBLE_RESOURCES)
        return SiteServiceDashboardUi(
            providerId = provider.id,
            accountName = name,
            accountDetail = metadata["googleEmail"] ?: metadata["umamiUsername"],
            status = snapshot.status,
            resources = visible.map(SiteResource::toUi),
            metrics = snapshot.metrics.map(SiteMetric::toUi),
            warnings = snapshot.warnings,
            fetchedAtMillis = snapshot.fetchedAtMillis,
            cacheState = cacheState,
            loadedResourceCount = snapshot.resources.size,
            resourcesTruncatedForDisplay = snapshot.resources.size > visible.size,
        )
    }

    private fun provider(providerId: String): SiteProvider = SiteProvider.fromId(providerId)
        ?: throw SiteServicesUiException("This site service is not available.")

    /** Keeps only the documented non-secret fields for [provider], trimmed and non-empty. */
    private fun cleanedFields(provider: SiteProvider, fields: Map<String, String>): Map<String, String> {
        val allowed = when (provider) {
            SiteProvider.CLARITY -> setOf(SiteServiceFieldKeys.PROJECT_NAME, SiteServiceFieldKeys.SITE_URL)
            SiteProvider.PLAUSIBLE -> setOf(SiteServiceFieldKeys.SITE_ID)
            SiteProvider.UMAMI -> setOf(SiteServiceFieldKeys.AUTH_MODE, SiteServiceFieldKeys.BASE_URL)
            else -> emptySet()
        }
        val cleaned = fields.filterKeys(allowed::contains)
            .mapValues { it.value.trim() }
            .filterValues(String::isNotEmpty)
            .toMutableMap()
        if (provider == SiteProvider.UMAMI && cleaned[SiteServiceFieldKeys.AUTH_MODE] != UmamiSiteAdapter.SELF_HOSTED) {
            cleaned[SiteServiceFieldKeys.AUTH_MODE] = UmamiSiteAdapter.CLOUD
            cleaned.remove(SiteServiceFieldKeys.BASE_URL)
        }
        return cleaned
    }

    private suspend inline fun <T> capture(providerId: String?, crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: SiteServicesUiException) {
        Result.failure(error)
    } catch (error: SiteServiceException) {
        Result.failure(SiteServicesUiException(error.message ?: genericFailure(providerId)))
    } catch (error: GoogleOAuthException) {
        Result.failure(SiteServicesUiException(error.message ?: genericFailure(providerId)))
    } catch (_: SecurityException) {
        Result.failure(SiteServicesUiException("Secure storage is unavailable. Unlock the device and try again."))
    } catch (_: Exception) {
        Result.failure(SiteServicesUiException(genericFailure(providerId)))
    }

    private fun genericFailure(providerId: String?): String {
        val name = providerId?.let(SiteProvider::fromId)?.displayName ?: "The site service"
        return "$name could not complete this request."
    }

    companion object {
        const val MAXIMUM_VISIBLE_RESOURCES: Int = 500
        internal const val GOOGLE_ANALYTICS_SESSION_SLOT = "site.google-analytics"

        fun create(context: Context): NativeSiteServicesUiGateway {
            val applicationContext = context.applicationContext
            val networkExecutor = Executors.newFixedThreadPool(8) { runnable ->
                Thread(runnable, "verceltics-site-services").apply { isDaemon = true }
            }
            return NativeSiteServicesUiGateway(
                store = SiteConnectionStore(SiteConnectionRepository.create(applicationContext)),
                api = SiteServicesApi(executor = networkExecutor),
                googleSessions = mapOf(
                    SiteProvider.GOOGLE_ANALYTICS to GoogleOAuthSession.create(applicationContext, GOOGLE_ANALYTICS_SESSION_SLOT),
                ),
                storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "verceltics-site-services-storage").apply { isDaemon = true }
                },
            )
        }
    }
}

private fun SiteMetric.toUi() = SiteMetricUi(key, label, value, unit, formattedValue)

private fun SiteResource.toUi() = SiteResourceUi(
    id = id,
    name = name,
    subtitle = subtitle,
    url = url,
    status = status,
    updatedAtMillis = updatedAtMillis,
    metrics = metrics.map(SiteMetric::toUi),
    metadata = metadata,
)

private fun SiteDetailPayload.toUi(isPartial: Boolean) = SiteServiceDetailUi(
    providerId = provider.id,
    resourceId = resourceId,
    title = title,
    sections = sections,
    series = series,
    tables = tables,
    rawResponses = rawResponses,
    warnings = warnings,
    fetchedAtMillis = fetchedAtMillis,
    isPartial = isPartial,
)

/** Small in-memory LRU of detail workspaces with per-provider freshness (iOS `payloadCache`). */
internal class SiteDetailMemoryCache(private val maximumEntries: Int) {
    private class Entry(val provider: SiteProvider, val detail: SiteServiceDetailUi, val storedAtMillis: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maximumEntries
    }

    @Synchronized
    fun fresh(key: String, provider: SiteProvider, nowMillis: Long): SiteServiceDetailUi? {
        val entry = entries[key] ?: return null
        return entry.detail.takeIf { nowMillis - entry.storedAtMillis < provider.detailCacheLifetimeMillis }
    }

    @Synchronized
    fun put(key: String, provider: SiteProvider, detail: SiteServiceDetailUi, nowMillis: Long) {
        entries[key] = Entry(provider, detail, nowMillis)
    }

    @Synchronized
    fun invalidate(provider: SiteProvider) {
        entries.entries.removeAll { it.value.provider == provider }
    }
}
