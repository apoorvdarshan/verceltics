package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.util.Locale
import java.util.UUID

/** Opaque handle for accepting or compensating one validated encrypted registrar replacement. */
class RegistrarConnectionCommit internal constructor(
    val provider: RegistrarProvider,
    /** The account that was added or rotated in place; it is now the active account. */
    val accountId: String,
    /** Every saved account of [provider] after this connection, in saved order. */
    val accounts: List<RegistrarAccountSummary>,
    /** The complete live portfolio that was validated (the stored cache may be bounded). */
    val snapshot: RegistrarSnapshot,
    internal val recordCommit: RegistrarRecordCommit,
) {
    override fun toString(): String =
        "RegistrarConnectionCommit(provider=${provider.id}, accountId=$accountId, recordCommit=<redacted>)"
}

/**
 * Port of iOS `RegistrarStore.matchingAccountIndex`: a reconnect updates an existing account in
 * place when its primary credential is unchanged, or when a rotated credential belongs to the same
 * stable username (Name.com, Namecheap) or organization (Gandi).
 */
internal object RegistrarAccountMatching {
    fun matches(
        account: RegistrarAccount,
        provider: RegistrarProvider,
        primaryCredential: SecretValue,
        metadata: Map<String, String>,
    ): Boolean = matchesPrimary(account, provider, primaryCredential) ||
        matchesStableIdentity(account, provider, metadata)

    /** Exact primary-credential matches win over stable-identity matches, like iOS. */
    fun matchingIndex(
        accounts: List<RegistrarAccount>,
        provider: RegistrarProvider,
        primaryCredential: SecretValue,
        metadata: Map<String, String>,
    ): Int? {
        accounts.indexOfFirst { matchesPrimary(it, provider, primaryCredential) }.takeIf { it >= 0 }?.let { return it }
        return accounts.indexOfFirst { matchesStableIdentity(it, provider, metadata) }.takeIf { it >= 0 }
    }

    private fun matchesPrimary(account: RegistrarAccount, provider: RegistrarProvider, primary: SecretValue): Boolean =
        account.provider == provider && account.credentials.primary == primary

    private fun matchesStableIdentity(
        account: RegistrarAccount,
        provider: RegistrarProvider,
        metadata: Map<String, String>,
    ): Boolean {
        if (account.provider != provider) return false
        val stableKey = when (provider) {
            RegistrarProvider.NAME_DOT_COM, RegistrarProvider.NAMECHEAP -> RegistrarMetadataKeys.USERNAME
            RegistrarProvider.GANDI -> RegistrarMetadataKeys.ORGANIZATION
            else -> return false
        }
        val stableValue = normalizedIdentity(metadata[stableKey])?.takeIf(String::isNotEmpty) ?: return false
        return normalizedIdentity(account.credentials.metadata[stableKey]) == stableValue
    }

    private fun normalizedIdentity(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)
}

/**
 * Coordinates encrypted per-registrar persistence of every saved account while keeping restore
 * strictly offline. Each account keeps its own offline cache inside the registrar's record.
 */
