package com.apoorvdarshan.verceltics.ui.pagespeed

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedApi
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedConnectionCommit
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedConnectionRepository
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedConnectionStore
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedCredentials
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedFetchResult
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedRestoreProblem
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedRestoreResult
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedSnapshot
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedSourceState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Production adapter. Provider calls and storage stay off-main and preserve cancellation. */
class NativePageSpeedUiGateway internal constructor(
    private val connectionStore: PageSpeedConnectionStore,
    private val api: PageSpeedApi,
    private val executor: ExecutorService,
) : PageSpeedUiGateway {
    override suspend fun restore(): Result<PageSpeedRestoreUi> = capture {
        when (val restored = executeAwait(executor, connectionStore::restore)) {
            PageSpeedRestoreResult.NotConnected -> PageSpeedRestoreUi.NotConnected
            is PageSpeedRestoreResult.Restored -> {
                val snapshot = restored.cachedSnapshot
                if (snapshot == null) {
                    PageSpeedRestoreUi.SavedWithoutSnapshot(restored.siteUrl.toASCIIString())
                } else {
                    PageSpeedRestoreUi.Available(
                        snapshot.toUi(
                            cacheState = if (restored.cacheIsStale) {
                                PageSpeedCacheState.CACHED_STALE
                            } else {
                                PageSpeedCacheState.CACHED_FRESH
                            },
                        ),
                    )
                }
            }
            is PageSpeedRestoreResult.Unavailable -> PageSpeedRestoreUi.SavedUnavailable(
                message = when (restored.problem) {
                    PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE ->
                        "The saved PageSpeed connection could not be opened. It was not deleted or replaced."
                    PageSpeedRestoreProblem.SECURE_STORAGE_UNAVAILABLE ->
                        "Secure storage is unavailable. Unlock the device and try again."
                },
            )
        }
    }

    override suspend fun connect(
        apiKey: SecretValue,
        siteUrl: String,
    ): Result<PageSpeedDashboardUi> = capture {
        val credentials = apiKey.use { rawKey ->
            try {
                PageSpeedCredentials.create(rawKey, siteUrl)
            } catch (_: IllegalArgumentException) {
                throw PageSpeedUiException("Enter a valid Google API key and complete HTTPS site URL.")
            }
        }
        val result = api.newSnapshotCall(credentials).executeAwait(executor)
        val snapshot = result.snapshotOrThrow()
        val commit = persistValidatedConnectionAwait(executor, connectionStore, credentials, result)
        connectionStore.acceptValidatedConnection(commit)
        snapshot.toUi(PageSpeedCacheState.LIVE)
    }

    override suspend fun refresh(): Result<PageSpeedDashboardUi> = capture {
        val saved = executeAwait(executor, connectionStore::loadForRefresh)
            ?: throw PageSpeedUiException("Connect PageSpeed & CrUX first.")
        val result = api.newSnapshotCall(saved.credentials).executeAwait(executor)
        val snapshot = result.snapshotOrThrow()
        executeAwait(executor) {
            check(connectionStore.persistRefreshResult(result)) {
                "The saved PageSpeed connection disappeared during refresh."
            }
        }
        snapshot.toUi(PageSpeedCacheState.LIVE)
    }

    override suspend fun disconnect(): Result<Unit> = capture {
        try {
            executeAwait(executor, connectionStore::disconnect)
        } catch (_: Exception) {
            throw PageSpeedUiException("The saved PageSpeed connection could not be removed.")
        }
    }

    private fun PageSpeedFetchResult.snapshotOrThrow(): PageSpeedSnapshot = when (this) {
        is PageSpeedFetchResult.Complete -> snapshot
        is PageSpeedFetchResult.Partial -> snapshot
        is PageSpeedFetchResult.Failure -> throw PageSpeedUiException(failure.message)
    }

    private fun PageSpeedSnapshot.toUi(cacheState: PageSpeedCacheState): PageSpeedDashboardUi =
        PageSpeedDashboardUi(
            siteUrl = siteUrl.toASCIIString(),
            siteName = siteName,
            status = status,
            metrics = metrics.map { metric ->
                PageSpeedMetricUi(
                    key = metric.key,
                    label = metric.label,
                    value = metric.value,
                    unit = metric.unit,
                    formattedValue = metric.formattedValue,
                )
            },
            fetchedAtMillis = fetchedAtMillis,
            sources = PageSpeedSourcesUi(
                mobile = availability.mobile.toUi(),
                desktop = availability.desktop.toUi(),
                crux = availability.crux.toUi(),
            ),
            warnings = warnings,
            cacheState = cacheState,
        )

    private fun PageSpeedSourceState.toUi(): PageSpeedSourceUiState =
        if (this == PageSpeedSourceState.AVAILABLE) {
            PageSpeedSourceUiState.AVAILABLE
        } else {
            PageSpeedSourceUiState.UNAVAILABLE
        }

    companion object {
        fun create(context: Context): NativePageSpeedUiGateway = NativePageSpeedUiGateway(
            connectionStore = PageSpeedConnectionStore(
                PageSpeedConnectionRepository.create(context.applicationContext),
            ),
            api = PageSpeedApi(),
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "verceltics-pagespeed").apply { isDaemon = true }
            },
        )
    }
}

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

/**
 * A secure file commit may already be inside an uninterruptible atomic rename when cancellation
 * arrives. Queue a revision-matched compensation behind that write so a cancelled connect cannot
 * leave credentials saved, without risking deletion of a newer connect or refresh.
 */
private suspend fun persistValidatedConnectionAwait(
    executor: ExecutorService,
    connectionStore: PageSpeedConnectionStore,
    credentials: PageSpeedCredentials,
    result: PageSpeedFetchResult,
): PageSpeedConnectionCommit = suspendCancellableCoroutine { continuation ->
    val committed = AtomicReference<PageSpeedConnectionCommit?>()
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
            val commit = connectionStore.saveValidatedConnection(credentials, result)
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
} catch (error: PageSpeedUiException) {
    Result.failure(error)
} catch (_: SecurityException) {
    Result.failure(PageSpeedUiException("Secure storage is unavailable."))
} catch (_: Exception) {
    Result.failure(PageSpeedUiException("PageSpeed & CrUX could not complete this request."))
}
