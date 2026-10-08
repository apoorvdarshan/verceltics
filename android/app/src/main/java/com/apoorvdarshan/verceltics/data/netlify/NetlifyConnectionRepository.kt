package com.apoorvdarshan.verceltics.data.netlify

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.hosting.AccountRecordRevision
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultCommit
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultEntry
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultLayout
import com.apoorvdarshan.verceltics.data.hosting.AccountVaultRecord
import com.apoorvdarshan.verceltics.data.hosting.ProviderAccountVault

/** In-memory identity for one encrypted Netlify record revision (its account and digest). */
internal typealias NetlifyRecordRevision = AccountRecordRevision

/** Opaque encrypted rollback state for one pending Netlify replacement. */
internal class NetlifyRecordCommit(val vaultCommit: AccountVaultCommit) {
    val accountId: String get() = vaultCommit.accountId

    override fun toString(): String = "NetlifyRecordCommit(accountId=$accountId, <redacted>)"
}

internal data class NetlifyVersionedConnection(
    val connection: NetlifyStoredConnection,
    val revision: NetlifyRecordRevision,
) {
    val accountId: String get() = revision.accountId
}

/**
 * Atomic encrypted Netlify accounts in their own no-backup paths, key alias, and AAD domain.
 * Several personal-token accounts can be saved; the one saved before multi-account support stays
 * in its original slot as the first account.
 */
class NetlifyConnectionRepository(
    storeFactory: (relativePath: String) -> AtomicBytesStore,
    cipher: AccountCipher,
    newAccountId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val vault = ProviderAccountVault(
        layout = LAYOUT,
        storeFactory = storeFactory,
        cipher = cipher,
        encode = { connection: NetlifyStoredConnection ->
            require(connection.account.providerId == NetlifyAccount.PROVIDER_ID) {
                "Wrong account provider for the Netlify storage slot."
            }
            NetlifyConnectionPayloadCodec.encode(connection)
        },
        decode = { _, plaintext -> NetlifyConnectionPayloadCodec.decode(plaintext) },
        newAccountId = newAccountId,
    )

    /** The active account's connection. */
    fun load(): NetlifyStoredConnection? = vault.loadActive()?.value

    fun load(accountId: String): NetlifyStoredConnection? = vault.load(accountId)?.value

    internal fun loadWithRevision(): NetlifyVersionedConnection? = vault.loadActive()?.toVersioned()

    internal fun loadWithRevision(accountId: String): NetlifyVersionedConnection? = vault.load(accountId)?.toVersioned()

    fun activeAccountId(): String? = vault.activeAccountId()

    fun records(): List<AccountVaultRecord<NetlifyStoredConnection>> = vault.records()

    /** Direct replacement of the active (or first) account, used by tests and migrations. */
    fun save(connection: NetlifyStoredConnection) {
        vault.save(connection)
    }

    internal fun saveWithRevision(accountId: String?, connection: NetlifyStoredConnection): NetlifyRecordCommit {
        require(connection.account.providerId == NetlifyAccount.PROVIDER_ID) {
            "Wrong account provider for the Netlify storage slot."
        }
        return NetlifyRecordCommit(vault.saveWithRevision(accountId, connection))
    }

    /** Compare-and-swap used by refreshes so stale work cannot resurrect or replace a new record. */
    internal fun saveIfRevisionMatches(
        expectedRevision: NetlifyRecordRevision,
        connection: NetlifyStoredConnection,
    ): Boolean = vault.saveIfRevisionMatches(expectedRevision, connection)

    /** Releases the prior encrypted record once the caller accepts the replacement. */
    internal fun accept(commit: NetlifyRecordCommit) = vault.accept(commit.vaultCommit)

    /** Restores the prior envelope only while this exact replacement remains current. */
    internal fun rollbackIfRevisionMatches(commit: NetlifyRecordCommit): Boolean =
        vault.rollbackIfRevisionMatches(commit.vaultCommit)

    fun activate(accountId: String): Boolean = vault.activate(accountId)

    /** Removes one account; returns the new active account id, if any remain. */
    fun deleteAccount(accountId: String): String? = vault.delete(accountId)

    /** Only an explicit user "Remove All" flow should erase every Netlify account. */
    fun delete() = vault.deleteAll()

    private fun AccountVaultEntry<NetlifyStoredConnection>.toVersioned() = NetlifyVersionedConnection(value, revision)

    companion object {
        internal const val ASSOCIATED_DATA = "verceltics.account-envelope.v1:netlify-personal-token"
        internal const val ACCOUNT_PATH = "accounts/netlify-personal-token.account"
        internal const val KEY_ALIAS = "verceltics.account-storage.netlify.v1"
        internal val LAYOUT = AccountVaultLayout(
            domain = "netlify-personal-token",
            primaryPath = ACCOUNT_PATH,
            primaryAssociatedData = ASSOCIATED_DATA,
        )

        fun create(context: Context): NetlifyConnectionRepository {
            val applicationContext = context.applicationContext
            return NetlifyConnectionRepository(
                storeFactory = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}
