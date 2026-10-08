package com.apoorvdarshan.verceltics.data.account

/** A connected Vercel personal-token account. Token contents are never printable. */
class VercelAccount(
    /** The Vercel user id the token validated as; one saved account per identity. */
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
) {
    val providerId: String = PROVIDER_ID

    init {
        require(id.isNotBlank() && id.length <= 256) { "Invalid account id." }
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
     * The same Vercel identity connected again (a rotated or re-issued token): the new token and
     * profile replace the saved ones in place, keeping when it was first connected and whether it
     * has long analytics history.
     */
    fun reconnectedAs(reconnected: VercelAccount, nowMillis: Long): VercelAccount {
        require(reconnected.id == id) { "A different Vercel identity cannot replace this account." }
        return copy(
            displayName = reconnected.displayName,
            email = reconnected.email,
            token = reconnected.token,
            updatedAtMillis = nowMillis.coerceAtLeast(createdAtMillis),
            username = reconnected.username ?: username,
            hasLongAnalyticsHistory = hasLongAnalyticsHistory || reconnected.hasLongAnalyticsHistory,
            avatar = reconnected.avatar,
        )
    }

    /** True when the user-visible profile fields match; tokens are compared separately. */
    fun hasSameProfile(other: VercelAccount): Boolean =
        displayName == other.displayName &&
            email == other.email &&
            username == other.username &&
            avatar == other.avatar

    private fun copy(
        displayName: String = this.displayName,
        email: String? = this.email,
        token: SecretValue = this.token,
        updatedAtMillis: Long = this.updatedAtMillis,
        username: String? = this.username,
        hasLongAnalyticsHistory: Boolean = this.hasLongAnalyticsHistory,
        avatar: String? = this.avatar,
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
    )

    override fun toString(): String =
        "VercelAccount(id=$id, providerId=$providerId, displayName=$displayName, " +
            "email=$email, username=$username, avatar=$avatar, token=<redacted>, " +
            "createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis, " +
            "hasLongAnalyticsHistory=$hasLongAnalyticsHistory)"

    companion object {
        const val PROVIDER_ID: String = "vercel"
        const val MAX_AVATAR_CHARACTERS: Int = 1_024
    }
}
