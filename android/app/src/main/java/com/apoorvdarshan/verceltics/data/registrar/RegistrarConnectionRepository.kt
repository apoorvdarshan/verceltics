package com.apoorvdarshan.verceltics.data.registrar

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** In-memory identity for one encrypted registrar record revision. */
internal class RegistrarRecordRevision private constructor(
    private val digest: ByteArray,
) {
    fun matches(envelope: ByteArray): Boolean {
        val candidate = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(envelope)
        return try {
            MessageDigest.isEqual(digest, candidate)
        } finally {
            candidate.fill(0)
        }
    }

    override fun toString(): String = "RegistrarRecordRevision(<redacted>)"

    companion object {
        private const val DIGEST_ALGORITHM = "SHA-256"

        fun from(envelope: ByteArray): RegistrarRecordRevision = RegistrarRecordRevision(
            MessageDigest.getInstance(DIGEST_ALGORITHM).digest(envelope),
        )
    }
}

/** Opaque encrypted rollback state for one pending replacement of a registrar slot. */
internal class RegistrarRecordCommit(
    val provider: RegistrarProvider,
    val revision: RegistrarRecordRevision,
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

    @Synchronized
    fun claimRollback(): RegistrarRollbackEnvelope? {
        if (state != State.PENDING) return null
        state = State.ROLLBACK_CLAIMED
        return RegistrarRollbackEnvelope(rollbackEnvelope).also { rollbackEnvelope = null }
    }

    override fun toString(): String = "RegistrarRecordCommit(provider=${provider.id}, <redacted>)"

    private enum class State {
        PENDING,
        ACCEPTED,
        ROLLBACK_CLAIMED,
    }
}

internal class RegistrarRollbackEnvelope(val bytes: ByteArray?) {
    override fun toString(): String = "RegistrarRollbackEnvelope(<redacted>)"
}

internal class RegistrarVersionedConnection(
    val connection: RegistrarStoredConnection,
    val revision: RegistrarRecordRevision,
) {
    override fun toString(): String = "RegistrarVersionedConnection(<redacted>)"
}

/**
 * One encrypted, atomic, no-backup record per registrar provider. Several registrars can be
 * connected at once; each slot has its own file, authenticated-data domain, revision and pending
 * commit, so a corrupted or in-flight record never affects another registrar. All slots share one
 * non-exportable AndroidKeyStore key dedicated to registrars.
 */
