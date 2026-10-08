package com.apoorvdarshan.verceltics.data.vercel

import java.util.Locale

data class VercelUser(
    val id: String,
    val username: String,
    val email: String?,
    val name: String?,
    /** `/v2/user` `avatar`: usually an avatar hash, see [VercelAvatarPolicy]. */
    val avatarUrl: String?,
) {
    /** The profile name, or the username when no name is set (iOS `name ?? username`). */
    val displayName: String
        get() = name?.takeIf(String::isNotBlank) ?: username
}

/** Where a project was listed from: the personal scope or a confirmed team membership. */
data class VercelProjectScope(
    val id: String?,
    val name: String,
    val slug: String?,
    val isTeam: Boolean,
) {
    companion object {
        val PERSONAL = VercelProjectScope(id = null, name = "Personal", slug = null, isTeam = false)
    }
}

/** One entry of a project's `latestDeployments` collection. */
data class VercelProjectDeployment(
    val createdAtMillis: Long?,
    val aliases: List<String> = emptyList(),
    val commitMessage: String? = null,
)

data class VercelGitLink(
    val org: String?,
    val repo: String?,
) {
    /** `org/repo` only when both halves are present, matching the iOS project card. */
    val fullName: String?
        get() {
            val owner = org?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val name = repo?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return "$owner/$name"
        }
}

data class VercelEnvironmentDomain(
    val name: String,
    val redirect: String?,
)

data class VercelCustomEnvironment(
    val type: String?,
    val domains: List<VercelEnvironmentDomain> = emptyList(),
    val currentDeploymentAliases: List<String> = emptyList(),
) {
    /** Non-redirecting domains first, then redirects, like iOS `preferredDomains`. */
    val preferredDomains: List<String>
        get() = domains.filter { it.redirect == null }.map(VercelEnvironmentDomain::name) +
            domains.filter { it.redirect != null }.map(VercelEnvironmentDomain::name)
}

data class VercelProject(
    val id: String,
    val name: String,
    val framework: String?,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
    val teamId: String? = null,
    val accountId: String? = null,
    val latestDeployments: List<VercelProjectDeployment> = emptyList(),
    val productionAliases: List<String> = emptyList(),
    val link: VercelGitLink? = null,
    val aliasDomains: List<String> = emptyList(),
    val customEnvironments: List<VercelCustomEnvironment> = emptyList(),
    val sourceScope: VercelProjectScope? = null,
) {
    val lastDeployment: VercelProjectDeployment?
        get() = latestDeployments.firstOrNull()

    /**
     * The best public domain for the project: the first custom domain, otherwise the shortest
     * `*.vercel.app` alias. Mirrors `Project.primaryDomain` on iOS, including source order.
     */
    val primaryDomain: String?
        get() {
            val production = customEnvironments.firstOrNull { it.type == "production" }
            val candidates = deduplicatedDomains(
                listOf(
                    lastDeployment?.aliases.orEmpty(),
                    production?.currentDeploymentAliases.orEmpty(),
                    productionAliases,
                    aliasDomains,
                    production?.preferredDomains.orEmpty(),
                ),
            )
            if (candidates.isEmpty()) return null
            candidates.firstOrNull { !isVercelDomain(it) }?.let { return it }
            return candidates.filter(::isVercelDomain).minByOrNull(String::length)
        }

    /** True when listing data alone cannot name a custom domain for the project. */
    val needsPrimaryDomainRefresh: Boolean
        get() = primaryDomain?.let(::isVercelDomain) ?: true

    /** Adds verified project domains so [primaryDomain] can prefer them. */
    fun withAdditionalDomains(domains: List<String>): VercelProject =
        if (domains.isEmpty()) this else copy(aliasDomains = aliasDomains + domains)

    fun withSourceScope(scope: VercelProjectScope): VercelProject = copy(sourceScope = scope)

    companion object {
        fun isVercelDomain(domain: String): Boolean {
            val normalized = domain.trim().lowercase(Locale.ROOT)
            return normalized == "vercel.app" || normalized.endsWith(".vercel.app")
        }

        private fun deduplicatedDomains(groups: List<List<String>>): List<String> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            groups.forEach { group ->
                group.forEach { domain ->
                    val normalized = domain.trim()
                    if (normalized.isNotEmpty() && seen.add(normalized.lowercase(Locale.ROOT))) {
                        result += normalized
                    }
                }
            }
            return result
        }
    }
}

