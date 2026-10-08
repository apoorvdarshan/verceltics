package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Non-secret identity of one saved account in a multi-account site service (iOS
 * `SiteIntegrationAccount` without its credential). Names and details are display labels only.
 */
data class SiteAccountEntry(
    val id: String,
    val name: String,
    val detail: String? = null,
) {
    init {
        require(SiteAccountIds.isValid(id)) { "Invalid site account id." }
        require(name.isNotBlank()) { "A site account needs a name." }
    }
}

/** Ordered saved accounts plus the active one (iOS `SiteStore.accounts` and `activeAccountID`). */
data class SiteAccountIndex(
    val accounts: List<SiteAccountEntry>,
    val activeId: String?,
) {
    init {
        require(accounts.size <= MAX_ACCOUNTS) { "Too many saved site accounts." }
        require(accounts.mapTo(HashSet(), SiteAccountEntry::id).size == accounts.size) { "Duplicate site account ids." }
        require(activeId == null || accounts.any { it.id == activeId }) { "The active site account is missing." }
        require(accounts.isEmpty() || activeId != null) { "Saved site accounts need an active account." }
    }

    val active: SiteAccountEntry? get() = activeId?.let(::entry)

    val ids: List<String> get() = accounts.map(SiteAccountEntry::id)

    fun entry(id: String): SiteAccountEntry? = accounts.firstOrNull { it.id == id }

    operator fun contains(id: String): Boolean = entry(id) != null

    /** Inserts a new account at the end or updates one in place, optionally making it active. */
    fun upserting(entry: SiteAccountEntry, activate: Boolean = true): SiteAccountIndex {
        val position = accounts.indexOfFirst { it.id == entry.id }
        val updated = if (position < 0) accounts + entry else accounts.toMutableList().also { it[position] = entry }
        return SiteAccountIndex(updated, if (activate || activeId == null) entry.id else activeId)
    }

    fun activating(id: String): SiteAccountIndex {
        require(id in this) { "Unknown site account." }
        return copy(activeId = id)
    }

    /** Removes an account; like iOS the first remaining account becomes active if needed. */
    fun removing(id: String): SiteAccountIndex {
        val remaining = accounts.filterNot { it.id == id }
        val active = activeId?.takeIf { current -> current != id && remaining.any { it.id == current } }
            ?: remaining.firstOrNull()?.id
        return SiteAccountIndex(remaining, active)
    }

    companion object {
        const val MAX_ACCOUNTS: Int = 64
        val EMPTY: SiteAccountIndex = SiteAccountIndex(emptyList(), null)
    }
}

object SiteAccountIds {
    private val PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private const val MAX_LABEL_CHARACTERS = 512

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * Stable id for the account migrated from a pre-multi-account record. Being deterministic, an
     * interrupted migration rewrites the same record instead of leaving an orphan behind.
     */
    fun migrated(domain: String): String =
        UUID.nameUUIDFromBytes("verceltics.site-account.migrated:$domain".toByteArray(StandardCharsets.UTF_8)).toString()

    /** Bounds a display label so index entries always fit their encrypted envelope. */
    fun label(value: String): String = value.trim().take(MAX_LABEL_CHARACTERS)
}

/** Plaintext codec for one account record; exists only next to authenticated encryption. */
interface SiteAccountRecordCodec<T> {
    fun encode(record: T): ByteArray

    fun decode(bytes: ByteArray, accountId: String): T
}

class SiteVersionedRecord<T>(
    val record: T,
    val revision: SiteConnectionRevision,
)

/**
 * Rollback material for one account write plus its index update. The previous envelopes stay
 * encrypted and are released as soon as the write is accepted or compensated.
 */
class SiteAccountCommit internal constructor(
    val accountId: String,
    internal val recordRevision: SiteConnectionRevision,
    internal val indexRevision: SiteConnectionRevision,
    previousRecord: ByteArray?,
    previousIndex: ByteArray?,
) {
    private var state = State.PENDING
    private var rollbackRecord: ByteArray? = previousRecord
    private var rollbackIndex: ByteArray? = previousIndex

    @Synchronized
    internal fun accept() {
        if (state != State.PENDING) return
        state = State.ACCEPTED
        wipe()
    }

    @Synchronized
    internal fun claimRollback(): Pair<ByteArray?, ByteArray?>? {
        if (state != State.PENDING) return null
        state = State.ROLLBACK_CLAIMED
        return (rollbackRecord to rollbackIndex).also {
            rollbackRecord = null
            rollbackIndex = null
        }
    }

    private fun wipe() {
        rollbackRecord?.fill(0)
        rollbackIndex?.fill(0)
        rollbackRecord = null
        rollbackIndex = null
    }

    override fun toString(): String = "SiteAccountCommit(accountId=$accountId, <redacted>)"

    private enum class State { PENDING, ACCEPTED, ROLLBACK_CLAIMED }
}

/**
 * Encrypted multi-account storage for one service: an account index (ids, labels, active id) and
 * one record per account. Every envelope binds its domain and account id into the authenticated
 * data, so a record can never be replayed into another account, service, or the index.
 */
