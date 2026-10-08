package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue

/**
 * The seven generic hosting providers ported from iOS `HostingProviderAPI`. [id] matches
 * `IntegrationCatalog` and is also the storage slot name, so it must never change.
 */
enum class HostingProvider(
    val id: String,
    val displayName: String,
    /** iOS `HostingDashboardView.resourceTitle`. */
    val resourceTitle: String,
    /** iOS `HostingResourceDetailView.historyTitle`. */
    val historyTitle: String,
    /** iOS `AccountProvider.primaryActionLabel`; null when no safe one-tap action exists. */
    val primaryActionLabel: String?,
    /** iOS `AccountProvider.credentialPageURL`. */
    val credentialPageUrl: String,
) {
    RAILWAY("railway", "Railway", "Projects", "Deployments", "Redeploy", "https://railway.com/account/tokens"),
    RENDER("render", "Render", "Services", "Deployments", "Redeploy", "https://dashboard.render.com/u/settings#api-keys"),
    DIGITAL_OCEAN(
        "digitalOcean",
        "DigitalOcean",
        "Apps",
        "Deployments",
        "Redeploy",
        "https://cloud.digitalocean.com/account/api/tokens",
    ),
    HEROKU("heroku", "Heroku", "Apps", "Releases", "Restart", "https://dashboard.heroku.com/account/applications"),
    FLY("fly", "Fly.io", "Apps", "Machines", "Restart", "https://fly.io/user/personal_access_tokens"),
    FIREBASE("firebase", "Firebase Hosting", "Sites", "Deployments", null, "https://console.firebase.google.com/"),
    AWS_AMPLIFY(
        "awsAmplify",
        "AWS Amplify",
        "Apps",
        "Build jobs",
        "Start release",
        "https://console.aws.amazon.com/iam/home#/security_credentials",
    ),
    ;

    companion object {
        fun fromId(id: String?): HostingProvider? = entries.firstOrNull { it.id == id }

        val ids: Set<String> = entries.mapTo(linkedSetOf(), HostingProvider::id)
    }
}

enum class RailwayTokenType(val wireValue: String) {
    ACCOUNT("account"),
    PROJECT("project"),
    ;

    companion object {
        fun fromWireValue(value: String): RailwayTokenType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown Railway token type.")
    }
}

/**
 * Provider credentials. Secrets stay in [SecretValue]; identifiers are validated so they can only
 * ever become an encoded path segment or query value. Every `toString` is redacted.
 */
sealed class HostingCredentials {
    abstract val provider: HostingProvider

    class Railway(val token: SecretValue, val tokenType: RailwayTokenType) : HostingCredentials() {
        override val provider = HostingProvider.RAILWAY
    }

    class Render(val apiKey: SecretValue) : HostingCredentials() {
        override val provider = HostingProvider.RENDER
    }

    class DigitalOcean(val token: SecretValue) : HostingCredentials() {
        override val provider = HostingProvider.DIGITAL_OCEAN
    }

    class Heroku(val token: SecretValue) : HostingCredentials() {
        override val provider = HostingProvider.HEROKU
    }

    class Fly(val token: SecretValue, organization: String) : HostingCredentials() {
        override val provider = HostingProvider.FLY
        val organization: String = requireIdentifier(organization, "Fly.io organization slug")
    }

    /**
     * Firebase stores no secret: Google access tokens come from [GoogleAccessTokenSource]. Each
     * saved Firebase account reads them from its own Google OAuth slot ([googleSlot]); a fresh
     * connection reads the shell's sign-in slot until the gateway adopts it into the account slot.
     * The slot is derived from the saved account id, so it is never persisted with the record.
     */
    class Firebase(
        projectId: String,
        googleSlot: String = FirebaseGoogleSlots.SIGN_IN,
    ) : HostingCredentials() {
        override val provider = HostingProvider.FIREBASE
        val projectId: String = requireIdentifier(projectId, "Firebase project ID")
        val googleSlot: String = googleSlot.also {
            require(FirebaseGoogleSlots.isValid(it)) { "Invalid Firebase Google account slot." }
        }

        fun inGoogleSlot(slot: String): Firebase = Firebase(projectId, slot)
    }

