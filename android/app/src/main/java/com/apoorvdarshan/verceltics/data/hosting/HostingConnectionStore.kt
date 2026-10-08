package com.apoorvdarshan.verceltics.data.hosting

/** Opaque handle for accepting or compensating one validated encrypted replacement. */
class HostingConnectionCommit internal constructor(
    val provider: HostingProvider,
    internal val recordCommit: HostingRecordCommit,
) {
    override fun toString(): String = "HostingConnectionCommit(provider=${provider.id}, <redacted>)"
}

/** Coordinates encrypted persistence for every hosting slot while keeping restore offline. */
class HostingConnectionStore(
    private val repository: HostingConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restore(provider: HostingProvider): HostingRestoreResult = try {
        val connection = repository.load(provider)
        if (connection == null) {
            HostingRestoreResult.NotConnected
        } else {
            val snapshot = connection.cachedSnapshot
            HostingRestoreResult.Restored(
                provider = provider,
                profile = connection.account.profile,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null || nowMillis() - snapshot.fetchedAtMillis >= CACHE_LIFETIME_MILLIS,
                linkContext = HostingLinkContext.of(connection.account.credentials),
            )
        }
    } catch (_: SecurityException) {
        HostingRestoreResult.Unavailable(HostingRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        HostingRestoreResult.Unavailable(HostingRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Each slot restores independently, so one unreadable record never hides the others. */
    fun restoreAll(): Map<HostingProvider, HostingRestoreResult> =
        HostingProvider.entries.associateWith(::restore)

    /** Backend-only access for network work. UI-facing restore never receives credentials. */
    internal fun loadForRefresh(provider: HostingProvider): HostingStoredConnection? = repository.load(provider)

    fun saveValidatedConnection(credentials: HostingCredentials, snapshot: HostingSnapshot): HostingConnectionCommit {
        require(snapshot.provider == credentials.provider) { "The snapshot belongs to a different provider." }
        val now = nowMillis()
        val existing = runCatching { repository.load(credentials.provider) }.getOrNull()
        val sameAccount = existing?.takeIf { it.account.profile.id == snapshot.profile.id }
        val account = HostingAccount(
            profile = snapshot.profile,
            credentials = credentials,
            createdAtMillis = sameAccount?.account?.createdAtMillis ?: now,
            updatedAtMillis = now,
        )
        val connection = boundedForStorage(HostingStoredConnection(account, snapshot.forOfflineCache()))
        return HostingConnectionCommit(credentials.provider, repository.saveWithRevision(connection))
    }

    fun acceptValidatedConnection(commit: HostingConnectionCommit) = repository.accept(commit.recordCommit)

    /**
     * Compensates cancellation only while this commit's exact revision is current; a newer
     * connection, refresh or disconnect is never overwritten by a late rollback.
     */
    fun rollbackValidatedConnection(commit: HostingConnectionCommit): Boolean =
        repository.rollbackIfRevisionMatches(commit.recordCommit)

    /** Stores a refreshed inventory unless the slot changed meanwhile. Failures never get here. */
    fun persistRefreshResult(snapshot: HostingSnapshot): Boolean {
        val versioned = repository.loadWithRevision(snapshot.provider) ?: return false
        val existing = versioned.connection
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

    fun disconnect(provider: HostingProvider) = repository.delete(provider)

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
