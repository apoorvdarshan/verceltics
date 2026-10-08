package com.apoorvdarshan.verceltics.ui.cloudflare

import android.content.Context
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccountInventory
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAccountSummary
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareApi
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionCommit
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionRepository
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareConnectionStore
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareDataSource
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareFetchResult
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflarePagesProject
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareProfile
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareReadApi
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareRestoreProblem
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareRestoreResult
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareSavedAccount
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareSnapshot
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareWorkerScript
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareZone
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.HttpsCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareMutationEventFlow
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.hosting.ProviderAccountUi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Production bridge from the encrypted Cloudflare backend to display-only models.
 *
 * The dashboard lists every loaded account, zone, Pages project and Worker (iOS has no display
 * cap); only the offline cache inside [CloudflareConnectionStore] is bounded.
 */
class NativeCloudflareUiGateway internal constructor(
    private val connectionStore: CloudflareConnectionStore,
    private val dataSource: CloudflareDataSource,
    private val networkExecutor: ExecutorService,
    private val storageExecutor: ExecutorService,
    private val beforeAcceptValidatedConnection: suspend () -> Unit = {},
    private val afterAcceptValidatedConnection: suspend () -> Unit = {},
    private val restTransport: CloudflareRestTransport = HttpsCloudflareRestTransport(),
    /** Identity checks for the launch login refresh (iOS `refreshAccountProfiles`). */
    private val profileApi: CloudflareReadApi = CloudflareApi(),
) : CloudflareUiGateway {
    override suspend fun restore(): Result<CloudflareRestoreUi> = capture {
        when (val restored = executeAwait(storageExecutor, connectionStore::restore)) {
            CloudflareRestoreResult.NotConnected -> CloudflareRestoreUi.NotConnected
            is CloudflareRestoreResult.Restored -> restored.cachedSnapshot?.let { snapshot ->
                CloudflareRestoreUi.Available(
                    snapshot.toDashboardUi(
                        if (restored.cacheIsStale) {
                            CloudflareCacheState.CACHED_STALE
                        } else {
                            CloudflareCacheState.CACHED_FRESH
                        },
                        restored.savedAccountId,
                    ),
                )
            } ?: CloudflareRestoreUi.SavedWithoutInventory(restored.profile.toUi(restored.savedAccountId))
            is CloudflareRestoreResult.Unavailable -> CloudflareRestoreUi.SavedUnavailable(
                when (restored.problem) {
                    CloudflareRestoreProblem.SAVED_RECORD_UNREADABLE ->
                        "The saved Cloudflare connection could not be opened. It was not deleted or replaced."
                    CloudflareRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                        "Secure storage is unavailable. Unlock the device and try again."
                },
            )
        }
    }

    /** Successful writes from operations, storage and every Cloudflare tool (iOS `cloudflareDataDidChange`). */
    private val mutationFlow = cloudflareMutationEventFlow()

    override fun mutationEvents(): Flow<CloudflareMutationEvent> = mutationFlow.asSharedFlow()

    /** Called by the Cloudflare tools after an explorer, Complete API or Product Center write succeeds. */
    internal fun publishToolsMutation(event: CloudflareMutationEvent) {
        mutationFlow.tryEmit(event)
    }

    override suspend fun connect(credential: CloudflareCredential): Result<CloudflareDashboardUi> = capture {
        val result = dataSource.newDashboardCall(credential).executeAwait(networkExecutor)
        val snapshot = result.snapshotOrThrow()
        var pendingCommit: CloudflareConnectionCommit? = null
        try {
            val commit = persistValidatedConnectionAwait(
                executor = storageExecutor,
                connectionStore = connectionStore,
                credential = credential,
                result = result,
            )
            pendingCommit = commit
            beforeAcceptValidatedConnection()
            withContext(NonCancellable) {
                executeAwait(storageExecutor) { connectionStore.acceptValidatedConnection(commit) }
                pendingCommit = null
                afterAcceptValidatedConnection()
                snapshot.toDashboardUi(CloudflareCacheState.LIVE, commit.savedAccountId)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                pendingCommit?.let { commit ->
                    executeAwait(storageExecutor) {
                        connectionStore.rollbackValidatedConnection(commit)
                    }
                }
                executeAwait(storageExecutor) {}
            }
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) {
                pendingCommit?.let { commit ->
                    executeAwait(storageExecutor) {
                        connectionStore.rollbackValidatedConnection(commit)
                    }
                }
            }
            throw error
        }
    }

    override suspend fun refresh(preferredAccountId: String?): Result<CloudflareDashboardUi> = capture {
        val saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
            ?: throw CloudflareUiException("Connect a Cloudflare account first.")
        val result = dataSource.newDashboardCall(
            credential = saved.connection.account.credential,
            preferredAccountId = preferredAccountId,
        ).executeAwait(networkExecutor)
        val snapshot = result.snapshotOrThrow()
        val persisted = executeAwait(storageExecutor) {
            connectionStore.persistRefreshResult(saved, result)
        }
        if (!persisted) {
            throw CloudflareUiException(
                "The saved Cloudflare connection changed while refreshing. Reopen it and try again.",
            )
        }
        snapshot.toDashboardUi(CloudflareCacheState.LIVE, saved.savedAccountId)
    }

    override suspend fun disconnect(): Result<Unit> = capture {
        executeAwait(storageExecutor, connectionStore::disconnect)
    }

    override suspend fun savedLogins(): Result<List<ProviderAccountUi>> = capture {
        executeAwait(storageExecutor, connectionStore::accounts).map(CloudflareSavedAccount::toUi)
    }

    override suspend fun switchLogin(savedAccountId: String): Result<CloudflareRestoreUi> = capture {
        if (!executeAwait(storageExecutor) { connectionStore.switchAccount(savedAccountId) }) {
            throw CloudflareUiException("That Cloudflare account is no longer saved.")
        }
        restore().getOrThrow()
    }

    override suspend fun removeLogin(savedAccountId: String): Result<CloudflareRestoreUi> = capture {
        executeAwait(storageExecutor) { connectionStore.removeAccount(savedAccountId) }
        restore().getOrThrow()
    }

    override suspend fun removeActiveLogin(): Result<CloudflareRestoreUi> = capture {
        executeAwait(storageExecutor, connectionStore::removeActiveAccount)
        restore().getOrThrow()
    }

    /**
     * iOS `refreshAccountProfiles`: a Global API Key login re-reads `/user` (its name), a scoped
     * token re-verifies (its status). Changes are stored with compare-and-swap; failures and
     * changed identities never touch saved logins.
     */
    override suspend fun refreshLoginProfiles(): Result<List<ProviderAccountUi>> = capture {
        executeAwait(storageExecutor, connectionStore::loadAllAccounts).forEach { versioned ->
            val account = versioned.connection.account
            val refreshed = try {
                when (val credential = account.credential) {
                    is CloudflareCredential.GlobalApiKey -> {
                        val user = profileApi.newUserCall(credential).executeAwait(networkExecutor)
                        account.profile.takeIf { it.id == user.id }?.copy(
                            displayName = user.displayName.take(MAX_PROFILE_NAME_CHARACTERS),
                            tokenStatus = if (user.suspended == true) "suspended" else "active",
                        )
                    }
                    is CloudflareCredential.ApiToken -> {
                        val verification = profileApi.newVerifyTokenCall(credential.token).executeAwait(networkExecutor)
                        account.profile.takeIf { verification.id == null || it.id == verification.id }
                            ?.copy(tokenStatus = verification.status)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@forEach
            executeAwait(storageExecutor) { connectionStore.persistRefreshedProfile(versioned, refreshed) }
        }
        executeAwait(storageExecutor, connectionStore::accounts).map(CloudflareSavedAccount::toUi)
    }

    /** Cloudflare tools borrow the saved credential through this gateway's serialized encrypted store. */
    internal suspend fun loadSavedCredentialForTools(): CloudflareCredential? =
        executeAwait(storageExecutor) { connectionStore.loadForRefresh()?.connection?.account?.credential }

    private val restClient: CloudflareRestClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CloudflareRestClient(
            credentialProvider = {
                executeAwait(storageExecutor) { connectionStore.loadForRefresh()?.connection?.account?.credential }
                    ?: throw CloudflareOperationException.notConnected()
            },
            executor = networkExecutor,
            transport = restTransport,
            mutationEvents = mutationFlow,
        )
    }

    override fun operationsClient(): CloudflareRestClient = restClient

    private fun CloudflareFetchResult.snapshotOrThrow(): CloudflareSnapshot = when (this) {
        is CloudflareFetchResult.Complete -> snapshot
        is CloudflareFetchResult.Partial -> snapshot
        is CloudflareFetchResult.Failure -> throw CloudflareUiException(failure.message)
    }

    /** Every loaded account and resource is listed, searchable and pickable (no display cap). */
    internal fun CloudflareSnapshot.toDashboardUi(
        cacheState: CloudflareCacheState,
        savedAccountId: String? = null,
    ): CloudflareDashboardUi =
        CloudflareDashboardUi(
            profile = profile.toUi(savedAccountId),
            accounts = accounts.map(CloudflareAccountSummary::toUi),
            loadedAccountCount = accounts.size,
            accountsComplete = accountsComplete,
            accountsTruncatedForDisplay = false,
            selectedAccountId = selectedAccountId,
            inventory = selectedAccountInventory?.toUi(),
            warnings = warnings,
            fetchedAtMillis = fetchedAtMillis,
            cacheState = cacheState,
        )

    private fun CloudflareAccountInventory.toUi(): CloudflareInventoryUi = CloudflareInventoryUi(
        accountId = accountId,
        zones = zones.map(CloudflareZone::toUi),
        pagesProjects = pagesProjects.map(CloudflarePagesProject::toUi),
        workers = workers.map(CloudflareWorkerScript::toUi),
        loadedZoneCount = zones.size,
        loadedPagesProjectCount = pagesProjects.size,
        loadedWorkerCount = workers.size,
        zonesComplete = zonesComplete,
        pagesComplete = pagesComplete,
        workersComplete = workersComplete,
        zonesTruncatedForDisplay = false,
        pagesTruncatedForDisplay = false,
        workersTruncatedForDisplay = false,
        warnings = warnings,
    )

    companion object {
        private const val MAX_PROFILE_NAME_CHARACTERS = 1_024

        fun create(context: Context): NativeCloudflareUiGateway = NativeCloudflareUiGateway(
            connectionStore = CloudflareConnectionStore(
                CloudflareConnectionRepository.create(context.applicationContext),
            ),
            dataSource = CloudflareDataSource(),
            networkExecutor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "verceltics-cloudflare").apply { isDaemon = true }
            },
            storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "verceltics-cloudflare-storage").apply { isDaemon = true }
            },
        )
    }
}

