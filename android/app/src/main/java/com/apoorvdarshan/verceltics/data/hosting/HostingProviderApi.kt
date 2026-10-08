package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Android port of iOS `HostingProviderAPI` for the seven generic hosting providers: one shared
 * entry point that dispatches to a per-provider adapter. All requests go through
 * [HostingHttpTransport] to fixed provider origins; nothing here keeps state between calls.
 */
class HostingProviderApi(
    private val transport: HostingHttpTransport,
    private val googleAccessTokenSource: GoogleAccessTokenSource = GoogleAccessTokenSource.Unavailable,
    private val maximumConcurrentLiveRequests: Int = DEFAULT_LIVE_CONCURRENCY,
) {
    init {
        require(maximumConcurrentLiveRequests in 1..16) { "Invalid live request concurrency." }
    }

    suspend fun validateProfile(credentials: HostingCredentials): HostingProfile =
        adapter(credentials).validateProfile()

    /** Resources sorted by name, as the iOS dashboard view model presents them. */
    suspend fun fetchResources(credentials: HostingCredentials): List<HostingResource> =
        adapter(credentials).fetchResources().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, HostingResource::name))

    suspend fun fetchDeployments(credentials: HostingCredentials, resource: HostingResource): List<HostingDeployment> =
        adapter(credentials).fetchDeployments(resource)

    /**
     * Sends the provider's real write request (iOS `performPrimaryAction`). Railway redeploys
     * [latestDeploymentId]; the other providers act on the resource itself.
     */
    suspend fun performPrimaryAction(
        credentials: HostingCredentials,
        resource: HostingResource,
        latestDeploymentId: String?,
    ) = adapter(credentials).performPrimaryAction(resource, latestDeploymentId)

    private fun adapter(credentials: HostingCredentials): HostingProviderAdapter = when (credentials) {
        is HostingCredentials.Railway -> RailwayAdapter(credentials, transport)
        is HostingCredentials.Render -> RenderAdapter(credentials, transport)
        is HostingCredentials.DigitalOcean -> DigitalOceanAdapter(credentials, transport)
        is HostingCredentials.Heroku -> HerokuAdapter(credentials, transport, maximumConcurrentLiveRequests)
        is HostingCredentials.Fly -> FlyAdapter(credentials, transport, maximumConcurrentLiveRequests)
        is HostingCredentials.Firebase -> FirebaseAdapter(credentials, transport, googleAccessTokenSource)
        is HostingCredentials.AwsAmplify -> AmplifyAdapter(credentials, transport)
    }

    companion object {
        const val DEFAULT_LIVE_CONCURRENCY: Int = 6

        /** AWS caps Amplify `ListBranches`/`ListJobs` at 50 results per page. */
        const val AWS_AMPLIFY_BRANCH_AND_JOB_PAGE_SIZE: Int = 50

        /**
         * iOS `herokuAppStatus`: summarizes documented dyno states without claiming an app is
         * running when its live dyno list could not be read (`null`).
         */
        fun herokuAppStatus(maintenance: Boolean, dynoStates: List<String>?): String {
            if (maintenance) return "Maintenance"
            val states = dynoStates?.map { it.lowercase(Locale.ROOT) } ?: return "Unknown"
            if (states.isEmpty()) return "Stopped"
            val running = states.count { it == "up" }
            val idle = states.count { it == "idle" }
            return when {
                states.all { it == "up" } -> "Running"
                states.all { it == "idle" } -> "Idle"
                states.all { it == "down" } -> "Stopped"
                "crashed" in states -> if (running + idle > 0) "Degraded" else "Crashed"
                "starting" in states && running + idle == 0 -> "Starting"
                running + idle > 0 -> "Degraded"
                else -> "Unknown"
            }
        }

        /**
         * iOS `flyAppStatus`: `null` means the Machines API was unavailable; an empty list means
         * the app currently has no Machines.
         */
        fun flyAppStatus(machineStates: List<String>?): String {
            val states = machineStates?.map { it.lowercase(Locale.ROOT) } ?: return "Unknown"
            if (states.isEmpty()) return "Stopped"
            val running = states.count { it == "started" }
            return when {
                states.all { it == "started" } -> "Running"
                states.all { it == "stopped" || it == "destroyed" } -> "Stopped"
                states.all { it == "suspended" } -> "Suspended"
                "failed" in states -> if (running > 0) "Degraded" else "Failed"
                states.any { it == "starting" || it == "created" || it == "replacing" } && running == 0 -> "Starting"
                "stopping" in states && running == 0 -> "Stopping"
                running > 0 -> "Degraded"
                else -> "Unknown"
            }
        }

        /** iOS `dashboardURL(for:)`. Identifiers are path-encoded; the AWS region is re-validated. */
        fun dashboardUrl(context: HostingLinkContext, resource: HostingResource? = null): String {
            fun segment(value: String) = AwsSigV4Signer.encode(value)
            return when (context.provider) {
                HostingProvider.RAILWAY ->
                    resource?.let { "https://railway.com/project/${segment(it.id)}" } ?: "https://railway.com/dashboard"
                HostingProvider.RENDER ->
                    resource?.let { "https://dashboard.render.com/${segment(it.id)}" } ?: "https://dashboard.render.com/"
                HostingProvider.DIGITAL_OCEAN -> resource?.let { "https://cloud.digitalocean.com/apps/${segment(it.id)}" }
                    ?: "https://cloud.digitalocean.com/apps"
                HostingProvider.HEROKU -> resource?.let { "https://dashboard.heroku.com/apps/${segment(it.name)}" }
                    ?: "https://dashboard.heroku.com/apps"
                HostingProvider.FLY -> resource?.let { "https://fly.io/apps/${segment(it.name)}" } ?: "https://fly.io/dashboard"
                HostingProvider.FIREBASE -> context.firebaseProjectId?.takeIf(String::isNotBlank)
                    ?.let { "https://console.firebase.google.com/project/${segment(it)}/hosting" }
                    ?: "https://console.firebase.google.com/"
                HostingProvider.AWS_AMPLIFY -> {
                    val region = context.awsRegion
                        ?.takeIf { runCatching { AwsSigV4Signer.requireStandardRegion(it) }.isSuccess }
                        ?: "us-east-1"
                    resource?.let { "https://$region.console.aws.amazon.com/amplify/apps/${segment(it.id)}" }
                        ?: "https://$region.console.aws.amazon.com/amplify/apps"
                }
            }
        }
    }
}

