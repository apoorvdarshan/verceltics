package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue

data class NetlifyUser(
    val id: String?,
    val uid: String?,
    val fullName: String?,
    val name: String?,
    val email: String?,
    val avatarUrl: String?,
)

data class NetlifyProfile(
    val id: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS) { "Invalid Netlify profile id." }
        require(displayName.isNotBlank() && displayName.length <= MAX_NAME_CHARACTERS) {
            "Invalid Netlify display name."
        }
        require(email == null || email.length <= MAX_URL_CHARACTERS) { "Invalid Netlify email." }
        require(avatarUrl == null || avatarUrl.length <= MAX_URL_CHARACTERS) {
            "Invalid Netlify avatar URL."
        }
    }
}

/** A connected Netlify personal-token account. The token is deliberately non-printable. */
class NetlifyAccount(
    val id: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
    val personalToken: SecretValue,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val providerId: String = PROVIDER_ID

    init {
        NetlifyProfile(id, displayName, email, avatarUrl)
        require(createdAtMillis >= 0L && updatedAtMillis >= createdAtMillis) {
            "Invalid Netlify account timestamps."
        }
    }

    fun profile(): NetlifyProfile = NetlifyProfile(id, displayName, email, avatarUrl)

    override fun toString(): String =
        "NetlifyAccount(id=$id, providerId=$providerId, displayName=$displayName, " +
            "email=$email, avatarUrl=$avatarUrl, personalToken=<redacted>, " +
            "createdAtMillis=$createdAtMillis, updatedAtMillis=$updatedAtMillis)"

    companion object {
        const val PROVIDER_ID: String = "netlify"
    }
}

data class NetlifySite(
    val id: String,
    val name: String,
    val subtitle: String?,
    val url: String?,
    val status: String?,
    val updatedAtMillis: Long?,
    val adminUrl: String?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS) { "Invalid Netlify site id." }
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARACTERS) { "Invalid Netlify site name." }
        require(subtitle == null || subtitle.length <= MAX_URL_CHARACTERS) { "Invalid site subtitle." }
        require(url == null || url.length <= MAX_URL_CHARACTERS) { "Invalid site URL." }
        require(status == null || status.length <= MAX_STATUS_CHARACTERS) { "Invalid site status." }
        require(updatedAtMillis == null || updatedAtMillis >= 0L) { "Invalid site update time." }
        require(adminUrl == null || adminUrl.length <= MAX_URL_CHARACTERS) { "Invalid admin URL." }
    }
}

enum class NetlifyDomainKind {
    CUSTOM,
    ALIAS,
    NETLIFY_SUBDOMAIN,
}

data class NetlifyDomain(
    val name: String,
    val kind: NetlifyDomainKind,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_DOMAIN_CHARACTERS) {
            "Invalid Netlify domain."
        }
    }
}

/** Read-only build configuration. No mutation endpoint is represented by this model. */
data class NetlifyBuildControls(
    val buildsStopped: Boolean?,
    val repositoryUrl: String?,
    val repositoryPath: String?,
    val repositoryBranch: String?,
    val baseDirectory: String?,
    val publishDirectory: String?,
    val functionsDirectory: String?,
    val buildCommand: String?,
    val allowedBranches: List<String>,
    val provider: String?,
) {
    init {
        require(allowedBranches.size <= MAX_NESTED_ITEMS) { "Too many Netlify build branches." }
    }
}

data class NetlifyDeployment(
    val id: String,
    val title: String,
    val status: String,
    val createdAtMillis: Long?,
    val url: String?,
    val branch: String?,
    val commitMessage: String?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS) { "Invalid Netlify deploy id." }
        require(title.isNotBlank() && title.length <= MAX_NAME_CHARACTERS) { "Invalid deploy title." }
        require(status.isNotBlank() && status.length <= MAX_STATUS_CHARACTERS) { "Invalid deploy status." }
        require(createdAtMillis == null || createdAtMillis >= 0L) { "Invalid deploy creation time." }
        require(url == null || url.length <= MAX_URL_CHARACTERS) { "Invalid deploy URL." }
        require(branch == null || branch.length <= MAX_NAME_CHARACTERS) { "Invalid deploy branch." }
        require(commitMessage == null || commitMessage.length <= MAX_URL_CHARACTERS) {
            "Invalid deploy commit message."
        }
    }
}

data class NetlifyBuild(
    val id: String,
    val deploymentId: String?,
    val commitSha: String?,
    val isDone: Boolean?,
    val error: String?,
    val createdAtMillis: Long?,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_CHARACTERS) { "Invalid Netlify build id." }
        require(deploymentId == null || deploymentId.length <= MAX_ID_CHARACTERS) {
            "Invalid Netlify build deploy id."
        }
        require(commitSha == null || commitSha.length <= MAX_ID_CHARACTERS) {
            "Invalid Netlify build commit."
        }
        require(error == null || error.length <= MAX_URL_CHARACTERS) { "Invalid Netlify build error." }
        require(createdAtMillis == null || createdAtMillis >= 0L) { "Invalid build creation time." }
    }
}

