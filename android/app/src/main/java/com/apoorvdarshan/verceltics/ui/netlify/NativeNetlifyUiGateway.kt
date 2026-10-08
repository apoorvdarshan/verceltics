package com.apoorvdarshan.verceltics.ui.netlify

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiRequestException
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.hosting.HostingApiException
import com.apoorvdarshan.verceltics.data.hosting.HostingRawApi
import com.apoorvdarshan.verceltics.data.hosting.SecureHostingHttpTransport
import com.apoorvdarshan.verceltics.data.netlify.NetlifyBuild
import com.apoorvdarshan.verceltics.data.netlify.NetlifyBuildControls
import com.apoorvdarshan.verceltics.data.netlify.NetlifyCollectionResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionCommit
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionRepository
import com.apoorvdarshan.verceltics.data.netlify.NetlifyConnectionStore
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDataSource
import com.apoorvdarshan.verceltics.data.netlify.NetlifyDeployment
import com.apoorvdarshan.verceltics.data.netlify.NetlifyFetchResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifyApi
import com.apoorvdarshan.verceltics.data.netlify.NetlifyProfile
import com.apoorvdarshan.verceltics.data.netlify.NetlifyReadApi
import com.apoorvdarshan.verceltics.data.netlify.NetlifySavedAccount
import com.apoorvdarshan.verceltics.data.netlify.NetlifyResourceResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifyRestoreProblem
import com.apoorvdarshan.verceltics.data.netlify.NetlifyRestoreResult
import com.apoorvdarshan.verceltics.data.netlify.NetlifySite
import com.apoorvdarshan.verceltics.data.netlify.NetlifySiteDetails
import com.apoorvdarshan.verceltics.data.netlify.NetlifySnapshot
import com.apoorvdarshan.verceltics.data.netlify.NetlifyWriteApi
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Production-owned bridge from the native encrypted Netlify backend into observable UI models. */
class NativeNetlifyUiGateway internal constructor(
    private val connectionStore: NetlifyConnectionStore,
    private val dataSource: NetlifyDataSource,
    private val networkExecutor: ExecutorService,
    private val storageExecutor: ExecutorService,
    private val beforeAcceptValidatedConnection: suspend () -> Unit = {},
    private val afterAcceptValidatedConnection: suspend () -> Unit = {},
    /** Complete API raw requests (Netlify's fixed `api.netlify.com` origin). */
    private val rawApi: HostingRawApi = HostingRawApi(SecureHostingHttpTransport(networkExecutor)),
    /** Confirmed writes (iOS "Redeploy") on the same fixed origin. */
    private val writeApi: NetlifyWriteApi = NetlifyWriteApi(SecureHostingHttpTransport(networkExecutor)),
    /** Profile validation for the launch account refresh (iOS `refreshAccountProfiles`). */
    private val profileApi: NetlifyReadApi = NetlifyApi(),
) : NetlifyUiGateway {
    override suspend fun restore(): Result<NetlifyRestoreUi> = capture {
        when (val restored = executeAwait(storageExecutor, connectionStore::restore)) {
            NetlifyRestoreResult.NotConnected -> NetlifyRestoreUi.NotConnected
            is NetlifyRestoreResult.Restored -> restored.cachedSnapshot?.let { snapshot ->
                NetlifyRestoreUi.Available(
                    snapshot.toDashboardUi(
                        cacheState = if (restored.cacheIsStale) {
                            NetlifyCacheState.CACHED_STALE
                        } else {
                            NetlifyCacheState.CACHED_FRESH
                        },
                        savedAccountId = restored.accountId,
                    ),
                )
            } ?: NetlifyRestoreUi.SavedWithoutInventory(restored.profile.toUi(restored.accountId))
            is NetlifyRestoreResult.Unavailable -> NetlifyRestoreUi.SavedUnavailable(
                when (restored.problem) {
                    NetlifyRestoreProblem.SAVED_RECORD_UNREADABLE ->
                        "The saved Netlify connection could not be opened. It was not deleted or replaced."
                    NetlifyRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                        "Secure storage is unavailable. Unlock the device and try again."
                },
            )
        }
    }

    override suspend fun connect(personalToken: SecretValue): Result<NetlifyDashboardUi> = capture {
        val result = dataSource.newSnapshotCall(personalToken).executeAwait(networkExecutor)
        val snapshot = result.snapshotOrThrow()
        var pendingCommit: NetlifyConnectionCommit? = null
        try {
            val commit = persistValidatedConnectionAwait(
                executor = storageExecutor,
                connectionStore = connectionStore,
                token = personalToken,
                result = result,
            )
            pendingCommit = commit
            beforeAcceptValidatedConnection()
            // Acceptance is the commit point. Once entered, cancellation cannot turn a durable
            // successful connection into a reported failure or request compensation.
            withContext(NonCancellable) {
                executeAwait(storageExecutor) { connectionStore.acceptValidatedConnection(commit) }
                pendingCommit = null
                afterAcceptValidatedConnection()
                snapshot.toDashboardUi(NetlifyCacheState.LIVE, commit.savedAccountId)
            }
        } catch (error: CancellationException) {
            // Cancellation can land after the encrypted save has returned but before accept. In
            // that window the inner continuation is already complete, so compensate explicitly.
            // A cancellation during the save itself is handled by persistValidatedConnectionAwait;
            // the single-threaded barrier waits for that queued rollback before allowing retry.
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

    /** Refreshes the active account; the result is stored only into that same account. */
    override suspend fun refresh(): Result<NetlifyDashboardUi> = capture {
        val saved = executeAwait(storageExecutor, connectionStore::loadActive)
            ?: throw NetlifyUiException("Connect a Netlify account first.")
        val result = dataSource.newSnapshotCall(saved.connection.account.personalToken).executeAwait(networkExecutor)
        val snapshot = result.snapshotOrThrow()
        executeAwait(storageExecutor) { connectionStore.persistRefreshResult(saved.accountId, result) }
        snapshot.toDashboardUi(NetlifyCacheState.LIVE, saved.accountId)
    }

    override suspend fun accounts(): Result<List<ProviderAccountUi>> = capture {
        executeAwait(storageExecutor, connectionStore::accounts).map(NetlifySavedAccount::toUi)
    }

    override suspend fun switchAccount(accountId: String): Result<NetlifyRestoreUi> = capture {
        if (!executeAwait(storageExecutor) { connectionStore.switchAccount(accountId) }) {
            throw NetlifyUiException("That Netlify account is no longer saved.")
        }
        restore().getOrThrow()
    }

    override suspend fun removeAccount(accountId: String): Result<NetlifyRestoreUi> = capture {
        executeAwait(storageExecutor) { connectionStore.removeAccount(accountId) }
        restore().getOrThrow()
    }

    /**
     * iOS `refreshAccountProfiles`: every saved token is validated again and a changed name, email
     * or avatar is stored with compare-and-swap. Failures never touch saved accounts.
     */
    override suspend fun refreshAccountProfiles(): Result<List<ProviderAccountUi>> = capture {
        executeAwait(storageExecutor, connectionStore::loadAllAccounts).forEach { versioned ->
            val profile = try {
                profileApi.newValidatePersonalTokenCall(versioned.connection.account.personalToken)
                    .executeAwait(networkExecutor)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@forEach
            executeAwait(storageExecutor) { connectionStore.persistRefreshedProfile(versioned, profile) }
        }
        executeAwait(storageExecutor, connectionStore::accounts).map(NetlifySavedAccount::toUi)
    }

    override suspend fun loadSite(siteId: String): Result<NetlifySiteWorkspaceUi> = capture {
        val saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
            ?: throw NetlifyUiException("Connect a Netlify account first.")
        val token = saved.account.personalToken
        coroutineScope {
            val details = async {
                dataSource.newSiteDetailsCall(token, siteId).executeAwait(networkExecutor)
            }
            val deployments = async {
                dataSource.newDeploymentsCall(token, siteId).executeAwait(networkExecutor)
            }
            val builds = async {
                dataSource.newBuildsCall(token, siteId).executeAwait(networkExecutor)
            }
            NetlifySiteWorkspaceUi(
                siteId = siteId,
                details = details.await().toUi(),
                deployments = deployments.await().toDeploymentsUi(),
                builds = builds.await().toBuildsUi(),
            )
        }
    }

    override suspend fun disconnect(): Result<Unit> = capture {
        executeAwait(storageExecutor, connectionStore::disconnect)
    }

    override suspend fun redeploySite(siteId: String): Result<String> = capture {
        val saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
            ?: throw NetlifyUiException("Connect a Netlify account first.")
        writeApi.redeploy(saved.account.personalToken, siteId)
        REDEPLOY_ACCEPTED
    }

    override suspend fun sendApiRequest(request: ProviderRawRequest): Result<ProviderRawResponse> = capture {
        val saved = executeAwait(storageExecutor, connectionStore::loadForRefresh)
            ?: throw NetlifyUiException("Connect a Netlify account first.")
        rawApi.sendNetlify(saved.account.personalToken, request)
    }

    private fun NetlifyFetchResult.snapshotOrThrow(): NetlifySnapshot = when (this) {
        is NetlifyFetchResult.Complete -> snapshot
        is NetlifyFetchResult.Partial -> snapshot
        is NetlifyFetchResult.Failure -> throw NetlifyUiException(failure.message)
    }

    /** The live inventory is never truncated; only the offline cache is bounded (by the store). */
    private fun NetlifySnapshot.toDashboardUi(
        cacheState: NetlifyCacheState,
        savedAccountId: String?,
    ): NetlifyDashboardUi =
        NetlifyDashboardUi(
            account = profile.toUi(savedAccountId),
            sites = sites.map(NetlifySite::toUi),
            loadedSiteCount = sites.size,
            providerInventoryComplete = sitesComplete,
            warnings = warnings,
            fetchedAtMillis = fetchedAtMillis,
            cacheState = cacheState,
        )

    private fun NetlifyResourceResult<NetlifySiteDetails>.toUi():
        NetlifyResourceUi<NetlifySiteDetailsUi> = when (this) {
        is NetlifyResourceResult.Complete -> NetlifyResourceUi.Available(value.toUi())
        is NetlifyResourceResult.Failure -> NetlifyResourceUi.Unavailable(failure.message)
    }

    private fun NetlifyCollectionResult<NetlifyDeployment>.toDeploymentsUi():
        NetlifyCollectionUi<NetlifyDeploymentUi> = when (this) {
        is NetlifyCollectionResult.Complete -> collection(
            items = items.map(NetlifyDeployment::toUi),
            providerComplete = true,
            warning = null,
        )
        is NetlifyCollectionResult.Partial -> collection(
            items = items.map(NetlifyDeployment::toUi),
            providerComplete = false,
            warning = failure.message,
        )
        is NetlifyCollectionResult.Failure -> NetlifyCollectionUi(
            items = emptyList(),
            loadedItemCount = 0,
            providerCollectionComplete = false,
            warning = failure.message,
        )
    }

    private fun NetlifyCollectionResult<NetlifyBuild>.toBuildsUi():
        NetlifyCollectionUi<NetlifyBuildUi> = when (this) {
        is NetlifyCollectionResult.Complete -> collection(
            items = items.map(NetlifyBuild::toUi),
            providerComplete = true,
            warning = null,
        )
        is NetlifyCollectionResult.Partial -> collection(
            items = items.map(NetlifyBuild::toUi),
            providerComplete = false,
            warning = failure.message,
        )
        is NetlifyCollectionResult.Failure -> NetlifyCollectionUi(
            items = emptyList(),
            loadedItemCount = 0,
            providerCollectionComplete = false,
            warning = failure.message,
        )
    }

    /** Complete history, as iOS shows it; pagination itself is bounded by the data source. */
    private fun <T> collection(
        items: List<T>,
        providerComplete: Boolean,
        warning: String?,
    ): NetlifyCollectionUi<T> = NetlifyCollectionUi(
        items = items,
        loadedItemCount = items.size,
        providerCollectionComplete = providerComplete,
        warning = warning,
    )

    companion object {
        const val REDEPLOY_ACCEPTED: String = "Redeploy request accepted."

        fun create(context: Context): NativeNetlifyUiGateway = NativeNetlifyUiGateway(
            connectionStore = NetlifyConnectionStore(
                NetlifyConnectionRepository.create(context.applicationContext),
            ),
            dataSource = NetlifyDataSource(),
            networkExecutor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "verceltics-netlify").apply { isDaemon = true }
            },
            storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "verceltics-netlify-storage").apply { isDaemon = true }
            },
        )
    }
}