internal interface HostingProviderAdapter {
    suspend fun validateProfile(): HostingProfile

    suspend fun fetchResources(): List<HostingResource>

    suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment>

    suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?)
}

// region Railway

private class RailwayAdapter(
    private val credentials: HostingCredentials.Railway,
    transport: HostingHttpTransport,
) : HostingProviderAdapter {
    private val isProjectToken = credentials.tokenType == RailwayTokenType.PROJECT
    private val client = HostingRequestClient(
        provider = HostingProvider.RAILWAY,
        transport = transport,
        endpoint = HostingEndpoint.RAILWAY,
        basePath = listOf("graphql", "v2"),
        authProvider = {
            if (isProjectToken) {
                HostingAuth.RailwayProjectToken(credentials.token)
            } else {
                HostingAuth.Bearer(credentials.token)
            }
        },
    )

    override suspend fun validateProfile(): HostingProfile {
        if (isProjectToken) {
            val token = projectToken("validate the Railway project token")
            return HostingProfile(
                id = cleanId(token.string("projectId")) ?: credentialFingerprint(credentials.token),
                name = "Railway Project",
                email = null,
                avatarUrl = null,
            )
        }
        val me = graphql("query { me { id name email avatar } }", operation = "validate the Railway token")
            .data()["me"].asObject()
        return HostingProfile(
            id = cleanId(me.string("id", "email")) ?: credentialFingerprint(credentials.token),
            name = cleanName(me.string("name", "email")) ?: "Railway Account",
            email = cleanText(me.string("email")),
            avatarUrl = cleanUrl(me.string("avatar")),
        )
    }

    override suspend fun fetchResources(): List<HostingResource> {
        if (isProjectToken) {
            val token = projectToken("load the Railway project")
            val projectId = cleanId(token.string("projectId"))
                ?: throw invalid("Railway did not return the project attached to this token.")
            val project = graphql(
                "query project(\$id: String!) { project(id: \$id) { id name description createdAt updatedAt } }",
                variables("id" to JsonString(projectId)),
                "load the Railway project",
            ).data()["project"].asObject()
            return listOf(
                HostingResource(
                    id = projectId,
                    name = cleanName(project.string("name")) ?: "Railway Project",
                    subtitle = cleanText(project.string("description")),
                    url = null,
                    status = "Project",
                    region = null,
                    kind = "Project",
                    updatedAtMillis = project["updatedAt"].dateMillis(),
                    metadata = metadataOf("environmentID" to token.string("environmentId")),
                ),
            )
        }
        val projects = collectRailwayConnection { cursor ->
            graphql(PROJECTS_QUERY, pageVariables(cursor), "load Railway projects").data()["projects"].asObject()
        }
        return projects.mapNotNull { project ->
            val id = stableIdentifier(
                cleanId(project.string("id")),
                "railway-project",
                listOf(project.string("name"), project.string("createdAt")),
            ) ?: return@mapNotNull null
            HostingResource(
                id = id,
                name = cleanName(project.string("name")) ?: "Untitled project",
                subtitle = cleanText(project.string("description")),
                url = null,
                status = "Project",
                region = null,
                kind = "Project",
                updatedAtMillis = project["updatedAt"].dateMillis(),
            )
        }
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> {
        val projectId = resource.id
        val services = collectRailwayConnection { cursor ->
            graphql(SERVICES_QUERY, pageVariables(cursor, "id" to JsonString(projectId)), "load Railway services")
                .data()["project"].asObject()["services"].asObject()
        }
        val environments = collectRailwayConnection { cursor ->
            graphql(ENVIRONMENTS_QUERY, pageVariables(cursor, "id" to JsonString(projectId)), "load Railway environments")
                .data()["project"].asObject()["environments"].asObject()
        }
        val deployments = mutableListOf<HostingDeployment>()
        for (service in services) {
            val serviceId = cleanId(service.string("id")) ?: continue
            for (environment in environments) {
                val environmentId = cleanId(environment.string("id")) ?: continue
                currentCoroutineContext().ensureActive()
                val input = JsonObject(
                    linkedMapOf(
                        "projectId" to JsonString(projectId),
                        "serviceId" to JsonString(serviceId),
                        "environmentId" to JsonString(environmentId),
                    ),
                )
                val nodes = collectRailwayConnection { cursor ->
                    graphql(DEPLOYMENTS_QUERY, pageVariables(cursor, "input" to input), "load Railway deployments")
                        .data()["deployments"].asObject()
                }
                val title = "${cleanName(service.string("name")) ?: "Service"} · " +
                    (cleanName(environment.string("name")) ?: "Environment")
                nodes.forEach { node ->
                    val meta = node["meta"]
                    val metaObject = meta.asObject()
                    deployments += HostingDeployment(
                        id = cleanId(node.string("id")) ?: fallbackIdentifier("railway-deploy", node),
                        title = title.take(MAX_TITLE_CHARACTERS),
                        status = cleanStatus(node.string("status")) ?: "UNKNOWN",
                        createdAtMillis = node["createdAt"].dateMillis(),
                        url = cleanUrl(node.string("url", "staticUrl")),
                        branch = cleanText(metaObject.string("branch"), MAX_NAME_CHARACTERS),
                        commitMessage = cleanText(metaObject.string("commitMessage") ?: displayJson(meta)),
                        metadata = metadataOf(
                            "serviceID" to serviceId,
                            "environmentID" to environmentId,
                            "canRedeploy" to (node.bool("canRedeploy") ?: false).toString(),
                            "canRollback" to (node.bool("canRollback") ?: false).toString(),
                        ),
                    )
                }
            }
        }
        return deployments.distinctBy(HostingDeployment::id).sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }
    }

    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) {
        val id = cleanId(latestDeploymentId)
            ?: throw configuration("No Railway deployment is available to redeploy.")
        graphql(
            "mutation deploymentRedeploy(\$id: String!) { deploymentRedeploy(id: \$id) { id status } }",
            variables("id" to JsonString(id)),
            "redeploy the Railway deployment",
        )
    }

    private suspend fun projectToken(operation: String): JsonObject =
        graphql("query { projectToken { projectId environmentId } }", operation = operation)
            .data()["projectToken"].asObject()

    private suspend fun graphql(
        query: String,
        variables: JsonObject = JsonObject.EMPTY,
        operation: String,
    ): JsonObject {
        val body = HostingJson.write(JsonObject(linkedMapOf("query" to JsonString(query), "variables" to variables)))
        val root = client.json(HostingHttpMethod.POST, path = emptyList(), body = body, operation = operation).asObject()
        val firstError = root["errors"].asArray().firstOrNull() ?: return root
        val message = firstError.asObject().string("message").orEmpty()
        throw HostingApiException(
            if (RAILWAY_AUTH_ERROR.containsMatchIn(message)) {
                HostingFailure(
                    HostingFailureKind.AUTHENTICATION,
                    "Railway rejected this token. Check the token type and the access it grants.",
                )
            } else {
                HostingFailure(HostingFailureKind.INVALID_RESPONSE, "Railway could not $operation.")
            },
        )
    }

    private fun JsonObject.data(): JsonObject = this["data"].asObject()

    private fun variables(vararg entries: Pair<String, JsonValue>) = JsonObject(linkedMapOf(*entries))

    private fun pageVariables(cursor: String?, vararg entries: Pair<String, JsonValue>): JsonObject {
        val values = linkedMapOf(*entries)
        values["first"] = JsonNumber("100")
        if (cursor != null) values["after"] = JsonString(cursor)
        return JsonObject(values)
    }

    private companion object {
        val RAILWAY_AUTH_ERROR = Regex("not authori[sz]ed|unauthori[sz]ed|unauthenticated|authentication|invalid token|forbidden", RegexOption.IGNORE_CASE)
        val PROJECTS_QUERY = """
            query projects(${'$'}first: Int, ${'$'}after: String) {
              projects(first: ${'$'}first, after: ${'$'}after) {
                edges { node { id name description createdAt updatedAt } }
                pageInfo { hasNextPage endCursor }
              }
            }
        """.trimIndent()
        val SERVICES_QUERY = """
            query projectServices(${'$'}id: String!, ${'$'}first: Int, ${'$'}after: String) {
              project(id: ${'$'}id) {
                services(first: ${'$'}first, after: ${'$'}after) {
                  edges { node { id name } }
                  pageInfo { hasNextPage endCursor }
                }
              }
            }
        """.trimIndent()
        val ENVIRONMENTS_QUERY = """
            query projectEnvironments(${'$'}id: String!, ${'$'}first: Int, ${'$'}after: String) {
              project(id: ${'$'}id) {
                environments(first: ${'$'}first, after: ${'$'}after) {
                  edges { node { id name } }
                  pageInfo { hasNextPage endCursor }
                }
              }
            }
        """.trimIndent()
        val DEPLOYMENTS_QUERY = """
            query deployments(${'$'}input: DeploymentListInput!, ${'$'}first: Int, ${'$'}after: String) {
              deployments(input: ${'$'}input, first: ${'$'}first, after: ${'$'}after) {
                edges { node { id status createdAt url staticUrl meta canRedeploy canRollback } }
                pageInfo { hasNextPage endCursor }
              }
            }
        """.trimIndent()
    }
}