data class VercelProjectsPage(
    val projects: List<VercelProject>,
    val nextCursor: String?,
)

/** A domain attached to a project via `/v9/projects/{id}/domains`. */
data class VercelProjectDomain(
    val name: String,
    val verified: Boolean?,
    val redirect: String?,
) {
    /** Verified (or unreported) and serving the project directly rather than redirecting. */
    val isServing: Boolean
        get() = verified != false && redirect.isNullOrBlank()
}

data class VercelDeploymentMeta(
    val commitMessage: String? = null,
    val commitRef: String? = null,
    val commitSha: String? = null,
    val commitAuthorName: String? = null,
    val org: String? = null,
    val repo: String? = null,
) {
    val repository: String?
        get() = VercelGitLink(org = org, repo = repo).fullName
}

/** A deployment returned by `/v6/deployments`. */
data class VercelDeployment(
    val uid: String?,
    val name: String?,
    val url: String?,
    val inspectorUrl: String?,
    val state: String?,
    val readyState: String?,
    val target: String?,
    val createdAtMillis: Long?,
    val meta: VercelDeploymentMeta = VercelDeploymentMeta(),
    val creatorUsername: String? = null,
    val creatorEmail: String? = null,
) {
    val stableId: String
        get() = uid ?: url ?: "${name ?: "deployment"}-${createdAtMillis ?: 0L}"

    /** The deployment id or URL accepted by the events endpoint. */
    val eventsIdentifier: String?
        get() = uid?.takeIf(String::isNotBlank) ?: url?.takeIf(String::isNotBlank)

    val displayState: String
        get() = state ?: readyState ?: "UNKNOWN"

    val displayTarget: String
        get() = target?.takeIf(String::isNotBlank)?.let(::capitalizeWords) ?: "Preview"

    companion object {
        private fun capitalizeWords(value: String): String = value
            .lowercase(Locale.ROOT)
            .split(' ')
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }
}

/** A build or runtime event from `/v3/deployments/{id}/events`, reduced to display values. */
data class VercelDeploymentEvent(
    val type: String,
    val createdAtMillis: Long,
    val message: String,
    val statusCode: String?,
) {
    val stableId: String
        get() = "$createdAtMillis-$type-${message.take(32)}"
}

data class VercelTeam(
    val id: String,
    val slug: String,
    val name: String?,
    val membershipConfirmed: Boolean?,
) {
    val displayName: String
        get() = name?.trim()?.takeIf(String::isNotEmpty) ?: slug

    val isConfirmedMember: Boolean
        get() = membershipConfirmed != false
}

data class VercelTeamsPage(
    val teams: List<VercelTeam>,
    val nextCursor: String?,
)

data class VercelAnalyticsOverview(
    val pageViews: Long,
    val visitors: Long,
    val bounceRate: Double?,
)

data class VercelAnalyticsPoint(
    val key: String,
    val pageViews: Long,
    val visitors: Long,
    val bounceRate: Double?,
)

data class VercelAnalyticsTimeseries(
    val groups: Map<String, List<VercelAnalyticsPoint>>,
)

class VercelApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String,
) : Exception(message) {
    override fun toString(): String =
        "VercelApiException(statusCode=$statusCode, errorCode=$errorCode, message=$message)"
}

class VercelResponseFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Vercel timestamps are milliseconds, but a few payloads report seconds. */
internal fun normalizeVercelTimestamp(value: Long?): Long? = when {
    value == null -> null
    value > 10_000_000_000L -> value
    else -> value * 1_000L
}
