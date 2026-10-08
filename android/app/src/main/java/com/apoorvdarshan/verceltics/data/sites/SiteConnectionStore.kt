package com.apoorvdarshan.verceltics.data.sites

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * One saved site-service account. [credential] is null for Google providers, whose tokens live in
 * the account's own `GoogleOAuthSession` slot ([SiteGoogleSlots]). [metadata] never has secrets.
 */
class StoredSiteConnection(
    val accountId: String,
    val provider: SiteProvider,
    val name: String,
    val credential: SecretValue?,
    val metadata: Map<String, String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val cachedSnapshot: SiteSnapshot?,
) {
    init {
        require(SiteAccountIds.isValid(accountId)) { "Invalid site account id." }
        require(name.isNotBlank()) { "A site connection needs a name." }
        require(provider.usesGoogleOAuth || credential != null) { "${provider.displayName} needs a credential." }
    }

    fun withSnapshot(name: String, snapshot: SiteSnapshot?, metadata: Map<String, String>, updatedAtMillis: Long) =
        StoredSiteConnection(accountId, provider, name, credential, metadata, createdAtMillis, updatedAtMillis, snapshot)

    /** Non-secret menu identity: the display name plus the Google email or Umami username. */
    val accountEntry: SiteAccountEntry
        get() = SiteAccountEntry(
            id = accountId,
            name = SiteAccountIds.label(name),
            detail = (metadata["googleEmail"] ?: metadata["umamiUsername"])?.let(SiteAccountIds::label)?.takeIf(String::isNotBlank),
        )

    override fun toString(): String =
        "StoredSiteConnection(accountId=$accountId, provider=${provider.id}, name=$name, credential=" +
            "${if (credential == null) "none" else "<redacted>"}, metadataKeys=${metadata.keys}, " +
            "cachedResources=${cachedSnapshot?.resources?.size})"
}

/** In-memory identity of one encrypted record (SHA-256 of its envelope). */
class SiteConnectionRevision internal constructor(private val digest: ByteArray) {
    internal fun matches(envelope: ByteArray): Boolean {
        val candidate = MessageDigest.getInstance("SHA-256").digest(envelope)
        return try {
            MessageDigest.isEqual(digest, candidate)
        } finally {
            candidate.fill(0)
        }
    }

    override fun toString(): String = "SiteConnectionRevision(<redacted>)"

    internal companion object {
        fun of(envelope: ByteArray) = SiteConnectionRevision(MessageDigest.getInstance("SHA-256").digest(envelope))
    }
}

class SiteVersionedConnection(
    val connection: StoredSiteConnection,
    val revision: SiteConnectionRevision,
)

/** Google OAuth slot names for site services: one slot per saved account. */
object SiteGoogleSlots {
    /** The single GA4 slot used before multi-account support; migrated to the first account. */
    const val LEGACY_GOOGLE_ANALYTICS: String = "site.google-analytics"

    fun legacy(provider: SiteProvider): String? =
        if (provider == SiteProvider.GOOGLE_ANALYTICS) LEGACY_GOOGLE_ANALYTICS else null

    fun forAccount(provider: SiteProvider, accountId: String): String {
        require(provider.usesGoogleOAuth) { "${provider.displayName} does not use Google sign-in." }
        require(SiteAccountIds.isValid(accountId)) { "Invalid site account id." }
        return "$LEGACY_GOOGLE_ANALYTICS.$accountId"
    }
}

/** Moves secrets that pre-multi-account builds kept outside the provider record. */
interface SiteLegacySecretsMigrator {
    fun migrate(provider: SiteProvider, accountId: String) {}

    fun deleteLegacy(provider: SiteProvider) {}

    companion object {
        val NONE: SiteLegacySecretsMigrator = object : SiteLegacySecretsMigrator {}
    }
}

