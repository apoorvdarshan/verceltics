package com.apoorvdarshan.verceltics.data.searchconsole

import android.content.Context
import com.apoorvdarshan.verceltics.data.account.AccountCipher
import com.apoorvdarshan.verceltics.data.account.AccountEnvelopeCodec
import com.apoorvdarshan.verceltics.data.account.AndroidKeystoreAccountCipher
import com.apoorvdarshan.verceltics.data.account.AtomicBytesStore
import com.apoorvdarshan.verceltics.data.account.NoBackupAtomicFileStore
import com.apoorvdarshan.verceltics.data.googleoauth.EncryptedGoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredential
import com.apoorvdarshan.verceltics.data.googleoauth.GoogleOAuthCredentialStore
import com.apoorvdarshan.verceltics.data.sites.SiteAccountCommit
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIds
import com.apoorvdarshan.verceltics.data.sites.SiteAccountIndex
import com.apoorvdarshan.verceltics.data.sites.SiteAccountVault
import com.apoorvdarshan.verceltics.data.sites.SiteConnectionRevision
import com.apoorvdarshan.verceltics.data.sites.SiteLegacyAccountSource
import com.apoorvdarshan.verceltics.data.sites.migratedIndex
import java.nio.charset.StandardCharsets

internal class SearchConsoleVersionedRecord(
    val record: SearchConsoleAccountRecord,
    val revision: SiteConnectionRevision,
)

/**
 * Encrypted multi-account Google Search Console storage: an account index and one no-backup
 * record per account (identity and offline property cache). Each account's Google credential lives
 * in its own `GoogleOAuthSession` slot, `search-console.<accountId>`. The pre-multi-account record
 * (which embedded the credential) migrates losslessly into the first account on first access.
 */
class SearchConsoleConnectionRepository internal constructor(
    private val vault: SiteAccountVault<SearchConsoleAccountRecord>,
    private val legacy: SiteLegacyAccountSource<SearchConsoleAccountRecord>,
) {
    constructor(
        storeFor: (relativePath: String) -> AtomicBytesStore,
        cipher: AccountCipher,
        credentialStore: (slot: String) -> GoogleOAuthCredentialStore,
    ) : this(
        vault = SiteAccountVault(
            domain = DOMAIN,
            indexStore = storeFor(INDEX_PATH),
            recordStoreFor = { accountId -> storeFor(recordPath(accountId)) },
            cipher = cipher,
            codec = SearchConsoleAccountRecordCodec,
        ),
        legacy = LegacySearchConsoleAccount(storeFor(ACCOUNT_PATH), cipher, credentialStore),
    )

    /** Saved accounts, migrating the legacy single-account record first. Throws when unreadable. */
    fun index(): SiteAccountIndex = vault.migratedIndex(legacy, entry = { record, _ -> record.entry })

    fun writeIndex(index: SiteAccountIndex) = vault.writeIndex(index)

    fun load(accountId: String): SearchConsoleAccountRecord? = vault.read(accountId)

    internal fun loadWithRevision(accountId: String): SearchConsoleVersionedRecord? =
        vault.readVersioned(accountId)?.let { SearchConsoleVersionedRecord(it.record, it.revision) }

    /** Writes a record and the index together; the commit can undo both while still current. */
    internal fun commit(record: SearchConsoleAccountRecord, index: SiteAccountIndex): SiteAccountCommit =
        vault.commit(record.id, record, index)

    internal fun accept(commit: SiteAccountCommit) = vault.accept(commit)

    internal fun rollbackIfCurrent(commit: SiteAccountCommit): Boolean = vault.rollbackIfCurrent(commit)

    internal fun saveIfRevisionMatches(expected: SiteConnectionRevision, record: SearchConsoleAccountRecord): Boolean =
        vault.writeIfRevisionMatches(record.id, expected, record)

    fun delete(accountId: String) = vault.delete(accountId)

    /** Drops an unreadable index (and any legacy record) so a corrupt connection can be replaced. */
    fun resetUnreadable() {
        vault.deleteIndex()
        legacy.delete()
    }

    fun <R> transaction(block: () -> R): R = vault.transaction(block)

    companion object {
        /** Legacy single-account record (read only for migration). */
        internal const val ASSOCIATED_DATA = "verceltics.account-envelope.v1:google-search-console-oauth"
        internal const val ACCOUNT_PATH = "accounts/google-search-console-oauth.account"
        internal const val KEY_ALIAS = "verceltics.account-storage.google-search-console.v1"
        internal const val DOMAIN = "google-search-console"
        internal const val INDEX_PATH = "accounts/google-search-console/accounts.index"

        fun recordPath(accountId: String): String {
            require(SiteAccountIds.isValid(accountId)) { "Invalid Search Console account id." }
            return "accounts/google-search-console/$accountId.account"
        }

        /** Each Search Console account's own Google OAuth slot. */
        fun credentialSlot(accountId: String): String {
            require(SiteAccountIds.isValid(accountId)) { "Invalid Search Console account id." }
            return "search-console.$accountId"
        }

        fun create(context: Context): SearchConsoleConnectionRepository {
            val applicationContext = context.applicationContext
            return SearchConsoleConnectionRepository(
                storeFor = { path -> NoBackupAtomicFileStore(applicationContext, path) },
                cipher = AndroidKeystoreAccountCipher(keyAlias = KEY_ALIAS),
                credentialStore = { slot -> EncryptedGoogleOAuthCredentialStore.create(applicationContext, slot) },
            )
        }
    }
}

