package com.apoorvdarshan.verceltics.data.hosting

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore

/** In-memory identity of one encrypted hosting record revision (its account and digest). */
internal typealias HostingRecordRevision = AccountRecordRevision

/** Opaque encrypted rollback state for one pending replacement in one provider's account list. */
internal class HostingRecordCommit(
    val provider: HostingProvider,
    val vaultCommit: AccountVaultCommit,
) {
    val accountId: String get() = vaultCommit.accountId

    override fun toString(): String = "HostingRecordCommit(provider=${provider.id}, accountId=$accountId, <redacted>)"
}

internal class HostingVersionedConnection(
    val connection: HostingStoredConnection,
    val revision: HostingRecordRevision,
) {
    val accountId: String get() = revision.accountId
}

/**
 * Encrypted, no-backup account lists for every hosting provider, sharing a single Keystore key.
 *
 * Each provider keeps several accounts plus an active one ([ProviderAccountVault]); the account
 * saved before multi-account support stays in its original slot as the first account. Record AADs
 * name the provider (and account), so an envelope copied into another slot fails to decrypt.
 * Several providers can be connected at once.
 */
class HostingConnectionRepository(
    storeFactory: (relativePath: String) -> AtomicBytesStore,
    cipher: AccountCipher,
    newAccountId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val vaults: Map<HostingProvider, ProviderAccountVault<HostingStoredConnection>> =
        HostingProvider.entries.associateWith { provider ->
            ProviderAccountVault(
                layout = layout(provider),
                storeFactory = storeFactory,
                cipher = cipher,
                encode = HostingConnectionPayloadCodec::encode,
                decode = { accountId, plaintext ->
                    HostingConnectionPayloadCodec.decode(plaintext, provider).scopedTo(accountId)
                },
                newAccountId = newAccountId,
            )
        }

    /** The active account's connection. */
    fun load(provider: HostingProvider): HostingStoredConnection? = vault(provider).loadActive()?.value

    fun load(provider: HostingProvider, accountId: String): HostingStoredConnection? =
        vault(provider).load(accountId)?.value

    internal fun loadWithRevision(provider: HostingProvider): HostingVersionedConnection? =
        vault(provider).loadActive()?.toVersioned()

    internal fun loadWithRevision(provider: HostingProvider, accountId: String): HostingVersionedConnection? =
        vault(provider).load(accountId)?.toVersioned()

    fun activeAccountId(provider: HostingProvider): String? = vault(provider).activeAccountId()

    /** Every saved account in the order it was added; unreadable records are listed, not dropped. */
    fun records(provider: HostingProvider): List<AccountVaultRecord<HostingStoredConnection>> = vault(provider).records()

    /** Direct replacement of the active account (or the first account), used by tests and migrations. */
    fun save(connection: HostingStoredConnection) {
        vault(connection.account.provider).save(connection)
    }

    /** Saves a validated connection into [accountId] (rotation) or a new account (null), active. */
    internal fun saveWithRevision(accountId: String?, connection: HostingStoredConnection): HostingRecordCommit {
        val provider = connection.account.provider
        return HostingRecordCommit(provider, vault(provider).saveWithRevision(accountId, connection))
    }

    /** Compare-and-swap for refreshes, so stale work cannot resurrect or replace a newer record. */
    internal fun saveIfRevisionMatches(
        expectedRevision: HostingRecordRevision,
        connection: HostingStoredConnection,
    ): Boolean = vault(connection.account.provider).saveIfRevisionMatches(expectedRevision, connection)

    internal fun accept(commit: HostingRecordCommit) = vault(commit.provider).accept(commit.vaultCommit)

    /** Restores the prior account record only while this exact replacement is still current. */
    internal fun rollbackIfRevisionMatches(commit: HostingRecordCommit): Boolean =
        vault(commit.provider).rollbackIfRevisionMatches(commit.vaultCommit)

    /** Makes [accountId] the provider's active account. */
    fun activate(provider: HostingProvider, accountId: String): Boolean = vault(provider).activate(accountId)

    /** Removes one account; returns the provider's new active account id, if any remain. */
    fun deleteAccount(provider: HostingProvider, accountId: String): String? = vault(provider).delete(accountId)

    /** Only an explicit user "Remove All" should erase every account of a provider. */
    fun delete(provider: HostingProvider) = vault(provider).deleteAll()

    private fun vault(provider: HostingProvider): ProviderAccountVault<HostingStoredConnection> =
        checkNotNull(vaults[provider])

    private fun AccountVaultEntry<HostingStoredConnection>.toVersioned() = HostingVersionedConnection(value, revision)

    companion object {
        internal const val KEY_ALIAS = "verceltics.account-storage.hosting.v1"

        /** The pre-multi-account record path, kept as the provider's first account. */
        internal fun accountPath(provider: HostingProvider): String = "accounts/hosting-${provider.id}.account"

        internal fun associatedData(provider: HostingProvider): String =
            "verceltics.account-envelope.v1:hosting-${provider.id}"

        internal fun layout(provider: HostingProvider): AccountVaultLayout = AccountVaultLayout(
            domain = "hosting-${provider.id}",
            primaryPath = accountPath(provider),
            primaryAssociatedData = associatedData(provider),
        )

        fun create(context: Context): HostingConnectionRepository {
            val applicationContext = context.applicationContext
            return HostingConnectionRepository(
                storeFactory = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}

/** Binds a decoded record to its account: Firebase reads Google tokens from the account's slot. */
internal fun HostingStoredConnection.scopedTo(accountId: String): HostingStoredConnection {
    val firebase = account.credentials as? HostingCredentials.Firebase ?: return this
    val slot = FirebaseGoogleSlots.forAccount(accountId)
    if (firebase.googleSlot == slot) return this
    return copy(
        account = HostingAccount(
            profile = account.profile,
            credentials = firebase.inGoogleSlot(slot),
            createdAtMillis = account.createdAtMillis,
            updatedAtMillis = account.updatedAtMillis,
        ),
    )
}
