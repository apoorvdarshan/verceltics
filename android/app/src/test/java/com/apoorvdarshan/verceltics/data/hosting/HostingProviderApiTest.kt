package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingProviderApiTest {
    // region Railway

    @Test
    fun railwayAccountTokenValidatesWithBearerGraphqlMe() = runBlocking {
        val transport = routes(
            "POST backboard.railway.com/graphql/v2" to {
                jsonResponse("""{"data":{"me":{"id":"user_1","name":"Ada Lovelace","email":"ada@example.com","avatar":"https://avatars.example/ada.png"}}}""")
            },
        )
        val profile = HostingProviderApi(transport).validateProfile(railway(RailwayTokenType.ACCOUNT))

        assertEquals(HostingProfile("user_1", "Ada Lovelace", "ada@example.com", "https://avatars.example/ada.png"), profile)
        val request = transport.requests.single()
        assertEquals(HostingHttpMethod.POST, request.method)
        assertEquals("railway-token", request.bearerToken())
        assertEquals("query { me { id name email avatar } }", request.graphqlQuery())
    }

    @Test
    fun railwayProjectTokenUsesProjectAccessHeaderAndProjectQueries() = runBlocking {
        val transport = routes(
            "POST backboard.railway.com/graphql/v2" to { request ->
                when {
                    "projectToken" in request.graphqlQuery() ->
                        jsonResponse("""{"data":{"projectToken":{"projectId":"proj_9","environmentId":"env_1"}}}""")
                    "project(id:" in request.graphqlQuery() -> {
                        assertEquals("proj_9", request.graphqlVariables().string("id"))
                        jsonResponse("""{"data":{"project":{"id":"proj_9","name":"Checkout","description":"Payments","updatedAt":"2026-10-01T10:00:00Z"}}}""")
                    }
                    else -> error("unexpected query")
                }
            },
        )
        val api = HostingProviderApi(transport)
        val credentials = railway(RailwayTokenType.PROJECT)

        val profile = api.validateProfile(credentials)
        val resources = api.fetchResources(credentials)

        assertEquals("proj_9", profile.id)
        assertEquals("Railway Project", profile.name)
        assertTrue(transport.requests.all { it.auth is HostingAuth.RailwayProjectToken })
        val project = resources.single()
        assertEquals("Checkout", project.name)
        assertEquals("Payments", project.subtitle)
        assertEquals(mapOf("environmentID" to "env_1"), project.metadata)
        assertEquals(parseIsoInstant("2026-10-01T10:00:00Z"), project.updatedAtMillis)
    }

    @Test
    fun railwayProjectsFollowCursorPaginationAndSortByName() = runBlocking {
        val transport = routes(
            "POST backboard.railway.com/graphql/v2" to { request ->
                val variables = request.graphqlVariables()
                assertEquals("100", (variables["first"] as JsonNumber).raw)
                when (variables.string("after")) {
                    null -> jsonResponse(
                        """{"data":{"projects":{"edges":[{"node":{"id":"p2","name":"zeta","description":null}},
                           {"node":{"id":"p1","name":"Alpha"}}],"pageInfo":{"hasNextPage":true,"endCursor":"cur-1"}}}}""",
                    )
                    "cur-1" -> jsonResponse(
                        """{"data":{"projects":{"edges":[{"node":{"id":"p3","name":"beta","createdAt":"2026-01-01T00:00:00Z"}}],
                           "pageInfo":{"hasNextPage":false,"endCursor":null}}}}""",
                    )
                    else -> error("unexpected cursor")
                }
            },
        )
        val resources = HostingProviderApi(transport).fetchResources(railway(RailwayTokenType.ACCOUNT))
        assertEquals(listOf("Alpha", "beta", "zeta"), resources.map { it.name })
        assertTrue(resources.all { it.kind == "Project" && it.status == "Project" })
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun railwayRepeatedCursorAndGraphqlErrorsFailSafely() = runBlocking {
        val repeating = routes(
            "POST backboard.railway.com/graphql/v2" to {
                jsonResponse("""{"data":{"projects":{"edges":[],"pageInfo":{"hasNextPage":true,"endCursor":"same"}}}}""")
            },
        )
        val repeated = assertFailure { HostingProviderApi(repeating).fetchResources(railway(RailwayTokenType.ACCOUNT)) }
        assertEquals("Railway pagination repeated a cursor.", repeated.message)

        val unauthorized = routes(
            "POST backboard.railway.com/graphql/v2" to {
                jsonResponse("""{"errors":[{"message":"Not Authorized: railway-token was echoed"}],"data":null}""")
            },
        )
        val auth = assertFailure { HostingProviderApi(unauthorized).validateProfile(railway(RailwayTokenType.ACCOUNT)) }
        assertEquals(HostingFailureKind.AUTHENTICATION, auth.kind)
        assertFalse(auth.message.contains("railway-token"))

        val other = routes(
            "POST backboard.railway.com/graphql/v2" to { jsonResponse("""{"errors":[{"message":"Problem processing request"}]}""") },
        )
        assertEquals(HostingFailureKind.INVALID_RESPONSE, assertFailure { HostingProviderApi(other).validateProfile(railway(RailwayTokenType.ACCOUNT)) }.kind)
    }

    @Test
    fun railwayDeploymentsFanOutAcrossServicesAndEnvironmentsNewestFirst() = runBlocking {
        val transport = routes(
            "POST backboard.railway.com/graphql/v2" to { request ->
                val query = request.graphqlQuery()
                when {
                    "services(" in query -> jsonResponse(
                        """{"data":{"project":{"services":{"edges":[{"node":{"id":"svc_web","name":"web"}},{"node":{"id":"svc_worker","name":"worker"}}],
                           "pageInfo":{"hasNextPage":false}}}}}""",
                    )
                    "environments(" in query -> jsonResponse(
                        """{"data":{"project":{"environments":{"edges":[{"node":{"id":"env_prod","name":"production"}}],"pageInfo":{"hasNextPage":false}}}}}""",
                    )
                    "deployments(" in query -> {
                        val input = request.graphqlVariables()["input"].asObject()
                        assertEquals("proj_1", input.string("projectId"))
                        assertEquals("env_prod", input.string("environmentId"))
                        when (input.string("serviceId")) {
                            "svc_web" -> jsonResponse(
                                """{"data":{"deployments":{"edges":[
                                   {"node":{"id":"dep_old","status":"REMOVED","createdAt":"2026-09-01T00:00:00Z","meta":{"commitMessage":"Initial","branch":"main"},"canRedeploy":true}},
                                   {"node":{"id":"dep_new","status":"SUCCESS","createdAt":"2026-10-01T00:00:00Z","staticUrl":"https://web.up.railway.app","meta":{"commitMessage":"Ship it\nbody","branch":"main"},"canRedeploy":true,"canRollback":false}}],
                                   "pageInfo":{"hasNextPage":false}}}}""",
                            )
                            else -> jsonResponse(
                                """{"data":{"deployments":{"edges":[{"node":{"id":"dep_worker","status":"CRASHED","createdAt":"2026-09-15T00:00:00Z","meta":null}}],"pageInfo":{"hasNextPage":false}}}}""",
                            )
                        }
                    }
                    else -> error("unexpected query $query")
                }
            },
        )
        val deployments = HostingProviderApi(transport).fetchDeployments(
            railway(RailwayTokenType.ACCOUNT),
            resource("proj_1"),
        )

        assertEquals(listOf("dep_new", "dep_worker", "dep_old"), deployments.map { it.id })
        val latest = deployments.first()
        assertEquals("web · production", latest.title)
        assertEquals("SUCCESS", latest.status)
        assertEquals("https://web.up.railway.app", latest.url)
        assertEquals("main", latest.branch)
        assertEquals("Ship it\nbody", latest.commitMessage)
        assertEquals("svc_web", latest.metadata["serviceID"])
        assertEquals("true", latest.metadata["canRedeploy"])
        assertEquals("worker · production", deployments[1].title)
    }

    @Test
    fun railwayRedeployTargetsLatestDeploymentAndRequiresOne() = runBlocking {
        val transport = routes(
            "POST backboard.railway.com/graphql/v2" to { jsonResponse("""{"data":{"deploymentRedeploy":{"id":"dep_2","status":"QUEUED"}}}""") },
        )
        val api = HostingProviderApi(transport)
        api.performPrimaryAction(railway(RailwayTokenType.ACCOUNT), resource("proj_1"), latestDeploymentId = "dep_1")

        val request = transport.requests.single()
        assertTrue(request.graphqlQuery().startsWith("mutation deploymentRedeploy"))
        assertEquals("dep_1", request.graphqlVariables().string("id"))

        val missing = assertFailure { api.performPrimaryAction(railway(RailwayTokenType.ACCOUNT), resource("proj_1"), null) }
        assertEquals(HostingFailureKind.CONFIGURATION, missing.kind)
        assertEquals("No Railway deployment is available to redeploy.", missing.message)
    }

    // endregion

    // region Render

    @Test
    fun renderValidatesFirstOwnerAndRejectsEmptyWorkspaceList() = runBlocking {
        val transport = routes(
            "GET api.render.com/v1/owners" to { request ->
                assertEquals("100", request.queryValue("limit"))
                jsonResponse("""[{"owner":{"id":"tea_1","name":"Studio","email":"ops@studio.example","type":"team"},"cursor":"c"}]""")
            },
        )
        val profile = HostingProviderApi(transport).validateProfile(HostingCredentials.Render(SecretValue.of("rnd_key")))
        assertEquals(HostingProfile("tea_1", "Studio", "ops@studio.example", null), profile)
        assertEquals("rnd_key", transport.requests.single().bearerToken())

        val empty = routes("GET api.render.com/v1/owners" to { jsonResponse("[]") })
        assertEquals(
            "No Render workspace was returned.",
            assertFailure { HostingProviderApi(empty).validateProfile(HostingCredentials.Render(SecretValue.of("k"))) }.message,
        )
    }

    @Test
    fun renderServicesFollowLastItemCursorAndNormalizeTypeRegionAndSuspension() = runBlocking {
        val transport = routes(
            "GET api.render.com/v1/services" to { request ->
                assertEquals("100", request.queryValue("limit"))
                assertEquals("true", request.queryValue("includePreviews"))
                when (request.queryValue("cursor")) {
                    null -> jsonResponse(
                        """[{"cursor":"cur-a","service":{"id":"srv-web","name":"studio-web","type":"web_service","repo":"https://github.com/studio/web",
                             "suspended":"not_suspended","updatedAt":"2026-10-02T08:00:00.000Z","serviceDetails":{"region":"oregon","url":"https://studio-web.onrender.com"}}},
                            {"cursor":"cur-b","service":{"id":"srv-cron","name":"nightly","type":"cron_job","imagePath":"docker.io/studio/cron","suspended":true,"region":"frankfurt"}}]""",
                    )
                    "cur-b" -> jsonResponse("[]")
                    else -> error("unexpected cursor")
                }
            },
        )
        val resources = HostingProviderApi(transport).fetchResources(HostingCredentials.Render(SecretValue.of("k")))

        assertEquals(listOf("nightly", "studio-web"), resources.map { it.name })
        val cron = resources[0]
        assertEquals("Cron Job", cron.kind)
        assertEquals("Suspended", cron.status)
        assertEquals("frankfurt", cron.region)
        assertEquals("docker.io/studio/cron", cron.subtitle)
        val web = resources[1]
        assertEquals("Web Service", web.kind)
        assertEquals("Active", web.status)
        assertEquals("oregon", web.region)
        assertEquals("https://github.com/studio/web", web.subtitle)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun renderRepeatedCursorFailsAndDeploysMapCommitMessages() = runBlocking {
        val looping = routes("GET api.render.com/v1/services" to { jsonResponse("""[{"cursor":"same","service":{"id":"srv-1","name":"a"}}]""") })
        assertEquals(
            "Render pagination repeated a cursor.",
            assertFailure { HostingProviderApi(looping).fetchResources(HostingCredentials.Render(SecretValue.of("k"))) }.message,
        )

        val transport = routes(
            "GET api.render.com/v1/services/srv-web/deploys" to {
                jsonResponse(
                    """[{"deploy":{"id":"dep-1","status":"live","createdAt":"2026-10-02T08:00:00Z","commit":{"id":"abc123","message":"Fix headers\n\nLonger body"}},"cursor":"x"},
                        {"deploy":{"id":"dep-0","status":"deactivated","commit":{"id":"def456"}},"cursor":"y"}]""",
                )
            },
        )
        val transportWithEnd = routes(
            "GET api.render.com/v1/services/srv-web/deploys" to { request ->
                if (request.queryValue("cursor") == null) transport.handlerFor(request) else jsonResponse("[]")
            },
        )
        val deployments = HostingProviderApi(transportWithEnd).fetchDeployments(HostingCredentials.Render(SecretValue.of("k")), resource("srv-web"))
        assertEquals("Fix headers", deployments[0].title)
        assertEquals("live", deployments[0].status)
        assertEquals("Fix headers\n\nLonger body", deployments[0].commitMessage)
        assertEquals("Deploy", deployments[1].title)
        assertEquals("def456", deployments[1].commitMessage)
    }

    @Test
    fun renderRedeployPostsEmptyJsonToServiceDeploys() = runBlocking {
        val transport = routes("POST api.render.com/v1/services/srv-web/deploys" to { jsonResponse("""{"id":"dep-2"}""", status = 201) })
        HostingProviderApi(transport).performPrimaryAction(HostingCredentials.Render(SecretValue.of("k")), resource("srv-web"), null)
        assertEquals("{}", transport.requests.single().bodyText())
        assertEquals("application/json", transport.requests.single().contentType)
    }

    // endregion

    // region DigitalOcean

    @Test
    fun digitalOceanUsesSingleV2PrefixAndNormalizesApps() = runBlocking {
        val transport = routes(
            "GET api.digitalocean.com/v2/account" to {
                jsonResponse("""{"account":{"uuid":"do-uuid","email":"ops@example.com","status":"active"}}""")
            },
            "GET api.digitalocean.com/v2/apps" to { request ->
                assertEquals("200", request.queryValue("per_page"))
                assertEquals("1", request.queryValue("page"))
                jsonResponse(
                    """{"apps":[{"id":"app-1","spec":{"name":"shop"},"default_ingress":"https://shop-abc.ondigitalocean.app",
                        "live_url":"https://shop.example","active_deployment":{"phase":"ACTIVE"},"region":{"slug":"nyc","label":"New York"},
                        "updated_at":"2026-09-30T12:00:00Z"},
                       {"id":"app-2","spec":{"name":"api"},"phase":"PENDING_DEPLOY"}],"links":{},"meta":{"total":2}}""",
                )
            },
        )
        val api = HostingProviderApi(transport)
        val credentials = HostingCredentials.DigitalOcean(SecretValue.of("dop_v1"))
        val profile = api.validateProfile(credentials)
        val resources = api.fetchResources(credentials)

        assertEquals(HostingProfile("do-uuid", "ops@example.com", "ops@example.com", null), profile)
        assertEquals(listOf("/v2/account", "/v2/apps"), transport.paths())
        assertEquals(listOf("api", "shop"), resources.map { it.name })
        val shop = resources[1]
        assertEquals("https://shop.example", shop.url)
        assertEquals("https://shop-abc.ondigitalocean.app", shop.subtitle)
        assertEquals("ACTIVE", shop.status)
        assertEquals("nyc", shop.region)
        assertEquals("PENDING_DEPLOY", resources[0].status)
    }

    @Test
    fun digitalOceanDeploymentsAndRedeploy() = runBlocking {
        val transport = routes(
            "GET api.digitalocean.com/v2/apps/app-1/deployments" to {
                jsonResponse("""{"deployments":[{"id":"d-2","cause":"commit abc pushed to main","phase":"ACTIVE","created_at":"2026-10-01T00:00:00Z"},{"phase":"ERROR"}]}""")
            },
            "POST api.digitalocean.com/v2/apps/app-1/deployments" to { jsonResponse("""{"deployment":{"id":"d-3"}}""") },
        )
        val api = HostingProviderApi(transport)
        val credentials = HostingCredentials.DigitalOcean(SecretValue.of("dop_v1"))
        val deployments = api.fetchDeployments(credentials, resource("app-1"))
        assertEquals("commit abc pushed to main", deployments[0].title)
        assertEquals("ACTIVE", deployments[0].status)
        assertTrue(deployments[1].id.matches(Regex("digitalocean-deploy-[0-9a-f]{20}")))
        assertEquals("Deployment", deployments[1].title)

        api.performPrimaryAction(credentials, resource("app-1"), null)
        assertEquals(HostingHttpMethod.POST, transport.requests.last().method)
        assertEquals("{}", transport.requests.last().bodyText())
    }

    // endregion

    // region Heroku

    @Test
    fun herokuFollowsRangePaginationAndSummarizesLiveDynoStates() = runBlocking {
        val transport = routes(
            "GET api.heroku.com/apps" to { request ->
                assertEquals("application/vnd.heroku+json; version=3", request.headers["Accept"])
                when (request.headers["Range"]) {
                    HEROKU_INITIAL_RANGE -> jsonResponse(
                        """[{"id":"a1","name":"shop","web_url":"https://shop.herokuapp.com/","maintenance":false,"region":{"name":"us"},"stack":{"name":"heroku-24"},"updated_at":"2026-10-01T00:00:00Z"},
                            {"id":"a2","name":"admin","maintenance":true,"region":{"name":"eu"}}]""",
                        status = 206,
                        headers = mapOf("Next-Range" to listOf("]a2..; max=200; order=asc")),
                    )
                    "]a2..; max=200; order=asc" -> jsonResponse("""[{"id":"a3","name":"billing"}]""")
                    else -> error("unexpected range ${request.headers["Range"]}")
                }
            },
            "GET api.heroku.com/apps/a1/dynos" to { jsonResponse("""[{"state":"up"},{"state":"up"}]""") },
            "GET api.heroku.com/apps/a2/dynos" to { jsonResponse("""[{"state":"crashed"}]""") },
            "GET api.heroku.com/apps/a3/dynos" to { jsonResponse("""{"id":"forbidden","message":"denied"}""", status = 403) },
        )
        val resources = HostingProviderApi(transport).fetchResources(HostingCredentials.Heroku(SecretValue.of("hrku")))

        assertEquals(listOf("admin", "billing", "shop"), resources.map { it.name })
        assertEquals(listOf("Maintenance", "Unknown", "Running"), resources.map { it.status })
        val shop = resources[2]
        assertEquals("heroku-24", shop.subtitle)
        assertEquals("us", shop.region)
        assertEquals("https://shop.herokuapp.com/", shop.url)
        assertEquals(2, transport.requests.count { it.encodedPath == "/apps" })
    }

    @Test
    fun herokuRepeatedRangeFailsAndReleasesSortNewestVersionFirst() = runBlocking {
        val looping = routes(
            "GET api.heroku.com/apps" to { jsonResponse("[]", headers = mapOf("Next-Range" to listOf(HEROKU_INITIAL_RANGE))) },
        )
        assertEquals(
            "Heroku pagination repeated a range.",
            assertFailure { HostingProviderApi(looping).fetchResources(HostingCredentials.Heroku(SecretValue.of("k"))) }.message,
        )

        val transport = routes(
            "GET api.heroku.com/apps/a1/releases" to {
                jsonResponse(
                    """[{"id":"r-b","version":11,"status":"succeeded","description":"Deploy 1a2b3c","created_at":"2026-09-01T00:00:00Z","current":false,"eligible_for_rollback":true},
                        {"id":"r-a","version":12,"status":"succeeded","description":"Set FOO config vars","created_at":"2026-10-01T00:00:00Z","current":true,"output_stream_url":"https://busl.heroku.com/streams/x"}]""",
                )
            },
        )
        val releases = HostingProviderApi(transport).fetchDeployments(HostingCredentials.Heroku(SecretValue.of("k")), resource("a1"))
        assertEquals(listOf("Release v12", "Release v11"), releases.map { it.title })
        assertEquals("https://busl.heroku.com/streams/x", releases[0].url)
        assertEquals("true", releases[1].metadata["rollbackEligible"])
    }

    @Test
    fun herokuRestartDeletesAllDynos() = runBlocking {
        val transport = routes("DELETE api.heroku.com/apps/a1/dynos" to { jsonResponse("{}", status = 202) })
        HostingProviderApi(transport).performPrimaryAction(HostingCredentials.Heroku(SecretValue.of("k")), resource("a1"), null)
        assertNull(transport.requests.single().bodyCopy())
    }

    @Test
    fun herokuStatusUsesLiveDynoStates() {
        // iOS testHerokuStatusUsesLiveDynoStates, plus the remaining documented branches.
        assertEquals("Maintenance", HostingProviderApi.herokuAppStatus(true, listOf("up")))
        assertEquals("Unknown", HostingProviderApi.herokuAppStatus(false, null))
        assertEquals("Stopped", HostingProviderApi.herokuAppStatus(false, emptyList()))
        assertEquals("Running", HostingProviderApi.herokuAppStatus(false, listOf("up", "UP")))
        assertEquals("Degraded", HostingProviderApi.herokuAppStatus(false, listOf("up", "crashed")))
        assertEquals("Crashed", HostingProviderApi.herokuAppStatus(false, listOf("crashed")))
        assertEquals("Idle", HostingProviderApi.herokuAppStatus(false, listOf("idle")))
        assertEquals("Stopped", HostingProviderApi.herokuAppStatus(false, listOf("down", "down")))
        assertEquals("Starting", HostingProviderApi.herokuAppStatus(false, listOf("starting", "down")))
        assertEquals("Degraded", HostingProviderApi.herokuAppStatus(false, listOf("up", "starting")))
        assertEquals("Unknown", HostingProviderApi.herokuAppStatus(false, listOf("down", "mystery")))
    }

    // endregion

    // region Fly.io

    @Test
    fun flyValidatesOrganizationAndLoadsAppsWithLiveMachineState() = runBlocking {
        val transport = routes(
            "GET api.machines.dev/v1/apps" to { request ->
                assertEquals("personal", request.queryValue("org_slug"))
                jsonResponse(
                    """{"total_apps":2,"apps":[{"id":"id-edge","name":"edge-proxy","machine_count":9,"volume_count":1,"network":"default"},
                        {"id":"id-chat","name":"realtime-chat","machine_count":2,"volume_count":0}]}""",
                )
            },
            "GET api.machines.dev/v1/apps/edge-proxy/machines" to {
                jsonResponse(
                    """[{"id":"m1","state":"started","updated_at":"2026-10-01T00:00:00Z"},{"id":"m2","state":"started","updated_at":"2026-10-02T00:00:00Z"}]""",
                )
            },
            "GET api.machines.dev/v1/apps/realtime-chat/machines" to { jsonResponse("{}", status = 500) },
        )
        val api = HostingProviderApi(transport)
        val credentials = HostingCredentials.Fly(SecretValue.of("fo1_token"), "personal")
        val profile = api.validateProfile(credentials)
        val resources = api.fetchResources(credentials)

        assertEquals(HostingProfile("personal", "Fly.io Personal", null, null), profile)
        val edge = resources.first { it.name == "edge-proxy" }
        assertEquals("id-edge", edge.id)
        assertEquals("Running", edge.status)
        assertEquals("2 Machines · 1 volumes", edge.subtitle)
        assertEquals("https://edge-proxy.fly.dev", edge.url)
        assertEquals("edge-proxy", edge.metadata["appName"])
        assertEquals(parseIsoInstant("2026-10-02T00:00:00Z"), edge.updatedAtMillis)
        val chat = resources.first { it.name == "realtime-chat" }
        assertEquals("Unknown", chat.status)
        assertEquals("2 Machines · 0 volumes", chat.subtitle)
        assertNull(chat.updatedAtMillis)
    }

    @Test
    fun flyMachinesIncludeDeletedAndRestartHitsEveryMachine() = runBlocking {
        val machines = """[{"id":"m1","name":"edge-ams","state":"started","region":"ams","created_at":"2026-10-01T00:00:00Z","config":{"image":"registry.fly.io/edge:v42"}},
                           {"id":"m2","name":"edge-sin","state":"destroyed","region":"sin"}]"""
        val transport = routes(
            "GET api.machines.dev/v1/apps/edge-proxy/machines" to { jsonResponse(machines) },
            "POST api.machines.dev/v1/apps/edge-proxy/machines/m1/restart" to { jsonResponse("""{"ok":true}""") },
            "POST api.machines.dev/v1/apps/edge-proxy/machines/m2/restart" to { jsonResponse("""{"ok":true}""") },
        )
        val api = HostingProviderApi(transport)
        val credentials = HostingCredentials.Fly(SecretValue.of("fo1_token"), "personal")
        val flyApp = resource("id-edge", "edge-proxy", metadata = mapOf("appName" to "edge-proxy"))

        val history = api.fetchDeployments(credentials, flyApp)
        assertEquals("true", transport.requests.single().queryValue("include_deleted"))
        assertEquals(listOf("edge-ams", "edge-sin"), history.map { it.title })
        assertEquals("ams", history[0].branch)
        assertEquals("registry.fly.io/edge:v42", history[0].commitMessage)

        api.performPrimaryAction(credentials, flyApp, null)
        assertEquals(
            listOf("/v1/apps/edge-proxy/machines/m1/restart", "/v1/apps/edge-proxy/machines/m2/restart"),
            transport.requests.filter { it.method == HostingHttpMethod.POST }.map { it.encodedPath },
        )
    }

    @Test
    fun flyStatusUsesLiveMachineStates() {
        // iOS testFlyStatusUsesLiveMachineStates, plus the remaining documented branches.
        assertEquals("Unknown", HostingProviderApi.flyAppStatus(null))
        assertEquals("Stopped", HostingProviderApi.flyAppStatus(emptyList()))
        assertEquals("Running", HostingProviderApi.flyAppStatus(listOf("started", "started")))
        assertEquals("Degraded", HostingProviderApi.flyAppStatus(listOf("started", "failed")))
        assertEquals("Failed", HostingProviderApi.flyAppStatus(listOf("failed")))
        assertEquals("Suspended", HostingProviderApi.flyAppStatus(listOf("suspended")))
        assertEquals("Stopped", HostingProviderApi.flyAppStatus(listOf("stopped", "destroyed")))
        assertEquals("Starting", HostingProviderApi.flyAppStatus(listOf("created", "stopped")))
        assertEquals("Stopping", HostingProviderApi.flyAppStatus(listOf("stopping")))
        assertEquals("Degraded", HostingProviderApi.flyAppStatus(listOf("started", "stopped")))
        assertEquals("Unknown", HostingProviderApi.flyAppStatus(listOf("mystery")))
    }

    // endregion

    // region Firebase Hosting

    @Test
    fun firebaseWithoutGoogleTokenRequiresSignInAndSendsNothing() = runBlocking {
        val requestedScopes = mutableListOf<Set<String>>()
        val transport = routes()
        val api = HostingProviderApi(transport, GoogleAccessTokenSource { scopes -> requestedScopes += scopes; null })

        val failure = assertFailure { api.validateProfile(HostingCredentials.Firebase("studio-prod")) }
        assertEquals(HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED, failure.kind)
        assertTrue(transport.requests.isEmpty())
        assertEquals(
            listOf(setOf("openid", "email", "https://www.googleapis.com/auth/firebase.hosting")),
            requestedScopes,
        )
    }

    @Test
    fun firebaseValidatesProjectAndReadsGoogleIdentity() = runBlocking {
        var tokenRequests = 0
        val transport = routes(
            "GET firebasehosting.googleapis.com/v1beta1/projects/studio-prod/sites" to { request ->
                assertEquals("1", request.queryValue("pageSize"))
                jsonResponse("""{"sites":[{"name":"projects/studio-prod/sites/studio-prod"}]}""")
            },
            "GET openidconnect.googleapis.com/v1/userinfo" to {
                jsonResponse("""{"sub":"1098","email":"owner@studio.example","email_verified":true}""")
            },
        )
        val api = HostingProviderApi(transport, GoogleAccessTokenSource { tokenRequests += 1; "ya29.fake-token" })

        val profile = api.validateProfile(HostingCredentials.Firebase(" studio-prod "))
        assertEquals(HostingProfile("1098:studio-prod", "studio-prod", "owner@studio.example", null), profile)
        assertTrue(transport.requests.all { it.bearerToken() == "ya29.fake-token" })
        assertEquals(1, tokenRequests)
    }

    @Test
    fun firebaseSitesFollowPageTokensAndReleasesNormalize() = runBlocking {
        val transport = routes(
            "GET firebasehosting.googleapis.com/v1beta1/projects/studio-prod/sites" to { request ->
                assertEquals("100", request.queryValue("pageSize"))
                when (request.queryValue("pageToken")) {
                    null -> jsonResponse(
                        """{"sites":[{"name":"projects/studio-prod/sites/studio-prod","defaultUrl":"https://studio-prod.web.app","type":"DEFAULT_SITE"}],"nextPageToken":"tok/=="}""",
                    )
                    "tok/==" -> jsonResponse(
                        """{"sites":[{"name":"projects/studio-prod/sites/docs-site","defaultUrl":"https://docs-site.web.app","appId":"1:123:web:abc","type":"USER_SITE"}]}""",
                    )
                    else -> error("unexpected token")
                }
            },
            "GET firebasehosting.googleapis.com/v1beta1/sites/docs-site/releases" to {
                jsonResponse(
                    """{"releases":[{"name":"sites/docs-site/releases/123","type":"DEPLOY","message":"Launch docs","releaseTime":"2026-10-03T09:30:00.123456Z",
                        "version":{"name":"sites/docs-site/versions/v1","status":"FINALIZED"}},
                       {"name":"sites/docs-site/releases/122","type":"ROLLBACK","version":{"finalizeTime":"2026-10-02T00:00:00Z"}}]}""",
                )
            },
        )
        val api = HostingProviderApi(transport, GoogleAccessTokenSource { "token" })
        val credentials = HostingCredentials.Firebase("studio-prod")
        val sites = api.fetchResources(credentials)

        assertEquals(listOf("docs-site", "studio-prod"), sites.map { it.id })
        assertEquals("User Site", sites[0].kind)
        assertEquals("1:123:web:abc", sites[0].subtitle)
        assertEquals("Default Site", sites[1].kind)
        assertEquals("https://studio-prod.web.app", sites[1].url)
        assertEquals("projects/studio-prod/sites/docs-site", sites[0].metadata["fullName"])
        assertEquals("tok%2F%3D%3D", transport.requests[1].encodedQuery!!.substringAfter("pageToken="))

        val releases = api.fetchDeployments(credentials, sites[0].copy())
        assertEquals("Deploy", releases[0].title)
        assertEquals("FINALIZED", releases[0].status)
        assertEquals("Launch docs", releases[0].commitMessage)
        assertEquals(parseIsoInstant("2026-10-03T09:30:00.123Z"), releases[0].createdAtMillis)
        assertEquals("Rollback", releases[1].title)
        assertEquals("RELEASED", releases[1].status)
        assertEquals(parseIsoInstant("2026-10-02T00:00:00Z"), releases[1].createdAtMillis)
    }

    @Test
    fun firebaseExpiredAuthorizationAndUnsupportedActionAreExplicit() = runBlocking {
        val transport = routes(
            "GET firebasehosting.googleapis.com/v1beta1/projects/studio-prod/sites" to { jsonResponse("""{"error":{"code":401}}""", status = 401) },
        )
        val api = HostingProviderApi(transport, GoogleAccessTokenSource { "expired" })
        assertEquals(
            HostingFailureKind.GOOGLE_SIGN_IN_REQUIRED,
            assertFailure { api.fetchResources(HostingCredentials.Firebase("studio-prod")) }.kind,
        )
        assertEquals(
            HostingFailureKind.UNSUPPORTED,
            assertFailure { api.performPrimaryAction(HostingCredentials.Firebase("studio-prod"), resource("docs-site"), null) }.kind,
        )

        val throwing = HostingProviderApi(routes(), GoogleAccessTokenSource { throw IOException("offline") })
        assertEquals(HostingFailureKind.NETWORK, assertFailure { throwing.validateProfile(HostingCredentials.Firebase("p")) }.kind)
    }

    // endregion

    // region AWS Amplify

    @Test
    fun amplifyValidatesWithSignedRequestsToTheRegionalEndpoint() = runBlocking {
        val transport = routes(
            "GET amplify.eu-west-1.amazonaws.com/apps" to { request ->
                assertEquals("1", request.queryValue("maxResults"))
                jsonResponse("""{"apps":[]}""")
            },
        )
        val profile = HostingProviderApi(transport).validateProfile(amplify())
        assertEquals(HostingProfile("MPLE-eu-west-1", "AWS eu-west-1", null, null), profile)
        val auth = transport.requests.single().auth as HostingAuth.AwsSigV4
        assertEquals("eu-west-1", auth.region)
        assertEquals("amplify", auth.service)
        assertEquals("AKIAIOSFODNN7EXAMPLE", auth.accessKeyId)
    }

    @Test
    fun amplifyAppsFollowNextTokenAndNormalize() = runBlocking {
        val transport = routes(
            "GET amplify.eu-west-1.amazonaws.com/apps" to { request ->
                when (request.queryValue("nextToken")) {
                    null -> jsonResponse(
                        """{"apps":[{"appId":"d1abc","name":"storefront","repository":"https://github.com/studio/store","platform":"WEB_COMPUTE",
                            "defaultDomain":"d1abc.amplifyapp.com","updateTime":1759312800.5,"productionBranch":{"branchName":"main","status":"SUCCEED"}}],"nextToken":"n+1"}""",
                    )
                    "n+1" -> jsonResponse("""{"apps":[{"appId":"d2def","name":"admin","defaultDomain":"d2def.amplifyapp.com"}]}""")
                    else -> error("unexpected token")
                }
            },
        )
        val apps = HostingProviderApi(transport).fetchResources(amplify())
        assertEquals(listOf("admin", "storefront"), apps.map { it.name })
        val store = apps[1]
        assertEquals("https://d1abc.amplifyapp.com", store.url)
        assertEquals("SUCCEED", store.status)
        assertEquals("WEB_COMPUTE", store.kind)
        assertEquals("eu-west-1", store.region)
        assertEquals("main", store.metadata["branch"])
        assertEquals(1_759_312_800_500L, store.updatedAtMillis)
        assertEquals("Configured", apps[0].status)
        assertEquals("Amplify app", apps[0].kind)
    }

    @Test
    fun amplifyJobsSpanBranchesWithBoundedPagesAndSortNewestFirst() = runBlocking {
        val transport = routes(
            "GET amplify.eu-west-1.amazonaws.com/apps/d1abc/branches" to { request ->
                assertEquals("50", request.queryValue("maxResults"))
                jsonResponse("""{"branches":[{"branchName":"main"},{"branchName":"feature/login"}]}""")
            },
            "GET amplify.eu-west-1.amazonaws.com/apps/d1abc/branches/main/jobs" to { request ->
                assertEquals("50", request.queryValue("maxResults"))
                jsonResponse(
                    """{"jobSummaries":[{"jobId":"2","jobType":"RELEASE","status":"SUCCEED","startTime":1759312800,"commitMessage":"Release 2"},
                        {"jobId":"1","jobType":"RELEASE","status":"FAILED","startTime":1759226400,"commitId":"abc123"}]}""",
                )
            },
            "GET amplify.eu-west-1.amazonaws.com/apps/d1abc/branches/feature%2Flogin/jobs" to {
                jsonResponse("""{"jobSummaries":[{"jobId":"1","jobType":"WEB_HOOK","status":"RUNNING","startTime":1759399200}]}""")
            },
        )
        val jobs = HostingProviderApi(transport).fetchDeployments(amplify(), resource("d1abc"))
        assertEquals(listOf("feature/login/1", "main/2", "main/1"), jobs.map { it.id })
        assertEquals("Web_hook", jobs[0].title)
        assertEquals("feature/login", jobs[0].branch)
        assertEquals("Release", jobs[1].title)
        assertEquals("Release 2", jobs[1].commitMessage)
        assertEquals("abc123", jobs[2].commitMessage)
        assertEquals(50, HostingProviderApi.AWS_AMPLIFY_BRANCH_AND_JOB_PAGE_SIZE)
    }

    @Test
    fun amplifyReleaseUsesProductionBranchOrFirstBranch() = runBlocking {
        val transport = routes(
            "POST amplify.eu-west-1.amazonaws.com/apps/d1abc/branches/main/jobs" to { jsonResponse("""{"jobSummary":{"jobId":"3"}}""") },
            "GET amplify.eu-west-1.amazonaws.com/apps/d2def/branches" to { request ->
                assertEquals("1", request.queryValue("maxResults"))
                jsonResponse("""{"branches":[{"branchName":"main"}]}""")
            },
            "POST amplify.eu-west-1.amazonaws.com/apps/d2def/branches/main/jobs" to { jsonResponse("{}") },
            "GET amplify.eu-west-1.amazonaws.com/apps/d3/branches" to { jsonResponse("""{"branches":[]}""") },
        )
        val api = HostingProviderApi(transport)
        api.performPrimaryAction(amplify(), resource("d1abc", metadata = mapOf("branch" to "main")), null)
        assertEquals("""{"jobType":"RELEASE"}""", transport.requests.last().bodyText())

        api.performPrimaryAction(amplify(), resource("d2def"), null)
        assertEquals("/apps/d2def/branches/main/jobs", transport.requests.last().encodedPath)

        val none = assertFailure { api.performPrimaryAction(amplify(), resource("d3"), null) }
        assertEquals("This Amplify app has no branch to release.", none.message)
    }

    @Test
    fun amplifyAuthErrorsAreClassifiedFromStatusOrErrorType() = runBlocking {
        val transport = routes(
            "GET amplify.eu-west-1.amazonaws.com/apps" to {
                jsonResponse(
                    """{"message":"The security token included in the request is invalid."}""",
                    status = 400,
                    headers = mapOf("x-amzn-ErrorType" to listOf("UnrecognizedClientException:http://internal.amazon.com/")),
                )
            },
        )
        val failure = assertFailure { HostingProviderApi(transport).validateProfile(amplify()) }
        assertEquals(HostingFailureKind.AUTHENTICATION, failure.kind)
        assertEquals("AWS rejected these credentials or they lack Amplify permissions.", failure.message)
    }

    // endregion

    // region Shared behavior

    @Test
    fun httpFailuresMapToSafeMessagesWithoutEchoingProviderBodies() = runBlocking {
        val leaked = "render-secret-echo"
        fun failing(status: Int) = routes("GET api.render.com/v1/owners" to { jsonResponse("""{"message":"$leaked"}""", status = status) })
        val credentials = HostingCredentials.Render(SecretValue.of("k"))

        val cases = mapOf(
            401 to (HostingFailureKind.AUTHENTICATION to "Render rejected these credentials."),
            403 to (HostingFailureKind.PERMISSION to "Render denied access. Check the credential's permissions."),
            404 to (HostingFailureKind.NOT_FOUND to "Render could not find the requested resource."),
            429 to (HostingFailureKind.RATE_LIMITED to "Render is rate limiting requests. Please try again shortly."),
            503 to (HostingFailureKind.TEMPORARY to "Render is temporarily unavailable."),
            418 to (HostingFailureKind.TEMPORARY to "Unable to validate the Render API key (HTTP 418)."),
        )
        cases.forEach { (status, expected) ->
            val failure = assertFailure { HostingProviderApi(failing(status)).validateProfile(credentials) }
            assertEquals(expected.first, failure.kind)
            assertEquals(expected.second, failure.message)
            assertEquals(status, failure.statusCode)
            assertFalse(failure.message.contains(leaked))
        }
    }

    @Test
    fun networkAndMalformedResponsesBecomeTypedFailures() = runBlocking {
        val offline = FakeHostingTransport { throw IOException("socket closed") }
        val network = assertFailure { HostingProviderApi(offline).validateProfile(HostingCredentials.Heroku(SecretValue.of("k"))) }
        assertEquals(HostingFailureKind.NETWORK, network.kind)
        assertEquals("Heroku could not be reached. Check your connection and try again.", network.message)

        val malformed = routes("GET api.heroku.com/account" to { jsonResponse("<html>oops</html>") })
        val invalid = assertFailure { HostingProviderApi(malformed).validateProfile(HostingCredentials.Heroku(SecretValue.of("k"))) }
        assertEquals(HostingFailureKind.INVALID_RESPONSE, invalid.kind)

        val emptyBody = routes("GET api.heroku.com/account" to { jsonResponse("") })
        val fallback = HostingProviderApi(emptyBody).validateProfile(HostingCredentials.Heroku(SecretValue.of("heroku-token")))
        assertEquals("Heroku Account", fallback.name)
        assertTrue(fallback.id.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun dashboardLinksMatchIosAndEncodeIdentifiers() {
        val railway = HostingLinkContext(HostingProvider.RAILWAY)
        assertEquals("https://railway.com/dashboard", HostingProviderApi.dashboardUrl(railway))
        assertEquals("https://railway.com/project/p%201", HostingProviderApi.dashboardUrl(railway, resource("p 1")))
        assertEquals("https://dashboard.render.com/srv-1", HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.RENDER), resource("srv-1")))
        assertEquals("https://cloud.digitalocean.com/apps", HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.DIGITAL_OCEAN)))
        assertEquals(
            "https://dashboard.heroku.com/apps/shop",
            HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.HEROKU), resource("uuid-1", "shop")),
        )
        assertEquals("https://fly.io/apps/edge", HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.FLY), resource("id", "edge")))
        assertEquals(
            "https://console.firebase.google.com/project/studio-prod/hosting",
            HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.FIREBASE, firebaseProjectId = "studio-prod"), resource("x")),
        )
        assertEquals("https://console.firebase.google.com/", HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.FIREBASE)))
        assertEquals(
            "https://eu-west-1.console.aws.amazon.com/amplify/apps/d1",
            HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.AWS_AMPLIFY, awsRegion = "eu-west-1"), resource("d1")),
        )
        assertEquals(
            "https://us-east-1.console.aws.amazon.com/amplify/apps",
            HostingProviderApi.dashboardUrl(HostingLinkContext(HostingProvider.AWS_AMPLIFY, awsRegion = "evil.example/")),
        )
    }

    @Test
    fun credentialsValidateIdentifiersAndNeverPrintSecrets() {
        assertThrows(IllegalArgumentException::class.java) { HostingCredentials.Fly(SecretValue.of("t"), "  ") }
        assertThrows(IllegalArgumentException::class.java) { HostingCredentials.Firebase("bad\nproject") }
        assertThrows(IllegalArgumentException::class.java) {
            HostingCredentials.AwsAmplify("short", SecretValue.of("s"), "us-east-1", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostingCredentials.AwsAmplify("AKIAIOSFODNN7EXAMPLE", SecretValue.of("s"), "us-east-1.evil.example", null)
        }
        assertEquals("Enter a valid AWS region such as us-east-1.", HostingCredentials.awsIdentifierProblem("AKIAIOSFODNN7EXAMPLE", "mars-1"))
        assertEquals("Enter a valid AWS access key ID.", HostingCredentials.awsIdentifierProblem("nope", "us-east-1"))
        assertNull(HostingCredentials.awsIdentifierProblem(" AKIAIOSFODNN7EXAMPLE ", " us-east-1 "))

        val rendered = listOf(
            railway(RailwayTokenType.ACCOUNT),
            HostingCredentials.Render(SecretValue.of("railway-token")),
            HostingCredentials.Fly(SecretValue.of("railway-token"), "personal"),
            HostingCredentials.AwsAmplify("AKIAIOSFODNN7EXAMPLE", SecretValue.of("railway-token"), "us-east-1", SecretValue.of("railway-token")),
        ).joinToString { it.toString() }
        assertFalse(rendered.contains("railway-token"))
        assertFalse(rendered.contains("AKIAIOSFODNN7EXAMPLE"))
    }

    // endregion

    private fun railway(type: RailwayTokenType) = HostingCredentials.Railway(SecretValue.of("railway-token"), type)

    private fun amplify() = HostingCredentials.AwsAmplify(
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = SecretValue.of("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
        region = "eu-west-1",
        sessionToken = null,
    )

    private fun routes(vararg routes: Pair<String, (HostingHttpRequest) -> HttpResponse>): RoutedTransport =
        RoutedTransport(routes.toList())

    internal class RoutedTransport(
        private val routes: List<Pair<String, (HostingHttpRequest) -> HttpResponse>>,
    ) : HostingHttpTransport {
        private val delegate = FakeHostingTransport(::handlerFor)
        val requests get() = delegate.requests

        fun handlerFor(request: HostingHttpRequest): HttpResponse {
            val key = "${request.method} ${request.endpoint.host}${request.encodedPath}"
            return routes.firstOrNull { it.first == key }?.second?.invoke(request)
                ?: jsonResponse("""{"message":"no route for $key"}""", status = 404)
        }

        fun paths() = delegate.paths()

        override suspend fun send(request: HostingHttpRequest): HttpResponse = delegate.send(request)
    }

    private fun assertFailure(block: suspend () -> Unit): HostingFailure = assertThrows(HostingApiException::class.java) {
        runBlocking { block() }
    }.failure
}