/**
 * Copies the legacy `site.google-analytics` credential into the migrated account's own slot. A
 * locked keystore aborts the migration (it retries later); a corrupt legacy credential is skipped
 * and the migrated account simply asks the user to reconnect.
 */
class SiteGoogleSlotMigrator(
    private val credentialStore: (slot: String) -> GoogleOAuthCredentialStore,
) : SiteLegacySecretsMigrator {
    override fun migrate(provider: SiteProvider, accountId: String) {
        val legacySlot = SiteGoogleSlots.legacy(provider) ?: return
        val credential = try {
            credentialStore(legacySlot).load()
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return
        credentialStore(SiteGoogleSlots.forAccount(provider, accountId)).save(credential)
    }

    override fun deleteLegacy(provider: SiteProvider) {
        SiteGoogleSlots.legacy(provider)?.let { credentialStore(it).delete() }
    }
}

/**
 * Encrypted multi-account site-service storage. Each provider keeps its own index and one record
 * per account, each in its own no-backup file and authenticated-data domain, so one account's
 * large cached inventory never pushes another over the envelope limit and records cannot be
 * replayed across providers or accounts. Pre-multi-account records migrate on first access.
 */
class SiteConnectionRepository(
    private val storeFor: (relativePath: String) -> AtomicBytesStore,
    private val cipher: AccountCipher,
    private val legacySecrets: SiteLegacySecretsMigrator = SiteLegacySecretsMigrator.NONE,
) {
    private val vaults = HashMap<SiteProvider, SiteAccountVault<StoredSiteConnection>>()
    private val legacySources = HashMap<SiteProvider, LegacySource>()

    fun index(provider: SiteProvider): SiteAccountIndex =
        vault(provider).migratedIndex(
            legacy = legacySource(provider),
            entry = { connection, _ -> connection.accountEntry },
        )

    fun writeIndex(provider: SiteProvider, index: SiteAccountIndex) = vault(provider).writeIndex(index)

    fun load(provider: SiteProvider, accountId: String): StoredSiteConnection? = vault(provider).read(accountId)

    fun loadWithRevision(provider: SiteProvider, accountId: String): SiteVersionedConnection? =
        vault(provider).readVersioned(accountId)?.let { SiteVersionedConnection(it.record, it.revision) }

    fun save(connection: StoredSiteConnection) = vault(connection.provider).write(connection.accountId, connection)

    /** Compare-and-swap so a stale refresh can never resurrect or overwrite a newer record. */
    fun saveIfRevisionMatches(expected: SiteConnectionRevision, connection: StoredSiteConnection): Boolean =
        vault(connection.provider).writeIfRevisionMatches(connection.accountId, expected, connection)

    fun delete(provider: SiteProvider, accountId: String) = vault(provider).delete(accountId)

    /** Drops an unreadable index (and any legacy record) so a corrupt provider can be reconnected. */
    fun resetUnreadable(provider: SiteProvider) {
        vault(provider).deleteIndex()
        legacySource(provider).delete()
    }

    fun <R> transaction(provider: SiteProvider, block: () -> R): R = vault(provider).transaction(block)

    @Synchronized
    private fun vault(provider: SiteProvider): SiteAccountVault<StoredSiteConnection> = vaults.getOrPut(provider) {
        SiteAccountVault(
            domain = "$DOMAIN_PREFIX${provider.id}",
            indexStore = storeFor(indexPath(provider)),
            recordStoreFor = { accountId -> storeFor(recordPath(provider, accountId)) },
            cipher = cipher,
            codec = object : SiteAccountRecordCodec<StoredSiteConnection> {
                override fun encode(record: StoredSiteConnection): ByteArray = SiteConnectionPayloadCodec.encode(record)

                override fun decode(bytes: ByteArray, accountId: String): StoredSiteConnection =
                    SiteConnectionPayloadCodec.decode(bytes, provider, accountId)
            },
        )
    }

    @Synchronized
    private fun legacySource(provider: SiteProvider): LegacySource = legacySources.getOrPut(provider) { LegacySource(provider) }

    /** The pre-multi-account `accounts/site-services/<provider>.account` record. */
    private inner class LegacySource(private val provider: SiteProvider) : SiteLegacyAccountSource<StoredSiteConnection> {
        private val store = storeFor(accountPath(provider))

        @Volatile
        private var removed = false

        override fun load(): StoredSiteConnection? {
            val envelope = store.read() ?: return null
            val associatedData = legacyAssociatedData(provider)
            var plaintext: ByteArray? = null
            return try {
                plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
                SiteConnectionPayloadCodec.decode(plaintext, provider, SiteAccountIds.migrated("$DOMAIN_PREFIX${provider.id}"))
            } finally {
                envelope.fill(0)
                associatedData.fill(0)
                plaintext?.fill(0)
            }
        }

        override fun migrateSecrets(record: StoredSiteConnection, accountId: String) =
            legacySecrets.migrate(provider, accountId)

        override fun delete() {
            if (removed) return
            store.delete()
            legacySecrets.deleteLegacy(provider)
            removed = true
        }
    }

    companion object {
        internal const val ASSOCIATED_DATA_PREFIX = "verceltics.account-envelope.v1:site-service:"
        internal const val DOMAIN_PREFIX = "site-service:"
        internal const val KEY_ALIAS = "verceltics.account-storage.site-services.v1"

        /** The legacy single-account record path (read only for migration). */
        fun accountPath(provider: SiteProvider): String = "accounts/site-services/${provider.id}.account"

        fun indexPath(provider: SiteProvider): String = "accounts/site-services/${provider.id}/accounts.index"

        fun recordPath(provider: SiteProvider, accountId: String): String {
            require(SiteAccountIds.isValid(accountId)) { "Invalid site account id." }
            return "accounts/site-services/${provider.id}/$accountId.account"
        }

        internal fun legacyAssociatedData(provider: SiteProvider): ByteArray =
            "$ASSOCIATED_DATA_PREFIX${provider.id}".toByteArray(StandardCharsets.UTF_8)

        fun create(context: Context): SiteConnectionRepository {
            val applicationContext = context.applicationContext
            return SiteConnectionRepository(
                storeFor = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
                legacySecrets = SiteGoogleSlotMigrator { slot ->
                    EncryptedGoogleOAuthCredentialStore.create(applicationContext, slot)
                },
            )
        }
    }
}

sealed interface SiteRestoreResult {
    data object NotConnected : SiteRestoreResult

    data class Restored(
        val provider: SiteProvider,
        val accountId: String,
        val name: String,
        val metadata: Map<String, String>,
        val cachedSnapshot: SiteSnapshot?,
        val cacheIsStale: Boolean,
        val accounts: SiteAccountIndex,
    ) : SiteRestoreResult

    /** A saved account exists but cannot be opened; [accounts] lists what could be read. */
    data class Unavailable(
        val problem: SiteRestoreProblem,
        val accounts: SiteAccountIndex = SiteAccountIndex.EMPTY,
    ) : SiteRestoreResult
}

enum class SiteRestoreProblem {
    SECURE_STORAGE_UNAVAILABLE,
    SAVED_RECORD_UNREADABLE,
}

/**
 * Same-identity rules for site-service accounts (iOS `SiteStore.isSameConnection`): reconnecting
 * the same identity rotates its credential in place instead of adding a duplicate account.
 */
internal object SiteAccountIdentity {
    /** Identity discovered after validation that outranks credential equality (Umami `/me`). */
    fun strongMatch(provider: SiteProvider, existing: StoredSiteConnection, metadata: Map<String, String>): Boolean {
        if (provider != SiteProvider.UMAMI) return false
        val userId = metadata["umamiUserID"].clean() ?: return false
        val endpoint = endpointIdentity(metadata["umamiEndpoint"]) ?: return false
        return existing.metadata["umamiUserID"].clean() == userId &&
            endpointIdentity(existing.metadata["umamiEndpoint"]) == endpoint
    }

    fun matches(
        provider: SiteProvider,
        existing: StoredSiteConnection,
        credential: SecretValue?,
        metadata: Map<String, String>,
    ): Boolean {
        if (existing.provider != provider) return false
        return when (provider) {
            SiteProvider.PLAUSIBLE -> {
                val current = siteIdentifier(existing.metadata["siteID"]) ?: return false
                current == siteIdentifier(metadata["siteID"])
            }
            SiteProvider.UMAMI -> strongMatch(provider, existing, metadata) ||
                (
                    credential != null && existing.credential == credential &&
                        existing.metadata["authMode"] == metadata["authMode"] &&
                        existing.metadata["baseURL"].orEmpty().equals(metadata["baseURL"].orEmpty(), ignoreCase = true)
                    )
            SiteProvider.GOOGLE_ANALYTICS -> {
                val subject = metadata["googleSubject"].clean() ?: return false
                existing.metadata["googleSubject"] == subject
            }
            SiteProvider.CLARITY -> {
                val currentSite = originIdentity(existing.metadata["siteURL"])
                val newSite = originIdentity(metadata["siteURL"])
                val currentProject = existing.metadata["projectName"].clean()?.lowercase(Locale.ROOT)
                val newProject = metadata["projectName"].clean()?.lowercase(Locale.ROOT)
                if (currentSite != null && newSite != null && currentProject != null && newProject != null) {
                    currentSite == newSite && currentProject == newProject
                } else {
                    credential != null && existing.credential == credential
                }
            }
            SiteProvider.BING_WEBMASTER,
            SiteProvider.UPTIME_ROBOT,
            SiteProvider.BETTER_STACK,
            -> credential != null && existing.credential == credential
        }
    }

    /** Plausible site ids: hosts compare case-insensitively, paths exactly. */
    fun siteIdentifier(raw: String?): String? {
        val value = raw.clean() ?: return null
        if ("://" in value) return urlIdentity(value)
        val separator = value.indexOf('/')
        return if (separator < 0) value.lowercase(Locale.ROOT) else value.substring(0, separator).lowercase(Locale.ROOT) + value.substring(separator)
    }

    private fun urlIdentity(value: String): String? = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        URI(scheme, uri.rawUserInfo, host, uri.port, uri.rawPath, uri.rawQuery, uri.rawFragment).toString()
    }.getOrNull()

    private fun originIdentity(raw: String?): String? = runCatching {
        val uri = URI(raw.clean() ?: return null)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        "$scheme://$host${if (uri.port == -1) "" else ":${uri.port}"}"
    }.getOrNull()

    private fun endpointIdentity(raw: String?): String? =
        raw.clean()?.trimEnd('/')?.lowercase(Locale.ROOT)

    private fun String?.clean(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}

/** Offline restore, validated saves, compare-and-swap refreshes, and account management. */
class SiteConnectionStore(
    private val repository: SiteConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Saved accounts for [provider], migrating a pre-multi-account record first. Throws when unreadable. */
    fun accounts(provider: SiteProvider): SiteAccountIndex = repository.index(provider)

    /** Offline restore of the active account; never performs network requests. */
    fun restore(provider: SiteProvider): SiteRestoreResult {
        val index = try {
            repository.index(provider)
        } catch (_: SecurityException) {
            return SiteRestoreResult.Unavailable(SiteRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
        } catch (_: Exception) {
            return SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE)
        }
        val active = index.active ?: return SiteRestoreResult.NotConnected
        return try {
            val connection = repository.load(provider, active.id)
                ?: return SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE, index)
            val snapshot = connection.cachedSnapshot
            SiteRestoreResult.Restored(
                provider = provider,
                accountId = active.id,
                name = connection.name,
                metadata = connection.metadata,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null ||
                    nowMillis() - snapshot.fetchedAtMillis >= provider.snapshotCacheLifetimeMillis,
                accounts = index,
            )
        } catch (_: SecurityException) {
            SiteRestoreResult.Unavailable(SiteRestoreProblem.SECURE_STORAGE_UNAVAILABLE, index)
        } catch (_: Exception) {
            SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE, index)
        }
    }

    /**
     * Backend-only access for requests (the active account unless [accountId] is given); UI-facing
     * restore never receives the credential.
     */
    fun loadForRequest(provider: SiteProvider, accountId: String? = null): SiteVersionedConnection? {
        val id = accountId ?: repository.index(provider).activeId ?: return null
        return repository.loadWithRevision(provider, id)
    }

    /** The saved account with the same identity as a new connection, if any (in-place rotation). */
    fun matchingAccountId(provider: SiteProvider, credential: SecretValue?, metadata: Map<String, String>): String? {
        val saved = repository.index(provider).ids.mapNotNull { id ->
            runCatching { repository.load(provider, id) }.getOrNull()
        }
        return saved.firstOrNull { SiteAccountIdentity.strongMatch(provider, it, metadata) }?.accountId
            ?: saved.firstOrNull { SiteAccountIdentity.matches(provider, it, credential, metadata) }?.accountId
    }

    /**
     * Saves a validated connection and makes it the active account. [accountId] null resolves a
     * same-identity account (rotated in place, keeping its id and creation time) or adds a new one.
     */
    fun saveValidatedConnection(
        provider: SiteProvider,
        accountId: String?,
        name: String,
        credential: SecretValue?,
        metadata: Map<String, String>,
        snapshot: SiteSnapshot,
    ): StoredSiteConnection = repository.transaction(provider) {
        val now = nowMillis()
        val index = repository.index(provider)
        val id = accountId ?: matchingAccountId(provider, credential, metadata) ?: SiteAccountIds.newId()
        if (id !in index && index.accounts.size >= SiteAccountIndex.MAX_ACCOUNTS) {
            throw IllegalStateException("Too many saved ${provider.displayName} accounts.")
        }
        val existing = if (id in index) runCatching { repository.load(provider, id) }.getOrNull() else null
        val connection = fittedToEnvelope(
            StoredSiteConnection(
                accountId = id,
                provider = provider,
                name = name,
                credential = credential,
                metadata = metadata,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
                cachedSnapshot = forOfflineCache(snapshot),
            ),
        )
        repository.save(connection)
        repository.writeIndex(provider, index.upserting(connection.accountEntry, activate = true))
        connection
    }

    /** Caches a refreshed snapshot only while the record it was fetched from is still current. */
    fun persistRefresh(
        source: SiteVersionedConnection,
        name: String,
        snapshot: SiteSnapshot,
        discoveredMetadata: Map<String, String> = emptyMap(),
    ): Boolean {
        val existing = source.connection
        require(snapshot.provider == existing.provider) { "The refresh belongs to a different provider." }
        return repository.transaction(existing.provider) {
            val updated = existing.withSnapshot(
                name = name,
                snapshot = forOfflineCache(snapshot),
                metadata = existing.metadata + discoveredMetadata,
                updatedAtMillis = nowMillis(),
            )
            val saved = repository.saveIfRevisionMatches(source.revision, fittedToEnvelope(updated))
            if (saved) {
                // Keep the account menu label current; the record stays the source of truth.
                runCatching {
                    val index = repository.index(existing.provider)
                    val entry = updated.accountEntry
                    if (existing.accountId in index && index.entry(existing.accountId) != entry) {
                        repository.writeIndex(existing.provider, index.upserting(entry, activate = false))
                    }
                }
            }
            saved
        }
    }

    /** Makes [accountId] active. Returns the updated index, or null when the account is unknown. */
    fun switchAccount(provider: SiteProvider, accountId: String): SiteAccountIndex? = repository.transaction(provider) {
        val index = repository.index(provider)
        if (accountId !in index) return@transaction null
        index.activating(accountId).also { repository.writeIndex(provider, it) }
    }

    /** Removes one account (index first, then its record) and returns the remaining accounts. */
    fun removeAccount(provider: SiteProvider, accountId: String): SiteAccountIndex = repository.transaction(provider) {
        val index = repository.index(provider)
        val remaining = index.removing(accountId)
        if (remaining != index) repository.writeIndex(provider, remaining)
        repository.delete(provider, accountId)
        remaining
    }

    /** Removes every account for [provider] and returns the removed ids. */
    fun removeAllAccounts(provider: SiteProvider): List<String> = repository.transaction(provider) {
        val ids = repository.index(provider).ids
        repository.writeIndex(provider, SiteAccountIndex.EMPTY)
        ids.forEach { repository.delete(provider, it) }
        ids
    }

    /**
     * Removes the active account (the single-account "Disconnect") and returns its id. A saved
     * account list that cannot be decoded is discarded instead, like the single-account build did
     * with an unreadable record; a locked keystore is never treated as corruption.
     */
    fun disconnect(provider: SiteProvider): String? = repository.transaction(provider) {
        val index = try {
            repository.index(provider)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            repository.resetUnreadable(provider)
            return@transaction null
        }
        index.activeId?.also { removeAccount(provider, it) }
    }

    /** Bounds the cached inventory so a refresh can always be persisted inside one envelope. */
    internal fun forOfflineCache(snapshot: SiteSnapshot): SiteSnapshot {
        var modified = snapshot.resources.size > MAX_CACHED_RESOURCES
        val resources = snapshot.resources.take(MAX_CACHED_RESOURCES).map { resource ->
            val bounded = resource.copy(
                id = resource.id.take(MAX_ID_CHARACTERS),
                name = resource.name.take(MAX_TEXT_CHARACTERS),
                subtitle = resource.subtitle?.take(MAX_TEXT_CHARACTERS),
                url = resource.url?.takeIf { it.length <= MAX_URL_CHARACTERS },
                status = resource.status?.take(MAX_TEXT_CHARACTERS),
                metrics = resource.metrics.take(MAX_CACHED_METRICS).map(::boundedMetric),
                metadata = resource.metadata.entries.take(MAX_CACHED_METADATA)
                    .associate { (key, value) -> key.take(MAX_ID_CHARACTERS) to value.take(MAX_METADATA_VALUE_CHARACTERS) },
            )
            if (bounded != resource) modified = true
            bounded
        }
        val metrics = snapshot.metrics.take(MAX_CACHED_METRICS).map(::boundedMetric)
        val warnings = snapshot.warnings.take(MAX_CACHED_WARNINGS).map { it.take(MAX_WARNING_CHARACTERS) }
        if (metrics != snapshot.metrics || warnings != snapshot.warnings) modified = true
        if (!modified) return snapshot.copy(resources = resources)
        return snapshot.copy(
            resources = resources,
            metrics = metrics,
            warnings = (warnings + cacheWarning(snapshot.provider)).distinct().take(MAX_CACHED_WARNINGS),
        )
    }

    private fun boundedMetric(metric: SiteMetric) = metric.copy(
        key = metric.key.take(MAX_ID_CHARACTERS),
        label = metric.label.take(MAX_TEXT_CHARACTERS),
        formattedValue = metric.formattedValue?.take(MAX_TEXT_CHARACTERS),
        resourceId = metric.resourceId?.take(MAX_ID_CHARACTERS),
    )

    /** Halves the cached resources until the encrypted payload fits the shared envelope limit. */
    private fun fittedToEnvelope(connection: StoredSiteConnection): StoredSiteConnection {
        var candidate = connection
        while (true) {
            val size = runCatching { SiteConnectionPayloadCodec.encode(candidate).also { it.fill(0) }.size }
                .getOrDefault(Int.MAX_VALUE)
            val snapshot = candidate.cachedSnapshot
            if (size <= SiteConnectionPayloadCodec.MAX_PLAINTEXT_BYTES || snapshot == null) return candidate
            val keep = snapshot.resources.size / 2
            val reduced = if (snapshot.resources.isEmpty()) {
                null
            } else {
                snapshot.copy(
                    resources = snapshot.resources.take(keep),
                    warnings = (snapshot.warnings + cacheWarning(snapshot.provider)).distinct().take(MAX_CACHED_WARNINGS),
                )
            }
            candidate = candidate.withSnapshot(candidate.name, reduced, candidate.metadata, candidate.updatedAtMillis)
        }
    }

    private fun cacheWarning(provider: SiteProvider) =
        "The offline ${provider.displayName} cache is intentionally bounded; refresh online for the complete data."

    companion object {
        const val MAX_CACHED_RESOURCES: Int = 200
        private const val MAX_CACHED_METRICS = 64
        private const val MAX_CACHED_METADATA = 32
        private const val MAX_CACHED_WARNINGS = 24
        private const val MAX_ID_CHARACTERS = 512
        private const val MAX_TEXT_CHARACTERS = 512
        private const val MAX_URL_CHARACTERS = 2_048
        private const val MAX_METADATA_VALUE_CHARACTERS = 2_048
        private const val MAX_WARNING_CHARACTERS = 1_024
    }
}

