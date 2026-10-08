package com.apoorvdarshan.verceltics.ui.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue

/**
 * UI-only boundary for all domain registrars. Secrets cross it only inside a
 * [RegistrarConnectRequest] and never enter observable screen state.
 */
interface RegistrarUiGateway {
    /** Offline restore of every registrar slot; never contacts a registrar. */
    suspend fun restore(): Result<RegistrarRestoreUi>

    /** Validates credentials against the registrar API, then saves them encrypted. */
    suspend fun connect(request: RegistrarConnectRequest): Result<RegistrarDashboardUi>

    suspend fun refresh(providerId: String): Result<RegistrarDashboardUi>

    suspend fun disconnect(providerId: String): Result<Unit>

    /** Public IPv4 of this network for Namecheap's ClientIp / Name.com's optional allowlist. */
    suspend fun detectPublicIpv4(): Result<String>
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

    data class Available(val dashboard: RegistrarDashboardUi) : RegistrarProviderRestoreUi

    data class SavedWithoutInventory(val account: RegistrarAccountUi) : RegistrarProviderRestoreUi

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
    val account: RegistrarAccountUi,
    /** Sorted case-insensitively by name, like the iOS dashboard. */
    val domains: List<RegistrarDomainUi>,
    val inventoryComplete: Boolean,
    val warnings: List<String>,
    val fetchedAtMillis: Long,
    val cacheState: RegistrarCacheState,
) {
    val isPartial: Boolean
        get() = !inventoryComplete || warnings.isNotEmpty()
}

/** Only redacted, user-safe messages cross the data/UI boundary. */
class RegistrarUiException(message: String) : Exception(message) {
    override fun toString(): String = "RegistrarUiException(message=$message)"
}
