package com.apoorvdarshan.verceltics.data.cloudflare

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

/** In-memory identity of one encrypted Cloudflare record revision (its saved account and digest). */
internal typealias CloudflareRecordRevision = AccountRecordRevision

internal class CloudflareRecordCommit(val vaultCommit: AccountVaultCommit) {
    val accountId: String get() = vaultCommit.accountId

    override fun toString(): String = "CloudflareRecordCommit(accountId=$accountId, <redacted>)"
}

internal data class CloudflareVersionedConnection(
    val connection: CloudflareStoredConnection,
    val revision: CloudflareRecordRevision,
) {
    /** The saved login (storage slot), not a Cloudflare account inside it. */
    val savedAccountId: String get() = revision.accountId
}

/**
 * Atomic encrypted Cloudflare logins in provider-specific no-backup slots and AAD domain. Several
 * logins (scoped API tokens and email + Global API Key) can be saved; the one saved before
 * multi-account support (a v1 or v2 record) stays in its original slot as the first login.
 */
class CloudflareConnectionRepository(
    storeFactory: (relativePath: String) -> AtomicBytesStore,
    cipher: AccountCipher,
    newAccountId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val vault = ProviderAccountVault(
        layout = LAYOUT,
        storeFactory = storeFactory,
        cipher = cipher,
        encode = { connection: CloudflareStoredConnection ->
            require(connection.account.providerId == CloudflareAccount.PROVIDER_ID)
            CloudflareConnectionPayloadCodec.encode(connection)
        },
        decode = { _, plaintext -> CloudflareConnectionPayloadCodec.decode(plaintext) },
        newAccountId = newAccountId,
    )

    /** The active login's connection. */
    fun load(): CloudflareStoredConnection? = vault.loadActive()?.value

    fun load(savedAccountId: String): CloudflareStoredConnection? = vault.load(savedAccountId)?.value

    internal fun loadWithRevision(): CloudflareVersionedConnection? = vault.loadActive()?.toVersioned()

    internal fun loadWithRevision(savedAccountId: String): CloudflareVersionedConnection? =
        vault.load(savedAccountId)?.toVersioned()

    fun activeAccountId(): String? = vault.activeAccountId()

    fun records(): List<AccountVaultRecord<CloudflareStoredConnection>> = vault.records()

    /** Direct replacement of the active (or first) login, used by tests and migrations. */
    fun save(connection: CloudflareStoredConnection) {
        vault.save(connection)
    }

    internal fun saveWithRevision(savedAccountId: String?, connection: CloudflareStoredConnection): CloudflareRecordCommit =
        CloudflareRecordCommit(vault.saveWithRevision(savedAccountId, connection))

    /** Compare-and-swap prevents stale refreshes from resurrecting or overwriting a record. */
    internal fun saveIfRevisionMatches(
        expectedRevision: CloudflareRecordRevision,
        connection: CloudflareStoredConnection,
    ): Boolean = vault.saveIfRevisionMatches(expectedRevision, connection)

    internal fun accept(commit: CloudflareRecordCommit) = vault.accept(commit.vaultCommit)

    internal fun rollbackIfRevisionMatches(commit: CloudflareRecordCommit): Boolean =
        vault.rollbackIfRevisionMatches(commit.vaultCommit)

    fun activate(savedAccountId: String): Boolean = vault.activate(savedAccountId)

    /** Removes one login; returns the new active login id, if any remain. */
    fun deleteAccount(savedAccountId: String): String? = vault.delete(savedAccountId)

    /** Removes every saved Cloudflare login. */
    fun delete() = vault.deleteAll()

    private fun AccountVaultEntry<CloudflareStoredConnection>.toVersioned() =
        CloudflareVersionedConnection(value, revision)

    companion object {
        internal const val ASSOCIATED_DATA = "verceltics.account-envelope.v1:cloudflare-api-token"
        internal const val ACCOUNT_PATH = "accounts/cloudflare-api-token.account"
        internal const val KEY_ALIAS = "verceltics.account-storage.cloudflare.v1"
        internal val LAYOUT = AccountVaultLayout(
            domain = "cloudflare-api-token",
            primaryPath = ACCOUNT_PATH,
            primaryAssociatedData = ASSOCIATED_DATA,
        )

        fun create(context: Context): CloudflareConnectionRepository {
            val applicationContext = context.applicationContext
            return CloudflareConnectionRepository(
                storeFactory = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
            )
        }
    }
}
