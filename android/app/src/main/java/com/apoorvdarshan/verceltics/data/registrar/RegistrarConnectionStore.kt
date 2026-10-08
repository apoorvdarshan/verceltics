package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.util.Locale

/** Opaque handle for accepting or compensating one validated encrypted registrar replacement. */
class RegistrarConnectionCommit internal constructor(
    val provider: RegistrarProvider,
    /** The complete live portfolio that was validated (the stored cache may be bounded). */
    val snapshot: RegistrarSnapshot,
    internal val recordCommit: RegistrarRecordCommit,
) {
    override fun toString(): String =
        "RegistrarConnectionCommit(provider=${provider.id}, recordCommit=<redacted>)"
}

/**
 * Port of iOS `RegistrarStore.matchingAccountIndex`: a reconnect keeps the original account
 * identity (creation time) when the primary credential is unchanged, or when a rotated credential
 * belongs to the same stable username (Name.com, Namecheap) or organization (Gandi).
 */
internal object RegistrarAccountMatching {
    fun matches(
        account: RegistrarAccount,
        provider: RegistrarProvider,
        primaryCredential: SecretValue,
        metadata: Map<String, String>,
    ): Boolean {
        if (account.provider != provider) return false
        if (account.credentials.primary == primaryCredential) return true
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

/** Coordinates encrypted per-registrar persistence while keeping restore strictly offline. */
class RegistrarConnectionStore(
    private val repository: RegistrarConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restoreAll(): Map<RegistrarProvider, RegistrarRestoreResult> =
        RegistrarProvider.entries.associateWith(::restore)

    fun restore(provider: RegistrarProvider): RegistrarRestoreResult = try {
        val connection = repository.load(provider)
        if (connection == null) {
            RegistrarRestoreResult.NotConnected
        } else {
            val snapshot = connection.cachedSnapshot
            RegistrarRestoreResult.Restored(
                provider = provider,
                accountName = connection.account.displayName,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null ||
                    nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
            )
        }
    } catch (_: SecurityException) {
        RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        RegistrarRestoreResult.Unavailable(RegistrarRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Internal backend access for refresh. UI-facing restore never receives credentials. */
    internal fun loadForRefresh(provider: RegistrarProvider): RegistrarStoredConnection? =
        repository.load(provider)

    /**
     * Saves a validated connection as a pending encrypted replacement. An unreadable existing
     * record is never overwritten: the load failure propagates and the connection fails.
     */
    fun saveValidatedConnection(
        credentials: RegistrarCredentials,
        validation: RegistrarValidation,
    ): RegistrarConnectionCommit {
        val provider = credentials.provider
        val now = nowMillis()
        val existing = repository.load(provider)
        val sameAccount = existing?.takeIf {
            RegistrarAccountMatching.matches(it.account, provider, credentials.primary, credentials.metadata)
        }
        val accountName = validation.accountName.trim().take(RegistrarAccount.MAX_ACCOUNT_NAME_CHARACTERS)
            .ifEmpty { provider.displayName }
        val account = RegistrarAccount(
            displayName = accountName,
            credentials = credentials,
            createdAtMillis = sameAccount?.account?.createdAtMillis?.coerceAtMost(now) ?: now,
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
        val connection = RegistrarStoredConnection(account, live.forOfflineCache(account))
        return RegistrarConnectionCommit(
            provider = provider,
            snapshot = live,
            recordCommit = repository.saveWithRevision(connection),
        )
    }

    /** Accepts a completed connection flow and releases its encrypted rollback copy. */
    fun acceptValidatedConnection(commit: RegistrarConnectionCommit) =
        repository.accept(commit.recordCommit)

    /**
     * Compensates cancellation only while this commit's exact encrypted revision is current. A
     * newer connection, refresh, or disconnect is never overwritten by a late rollback.
     */
    fun rollbackValidatedConnection(commit: RegistrarConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /**
     * Builds the live snapshot for a successful refresh and caches it when the slot still holds the
     * same credentials and revision. A failed refresh never reaches this method, so saved
     * credentials and the last cached portfolio are never destroyed by network errors.
     */
    fun persistRefreshResult(
        usedCredentials: RegistrarCredentials,
        domains: List<RegistrarDomain>,
    ): RegistrarRefreshOutcome {
        val provider = usedCredentials.provider
        val versioned = repository.loadWithRevision(provider)
            ?: return RegistrarRefreshOutcome.Disconnected
        val existing = versioned.connection
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
        val saved = repository.saveIfRevisionMatches(
            expectedRevision = versioned.revision,
            connection = RegistrarStoredConnection(account, live.forOfflineCache(account)),
        )
        return RegistrarRefreshOutcome.Refreshed(live, cached = saved)
    }

    fun disconnect(provider: RegistrarProvider) = repository.delete(provider)

    /**
     * Bounds the offline cache: at most [RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS]
     * domains within the encrypted payload budget, with per-field limits. Any bounding marks the
     * cached snapshot incomplete with an explicit warning.
     */
    private fun RegistrarSnapshot.forOfflineCache(account: RegistrarAccount): RegistrarSnapshot {
        var modified = false
        var budget = cacheBudgetBytes(account)
        val cached = ArrayList<RegistrarDomain>(minOf(domains.size, RegistrarConnectionPayloadCodec.MAX_CACHED_DOMAINS))
        for (domain in domains) {
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
            val size = RegistrarConnectionPayloadCodec.encodedDomainSize(bounded)
            if (size > budget) {
                modified = true
                break
            }
            budget -= size
            cached += bounded
        }
        if (!modified) return copy(domains = cached)
        return copy(
            domains = cached,
            domainsComplete = false,
            warnings = (warnings + OFFLINE_CACHE_WARNING).distinct(),
        )
    }

    private fun cacheBudgetBytes(account: RegistrarAccount): Int {
        val overhead = RegistrarConnectionPayloadCodec.encode(RegistrarStoredConnection(account, null))
        return try {
            RegistrarConnectionPayloadCodec.MAX_PLAINTEXT_BYTES - overhead.size - SNAPSHOT_HEADER_RESERVE_BYTES
        } finally {
            overhead.fill(0)
        }
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
        private const val SNAPSHOT_HEADER_RESERVE_BYTES = 16 * 1024
    }
}

sealed interface RegistrarRefreshOutcome {
    /** The registrar was disconnected while the refresh was in flight. */
    data object Disconnected : RegistrarRefreshOutcome

    /** A different connection replaced the slot while the refresh was in flight. */
    data object Replaced : RegistrarRefreshOutcome

    data class Refreshed(val snapshot: RegistrarSnapshot, val cached: Boolean) : RegistrarRefreshOutcome
}
