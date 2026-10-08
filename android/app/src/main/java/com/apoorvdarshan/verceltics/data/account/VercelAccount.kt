package com.apoorvdarshan.verceltics.data.account

import java.util.UUID

/** A connected Vercel personal-token account. Token contents are never printable. */
class VercelAccount(
    /**
     * This saved account's local id: unique per saved token and stable for its lifetime, like the
     * iOS `VercelAccount.id` UUID. Two tokens for the same Vercel user are two accounts. Accounts
     * saved before the local id existed keep their Vercel user id as their local id.
     */
    val id: String,
    val displayName: String,
    val email: String?,
    val token: SecretValue,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** The Vercel username (personal scope slug), used for `vercel.com/{scope}/{project}` links. */
    val username: String? = null,
    /**
     * Set once a 3- or 12-month Web Analytics request succeeds for this account, so the range
     * picker stops marking those ranges as locked (iOS `accountsWithLongAnalyticsHistory`).
     */
    val hasLongAnalyticsHistory: Boolean = false,
    /** The `/v2/user` avatar: a Vercel avatar hash, or an HTTPS URL. */
    val avatar: String? = null,
    /** The Vercel user the token belongs to (`/v2/user` `id`). Several accounts may share it. */
    val vercelUserId: String = id,
) {
    val providerId: String = PROVIDER_ID

    init {
        require(id.isNotBlank() && id.length <= 256) { "Invalid account id." }
        require(vercelUserId.isNotBlank() && vercelUserId.length <= 256) { "Invalid Vercel user id." }
        require(displayName.isNotBlank() && displayName.length <= 256) { "Invalid display name." }
        require(email == null || email.length <= 512) { "Invalid email." }
        require(username == null || (username.isNotBlank() && username.length <= 256)) { "Invalid username." }
        require(avatar == null || (avatar.isNotBlank() && avatar.length <= MAX_AVATAR_CHARACTERS)) {
            "Invalid avatar."
        }
        require(createdAtMillis >= 0L) { "Invalid creation timestamp." }
        require(updatedAtMillis >= createdAtMillis) { "Invalid update timestamp." }
    }

    fun withUpdatedToken(newToken: SecretValue, nowMillis: Long): VercelAccount = copy(
        token = newToken,
        updatedAtMillis = nowMillis.coerceAtLeast(createdAtMillis),
    )

    fun withUsername(newUsername: String): VercelAccount = copy(username = newUsername)

    fun withLongAnalyticsHistory(): VercelAccount = copy(hasLongAnalyticsHistory = true)

    /** The same saved account under another local id; everything else is unchanged. */
    fun withLocalId(newId: String): VercelAccount = copy(id = newId)

    /** The latest `/v2/user` profile; the token, timestamps and analytics flag are unchanged. */
    fun withProfile(
        displayName: String,
        email: String?,
        username: String?,
        avatar: String?,
    ): VercelAccount = copy(
        displayName = displayName,
        email = email,
        username = username ?: this.username,
        avatar = avatar,
    )

    /**
     * The same token connected again (iOS `login(token:)` finding `$0.token == token`): the fresh
     * profile replaces the saved one in place, keeping this account's local id, when it was first
     * connected, and whether it has long analytics history.
     */
    fun reconnectedWith(reconnected: VercelAccount, nowMillis: Long): VercelAccount {
        require(reconnected.token == token) { "Only the same token updates a saved account in place." }
        return copy(
            displayName = reconnected.displayName,
            email = reconnected.email,
            updatedAtMillis = nowMillis.coerceAtLeast(createdAtMillis),
            username = reconnected.username ?: username,
            hasLongAnalyticsHistory = hasLongAnalyticsHistory || reconnected.hasLongAnalyticsHistory,
            avatar = reconnected.avatar,
            vercelUserId = reconnected.vercelUserId,
        )
    }

    /** True when the user-visible profile fields match; tokens are compared separately. */
    fun hasSameProfile(other: VercelAccount): Boolean =
        displayName == other.displayName &&
            email == other.email &&
            username == other.username &&
            avatar == other.avatar

    private fun copy(
        id: String = this.id,
        displayName: String = this.displayName,
        email: String? = this.email,
        token: SecretValue = this.token,
        updatedAtMillis: Long = this.updatedAtMillis,
        username: String? = this.username,
        hasLongAnalyticsHistory: Boolean = this.hasLongAnalyticsHistory,
        avatar: String? = this.avatar,
        vercelUserId: String = this.vercelUserId,
    ): VercelAccount = VercelAccount(
        id = id,
        displayName = displayName,
        email = email,
        token = token,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        username = username,
        hasLongAnalyticsHistory = hasLongAnalyticsHistory,
        avatar = avatar,
        vercelUserId = vercelUserId,
    )

    override fun toString(): String =
        "VercelAccount(id=$id, providerId=$providerId, vercelUserId=$vercelUserId, " +
            "displayName=$displayName, email=$email, username=$username, avatar=$avatar, " +
            "token=<redacted>, createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis, " +
            "hasLongAnalyticsHistory=$hasLongAnalyticsHistory)"

    companion object {
        const val PROVIDER_ID: String = "vercel"
        const val MAX_AVATAR_CHARACTERS: Int = 1_024

        /** A fresh local id for a newly saved token. */
        fun newLocalId(): String = UUID.randomUUID().toString()
    }
}