/** Versioned binary plaintext; exists only between this codec and authenticated encryption. */
internal object SiteConnectionPayloadCodec {
    private const val VERSION = 1
    const val MAX_PLAINTEXT_BYTES: Int = 440 * 1_024
    private const val MAX_TEXT_BYTES = 16 * 1_024
    private const val MAX_SECRET_BYTES = 16 * 1_024
    private const val MAX_ITEMS = 4_096

    fun encode(connection: StoredSiteConnection): ByteArray {
        val bytes = WipingOutput()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            writeText(output, connection.provider.id)
            writeText(output, connection.name)
            output.writeBoolean(connection.credential != null)
            connection.credential?.let { secret ->
                val secretBytes = secret.utf8Bytes()
                try {
                    require(secretBytes.size <= MAX_SECRET_BYTES) { "The credential is too large." }
                    output.writeInt(secretBytes.size)
                    output.write(secretBytes)
                } finally {
                    secretBytes.fill(0)
                }
            }
            writeMap(output, connection.metadata)
            output.writeLong(connection.createdAtMillis)
            output.writeLong(connection.updatedAtMillis)
            output.writeBoolean(connection.cachedSnapshot != null)
            connection.cachedSnapshot?.let { writeSnapshot(output, it) }
            output.flush()
            return bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray, expectedProvider: SiteProvider, accountId: String): StoredSiteConnection =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported site connection version." }
            require(readText(input) == expectedProvider.id) { "The site record belongs to another provider." }
            val name = readText(input)
            val credential = if (input.readBoolean()) {
                val length = input.readInt()
                require(length in 1..MAX_SECRET_BYTES && length <= input.available()) { "Invalid credential length." }
                val secretBytes = ByteArray(length).also(input::readFully)
                try {
                    SecretValue.of(String(secretBytes, StandardCharsets.UTF_8))
                } finally {
                    secretBytes.fill(0)
                }
            } else {
                null
            }
            val metadata = readMap(input)
            val createdAt = input.readLong()
            val updatedAt = input.readLong()
            val snapshot = if (input.readBoolean()) readSnapshot(input, expectedProvider) else null
            require(input.available() == 0) { "Unexpected trailing site connection data." }
            StoredSiteConnection(accountId, expectedProvider, name, credential, metadata, createdAt, updatedAt, snapshot)
        }

    private fun writeSnapshot(output: DataOutputStream, snapshot: SiteSnapshot) {
        output.writeLong(snapshot.fetchedAtMillis)
        writeNullableText(output, snapshot.status)
        writeTexts(output, snapshot.warnings)
        writeMetrics(output, snapshot.metrics)
        writeCount(output, snapshot.resources.size)
        snapshot.resources.forEach { resource ->
            writeText(output, resource.id)
            writeText(output, resource.name)
            writeNullableText(output, resource.subtitle)
            writeNullableText(output, resource.url)
            writeNullableText(output, resource.status)
            output.writeBoolean(resource.updatedAtMillis != null)
            resource.updatedAtMillis?.let(output::writeLong)
            writeMetrics(output, resource.metrics)
            writeMap(output, resource.metadata)
        }
    }

    private fun readSnapshot(input: DataInputStream, provider: SiteProvider): SiteSnapshot {
        val fetchedAt = input.readLong()
        val status = readNullableText(input)
        val warnings = readTexts(input)
        val metrics = readMetrics(input)
        val resources = List(readCount(input)) {
            SiteResource(
                id = readText(input),
                provider = provider,
                name = readText(input),
                subtitle = readNullableText(input),
                url = readNullableText(input),
                status = readNullableText(input),
                updatedAtMillis = if (input.readBoolean()) input.readLong() else null,
                metrics = readMetrics(input),
                metadata = readMap(input),
            )
        }
        return SiteSnapshot(provider, resources, metrics, status, fetchedAt, warnings)
    }

    private fun writeMetrics(output: DataOutputStream, metrics: List<SiteMetric>) {
        writeCount(output, metrics.size)
        metrics.forEach { metric ->
            writeText(output, metric.key)
            writeText(output, metric.label)
            output.writeDouble(metric.value)
            writeText(output, metric.unit.name)
            writeNullableText(output, metric.formattedValue)
            writeNullableText(output, metric.resourceId)
        }
    }

    private fun readMetrics(input: DataInputStream): List<SiteMetric> = List(readCount(input)) {
        SiteMetric(
            key = readText(input),
            label = readText(input),
            value = input.readDouble(),
            unit = readText(input).let { name -> SiteMetricUnit.entries.firstOrNull { it.name == name } ?: SiteMetricUnit.NONE },
            formattedValue = readNullableText(input),
            resourceId = readNullableText(input),
        )
    }

    private fun writeMap(output: DataOutputStream, values: Map<String, String>) {
        writeCount(output, values.size)
        values.forEach { (key, value) ->
            writeText(output, key)
            writeText(output, value)
        }
    }

    private fun readMap(input: DataInputStream): Map<String, String> {
        val count = readCount(input)
        return LinkedHashMap<String, String>(count).also { map -> repeat(count) { map[readText(input)] = readText(input) } }
    }

    private fun writeTexts(output: DataOutputStream, values: List<String>) {
        writeCount(output, values.size)
        values.forEach { writeText(output, it) }
    }

    private fun readTexts(input: DataInputStream): List<String> = List(readCount(input)) { readText(input) }

    private fun writeCount(output: DataOutputStream, count: Int) {
        require(count in 0..MAX_ITEMS) { "Too many cached site values." }
        output.writeInt(count)
    }

    private fun readCount(input: DataInputStream): Int =
        input.readInt().also { require(it in 0..MAX_ITEMS) { "Invalid cached site count." } }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "A cached site value is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_TEXT_BYTES && length <= input.available()) { "Invalid cached site value." }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    private fun writeNullableText(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        value?.let { writeText(output, it) }
    }

    private fun readNullableText(input: DataInputStream): String? = if (input.readBoolean()) readText(input) else null

    private class WipingOutput : ByteArrayOutputStream() {
        override fun close() {
            buf.fill(0)
            reset()
            super.close()
        }
    }
}
