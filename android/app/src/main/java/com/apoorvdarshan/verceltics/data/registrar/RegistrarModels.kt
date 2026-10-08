package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.util.Locale

/**
 * The eight domain registrars supported natively. [id] matches the provider ids used by
 * `IntegrationCatalog`; copy, credential links and dashboard links mirror the iOS
 * `RegistrarProvider` model.
 */
enum class RegistrarProvider(
    val id: String,
    val displayName: String,
    val apiDescription: String,
    /** The exact HTTPS origin requests are allowed to reach. */
    internal val origin: String,
    /** Path prefix of the documented API base URL (the policy only keeps the origin). */
    internal val apiPathPrefix: String,
    val credentialUrl: String,
    val dashboardUrl: String,
) {
    NAME_DOT_COM(
        id = "nameDotCom",
        displayName = "Name.com",
        apiDescription = "Domains, DNS, renewals, transfers and privacy",
        origin = "https://api.name.com/",
        apiPathPrefix = "",
        credentialUrl = "https://www.name.com/account/settings/api",
        dashboardUrl = "https://www.name.com/account/domain",
    ),
    NAMECHEAP(
        id = "namecheap",
        displayName = "Namecheap",
        apiDescription = "Domains, DNS, contacts, renewals and transfers",
        origin = "https://api.namecheap.com/",
        apiPathPrefix = "",
        credentialUrl = "https://ap.www.namecheap.com/settings/tools/apiaccess/",
        dashboardUrl = "https://ap.www.namecheap.com/domains/domainlist",
    ),
    PORKBUN(
        id = "porkbun",
        displayName = "Porkbun",
        apiDescription = "Domains, DNS, SSL, forwarding and marketplace",
        origin = "https://api.porkbun.com/",
        apiPathPrefix = "/api/json/v3",
        credentialUrl = "https://porkbun.com/account/api",
        dashboardUrl = "https://porkbun.com/account/domainsSpeedy",
    ),
    SPACESHIP(
        id = "spaceship",
        displayName = "Spaceship",
        apiDescription = "Domains, contacts, DNS and nameservers",
        origin = "https://spaceship.dev/",
        apiPathPrefix = "/api",
        credentialUrl = "https://www.spaceship.com/application/api-manager/",
        dashboardUrl = "https://www.spaceship.com/application/domain-list-application/",
    ),
    DYNADOT(
        id = "dynadot",
        displayName = "Dynadot",
        apiDescription = "Domains, DNS, renewals, auctions and aftermarket",
        origin = "https://api.dynadot.com/",
        apiPathPrefix = "",
        credentialUrl = "https://www.dynadot.com/account/domain/setting/api.html",
        dashboardUrl = "https://www.dynadot.com/account/domain/name/list.html",
    ),
    NAME_SILO(
        id = "nameSilo",
        displayName = "NameSilo",
        apiDescription = "Domains, DNS, renewals, contacts and transfers",
        origin = "https://www.namesilo.com/",
        apiPathPrefix = "",
        credentialUrl = "https://www.namesilo.com/account/api-manager",
        dashboardUrl = "https://www.namesilo.com/account_domains.php",
    ),
    GANDI(
        id = "gandi",
        displayName = "Gandi",
        apiDescription = "Domains, LiveDNS, certificates, mail and billing",
        origin = "https://api.gandi.net/",
        apiPathPrefix = "",
        credentialUrl = "https://admin.gandi.net/organizations",
        dashboardUrl = "https://admin.gandi.net/domain",
    ),
    GO_DADDY(
        id = "goDaddy",
        displayName = "GoDaddy",
        apiDescription = "Domains, DNS, renewals, privacy and transfers",
        origin = "https://api.godaddy.com/",
        apiPathPrefix = "",
        credentialUrl = "https://developer.godaddy.com/keys",
        dashboardUrl = "https://dcc.godaddy.com/portfolio",
    );

    /** Porkbun, Spaceship and GoDaddy authenticate with a key and a separate secret. */
    val requiresSecret: Boolean
        get() = this == PORKBUN || this == SPACESHIP || this == GO_DADDY

    /** Name.com and Namecheap both need the account/API username. */
    val requiresUsername: Boolean
        get() = this == NAME_DOT_COM || this == NAMECHEAP

    /** Namecheap requires the whitelisted public IPv4 on every request. */
    val requiresClientIp: Boolean
        get() = this == NAMECHEAP

    val acceptsOrganizationLabel: Boolean
        get() = this == GANDI

    /** iOS shows the public network address helper for these two registrars. */
    val showsPublicIpv4Helper: Boolean
        get() = this == NAMECHEAP || this == NAME_DOT_COM

    /** Lowercase stable token used for encrypted storage paths and authenticated data. */
    internal val storageSlug: String
        get() = id.lowercase(Locale.ROOT)

    companion object {
        fun fromId(id: String?): RegistrarProvider? = entries.firstOrNull { it.id == id }

        val ids: List<String> = entries.map(RegistrarProvider::id)
    }
}

