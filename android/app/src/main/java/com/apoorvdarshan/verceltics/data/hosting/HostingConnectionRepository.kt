package com.apoorvdarshan.verceltics.data.hosting

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** In-memory identity of one encrypted hosting record revision. */
internal class HostingRecordRevision private constructor(private val digest: ByteArray) {
    fun matches(envelope: ByteArray): Boolean {
        val candidate = MessageDigest.getInstance("SHA-256").digest(envelope)
        return try {
            MessageDigest.isEqual(digest, candidate)
        } finally {
            candidate.fill(0)
        }
    }

    override fun toString(): String = "HostingRecordRevision(<redacted>)"

    companion object {
        fun from(envelope: ByteArray) = HostingRecordRevision(MessageDigest.getInstance("SHA-256").digest(envelope))
    }
}

/** Opaque encrypted rollback state for one pending replacement in one provider slot. */
internal class HostingRecordCommit(
    val provider: HostingProvider,
    val revision: HostingRecordRevision,
    previousEnvelope: ByteArray?,
) {
    private var state = State.PENDING
    private var rollbackEnvelope: ByteArray? = previousEnvelope

    @Synchronized
    fun accept(): ByteArray? {
        if (state != State.PENDING) return null
        state = State.ACCEPTED
        return rollbackEnvelope.also { rollbackEnvelope = null }
    }

    /** Returns the envelope to restore (null bytes meaning "the slot was empty"), once. */
    @Synchronized
    fun claimRollback(): Array<ByteArray?>? {
        if (state != State.PENDING) return null
        state = State.ROLLBACK_CLAIMED
        return arrayOf(rollbackEnvelope).also { rollbackEnvelope = null }
    }

    override fun toString(): String = "HostingRecordCommit(provider=${provider.id}, <redacted>)"

    private enum class State {
        PENDING,
        ACCEPTED,
        ROLLBACK_CLAIMED,
    }
}

internal class HostingVersionedConnection(
    val connection: HostingStoredConnection,
    val revision: HostingRecordRevision,
)

/**
 * One encrypted, no-backup storage slot per hosting provider, sharing a single Keystore key.
 * Each slot's AAD names its provider, so an envelope copied into another slot fails to decrypt.
 * Several providers can be connected at once; each slot holds one account.
 */
class HostingConnectionRepository(
    storeFactory: (HostingProvider) -> AtomicBytesStore,
    private val cipher: AccountCipher,
) {
    private val stores: Map<HostingProvider, AtomicBytesStore> = HostingProvider.entries.associateWith(storeFactory)
    private val pendingCommits = mutableMapOf<HostingProvider, HostingRecordCommit>()

    @Synchronized
    fun load(provider: HostingProvider): HostingStoredConnection? = loadWithRevision(provider)?.connection

    @Synchronized
    internal fun loadWithRevision(provider: HostingProvider): HostingVersionedConnection? {
        val envelope = store(provider).read() ?: return null
        val associatedData = associatedData(provider).toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            val revision = HostingRecordRevision.from(envelope)
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            HostingVersionedConnection(HostingConnectionPayloadCodec.decode(plaintext, provider), revision)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    /** Direct replacement, used by tests and migrations. Connect flows use [saveWithRevision]. */
    @Synchronized
    fun save(connection: HostingStoredConnection) {
        val provider = connection.account.provider
        check(pendingCommits[provider] == null) { "A hosting connection replacement is already pending." }
        val envelope = encryptedEnvelope(connection)
        try {
            store(provider).write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    @Synchronized
    internal fun saveWithRevision(connection: HostingStoredConnection): HostingRecordCommit {
        val provider = connection.account.provider
        check(pendingCommits[provider] == null) { "A hosting connection replacement is already pending." }
        var previousEnvelope = store(provider).read()
        var envelope: ByteArray? = null
        return try {
            envelope = encryptedEnvelope(connection)
            store(provider).write(envelope)
            HostingRecordCommit(provider, HostingRecordRevision.from(envelope), previousEnvelope).also {
                pendingCommits[provider] = it
                previousEnvelope = null
            }
        } finally {
            envelope?.fill(0)
            previousEnvelope?.fill(0)
        }
    }

    /** Compare-and-swap for refreshes, so stale work cannot resurrect or replace a newer record. */
    @Synchronized
    internal fun saveIfRevisionMatches(
        expectedRevision: HostingRecordRevision,
        connection: HostingStoredConnection,
    ): Boolean {
        val provider = connection.account.provider
        if (pendingCommits[provider] != null) return false
        val current = store(provider).read() ?: return false
        var replacement: ByteArray? = null
        return try {
            if (!expectedRevision.matches(current)) return false
            replacement = encryptedEnvelope(connection)
            store(provider).write(replacement)
            true
        } finally {
            current.fill(0)
            replacement?.fill(0)
        }
    }

    @Synchronized
    internal fun accept(commit: HostingRecordCommit) {
        if (pendingCommits[commit.provider] === commit) pendingCommits.remove(commit.provider)
        commit.accept()?.fill(0)
    }

    /** Restores the prior envelope only while this exact replacement is still current. */
    @Synchronized
    internal fun rollbackIfRevisionMatches(commit: HostingRecordCommit): Boolean {
        if (pendingCommits[commit.provider] !== commit) return false
        val rollback = commit.claimRollback() ?: return false
        pendingCommits.remove(commit.provider)
        val previous = rollback[0]
        val store = store(commit.provider)
        val current = store.read()
        return try {
            if (current == null || !commit.revision.matches(current)) {
                false
            } else {
                if (previous == null) store.delete() else store.write(previous)
                true
            }
        } finally {
            current?.fill(0)
            previous?.fill(0)
        }
    }

    /** Only an explicit user disconnect should erase a slot. */
    @Synchronized
    fun delete(provider: HostingProvider) {
        pendingCommits.remove(provider)?.accept()?.fill(0)
        store(provider).delete()
    }

    private fun store(provider: HostingProvider): AtomicBytesStore = checkNotNull(stores[provider])

    private fun encryptedEnvelope(connection: HostingStoredConnection): ByteArray {
        val provider = connection.account.provider
        val plaintext = HostingConnectionPayloadCodec.encode(connection)
        val associatedData = associatedData(provider).toByteArray(StandardCharsets.UTF_8)
        return try {
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
    }

    companion object {
        internal const val KEY_ALIAS = "verceltics.account-storage.hosting.v1"

        internal fun accountPath(provider: HostingProvider): String = "accounts/hosting-${provider.id}.account"

        internal fun associatedData(provider: HostingProvider): String =
            "verceltics.account-envelope.v1:hosting-${provider.id}"

        fun create(context: Context): HostingConnectionRepository {
            val applicationContext = context.applicationContext
            return HostingConnectionRepository(
                storeFactory = { provider -> NoBackupAtomicFileStore(applicationContext, accountPath(provider)) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}
