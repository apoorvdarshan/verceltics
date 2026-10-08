package com.apoorvdarshan.verceltics.ui.vercel

/**
 * Process-local stale-while-revalidate windows, matching iOS `DashboardRefreshPolicy` and the
 * deployment-events cache. Cached values stay visible while an eligible refresh runs; a manual
 * refresh always bypasses them.
 */
object VercelRefreshPolicy {
    /** Projects, project details, domains and deployments (iOS `inventoryFreshness`). */
    const val INVENTORY_FRESHNESS_MILLIS: Long = 15 * 60 * 1_000L

    /** Web Analytics reports (iOS `reportFreshness`). */
    const val REPORT_FRESHNESS_MILLIS: Long = 10 * 60 * 1_000L

    /** Build events change quickly while a deployment is building. */
    const val EVENTS_FRESHNESS_MILLIS: Long = 2 * 60 * 1_000L

    const val CACHE_ENTRY_LIMIT: Int = 32

    fun isFresh(updatedAtMillis: Long?, nowMillis: Long, lifetimeMillis: Long): Boolean =
        updatedAtMillis != null && nowMillis - updatedAtMillis in 0 until lifetimeMillis
}

/**
 * A bounded, least-recently-used memory cache whose entries remember when they were stored.
 * Not thread-safe: the owning ViewModel only touches it from the main thread.
 */
class VercelTimedCache<K : Any, V : Any>(
    private val lifetimeMillis: Long,
    private val limit: Int = VercelRefreshPolicy.CACHE_ENTRY_LIMIT,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class Entry<V>(val value: V, val updatedAtMillis: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean = size > limit
    }

    init {
        require(limit > 0) { "A cache needs at least one entry." }
        require(lifetimeMillis > 0) { "A cache lifetime must be positive." }
    }

    val size: Int
        get() = entries.size

    operator fun get(key: K): Entry<V>? = entries[key]

    fun isFresh(entry: Entry<V>): Boolean =
        VercelRefreshPolicy.isFresh(entry.updatedAtMillis, nowMillis(), lifetimeMillis)

    fun put(key: K, value: V, updatedAtMillis: Long = nowMillis()): Entry<V> =
        Entry(value, updatedAtMillis).also { entries[key] = it }

    fun clear() = entries.clear()
}
