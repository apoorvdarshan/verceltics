package com.apoorvdarshan.verceltics.ui.hosting

import android.content.Context
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.data.hosting.HostingApiException
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionCommit
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionRepository
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingDeployment
import com.apoorvdarshan.verceltics.data.hosting.HostingFailureKind
import com.apoorvdarshan.verceltics.data.hosting.HostingLinkContext
import com.apoorvdarshan.verceltics.data.hosting.HostingProfile
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingProviderApi
import com.apoorvdarshan.verceltics.data.hosting.HostingResource
import com.apoorvdarshan.verceltics.data.hosting.HostingRestoreProblem
import com.apoorvdarshan.verceltics.data.hosting.HostingRestoreResult
import com.apoorvdarshan.verceltics.data.hosting.HostingSnapshot
import com.apoorvdarshan.verceltics.data.hosting.SecureHostingHttpTransport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Production bridge from the encrypted hosting backend into observable UI models. */
class NativeHostingProviderUiGateway internal constructor(
    private val connectionStore: HostingConnectionStore,
    private val api: HostingProviderApi,
    private val storageExecutor: ExecutorService,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val beforeAcceptValidatedConnection: suspend () -> Unit = {},
    private val afterAcceptValidatedConnection: suspend () -> Unit = {},
) : HostingProviderUiGateway {
    override suspend fun restore(): Result<Map<String, HostingRestoreUi>> = capture(null) {
        executeAwait(storageExecutor, connectionStore::restoreAll).entries.associate { (provider, restored) ->
            provider.id to restored.toUi(provider)
        }
    }

    override suspend fun connect(credentials: HostingCredentials): Result<HostingDashboardUi> =
        capture(credentials.provider) {
            val snapshot = withContext(workDispatcher) {
                val profile = api.validateProfile(credentials)
                val resources = api.fetchResources(credentials)
                HostingSnapshot(credentials.provider, profile, resources, nowMillis())
            }
            val link = HostingLinkContext.of(credentials)
            var pendingCommit: HostingConnectionCommit? = null
            try {
                val commit = persistValidatedConnectionAwait(storageExecutor, connectionStore, credentials, snapshot)
                pendingCommit = commit
                beforeAcceptValidatedConnection()
                // Acceptance is the commit point; cancellation can no longer turn a durable
                // connection into a reported failure.
                withContext(NonCancellable) {
                    executeAwait(storageExecutor) { connectionStore.acceptValidatedConnection(commit) }
                    pendingCommit = null
                    afterAcceptValidatedConnection()
                    snapshot.toDashboardUi(link, HostingCacheState.LIVE)
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    pendingCommit?.let { commit ->
                        executeAwait(storageExecutor) { connectionStore.rollbackValidatedConnection(commit) }
                    }
                    // Barrier: wait for any rollback queued by an interrupted save.
                    executeAwait(storageExecutor) {}
                }
                throw error
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    pendingCommit?.let { commit ->
                        executeAwait(storageExecutor) { connectionStore.rollbackValidatedConnection(commit) }
                    }
                }
                throw error
            }
        }

    override suspend fun refresh(providerId: String): Result<HostingDashboardUi> =
        capture(HostingProvider.fromId(providerId)) {
            val provider = provider(providerId)
            val saved = executeAwait(storageExecutor) { connectionStore.loadForRefresh(provider) }
                ?: throw HostingUiException("Connect ${provider.displayName} first.")
            val credentials = saved.account.credentials
            val snapshot = withContext(workDispatcher) {
                HostingSnapshot(provider, saved.account.profile, api.fetchResources(credentials), nowMillis())
            }
            executeAwait(storageExecutor) { connectionStore.persistRefreshResult(snapshot) }
            snapshot.toDashboardUi(HostingLinkContext.of(credentials), HostingCacheState.LIVE)
        }

    override suspend fun loadResource(
        providerId: String,
        resource: HostingResourceUi,
    ): Result<HostingResourceWorkspaceUi> = capture(HostingProvider.fromId(providerId)) {
        val provider = provider(providerId)
        val saved = executeAwait(storageExecutor) { connectionStore.loadForRefresh(provider) }
            ?: throw HostingUiException("Connect ${provider.displayName} first.")
        val deployments = withContext(workDispatcher) {
            api.fetchDeployments(saved.account.credentials, resource.toModel())
        }
        val visible = deployments.take(MAXIMUM_VISIBLE_HISTORY_ITEMS)
        HostingResourceWorkspaceUi(
            providerId = providerId,
            resourceId = resource.id,
            deployments = visible.map(HostingDeployment::toUi),
            loadedDeploymentCount = deployments.size,
            truncatedForDisplay = deployments.size > visible.size,
        )
    }

    override suspend fun performPrimaryAction(
        providerId: String,
        resource: HostingResourceUi,
        latestDeploymentId: String?,
    ): Result<String> = capture(HostingProvider.fromId(providerId)) {
        val provider = provider(providerId)
        val label = provider.primaryActionLabel
            ?: throw HostingUiException("${provider.displayName} has no safe one-tap action here.")
        val saved = executeAwait(storageExecutor) { connectionStore.loadForRefresh(provider) }
            ?: throw HostingUiException("Connect ${provider.displayName} first.")
        withContext(workDispatcher) {
            api.performPrimaryAction(saved.account.credentials, resource.toModel(), latestDeploymentId)
        }
        "$label request accepted."
    }

    override suspend fun disconnect(providerId: String): Result<Unit> = capture(HostingProvider.fromId(providerId)) {
        val provider = provider(providerId)
        executeAwait(storageExecutor) { connectionStore.disconnect(provider) }
    }

    private fun provider(providerId: String): HostingProvider =
        HostingProvider.fromId(providerId) ?: throw HostingUiException("This hosting provider is not supported.")

    private fun HostingRestoreResult.toUi(provider: HostingProvider): HostingRestoreUi = when (this) {
        HostingRestoreResult.NotConnected -> HostingRestoreUi.NotConnected
        is HostingRestoreResult.Restored -> cachedSnapshot?.let { snapshot ->
            HostingRestoreUi.Available(
                snapshot.toDashboardUi(
                    linkContext,
                    if (cacheIsStale) HostingCacheState.CACHED_STALE else HostingCacheState.CACHED_FRESH,
                ),
            )
        } ?: HostingRestoreUi.SavedWithoutInventory(
            account = profile.toUi(),
            dashboardUrl = HostingProviderApi.dashboardUrl(linkContext),
        )
        is HostingRestoreResult.Unavailable -> HostingRestoreUi.SavedUnavailable(
            when (problem) {
                HostingRestoreProblem.SAVED_RECORD_UNREADABLE ->
                    "The saved ${provider.displayName} connection could not be opened. It was not deleted or replaced."
                HostingRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                    "Secure storage is unavailable. Unlock the device and try again."
            },
        )
    }

    private fun HostingSnapshot.toDashboardUi(link: HostingLinkContext, cacheState: HostingCacheState): HostingDashboardUi {
        val visible = resources.take(MAXIMUM_VISIBLE_RESOURCES)
        return HostingDashboardUi(
            providerId = provider.id,
            account = profile.toUi(),
            resources = visible.map { resource ->
                HostingResourceUi(
                    id = resource.id,
                    name = resource.name,
                    subtitle = resource.subtitle,
                    url = resource.url,
                    status = resource.status,
                    region = resource.region,
                    kind = resource.kind,
                    updatedAtMillis = resource.updatedAtMillis,
                    dashboardUrl = HostingProviderApi.dashboardUrl(link, resource),
                    metadata = resource.metadata,
                )
            },
            loadedResourceCount = resources.size,
            truncatedForDisplay = resources.size > visible.size,
            warnings = warnings,
            fetchedAtMillis = fetchedAtMillis,
            cacheState = cacheState,
            dashboardUrl = HostingProviderApi.dashboardUrl(link),
        )
    }

    companion object {
        const val MAXIMUM_VISIBLE_RESOURCES: Int = 200
        const val MAXIMUM_VISIBLE_HISTORY_ITEMS: Int = 100

        fun create(context: Context, googleAccessTokenSource: GoogleAccessTokenSource): NativeHostingProviderUiGateway {
            val networkExecutor = Executors.newFixedThreadPool(8) { runnable ->
                Thread(runnable, "verceltics-hosting").apply { isDaemon = true }
            }
            return NativeHostingProviderUiGateway(
                connectionStore = HostingConnectionStore(HostingConnectionRepository.create(context.applicationContext)),
                api = HostingProviderApi(
                    transport = SecureHostingHttpTransport(networkExecutor),
                    googleAccessTokenSource = googleAccessTokenSource,
                ),
                storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "verceltics-hosting-storage").apply { isDaemon = true }
                },
            )
        }
    }
}

