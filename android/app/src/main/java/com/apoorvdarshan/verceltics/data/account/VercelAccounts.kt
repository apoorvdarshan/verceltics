package com.apoorvdarshan.verceltics.data.account

/**
 * Every saved Vercel account in connection order, plus the one the app shows (iOS `AuthManager`
 * `accounts` and `activeAccountId`). Immutable: each operation returns the next snapshot.
 *
 * Accounts are keyed by their local id and identified by their token, exactly like iOS: the same
 * token is one account, while another token — even for the same Vercel user, such as a
 * team-scoped token — is a separate account. A non-empty list always has an active account; an
 * empty list never does.
 */
class VercelAccounts private constructor(
    val accounts: List<VercelAccount>,
    val activeAccountId: String?,
) {
    init {
        require(accounts.size <= MAX_ACCOUNTS) { "You can save up to $MAX_ACCOUNTS Vercel accounts." }
        require(accounts.map(VercelAccount::id).toSet().size == accounts.size) {
            "Each saved Vercel account needs its own id."
        }
        require(accounts.map(VercelAccount::token).toSet().size == accounts.size) {
            "Each Vercel token can only be saved once."
        }
        require((activeAccountId == null) == accounts.isEmpty()) { "Invalid active Vercel account." }
        require(activeAccountId == null || accounts.any { it.id == activeAccountId }) {
            "The active Vercel account is not saved."
        }
    }

    val isEmpty: Boolean
        get() = accounts.isEmpty()

    val active: VercelAccount?
        get() = activeAccountId?.let(::find)

    fun find(accountId: String): VercelAccount? = accounts.firstOrNull { it.id == accountId }

    /** The saved account holding exactly this token, if any. */
    fun findByToken(token: SecretValue): VercelAccount? = accounts.firstOrNull { it.token == token }

    /**
     * Saves a freshly validated token and makes its account active (iOS `login(token:)`). When
     * the same token is already saved, that account's profile is updated in place (same position,
     * same local id); any other token is added as a new account under [account]'s local id.
     */
    fun connect(account: VercelAccount, nowMillis: Long): VercelAccounts {
        val existing = findByToken(account.token)
        if (existing != null) {
            val updated = existing.reconnectedWith(account, nowMillis)
            return VercelAccounts(accounts.map { if (it.id == existing.id) updated else it }, existing.id)
        }
        require(find(account.id) == null) { "A new Vercel account needs a new local id." }
        return VercelAccounts(accounts + account, account.id)
    }

    /** Makes a saved account active. */
    fun switchTo(accountId: String): VercelAccounts {
        require(find(accountId) != null) { "That Vercel account is no longer saved on this device." }
        return if (accountId == activeAccountId) this else VercelAccounts(accounts, accountId)
    }

    /**
     * Removes one account; removing an unknown id changes nothing. When the active account is
     * removed the first remaining account becomes active, like iOS `removeAccount(id:)`.
     */
    fun remove(accountId: String): VercelAccounts {
        if (find(accountId) == null) return this
        val remaining = accounts.filterNot { it.id == accountId }
        val nextActive = if (activeAccountId == accountId) remaining.firstOrNull()?.id else activeAccountId
        return VercelAccounts(remaining, nextActive)
    }

    /** Replaces a saved account with an updated copy under the same local id. */
    fun replace(account: VercelAccount): VercelAccounts {
        require(find(account.id) != null) { "That Vercel account is no longer saved on this device." }
        return VercelAccounts(accounts.map { if (it.id == account.id) account else it }, activeAccountId)
    }

    /**
     * Folds an account read from the single-account storage of earlier builds into this list
     * without losing anything. An empty list adopts it as the active account. A list that already
     * holds the same token keeps that account (an interrupted migration), merging the analytics
     * flag and username. Any other token joins as an extra, inactive account, under a fresh local
     * id from [newLocalId] if its stored id is already taken.
     */
    fun adoptingLegacy(
        legacy: VercelAccount,
        newLocalId: () -> String = VercelAccount::newLocalId,
    ): VercelAccounts {
        if (isEmpty) return VercelAccounts(listOf(legacy), legacy.id)
        val sameToken = findByToken(legacy.token)
        if (sameToken != null) {
            var merged = sameToken
            if (legacy.hasLongAnalyticsHistory && !merged.hasLongAnalyticsHistory) merged = merged.withLongAnalyticsHistory()
            if (merged.username == null && legacy.username != null) merged = merged.withUsername(legacy.username)
            return if (merged === sameToken) this else replace(merged)
        }
        var adopted = legacy
        while (find(adopted.id) != null) adopted = adopted.withLocalId(newLocalId())
        return VercelAccounts(accounts + adopted, activeAccountId)
    }

    override fun toString(): String =
        "VercelAccounts(ids=${accounts.map(VercelAccount::id)}, activeAccountId=$activeAccountId)"

    companion object {
        /** A generous cap that keeps the encrypted list far below the envelope size limit. */
        const val MAX_ACCOUNTS: Int = 32

        val EMPTY: VercelAccounts = VercelAccounts(emptyList(), null)

        /**
         * Builds a snapshot, falling back to the first account when [activeAccountId] is missing
         * or no longer saved (iOS defaults to the first account the same way).
         */
        fun of(accounts: List<VercelAccount>, activeAccountId: String?): VercelAccounts {
            val active = activeAccountId?.takeIf { id -> accounts.any { it.id == id } }
                ?: accounts.firstOrNull()?.id
            return VercelAccounts(accounts.toList(), active)
        }
    }
}
