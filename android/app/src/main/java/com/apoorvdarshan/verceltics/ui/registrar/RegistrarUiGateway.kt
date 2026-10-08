package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawResponse

/**
 * UI-only boundary for all domain registrars. Secrets cross it only inside a
 * [RegistrarConnectRequest] and never enter observable screen state.
 */
interface RegistrarUiGateway {
    /** Offline restore of every registrar slot; never contacts a registrar. */
    suspend fun restore(): Result<RegistrarRestoreUi>

    /**
     * Validates credentials against the registrar API, then saves them encrypted as the active
     * account. Other saved accounts of the registrar stay connected; reconnecting an identity that
     * is already saved rotates its credentials in place.
     */
    suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi>

    /** Refreshes the registrar's active account. */
    suspend fun refresh(providerId: String): Result<RegistrarDashboardUi>

    /** Removes every saved account of the registrar (iOS "Remove All"). */
    suspend fun disconnect(providerId: String): Result<Unit>

    /** Makes another saved account active, offline; it opens with its own cached portfolio. */
    suspend fun switchAccount(providerId: String, accountId: String): Result<RegistrarProviderRestoreUi> =
        Result.failure(RegistrarUiException(ACCOUNTS_UNAVAILABLE))

    /**
     * Removes one saved account (iOS "Remove Current"). The result is the registrar's new state:
     * the next active account, or [RegistrarProviderRestoreUi.NotConnected] after the last one.
     */
    suspend fun removeAccount(providerId: String, accountId: String): Result<RegistrarProviderRestoreUi> =
        Result.failure(RegistrarUiException(ACCOUNTS_UNAVAILABLE))

    /** Public IPv4 of this network for Namecheap's ClientIp / Name.com's optional allowlist. */
    suspend fun detectPublicIpv4(): Result<String>

    /**
     * Sends one Complete API raw request with the saved registrar credentials. HTTP errors are
     * returned as responses; only validation, transport and credential problems fail.
     */
    suspend fun sendApiRequest(providerId: String, request: ProviderRawRequest): Result<ProviderRawResponse> =
        Result.failure(RegistrarUiException(SAMPLE_API_UNAVAILABLE))

    companion object {
        const val SAMPLE_API_UNAVAILABLE: String =
            "Sample data can’t send live API requests. Connect an account to use the Complete API."
        const val ACCOUNTS_UNAVAILABLE: String = "Saved registrar accounts can’t be changed here."
    }
}

/** Credentials typed into the connection form. Deliberately non-printable. */
class RegistrarConnectRequest(
    val providerId: String,
    val apiKey: SecretValue,
    val apiSecret: SecretValue? = null,
    val username: String = "",
    val clientIp: String = "",
    val organization: String = "",
) {
    override fun toString(): String =
        "RegistrarConnectRequest(providerId=$providerId, apiKey=<redacted>, " +
            "apiSecret=${if (apiSecret == null) "null" else "<redacted>"}, username=<redacted>, " +
            "clientIp=<redacted>, organization=<redacted>)"
}

data class RegistrarRestoreUi(
    /** Keyed by registrar provider id; missing ids are treated as not connected. */
    val providers: Map<String, RegistrarProviderRestoreUi>,
)

sealed interface RegistrarProviderRestoreUi {
    data object NotConnected : RegistrarProviderRestoreUi

    /** The active account's cached portfolio; [RegistrarDashboardUi.accounts] lists every account. */
    data class Available(val dashboard: RegistrarDashboardUi) : RegistrarProviderRestoreUi

    data class SavedWithoutInventory(
        val account: RegistrarAccountUi,
        val accounts: List<RegistrarAccountUi> = listOf(account),
    ) : RegistrarProviderRestoreUi

    data class SavedUnavailable(val message: String) : RegistrarProviderRestoreUi
}

enum class RegistrarCacheState {
    LIVE,
    CACHED_FRESH,
    CACHED_STALE,
}

data class RegistrarAccountUi(
    val providerId: String,
    val displayName: String,
    /** Opaque id of this saved account within its registrar (empty only in single-account fixtures). */
    val id: String = "",
)

data class RegistrarDomainUi(
    val id: String,
    val name: String,
    val status: String?,
    val createdAtMillis: Long?,
    val expiresAtMillis: Long?,
    val autoRenew: Boolean?,
    val locked: Boolean?,
    val privacyEnabled: Boolean?,
    val nameservers: List<String>,
)

data class RegistrarDashboardUi(
    /** The active account this portfolio belongs to. */
    val account: RegistrarAccountUi,
    /** Sorted case-insensitively by name, like the iOS dashboard. */
    val domains: List<RegistrarDomainUi>,
    val inventoryComplete: Boolean,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: RegistrarCacheState,
    /** Every saved account of this registrar, in saved order (always includes [account]). */
    val accounts: List<RegistrarAccountUi> = listOf(account),
) {
    val isPartial: Boolean
        get() = !inventoryComplete || warnings.isNotEmpty()
}

/** Only redacted, user-safe messages cross the data/UI boundary. */
class RegistrarUiException(message: String) : Exception(message) {
    override fun toString(): String = "RegistrarUiException(message=$message)"
}
