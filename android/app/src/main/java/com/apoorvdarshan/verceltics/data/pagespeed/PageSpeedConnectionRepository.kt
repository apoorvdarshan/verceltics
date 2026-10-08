package com.apoorvdarshan.verceltics.data.pagespeed

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.sites.SiteAccountCommit
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex
import com.apoorvdarshan.verceltics.data.sites.SiteAccountRecordCodec
import com.apoorvdarshan.verceltics.data.sites.SiteAccountVault
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionRevision
import com.apoorvdarshan.verceltics.data.sites.SiteLegacyAccountSource
import com.apoorvdarshan.verceltics.data.sites.migratedIndex
import java.nio.charset.StandardCharsets

class PageSpeedVersionedConnection internal constructor(
    val connection: PageSpeedStoredConnection,
    internal val revision: SiteConnectionRevision,
) {
    override fun toString(): String = "PageSpeedVersionedConnection(connection=$connection)"
}

/**
 * Atomic encrypted PageSpeed storage for several audited sites: an index plus one no-backup record
 * per site, each bound to its account id in the authenticated data. It deliberately uses its own
 * files and associated data, separate from Vercel. A corrupt/unreadable record is surfaced and is
 * never treated as an absent account or deleted. The pre-multi-site record migrates on first access.
 */
class PageSpeedConnectionRepository internal constructor(
    private val vault: SiteAccountVault<PageSpeedStoredConnection>,
    private val legacy: SiteLegacyAccountSource<PageSpeedStoredConnection>,
) {
    constructor(
        storeFor: (relativePath: String) -> AtomicBytesStore,
        cipher: AccountCipher,
    ) : this(
        vault = SiteAccountVault(
            domain = DOMAIN,
            indexStore = storeFor(INDEX_PATH),
            recordStoreFor = { accountId -> storeFor(recordPath(accountId)) },
            cipher = cipher,
            codec = RecordCodec,
        ),
        legacy = LegacyPageSpeedAccount(storeFor(ACCOUNT_PATH), cipher),
    )

    /** Saved sites, migrating the legacy single-site record first. Throws when unreadable. */
    fun index(): SiteAccountIndex = vault.migratedIndex(
        legacy = legacy,
        entry = { connection, _ -> connection.accountEntry },
        // Keep the legacy record's own id when it is a valid account id (it is a UUID).
        migratedId = { connection ->
            connection.id.takeIf(SiteAccountIds::isValid) ?: SiteAccountIds.migrated(DOMAIN)
        },
        rekey = { connection, accountId ->
            if (connection.id == accountId) connection else connection.copy(id = accountId)
        },
    )

    fun writeIndex(index: SiteAccountIndex) = vault.writeIndex(index)

    fun load(accountId: String): PageSpeedStoredConnection? = vault.read(accountId)

    fun loadWithRevision(accountId: String): PageSpeedVersionedConnection? =
        vault.readVersioned(accountId)?.let { PageSpeedVersionedConnection(it.record, it.revision) }

    internal fun commit(connection: PageSpeedStoredConnection, index: SiteAccountIndex): SiteAccountCommit =
        vault.commit(connection.id, connection, index)

    internal fun accept(commit: SiteAccountCommit) = vault.accept(commit)

    internal fun rollbackIfCurrent(commit: SiteAccountCommit): Boolean = vault.rollbackIfCurrent(commit)

    internal fun saveIfRevisionMatches(expected: SiteConnectionRevision, connection: PageSpeedStoredConnection): Boolean =
        vault.writeIfRevisionMatches(connection.id, expected, connection)

    /** Only an explicit user removal flow should call this. */
    fun delete(accountId: String) = vault.delete(accountId)

    /** Drops an unreadable index (and any legacy record) so a corrupt connection can be replaced. */
    fun resetUnreadable() {
        vault.deleteIndex()
        legacy.delete()
    }

    fun <R> transaction(block: () -> R): R = vault.transaction(block)

    private object RecordCodec : SiteAccountRecordCodec<PageSpeedStoredConnection> {
        override fun encode(record: PageSpeedStoredConnection): ByteArray = PageSpeedConnectionPayloadCodec.encode(record)

        override fun decode(bytes: ByteArray, accountId: String): PageSpeedStoredConnection =
            PageSpeedConnectionPayloadCodec.decode(bytes).also {
                require(it.id == accountId) { "The PageSpeed record belongs to another site." }
            }
    }

    companion object {
        /** Legacy single-site record (read only for migration). */
        internal const val ASSOCIATED_DATA = "verceltics.account-envelope.v1:pagespeed-crux"
        internal const val ACCOUNT_PATH = "accounts/pagespeed-crux-api-key.account"
        internal const val DOMAIN = "pagespeed-crux"
        internal const val INDEX_PATH = "accounts/pagespeed-crux/accounts.index"

        fun recordPath(accountId: String): String {
            require(SiteAccountIds.isValid(accountId)) { "Invalid PageSpeed account id." }
            return "accounts/pagespeed-crux/$accountId.account"
        }

        fun create(context: Context): PageSpeedConnectionRepository {
            val applicationContext = context.applicationContext
            return PageSpeedConnectionRepository(
                storeFor = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                // Reuse the existing non-exportable key without changing its alias or Vercel data.
                cipher = AndroidKeystoreAccountCipher(),
            )
        }
    }
}

/** The pre-multi-site `accounts/pagespeed-crux-api-key.account` record. */
private class LegacyPageSpeedAccount(
    private val store: AtomicBytesStore,
    private val cipher: AccountCipher,
) : SiteLegacyAccountSource<PageSpeedStoredConnection> {
    override fun load(): PageSpeedStoredConnection? {
        val envelope = store.read() ?: return null
        val associatedData = PageSpeedConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            PageSpeedConnectionPayloadCodec.decode(plaintext)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    override fun delete() = store.delete()
}