/**
 * The pre-multi-account record, whose payload embedded the Google credential. Migration moves the
 * credential into the first account's OAuth slot and the identity and cache into its record.
 */
private class LegacySearchConsoleAccount(
    private val store: AtomicBytesStore,
    private val cipher: AccountCipher,
    private val credentialStore: (slot: String) -> GoogleOAuthCredentialStore,
) : SiteLegacyAccountSource<SearchConsoleAccountRecord> {
    private var loadedCredential: SearchConsoleOAuthCredential? = null

    override fun load(): SearchConsoleAccountRecord? {
        val envelope = store.read() ?: return null
        val associatedData = SearchConsoleConnectionRepository.ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        val legacy = try {
            plaintext = cipher.decrypt(AccountEnvelopeCodec.decode(envelope), associatedData)
            SearchConsoleConnectionPayloadCodec.decode(plaintext)
        } finally {
            envelope.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
        loadedCredential = legacy.credential
        return SearchConsoleAccountRecord(
            id = SiteAccountIds.migrated(SearchConsoleConnectionRepository.DOMAIN),
            subject = legacy.credential.subject,
            email = legacy.credential.email,
            createdAtMillis = legacy.createdAtMillis,
            updatedAtMillis = legacy.updatedAtMillis,
            cachedSnapshot = legacy.cachedSnapshot,
        )
    }

    override fun migrateSecrets(record: SearchConsoleAccountRecord, accountId: String) {
        val credential = loadedCredential ?: return
        loadedCredential = null
        // A credential the shared Google store cannot represent is dropped; the account then asks
        // the user to reconnect instead of blocking the whole migration.
        val google = runCatching { credential.toGoogleCredential() }.getOrNull() ?: return
        credentialStore(SearchConsoleConnectionRepository.credentialSlot(accountId)).save(google)
    }

    override fun delete() = store.delete()
}

internal fun SearchConsoleOAuthCredential.toGoogleCredential(): GoogleOAuthCredential = GoogleOAuthCredential(
    accessToken = accessToken,
    refreshToken = refreshToken,
    tokenType = tokenType,
    scopes = scopes,
    expiresAtMillis = expiresAtMillis,
    subject = subject,
    email = email,
)

internal fun GoogleOAuthCredential.toSearchConsoleCredential(): SearchConsoleOAuthCredential = SearchConsoleOAuthCredential(
    accessToken = accessToken,
    refreshToken = refreshToken,
    tokenType = tokenType,
    scopes = scopes,
    expiresAtMillis = expiresAtMillis,
    subject = subject,
    email = email,
)
