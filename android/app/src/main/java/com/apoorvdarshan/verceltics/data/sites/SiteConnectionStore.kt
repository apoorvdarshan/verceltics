package com.apoorvdarshan.verceltics.data.sites

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * One saved site-service connection. [credential] is null for Google providers, whose tokens
 * live in their `GoogleOAuthSession` slot. [metadata] never contains secrets.
 */
class StoredSiteConnection(
    val provider: SiteProvider,
    val name: String,
    val credential: SecretValue?,
    val metadata: Map<String, String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val cachedSnapshot: SiteSnapshot?,
) {
    init {
        require(name.isNotBlank()) { "A site connection needs a name." }
        require(provider.usesGoogleOAuth || credential != null) { "${provider.displayName} needs a credential." }
    }

    fun withSnapshot(name: String, snapshot: SiteSnapshot?, metadata: Map<String, String>, updatedAtMillis: Long) =
        StoredSiteConnection(provider, name, credential, metadata, createdAtMillis, updatedAtMillis, snapshot)

    override fun toString(): String =
        "StoredSiteConnection(provider=${provider.id}, name=$name, credential=" +
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

/**
 * Encrypted site-service records. Each provider has its own no-backup file and authenticated-data
 * domain, so one provider's large cached inventory can never push another over the envelope limit
 * and a record cannot be replayed into another provider's slot.
 */
class SiteConnectionRepository(
    private val storeFor: (SiteProvider) -> AtomicBytesStore,
    private val cipher: AccountCipher,
) {
    private val stores = HashMap<SiteProvider, AtomicBytesStore>()

    @Synchronized
    fun load(provider: SiteProvider): StoredSiteConnection? = loadWithRevision(provider)?.connection

    @Synchronized
    fun loadWithRevision(provider: SiteProvider): SiteVersionedConnection? {
        val envelope = store(provider).read() ?: return null
        val associatedData = associatedData(provider)
        var plaintext: ByteArray? = null
        return try {
            val revision = SiteConnectionRevision.of(envelope)
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            SiteVersionedConnection(SiteConnectionPayloadCodec.decode(plaintext, provider), revision)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    @Synchronized
    fun save(connection: StoredSiteConnection) {
        val envelope = envelope(connection)
        try {
            store(connection.provider).write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    /** Compare-and-swap so a stale refresh can never resurrect or overwrite a newer record. */
    @Synchronized
    fun saveIfRevisionMatches(expected: SiteConnectionRevision, connection: StoredSiteConnection): Boolean {
        val current = store(connection.provider).read() ?: return false
        var replacement: ByteArray? = null
        return try {
            if (!expected.matches(current)) return false
            replacement = envelope(connection)
            store(connection.provider).write(replacement)
            true
        } finally {
            current.fill(0)
            replacement?.fill(0)
        }
    }

    @Synchronized
    fun delete(provider: SiteProvider) = store(provider).delete()

    private fun store(provider: SiteProvider): AtomicBytesStore = stores.getOrPut(provider) { storeFor(provider) }

    private fun envelope(connection: StoredSiteConnection): ByteArray {
        val plaintext = SiteConnectionPayloadCodec.encode(connection)
        val associatedData = associatedData(connection.provider)
        return try {
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
    }

    private fun associatedData(provider: SiteProvider): ByteArray =
        "$ASSOCIATED_DATA_PREFIX${provider.id}".toByteArray(StandardCharsets.UTF_8)

    companion object {
        internal const val ASSOCIATED_DATA_PREFIX = "verceltics.account-envelope.v1:site-service:"
        internal const val KEY_ALIAS = "verceltics.account-storage.site-services.v1"

        fun accountPath(provider: SiteProvider): String = "accounts/site-services/${provider.id}.account"

        fun create(context: Context): SiteConnectionRepository {
            val applicationContext = context.applicationContext
            return SiteConnectionRepository(
                storeFor = { provider -> NoBackupAtomicFileStore(applicationContext, accountPath(provider)) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}

sealed interface SiteRestoreResult {
    data object NotConnected : SiteRestoreResult

    data class Restored(
        val provider: SiteProvider,
        val name: String,
        val metadata: Map<String, String>,
        val cachedSnapshot: SiteSnapshot?,
        val cacheIsStale: Boolean,
    ) : SiteRestoreResult

    data class Unavailable(val problem: SiteRestoreProblem) : SiteRestoreResult
}

enum class SiteRestoreProblem {
    SECURE_STORAGE_UNAVAILABLE,
    SAVED_RECORD_UNREADABLE,
}

/** Offline restore, validated saves, compare-and-swap refreshes, and explicit disconnects. */
class SiteConnectionStore(
    private val repository: SiteConnectionRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun restore(provider: SiteProvider): SiteRestoreResult = try {
        val connection = repository.load(provider)
        if (connection == null) {
            SiteRestoreResult.NotConnected
        } else {
            val snapshot = connection.cachedSnapshot
            SiteRestoreResult.Restored(
                provider = provider,
                name = connection.name,
                metadata = connection.metadata,
                cachedSnapshot = snapshot,
                cacheIsStale = snapshot == null ||
                    nowMillis() - snapshot.fetchedAtMillis >= provider.snapshotCacheLifetimeMillis,
            )
        }
    } catch (_: SecurityException) {
        SiteRestoreResult.Unavailable(SiteRestoreProblem.SECURE_STORAGE_UNAVAILABLE)
    } catch (_: Exception) {
        SiteRestoreResult.Unavailable(SiteRestoreProblem.SAVED_RECORD_UNREADABLE)
    }

    /** Backend-only access for requests; UI-facing restore never receives the credential. */
    fun loadForRequest(provider: SiteProvider): SiteVersionedConnection? = repository.loadWithRevision(provider)

    fun saveValidatedConnection(
        provider: SiteProvider,
        name: String,
        credential: SecretValue?,
        metadata: Map<String, String>,
        snapshot: SiteSnapshot,
    ): StoredSiteConnection {
        val now = nowMillis()
        val existing = runCatching { repository.load(provider) }.getOrNull()
        val connection = StoredSiteConnection(
            provider = provider,
            name = name,
            credential = credential,
            metadata = metadata,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
            cachedSnapshot = forOfflineCache(snapshot),
        )
        repository.save(fittedToEnvelope(connection))
        return connection
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
        val updated = existing.withSnapshot(
            name = name,
            snapshot = forOfflineCache(snapshot),
            metadata = existing.metadata + discoveredMetadata,
            updatedAtMillis = nowMillis(),
        )
        return repository.saveIfRevisionMatches(source.revision, fittedToEnvelope(updated))
    }

    fun disconnect(provider: SiteProvider) = repository.delete(provider)

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

    fun decode(bytes: ByteArray, expectedProvider: SiteProvider): StoredSiteConnection =
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
            StoredSiteConnection(expectedProvider, name, credential, metadata, createdAt, updatedAt, snapshot)
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