private fun HostingProfile.toUi(): HostingAccountUi = HostingAccountUi(id, name, email)

private fun HostingResourceUi.toModel(): HostingResource = HostingResource(
    id = id,
    name = name,
    subtitle = subtitle,
    url = url,
    status = status,
    region = region,
    kind = kind,
    updatedAtMillis = updatedAtMillis,
    metadata = metadata,
)

private fun HostingDeployment.toUi(): HostingDeploymentUi = HostingDeploymentUi(
    id = id,
    title = title,
    status = status,
    createdAtMillis = createdAtMillis,
    url = url,
    branch = branch,
    commitMessage = commitMessage,
)

private suspend fun <T> executeAwait(executor: ExecutorService, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        val future = executor.submit {
            try {
                val value = block()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

/**
 * Saves on the single storage thread. If the caller is cancelled while the save is queued or
 * running, the completed replacement is rolled back on that same thread.
 */
private suspend fun persistValidatedConnectionAwait(
    executor: ExecutorService,
    connectionStore: HostingConnectionStore,
    credentials: HostingCredentials,
    snapshot: HostingSnapshot,
): HostingConnectionCommit = suspendCancellableCoroutine { continuation ->
    val committed = AtomicReference<HostingConnectionCommit?>()
    val cleanupScheduled = AtomicBoolean(false)

    fun scheduleRollback() {
        if (!cleanupScheduled.compareAndSet(false, true)) return
        executor.execute {
            committed.get()?.let { commit -> runCatching { connectionStore.rollbackValidatedConnection(commit) } }
        }
    }

    continuation.invokeOnCancellation { scheduleRollback() }
    executor.execute {
        if (!continuation.isActive) return@execute
        try {
            val commit = connectionStore.saveValidatedConnection(credentials, snapshot)
            committed.set(commit)
            continuation.resume(commit) { _, _, _ -> scheduleRollback() }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

private suspend inline fun <T> capture(provider: HostingProvider?, crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: HostingUiException) {
    Result.failure(error)
} catch (error: HostingApiException) {
    Result.failure(
        HostingUiException(
            error.failure.message,
            requiresGoogleSignIn = error.failure.kind == HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED,
        ),
    )
} catch (_: SecurityException) {
    Result.failure(HostingUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(
        HostingUiException("${provider?.displayName ?: "The hosting provider"} could not complete this request."),
    )
}
