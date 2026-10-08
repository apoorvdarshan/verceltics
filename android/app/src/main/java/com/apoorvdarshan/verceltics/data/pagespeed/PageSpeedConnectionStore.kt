package com.apoorvdarshan.verceltics.data.pagespeed

import com.apoorvdarshan.verceltics.data.sites.SiteAccountCommit
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex

/** Opaque rollback handle for one validated encrypted persistence transaction. */
class PageSpeedConnectionCommit internal constructor(
    val connectionId: String,
    internal val accountCommit: SiteAccountCommit,
) {
    override fun toString(): String =
        "PageSpeedConnectionCommit(connectionId=$connectionId, accountCommit=<redacted>)"
}

/**
 * Coordinates persistence of several audited sites without turning restore into a
 * network-dependent operation. Each site is one account; the active one drives the dashboard.
 */
class PageSpeedConnectionStore(
    private val repository: PageSpeedConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Saved sites (migrating the legacy record first). Throws when unreadable. */
    fun accounts(): SiteAccountIndex = repository.index()

    fun restore(): PageSpeedRestoreResult {
        val index = try {
            repository.index()
        } catch (_: SecurityException) {
            return PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            return PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE)
        }
        val active = index.active ?: return PageSpeedRestoreResult.NotConnected
        return try {
            val connection = repository.load(active.id)
                ?: return PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE, index)
            val snapshot = connection.cachedSnapshot
            PageSpeedRestoreResult.Restored(
                connectionId = connection.id,
                siteUrl = connection.credentials.siteUrl,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null || nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
                accounts = index,
            )
        } catch (_: SecurityException) {
            PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SECURE_STORAGE_UNAVAILABLE, index)
        } catch (_: Exception) {
            PageSpeedRestoreResult.Unavailable(PageSpeedRestoreProblem.SAVED_RECORD_UNREADABLE, index)
        }
    }

    /** Internal backend access for an explicit refresh of the active site; restore never gets the key. */
    internal fun loadForRefresh(): PageSpeedVersionedConnection? {
        val activeId = repository.index().activeId ?: return null
        return repository.loadWithRevision(activeId)
    }

    /** The saved account auditing the same normalized URL (its key is rotated in place), if any. */
    fun matchingAccountId(credentials: PageSpeedCredentials): String? =
        repository.index().ids.firstOrNull { id ->
            runCatching { repository.load(id) }.getOrNull()?.credentials?.siteUrl == credentials.siteUrl
        }

    /**
     * Saves a validated site and makes it active. Reconnecting the same URL replaces that site's key
     * in place (keeping its id and creation time); any other URL is added as a new site.
     */
    fun saveValidatedConnection(
        credentials: PageSpeedCredentials,
        result: PageSpeedFetchResult,
    ): PageSpeedConnectionCommit {
        val snapshot = when (result) {
            is PageSpeedFetchResult.Complete -> result.snapshot
            is PageSpeedFetchResult.Partial -> result.snapshot
            is PageSpeedFetchResult.Failure -> error("A failed PageSpeed validation cannot be saved.")
        }
        require(snapshot.siteUrl == credentials.siteUrl) {
            "The PageSpeed validation belongs to a different site."
        }
        return repository.transaction {
            val index = repository.index()
            val existingId = matchingAccountId(credentials)
            if (existingId == null && index.accounts.size >= SiteAccountIndex.MAX_ACCOUNTS) {
                throw IllegalStateException("Too many saved PageSpeed sites.")
            }
            val existing = existingId?.let(repository::load)
            val now = nowMillis()
            val connection = PageSpeedStoredConnection(
                id = existingId ?: SiteAccountIds.newId(),
                credentials = credentials,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = maxOf(now, existing?.createdAtMillis ?: now),
                cachedSnapshot = snapshot,
            )
            PageSpeedConnectionCommit(
                connectionId = connection.id,
                accountCommit = repository.commit(connection, index.upserting(connection.accountEntry, activate = true)),
            )
        }
    }

    /** Accepts a successful replacement and immediately releases its encrypted rollback copy. */
    fun acceptValidatedConnection(commit: PageSpeedConnectionCommit) =
        repository.accept(commit.accountCommit)

    /**
     * Compensates a cancelled connect only while its exact encrypted revision is still current.
     * A later connect or refresh is never removed by this rollback.
     */
    fun rollbackValidatedConnection(commit: PageSpeedConnectionCommit): Boolean =
        repository.rollbackIfCurrent(commit.accountCommit)

    /**
     * Saves a refreshed audit for the site it was fetched from, only while that record is unchanged.
     * A failed refresh never destroys the saved credential or last truthful snapshot.
     */
    fun persistRefreshResult(source: PageSpeedVersionedConnection, result: PageSpeedFetchResult): Boolean {
        val snapshot = when (result) {
            is PageSpeedFetchResult.Complete -> result.snapshot
            is PageSpeedFetchResult.Partial -> result.snapshot
            is PageSpeedFetchResult.Failure -> return false
        }
        val existing = source.connection
        require(existing.credentials.siteUrl == snapshot.siteUrl) {
            "The PageSpeed refresh belongs to a different site."
        }
        return repository.transaction {
            val updated = existing.copy(
                updatedAtMillis = maxOf(nowMillis(), existing.createdAtMillis),
                cachedSnapshot = snapshot,
            )
            val saved = repository.saveIfRevisionMatches(source.revision, updated)
            if (saved) {
                runCatching {
                    val index = repository.index()
                    val entry = updated.accountEntry
                    if (updated.id in index && index.entry(updated.id) != entry) {
                        repository.writeIndex(index.upserting(entry, activate = false))
                    }
                }
            }
            saved
        }
    }

    /** Makes [accountId] active; null when it is no longer saved. */
    fun switchAccount(accountId: String): SiteAccountIndex? = repository.transaction {
        val index = repository.index()
        if (accountId !in index) return@transaction null
        index.activating(accountId).also(repository::writeIndex)
    }

    /** Removes one site (index first, then its record) and returns the remaining sites. */
    fun removeAccount(accountId: String): SiteAccountIndex = repository.transaction {
        val index = repository.index()
        val remaining = index.removing(accountId)
        if (remaining != index) repository.writeIndex(remaining)
        repository.delete(accountId)
        remaining
    }

    fun removeAllAccounts(): List<String> = repository.transaction {
        val ids = repository.index().ids
        repository.writeIndex(SiteAccountIndex.EMPTY)
        ids.forEach(repository::delete)
        ids
    }

    /**
     * Removes the active site (the single-site "Disconnect"). An undecodable site list is discarded
     * instead, as the single-site build did with an unreadable record; a locked keystore is not.
     */
    fun disconnect() {
        repository.transaction {
            val index = try {
                repository.index()
            } catch (error: SecurityException) {
                throw error
            } catch (_: Exception) {
                repository.resetUnreadable()
                return@transaction
            }
            index.activeId?.let(::removeAccount)
        }
    }

    companion object {
        const val CACHE_LIFETIME_MILLIS: Long = 30 * 60 * 1_000L
    }
}