data class NetlifySiteDetails(
    val site: NetlifySite,
    val domains: List<NetlifyDomain>,
    val buildControls: NetlifyBuildControls?,
    val publishedDeployment: NetlifyDeployment?,
) {
    init {
        require(domains.size <= MAX_NESTED_ITEMS) { "Too many Netlify site domains." }
    }
}

data class NetlifySnapshot(
    val profile: NetlifyProfile,
    val sites: List<NetlifySite>,
    val fetchedAtMillis: Long,
    val sitesComplete: Boolean,
    val warnings: List<String>,
) {
    init {
        require(fetchedAtMillis >= 0L) { "Invalid Netlify snapshot timestamp." }
        require(warnings.isNotEmpty() == !sitesComplete) {
            "Netlify completeness and warnings disagree."
        }
        require(warnings.all { it.isNotBlank() && it.length <= MAX_WARNING_CHARACTERS }) {
            "Invalid Netlify warning."
        }
    }
}

enum class NetlifyFailureKind {
    AUTHENTICATION,
    RATE_LIMITED,
    NOT_FOUND,
    TEMPORARY,
    NETWORK,
    INVALID_RESPONSE,
    CONFIGURATION,
    SECURE_STORAGE,
}

data class NetlifyFailure(
    val kind: NetlifyFailureKind,
    val message: String,
    val statusCode: Int? = null,
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_WARNING_CHARACTERS) {
            "A Netlify failure needs a safe message."
        }
    }
}

sealed interface NetlifyFetchResult {
    data class Complete(val snapshot: NetlifySnapshot) : NetlifyFetchResult {
        init {
            require(snapshot.sitesComplete && snapshot.warnings.isEmpty())
        }
    }

    data class Partial(val snapshot: NetlifySnapshot, val failure: NetlifyFailure) : NetlifyFetchResult {
        init {
            require(!snapshot.sitesComplete && snapshot.warnings.isNotEmpty())
        }
    }

    data class Failure(val failure: NetlifyFailure) : NetlifyFetchResult
}

sealed interface NetlifyCollectionResult<out T> {
    data class Complete<T>(val items: List<T>) : NetlifyCollectionResult<T>

    data class Partial<T>(
        val items: List<T>,
        val failure: NetlifyFailure,
        val completedPages: Int,
    ) : NetlifyCollectionResult<T> {
        init {
            require(items.isNotEmpty() && completedPages > 0) {
                "A partial collection must contain successfully loaded pages."
            }
        }
    }

    data class Failure(val failure: NetlifyFailure) : NetlifyCollectionResult<Nothing>
}

sealed interface NetlifyResourceResult<out T> {
    data class Complete<T>(val value: T) : NetlifyResourceResult<T>

    data class Failure(val failure: NetlifyFailure) : NetlifyResourceResult<Nothing>
}

data class NetlifyStoredConnection(
    val account: NetlifyAccount,
    val cachedSnapshot: NetlifySnapshot?,
) {
    init {
        require(cachedSnapshot == null || cachedSnapshot.profile.id == account.id) {
            "The cached Netlify snapshot belongs to a different account."
        }
    }

    override fun toString(): String =
        "NetlifyStoredConnection(accountId=${account.id}, cachedSnapshot=${cachedSnapshot != null}, " +
            "personalToken=<redacted>)"
}

/** One saved Netlify account for the account menu. Never carries the token. */
data class NetlifySavedAccount(
    val accountId: String,
    /** Null when the record exists but could not be opened (it is kept, never deleted). */
    val profile: NetlifyProfile?,
    val isActive: Boolean,
)

enum class NetlifyRestoreProblem {
    SAVED_RECORD_UNREADABLE,
    SECURE_STORAGE_UNAVAILABLE,
}

/** Offline-only state. Restore never contacts Netlify and never exposes the saved token. */
sealed interface NetlifyRestoreResult {
    data object NotConnected : NetlifyRestoreResult

    data class Restored(
        val profile: NetlifyProfile,
        val cachedSnapshot: NetlifySnapshot?,
        val cacheIsStale: Boolean,
        /** The active saved account this restore describes. */
        val accountId: String = com.apoorvdarshan.verceltics.data.hosting.AccountVaultLayout.PRIMARY_ACCOUNT_ID,
    ) : NetlifyRestoreResult

    data class Unavailable(val problem: NetlifyRestoreProblem) : NetlifyRestoreResult
}

internal const val MAX_ID_CHARACTERS = 512
internal const val MAX_NAME_CHARACTERS = 1_024
internal const val MAX_STATUS_CHARACTERS = 256
internal const val MAX_URL_CHARACTERS = 8_192
internal const val MAX_DOMAIN_CHARACTERS = 2_048
internal const val MAX_WARNING_CHARACTERS = 2_048
internal const val MAX_NESTED_ITEMS = 256