private fun CloudflareProfile.toUi(savedAccountId: String?) =
    CloudflareProfileUi(id, displayName, tokenStatus, authMode, email, savedAccountId)

private fun CloudflareSavedAccount.toUi(): ProviderAccountUi = ProviderAccountUi(
    id = savedAccountId,
    displayName = profile?.displayName ?: "Saved Cloudflare account",
    detail = when {
        profile == null -> "Couldn’t open this saved account"
        profile.authMode == CloudflareAuthMode.GLOBAL_API_KEY -> profile.email ?: "Global API Key"
        else -> "Scoped API token"
    },
    avatarUrl = null,
    isActive = isActive,
    isReadable = profile != null,
)

private fun CloudflareAccountSummary.toUi() = CloudflareAccountUi(id, name, type)

private fun CloudflareZone.toUi() = CloudflareZoneUi(
    id = id,
    name = name,
    status = status,
    type = type,
    paused = paused,
    accountName = accountName,
    planName = planName,
)

private fun CloudflarePagesProject.toUi() = CloudflarePagesProjectUi(
    id = id,
    name = name,
    subdomain = subdomain,
    domains = domains,
    productionBranch = productionBranch,
    latestDeploymentStatus = latestDeploymentStatus,
)

private fun CloudflareWorkerScript.toUi() = CloudflareWorkerUi(
    id = id,
    modifiedOn = modifiedOn,
    compatibilityDate = compatibilityDate,
    handlers = handlers,
    hasAssets = hasAssets,
    hasModules = hasModules,
    routes = routes,
    tags = tags,
)