class RegistrarConnectionRepository internal constructor(
    private val storeFactory: (RegistrarProvider) -> AtomicBytesStore,
    private val cipher: AccountCipher,
) {
    /** Slots are opened lazily on the storage thread, never during construction. */
    private val stores = mutableMapOf<RegistrarProvider, AtomicBytesStore>()
    private val pendingCommits = mutableMapOf<RegistrarProvider, RegistrarRecordCommit>()

    @Synchronized
    fun load(provider: RegistrarProvider): RegistrarStoredConnection? = loadWithRevision(provider)?.connection

    @Synchronized
    internal fun loadWithRevision(provider: RegistrarProvider): RegistrarVersionedConnection? {
        val envelopeBytes = store(provider).read() ?: return null
        val associatedData = associatedData(provider).toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            val revision = RegistrarRecordRevision.from(envelopeBytes)
            val sealedPayload = AccountEnvelopeCodec.decode(envelopeBytes)
            plaintext = cipher.decrypt(sealedPayload, associatedData)
            val connection = RegistrarConnectionPayloadCodec.decode(plaintext)
            require(connection.account.provider == provider) {
                "The registrar record does not match its storage slot."
            }
            RegistrarVersionedConnection(connection, revision)
        } finally {
            envelopeBytes.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    @Synchronized
    fun save(connection: RegistrarStoredConnection) {
        val provider = connection.account.provider
        check(pendingCommits[provider] == null) { "A registrar connection replacement is already pending." }
        val envelope = encryptedEnvelope(connection)
        try {
            store(provider).write(envelope)
        } finally {
            envelope.fill(0)
        }
    }

    @Synchronized
    internal fun saveWithRevision(connection: RegistrarStoredConnection): RegistrarRecordCommit {
        val provider = connection.account.provider
        check(pendingCommits[provider] == null) { "A registrar connection replacement is already pending." }
        val store = store(provider)
        var previousEnvelope = store.read()
        var envelope: ByteArray? = null
        return try {
            envelope = encryptedEnvelope(connection)
            store.write(envelope)
            RegistrarRecordCommit(
                provider = provider,
                revision = RegistrarRecordRevision.from(envelope),
                previousEnvelope = previousEnvelope,
            ).also { commit ->
                pendingCommits[provider] = commit
                previousEnvelope = null
            }
        } finally {
            envelope?.fill(0)
            previousEnvelope?.fill(0)
        }
    }

    /** Compare-and-swap used by refreshes so stale work cannot resurrect or replace a newer record. */
    @Synchronized
    internal fun saveIfRevisionMatches(
        expectedRevision: RegistrarRecordRevision,
        connection: RegistrarStoredConnection,
    ): Boolean {
        val provider = connection.account.provider
        if (pendingCommits[provider] != null) return false
        val store = store(provider)
        val currentEnvelope = store.read() ?: return false
        var replacementEnvelope: ByteArray? = null
        return try {
            if (!expectedRevision.matches(currentEnvelope)) return false
            replacementEnvelope = encryptedEnvelope(connection)
            store.write(replacementEnvelope)
            true
        } finally {
            currentEnvelope.fill(0)
            replacementEnvelope?.fill(0)
        }
    }

    /** Releases the prior encrypted record once the caller accepts the replacement. */
    @Synchronized
    internal fun accept(commit: RegistrarRecordCommit) {
        if (pendingCommits[commit.provider] === commit) pendingCommits.remove(commit.provider)
        commit.accept()?.fill(0)
    }

    /** Restores the prior envelope only while this exact replacement remains current. */
    @Synchronized
    internal fun rollbackIfRevisionMatches(commit: RegistrarRecordCommit): Boolean {
        if (pendingCommits[commit.provider] !== commit) return false
        val rollback = commit.claimRollback() ?: return false
        pendingCommits.remove(commit.provider)
        val store = store(commit.provider)
        val previousEnvelope = rollback.bytes
        val currentEnvelope = store.read()
        return try {
            if (currentEnvelope == null || !commit.revision.matches(currentEnvelope)) {
                false
            } else {
                if (previousEnvelope == null) store.delete() else store.write(previousEnvelope)
                true
            }
        } finally {
            currentEnvelope?.fill(0)
            previousEnvelope?.fill(0)
        }
    }

    /** Only an explicit user disconnect flow should erase a registrar slot. */
    @Synchronized
    fun delete(provider: RegistrarProvider) {
        pendingCommits.remove(provider)?.accept()?.fill(0)
        store(provider).delete()
    }

    @Synchronized
    private fun store(provider: RegistrarProvider): AtomicBytesStore =
        stores.getOrPut(provider) { storeFactory(provider) }

    private fun encryptedEnvelope(connection: RegistrarStoredConnection): ByteArray {
        val provider = connection.account.provider
        val plaintext = RegistrarConnectionPayloadCodec.encode(connection)
        val associatedData = associatedData(provider).toByteArray(StandardCharsets.UTF_8)
        return try {
            AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
        }
    }

    companion object {
        internal const val KEY_ALIAS = "verceltics.account-storage.registrar.v1"

        internal fun accountPath(provider: RegistrarProvider): String =
            "accounts/registrar-${provider.storageSlug}.account"

        internal fun associatedData(provider: RegistrarProvider): String =
            "verceltics.account-envelope.v1:registrar-${provider.storageSlug}"

        fun create(context: Context): RegistrarConnectionRepository {
            val applicationContext = context.applicationContext
            return RegistrarConnectionRepository(
                storeFactory = { provider -> NoBackupAtomicFileStore(applicationContext, accountPath(provider)) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}