    class AwsAmplify(
        accessKeyId: String,
        val secretAccessKey: SecretValue,
        region: String,
        val sessionToken: SecretValue?,
    ) : HostingCredentials() {
        override val provider = HostingProvider.AWS_AMPLIFY
        val accessKeyId: String = accessKeyId.trim().also {
            require(AWS_ACCESS_KEY_ID.matches(it)) { "Enter a valid AWS access key ID." }
        }
        val region: String = region.trim().also(AwsSigV4Signer::requireStandardRegion)
    }

    override fun toString(): String = when (this) {
        is Railway -> "HostingCredentials.Railway(tokenType=$tokenType, token=<redacted>)"
        is Fly -> "HostingCredentials.Fly(organization=$organization, token=<redacted>)"
        is Firebase -> "HostingCredentials.Firebase(projectId=$projectId, googleSlot=$googleSlot)"
        is AwsAmplify -> "HostingCredentials.AwsAmplify(accessKeyId=…${accessKeyId.takeLast(4)}, " +
            "region=$region, secretAccessKey=<redacted>, sessionToken=" +
            "${if (sessionToken == null) "none" else "<redacted>"})"
        else -> "HostingCredentials.${provider.name}(<redacted>)"
    }

    companion object {
        private val AWS_ACCESS_KEY_ID = Regex("[A-Za-z0-9]{16,128}")
        private const val MAX_IDENTIFIER_CHARACTERS = 128

        /**
         * Validates the non-secret AWS fields first, so a typo never consumes (and clears) the
         * secret inputs. Returns a user-facing problem, or null when both are valid.
         */
        fun awsIdentifierProblem(accessKeyId: String, region: String): String? = when {
            !AWS_ACCESS_KEY_ID.matches(accessKeyId.trim()) -> "Enter a valid AWS access key ID."
            runCatching { AwsSigV4Signer.requireStandardRegion(region.trim()) }.isFailure ->
                "Enter a valid AWS region such as us-east-1."
            else -> null
        }

        internal fun requireIdentifier(value: String, label: String): String {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "Enter the $label." }
            require(trimmed.length <= MAX_IDENTIFIER_CHARACTERS && trimmed.none(Char::isISOControl)) {
                "The $label is not valid."
            }
            return trimmed
        }
    }
}

data class HostingProfile(
    val id: String,
    val name: String,
    val email: String?,
    val avatarUrl: String?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_HOSTING_ID_CHARACTERS) { "Invalid hosting profile id." }
        require(name.isNotBlank() && name.length <= MAX_HOSTING_NAME_CHARACTERS) {
            "Invalid hosting profile name."
        }
        require(email == null || email.length <= MAX_HOSTING_TEXT_CHARACTERS) { "Invalid hosting email." }
        require(avatarUrl == null || avatarUrl.length <= MAX_HOSTING_TEXT_CHARACTERS) {
            "Invalid hosting avatar URL."
        }
    }
}

/** iOS `HostingResource`. */
data class HostingResource(
    val id: String,
    val name: String,
    val subtitle: String?,
    val url: String?,
    val status: String?,
    val region: String?,
    val kind: String?,
    val updatedAtMillis: Long?,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_HOSTING_ID_CHARACTERS) { "Invalid hosting resource id." }
        require(name.isNotBlank() && name.length <= MAX_HOSTING_NAME_CHARACTERS) {
            "Invalid hosting resource name."
        }
        listOf(subtitle, url, status, region, kind).forEach { value ->
            require(value == null || value.length <= MAX_HOSTING_TEXT_CHARACTERS) {
                "Invalid hosting resource field."
            }
        }
        require(updatedAtMillis == null || updatedAtMillis >= 0L) { "Invalid resource update time." }
        require(metadata.size <= MAX_HOSTING_METADATA_ENTRIES) { "Too much resource metadata." }
    }
}

/** iOS `HostingDeployment`. */
data class HostingDeployment(
    val id: String,
    val title: String,
    val status: String,
    val createdAtMillis: Long?,
    val url: String?,
    val branch: String?,
    val commitMessage: String?,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_HOSTING_ID_CHARACTERS) { "Invalid deployment id." }
        require(title.isNotBlank() && title.length <= MAX_HOSTING_NAME_CHARACTERS) { "Invalid deployment title." }
        require(status.isNotBlank() && status.length <= MAX_HOSTING_TEXT_CHARACTERS) {
            "Invalid deployment status."
        }
        require(createdAtMillis == null || createdAtMillis >= 0L) { "Invalid deployment time." }
    }
}