private suspend fun <T> CancelableCall<T>.executeAwait(executor: ExecutorService): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        executor.execute {
            try {
                val value = execute()
                if (continuation.isActive) continuation.resume(value)
            } catch (error: CancellationException) {
                if (continuation.isActive) continuation.cancel(error)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

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

private suspend fun persistValidatedConnectionAwait(
    executor: ExecutorService,
    connectionStore: CloudflareConnectionStore,
    credential: CloudflareCredential,
    result: CloudflareFetchResult,
): CloudflareConnectionCommit = suspendCancellableCoroutine { continuation ->
    val committed = AtomicReference<CloudflareConnectionCommit?>()
    val cleanupScheduled = AtomicBoolean(false)

    fun scheduleRollback() {
        if (!cleanupScheduled.compareAndSet(false, true)) return
        executor.execute {
            committed.get()?.let { commit ->
                runCatching { connectionStore.rollbackValidatedConnection(commit) }
            }
        }
    }

    continuation.invokeOnCancellation { scheduleRollback() }
    executor.execute {
        if (!continuation.isActive) return@execute
        try {
            val commit = connectionStore.saveValidatedConnection(credential, result)
            committed.set(commit)
            continuation.resume(commit) { _, _, _ -> scheduleRollback() }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

private suspend inline fun <T> capture(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: CloudflareUiException) {
    Result.failure(error)
} catch (_: SecurityException) {
    Result.failure(CloudflareUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(CloudflareUiException("Cloudflare could not complete this request."))
}