/** Metadata keys shared with the iOS `RegistrarAccount.metadata` dictionary. */
object RegistrarMetadataKeys {
    const val USERNAME: String = "username"
    const val CLIENT_IP: String = "clientIP"
    const val ORGANIZATION: String = "organization"

    internal val ALL: Set<String> = setOf(USERNAME, CLIENT_IP, ORGANIZATION)
}

/**
 * Validated registrar credentials. Secrets stay wrapped in [SecretValue]; non-secret metadata
 * (username, client IP, organization label) is plain text. The object is deliberately
 * non-printable.
 */
class RegistrarCredentials internal constructor(
    val provider: RegistrarProvider,
    val primary: SecretValue,
    val secondary: SecretValue?,
    metadata: Map<String, String>,
) {
    val metadata: Map<String, String> = metadata.toMap()

    init {
        require(this.metadata.keys.all { it in RegistrarMetadataKeys.ALL }) {
            "Unsupported registrar metadata."
        }
        require(this.metadata.values.all { it.isNotBlank() && it.length <= MAX_METADATA_CHARACTERS }) {
            "Invalid registrar metadata."
        }
        require(!provider.requiresSecret || secondary != null) { "Enter the API secret." }
    }

    fun metadataValue(key: String): String? = metadata[key]?.takeIf(String::isNotBlank)

    /** Same credentials, compared in constant time for the secret parts. */
    fun sameAs(other: RegistrarCredentials): Boolean =
        provider == other.provider &&
            primary == other.primary &&
            secondary == other.secondary &&
            metadata == other.metadata

    override fun toString(): String =
        "RegistrarCredentials(provider=${provider.id}, primary=<redacted>, " +
            "secondary=${if (secondary == null) "null" else "<redacted>"}, " +
            "metadataKeys=${metadata.keys.sorted()})"

    companion object {
        internal const val MAX_METADATA_CHARACTERS = 512

        /**
         * Port of the iOS connection form + `RegistrarStore.connect` normalization: values are
         * trimmed, Name.com keeps only the username, Namecheap stores the canonical public IPv4,
         * and Gandi keeps the optional organization label.
         */
        fun fromInput(
            provider: RegistrarProvider,
            apiKey: SecretValue,
            apiSecret: SecretValue?,
            username: String = "",
            clientIp: String = "",
            organization: String = "",
        ): RegistrarCredentials {
            val primary = apiKey.use { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) throw RegistrarApiException.invalidConfiguration("Enter the API key.")
                SecretValue.of(trimmed)
            }
            val secondary = if (provider.requiresSecret) {
                val secret = apiSecret ?: throw RegistrarApiException.invalidConfiguration("Enter the API secret.")
                secret.use { raw ->
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) throw RegistrarApiException.invalidConfiguration("Enter the API secret.")
                    SecretValue.of(trimmed)
                }
            } else {
                null
            }
            val metadata = linkedMapOf<String, String>()
            when (provider) {
                RegistrarProvider.NAME_DOT_COM -> {
                    val user = username.trim()
                    if (user.isEmpty()) {
                        throw RegistrarApiException.invalidConfiguration("Enter the Name.com API username.")
                    }
                    metadata[RegistrarMetadataKeys.USERNAME] = user
                }
                RegistrarProvider.NAMECHEAP -> {
                    val user = username.trim()
                    if (user.isEmpty()) {
                        throw RegistrarApiException.invalidConfiguration("Enter the Namecheap API username.")
                    }
                    val ip = PublicIpv4Lookup.normalizedPublicIpv4(clientIp)
                        ?: throw RegistrarApiException.invalidConfiguration(
                            "Enter a valid public IPv4 address that is whitelisted in Namecheap.",
                        )
                    metadata[RegistrarMetadataKeys.USERNAME] = user
                    metadata[RegistrarMetadataKeys.CLIENT_IP] = ip
                }
                RegistrarProvider.GANDI -> {
                    organization.trim().takeIf(String::isNotEmpty)?.let {
                        metadata[RegistrarMetadataKeys.ORGANIZATION] = it.take(MAX_METADATA_CHARACTERS)
                    }
                }
                else -> Unit
            }
            metadata.values.forEach { value ->
                if (value.length > MAX_METADATA_CHARACTERS) {
                    throw RegistrarApiException.invalidConfiguration("A registrar field is too long.")
                }
            }
            return RegistrarCredentials(provider, primary, secondary, metadata)
        }
    }
}

/** Normalized domain from any registrar list endpoint (iOS `RegistrarDomain`). */
data class RegistrarDomain(
    val name: String,
    val status: String?,
    val createdAtMillis: Long?,
    val expiresAtMillis: Long?,
    val autoRenew: Boolean?,
    val locked: Boolean?,
    val privacyEnabled: Boolean?,
    val nameservers: List<String>,
    val metadata: Map<String, String>,
) {
    val id: String
        get() = name.lowercase(Locale.ROOT)

    /** Whole days until expiry, truncated toward zero like `Calendar.dateComponents(.day)`. */
    fun daysUntilExpiry(nowMillis: Long): Int? = expiresAtMillis?.let { registrarDaysUntil(it, nowMillis) }

    companion object {
        internal fun named(name: String): RegistrarDomain = RegistrarDomain(
            name = name,
            status = null,
            createdAtMillis = null,
            expiresAtMillis = null,
            autoRenew = null,
            locked = null,
            privacyEnabled = null,
            nameservers = emptyList(),
            metadata = emptyMap(),
        )
    }
}