data class HostingSnapshot(
    val provider: HostingProvider,
    val profile: HostingProfile,
    val resources: List<HostingResource>,
    val fetchedAtMillis: Long,
    val warnings: List<String> = emptyList(),
) {
    init {
        require(fetchedAtMillis >= 0L) { "Invalid hosting snapshot timestamp." }
        require(warnings.all { it.isNotBlank() && it.length <= MAX_HOSTING_TEXT_CHARACTERS }) {
            "Invalid hosting warning."
        }
    }
}

/** A connected hosting account. Credentials are deliberately non-printable. */
class HostingAccount(
    val profile: HostingProfile,
    val credentials: HostingCredentials,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val provider: HostingProvider
        get() = credentials.provider

    init {
        require(createdAtMillis >= 0L && updatedAtMillis >= createdAtMillis) {
            "Invalid hosting account timestamps."
        }
    }

    override fun toString(): String =
        "HostingAccount(provider=${provider.id}, profileId=${profile.id}, credentials=<redacted>)"
}

data class HostingStoredConnection(
    val account: HostingAccount,
    val cachedSnapshot: HostingSnapshot?,
) {
    init {
        require(cachedSnapshot == null || cachedSnapshot.provider == account.provider) {
            "The cached hosting snapshot belongs to a different provider."
        }
    }

    override fun toString(): String =
        "HostingStoredConnection(provider=${account.provider.id}, cachedSnapshot=${cachedSnapshot != null}, " +
            "credentials=<redacted>)"
}

enum class HostingFailureKind {
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    RATE_LIMITED,
    TEMPORARY,
    NETWORK,
    INVALID_RESPONSE,
    CONFIGURATION,
    GOOGLE_SIGN_IN_REQUIRED,
    UNSUPPORTED,
    SECURE_STORAGE,
}

data class HostingFailure(
    val kind: HostingFailureKind,
    val message: String,
    val statusCode: Int? = null,
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_HOSTING_TEXT_CHARACTERS) {
            "A hosting failure needs a safe message."
        }
    }
}

/** Carries only app-authored messages; provider response bodies are never echoed. */
class HostingApiException(val failure: HostingFailure) : RuntimeException(failure.message) {
    override fun toString(): String =
        "HostingApiException(kind=${failure.kind}, statusCode=${failure.statusCode})"
}

enum class HostingRestoreProblem {
    SAVED_RECORD_UNREADABLE,
    SECURE_STORAGE_UNAVAILABLE,
}

/** Offline-only restore result for one provider's active account. Never exposes credentials. */
sealed interface HostingRestoreResult {
    data object NotConnected : HostingRestoreResult

    data class Restored(
        val provider: HostingProvider,
        val profile: HostingProfile,
        val cachedSnapshot: HostingSnapshot?,
        val cacheIsStale: Boolean,
        /** Non-secret context needed to build console links (Firebase project, AWS region). */
        val linkContext: HostingLinkContext,
        /** The active saved account this restore describes. */
        val accountId: String = AccountVaultLayout.PRIMARY_ACCOUNT_ID,
    ) : HostingRestoreResult

    data class Unavailable(val problem: HostingRestoreProblem) : HostingRestoreResult
}

/** The non-secret part of a connection that dashboard links depend on. */
data class HostingLinkContext(
    val provider: HostingProvider,
    val firebaseProjectId: String? = null,
    val awsRegion: String? = null,
    /** The Fly.io organization slug; the Complete API explorer starts from its app list. */
    val flyOrganization: String? = null,
) {
    companion object {
        fun of(credentials: HostingCredentials): HostingLinkContext = HostingLinkContext(
            provider = credentials.provider,
            firebaseProjectId = (credentials as? HostingCredentials.Firebase)?.projectId,
            awsRegion = (credentials as? HostingCredentials.AwsAmplify)?.region,
            flyOrganization = (credentials as? HostingCredentials.Fly)?.organization,
        )
    }
}

internal const val MAX_HOSTING_ID_CHARACTERS = 512
internal const val MAX_HOSTING_NAME_CHARACTERS = 1_024
internal const val MAX_HOSTING_TEXT_CHARACTERS = 8_192
internal const val MAX_HOSTING_METADATA_ENTRIES = 16