class RegistrarConnectionStore(
    private val repository: RegistrarConnectionRepository,
    private val newAccountId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restoreAll(): Map<RegistrarProvider, RegistrarRestoreResult> =
        RegistrarProvider.entries.associateWith(::restore)

    fun restore(provider: RegistrarProvider): RegistrarRestoreResult = try {
        repository.load(provider)?.let(::restoreResult) ?: RegistrarRestoreResult.NotConnected
    } catch (_: SecurityException) {
        RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Internal backend access for refresh and raw API calls: the active account, never the UI. */
    internal fun loadForRefresh(provider: RegistrarProvider): RegistrarSavedAccount? =
        repository.load(provider)?.active

    /**
     * Saves a validated connection as a pending encrypted replacement and makes it the active
     * account. A reconnect of an already-saved identity rotates that account's credentials in place
     * (keeping its id and creation time); anything else is added as a new account, so connecting
     * never disconnects another account. An unreadable existing record is never overwritten: the
     * load failure propagates and the connection fails.
     */
    fun saveValidatedConnection(
        credentials: RegistrarCredentials,
        validation: RegistrarValidation,
    ): RegistrarConnectionCommit {
        val provider = credentials.provider
        val now = nowMillis()
        val existing = repository.load(provider)
        val saved = existing?.accounts.orEmpty()
        val matchIndex = RegistrarAccountMatching.matchingIndex(
            accounts = saved.map { it.connection.account },
            provider = provider,
            primaryCredential = credentials.primary,
            metadata = credentials.metadata,
        )
        val matched = matchIndex?.let(saved::get)
        if (matched == null && saved.size >= RegistrarAccountSet.MAX_ACCOUNTS) {
            throw RegistrarStoreException(
                "You can save up to ${RegistrarAccountSet.MAX_ACCOUNTS} ${provider.displayName} accounts on this " +
                    "device. Remove one before adding another.",
            )
        }
        val accountName = validation.accountName.trim().take(RegistrarAccount.MAX_ACCOUNT_NAME_CHARACTERS)
            .ifEmpty { provider.displayName }
        val account = RegistrarAccount(
            displayName = accountName,
            credentials = credentials,
            createdAtMillis = matched?.connection?.account?.createdAtMillis?.coerceAtMost(now) ?: now,
            updatedAtMillis = now,
        )
        val live = RegistrarSnapshot(
            provider = provider,
            accountName = accountName,
            domains = validation.domains,
            fetchedAtMillis = now,
            domainsComplete = true,
            warnings = emptyList(),
        )
        val accountId = matched?.id ?: uniqueAccountId(saved)
        val replacement = RegistrarSavedAccount(accountId, RegistrarStoredConnection(account, live))
        val updated = if (matchIndex != null) {
            saved.toMutableList().also { it[matchIndex] = replacement }
        } else {
            saved + replacement
        }
        val accounts = boundedForStorage(RegistrarAccountSet(provider, updated, accountId))
        return RegistrarConnectionCommit(
            provider = provider,
            accountId = accountId,
            accounts = accounts.summaries,
            snapshot = live,
            recordCommit = repository.saveWithRevision(accounts),
        )
    }

    /** Accepts a completed connection flow and releases its encrypted rollback copy. */
    fun acceptValidatedConnection(commit: RegistrarConnectionCommit) =
        repository.accept(commit.recordCommit)

    /**
     * Compensates cancellation only while this commit's exact encrypted revision is current, so the
     * previous accounts (and active account) come back. A newer connection, refresh, switch or
     * removal is never overwritten by a late rollback.
     */
    fun rollbackValidatedConnection(commit: RegistrarConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /**
     * Builds the live snapshot for a successful refresh of [accountId] and caches it for that
     * account only, while it still holds the same credentials and the record revision is unchanged.
     * A failed refresh never reaches this method, so saved credentials and the last cached portfolio
     * are never destroyed by network errors.
     */
    fun persistRefreshResult(
        accountId: String,
        usedCredentials: RegistrarCredentials,
        domains: List<RegistrarDomain>,
    ): RegistrarRefreshOutcome {
        val provider = usedCredentials.provider
        val versioned = repository.loadWithRevision(provider)
            ?: return RegistrarRefreshOutcome.Disconnected
        val existing = versioned.accounts.account(accountId)?.connection
            ?: return RegistrarRefreshOutcome.Disconnected
        if (!existing.account.credentials.sameAs(usedCredentials)) {
            return RegistrarRefreshOutcome.Replaced
        }
        val now = maxOf(nowMillis(), existing.account.createdAtMillis)
        val account = RegistrarAccount(
            displayName = existing.account.displayName,
            credentials = existing.account.credentials,
            createdAtMillis = existing.account.createdAtMillis,
            updatedAtMillis = maxOf(now, existing.account.updatedAtMillis),
        )
        val live = RegistrarSnapshot(
            provider = provider,
            accountName = account.displayName,
            domains = domains,
            fetchedAtMillis = now,
            domainsComplete = true,
            warnings = emptyList(),
        )
        val updated = boundedForStorage(
            versioned.accounts.replacing(RegistrarSavedAccount(accountId, RegistrarStoredConnection(account, live))),
        )
        val saved = repository.saveIfRevisionMatches(expectedRevision = versioned.revision, accounts = updated)
        return RegistrarRefreshOutcome.Refreshed(
            snapshot = live,
            cached = saved,
            accountId = accountId,
            accounts = versioned.accounts.summaries,
        )
    }

    /** iOS `switchAccount(to:)`: makes another saved account active; it keeps its own cache. */
    fun switchAccount(provider: RegistrarProvider, accountId: String): RegistrarRestoreResult {
        val versioned = repository.loadWithRevision(provider) ?: throw missingAccount(provider)
        val accounts = versioned.accounts
        if (accounts.account(accountId) == null) throw missingAccount(provider)
        if (accounts.activeAccountId == accountId) return restoreResult(accounts)
        val updated = accounts.withActive(accountId)
        if (!repository.saveIfRevisionMatches(versioned.revision, updated)) throw changedAccounts(provider)
        return restoreResult(updated)
    }

    /**
     * iOS `removeAccount(id:)`: removes one account from this device. Removing the active account
     * activates the first remaining one; removing the last account empties the registrar slot.
     */
    fun removeAccount(provider: RegistrarProvider, accountId: String): RegistrarRestoreResult {
        val versioned = repository.loadWithRevision(provider) ?: return RegistrarRestoreResult.NotConnected
        val accounts = versioned.accounts
        val remaining = accounts.accounts.filter { it.id != accountId }
        if (remaining.size == accounts.accounts.size) return restoreResult(accounts)
        if (remaining.isEmpty()) {
            repository.delete(provider)
            return RegistrarRestoreResult.NotConnected
        }
        val activeId = if (accounts.activeAccountId == accountId) remaining.first().id else accounts.activeAccountId
        val updated = RegistrarAccountSet(provider, remaining, activeId)
        if (!repository.saveIfRevisionMatches(versioned.revision, updated)) throw changedAccounts(provider)
        return restoreResult(updated)
    }

    /** iOS `removeAll()` for one registrar: erases every saved account and its cache. */
    fun disconnect(provider: RegistrarProvider) = repository.delete(provider)

    private fun restoreResult(accounts: RegistrarAccountSet): RegistrarRestoreResult.Restored {
        val active = accounts.active
        val snapshot = active.connection.cachedSnapshot
        return RegistrarRestoreResult.Restored(
            provider = accounts.provider,
            accountId = active.id,
            accountName = active.connection.account.displayName,
            accounts = accounts.summaries,
            cachedSnapshot = snapshot,
            cacheIsStale = snapshot == null || nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
        )
    }

    private fun uniqueAccountId(existing: List<RegistrarSavedAccount>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = newAccountId()
            if (candidate !in taken && RegistrarAccountSet.isValidAccountId(candidate)) return candidate
        }
        throw IllegalStateException("Could not allocate a registrar account id.")
    }

    private fun missingAccount(provider: RegistrarProvider) =
        RegistrarStoreException("That ${provider.displayName} account is no longer saved on this device.")

    private fun changedAccounts(provider: RegistrarProvider) =
        RegistrarStoreException("The saved ${provider.displayName} accounts changed. Try again.")

    /**
     * Bounds every account's offline cache so the whole encrypted record stays within its payload
     * budget. Accounts share the budget fairly: an account that needs less than an equal share
     * leaves the rest to the others. Each cache keeps at most
     * [RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS] domains with per-field limits; any
     * bounding marks that cached snapshot incomplete with an explicit warning. Caches that already
     * fit are returned unchanged.
     */
    private fun boundedForStorage(accounts: RegistrarAccountSet): RegistrarAccountSet {
        val prepared = accounts.accounts.map { saved -> saved.connection.cachedSnapshot?.let(::prepareCache) }
        val withoutCaches = accounts.mapAccounts { saved ->
            saved.withConnection(RegistrarStoredConnection(saved.connection.account, null))
        }
        val overhead = RegistrarConnectionPayloadCodec.encode(withoutCaches)
        val headers = prepared.sumOf { cache ->
            cache?.snapshot?.let { snapshot ->
                RegistrarConnectionPayloadCodec.encodedSnapshotHeaderSize(
                    snapshot.accountName,
                    (snapshot.warnings + OFFLINE_CACHE_WARNING).distinct(),
                )
            } ?: 0
        }
        val available = try {
            RegistrarConnectionPayloadCodec.MAX_PLAINTEXT_BYTES - overhead.size - headers - PAYLOAD_SAFETY_MARGIN_BYTES
        } finally {
            overhead.fill(0)
        }
        val budgets = shareCacheBudget(prepared.map { it?.totalSize ?: 0 }, available)
        return RegistrarAccountSet(
            provider = accounts.provider,
            accounts = accounts.accounts.mapIndexed { index, saved ->
                val cache = prepared[index] ?: return@mapIndexed saved
                saved.withConnection(RegistrarStoredConnection(saved.connection.account, cache.boundedTo(budgets[index])))
            },
            activeAccountId = accounts.activeAccountId,
        )
    }

    private class PreparedCache(
        val snapshot: RegistrarSnapshot,
        val domains: List<RegistrarDomain>,
        val sizes: IntArray,
        val modified: Boolean,
    ) {
        val totalSize: Int = sizes.sum()

        fun boundedTo(budget: Int): RegistrarSnapshot {
            var remaining = budget
            var count = 0
            while (count < domains.size && sizes[count] <= remaining) {
                remaining -= sizes[count]
                count += 1
            }
            val kept = domains.subList(0, count).toList()
            if (!modified && count == domains.size) return snapshot.copy(domains = kept)
            return snapshot.copy(
                domains = kept,
                domainsComplete = false,
                warnings = (snapshot.warnings + OFFLINE_CACHE_WARNING).distinct(),
            )
        }
    }

    private fun prepareCache(snapshot: RegistrarSnapshot): PreparedCache {
        var modified = false
        val cached = ArrayList<RegistrarDomain>(minOf(snapshot.domains.size, RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS))
        for (domain in snapshot.domains) {
            if (cached.size >= RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS) {
                modified = true
                break
            }
            if (domain.name.length > MAX_CACHED_NAME_CHARACTERS) {
                modified = true
                continue
            }
            val bounded = domain.boundedForCache()
            if (bounded != domain) modified = true
            cached += bounded
        }
        val sizes = IntArray(cached.size) { RegistrarConnectionPayloadCodec.encodedDomainSize(cached[it]) }
        return PreparedCache(snapshot, cached, sizes, modified)
    }

    private fun RegistrarDomain.boundedForCache(): RegistrarDomain = copy(
        status = status?.take(MAX_CACHED_STATUS_CHARACTERS),
        nameservers = nameservers
            .filter { it.length <= MAX_CACHED_NAME_CHARACTERS }
            .take(RegistrarConnectionPayloadCodec.MAX_NAMESERVERS),
        metadata = metadata.entries
            .filter { it.key.length <= MAX_CACHED_METADATA_KEY_CHARACTERS }
            .take(MAX_CACHED_METADATA_ENTRIES)
            .associate { it.key to it.value.take(MAX_CACHED_METADATA_VALUE_CHARACTERS) },
    )

    companion object {
        /** iOS `DashboardRefreshPolicy.inventoryFreshness` (15 minutes). */
        const val CACHE_LIFETIME_MILLIS: Long = 15 * 60 * 1_000L
        const val OFFLINE_CACHE_WARNING: String =
            "The offline registrar cache is intentionally bounded; refresh online for the complete domain portfolio."
        private const val MAX_CACHED_NAME_CHARACTERS = 253
        private const val MAX_CACHED_STATUS_CHARACTERS = 128
        private const val MAX_CACHED_METADATA_ENTRIES = 4
        private const val MAX_CACHED_METADATA_KEY_CHARACTERS = 64
        private const val MAX_CACHED_METADATA_VALUE_CHARACTERS = 256
        private const val PAYLOAD_SAFETY_MARGIN_BYTES = 4 * 1024
        private const val MAX_ID_ATTEMPTS = 8

        /**
         * Max-min fair split of [total] bytes: accounts needing less than an equal share get what
         * they need and the remainder is shared by the others. The result never exceeds [total].
         */
        internal fun shareCacheBudget(needs: List<Int>, total: Int): List<Int> {
            val budgets = IntArray(needs.size)
            var remaining = total.coerceAtLeast(0)
            var unserved = needs.size
            for (index in needs.indices.sortedBy { needs[it] }) {
                val grant = minOf(needs[index].coerceAtLeast(0), remaining / unserved)
                budgets[index] = grant
                remaining -= grant
                unserved -= 1
            }
            return budgets.toList()
        }
    }
}

sealed interface RegistrarRefreshOutcome {
    /** The account (or the whole registrar) was removed while the refresh was in flight. */
    data object Disconnected : RegistrarRefreshOutcome

    /** The account was reconnected with different credentials while the refresh was in flight. */
    data object Replaced : RegistrarRefreshOutcome

    data class Refreshed(
        val snapshot: RegistrarSnapshot,
        val cached: Boolean,
        val accountId: String,
        val accounts: List<RegistrarAccountSummary>,
    ) : RegistrarRefreshOutcome
}
