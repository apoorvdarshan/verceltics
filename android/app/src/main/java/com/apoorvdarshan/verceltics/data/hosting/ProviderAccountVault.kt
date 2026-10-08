package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Where one provider's saved accounts live (iOS keeps a list of accounts plus an active id).
 *
 * The pre-multi-account single record keeps its exact file, AAD and bytes as the
 * [PRIMARY_ACCOUNT_ID] account, so upgrading is lossless by construction: an existing user's
 * record is never rewritten, it simply becomes the first (and active) account. Further accounts get
 * their own file whose AAD binds both the provider domain and the account id, so an envelope copied
 * between accounts or providers fails authentication instead of being misread. The list of account
 * ids and the active id are kept in a separately encrypted index; while no index exists, a primary
 * record alone means "one account, active".
 */
class AccountVaultLayout(
    /** Provider storage domain, for example `hosting-railway` or `netlify-personal-token`. */
    val domain: String,
    /** The pre-multi-account record path, reused for [PRIMARY_ACCOUNT_ID]. */
    val primaryPath: String,
    /** The pre-multi-account record AAD, reused for [PRIMARY_ACCOUNT_ID]. */
    val primaryAssociatedData: String,
) {
    init {
        require(DOMAIN.matches(domain)) { "Invalid account vault domain." }
    }

    val indexPath: String get() = "accounts/$domain.index"

    val indexAssociatedData: String get() = "verceltics.account-index.v1:$domain"

    fun recordPath(accountId: String): String {
        requireAccountId(accountId)
        return if (accountId == PRIMARY_ACCOUNT_ID) primaryPath else "accounts/$domain/$accountId.account"
    }

    fun recordAssociatedData(accountId: String): String {
        requireAccountId(accountId)
        return if (accountId == PRIMARY_ACCOUNT_ID) {
            primaryAssociatedData
        } else {
            "verceltics.account-envelope.v2:$domain:$accountId"
        }
    }

    override fun toString(): String = "AccountVaultLayout(domain=$domain)"

    companion object {
        /** The account id of the record that existed before multi-account support. */
        const val PRIMARY_ACCOUNT_ID: String = "primary"
        private val DOMAIN = Regex("[a-z0-9][a-zA-Z0-9.-]{0,63}")
        private val ACCOUNT_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")

        fun isValidAccountId(value: String): Boolean = ACCOUNT_ID.matches(value)

        fun requireAccountId(value: String) {
            require(isValidAccountId(value)) { "Invalid saved account id." }
        }
    }
}

/** Revision of one encrypted account record: the account it belongs to and its envelope digest. */
class AccountRecordRevision private constructor(
    val accountId: String,
    private val digest: ByteArray,
) {
    internal fun matches(envelope: ByteArray): Boolean {
        val candidate = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(envelope)
        return try {
            MessageDigest.isEqual(digest, candidate)
        } finally {
            candidate.fill(0)
        }
    }

    override fun toString(): String = "AccountRecordRevision(accountId=$accountId, <redacted>)"

    companion object {
        private const val DIGEST_ALGORITHM = "SHA-256"

        internal fun of(accountId: String, envelope: ByteArray): AccountRecordRevision =
            AccountRecordRevision(accountId, MessageDigest.getInstance(DIGEST_ALGORITHM).digest(envelope))
    }
}

/** One decrypted record plus the revision that produced it. */
class AccountVaultEntry<T>(
    val accountId: String,
    val value: T,
    val revision: AccountRecordRevision,
) {
    override fun toString(): String = "AccountVaultEntry(accountId=$accountId, <redacted>)"
}

/** The saved account ids, in the order they were added, and the active one. */
data class AccountVaultIndex(
    val accountIds: List<String>,
    val activeAccountId: String?,
) {
    init {
        require(accountIds.size == accountIds.distinct().size) { "Duplicate saved account ids." }
        require(activeAccountId == null || activeAccountId in accountIds) { "The active account is not saved." }
    }

    companion object {
        val EMPTY = AccountVaultIndex(emptyList(), null)
    }
}

/** One listed account: decrypted, or present but unreadable (never deleted on that basis). */
sealed interface AccountVaultRecord<out T> {
    val accountId: String