private fun NetlifyProfile.toUi(savedAccountId: String?): NetlifyAccountUi =
    NetlifyAccountUi(id, displayName, email, avatarUrl, savedAccountId)

private fun NetlifySavedAccount.toUi(): ProviderAccountUi = ProviderAccountUi(
    id = accountId,
    displayName = profile?.displayName ?: "Saved Netlify account",
    detail = if (profile == null) "Couldn’t open this saved account" else profile.email,
    avatarUrl = profile?.avatarUrl,
    isActive = isActive,
    isReadable = profile != null,
)

private fun NetlifySite.toUi(): NetlifySiteUi = NetlifySiteUi(
    id = id,
    name = name,
    subtitle = subtitle,
    url = url,
    status = status,
    updatedAtMillis = updatedAtMillis,
)

private fun NetlifySiteDetails.toUi(): NetlifySiteDetailsUi = NetlifySiteDetailsUi(
    site = site.toUi(),
    domains = domains.map { NetlifyDomainUi(it.name, it.kind.name) },
    buildControls = buildControls?.toUi(),
    publishedDeployment = publishedDeployment?.toUi(),
)

private fun NetlifyBuildControls.toUi(): NetlifyBuildControlsUi = NetlifyBuildControlsUi(
    buildsStopped = buildsStopped,
    repositoryUrl = repositoryUrl,
    repositoryPath = repositoryPath,
    repositoryBranch = repositoryBranch,
    baseDirectory = baseDirectory,
    publishDirectory = publishDirectory,
    functionsDirectory = functionsDirectory,
    buildCommand = buildCommand,
    allowedBranches = allowedBranches,
    provider = provider,
)