class SiteAccountVault<T>(
    val domain: String,
    private val indexStore: AtomicBytesStore,
    private val recordStoreFor: (accountId: String) -> AtomicBytesStore,
    private val cipher: AccountCipher,
    private val codec: SiteAccountRecordCodec<T>,
) {
    init {
        require(DOMAIN.matches(domain)) { "Invalid site account domain." }
    }

    private val recordStores = HashMap<String, AtomicBytesStore>()

    /** Runs [block] while holding this vault's lock (reentrant for the vault's own methods). */
    fun <R> transaction(block: () -> R): R = synchronized(this) { block() }

    /** The saved index, or null when this service has never written one. Throws when unreadable. */
    @Synchronized
    fun readIndex(): SiteAccountIndex? {
        val envelope = indexStore.read() ?: return null
        return open(envelope, indexAssociatedData()) { SiteAccountIndexCodec.decode(it, domain) }
    }

    @Synchronized
    fun writeIndex(index: SiteAccountIndex) {
        val envelope = seal(SiteAccountIndexCodec.encode(index, domain), indexAssociatedData())
        try {
            indexStore.write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    /** Forgets an index that cannot be read, so the user can start over (records become orphans). */
    @Synchronized
    fun deleteIndex() = indexStore.delete()

    @Synchronized
    fun read(accountId: String): T? = readVersioned(accountId)?.record

    @Synchronized
    fun readVersioned(accountId: String): SiteVersionedRecord<T>? {
        val envelope = recordStore(accountId).read() ?: return null
        val revision = SiteConnectionRevision.of(envelope)
        return SiteVersionedRecord(open(envelope, recordAssociatedData(accountId)) { codec.decode(it, accountId) }, revision)
    }

    @Synchronized
    fun write(accountId: String, record: T) {
        val envelope = recordEnvelope(accountId, record)
        try {
            recordStore(accountId).write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    /** Compare-and-swap so a stale refresh can never resurrect or overwrite a newer record. */
    @Synchronized
    fun writeIfRevisionMatches(accountId: String, expected: SiteConnectionRevision, record: T): Boolean {
        val current = recordStore(accountId).read() ?: return false
        var replacement: ByteArray? = null
        return try {
            if (!expected.matches(current)) return false
            replacement = recordEnvelope(accountId, record)
            recordStore(accountId).write(replacement)
            true
        } finally {
            current.fill(0)
            replacement?.fill(0)
        }
    }

    @Synchronized
    fun delete(accountId: String) = recordStore(accountId).delete()

    /** Writes [record] and then [index], returning a handle that can undo both while current. */
    @Synchronized
    fun commit(accountId: String, record: T, index: SiteAccountIndex): SiteAccountCommit {
        var previousRecord = recordStore(accountId).read()
        var previousIndex = indexStore.read()
        var recordEnvelope: ByteArray? = null
        var indexEnvelope: ByteArray? = null
        return try {
            recordEnvelope = recordEnvelope(accountId, record)
            indexEnvelope = seal(SiteAccountIndexCodec.encode(index, domain), indexAssociatedData())
            recordStore(accountId).write(recordEnvelope)
            try {
                indexStore.write(indexEnvelope)
            } catch (error: Exception) {
                // Never leave a record behind that its index does not know about.
                runCatching { if (previousRecord == null) recordStore(accountId).delete() else recordStore(accountId).write(previousRecord) }
                throw error
            }
            SiteAccountCommit(
                accountId = accountId,
                recordRevision = SiteConnectionRevision.of(recordEnvelope),
                indexRevision = SiteConnectionRevision.of(indexEnvelope),
                previousRecord = previousRecord,
                previousIndex = previousIndex,
            ).also {
                previousRecord = null
                previousIndex = null
            }
        } finally {
            recordEnvelope?.fill(0)
            indexEnvelope?.fill(0)
            previousRecord?.fill(0)
            previousIndex?.fill(0)
        }
    }

    @Synchronized
    fun accept(commit: SiteAccountCommit) = commit.accept()

    /**
     * Compensates a cancelled [commit] only while its exact record revision is still current, so a
     * later connect, refresh, or removal is never undone. Returns whether anything was restored.
     */
    @Synchronized
    fun rollbackIfCurrent(commit: SiteAccountCommit): Boolean {
        val (previousRecord, previousIndex) = commit.claimRollback() ?: return false
        val currentRecord = recordStore(commit.accountId).read()
        val currentIndex = indexStore.read()
        return try {
            if (currentRecord == null || !commit.recordRevision.matches(currentRecord)) return false
            if (previousRecord == null) {
                recordStore(commit.accountId).delete()
            } else {
                recordStore(commit.accountId).write(previousRecord)
            }
            if (currentIndex != null && commit.indexRevision.matches(currentIndex)) {
                if (previousIndex == null) indexStore.delete() else indexStore.write(previousIndex)
            } else if (previousRecord == null) {
                // The index moved on (for example another account was activated); only forget the
                // account this commit created.
                readIndex()?.takeIf { commit.accountId in it }?.let { writeIndex(it.removing(commit.accountId)) }
            }
            true
        } finally {
            currentRecord?.fill(0)
            currentIndex?.fill(0)
            previousRecord?.fill(0)
            previousIndex?.fill(0)
        }
    }

    private fun recordStore(accountId: String): AtomicBytesStore {
        require(SiteAccountIds.isValid(accountId)) { "Invalid site account id." }
        return recordStores.getOrPut(accountId) { recordStoreFor(accountId) }
    }

    private fun recordEnvelope(accountId: String, record: T): ByteArray {
        require(SiteAccountIds.isValid(accountId)) { "Invalid site account id." }
        return seal(codec.encode(record), recordAssociatedData(accountId))
    }

    /** Encrypts and wipes [plaintext]. */
    private fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray = try {
        AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
    } finally {
        plaintext.fill(0)
        associatedData.fill(0)
    }

    private inline fun <R> open(envelope: ByteArray, associatedData: ByteArray, decode: (ByteArray) -> R): R {
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            decode(plaintext)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun indexAssociatedData(): ByteArray =
        "$ASSOCIATED_DATA_PREFIX$domain:accounts".toByteArray(StandardCharsets.UTF_8)

    private fun recordAssociatedData(accountId: String): ByteArray =
        "$ASSOCIATED_DATA_PREFIX$domain:account:$accountId".toByteArray(StandardCharsets.UTF_8)

    override fun toString(): String = "SiteAccountVault(domain=$domain)"

    companion object {
        internal const val ASSOCIATED_DATA_PREFIX = "verceltics.account-envelope.v1:"
        private val DOMAIN = Regex("[a-z0-9][a-zA-Z0-9.:-]{0,127}")
    }
}

/** A single-account record written by builds that predate multi-account support. */
interface SiteLegacyAccountSource<T> {
    /** The legacy record, or null when absent. Throws (and changes nothing) when unreadable. */
    fun load(): T?

    /** Copies secrets kept outside the record (Google OAuth slots) to the migrated account. */
    fun migrateSecrets(record: T, accountId: String) {}

    /** Removes the legacy record and any legacy secrets once the migrated index is durable. */
    fun delete()
}

/**
 * Returns the account index, first moving a legacy single-account record into the vault. The
 * migration is lossless and idempotent: the record and secrets are copied before the index is
 * written, and the legacy copy is deleted only after that, so an interrupted run simply repeats.
 * The migrated account becomes the active one, so existing users keep their connection.
 */
fun <T> SiteAccountVault<T>.migratedIndex(
    legacy: SiteLegacyAccountSource<T>,
    entry: (record: T, accountId: String) -> SiteAccountEntry,
    migratedId: (T) -> String = { SiteAccountIds.migrated(domain) },
    rekey: (record: T, accountId: String) -> T = { record, _ -> record },
): SiteAccountIndex = transaction {
    readIndex()?.let { existing ->
        // A crash between the index write and the legacy delete leaves a copy behind; finish it.
        runCatching(legacy::delete)
        return@transaction existing
    }
    val record = legacy.load() ?: return@transaction SiteAccountIndex.EMPTY
    val accountId = migratedId(record)
    val migrated = rekey(record, accountId)
    write(accountId, migrated)
    legacy.migrateSecrets(record, accountId)
    val index = SiteAccountIndex(listOf(entry(migrated, accountId)), accountId)
    writeIndex(index)
    runCatching(legacy::delete)
    index
}

/** Versioned binary index payload; never contains secrets. */
internal object SiteAccountIndexCodec {
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 4_096

    fun encode(index: SiteAccountIndex, domain: String): ByteArray {
        val bytes = WipingIndexOutput()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            writeText(output, domain)
            output.writeBoolean(index.activeId != null)
            index.activeId?.let { writeText(output, it) }
            output.writeInt(index.accounts.size)
            index.accounts.forEach { account ->
                writeText(output, account.id)
                writeText(output, account.name)
                output.writeBoolean(account.detail != null)
                account.detail?.let { writeText(output, it) }
            }
            output.flush()
            return bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray, domain: String): SiteAccountIndex =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported site account index version." }
            require(readText(input) == domain) { "The account index belongs to another service." }
            val activeId = if (input.readBoolean()) readText(input) else null
            val count = input.readInt()
            require(count in 0..SiteAccountIndex.MAX_ACCOUNTS) { "Invalid site account count." }
            val accounts = List(count) {
                SiteAccountEntry(
                    id = readText(input),
                    name = readText(input),
                    detail = if (input.readBoolean()) readText(input) else null,
                )
            }
            require(input.available() == 0) { "Unexpected trailing account index data." }
            SiteAccountIndex(accounts, activeId)
        }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES) { "An account label is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_TEXT_BYTES && length <= input.available()) { "Invalid account index value." }
        return String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
    }

    private class WipingIndexOutput : ByteArrayOutputStream() {
        override fun close() {
            buf.fill(0)
            reset()
            super.close()
        }
    }
}
