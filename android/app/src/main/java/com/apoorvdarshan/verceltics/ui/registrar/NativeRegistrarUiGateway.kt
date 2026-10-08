package com.apoorvdarshan.verceltics.ui.registrar

import android.content.Context
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.registrar.PublicIpv4Lookup
import com.apoorvdarshan.verceltics.data.registrar.RegistrarApi
import com.apoorvdarshan.verceltics.data.registrar.RegistrarApiException
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionCommit
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionRepository
import com.apoorvdarshan.verceltics.data.registrar.RegistrarConnectionStore
import com.apoorvdarshan.verceltics.data.registrar.RegistrarCredentials
import com.apoorvdarshan.verceltics.data.registrar.RegistrarDomain
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRawApi
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRefreshOutcome
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRestoreProblem
import com.apoorvdarshan.verceltics.data.registrar.RegistrarRestoreResult
import com.apoorvdarshan.verceltics.data.registrar.RegistrarSnapshot
import com.apoorvdarshan.verceltics.data.registrar.RegistrarValidation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Production bridge from the native encrypted registrar backend into observable UI models. */
class NativeRegistrarUiGateway internal constructor(
    private val connectionStore: RegistrarConnectionStore,
    private val api: RegistrarApi,
    private val publicIpv4Lookup: PublicIpv4Lookup,
    private val networkExecutor: ExecutorService,
    private val storageExecutor: ExecutorService,
    private val beforeAcceptValidatedConnection: suspend () -> Unit = {},
    private val rawApi: RegistrarRawApi = RegistrarRawApi(),
) : RegistrarUiGateway {
    override suspend fun restore(): Result<RegistrarRestoreUi> = capture {
        val restored = executeAwait(storageExecutor, connectionStore::restoreAll)
        RegistrarRestoreUi(
            restored.entries.associate { (provider, result) -> provider.id to result.toUi(provider) },
        )
    }

    override suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi> = capture {
        val provider = providerOrThrow(request.providerId)
        val credentials = RegistrarCredentials.fromInput(
            provider = provider,
            apiKey = request.apiKey,
            apiSecret = request.apiSecret,
            username = request.username,
            clientIp = request.clientIp,
            organization = request.organization,
        )
        val validated = api.newValidateCredentialsCall(credentials).executeAwait(networkExecutor)
        val validation = RegistrarValidation(validated.accountName, validated.domains.sortedForDisplay())
        var pendingCommit: RegistrarConnectionCommit? = null
        try {
            val commit = persistValidatedConnectionAwait(storageExecutor, connectionStore, credentials, validation)
            pendingCommit = commit
            beforeAcceptValidatedConnection()
            // Acceptance is the commit point. Once entered, cancellation cannot turn a durable
            // successful connection into a reported failure or request compensation.
            withContext(NonCancellable) {
                executeAwait(storageExecutor) { connectionStore.acceptValidatedConnection(commit) }
                pendingCommit = null
                commit.snapshot.toDashboardUi(RegistrarCacheState.LIVE)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                pendingCommit?.let { commit ->
                    executeAwait(storageExecutor) { connectionStore.rollbackValidatedConnection(commit) }
                }
                // Barrier: a rollback queued by the save continuation completes before retry.
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

    override suspend fun refresh(providerId: String): Result<RegistrarDashboardUi> = capture {
        val provider = providerOrThrow(providerId)
        val saved = executeAwait(storageExecutor) { connectionStore.loadForRefresh(provider) }
            ?: throw RegistrarUiException("Connect ${provider.displayName} first.")
        val credentials = saved.account.credentials
        val domains = api.newFetchDomainsCall(credentials).executeAwait(networkExecutor).sortedForDisplay()
        when (val outcome = executeAwait(storageExecutor) { connectionStore.persistRefreshResult(credentials, domains) }) {
            RegistrarRefreshOutcome.Disconnected ->
                throw RegistrarUiException("${provider.displayName} was disconnected during the refresh.")
            RegistrarRefreshOutcome.Replaced ->
                throw RegistrarUiException("${provider.displayName} was reconnected during the refresh.")
            is RegistrarRefreshOutcome.Refreshed -> outcome.snapshot.toDashboardUi(RegistrarCacheState.LIVE)
        }
    }

    override suspend fun disconnect(providerId: String): Result<Unit> = capture {
        val provider = providerOrThrow(providerId)
        executeAwait(storageExecutor) { connectionStore.disconnect(provider) }
    }

    override suspend fun detectPublicIpv4(): Result<String> = capture {
        publicIpv4Lookup.newResolveCall().executeAwait(networkExecutor)
    }

    override suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> = capture {
        val provider = providerOrThrow(providerId)
        val saved = executeAwait(storageExecutor) { connectionStore.loadForRefresh(provider) }
            ?: throw RegistrarUiException("Connect ${provider.displayName} first.")
        rawApi.newRawCall(saved.account.credentials, request).executeAwait(networkExecutor)
    }

    private fun providerOrThrow(providerId: String): RegistrarProvider =
        RegistrarProvider.fromId(providerId) ?: throw RegistrarUiException("This registrar is not supported.")

    private fun RegistrarRestoreResult.toUi(provider: RegistrarProvider): RegistrarProviderRestoreUi = when (this) {
        RegistrarRestoreResult.NotConnected -> RegistrarProviderRestoreUi.NotConnected
        is RegistrarRestoreResult.Restored -> cachedSnapshot?.let { snapshot ->
            RegistrarProviderRestoreUi.Available(
                snapshot.toDashboardUi(
                    if (cacheIsStale) RegistrarCacheState.CACHED_STALE else RegistrarCacheState.CACHED_FRESH,
                ),
            )
        } ?: RegistrarProviderRestoreUi.SavedWithoutInventory(RegistrarAccountUi(provider.id, accountName))
        is RegistrarRestoreResult.Unavailable -> RegistrarProviderRestoreUi.SavedUnavailable(
            when (problem) {
                RegistrarRestoreProblem.SAVED_RECORD_UNREADABLE ->
                    "The saved ${provider.displayName} connection could not be opened. It was not deleted or replaced."
                RegistrarRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                    "Secure storage is unavailable. Unlock the device and try again."
            },
        )
    }

    companion object {
        fun create(context: Context): NativeRegistrarUiGateway = NativeRegistrarUiGateway(
            connectionStore = RegistrarConnectionStore(
                RegistrarConnectionRepository.create(context.applicationContext),
            ),
            api = RegistrarApi(),
            publicIpv4Lookup = PublicIpv4Lookup(),
            networkExecutor = Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "verceltics-registrar").apply { isDaemon = true }
            },
            storageExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "verceltics-registrar-storage").apply { isDaemon = true }
            },
        )
    }
}

