package com.apoorvdarshan.verceltics.ui.hosting

/**
 * iOS `HostingResourceDetailViewModel.cachedDeployments`: an in-memory deployment-history cache
 * keyed by account and resource. Opening a resource again within [lifetimeMillis] reuses the
 * loaded history without a network request; an older entry is shown immediately while a fresh
 * copy loads. Explicit refreshes and confirmed writes always bypass it.
 *
 * Only redacted UI models are stored, never credentials. Entries are least-recently-used and the
 * cache is bounded by [maximumEntries], so long sessions cannot grow memory without limit.
 */
class ResourceHistoryCache<T : Any>(
    private val nowMillis: () -> Long,
    private val lifetimeMillis: Long = LIFETIME_MILLIS,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    class Entry<T>(val value: T, val storedAtMillis: Long, val isFresh: Boolean)

    private class Stored<T>(val value: T, val storedAtMillis: Long)

    private val entries = object : LinkedHashMap<String, Stored<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stored<T>>?): Boolean =
            size > maximumEntries
    }

    init {
        require(lifetimeMillis > 0) { "The history cache lifetime must be positive." }
        require(maximumEntries > 0) { "The history cache needs at least one entry." }
    }

    @Synchronized
    fun get(key: String): Entry<T>? {
        val stored = entries[key] ?: return null
        val age = nowMillis() - stored.storedAtMillis
        return Entry(stored.value, stored.storedAtMillis, isFresh = age in 0 until lifetimeMillis)
    }

    @Synchronized
    fun put(key: String, value: T) {
        entries[key] = Stored(value, nowMillis())
    }

    @Synchronized
    fun invalidate(key: String) {
        entries.remove(key)
    }

    /** Drops every entry whose key starts with [prefix] (for example one provider's account). */
    @Synchronized
    fun invalidateScope(prefix: String) {
        entries.keys.removeAll { it.startsWith(prefix) }
    }

    @Synchronized
    fun clear() = entries.clear()

    @get:Synchronized
    val size: Int
        get() = entries.size

    companion object {
        /** iOS `cacheLifetime: TimeInterval = 180`. */
        const val LIFETIME_MILLIS: Long = 180 * 1_000L
        const val DEFAULT_MAXIMUM_ENTRIES: Int = 32

        /** Cache key scoped to provider, account and resource, like iOS `"\(cacheScope)|\(id)"`. */
        fun key(providerId: String, accountId: String, resourceId: String): String =
            "${scope(providerId, accountId)}$resourceId"

        fun scope(providerId: String, accountId: String? = null): String =
            if (accountId == null) "$providerId|" else "$providerId|$accountId|"
    }
}
