package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.account.VercelAccount
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpClient
import com.apoorvdarshan.verceltics.data.network.SecureProviderHttpClient
import com.apoorvdarshan.verceltics.data.network.map

class VercelApi(
    // Rich project listings (100 projects with deployments and aliases) can pass the 2 MB default.
    private val httpClient: ProviderHttpClient = SecureProviderHttpClient(
        BASE_URL,
        maximumResponseBytes = MAX_RESOURCE_RESPONSE_BYTES,
    ),
    private val jsonParser: VercelJsonParser = AndroidVercelJsonParser(),
    private val analyticsHttpClient: ProviderHttpClient = SecureProviderHttpClient(ANALYTICS_BASE_URL),
) {
    fun newValidatePersonalTokenCall(token: SecretValue): CancelableCall<VercelUser> =
        httpClient.newGetCall(
            relativePath = "/v2/user",
            bearerToken = token,
        ).map { response ->
            requireSuccessful(response, "validate the Vercel token")
            response.useBody(jsonParser::parseUser)
        }

    fun newListProjectsCall(
        token: SecretValue,
        limit: Int = DEFAULT_PROJECT_LIMIT,
        until: String? = null,
        teamId: String? = null,
    ): CancelableCall<VercelProjectsPage> {
        require(limit in 1..MAX_PROJECT_LIMIT) { "Vercel project limit must be between 1 and 100." }
        val query = buildList {
            teamId?.takeIf(String::isNotBlank)?.let { add("teamId" to it) }
            add("limit" to limit.toString())
            until?.takeIf(String::isNotBlank)?.let { add("until" to it) }
        }
        return httpClient.newGetCall(
            relativePath = "/v9/projects",
            queryParameters = query,
            bearerToken = token,
        ).map { response ->
            requireSuccessful(response, "load Vercel projects")
            response.useBody(jsonParser::parseProjects)
        }
    }

    fun newListTeamsCall(
        token: SecretValue,
        limit: Int = DEFAULT_PROJECT_LIMIT,
        until: String? = null,
    ): CancelableCall<VercelTeamsPage> {
        require(limit in 1..MAX_PROJECT_LIMIT) { "Vercel team limit must be between 1 and 100." }
        val query = buildList {
            add("limit" to limit.toString())
            until?.takeIf(String::isNotBlank)?.let { add("until" to it) }
        }
        return httpClient.newGetCall(
            relativePath = "/v2/teams",
            queryParameters = query,
            bearerToken = token,
        ).map { response ->
            requireSuccessful(response, "load Vercel teams")
            response.useBody(jsonParser::parseTeams)
        }
    }

    /** `/v9/projects/{id}`: the full project record, including production aliases and links. */
    fun newProjectCall(
        token: SecretValue,
        projectId: String,
        teamId: String?,
    ): CancelableCall<VercelProject> = httpClient.newGetCall(
        relativePath = "/v9/projects/${requireSafePathSegment(projectId, "project id")}",
        queryParameters = teamQuery(teamId),
        bearerToken = token,
    ).map { response ->
        requireSuccessful(response, "load the Vercel project")
        response.useBody(jsonParser::parseProject)
    }

    /**
     * `/v9/projects/{id}/domains`, reduced to verified names that serve the project directly
     * (redirecting and unverified domains are excluded, as on iOS).
     */
    fun newProjectDomainsCall(
        token: SecretValue,
        projectId: String,
        teamId: String?,
    ): CancelableCall<List<String>> = httpClient.newGetCall(
        relativePath = "/v9/projects/${requireSafePathSegment(projectId, "project id")}/domains",
        queryParameters = teamQuery(teamId),
        bearerToken = token,
    ).map { response ->
        requireSuccessful(response, "load Vercel project domains")
        response.useBody(jsonParser::parseProjectDomains)
            .filter(VercelProjectDomain::isServing)
            .map(VercelProjectDomain::name)
    }

    /** `/v6/deployments` for one project, newest first. */
    fun newDeploymentsCall(
        token: SecretValue,
        projectId: String,
        teamId: String?,
        limit: Int = DEFAULT_DEPLOYMENT_LIMIT,
    ): CancelableCall<List<VercelDeployment>> {
        require(limit in 1..MAX_PROJECT_LIMIT) { "Vercel deployment limit must be between 1 and 100." }
        require(projectId.isNotBlank()) { "A Vercel project id is required." }
        return httpClient.newGetCall(
            relativePath = "/v6/deployments",
            queryParameters = teamQuery(teamId) + listOf(
                "projectId" to projectId,
                "limit" to limit.toString(),
            ),
            bearerToken = token,
        ).map { response ->
            requireSuccessful(response, "load Vercel deployments")
            response.useBody(jsonParser::parseDeployments)
        }
    }

    /** `/v3/deployments/{idOrUrl}/events`: up to [limit] of the newest build events. */
    fun newDeploymentEventsCall(
        token: SecretValue,
        deploymentIdOrUrl: String,
        teamId: String?,
        limit: Int = DEFAULT_EVENT_LIMIT,
    ): CancelableCall<List<VercelDeploymentEvent>> {
        require(limit in 1..MAX_EVENT_LIMIT) { "Vercel event limit must be between 1 and 200." }
        val segment = requireSafePathSegment(deploymentIdOrUrl, "deployment id")
        return httpClient.newGetCall(
            relativePath = "/v3/deployments/$segment/events",
            queryParameters = teamQuery(teamId) + listOf(
                "direction" to "backward",
                "limit" to limit.toString(),
                "builds" to "1",
            ),
            bearerToken = token,
        ).map { response ->
            requireSuccessful(response, "load Vercel build events")
            response.useBody(jsonParser::parseDeploymentEvents)
        }
    }

    fun newAnalyticsOverviewCall(
        token: SecretValue,
        projectId: String,
        teamId: String?,
        from: String,
        to: String,
        environment: String?,
    ): CancelableCall<VercelAnalyticsOverview> = analyticsHttpClient.newGetCall(
        relativePath = "/web-analytics/v2/overview",
        queryParameters = analyticsQuery(
            projectId = projectId,
            teamId = teamId,
            from = from,
            to = to,
            environment = environment,
        ),
        bearerToken = token,
    ).map { response ->
        requireSuccessful(response, "load Vercel Web Analytics")
        response.useBody(jsonParser::parseAnalyticsOverview)
    }

    fun newAnalyticsTimeseriesCall(
        token: SecretValue,
        projectId: String,
        teamId: String?,
        from: String,
        to: String,
        environment: String?,
        groupBy: String? = null,
    ): CancelableCall<VercelAnalyticsTimeseries> = analyticsHttpClient.newGetCall(
        relativePath = "/web-analytics/v2/timeseries",
        queryParameters = analyticsQuery(
            projectId = projectId,
            teamId = teamId,
            from = from,
            to = to,
            environment = environment,
        ) + listOfNotNull(groupBy?.takeIf(String::isNotBlank)?.let { "groupBy" to it }),
        bearerToken = token,
    ).map { response ->
        requireSuccessful(response, "load Vercel Web Analytics")
        response.useBody(jsonParser::parseAnalyticsTimeseries)
    }

    fun accountForValidatedUser(
        user: VercelUser,
        token: SecretValue,
        nowMillis: Long = System.currentTimeMillis(),
    ): VercelAccount = VercelAccount(
        id = user.id,
        displayName = user.name?.takeIf(String::isNotBlank) ?: user.username,
        email = user.email,
        username = user.username,
        token = token,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
    )

    private fun analyticsQuery(
        projectId: String,
        teamId: String?,
        from: String,
        to: String,
        environment: String?,
    ): List<Pair<String, String>> = buildList {
        add("projectId" to projectId)
        teamId?.takeIf(String::isNotBlank)?.let { add("teamId" to it) }
        add("from" to from)
        add("to" to to)
        environment?.takeIf(String::isNotBlank)?.let { add("environment" to it) }
    }

    private fun teamQuery(teamId: String?): List<Pair<String, String>> =
        listOfNotNull(teamId?.takeIf(String::isNotBlank)?.let { "teamId" to it })

    private fun requireSuccessful(response: HttpResponse, operation: String) {
        if (response.statusCode in 200..299) return
        val errorCode = response.useBody(jsonParser::parseErrorCode)
        val message = when (response.statusCode) {
            401, 403 -> "Vercel rejected this personal token."
            404 -> "Vercel could not find the requested resource."
            408 -> "Vercel timed out while trying to $operation."
            429 -> "Vercel is rate limiting requests. Please try again shortly."
            in 500..599 -> "Vercel is temporarily unavailable."
            else -> "Unable to $operation (HTTP ${response.statusCode})."
        }
        throw VercelApiException(
            statusCode = response.statusCode,
            errorCode = errorCode,
            message = message,
        )
    }

    private inline fun <T> HttpResponse.useBody(block: (ByteArray) -> T): T {
        val bytes = takeBody()
        return try {
            block(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val BASE_URL: String = "https://api.vercel.com/"
        const val ANALYTICS_BASE_URL: String = "https://vercel.com/api/"
        private const val DEFAULT_PROJECT_LIMIT = 100
        private const val MAX_PROJECT_LIMIT = 100
        private const val MAX_RESOURCE_RESPONSE_BYTES = 8 * 1_024 * 1_024
        const val DEFAULT_DEPLOYMENT_LIMIT: Int = 6
        const val DEFAULT_EVENT_LIMIT: Int = 80
        private const val MAX_EVENT_LIMIT = 200
        private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._-]{1,253}")

        /**
         * Resource identifiers become exactly one path segment pinned to api.vercel.com. Anything
         * that could add a segment, traverse, or need percent-encoding is rejected outright.
         */
        internal fun requireSafePathSegment(value: String, label: String): String {
            require(SAFE_PATH_SEGMENT.matches(value) && value != "." && value != "..") {
                "Invalid Vercel $label."
            }
            return value
        }
    }
}
