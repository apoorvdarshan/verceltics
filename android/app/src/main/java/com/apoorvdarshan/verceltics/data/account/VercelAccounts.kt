package com.apoorvdarshan.verceltics.data.account

/**
 * Every saved Vercel account in connection order, plus the one the app shows (iOS `AuthManager`
 * `accounts` and `activeAccountId`). Immutable: each operation returns the next snapshot.
 *
 * A non-empty list always has an active account; an empty list never does.
 */
class VercelAccounts private constructor(
    val accounts: List<VercelAccount>,
    val activeAccountId: String?,
) {
    init {
        require(accounts.size <= MAX_ACCOUNTS) { "You can save up to $MAX_ACCOUNTS Vercel accounts." }
        require(accounts.map(VercelAccount::id).toSet().size == accounts.size) {
            "Each Vercel identity can only be saved once."
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

    /**
     * Saves a freshly validated account and makes it active. Reconnecting an identity that is
     * already saved rotates its token and profile in place (same position, same id) instead of
     * adding a second copy.
     */
    fun connect(account: VercelAccount, nowMillis: Long): VercelAccounts {
        val existing = find(account.id)
        val updated = if (existing == null) {
            accounts + account
        } else {
            accounts.map { if (it.id == account.id) it.reconnectedAs(account, nowMillis) else it }
        }
        return VercelAccounts(updated, account.id)
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

    /** Replaces a saved account with an updated copy of the same identity. */
    fun replace(account: VercelAccount): VercelAccounts {
        require(find(account.id) != null) { "That Vercel account is no longer saved on this device." }
        return VercelAccounts(accounts.map { if (it.id == account.id) account else it }, activeAccountId)
    }

    /**
     * Folds an account read from the single-account storage of earlier builds into this list
     * without losing anything. An empty list adopts it as the active account; a list that already
     * has the identity keeps whichever copy was updated last (merging the analytics flag); any
     * other list gains it as an extra, inactive account.
     */
    fun adoptingLegacy(legacy: VercelAccount): VercelAccounts {
        val existing = find(legacy.id) ?: return if (isEmpty) {
            VercelAccounts(listOf(legacy), legacy.id)
        } else {
            VercelAccounts(accounts + legacy, activeAccountId)
        }
        if (legacy.updatedAtMillis <= existing.updatedAtMillis && legacy.token == existing.token) {
            return if (legacy.hasLongAnalyticsHistory && !existing.hasLongAnalyticsHistory) {
                replace(existing.withLongAnalyticsHistory())
            } else {
                this
            }
        }
        val newer = if (legacy.updatedAtMillis >= existing.updatedAtMillis) legacy else existing
        val older = if (newer === legacy) existing else legacy
        val merged = if (older.hasLongAnalyticsHistory && !newer.hasLongAnalyticsHistory) {
            newer.withLongAnalyticsHistory()
        } else {
            newer
        }
        return replace(merged)
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