/** Shared day arithmetic for data and UI layers. */
fun registrarDaysUntil(expiresAtMillis: Long, nowMillis: Long): Int {
    val days = (expiresAtMillis - nowMillis) / MILLIS_PER_DAY
    return days.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

internal const val MILLIS_PER_DAY: Long = 86_400_000L

/** Result of a successful credential validation: the account label and the full portfolio. */
data class RegistrarValidation(
    val accountName: String,
    val domains: List<RegistrarDomain>,
)

enum class RegistrarFailureKind {
    CONFIGURATION,
    REQUEST_FAILED,
    INVALID_RESPONSE,
    DECODING,
    NETWORK,
}

/** User-safe failure. Messages mirror iOS `RegistrarAPIError.errorDescription`. */
class RegistrarApiException(
    val kind: RegistrarFailureKind,
    override val message: String,
    val statusCode: Int? = null,
) : RuntimeException(message) {
    override fun toString(): String =
        "RegistrarApiException(kind=$kind, statusCode=$statusCode, message=$message)"

    companion object {
        fun invalidConfiguration(message: String): RegistrarApiException =
            RegistrarApiException(RegistrarFailureKind.CONFIGURATION, message)

        fun invalidResponse(): RegistrarApiException = RegistrarApiException(
            RegistrarFailureKind.INVALID_RESPONSE,
            "The registrar returned an invalid response.",
        )

        fun requestFailed(statusCode: Int, message: String): RegistrarApiException =
            RegistrarApiException(
                kind = RegistrarFailureKind.REQUEST_FAILED,
                message = if (message.isEmpty()) {
                    "Request failed (HTTP $statusCode)."
                } else {
                    "Request failed (HTTP $statusCode): $message"
                },
                statusCode = statusCode,
            )

        fun decoding(message: String): RegistrarApiException = RegistrarApiException(
            RegistrarFailureKind.DECODING,
            "Could not read the registrar response: $message",
        )

        fun network(provider: RegistrarProvider): RegistrarApiException = RegistrarApiException(
            RegistrarFailureKind.NETWORK,
            "${provider.displayName} could not be reached. Check your connection and try again.",
        )
    }
}

/** Account label plus the encrypted credentials for one registrar slot. */
class RegistrarAccount(
    val displayName: String,
    val credentials: RegistrarCredentials,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val provider: RegistrarProvider
        get() = credentials.provider

    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_ACCOUNT_NAME_CHARACTERS) {
            "Invalid registrar account name."
        }
        require(createdAtMillis >= 0L && updatedAtMillis >= createdAtMillis) {
            "Invalid registrar account timestamps."
        }
    }

    override fun toString(): String =
        "RegistrarAccount(provider=${provider.id}, displayName=$displayName, credentials=<redacted>, " +
            "createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis)"

    companion object {
        internal const val MAX_ACCOUNT_NAME_CHARACTERS = 512
    }
}

/** Domain portfolio snapshot; incomplete only when the offline cache had to be bounded. */
data class RegistrarSnapshot(
    val provider: RegistrarProvider,
    val accountName: String,
    val domains: List<RegistrarDomain>,
    val fetchedAtMillis: Long,
    val domainsComplete: Boolean,
    val warnings: List<String>,
) {
    init {
        require(fetchedAtMillis >= 0L) { "Invalid registrar snapshot timestamp." }
        require(warnings.isNotEmpty() == !domainsComplete) {
            "Registrar completeness and warnings disagree."
        }
    }
}

class RegistrarStoredConnection(
    val account: RegistrarAccount,
    val cachedSnapshot: RegistrarSnapshot?,
) {
    init {
        require(cachedSnapshot == null || cachedSnapshot.provider == account.provider) {
            "The cached registrar snapshot belongs to a different provider."
        }
    }

    override fun toString(): String =
        "RegistrarStoredConnection(provider=${account.provider.id}, " +
            "cachedSnapshot=${cachedSnapshot != null}, credentials=<redacted>)"
}

enum class RegistrarRestoreProblem {
    SAVED_RECORD_UNREADABLE,
    SECURE_STORAGE_UNAVAILABLE,
}

/** Offline-only state for one registrar slot; never exposes saved credentials. */
sealed interface RegistrarRestoreResult {
    data object NotConnected : RegistrarRestoreResult

    data class Restored(
        val provider: RegistrarProvider,
        val accountName: String,
        val cachedSnapshot: RegistrarSnapshot?,
        val cacheIsStale: Boolean,
    ) : RegistrarRestoreResult

    data class Unavailable(val problem: RegistrarRestoreProblem) : RegistrarRestoreResult
}
