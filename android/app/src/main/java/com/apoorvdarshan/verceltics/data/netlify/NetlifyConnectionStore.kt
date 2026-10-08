package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultRecord

/** Opaque handle for accepting or compensating one validated encrypted replacement. */
class NetlifyConnectionCommit internal constructor(
    /** The Netlify user id that was validated. */
    val accountId: String,
    internal val recordCommit: NetlifyRecordCommit,
) {
    /** The saved account (storage slot) the connection was written to. */
    val savedAccountId: String get() = recordCommit.accountId

    /** False when the validated user was already saved and its token was rotated in place. */
    val isNewAccount: Boolean get() = recordCommit.vaultCommit.isNewAccount

    override fun toString(): String =
        "NetlifyConnectionCommit(accountId=$accountId, recordCommit=<redacted>)"
}

/** Coordinates encrypted persistence while keeping restore strictly offline. */
class NetlifyConnectionStore(
    private val repository: NetlifyConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Offline restore of the active account. */
    fun restore(): NetlifyRestoreResult = try {
        val versioned = repository.loadWithRevision() ?: return NetlifyRestoreResult.NotConnected
        val connection = versioned.connection
        val snapshot = connection.cachedSnapshot
        NetlifyRestoreResult.Restored(
            profile = connection.account.profile(),
            cachedSnapshot = snapshot,
            cacheIsStale = snapshot == null ||
                nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
            accountId = versioned.accountId,
        )
    } catch (_: SecurityException) {
        NetlifyRestoreResult.Unavailable(NetlifyRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        NetlifyRestoreResult.Unavailable(NetlifyRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Every saved account for the account menu. Offline and token-free. */
    fun accounts(): List<NetlifySavedAccount> {
        val activeId = repository.activeAccountId()
        return repository.records().map { record ->
            NetlifySavedAccount(
                accountId = record.accountId,
                profile = (record as? AccountVaultRecord.Readable)?.entry?.value?.account?.profile(),
                isActive = record.accountId == activeId,
            )
        }
    }

    /** Internal backend access for refresh. UI-facing restore never receives this token. */
    internal fun loadForRefresh(): NetlifyStoredConnection? = repository.load()

    /** The active account with the revision its refresh must compare against. */
    internal fun loadActive(): NetlifyVersionedConnection? = repository.loadWithRevision()

    /** Every readable saved account, for the launch profile refresh (iOS `refreshAccountProfiles`). */
    internal fun loadAllAccounts(): List<NetlifyVersionedConnection> = repository.records().mapNotNull { record ->
        (record as? AccountVaultRecord.Readable)?.entry?.let { NetlifyVersionedConnection(it.value, it.revision) }
    }

    fun saveValidatedConnection(
        token: SecretValue,
        result: NetlifyFetchResult,
    ): NetlifyConnectionCommit {
        val snapshot = result.snapshotOrThrow()
        val now = nowMillis()
        // Reconnecting a Netlify user that is already saved rotates its token in place (iOS).
        val sameRecord = repository.records()
            .filterIsInstance<AccountVaultRecord.Readable<NetlifyStoredConnection>>()
            .map { it.entry }
            .firstOrNull { it.value.account.id == snapshot.profile.id }
        val sameAccount = sameRecord?.value
        val candidateCache = snapshot.forOfflineCache()
        val preservedCache = if (result is NetlifyFetchResult.Partial) {
            sameAccount?.cachedSnapshot
                ?.takeIf { it.isFullerThan(candidateCache) }
                ?.copy(profile = snapshot.profile)
        } else {
            null
        }
        val connection = NetlifyStoredConnection(
            account = NetlifyAccount(
                id = snapshot.profile.id,
                displayName = snapshot.profile.displayName,
                email = snapshot.profile.email,
                avatarUrl = snapshot.profile.avatarUrl,
                personalToken = token,
                createdAtMillis = sameAccount?.account?.createdAtMillis ?: now,
                updatedAtMillis = maxOf(now, sameAccount?.account?.createdAtMillis ?: now),
            ),
            cachedSnapshot = preservedCache ?: candidateCache,
        )
        return NetlifyConnectionCommit(
            accountId = connection.account.id,
            recordCommit = repository.saveWithRevision(sameRecord?.accountId, connection),
        )
    }

    /** Accepts a completed connection flow and releases its encrypted rollback copy. */
    fun acceptValidatedConnection(commit: NetlifyConnectionCommit) =
        repository.accept(commit.recordCommit)

    /**
     * Compensates cancellation only while this commit's exact encrypted revision is current. A
     * newer connection, refresh, or disconnect is never overwritten by a late rollback.
     */
    fun rollbackValidatedConnection(commit: NetlifyConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /**
     * A failed refresh never destroys saved credentials or cached inventory. A partial refresh is
     * cached only when no prior inventory exists, so an incomplete page cannot replace a fuller
     * last-known snapshot.
     */
    fun persistRefreshResult(result: NetlifyFetchResult): Boolean {
        val accountId = repository.activeAccountId() ?: return false
        return persistRefreshResult(accountId, result)
    }

    /** [persistRefreshResult] for one saved account, so a refresh can never land in another account. */
    fun persistRefreshResult(savedAccountId: String, result: NetlifyFetchResult): Boolean {
        val versioned = repository.loadWithRevision(savedAccountId) ?: return false
        val existing = versioned.connection
        val snapshot = when (result) {
            is NetlifyFetchResult.Complete -> result.snapshot
            is NetlifyFetchResult.Partial -> {
                if (existing.cachedSnapshot != null) return false
                result.snapshot
            }
            is NetlifyFetchResult.Failure -> return false
        }
        if (snapshot.profile.id != existing.account.id) return false
        return repository.saveIfRevisionMatches(
            expectedRevision = versioned.revision,
            connection = NetlifyStoredConnection(
                account = NetlifyAccount(
                    id = existing.account.id,
                    displayName = snapshot.profile.displayName,
                    email = snapshot.profile.email,
                    avatarUrl = snapshot.profile.avatarUrl,
                    personalToken = existing.account.personalToken,
                    createdAtMillis = existing.account.createdAtMillis,
                    updatedAtMillis = maxOf(nowMillis(), existing.account.createdAtMillis),
                ),
                cachedSnapshot = snapshot.forOfflineCache(),
            ),
        )
    }

    /**
     * iOS `refreshAccountProfiles`: updates a saved account's name, email and avatar when Netlify
     * still reports the same user and the record did not change meanwhile.
     */
    internal fun persistRefreshedProfile(expected: NetlifyVersionedConnection, profile: NetlifyProfile): Boolean {
        val existing = expected.connection
        if (existing.account.id != profile.id || existing.account.profile() == profile) return false
        return repository.saveIfRevisionMatches(
            expectedRevision = expected.revision,
            connection = NetlifyStoredConnection(
                account = NetlifyAccount(
                    id = existing.account.id,
                    displayName = profile.displayName,
                    email = profile.email,
                    avatarUrl = profile.avatarUrl,
                    personalToken = existing.account.personalToken,
                    createdAtMillis = existing.account.createdAtMillis,
                    updatedAtMillis = maxOf(nowMillis(), existing.account.createdAtMillis),
                ),
                cachedSnapshot = existing.cachedSnapshot?.copy(profile = profile),
            ),
        )
    }

    fun switchAccount(savedAccountId: String): Boolean = repository.activate(savedAccountId)

    /** Removes one saved account; returns the new active account id (null when none remain). */
    fun removeAccount(savedAccountId: String): String? = repository.deleteAccount(savedAccountId)

    /** Removes every saved Netlify account (iOS "Remove All Accounts"). */
    fun disconnect() {
        repository.delete()
    }

    /** Removes the active account (resolved here, so it is never "all accounts"). */
    fun removeActiveAccount(): String? = repository.deleteActiveAccount()

    private fun NetlifyFetchResult.snapshotOrThrow(): NetlifySnapshot = when (this) {
        is NetlifyFetchResult.Complete -> snapshot
        is NetlifyFetchResult.Partial -> snapshot
        is NetlifyFetchResult.Failure -> error("A failed Netlify validation cannot be saved.")
    }

    private fun NetlifySnapshot.forOfflineCache(): NetlifySnapshot {
        var modified = sites.size > MAX_CACHED_SITES
        val cachedSites = sites.take(MAX_CACHED_SITES).map { site ->
            val cached = site.copy(
                id = site.id.take(CACHED_ID_CHARACTERS),
                name = site.name.take(CACHED_NAME_CHARACTERS),
                subtitle = site.subtitle?.take(CACHED_SUBTITLE_CHARACTERS),
                url = site.url?.takeIf { it.length <= CACHED_URL_CHARACTERS },
                status = site.status?.take(CACHED_STATUS_CHARACTERS),
                adminUrl = site.adminUrl?.takeIf { it.length <= CACHED_URL_CHARACTERS },
            )
            if (cached != site) modified = true
            cached
        }
        if (!modified) return copy(sites = cachedSites)

        val cacheWarning =
            "The offline Netlify cache is intentionally bounded; refresh online for the complete site data."
        return copy(
            sites = cachedSites,
            sitesComplete = false,
            warnings = (warnings + cacheWarning).distinct(),
        )
    }

    private fun NetlifySnapshot.isFullerThan(candidate: NetlifySnapshot): Boolean =
        sites.size > candidate.sites.size ||
            (sitesComplete && sites.size == candidate.sites.size)

    companion object {
        const val CACHE_LIFETIME_MILLIS: Long = 15 * 60 * 1_000L
        internal const val MAX_CACHED_SITES = 25
        private const val CACHED_ID_CHARACTERS = 256
        private const val CACHED_NAME_CHARACTERS = 256
        private const val CACHED_SUBTITLE_CHARACTERS = 512
        private const val CACHED_URL_CHARACTERS = 2_048
        private const val CACHED_STATUS_CHARACTERS = 128
    }
}
