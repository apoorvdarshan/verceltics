package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsDomain
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import java.util.concurrent.ConcurrentHashMap

/** Pushed Worker screens (iOS `NavigationLink` destinations from the Worker detail). */
object CloudflareWorkerRoutes {
    const val OPERATIONS: String = "operations"
    const val VERSION: String = "version"
    const val CONTENT: String = "content"
    const val LIVE_TAIL: String = "liveTail"

    /** iOS `CloudflareWorkerOperationsView`. Args: account id, script name. */
    fun operations(accountId: String, scriptName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.WORKER,
        screen = OPERATIONS,
        title = "Worker operations",
        args = listOf(accountId, scriptName),
    )

    /** iOS `CloudflareWorkerVersionDetailView`. Args: account id, script name, version id, row title. */
    fun version(accountId: String, scriptName: String, versionId: String, label: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.WORKER,
        screen = VERSION,
        title = "Version detail",
        args = listOf(accountId, scriptName, versionId, label),
    )

    /** iOS `CloudflareWorkerContentView`. Args: account id, script name. */
    fun content(accountId: String, scriptName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.WORKER,
        screen = CONTENT,
        title = "Worker content",
        args = listOf(accountId, scriptName),
    )

    /** iOS `CloudflareWorkerLiveTailView`. Args: account id, script name. */
    fun liveTail(accountId: String, scriptName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.WORKER,
        screen = LIVE_TAIL,
        title = "Live Worker logs",
        args = listOf(accountId, scriptName),
    )

    /** Mutations under this API path change the Worker (iOS `cloudflareDataDidChange` filter). */
    fun scriptApiPathPrefix(accountId: String, scriptName: String): String =
        "/" + listOf("accounts", accountId, "workers", "scripts", scriptName)
            .joinToString("/") { CloudflareRestRequest.percentEncodeSegment(it) }
}

/**
 * Process-memory cache shared by the Worker screens (iOS `@ResettableMemoryCache`, 180 s). It holds
 * metadata only — never Worker source or secret values — and is never written to disk.
 */
class CloudflareWorkerMemoryCache(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private class Entry(val value: Any, val storedAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun <T : Any> get(key: String): Pair<T, Boolean>? {
        val entry = entries[key] ?: return null
        @Suppress("UNCHECKED_CAST")
        return (entry.value as T) to (nowMillis() - entry.storedAtMillis < LIFETIME_MILLIS)
    }

    fun put(key: String, value: Any, fresh: Boolean = true) {
        entries[key] = Entry(value, if (fresh) nowMillis() else Long.MIN_VALUE / 2)
        if (entries.size > MAXIMUM_ENTRIES) {
            entries.entries.minByOrNull { it.value.storedAtMillis }?.key?.let(entries::remove)
        }
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    companion object {
        const val LIFETIME_MILLIS: Long = 180_000
        private const val MAXIMUM_ENTRIES = 64
        val shared: CloudflareWorkerMemoryCache = CloudflareWorkerMemoryCache()

        fun workerKey(accountId: String, scriptName: String) = "worker|$accountId|$scriptName"
        fun deploymentsKey(accountId: String, scriptName: String) = "deployments|$accountId|$scriptName"
        fun operationsKey(accountId: String, scriptName: String) = "operations|$accountId|$scriptName"
        fun versionKey(accountId: String, scriptName: String, versionId: String) = "version|$accountId|$scriptName|$versionId"
    }
}
