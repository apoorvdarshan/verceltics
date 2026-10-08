package com.apoorvdarshan.verceltics.data.account

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Atomic encrypted persistence for every saved Vercel account and the active account id.
 *
 * Earlier builds kept exactly one account in [legacyStore]. The first read after upgrading folds
 * that account into the list (as the active account when the list is empty), writes the list,
 * and only then deletes the legacy record, so an interrupted migration is simply repeated. Every
 * later write deletes any legacy record again, so a removed token can never be resurrected.
 *
 * A read or decryption failure is surfaced and never silently replaced or deleted.
 */
class VercelAccountRepository(
    private val store: AtomicBytesStore,
    private val cipher: AccountCipher,
    private val legacyStore: AtomicBytesStore? = null,
) {
    /** Every saved account, migrating the single-account record of earlier builds first. */
    @Synchronized
    fun loadAll(): VercelAccounts {
        val saved = readList()
        val legacyEnvelope = legacyStore?.read() ?: return saved ?: VercelAccounts.EMPTY
        val legacy = decrypt(legacyEnvelope, LEGACY_ASSOCIATED_DATA, VercelAccountPayloadCodec::decode)
        val migrated = (saved ?: VercelAccounts.EMPTY).adoptingLegacy(legacy)
        writeList(migrated)
        legacyStore.delete()
        return migrated
    }

    /** Persists the whole list; an empty list removes every saved credential. */
    @Synchronized
    fun saveAll(accounts: VercelAccounts) {
        if (accounts.isEmpty) {
            deleteAll()
            return
        }
        writeList(accounts)
        legacyStore?.delete()
    }

    /** Call only after an explicit user removal of every Vercel account. */
    @Synchronized
    fun deleteAll() {
        store.delete()
        legacyStore?.delete()
    }

    /** The active account, or null when nothing is saved. */
    @Synchronized
    fun load(): VercelAccount? = loadAll().active

    /** Adds [account] (or updates the account already holding its token) and makes it active. */
    @Synchronized
    fun save(account: VercelAccount) {
        require(account.providerId == VercelAccount.PROVIDER_ID) { "Wrong account provider." }
        saveAll(loadAll().connect(account, nowMillis = account.updatedAtMillis))
    }

    /** Call only after an explicit user disconnect action. Removes every saved account. */
    @Synchronized
    fun delete() = deleteAll()

    private fun readList(): VercelAccounts? {
        val envelope = store.read() ?: return null
        return decrypt(envelope, LIST_ASSOCIATED_DATA, VercelAccountListCodec::decode)
    }

    private fun writeList(accounts: VercelAccounts) {
        val plaintext = VercelAccountListCodec.encode(accounts)
        val associatedData = LIST_ASSOCIATED_DATA.toByteArray(StandardCharsets.UTF_8)
        var envelope: ByteArray? = null
        try {
            envelope = AccountEnvelopeCodec.encode(cipher.encrypt(plaintext, associatedData))
            store.write(envelope)
        } finally {
            plaintext.fill(0)
            associatedData.fill(0)
            envelope?.fill(0)
        }
    }

    private fun <T> decrypt(envelopeBytes: ByteArray, associatedDataText: String, decode: (ByteArray) -> T): T {
        val associatedData = associatedDataText.toByteArray(StandardCharsets.UTF_8)
        var plaintext: ByteArray? = null
        return try {
            val sealedPayload = AccountEnvelopeCodec.decode(envelopeBytes)
            plaintext = cipher.decrypt(sealedPayload, associatedData)
            decode(plaintext)
        } finally {
            envelopeBytes.fill(0)
            associatedData.fill(0)
            plaintext?.fill(0)
        }
    }

    companion object {
        private const val LEGACY_ASSOCIATED_DATA = "verceltics.account-envelope.v1:vercel"
        private const val LIST_ASSOCIATED_DATA = "verceltics.account-list.v1:vercel"
        private const val LEGACY_ACCOUNT_PATH = "accounts/vercel-personal-token.account"
        private const val ACCOUNT_LIST_PATH = "accounts/vercel-personal-tokens.accounts"

        fun create(context: Context): VercelAccountRepository = VercelAccountRepository(
            store = NoBackupAtomicFileStore(context, ACCOUNT_LIST_PATH),
            cipher = AndroidKeystoreAccountCipher(),
            legacyStore = NoBackupAtomicFileStore(context, LEGACY_ACCOUNT_PATH),
        )
    }
}