// endregion

// region Render

private class RenderAdapter(
    private val credentials: HostingCredentials.Render,
    transport: HostingHttpTransport,
) : HostingProviderAdapter {
    private val client = HostingRequestClient(
        provider = HostingProvider.RENDER,
        transport = transport,
        endpoint = HostingEndpoint.RENDER,
        basePath = listOf("v1"),
        authProvider = { HostingAuth.Bearer(credentials.apiKey) },
    )

    override suspend fun validateProfile(): HostingProfile {
        val first = client.json(path = listOf("owners"), query = listOf("limit" to "100"), operation = "validate the Render API key")
            .asArray().firstOrNull()
        val owner = (first.asObject()["owner"] ?: first).asObject()
        if (owner.isEmpty) throw invalid("No Render workspace was returned.")
        return HostingProfile(
            id = cleanId(owner.string("id", "email")) ?: credentialFingerprint(credentials.apiKey),
            name = cleanName(owner.string("name", "email")) ?: "Render Workspace",
            email = cleanText(owner.string("email")),
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> = client.fetchRenderCursorPages(
        path = listOf("services"),
        query = listOf("limit" to "100", "includePreviews" to "true"),
        operation = "load Render services",
    ).mapNotNull { value ->
        val service = (value.asObject()["service"] ?: value).asObject()
        val details = service["serviceDetails"].asObject()
        val id = stableIdentifier(
            cleanId(service.string("id")),
            "render-service",
            listOf(service.string("name"), service.string("url", "repo", "imagePath")),
        ) ?: return@mapNotNull null
        HostingResource(
            id = id,
            name = cleanName(service.string("name")) ?: "Untitled service",
            subtitle = cleanText(service.string("repo", "imagePath")),
            url = cleanUrl(service.string("url")),
            status = if (service.bool("suspended") == true) "Suspended" else "Active",
            region = cleanStatus(service.string("region") ?: details.string("region")),
            kind = service.string("type")?.replace('_', ' ')?.capitalizedWords()?.let(::cleanStatus) ?: "Service",
            updatedAtMillis = service["updatedAt"].dateMillis(),
        )
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> =
        client.fetchRenderCursorPages(
            path = listOf("services", resource.id, "deploys"),
            query = listOf("limit" to "100"),
            operation = "load Render deploys",
        ).map { value ->
            val deploy = (value.asObject()["deploy"] ?: value).asObject()
            val commit = deploy["commit"].asObject()
            HostingDeployment(
                id = cleanId(deploy.string("id")) ?: fallbackIdentifier("render-deploy", deploy),
                title = cleanTitle(commit.string("message")) ?: "Deploy",
                status = cleanStatus(deploy.string("status")) ?: "unknown",
                createdAtMillis = deploy["createdAt"].dateMillis(),
                url = null,
                branch = null,
                commitMessage = cleanText(commit.string("message", "id")),
            )
        }.distinctBy(HostingDeployment::id)

    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) =
        client.execute(HostingHttpMethod.POST, listOf("services", resource.id, "deploys"), "{}", "redeploy the Render service")
}

// endregion

// region DigitalOcean

private class DigitalOceanAdapter(
    private val credentials: HostingCredentials.DigitalOcean,
    transport: HostingHttpTransport,
) : HostingProviderAdapter {
    /** iOS `normalizedRequestPath`: first-class DigitalOcean calls carry exactly one `/v2`. */
    private val client = HostingRequestClient(
        provider = HostingProvider.DIGITAL_OCEAN,
        transport = transport,
        endpoint = HostingEndpoint.DIGITAL_OCEAN,
        basePath = listOf("v2"),
        authProvider = { HostingAuth.Bearer(credentials.token) },
    )

    override suspend fun validateProfile(): HostingProfile {
        val account = client.json(path = listOf("account"), operation = "validate the DigitalOcean token")
            .asObject()["account"].asObject()
        return HostingProfile(
            id = cleanId(account.string("uuid", "email")) ?: credentialFingerprint(credentials.token),
            name = cleanName(account.string("name", "email")) ?: "DigitalOcean Account",
            email = cleanText(account.string("email")),
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> = collectNumberedPages(PAGE_SIZE) { page ->
        client.json(
            path = listOf("apps"),
            query = listOf("per_page" to PAGE_SIZE.toString(), "page" to page.toString()),
            operation = "load DigitalOcean apps",
        ).asObject()["apps"].asArray()
    }.mapNotNull { value ->
        val app = value.asObject()
        val spec = app["spec"].asObject()
        val id = stableIdentifier(
            cleanId(app.string("id")),
            "digitalocean-app",
            listOf(spec.string("name"), app.string("live_url", "default_ingress")),
        ) ?: return@mapNotNull null
        HostingResource(
            id = id,
            name = cleanName(app.string("spec.name", "name") ?: spec.string("name")) ?: "Untitled app",
            subtitle = cleanText(app.string("default_ingress", "live_url")),
            url = cleanUrl(app.string("live_url", "default_ingress")),
            status = cleanStatus(app["active_deployment"].asObject().string("phase") ?: app.string("phase")),
            region = cleanStatus(app["region"].asObject().string("slug", "label")),
            kind = "App",
            updatedAtMillis = app["updated_at"].dateMillis(),
        )
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> =
        collectNumberedPages(PAGE_SIZE) { page ->
            client.json(
                path = listOf("apps", resource.id, "deployments"),
                query = listOf("per_page" to PAGE_SIZE.toString(), "page" to page.toString()),
                operation = "load DigitalOcean deployments",
            ).asObject()["deployments"].asArray()
        }.map { value ->
            val deployment = value.asObject()
            HostingDeployment(
                id = cleanId(deployment.string("id")) ?: fallbackIdentifier("digitalocean-deploy", deployment),
                title = cleanTitle(deployment.string("cause")) ?: "Deployment",
                status = cleanStatus(deployment.string("phase")) ?: "UNKNOWN",
                createdAtMillis = deployment["created_at"].dateMillis(),
                url = null,
                branch = null,
                commitMessage = cleanText(deployment.string("cause")),
            )
        }.distinctBy(HostingDeployment::id)

    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) =
        client.execute(HostingHttpMethod.POST, listOf("apps", resource.id, "deployments"), "{}", "redeploy the DigitalOcean app")

    private companion object {
        const val PAGE_SIZE = 200
    }
}

// endregion

// region Heroku

private class HerokuAdapter(
    private val credentials: HostingCredentials.Heroku,
    transport: HostingHttpTransport,
    private val maximumConcurrent: Int,
) : HostingProviderAdapter {
    private val client = HostingRequestClient(
        provider = HostingProvider.HEROKU,
        transport = transport,
        endpoint = HostingEndpoint.HEROKU,
        basePath = emptyList(),
        defaultHeaders = mapOf("Accept" to "application/vnd.heroku+json; version=3"),
        authProvider = { HostingAuth.Bearer(credentials.token) },
    )

    private class AppSnapshot(
        val id: String,
        val name: String,
        val subtitle: String?,
        val url: String?,
        val maintenance: Boolean,
        val region: String?,
        val updatedAtMillis: Long?,
    )

    override suspend fun validateProfile(): HostingProfile {
        val account = client.json(path = listOf("account"), operation = "validate the Heroku token").asObject()
        return HostingProfile(
            id = cleanId(account.string("id", "email")) ?: credentialFingerprint(credentials.token),
            name = cleanName(account.string("name", "email")) ?: "Heroku Account",
            email = cleanText(account.string("email")),
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> {
        val apps = client.fetchHerokuRangePages(listOf("apps"), "load Heroku apps").mapNotNull { value ->
            val app = value.asObject()
            val id = stableIdentifier(
                cleanId(app.string("id", "name")),
                "heroku-app",
                listOf(app.string("name"), app.string("web_url")),
            ) ?: return@mapNotNull null
            AppSnapshot(
                id = id,
                name = cleanName(app.string("name")) ?: "Untitled app",
                subtitle = cleanText(app["stack"].asObject().string("name")),
                url = cleanUrl(app.string("web_url")),
                maintenance = app.bool("maintenance") == true,
                region = cleanStatus(app["region"].asObject().string("name")),
                updatedAtMillis = app["updated_at"].dateMillis(),
            )
        }
        val dynoStates = fetchLiveValues(apps.map(AppSnapshot::id), maximumConcurrent) { id ->
            client.fetchHerokuRangePages(listOf("apps", id, "dynos"), "load Heroku dynos")
                .mapNotNull { it.asObject().string("state") }
        }
        return apps.mapIndexed { index, app ->
            HostingResource(
                id = app.id,
                name = app.name,
                subtitle = app.subtitle,
                url = app.url,
                status = HostingProviderApi.herokuAppStatus(app.maintenance, dynoStates[index]),
                region = app.region,
                kind = "App",
                updatedAtMillis = app.updatedAtMillis,
            )
        }
    }

    /** Releases newest first (Heroku's `id` range ordering is not chronological). */
    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> =
        client.fetchHerokuRangePages(listOf("apps", resource.id, "releases"), "load Heroku releases").map { value ->
            val release = value.asObject()
            val version = release["version"].integer()
            HostingDeployment(
                id = cleanId(release.string("id"))
                    ?: version?.let { "release-$it" }
                    ?: fallbackIdentifier("heroku-release", release),
                title = version?.let { "Release v$it" } ?: "Release",
                status = cleanStatus(release.string("status"))
                    ?: if (release.bool("current") == true) "current" else "released",
                createdAtMillis = release["created_at"].dateMillis(),
                url = cleanUrl(release.string("output_stream_url")),
                branch = null,
                commitMessage = cleanText(release.string("description")),
                metadata = metadataOf("rollbackEligible" to (release.bool("eligible_for_rollback") ?: false).toString()),
            ) to version
        }.distinctBy { it.first.id }
            .sortedWith(
                compareByDescending<Pair<HostingDeployment, Long?>> { it.second ?: Long.MIN_VALUE }
                    .thenByDescending { it.first.createdAtMillis ?: Long.MIN_VALUE },
            )
            .map { it.first }

    /** Restarts every dyno (`DELETE /apps/{app}/dynos`). */
    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) =
        client.execute(HostingHttpMethod.DELETE, listOf("apps", resource.id, "dynos"), operation = "restart the Heroku app")
}

// endregion

// region Fly.io

private class FlyAdapter(
    private val credentials: HostingCredentials.Fly,
    transport: HostingHttpTransport,
    private val maximumConcurrent: Int,
) : HostingProviderAdapter {
    private val client = HostingRequestClient(
        provider = HostingProvider.FLY,
        transport = transport,
        endpoint = HostingEndpoint.FLY,
        basePath = listOf("v1"),
        authProvider = { HostingAuth.Bearer(credentials.token) },
    )

    private class MachineSnapshot(val count: Int, val states: List<String>, val updatedAtMillis: Long?)

    override suspend fun validateProfile(): HostingProfile {
        val organization = credentials.organization
        client.json(path = listOf("apps"), query = listOf("org_slug" to organization), operation = "validate the Fly.io token")
        return HostingProfile(
            id = organization,
            name = if (organization == "personal") "Fly.io Personal" else organization,
            email = null,
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> {
        val apps = client.json(
            path = listOf("apps"),
            query = listOf("org_slug" to credentials.organization),
            operation = "load Fly.io apps",
        ).asObject()["apps"].asArray().mapNotNull { value ->
            val app = value.asObject()
            val name = cleanId(app.string("name")) ?: return@mapNotNull null
            Triple(cleanId(app.string("id", "name")) ?: name, name, app)
        }
        val machines = fetchLiveValues(apps.map { it.second }, maximumConcurrent) { appName ->
            val list = client.json(path = listOf("apps", appName, "machines"), operation = "load Fly.io Machines")
                .asArray().map { it.asObject() }
            MachineSnapshot(
                count = list.size,
                states = list.mapNotNull { it.string("state") },
                updatedAtMillis = list.mapNotNull { it["updated_at"].dateMillis() }.maxOrNull(),
            )
        }
        return apps.mapIndexed { index, (id, name, app) ->
            val snapshot = machines[index]
            val machineCount = snapshot?.count ?: app["machine_count"].integer() ?: 0
            val volumeCount = app["volume_count"].integer() ?: 0
            HostingResource(
                id = id,
                name = name,
                subtitle = "$machineCount Machines · $volumeCount volumes",
                url = "https://$name.fly.dev".takeIf { FLY_APP_NAME.matches(name) },
                status = HostingProviderApi.flyAppStatus(snapshot?.states),
                region = null,
                kind = "App",
                updatedAtMillis = snapshot?.updatedAtMillis,
                metadata = metadataOf("appName" to name),
            )
        }
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> =
        client.json(
            path = listOf("apps", appName(resource), "machines"),
            query = listOf("include_deleted" to "true"),
            operation = "load Fly.io Machines",
        ).asArray().map { value ->
            val machine = value.asObject()
            val region = cleanStatus(machine.string("region"))
            HostingDeployment(
                id = cleanId(machine.string("id")) ?: fallbackIdentifier("fly-machine", machine),
                title = cleanTitle(machine.string("name")) ?: "Machine",
                status = cleanStatus(machine.string("state")) ?: "unknown",
                createdAtMillis = machine["created_at"].dateMillis(),
                url = null,
                branch = region,
                commitMessage = cleanText(machine["config"].asObject().string("image")),
                metadata = metadataOf("region" to region),
            )
        }

    /** Restarts every Machine of the app, one request per Machine (as on iOS). */
    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) {
        val appName = appName(resource)
        val machines = client.json(path = listOf("apps", appName, "machines"), operation = "load Fly.io Machines").asArray()
        for (machine in machines) {
            currentCoroutineContext().ensureActive()
            val machineId = cleanId(machine.asObject().string("id")) ?: continue
            client.execute(
                HostingHttpMethod.POST,
                listOf("apps", appName, "machines", machineId, "restart"),
                "{}",
                "restart the Fly.io Machine",
            )
        }
    }

    private fun appName(resource: HostingResource): String =
        cleanId(resource.metadata["appName"]) ?: resource.name

    private companion object {
        val FLY_APP_NAME = Regex("[a-z0-9][a-z0-9-]{0,62}")
    }
}

// endregion

// region Firebase Hosting

private class FirebaseAdapter(
    private val credentials: HostingCredentials.Firebase,
    private val transport: HostingHttpTransport,
    private val tokenSource: GoogleAccessTokenSource,
) : HostingProviderAdapter {
    private var accessToken: SecretValue? = null

    private val client = HostingRequestClient(
        provider = HostingProvider.FIREBASE,
        transport = transport,
        endpoint = HostingEndpoint.FIREBASE,
        basePath = listOf("v1beta1"),
        authProvider = { HostingAuth.Bearer(token()) },
    )

    override suspend fun validateProfile(): HostingProfile {
        val projectId = credentials.projectId
        client.json(
            path = listOf("projects", projectId, "sites"),
            query = listOf("pageSize" to "1"),
            operation = "open the Firebase project",
        )
        // Best effort: `openid email` scopes let the account card show who connected.
        val identity = try {
            HostingRequestClient(
                provider = HostingProvider.FIREBASE,
                transport = transport,
                endpoint = HostingEndpoint.GOOGLE_OPENID,
                basePath = listOf("v1"),
                authProvider = { HostingAuth.Bearer(token()) },
            ).json(path = listOf("userinfo"), operation = "read the Google account").asObject()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            JsonObject.EMPTY
        }
        val email = cleanText(identity.string("email"))
        val subject = cleanId(identity.string("sub")) ?: email ?: "google"
        return HostingProfile(
            id = "$subject:$projectId".take(MAX_HOSTING_ID_CHARACTERS),
            name = projectId,
            email = email,
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> = client.fetchTokenPages(
        path = listOf("projects", credentials.projectId, "sites"),
        query = listOf("pageSize" to "100"),
        itemKey = "sites",
        tokenQueryName = "pageToken",
        operation = "load Firebase Hosting sites",
    ).mapNotNull { value ->
        val site = value.asObject()
        val fullName = cleanId(site.string("name")) ?: return@mapNotNull null
        val siteId = cleanId(fullName.substringAfterLast('/')) ?: return@mapNotNull null
        HostingResource(
            id = siteId,
            name = siteId,
            subtitle = cleanText(site.string("appId", "type")),
            url = cleanUrl(site.string("defaultUrl")),
            status = "Hosting",
            region = null,
            kind = site.string("type")?.replace('_', ' ')?.capitalizedWords()?.let(::cleanStatus) ?: "Site",
            updatedAtMillis = null,
            metadata = metadataOf("fullName" to fullName),
        )
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> = client.fetchTokenPages(
        path = listOf("sites", resource.id, "releases"),
        query = listOf("pageSize" to "100"),
        itemKey = "releases",
        tokenQueryName = "pageToken",
        operation = "load Firebase Hosting releases",
    ).map { value ->
        val release = value.asObject()
        val version = release["version"].asObject()
        HostingDeployment(
            id = cleanId(release.string("name")) ?: fallbackIdentifier("firebase-release", release),
            title = release.string("type")?.replace('_', ' ')?.capitalizedWords()?.let(::cleanTitle) ?: "Release",
            status = cleanStatus(version.string("status")) ?: "RELEASED",
            createdAtMillis = release["releaseTime"].dateMillis() ?: version["finalizeTime"].dateMillis(),
            url = null,
            branch = null,
            commitMessage = cleanText(release.string("message")),
            metadata = metadataOf("version" to version.string("name")),
        )
    }.distinctBy(HostingDeployment::id)

    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) {
        throw HostingApiException(
            HostingFailure(HostingFailureKind.UNSUPPORTED, "Firebase Hosting has no safe one-tap action here."),
        )
    }

    private suspend fun token(): SecretValue {
        accessToken?.let { return it }
        val raw = try {
            tokenSource.accessToken(GoogleAccessTokenSource.FIREBASE_HOSTING_SCOPES)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw HostingApiException(
                HostingFailure(
                    HostingFailureKind.NETWORK,
                    "Google authorization could not be refreshed. Check your connection and try again.",
                ),
            )
        }
        val token = raw?.trim()?.takeIf(String::isNotEmpty)?.let { runCatching { SecretValue.of(it) }.getOrNull() }
            ?: throw HostingApiException(
                HostingFailure(
                    HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED,
                    "Continue with Google to connect Firebase Hosting.",
                ),
            )
        accessToken = token
        return token
    }
}

// endregion

// region AWS Amplify

private class AmplifyAdapter(
    private val credentials: HostingCredentials.AwsAmplify,
    transport: HostingHttpTransport,
) : HostingProviderAdapter {
    private val client = HostingRequestClient(
        provider = HostingProvider.AWS_AMPLIFY,
        transport = transport,
        endpoint = HostingEndpoint.amplify(credentials.region),
        basePath = emptyList(),
        authProvider = {
            HostingAuth.AwsSigV4(
                accessKeyId = credentials.accessKeyId,
                secretAccessKey = credentials.secretAccessKey,
                sessionToken = credentials.sessionToken,
                region = credentials.region,
                service = "amplify",
            )
        },
    )

    override suspend fun validateProfile(): HostingProfile {
        client.json(path = listOf("apps"), query = listOf("maxResults" to "1"), operation = "validate the AWS credentials")
        return HostingProfile(
            id = "${credentials.accessKeyId.takeLast(4)}-${credentials.region}",
            name = "AWS ${credentials.region}",
            email = null,
            avatarUrl = null,
        )
    }

    override suspend fun fetchResources(): List<HostingResource> = client.fetchTokenPages(
        path = listOf("apps"),
        query = listOf("maxResults" to "100"),
        itemKey = "apps",
        tokenQueryName = "nextToken",
        operation = "load Amplify apps",
    ).mapNotNull { value ->
        val app = value.asObject()
        val production = app["productionBranch"].asObject()
        val id = stableIdentifier(
            cleanId(app.string("appId")),
            "amplify-app",
            listOf(app.string("name"), app.string("repository", "defaultDomain")),
        ) ?: return@mapNotNull null
        HostingResource(
            id = id,
            name = cleanName(app.string("name")) ?: "Untitled app",
            subtitle = cleanText(app.string("repository", "description")),
            url = cleanText(app.string("defaultDomain"))?.let { "https://$it" }?.let(::cleanUrl),
            status = cleanStatus(production.string("status")) ?: "Configured",
            region = credentials.region,
            kind = cleanStatus(app.string("platform")) ?: "Amplify app",
            updatedAtMillis = app["updateTime"].dateMillis(),
            metadata = metadataOf("branch" to production.string("branchName")),
        )
    }

    override suspend fun fetchDeployments(resource: HostingResource): List<HostingDeployment> {
        val pageSize = HostingProviderApi.AWS_AMPLIFY_BRANCH_AND_JOB_PAGE_SIZE.toString()
        val branches = client.fetchTokenPages(
            path = listOf("apps", resource.id, "branches"),
            query = listOf("maxResults" to pageSize),
            itemKey = "branches",
            tokenQueryName = "nextToken",
            operation = "load Amplify branches",
        )
        val results = mutableListOf<HostingDeployment>()
        for (branchValue in branches) {
            currentCoroutineContext().ensureActive()
            val branchName = cleanId(branchValue.asObject().string("branchName")) ?: continue
            client.fetchTokenPages(
                path = listOf("apps", resource.id, "branches", branchName, "jobs"),
                query = listOf("maxResults" to pageSize),
                itemKey = "jobSummaries",
                tokenQueryName = "nextToken",
                operation = "load Amplify build jobs",
            ).forEach { value ->
                val job = value.asObject()
                results += HostingDeployment(
                    id = cleanId(job.string("jobId"))?.let { "$branchName/$it" }
                        ?: fallbackIdentifier("amplify-job", JsonObject(job.members + ("branchName" to JsonString(branchName)))),
                    title = job.string("jobType")?.capitalizedWords()?.let(::cleanTitle) ?: "Build job",
                    status = cleanStatus(job.string("status")) ?: "UNKNOWN",
                    createdAtMillis = job["startTime"].dateMillis(),
                    url = null,
                    branch = branchName,
                    commitMessage = cleanText(job.string("commitMessage", "commitId")),
                )
            }
        }
        return results.distinctBy(HostingDeployment::id).sortedByDescending { it.createdAtMillis ?: Long.MIN_VALUE }
    }

    override suspend fun performPrimaryAction(resource: HostingResource, latestDeploymentId: String?) {
        var branch = resource.metadata["branch"]?.trim().orEmpty()
        if (branch.isEmpty()) {
            branch = client.json(
                path = listOf("apps", resource.id, "branches"),
                query = listOf("maxResults" to "1"),
                operation = "load Amplify branches",
            ).asObject()["branches"].asArray().firstOrNull().asObject().string("branchName").orEmpty()
        }
        val safeBranch = cleanId(branch) ?: throw configuration("This Amplify app has no branch to release.")
        client.execute(
            HostingHttpMethod.POST,
            listOf("apps", resource.id, "branches", safeBranch, "jobs"),
            "{\"jobType\":\"RELEASE\"}",
            "start the Amplify release",
        )
    }
}

// endregion

// region Normalization helpers

private const val MAX_NAME_CHARACTERS = 256
private const val MAX_TITLE_CHARACTERS = 256
private const val MAX_STATUS_CHARACTERS = 128
private const val MAX_TEXT_CHARACTERS = 1_024
private const val MAX_URL_CHARACTERS = 2_048

internal fun cleanId(value: String?): String? =
    value?.takeIf { it.isNotBlank() && it.length <= MAX_HOSTING_ID_CHARACTERS && it.none(Char::isISOControl) }

internal fun cleanText(value: String?, maximum: Int = MAX_TEXT_CHARACTERS): String? =
    value?.trim()?.takeIf(String::isNotEmpty)?.take(maximum)

private fun cleanName(value: String?): String? = cleanText(value?.lineSequence()?.firstOrNull(), MAX_NAME_CHARACTERS)

private fun cleanTitle(value: String?): String? =
    cleanText(value?.trim()?.lineSequence()?.firstOrNull(), MAX_TITLE_CHARACTERS)

private fun cleanStatus(value: String?): String? = cleanText(value, MAX_STATUS_CHARACTERS)

/** Only absolute HTTPS links are kept; anything else is not offered as an "Open" action. */
internal fun cleanUrl(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_CHARACTERS } ?: return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    return trimmed.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null }
}

/** Swift `String.capitalized` for provider enum strings such as `web_service` or `RELEASE`. */
internal fun String.capitalizedWords(): String = split(' ').filter(String::isNotEmpty).joinToString(" ") { word ->
    word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}

private fun metadataOf(vararg entries: Pair<String, String?>): Map<String, String> =
    entries.mapNotNull { (key, value) -> cleanText(value, MAX_TEXT_CHARACTERS)?.let { key to it } }.toMap()

private fun credentialFingerprint(secret: SecretValue): String {
    val bytes = secret.use { it.toByteArray(StandardCharsets.UTF_8) }
    return try {
        sha256Hex(bytes).take(16)
    } finally {
        bytes.fill(0)
    }
}

private fun invalid(message: String) =
    HostingApiException(HostingFailure(HostingFailureKind.INVALID_RESPONSE, message))

private fun configuration(message: String) =
    HostingApiException(HostingFailure(HostingFailureKind.CONFIGURATION, message))

// endregion