private fun NetlifyDeployment.toUi(): NetlifyDeploymentUi = NetlifyDeploymentUi(
    id = id,
    title = title,
    status = status,
    createdAtMillis = createdAtMillis,
    url = url,
    branch = branch,
    commitMessage = commitMessage,
)

private fun NetlifyBuild.toUi(): NetlifyBuildUi = NetlifyBuildUi(
    id = id,
    deploymentId = deploymentId,
    commitSha = commitSha,
    isDone = isDone,
    error = error,
    createdAtMillis = createdAtMillis,
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
    connectionStore: NetlifyConnectionStore,
    token: SecretValue,
    result: NetlifyFetchResult,
): NetlifyConnectionCommit = suspendCancellableCoroutine { continuation ->
    val committed = AtomicReference<NetlifyConnectionCommit?>()
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
            val commit = connectionStore.saveValidatedConnection(token, result)
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
} catch (error: NetlifyUiException) {
    Result.failure(error)
} catch (error: HostingApiException) {
    Result.failure(NetlifyUiException(error.failure.message))
} catch (error: ProviderApiRequestException) {
    Result.failure(NetlifyUiException(error.message ?: "The request is invalid."))
} catch (_: SecurityException) {
    Result.failure(NetlifyUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(NetlifyUiException("Netlify could not complete this request."))
}
