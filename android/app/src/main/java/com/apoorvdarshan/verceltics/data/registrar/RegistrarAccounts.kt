package com.apoorvdarshan.verceltics.data.registrar

/** Non-secret identity of one saved registrar account, safe to show in the UI. */
data class RegistrarAccountSummary(
    val id: String,
    val displayName: String,
)

/** One saved account inside a registrar slot, identified by an opaque id that never changes. */
class RegistrarSavedAccount(
    val id: String,
    val connection: RegistrarStoredConnection,
) {
    init {
        require(RegistrarAccountSet.isValidAccountId(id)) { "Invalid registrar account id." }
    }

    val summary: RegistrarAccountSummary
        get() = RegistrarAccountSummary(id, connection.account.displayName)

    fun withConnection(connection: RegistrarStoredConnection): RegistrarSavedAccount =
        RegistrarSavedAccount(id, connection)

    override fun toString(): String = "RegistrarSavedAccount(id=$id, connection=$connection)"
}

/**
 * Every saved account of one registrar plus the active one: the per-registrar port of iOS
 * `RegistrarStore.accounts` / `activeAccountID`. A registrar with no saved account has no set.
 */
class RegistrarAccountSet(
    val provider: RegistrarProvider,
    accounts: List<RegistrarSavedAccount>,
    val activeAccountId: String,
) {
    val accounts: List<RegistrarSavedAccount> = accounts.toList()

    init {
        require(this.accounts.isNotEmpty()) { "A registrar account set needs at least one account." }
        require(this.accounts.size <= MAX_ACCOUNTS) { "Too many saved registrar accounts." }
        require(this.accounts.all { it.connection.account.provider == provider }) {
            "A saved account belongs to a different registrar."
        }
        require(this.accounts.mapTo(HashSet()) { it.id }.size == this.accounts.size) {
            "Duplicate registrar account ids."
        }
        require(this.accounts.any { it.id == activeAccountId }) { "The active registrar account is missing." }
    }

    val active: RegistrarSavedAccount
        get() = accounts.first { it.id == activeAccountId }

    val summaries: List<RegistrarAccountSummary>
        get() = accounts.map(RegistrarSavedAccount::summary)

    fun account(id: String): RegistrarSavedAccount? = accounts.firstOrNull { it.id == id }

    fun withActive(id: String): RegistrarAccountSet = RegistrarAccountSet(provider, accounts, id)

    /** Replaces the account with the same id in place, keeping list order. */
    fun replacing(account: RegistrarSavedAccount): RegistrarAccountSet = RegistrarAccountSet(
        provider = provider,
        accounts = accounts.map { if (it.id == account.id) account else it },
        activeAccountId = activeAccountId,
    )

    fun mapAccounts(transform: (RegistrarSavedAccount) -> RegistrarSavedAccount): RegistrarAccountSet =
        RegistrarAccountSet(provider, accounts.map(transform), activeAccountId)

    override fun toString(): String =
        "RegistrarAccountSet(provider=${provider.id}, accounts=${accounts.size}, activeAccountId=$activeAccountId)"

    companion object {
        /** Bounds the encrypted per-registrar record; every account shares its cache budget. */
        const val MAX_ACCOUNTS: Int = 16

        /**
         * Id given to the single account of a pre-multi-account (v1) record when it is migrated.
         * Random ids are UUIDs, so this can never collide with an account added later.
         */
        const val MIGRATED_ACCOUNT_ID: String = "migrated-v1"

        internal const val MAX_ACCOUNT_ID_CHARACTERS = 64

        internal fun isValidAccountId(id: String): Boolean =
            id.length in 1..MAX_ACCOUNT_ID_CHARACTERS &&
                id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
    }
}

/** User-safe failure from local registrar account bookkeeping (never from a registrar API). */
class RegistrarStoreException(message: String) : Exception(message) {
    override fun toString(): String = "RegistrarStoreException(message=$message)"
}
