package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.sites.SiteAccountCommit
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex

/** Offline restore, race-safe persistence, and account management for Search Console accounts. */
class SearchConsoleConnectionStore(
    private val repository: SearchConsoleConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Saved accounts (migrating the legacy record first). Throws when unreadable. */
    fun accounts(): SiteAccountIndex = repository.index()

    /** The active account for a request, with its revision for compare-and-swap writes. */
    internal fun loadForRefresh(): SearchConsoleVersionedRecord? {
        val activeId = repository.index().activeId ?: return null
        return repository.loadWithRevision(activeId)
    }

    fun restore(): SearchConsoleRestoreResult {
        val index = try {
            repository.index()
        } catch (_: SecurityException) {
            return SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            return SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SAVED_RECORD_UNREADABLE)
        }
        val active = index.active ?: return SearchConsoleRestoreResult.NotConnected
        return try {
            val record = repository.load(active.id)
                ?: return SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SAVED_RECORD_UNREADABLE, index)
            val now = nowMillis()
            SearchConsoleRestoreResult.Restored(
                accountId = record.id,
                subject = record.subject,
                email = record.email,
                cachedSnapshot = record.cachedSnapshot,
                cacheIsStale = record.cachedSnapshot?.let { now - it.fetchedAtMillis > CACHE_STALE_AFTER_MILLIS } ?: false,
                accounts = index,
            )
        } catch (_: SecurityException) {
            SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SECURE_STORAGE_UNAVAILABLE, index)
        } catch (_: Exception) {
            SearchConsoleRestoreResult.Unavailable(SearchConsoleRestoreProblem.SAVED_RECORD_UNREADABLE, index)
        }
    }

    /** The saved account for the same Google identity (rotated in place on reconnect), if any. */
    fun matchingAccountId(subject: String?, email: String?): String? {
        val index = repository.index()
        val records = index.ids.mapNotNull { id -> runCatching { repository.load(id) }.getOrNull() }
        if (subject != null) return records.firstOrNull { it.subject == subject }?.id
        if (email != null) return records.firstOrNull { it.subject == null && it.email.equals(email, ignoreCase = true) }?.id
        return null
    }

    /**
     * Saves a validated connection for [accountId] and makes it active. Reconnecting an existing
     * account keeps its creation time and merges its offline cache. A corrupt or unavailable prior
     * record is never treated as absent and overwritten.
     */
    internal fun saveValidatedConnection(
        accountId: String,
        subject: String?,
        email: String?,
        result: SearchConsoleFetchResult<SearchConsoleSnapshot>,
    ): SiteAccountCommit {
        require(SiteAccountIds.isValid(accountId)) { "Invalid Search Console account id." }
        val liveSnapshot = when (result) {
            is SearchConsoleFetchResult.Complete -> result.value
            is SearchConsoleFetchResult.Partial -> result.value
            is SearchConsoleFetchResult.Failure -> throw IllegalArgumentException(
                "A failed Search Console validation cannot be saved.",
            )
        }
        return repository.transaction {
            val index = repository.index()
            val current = if (accountId in index) repository.load(accountId) else null
            if (current == null && index.accounts.size >= SiteAccountIndex.MAX_ACCOUNTS) {
                throw IllegalStateException("Too many saved Search Console accounts.")
            }
            val boundedLive = boundedSnapshot(liveSnapshot)
            val cache = current?.cachedSnapshot?.let { mergeCache(it, boundedLive) } ?: boundedLive
            val now = nowMillis()
            val record = SearchConsoleAccountRecord(
                id = accountId,
                subject = subject,
                email = email,
                createdAtMillis = current?.createdAtMillis ?: now,
                updatedAtMillis = now,
                cachedSnapshot = cache,
            )
            repository.commit(record, index.upserting(record.entry, activate = true))
        }
    }

    internal fun acceptValidatedConnection(commit: SiteAccountCommit) = repository.accept(commit)

    internal fun rollbackValidatedConnection(commit: SiteAccountCommit): Boolean = repository.rollbackIfCurrent(commit)

    /** CAS-update for an inventory refresh. Failures never erase a usable offline cache. */
    internal fun persistSnapshotRefresh(
        expected: SearchConsoleVersionedRecord,
        result: SearchConsoleFetchResult<SearchConsoleSnapshot>,
    ): Boolean {
        val snapshot = when (result) {
            is SearchConsoleFetchResult.Complete -> boundedSnapshot(result.value)
            is SearchConsoleFetchResult.Partial -> boundedSnapshot(result.value)
            is SearchConsoleFetchResult.Failure -> return false
        }
        val existing = expected.record.cachedSnapshot
        if (existing?.propertiesComplete == true && !snapshot.propertiesComplete) return false
        val cache = existing?.let { mergeCache(it, snapshot) } ?: snapshot
        val now = nowMillis()
        return repository.saveIfRevisionMatches(
            expected.revision,
            expected.record.copy(
                updatedAtMillis = maxOf(now, expected.record.createdAtMillis),
                cachedSnapshot = cache,
            ),
        )
    }

    /** Makes [accountId] active; null when it is no longer saved. */
    fun switchAccount(accountId: String): SiteAccountIndex? = repository.transaction {
        val index = repository.index()
        if (accountId !in index) return@transaction null
        index.activating(accountId).also(repository::writeIndex)
    }

    /** Removes one account (index first, then its record) and returns the remaining accounts. */
    fun removeAccount(accountId: String): SiteAccountIndex = repository.transaction {
        val index = repository.index()
        val remaining = index.removing(accountId)
        if (remaining != index) repository.writeIndex(remaining)
        repository.delete(accountId)
        remaining
    }

    /** Removes every saved account and returns their ids (so their OAuth slots can be cleared). */
    fun removeAllAccounts(): List<String> = repository.transaction {
        val ids = repository.index().ids
        repository.writeIndex(SiteAccountIndex.EMPTY)
        ids.forEach(repository::delete)
        ids
    }

    /**
     * Removes the active account (the single-account "Disconnect") and returns its id, so its OAuth
     * slot can be cleared. An undecodable account list is discarded instead; a locked keystore is
     * never treated as corruption.
     */
    fun disconnect(): String? = repository.transaction {
        val index = try {
            repository.index()
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            repository.resetUnreadable()
            return@transaction null
        }
        index.activeId?.also(::removeAccount)
    }

    private fun boundedSnapshot(snapshot: SearchConsoleSnapshot): SearchConsoleSnapshot {
        val properties = snapshot.properties
            .filter { property ->
                property.siteUrl.toByteArray(Charsets.UTF_8).size <=
                    MAX_SEARCH_CONSOLE_STORED_STRING_BYTES
            }
            .take(MAX_CACHED_PROPERTIES)
        val modified = properties.size != snapshot.properties.size
        if (!modified) return snapshot.copy(properties = properties)
        val warning =
            "The offline Search Console property cache is intentionally bounded; refresh online for the complete list."
        return SearchConsoleSnapshot(
            properties = properties,
            fetchedAtMillis = snapshot.fetchedAtMillis,
            propertiesComplete = false,
            warnings = (snapshot.warnings.take(MAX_WARNINGS - 1) + warning).distinct(),
        )
    }

    private fun mergeCache(
        existing: SearchConsoleSnapshot,
        candidate: SearchConsoleSnapshot,
    ): SearchConsoleSnapshot {
        if (candidate.propertiesComplete) return candidate
        if (existing.propertiesComplete) return existing

        val propertiesByUrl = linkedMapOf<String, SearchConsoleProperty>()
        existing.properties.forEach { propertiesByUrl[it.siteUrl] = it }
        // New observations replace stale details without changing the stable cached order.
        candidate.properties.forEach { propertiesByUrl[it.siteUrl] = it }
        val warnings = (candidate.warnings + existing.warnings)
            .distinct()
            .take(MAX_WARNINGS)
        return boundedSnapshot(
            SearchConsoleSnapshot(
                properties = propertiesByUrl.values.toList(),
                fetchedAtMillis = maxOf(existing.fetchedAtMillis, candidate.fetchedAtMillis),
                propertiesComplete = false,
                warnings = warnings,
            ),
        )
    }

    companion object {
        const val MAX_CACHED_PROPERTIES = 25
        const val CACHE_STALE_AFTER_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
