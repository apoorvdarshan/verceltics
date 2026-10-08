package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue

data class CloudflareTokenVerification(
    val id: String?,
    val status: String,
    val notBefore: String?,
    val expiresOn: String?,
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true)

    init {
        require(id == null || id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(status.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
        require(notBefore == null || notBefore.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
        require(expiresOn == null || expiresOn.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
    }
}

/** `/user` for email + Global API Key connections (iOS `CloudflareUser`). */
data class CloudflareUserIdentity(
    val id: String,
    val email: String,
    val firstName: String?,
    val lastName: String?,
    val suspended: Boolean?,
) {
    /** iOS `CloudflareUser.displayName`: the full name, falling back to the email. */
    val displayName: String
        get() = listOfNotNull(firstName?.trim(), lastName?.trim())
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .ifEmpty { email }

    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(email.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(firstName == null || firstName.length <= CF_MAX_NAME_CHARACTERS)
        require(lastName == null || lastName.length <= CF_MAX_NAME_CHARACTERS)
    }
}

/**
 * Connection identity, matching iOS's two login modes.
 *
 * API-token connections synthesize it from `/user/tokens/verify` plus the first accessible
 * account. Global API Key connections use the `/user` profile and keep the login [email] so the
 * dashboard can label the credential like iOS (`email ?? "Scoped API token"`).
 */
data class CloudflareProfile(
    val id: String,
    val displayName: String,
    val tokenStatus: String,
    val authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN,
    val email: String? = null,
) {
    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(displayName.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(tokenStatus.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
        require(email == null || email.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require((authMode == CloudflareAuthMode.GLOBAL_API_KEY) == (email != null)) {
            "Only Global API Key profiles carry a login email."
        }
    }
}

data class CloudflareAccountSummary(
    val id: String,
    val name: String,
    val type: String?,
    val createdOn: String?,
) {
    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(name.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(type == null || type.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
        require(createdOn == null || createdOn.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
    }
}

data class CloudflareZone(
    val id: String,
    val name: String,
    val status: String?,
    val type: String?,
    val paused: Boolean?,
    val accountId: String?,
    val accountName: String?,
    val planName: String?,
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true) && paused != true

    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(name.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(status == null || status.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
        require(type == null || type.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
        require(accountId == null || accountId.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(accountName == null || accountName.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(planName == null || planName.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
    }
}

data class CloudflarePagesProject(
    val id: String,
    val name: String,
    val subdomain: String?,
    val domains: List<String>,
    val productionBranch: String?,
    val createdOn: String?,
    val latestDeploymentStatus: String?,
) {
    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(name.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(subdomain == null || subdomain.isSafeCloudflareText(CF_MAX_URL_CHARACTERS))
        require(domains.size <= CF_MAX_NESTED_ITEMS)
        require(domains.all { it.isSafeCloudflareText(CF_MAX_DOMAIN_CHARACTERS) })
        require(productionBranch == null || productionBranch.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS))
        require(createdOn == null || createdOn.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
        require(latestDeploymentStatus == null || latestDeploymentStatus.isSafeCloudflareText(CF_MAX_STATUS_CHARACTERS))
    }
}

data class CloudflareWorkerScript(
    val id: String,
    val createdOn: String?,
    val modifiedOn: String?,
    val compatibilityDate: String?,
    val handlers: List<String>,
    val hasAssets: Boolean?,
    val hasModules: Boolean?,
    /** Route patterns (`routes[].pattern`), searchable like iOS. */
    val routes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    init {
        require(id.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(createdOn == null || createdOn.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
        require(modifiedOn == null || modifiedOn.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
        require(compatibilityDate == null || compatibilityDate.isSafeCloudflareText(CF_MAX_DATE_CHARACTERS))
        require(handlers.size <= CF_MAX_NESTED_ITEMS)
        require(handlers.all { it.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS) })
        require(routes.size <= CF_MAX_NESTED_ITEMS)
        require(routes.all { it.isSafeCloudflareText(CF_MAX_DOMAIN_CHARACTERS) })
        require(tags.size <= CF_MAX_NESTED_ITEMS)
        require(tags.all { it.isSafeCloudflareText(CF_MAX_NAME_CHARACTERS) })
    }
}

data class CloudflarePage<T>(
    val items: List<T>,
    val page: Int?,
    val totalPages: Int?,
) {
    init {
        require(page == null || page >= 0)
        require(totalPages == null || totalPages >= 0)
    }
}

enum class CloudflareFailureKind {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    NOT_FOUND,
    TEMPORARY,
    NETWORK,
    INVALID_RESPONSE,
    CONFIGURATION,
    SECURE_STORAGE,
    PROVIDER_REJECTED,
}

data class CloudflareFailure(
    val kind: CloudflareFailureKind,
    val message: String,
    val statusCode: Int? = null,
) {
    init {
        require(message.isSafeCloudflareText(CF_MAX_WARNING_CHARACTERS))
    }
}

sealed interface CloudflareCollectionResult<out T> {
    data class Complete<T>(val items: List<T>) : CloudflareCollectionResult<T>

    data class Partial<T>(
        val items: List<T>,
        val failure: CloudflareFailure,
        val completedPages: Int,
    ) : CloudflareCollectionResult<T> {
        init {
            require(items.isNotEmpty() && completedPages > 0)
        }
    }

    data class Failure(val failure: CloudflareFailure) : CloudflareCollectionResult<Nothing>
}

data class CloudflareAccountInventory(
    val accountId: String,
    val zones: List<CloudflareZone>,
    val pagesProjects: List<CloudflarePagesProject>,
    val workers: List<CloudflareWorkerScript>,
    val zonesComplete: Boolean,
    val pagesComplete: Boolean,
    val workersComplete: Boolean,
    val warnings: List<String>,
) {
    val isComplete: Boolean get() = zonesComplete && pagesComplete && workersComplete

    init {
        require(accountId.isSafeCloudflareText(CF_MAX_ID_CHARACTERS))
        require(warnings.isEmpty() == isComplete)
        require(warnings.all { it.isSafeCloudflareText(CF_MAX_WARNING_CHARACTERS) })
    }
}

data class CloudflareSnapshot(
    val profile: CloudflareProfile,
    val accounts: List<CloudflareAccountSummary>,
    val selectedAccountId: String?,
    val selectedAccountInventory: CloudflareAccountInventory?,
    val accountsComplete: Boolean,
    val fetchedAtMillis: Long,
    val warnings: List<String>,
) {
    val isComplete: Boolean
        get() = accountsComplete && (selectedAccountId == null || selectedAccountInventory?.isComplete == true)

    init {
        require(fetchedAtMillis >= 0L)
        require(selectedAccountId == null || accounts.any { it.id == selectedAccountId })
        require((selectedAccountId == null) == (selectedAccountInventory == null))
        require(selectedAccountInventory == null || selectedAccountInventory.accountId == selectedAccountId)
        require(warnings.isEmpty() == isComplete)
        require(warnings.all { it.isSafeCloudflareText(CF_MAX_WARNING_CHARACTERS) })
    }
}

sealed interface CloudflareFetchResult {
    data class Complete(val snapshot: CloudflareSnapshot) : CloudflareFetchResult {
        init {
            require(snapshot.isComplete)
        }
    }

    data class Partial(
        val snapshot: CloudflareSnapshot,
        val failures: List<CloudflareFailure>,
    ) : CloudflareFetchResult {
        init {
            require(!snapshot.isComplete && failures.isNotEmpty())
        }
    }

    data class Failure(val failure: CloudflareFailure) : CloudflareFetchResult
}

/**
 * A connected Cloudflare credential (scoped API token or email + Global API Key). The secret is
 * never printable or exposed by restore models.
 */
class CloudflareAccount(
    val profile: CloudflareProfile,
    val credential: CloudflareCredential,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    constructor(
        profile: CloudflareProfile,
        apiToken: SecretValue,
        createdAtMillis: Long,
        updatedAtMillis: Long,
    ) : this(profile, CloudflareCredential.ApiToken(apiToken), createdAtMillis, updatedAtMillis)

    val providerId: String = PROVIDER_ID

    /** The scoped API token, or null for Global API Key connections. */
    val apiToken: SecretValue? get() = (credential as? CloudflareCredential.ApiToken)?.token

    init {
        require(createdAtMillis >= 0L && updatedAtMillis >= createdAtMillis)
        require(profile.authMode == credential.authMode) {
            "The Cloudflare profile and credential use different authentication modes."
        }
        require(
            credential !is CloudflareCredential.GlobalApiKey || profile.email == credential.email,
        ) { "The Cloudflare profile email does not match its Global API Key credential." }
    }

    override fun toString(): String =
        "CloudflareAccount(profileId=${profile.id}, providerId=$providerId, authMode=${credential.authMode}, " +
            "credential=<redacted>, createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis)"

    companion object {
        const val PROVIDER_ID = "cloudflare"
    }
}

data class CloudflareStoredConnection(
    val account: CloudflareAccount,
    val cachedSnapshot: CloudflareSnapshot?,
) {
    init {
        require(cachedSnapshot == null || cachedSnapshot.profile.id == account.profile.id)
    }

    override fun toString(): String =
        "CloudflareStoredConnection(profileId=${account.profile.id}, " +
            "cachedSnapshot=${cachedSnapshot != null}, credential=<redacted>)"
}

enum class CloudflareRestoreProblem {
    SAVED_RECORD_UNREADABLE,
    SECURE_STORAGE_UNAVAILABLE,
}

sealed interface CloudflareRestoreResult {
    data object NotConnected : CloudflareRestoreResult

    data class Restored(
        val profile: CloudflareProfile,
        val cachedSnapshot: CloudflareSnapshot?,
        val cacheIsStale: Boolean,
    ) : CloudflareRestoreResult

    data class Unavailable(val problem: CloudflareRestoreProblem) : CloudflareRestoreResult
}

internal fun String.isSafeCloudflareText(maximumCharacters: Int): Boolean =
    isNotBlank() && length <= maximumCharacters && none { it == '\u0000' }

internal const val CF_MAX_ID_CHARACTERS = 512
internal const val CF_MAX_NAME_CHARACTERS = 1_024
internal const val CF_MAX_STATUS_CHARACTERS = 256
internal const val CF_MAX_DATE_CHARACTERS = 256
internal const val CF_MAX_URL_CHARACTERS = 8_192
internal const val CF_MAX_DOMAIN_CHARACTERS = 2_048
internal const val CF_MAX_WARNING_CHARACTERS = 2_048
internal const val CF_MAX_NESTED_ITEMS = 256