internal fun List<RegistrarDomain>.sortedForDisplay(): List<RegistrarDomain> =
    sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, RegistrarDomain::name))

internal fun RegistrarSnapshot.toDashboardUi(cacheState: RegistrarCacheState): RegistrarDashboardUi =
    RegistrarDashboardUi(
        account = RegistrarAccountUi(provider.id, accountName),
        domains = domains.map(RegistrarDomain::toUi),
        inventoryComplete = domainsComplete,
        warnings = warnings,
        fetchedAtMillis = fetchedAtMillis,
        cacheState = cacheState,
    )

internal fun RegistrarDomain.toUi(): RegistrarDomainUi = RegistrarDomainUi(
    id = id,
    name = name,
    status = status,
    createdAtMillis = createdAtMillis,
    expiresAtMillis = expiresAtMillis,
    autoRenew = autoRenew,
    locked = locked,
    privacyEnabled = privacyEnabled,
    nameservers = nameservers,
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
    connectionStore: RegistrarConnectionStore,
    credentials: RegistrarCredentials,
    validation: RegistrarValidation,
): RegistrarConnectionCommit = suspendCancellableCoroutine { continuation ->
    val committed = AtomicReference<RegistrarConnectionCommit?>()
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
            val commit = connectionStore.saveValidatedConnection(credentials, validation)
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
} catch (error: RegistrarUiException) {
    Result.failure(error)
} catch (error: RegistrarApiException) {
    Result.failure(RegistrarUiException(error.message))
} catch (_: SecurityException) {
    Result.failure(RegistrarUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(RegistrarUiException("The registrar could not complete this request."))
}
