package com.apoorvdarshan.verceltics.data.account

/** A connected Vercel personal-token account. Token contents are never printable. */
class VercelAccount(
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
) {
    val providerId: String = PROVIDER_ID

    init {
        require(id.isNotBlank() && id.length <= 256) { "Invalid account id." }
        require(displayName.isNotBlank() && displayName.length <= 256) { "Invalid display name." }
        require(email == null || email.length <= 512) { "Invalid email." }
        require(username == null || (username.isNotBlank() && username.length <= 256)) { "Invalid username." }
        require(createdAtMillis >= 0L) { "Invalid creation timestamp." }
        require(updatedAtMillis >= createdAtMillis) { "Invalid update timestamp." }
    }

    fun withUpdatedToken(newToken: SecretValue, nowMillis: Long): VercelAccount = copy(
        token = newToken,
        updatedAtMillis = nowMillis.coerceAtLeast(createdAtMillis),
    )

    fun withUsername(newUsername: String): VercelAccount = copy(username = newUsername)

    fun withLongAnalyticsHistory(): VercelAccount = copy(hasLongAnalyticsHistory = true)

    private fun copy(
        token: SecretValue = this.token,
        updatedAtMillis: Long = this.updatedAtMillis,
        username: String? = this.username,
        hasLongAnalyticsHistory: Boolean = this.hasLongAnalyticsHistory,
    ): VercelAccount = VercelAccount(
        id = id,
        displayName = displayName,
        email = email,
        token = token,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        username = username,
        hasLongAnalyticsHistory = hasLongAnalyticsHistory,
    )

    override fun toString(): String =
        "VercelAccount(id=$id, providerId=$providerId, displayName=$displayName, " +
            "email=$email, username=$username, token=<redacted>, createdAtMillis=$createdAtMillis, " +
            "updatedAtMillis=$updatedAtMillis, hasLongAnalyticsHistory=$hasLongAnalyticsHistory)"

    companion object {
        const val PROVIDER_ID: String = "vercel"
    }
}
