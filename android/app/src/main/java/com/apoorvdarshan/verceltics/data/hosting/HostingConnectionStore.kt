package com.apoorvdarshan.verceltics.data.hosting

/** Opaque handle for accepting or compensating one validated encrypted replacement. */
class HostingConnectionCommit internal constructor(
    val provider: HostingProvider,
    internal val recordCommit: HostingRecordCommit,
) {
    /** The saved account this connection was written to (a rotated existing one, or a new one). */
    val accountId: String get() = recordCommit.accountId

    /** False when the validated identity was already saved and its credentials were rotated in place. */
    val isNewAccount: Boolean get() = recordCommit.vaultCommit.isNewAccount

    override fun toString(): String = "HostingConnectionCommit(provider=${provider.id}, accountId=$accountId, <redacted>)"
}

/** One saved hosting account for the account menu. Never carries credentials. */
data class HostingSavedAccount(
    val accountId: String,
    /** Null when the record exists but could not be opened (it is kept, never deleted). */
    val profile: HostingProfile?,
    val isActive: Boolean,
)

/** Coordinates encrypted persistence for every hosting account while keeping restore offline. */
class HostingConnectionStore(
    private val repository: HostingConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Offline restore of the provider's active account. */
    fun restore(provider: HostingProvider): HostingRestoreResult = try {
        val versioned = repository.loadWithRevision(provider)
        if (versioned == null) {
            HostingRestoreResult.NotConnected
        } else {
            val connection = versioned.connection
            val snapshot = connection.cachedSnapshot
            HostingRestoreResult.Restored(
                provider = provider,
                profile = connection.account.profile,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null || nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
                linkContext = HostingLinkContext.of(connection.account.credentials),
                accountId = versioned.accountId,
            )
        }
    } catch (_: SecurityException) {
        HostingRestoreResult.Unavailable(HostingRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        HostingRestoreResult.Unavailable(HostingRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Each provider restores independently, so one unreadable record never hides the others. */
    fun restoreAll(): Map<HostingProvider, HostingRestoreResult> =
        HostingProvider.entries.associateWith(::restore)

    /** Every saved account of [provider], for the account menu. Offline and credential-free. */
    fun accounts(provider: HostingProvider): List<HostingSavedAccount> {
        val activeId = repository.activeAccountId(provider)
        return repository.records(provider).map { record ->
            HostingSavedAccount(
                accountId = record.accountId,
                profile = (record as? AccountVaultRecord.Readable)?.entry?.value?.account?.profile,
                isActive = record.accountId == activeId,
            )
        }
    }

    /** Backend-only access for network work. UI-facing restore never receives credentials. */
    internal fun loadForRefresh(provider: HostingProvider): HostingStoredConnection? = repository.load(provider)

    /** The active account with the revision a refresh must compare against. */
    internal fun loadActive(provider: HostingProvider): HostingVersionedConnection? =
        repository.loadWithRevision(provider)

    internal fun loadAccount(provider: HostingProvider, accountId: String): HostingVersionedConnection? =
        repository.loadWithRevision(provider, accountId)

    /** Every readable saved account, for the launch profile refresh (iOS `refreshAccountProfiles`). */
    internal fun loadAllAccounts(provider: HostingProvider): List<HostingVersionedConnection> =
        repository.records(provider).mapNotNull { record ->
            (record as? AccountVaultRecord.Readable)?.entry?.let { HostingVersionedConnection(it.value, it.revision) }
        }

    /**
     * Saves a validated connection as the active account. Reconnecting an identity that is already
     * saved (same provider profile id) rotates its credentials in place, like iOS.
     */
    fun saveValidatedConnection(credentials: HostingCredentials, snapshot: HostingSnapshot): HostingConnectionCommit {
        require(snapshot.provider == credentials.provider) { "The snapshot belongs to a different provider." }
        val provider = credentials.provider
        val now = nowMillis()
        val sameAccount = repository.records(provider)
            .filterIsInstance<AccountVaultRecord.Readable<HostingStoredConnection>>()
            .map { it.entry }
            .firstOrNull { it.value.account.isSameIdentity(credentials, snapshot.profile) }
        val account = HostingAccount(
            profile = snapshot.profile,
            credentials = credentials,
            createdAtMillis = sameAccount?.value?.account?.createdAtMillis ?: now,
            updatedAtMillis = maxOf(now, sameAccount?.value?.account?.createdAtMillis ?: now),
        )
        val connection = boundedForStorage(HostingStoredConnection(account, snapshot.forOfflineCache()))
        return HostingConnectionCommit(provider, repository.saveWithRevision(sameAccount?.accountId, connection))
    }

    fun acceptValidatedConnection(commit: HostingConnectionCommit) = repository.accept(commit.recordCommit)

    /**
     * Compensates cancellation only while this commit's exact revision is current; a newer
     * connection, refresh or removal is never overwritten by a late rollback.
     */
    fun rollbackValidatedConnection(commit: HostingConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /** Stores a refreshed inventory for the active account unless it changed meanwhile. */
    fun persistRefreshResult(snapshot: HostingSnapshot): Boolean {
        val accountId = repository.activeAccountId(snapshot.provider) ?: return false
        return persistRefreshResult(accountId, snapshot)
    }

    /** Stores a refreshed inventory for [accountId] unless that record changed meanwhile. */
    fun persistRefreshResult(accountId: String, snapshot: HostingSnapshot): Boolean {
        val versioned = repository.loadWithRevision(snapshot.provider, accountId) ?: return false
        val existing = versioned.connection
        if (existing.account.profile.id != snapshot.profile.id) return false
        val account = HostingAccount(
            profile = snapshot.profile,
            credentials = existing.account.credentials,
            createdAtMillis = existing.account.createdAtMillis,
            updatedAtMillis = maxOf(nowMillis(), existing.account.createdAtMillis),
        )
        return repository.saveIfRevisionMatches(
            versioned.revision,
            boundedForStorage(HostingStoredConnection(account, snapshot.forOfflineCache())),
        )
    }

    /**
     * iOS `refreshAccountProfiles`: updates a saved account's name, email and avatar when the
     * provider still reports the same identity and the record did not change meanwhile.
     */
    internal fun persistRefreshedProfile(expected: HostingVersionedConnection, profile: HostingProfile): Boolean {
        val existing = expected.connection
        if (existing.account.profile.id != profile.id || existing.account.profile == profile) return false
        val account = HostingAccount(
            profile = profile,
            credentials = existing.account.credentials,
            createdAtMillis = existing.account.createdAtMillis,
            updatedAtMillis = maxOf(nowMillis(), existing.account.createdAtMillis),
        )
        val snapshot = existing.cachedSnapshot?.copy(profile = profile)
        return repository.saveIfRevisionMatches(
            expected.revision,
            boundedForStorage(HostingStoredConnection(account, snapshot)),
        )
    }

    /** Switches the provider's active account. */
    fun switchAccount(provider: HostingProvider, accountId: String): Boolean = repository.activate(provider, accountId)

    /** Removes one saved account; returns the new active account id (null when none remain). */
    fun removeAccount(provider: HostingProvider, accountId: String): String? =
        repository.deleteAccount(provider, accountId)

    /** Removes every saved account of [provider] (iOS "Remove All Accounts"); returns the removed ids. */
    fun disconnect(provider: HostingProvider): List<String> = repository.delete(provider)

    /**
     * Removes the active account even when it could not be listed (an unreadable record): the
     * data layer resolves it, so a "remove current" never falls back to removing every account.
     */
    fun removeActiveAccount(provider: HostingProvider): String? = repository.deleteActiveAccount(provider)

    fun activeAccountId(provider: HostingProvider): String? = repository.activeAccountId(provider)

    /**
     * iOS matches the provider user id (or the same credential). Fly.io and AWS Amplify report no
     * user id: Fly's profile is just the organization slug that any user can share ("personal"), so
     * only the same token is the same account; Amplify is identified by its full access key id and
     * region, so a new secret for the same key rotates in place.
     */
    private fun HostingAccount.isSameIdentity(candidate: HostingCredentials, candidateProfile: HostingProfile): Boolean =
        when (val saved = credentials) {
            is HostingCredentials.Fly -> candidate is HostingCredentials.Fly &&
                saved.organization == candidate.organization && saved.token == candidate.token
            is HostingCredentials.AwsAmplify -> candidate is HostingCredentials.AwsAmplify &&
                saved.accessKeyId == candidate.accessKeyId && saved.region == candidate.region
            is HostingCredentials.Railway -> candidate is HostingCredentials.Railway &&
                saved.tokenType == candidate.tokenType && profile.id == candidateProfile.id
            else -> saved.provider == candidate.provider && profile.id == candidateProfile.id
        }

    private fun HostingSnapshot.forOfflineCache(): HostingSnapshot {
        var truncated = resources.size > MAX_CACHED_RESOURCES
        val cached = resources.take(MAX_CACHED_RESOURCES).map { resource ->
            val bounded = resource.copy(
                id = resource.id,
                name = resource.name.take(CACHED_NAME_CHARACTERS),
                subtitle = resource.subtitle?.take(CACHED_TEXT_CHARACTERS),
                url = resource.url?.takeIf { it.length <= CACHED_URL_CHARACTERS },
                status = resource.status?.take(CACHED_SHORT_CHARACTERS),
                region = resource.region?.take(CACHED_SHORT_CHARACTERS),
                kind = resource.kind?.take(CACHED_SHORT_CHARACTERS),
                metadata = resource.metadata.entries.take(CACHED_METADATA_ENTRIES)
                    .associate { (key, value) -> key.take(CACHED_SHORT_CHARACTERS) to value.take(CACHED_TEXT_CHARACTERS) },
            )
            if (bounded != resource) truncated = true
            bounded
        }
        return copy(
            resources = cached,
            warnings = if (truncated) (warnings + CACHE_WARNING).distinct() else warnings,
        )
    }

    /** Halves the cached inventory until the encrypted payload fits its fixed size budget. */
    private fun boundedForStorage(connection: HostingStoredConnection): HostingStoredConnection {
        var candidate = connection
        while (true) {
            val encoded = HostingConnectionPayloadCodec.encodeOrNull(candidate)
            if (encoded != null) {
                encoded.fill(0)
                return candidate
            }
            val snapshot = candidate.cachedSnapshot
                ?: throw IllegalArgumentException("The hosting connection is too large to store safely.")
            candidate = candidate.copy(
                cachedSnapshot = if (snapshot.resources.isEmpty()) {
                    null
                } else {
                    snapshot.copy(
                        resources = snapshot.resources.take(snapshot.resources.size / 2),
                        warnings = (snapshot.warnings + CACHE_WARNING).distinct(),
                    )
                },
            )
        }
    }

    companion object {
        /** iOS `DashboardRefreshPolicy.inventoryFreshness` equivalent for offline caches. */
        const val CACHE_LIFETIME_MILLIS: Long = 15 * 60 * 1_000L
        internal const val MAX_CACHED_RESOURCES = 100
        private const val CACHED_NAME_CHARACTERS = 256
        private const val CACHED_TEXT_CHARACTERS = 512
        private const val CACHED_URL_CHARACTERS = 2_048
        private const val CACHED_SHORT_CHARACTERS = 128
        private const val CACHED_METADATA_ENTRIES = 8
        internal const val CACHE_WARNING =
            "The offline cache is intentionally bounded; refresh online for the complete inventory."
    }
}