    class Readable<T>(val entry: AccountVaultEntry<T>) : AccountVaultRecord<T> {
        override val accountId: String get() = entry.accountId
    }

    data class Unreadable(
        override val accountId: String,
        /** True when the Keystore refused (device locked); false for a corrupt or foreign record. */
        val secureStorageUnavailable: Boolean,
    ) : AccountVaultRecord<Nothing>
}

/**
 * Opaque rollback state for one pending validated save. It restores both the account record and
 * the index exactly as they were, but only while this save is still the current revision.
 */
class AccountVaultCommit internal constructor(
    val accountId: String,
    /** True when the save created a new account rather than rotating an existing one. */
    val isNewAccount: Boolean,
    internal val recordRevision: AccountRecordRevision,
    previousRecordEnvelope: ByteArray?,
    internal val indexRevision: AccountRecordRevision,
    previousIndexEnvelope: ByteArray?,
) {
    private var state = State.PENDING
    private var previousRecord: ByteArray? = previousRecordEnvelope
    private var previousIndex: ByteArray? = previousIndexEnvelope

    @Synchronized
    internal fun accept() {
        if (state != State.PENDING) return
        state = State.ACCEPTED
        wipe()
    }

    /** Returns `[previousRecord, previousIndex]` (null meaning "absent"), exactly once. */
    @Synchronized
    internal fun claimRollback(): Array<ByteArray?>? {
        if (state != State.PENDING) return null
        state = State.ROLLBACK_CLAIMED
        return arrayOf(previousRecord, previousIndex).also {
            previousRecord = null
            previousIndex = null
        }
    }

    private fun wipe() {
        previousRecord?.fill(0)
        previousIndex?.fill(0)
        previousRecord = null
        previousIndex = null
    }

    override fun toString(): String = "AccountVaultCommit(accountId=$accountId, isNewAccount=$isNewAccount, <redacted>)"

    private enum class State {
        PENDING,
        ACCEPTED,
        ROLLBACK_CLAIMED,
    }
}

/**
 * Encrypted, no-backup storage for every saved account of one provider, plus the active account.
 *
 * Writes go through [AtomicBytesStore], so each file is replaced atomically. A new account's record
 * is written before the index that references it and an account's record is deleted before the
 * index stops referencing it, so a crash can at worst leave an index entry whose record is gone;
 * such entries are skipped and pruned by the next index write.
 */
