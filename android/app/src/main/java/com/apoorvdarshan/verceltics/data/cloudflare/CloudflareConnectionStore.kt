package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue

class CloudflareConnectionCommit internal constructor(
    val profileId: String,
    internal val recordCommit: CloudflareRecordCommit,
) {
    override fun toString(): String =
        "CloudflareConnectionCommit(profileId=$profileId, recordCommit=<redacted>)"
}

/** Coordinates encrypted persistence while keeping restore strictly offline and credential-free. */
class CloudflareConnectionStore(
    private val repository: CloudflareConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restore(): CloudflareRestoreResult = try {
        val connection = repository.load() ?: return CloudflareRestoreResult.NotConnected
        val snapshot = connection.cachedSnapshot
        CloudflareRestoreResult.Restored(
            profile = connection.account.profile,
            cachedSnapshot = snapshot,
            cacheIsStale = snapshot == null ||
                nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
        )
    } catch (_: SecurityException) {
        CloudflareRestoreResult.Unavailable(CloudflareRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        CloudflareRestoreResult.Unavailable(CloudflareRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Internal backend access for refresh. UI-facing restore never receives the credential. */
    internal fun loadForRefresh(): CloudflareVersionedConnection? = repository.loadWithRevision()

    fun saveValidatedConnection(
        token: SecretValue,
        result: CloudflareFetchResult,
    ): CloudflareConnectionCommit = saveValidatedConnection(CloudflareCredential.ApiToken(token), result)

    fun saveValidatedConnection(
        credential: CloudflareCredential,
        result: CloudflareFetchResult,
    ): CloudflareConnectionCommit {
        val snapshot = result.snapshotOrThrow()
        require(snapshot.profile.authMode == credential.authMode) {
            "The Cloudflare validation belongs to a different authentication mode."
        }
        val now = nowMillis()
        val existing = repository.load()
        val sameProfile = existing?.takeIf { it.account.profile.id == snapshot.profile.id }
        val candidateCache = snapshot.forOfflineCache()
        val savedCache = if (result is CloudflareFetchResult.Partial) {
            candidateCache.mergePartialCache(sameProfile?.cachedSnapshot).forOfflineCache()
        } else {
            candidateCache
        }
        val connection = CloudflareStoredConnection(
            account = CloudflareAccount(
                profile = snapshot.profile,
                credential = credential,
                createdAtMillis = sameProfile?.account?.createdAtMillis ?: now,
                updatedAtMillis = now,
            ),
            cachedSnapshot = savedCache,
        )
        return CloudflareConnectionCommit(
            profileId = snapshot.profile.id,
            recordCommit = repository.saveWithRevision(connection),
        )
    }

    fun acceptValidatedConnection(commit: CloudflareConnectionCommit) =
        repository.accept(commit.recordCommit)

    fun rollbackValidatedConnection(commit: CloudflareConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /** Revision-CAS ensures a stale refresh cannot resurrect or replace newer credentials. */
    internal fun persistRefreshResult(
        expected: CloudflareVersionedConnection,
        result: CloudflareFetchResult,
    ): Boolean {
        val existing = expected.connection
        val liveSnapshot = when (result) {
            is CloudflareFetchResult.Complete -> result.snapshot
            is CloudflareFetchResult.Partial -> {
                val candidate = result.snapshot.forOfflineCache()
                candidate.mergePartialCache(existing.cachedSnapshot)
            }
            is CloudflareFetchResult.Failure -> return false
        }
        require(liveSnapshot.profile.id == existing.account.profile.id) {
            "The Cloudflare refresh belongs to a different profile."
        }
        return repository.saveIfRevisionMatches(
            expectedRevision = expected.revision,
            connection = CloudflareStoredConnection(
                account = CloudflareAccount(
                    profile = liveSnapshot.profile,
                    credential = existing.account.credential,
                    createdAtMillis = existing.account.createdAtMillis,
                    updatedAtMillis = nowMillis(),
                ),
                cachedSnapshot = liveSnapshot.forOfflineCache(),
            ),
        )
    }

    fun disconnect() = repository.delete()

    private fun CloudflareFetchResult.snapshotOrThrow(): CloudflareSnapshot = when (this) {
        is CloudflareFetchResult.Complete -> snapshot
        is CloudflareFetchResult.Partial -> snapshot
        is CloudflareFetchResult.Failure -> error("A failed Cloudflare validation cannot be saved.")
    }

    private fun CloudflareSnapshot.forOfflineCache(): CloudflareSnapshot {
        val selectedAccount = selectedAccountId
            ?.let { id -> accounts.firstOrNull { it.id == id } }
            ?.forOfflineCache()
        val initiallyCachedAccounts = accounts.take(MAX_CACHED_ACCOUNTS).map { it.forOfflineCache() }
        var accountsWereTrimmed = accounts.size > MAX_CACHED_ACCOUNTS || initiallyCachedAccounts != accounts
        val cachedAccounts = initiallyCachedAccounts.toMutableList().also { bounded ->
            if (selectedAccount != null && bounded.none { it.id == selectedAccount.id }) {
                if (bounded.isNotEmpty()) bounded[bounded.lastIndex] = selectedAccount else bounded += selectedAccount
                accountsWereTrimmed = true
            }
        }.distinctBy { it.id }

        val inventory = selectedAccountInventory?.let { source ->
            val cachedZones = source.zones.take(MAX_CACHED_ZONES).map { it.forOfflineCache() }
            val cachedPages = source.pagesProjects.take(MAX_CACHED_PAGES_PROJECTS)
                .map { it.forOfflineCache() }
            val cachedWorkers = source.workers.take(MAX_CACHED_WORKERS).map { it.forOfflineCache() }
            val zonesTrimmed = cachedZones != source.zones
            val pagesTrimmed = cachedPages != source.pagesProjects
            val workersTrimmed = cachedWorkers != source.workers
            val boundedSourceWarnings = source.warnings.take(MAX_CACHED_WARNINGS)
                .map { it.take(CACHED_WARNING_CHARACTERS) }
            val warningsTrimmed = boundedSourceWarnings != source.warnings
            val inventoryWasTrimmed = zonesTrimmed || pagesTrimmed || workersTrimmed || warningsTrimmed
            CloudflareAccountInventory(
                accountId = source.accountId,
                zones = cachedZones,
                pagesProjects = cachedPages,
                workers = cachedWorkers,
                zonesComplete = source.zonesComplete && !zonesTrimmed,
                pagesComplete = source.pagesComplete && !pagesTrimmed,
                workersComplete = source.workersComplete && !workersTrimmed,
                warnings = boundedWarnings(source.warnings, inventoryWasTrimmed),
            )
        }
        val boundedSnapshotWarnings = warnings.take(MAX_CACHED_WARNINGS)
            .map { it.take(CACHED_WARNING_CHARACTERS) }
        val warningsWereTrimmed = boundedSnapshotWarnings != warnings
        val modified = accountsWereTrimmed || inventory != selectedAccountInventory || warningsWereTrimmed
        if (!modified) return copy(accounts = cachedAccounts, selectedAccountInventory = inventory)
        return copy(
            accounts = cachedAccounts,
            selectedAccountInventory = inventory,
            accountsComplete = accountsComplete && !accountsWereTrimmed,
            warnings = boundedWarnings(warnings, includeCacheWarning = true),
        )
    }

    private fun boundedWarnings(values: List<String>, includeCacheWarning: Boolean): List<String> {
        val capacity = if (includeCacheWarning) MAX_CACHED_WARNINGS - 1 else MAX_CACHED_WARNINGS
        val bounded = values.take(capacity).map { it.take(CACHED_WARNING_CHARACTERS) }
        return (bounded + listOfNotNull(OFFLINE_CACHE_WARNING.takeIf { includeCacheWarning })).distinct()
    }

    private fun CloudflareAccountSummary.forOfflineCache() = copy(
        name = name.take(CACHED_NAME_CHARACTERS),
        type = type?.take(CACHED_STATUS_CHARACTERS),
        createdOn = createdOn?.take(CACHED_DATE_CHARACTERS),
    )

    private fun CloudflareZone.forOfflineCache() = copy(
        name = name.take(CACHED_NAME_CHARACTERS),
        status = status?.take(CACHED_STATUS_CHARACTERS),
        type = type?.take(CACHED_STATUS_CHARACTERS),
        accountName = accountName?.take(CACHED_NAME_CHARACTERS),
        planName = planName?.take(CACHED_NAME_CHARACTERS),
    )

    private fun CloudflarePagesProject.forOfflineCache() = copy(
        name = name.take(CACHED_NAME_CHARACTERS),
        subdomain = subdomain?.take(CACHED_URL_CHARACTERS),
        domains = domains.take(MAX_CACHED_NESTED_VALUES).map { it.take(CACHED_DOMAIN_CHARACTERS) },
        productionBranch = productionBranch?.take(CACHED_NAME_CHARACTERS),
        createdOn = createdOn?.take(CACHED_DATE_CHARACTERS),
        latestDeploymentStatus = latestDeploymentStatus?.take(CACHED_STATUS_CHARACTERS),
    )

    private fun CloudflareWorkerScript.forOfflineCache() = copy(
        createdOn = createdOn?.take(CACHED_DATE_CHARACTERS),
        modifiedOn = modifiedOn?.take(CACHED_DATE_CHARACTERS),
        compatibilityDate = compatibilityDate?.take(CACHED_DATE_CHARACTERS),
        handlers = handlers.take(MAX_CACHED_NESTED_VALUES).map { it.take(CACHED_HANDLER_CHARACTERS) },
        routes = routes.take(MAX_CACHED_NESTED_VALUES).map { it.take(CACHED_DOMAIN_CHARACTERS) },
        tags = tags.take(MAX_CACHED_NESTED_VALUES).map { it.take(CACHED_HANDLER_CHARACTERS) },
    )

    /**
     * A partial response may improve one product while another request fails. Merge sections
     * independently so such a response cannot erase a previously complete section.
     */
    private fun CloudflareSnapshot.mergePartialCache(previous: CloudflareSnapshot?): CloudflareSnapshot {
        if (previous == null || previous.profile.id != profile.id) return this
        val mergedAccounts = when {
            accountsComplete -> accounts
            previous.accountsComplete -> previous.accounts
            else -> mergeById(previous.accounts, accounts, CloudflareAccountSummary::id)
        }
        val mergedAccountsComplete = accountsComplete || previous.accountsComplete
        val preferredSelectedId = selectedAccountId?.takeIf { id -> mergedAccounts.any { it.id == id } }
            ?: previous.selectedAccountId?.takeIf { id -> mergedAccounts.any { it.id == id } }
            ?: mergedAccounts.firstOrNull()?.id
        val candidateInventory = selectedAccountInventory?.takeIf { it.accountId == preferredSelectedId }
        val previousInventory = previous.selectedAccountInventory?.takeIf { it.accountId == preferredSelectedId }
        val mergedInventory = when {
            candidateInventory != null && previousInventory != null ->
                candidateInventory.mergePartialInventory(previousInventory)
            candidateInventory != null -> candidateInventory
            else -> previousInventory
        }
        val mergedWarnings = buildList {
            if (!mergedAccountsComplete) {
                add(sectionWarning(ACCOUNT_WARNING_PREFIX, warnings, previous.warnings))
            }
            mergedInventory?.warnings?.let(::addAll)
            val mergedIsComplete = mergedAccountsComplete &&
                (preferredSelectedId == null || mergedInventory?.isComplete == true)
            if (!mergedIsComplete) {
                addAll(nonSectionWarnings(previous.warnings))
                addAll(nonSectionWarnings(warnings))
            }
        }.distinct()
        return copy(
            accounts = mergedAccounts,
            selectedAccountId = preferredSelectedId,
            selectedAccountInventory = mergedInventory,
            accountsComplete = mergedAccountsComplete,
            warnings = mergedWarnings,
        )
    }

    private fun CloudflareAccountInventory.mergePartialInventory(
        previous: CloudflareAccountInventory,
    ): CloudflareAccountInventory {
        val mergedZonesComplete = zonesComplete || previous.zonesComplete
        val mergedPagesComplete = pagesComplete || previous.pagesComplete
        val mergedWorkersComplete = workersComplete || previous.workersComplete
        val mergedWarnings = buildList {
            if (!mergedZonesComplete) add(sectionWarning(ZONE_WARNING_PREFIX, warnings, previous.warnings))
            if (!mergedPagesComplete) add(sectionWarning(PAGES_WARNING_PREFIX, warnings, previous.warnings))
            if (!mergedWorkersComplete) add(sectionWarning(WORKER_WARNING_PREFIX, warnings, previous.warnings))
            if (!(mergedZonesComplete && mergedPagesComplete && mergedWorkersComplete)) {
                addAll(nonSectionWarnings(previous.warnings))
                addAll(nonSectionWarnings(warnings))
            }
        }.distinct()
        return copy(
            zones = mergeSection(previous.zones, zones, previous.zonesComplete, zonesComplete, CloudflareZone::id),
            pagesProjects = mergeSection(
                previous.pagesProjects,
                pagesProjects,
                previous.pagesComplete,
                pagesComplete,
                CloudflarePagesProject::id,
            ),
            workers = mergeSection(
                previous.workers,
                workers,
                previous.workersComplete,
                workersComplete,
                CloudflareWorkerScript::id,
            ),
            zonesComplete = mergedZonesComplete,
            pagesComplete = mergedPagesComplete,
            workersComplete = mergedWorkersComplete,
            warnings = mergedWarnings,
        )
    }

    private fun <T> mergeSection(
        previous: List<T>,
        candidate: List<T>,
        previousComplete: Boolean,
        candidateComplete: Boolean,
        identity: (T) -> String,
    ): List<T> = when {
        candidateComplete -> candidate
        previousComplete -> previous
        else -> mergeById(previous, candidate, identity)
    }

    private fun <T> mergeById(previous: List<T>, candidate: List<T>, identity: (T) -> String): List<T> =
        (previous + candidate).associateBy(identity).values.toList()

    private fun sectionWarning(prefix: String, current: List<String>, previous: List<String>): String =
        current.firstOrNull { it.startsWith(prefix) }
            ?: previous.firstOrNull { it.startsWith(prefix) }
            ?: "$prefix refresh online to retry."

    private fun nonSectionWarnings(values: List<String>): List<String> = values.filterNot { warning ->
        SECTION_WARNING_PREFIXES.any(warning::startsWith)
    }

    companion object {
        const val CACHE_LIFETIME_MILLIS: Long = 15 * 60 * 1_000L
        internal const val MAX_CACHED_ACCOUNTS = 10
        internal const val MAX_CACHED_ZONES = 20
        internal const val MAX_CACHED_PAGES_PROJECTS = 10
        internal const val MAX_CACHED_WORKERS = 20
        private const val MAX_CACHED_NESTED_VALUES = 4
        private const val MAX_CACHED_WARNINGS = 8
        private const val CACHED_NAME_CHARACTERS = 128
        private const val CACHED_STATUS_CHARACTERS = 128
        private const val CACHED_DATE_CHARACTERS = 128
        private const val CACHED_URL_CHARACTERS = 512
        private const val CACHED_DOMAIN_CHARACTERS = 128
        private const val CACHED_HANDLER_CHARACTERS = 64
        private const val CACHED_WARNING_CHARACTERS = 256
        private const val OFFLINE_CACHE_WARNING =
            "The offline Cloudflare cache is intentionally bounded; refresh online for complete inventory."
        private const val ACCOUNT_WARNING_PREFIX = "Cloudflare account inventory is incomplete:"
        private const val ZONE_WARNING_PREFIX = "Cloudflare zone inventory is incomplete:"
        private const val PAGES_WARNING_PREFIX = "Cloudflare Pages inventory is incomplete:"
        private const val WORKER_WARNING_PREFIX = "Cloudflare Worker inventory is incomplete:"
        private val SECTION_WARNING_PREFIXES = listOf(
            ACCOUNT_WARNING_PREFIX,
            ZONE_WARNING_PREFIX,
            PAGES_WARNING_PREFIX,
            WORKER_WARNING_PREFIX,
        )
    }
}