class ProviderAccountVault<T : Any>(
    val layout: AccountVaultLayout,
    private val storeFactory: (relativePath: String) -> AtomicBytesStore,
    private val cipher: AccountCipher,
    private val encode: (T) -> ByteArray,
    private val decode: (accountId: String, plaintext: ByteArray) -> T,
    private val newAccountId: () -> String = { UUID.randomUUID().toString() },
    /**
     * Account ids whose record files exist on disk (production lists `accounts/<domain>/`). Remove
     * All uses it so records survive neither an unreadable index nor an interrupted earlier write.
     */
    private val storedAccountIds: () -> List<String> = { emptyList() },
) {
    private val stores = mutableMapOf<String, AtomicBytesStore>()
    private var pendingCommit: AccountVaultCommit? = null

    @Synchronized
    fun index(): AccountVaultIndex = readIndex()

    @Synchronized
    fun activeAccountId(): String? = activeEntryId(readIndex())

    /** The active account's record, falling back to the next saved record if it went missing. */
    @Synchronized
    fun loadActive(): AccountVaultEntry<T>? {
        val index = readIndex()
        val ordered = listOfNotNull(index.activeAccountId) + index.accountIds.filter { it != index.activeAccountId }
        ordered.forEach { id -> loadEntry(id)?.let { return it } }
        return null
    }

    @Synchronized
    fun load(accountId: String): AccountVaultEntry<T>? {
        if (accountId !in readIndex().accountIds) return null
        return loadEntry(accountId)
    }

    /** Every saved account in index order; unreadable records are listed, never dropped. */
    @Synchronized
    fun records(): List<AccountVaultRecord<T>> = readIndex().accountIds.mapNotNull { id ->
        try {
            loadEntry(id)?.let { AccountVaultRecord.Readable(it) }
        } catch (_: SecurityException) {
            AccountVaultRecord.Unreadable(id, secureStorageUnavailable = true)
        } catch (_: Exception) {
            AccountVaultRecord.Unreadable(id, secureStorageUnavailable = false)
        }
    }

    /**
     * Direct save without rollback state, used by tests and migrations. Replaces [accountId] (the
     * active account by default) or creates the first account, and makes it active.
     */
    @Synchronized
    fun save(value: T, accountId: String? = activeAccountId()): String {
        check(pendingCommit == null) { "A saved account replacement is already pending." }
        val index = readIndex()
        val id = accountId?.takeIf { it in index.accountIds } ?: allocateAccountId(index)
        check(id in index.accountIds || index.accountIds.size < MAX_ACCOUNTS) {
            "Remove an account before adding another one."
        }
        writeRecord(id, value)
        writeIndex(index.including(id).copy(activeAccountId = id))
        return id
    }

    /**
     * Saves a validated account and makes it active. [accountId] rotates an existing account in
     * place; null adds a new account. The returned commit must be accepted or rolled back.
     */
    @Synchronized
    fun saveWithRevision(accountId: String?, value: T): AccountVaultCommit {
        check(pendingCommit == null) { "A saved account replacement is already pending." }
        val index = readIndex()
        require(accountId == null || accountId in index.accountIds) { "The saved account no longer exists." }
        val id = accountId ?: allocateAccountId(index)
        val isNew = id !in index.accountIds
        check(!isNew || index.accountIds.size < MAX_ACCOUNTS) { "Remove an account before adding another one." }
        val recordStore = store(layout.recordPath(id))
        val indexStore = store(layout.indexPath)
        var previousRecord: ByteArray? = recordStore.read()
        var previousIndex: ByteArray? = indexStore.read()
        var recordEnvelope: ByteArray? = null
        var indexEnvelope: ByteArray? = null
        var recordWritten = false
        return try {
            recordEnvelope = encryptRecord(id, value)
            recordStore.write(recordEnvelope)
            recordWritten = true
            indexEnvelope = encryptIndex(index.including(id).copy(activeAccountId = id))
            indexStore.write(indexEnvelope)
            AccountVaultCommit(
                accountId = id,
                isNewAccount = isNew,
                recordRevision = AccountRecordRevision.of(id, recordEnvelope),
                previousRecordEnvelope = previousRecord,
                indexRevision = AccountRecordRevision.of(INDEX_REVISION_ID, indexEnvelope),
                previousIndexEnvelope = previousIndex,
            ).also { commit ->
                pendingCommit = commit
                previousRecord = null
                previousIndex = null
            }
        } catch (error: Exception) {
            if (recordWritten) {
                runCatching {
                    val previous = previousRecord
                    if (previous == null) recordStore.delete() else recordStore.write(previous)
                }
            }
            throw error
        } finally {
            recordEnvelope?.fill(0)
            indexEnvelope?.fill(0)
            previousRecord?.fill(0)
            previousIndex?.fill(0)
        }
    }

    /** Compare-and-swap for refreshes: stale work can never resurrect or replace a newer record. */
    @Synchronized
    fun saveIfRevisionMatches(expected: AccountRecordRevision, value: T): Boolean {
        val id = expected.accountId
        if (pendingCommit?.accountId == id) return false
        if (id !in readIndex().accountIds) return false
        val recordStore = store(layout.recordPath(id))
        val current = recordStore.read() ?: return false
        var replacement: ByteArray? = null
        return try {
            if (!expected.matches(current)) return false
            replacement = encryptRecord(id, value)
            recordStore.write(replacement)
            true
        } finally {
            current.fill(0)
            replacement?.fill(0)
        }
    }

    @Synchronized
    fun accept(commit: AccountVaultCommit) {
        if (pendingCommit === commit) pendingCommit = null
        commit.accept()
    }

    /**
     * Restores the record (and index) replaced by [commit] only while that exact replacement is
     * still current; a newer connection, refresh or removal is never overwritten.
     */
    @Synchronized
    fun rollbackIfRevisionMatches(commit: AccountVaultCommit): Boolean {
        if (pendingCommit !== commit) return false
        val rollback = commit.claimRollback() ?: return false
        pendingCommit = null
        val previousRecord = rollback[0]
        val previousIndex = rollback[1]
        val recordStore = store(layout.recordPath(commit.accountId))
        val indexStore = store(layout.indexPath)
        val currentRecord = recordStore.read()
        var currentIndex: ByteArray? = null
        return try {
            if (currentRecord == null || !commit.recordRevision.matches(currentRecord)) return false
            if (previousRecord == null) recordStore.delete() else recordStore.write(previousRecord)
            currentIndex = indexStore.read()
            if (currentIndex != null && commit.indexRevision.matches(currentIndex)) {
                if (previousIndex == null) indexStore.delete() else indexStore.write(previousIndex)
            } else if (commit.isNewAccount) {
                // Someone switched or removed accounts meanwhile: keep their index, minus ours.
                val index = readIndex()
                if (commit.accountId in index.accountIds) writeIndex(index.excluding(commit.accountId))
            }
            true
        } finally {
            currentRecord?.fill(0)
            currentIndex?.fill(0)
            previousRecord?.fill(0)
            previousIndex?.fill(0)
        }
    }

    /** Makes [accountId] the active account. Returns false when it is not saved. */
    @Synchronized
    fun activate(accountId: String): Boolean {
        val index = readIndex()
        if (accountId !in index.accountIds) return false
        if (index.activeAccountId != accountId) writeIndex(index.copy(activeAccountId = accountId))
        return true
    }

    /** Removes one account. Returns the new active account id (the first remaining, iOS order). */
    @Synchronized
    fun delete(accountId: String): String? {
        AccountVaultLayout.requireAccountId(accountId)
        pendingCommit?.takeIf { it.accountId == accountId }?.let { commit ->
            commit.accept()
            pendingCommit = null
        }
        val index = readIndex()
        store(layout.recordPath(accountId)).delete()
        if (accountId !in index.accountIds) return activeEntryId(index)
        val remaining = index.excluding(accountId)
        writeIndex(remaining)
        return remaining.activeAccountId
    }

    /**
     * Removes every saved account of this provider (iOS "Remove All Accounts"), including records
     * the index no longer (or cannot) name. Returns the ids that were removed.
     */
    @Synchronized
    fun deleteAll(): List<String> {
        pendingCommit?.accept()
        pendingCommit = null
        val indexed = runCatching { readIndex().accountIds }.getOrDefault(emptyList())
        val onDisk = runCatching { storedAccountIds() }.getOrDefault(emptyList())
            .filter(AccountVaultLayout::isValidAccountId)
        val ids = (listOf(AccountVaultLayout.PRIMARY_ACCOUNT_ID) + indexed + onDisk).distinct()
        ids.forEach { id -> store(layout.recordPath(id)).delete() }
        store(layout.indexPath).delete()
        return ids
    }

    /** Resolves and removes the active account; returns the new active id. */
    @Synchronized
    fun deleteActive(): String? {
        val active = activeAccountId() ?: return null
        return delete(active)
    }

    private fun activeEntryId(index: AccountVaultIndex): String? = index.activeAccountId ?: index.accountIds.firstOrNull()

    private fun loadEntry(accountId: String): AccountVaultEntry<T>? {
        val envelope = store(layout.recordPath(accountId)).read() ?: return null
        val associatedData = layout.recordAssociatedData(accountId).toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            val revision = AccountRecordRevision.of(accountId, envelope)
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            AccountVaultEntry(accountId, decode(accountId, plaintext), revision)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun readIndex(): AccountVaultIndex {
        val envelope = store(layout.indexPath).read()
        if (envelope == null) {
            val primary = store(layout.primaryPath).read() ?: return AccountVaultIndex.EMPTY
            primary.fill(0)
            return AccountVaultIndex(listOf(AccountVaultLayout.PRIMARY_ACCOUNT_ID), AccountVaultLayout.PRIMARY_ACCOUNT_ID)
        }
        val associatedData = layout.indexAssociatedData.toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            AccountVaultIndexCodec.decode(plaintext)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun writeIndex(index: AccountVaultIndex) {
        val indexStore = store(layout.indexPath)
        if (index.accountIds.isEmpty()) {
            indexStore.delete()
            return
        }
        val envelope = encryptIndex(index)
        try {
            indexStore.write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    private fun writeRecord(accountId: String, value: T) {
        val envelope = encryptRecord(accountId, value)
        try {
            store(layout.recordPath(accountId)).write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    private fun encryptRecord(accountId: String, value: T): ByteArray {
        val plaintext = encode(value)
        val associatedData = layout.recordAssociatedData(accountId).toByteArray(StandardCharsets.UTF_8)
        return try {
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
    }

    private fun encryptIndex(index: AccountVaultIndex): ByteArray {
        val plaintext = AccountVaultIndexCodec.encode(index)
        val associatedData = layout.indexAssociatedData.toByteArray(StandardCharsets.UTF_8)
        return try {
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
    }

    /** The legacy primary slot is reused whenever it is free, so one account keeps one file. */
    private fun allocateAccountId(index: AccountVaultIndex): String {
        if (AccountVaultLayout.PRIMARY_ACCOUNT_ID !in index.accountIds) return AccountVaultLayout.PRIMARY_ACCOUNT_ID
        repeat(8) {
            val candidate = newAccountId().lowercase()
            if (AccountVaultLayout.isValidAccountId(candidate) && candidate !in index.accountIds) return candidate
        }
        throw IllegalStateException("Could not allocate a saved account id.")
    }

    private fun store(path: String): AtomicBytesStore = stores.getOrPut(path) { storeFactory(path) }

    private fun AccountVaultIndex.including(accountId: String): AccountVaultIndex =
        if (accountId in accountIds) this else copy(accountIds = accountIds + accountId)

    private fun AccountVaultIndex.excluding(accountId: String): AccountVaultIndex {
        val remaining = accountIds - accountId
        val active = activeAccountId?.takeIf { it != accountId && it in remaining } ?: remaining.firstOrNull()
        return AccountVaultIndex(remaining, active)
    }

    override fun toString(): String = "ProviderAccountVault(domain=${layout.domain})"

    companion object {
        /** Saved accounts per provider; generous next to iOS, which has no cap but a small menu. */
        const val MAX_ACCOUNTS: Int = 32
        private const val INDEX_REVISION_ID = "index"
    }
}

internal object AccountVaultIndexCodec {
    private const val VERSION = 1
    private const val MAX_ID_BYTES = 128

    fun encode(index: AccountVaultIndex): ByteArray {
        require(index.accountIds.size <= ProviderAccountVault.MAX_ACCOUNTS) { "Too many saved accounts." }
        val bytes = WipingOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeInt(index.accountIds.size)
            index.accountIds.forEach { writeId(output, it) }
            output.writeBoolean(index.activeAccountId != null)
            index.activeAccountId?.let { writeId(output, it) }
            output.flush()
            return bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): AccountVaultIndex = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == VERSION) { "Unsupported saved account index version." }
        val count = input.readInt()
        require(count in 0..ProviderAccountVault.MAX_ACCOUNTS) { "Invalid saved account count." }
        val ids = List(count) { readId(input) }
        val active = if (input.readBoolean()) readId(input) else null
        require(input.available() == 0) { "Unexpected trailing saved account index data." }
        AccountVaultIndex(ids, active?.takeIf { it in ids } ?: ids.firstOrNull())
    }

    private fun writeId(output: DataOutputStream, id: String) {
        AccountVaultLayout.requireAccountId(id)
        val bytes = id.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_ID_BYTES) { "Saved account id is too long." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readId(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 1..MAX_ID_BYTES && length <= input.available()) { "Invalid saved account id length." }
        val id = String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
        AccountVaultLayout.requireAccountId(id)
        return id
    }

    private class WipingOutputStream : ByteArrayOutputStream() {
        override fun close() {
            buf.fill(0)
            reset()
            super.close()
        }
    }
}

/** Lists the account ids stored under `noBackupFilesDir/accounts/<domain>/` (production vaults). */
internal fun noBackupStoredAccountIds(noBackupRoot: java.io.File, layout: AccountVaultLayout): List<String> =
    java.io.File(noBackupRoot, "accounts/${layout.domain}").listFiles().orEmpty()
        .map { it.name }
        .filter { it.endsWith(".account") }
        .map { it.removeSuffix(".account") }
        .filter(AccountVaultLayout::isValidAccountId)
