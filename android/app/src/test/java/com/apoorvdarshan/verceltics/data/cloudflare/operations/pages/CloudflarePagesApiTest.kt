package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.DELETE
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.GET
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.PATCH
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod.POST
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflarePagesApiTest {
    private val transport = FakeCloudflareRestTransport()
    private val sleeps = mutableListOf<Long>()
    private val api = CloudflarePagesApi(
        client = FakeCloudflareRestTransport.client(transport),
        ioDispatcher = Dispatchers.Unconfined,
        sleep = { sleeps += it },
        boundaryFactory = { "test-boundary" },
    )
    private val project = "/accounts/acc/pages/projects/site"

    @Test
    fun projectParsingKeepsOnlyPresenceOfSecrets() = runTest {
        transport.enqueueJson(GET, project, envelope(PROJECT_JSON))
        val detail = api.fetchProject("acc", "site")

        assertEquals("site", detail.name)
        assertEquals(listOf("site.example"), detail.domains)
        assertTrue(detail.buildConfig!!.webAnalyticsTokenConfigured)
        assertEquals("abc123", detail.latestDeployment?.shortId)
        assertEquals(CloudflarePagesEnvironment.PRODUCTION, detail.latestDeployment?.environment)
        val production = detail.deploymentConfigs?.production!!
        assertEquals(listOf("API_KEY", "PUBLIC_URL"), production.environmentVariables.keys.sorted())
        assertTrue(production.environmentVariables.getValue("API_KEY").isSecret)
        assertTrue(production.environmentVariables.getValue("API_KEY").valueConfigured)
        assertFalse(production.environmentVariables.getValue("PUBLIC_URL").valueConfigured)
        assertFalse(detail.toString().contains("super-secret"))
        assertEquals(listOf("D1", "KV"), production.bindingGroups.map { it.first })
        assertEquals(2, production.bindingCount)
        assertEquals("db-id", production.bindingGroups.first().second.getValue("DB").summary)
        assertEquals(30, production.cpuMilliseconds)
        assertEquals("smart", production.placementMode)
        assertTrue(CloudflarePagesProjectEditDraft.canSafelyEdit(detail))
    }

    @Test
    fun deploymentsUseEnvironmentFilterAndPagination() = runTest {
        val path = "$project/deployments"
        transport.enqueueJson(GET, path, envelope("[${deployment("d1")}]", "{\"page\":1,\"total_pages\":2}"))
        transport.enqueueJson(GET, path, envelope("[${deployment("d2")}]", "{\"page\":2,\"total_pages\":2}"))
        val deployments = api.fetchDeployments("acc", "site", CloudflarePagesEnvironment.PREVIEW)

        assertEquals(listOf("d1", "d2"), deployments.map { it.id })
        transport.requests.forEach { request ->
            assertTrue(request.query.contains("env" to "preview"))
            assertTrue(request.query.contains("per_page" to "20"))
        }
        assertEquals(listOf("API_KEY"), deployments.first().environmentVariableNames)
    }

    @Test
    fun logsParseFromHistoryEndpoint() = runTest {
        transport.enqueueJson(
            GET,
            "$project/deployments/d1/history/logs",
            envelope("{\"total\":2,\"data\":[{\"line\":\"Cloning\",\"ts\":\"2026-01-01T00:00:00Z\"},{\"line\":\"Done\",\"ts\":\"bad\"}]}"),
        )
        val logs = api.fetchDeploymentLogs("acc", "site", "d1")
        assertEquals(listOf("Cloning", "Done"), logs.map { it.line })
        assertTrue(logs.first().date != null)
        assertNull(logs.last().date)
    }

    @Test
    fun deploymentMutationsBuildIosRequestsAndRequireMatchingConfirmation() = runTest {
        transport.enqueueJson(POST, "$project/deployments/d1/retry", envelope(deployment("d9")))
        transport.enqueueJson(POST, "$project/deployments/d1/rollback", envelope(deployment("d1")))
        transport.enqueueJson(DELETE, "$project/deployments/d1", envelope("null"))

        assertEquals("d9", api.retryDeployment("acc", "site", "d1", CloudflareMutationConfirmation("d1")).id)
        api.rollbackDeployment("acc", "site", "d1", CloudflareMutationConfirmation("d1"))
        api.deleteDeployment("acc", "site", "d1", CloudflareMutationConfirmation("d1"), force = true)

        assertEquals(
            listOf("POST $project/deployments/d1/retry", "POST $project/deployments/d1/rollback", "DELETE $project/deployments/d1"),
            transport.requests.map { it.toString() },
        )
        assertEquals(listOf("force" to "true"), transport.requests.last().query)
        assertNull(transport.requests.first().bodyText)

        val before = transport.requests.size
        expectConfirmationRequired { api.retryDeployment("acc", "site", "d1", CloudflareMutationConfirmation("d2")) }
        expectConfirmationRequired { api.rollbackDeployment("acc", "site", "d1", CloudflareMutationConfirmation("other")) }
        expectConfirmationRequired { api.deleteDeployment("acc", "site", "d1", CloudflareMutationConfirmation("d2")) }
        assertEquals(before, transport.requests.size)
    }

    @Test
    fun projectSettingsPatchSendsSortedSafeFieldsOnly() = runTest {
        transport.enqueueJson(GET, project, envelope(PROJECT_JSON))
        val detail = api.fetchProject("acc", "site")
        transport.enqueueJson(PATCH, project, envelope(PROJECT_JSON))
        val draft = CloudflarePagesProjectEditDraft.from(detail).copy(
            productionBranch = " release ",
            pathIncludes = "src/**, docs/**\n",
            previewDeploymentSetting = "custom",
            previewBranchIncludes = "feature/*",
        )
        api.updateProject("acc", "site", draft, CloudflareMutationConfirmation(api.projectPath("acc", "site")))

        val body = transport.requests.last().bodyText!!
        assertEquals(
            "{\"build_config\":{\"build_caching\":true,\"build_command\":\"npm run build\",\"destination_dir\":\"dist\",\"root_dir\":\"\"}," +
                "\"production_branch\":\"release\",\"source\":{\"config\":{\"owner\":\"octo\",\"path_excludes\":[],\"path_includes\":[\"src/**\",\"docs/**\"]," +
                "\"pr_comments_enabled\":true,\"preview_branch_excludes\":[],\"preview_branch_includes\":[\"feature/*\"],\"preview_deployment_setting\":\"custom\"," +
                "\"production_branch\":\"release\",\"production_deployments_enabled\":true,\"repo_name\":\"site\"},\"type\":\"github\"}}",
            body,
        )
        assertFalse(body.contains("web_analytics"))
        assertFalse(body.contains("env_vars"))

        val directUploadDraft = draft.copy(sourceType = null)
        transport.enqueueJson(PATCH, project, envelope(PROJECT_JSON))
        api.updateProject("acc", "site", directUploadDraft, CloudflareMutationConfirmation(project))
        assertFalse(transport.requests.last().bodyText!!.contains("source"))

        val before = transport.requests.size
        expectConfirmationRequired { api.updateProject("acc", "site", draft, CloudflareMutationConfirmation("site")) }
        assertEquals(before, transport.requests.size)
    }

    @Test
    fun buildCacheAndProjectDeletionUseIosPaths() = runTest {
        transport.enqueueJson(POST, "$project/purge_build_cache", envelope("null"))
        transport.enqueueJson(DELETE, project, envelope("null"))
        api.purgeBuildCache("acc", "site", CloudflareMutationConfirmation("$project/purge_build_cache"))
        api.deleteProject("acc", "site", CloudflareMutationConfirmation(project))
        assertEquals(listOf("POST $project/purge_build_cache", "DELETE $project"), transport.requests.map { it.toString() })

        expectConfirmationRequired { api.purgeBuildCache("acc", "site", CloudflareMutationConfirmation(project)) }
        expectConfirmationRequired { api.deleteProject("acc", "site", CloudflareMutationConfirmation("$project/purge_build_cache")) }
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun domainsPaginateWithTotalCountAndMutationsUseDomainPaths() = runTest {
        val domains = "$project/domains"
        transport.enqueueJson(GET, domains, envelope("[${domain("a.example.com")}]", "{\"total_count\":2}"))
        transport.enqueueJson(GET, domains, envelope("[${domain("b.example.com")}]", "{\"total_count\":2}"))
        assertEquals(listOf("a.example.com", "b.example.com"), api.fetchDomains("acc", "site").map { it.name })

        transport.enqueueJson(POST, domains, envelope(domain("www.example.com")))
        api.addDomain("acc", "site", "  WWW.Example.com ", CloudflareMutationConfirmation(domains))
        assertEquals("{\"name\":\"www.example.com\"}", transport.requests.last().bodyText)

        transport.enqueueJson(PATCH, "$domains/www.example.com", envelope(domain("www.example.com")))
        api.retryDomainValidation("acc", "site", "www.example.com", CloudflareMutationConfirmation("$domains/www.example.com"))
        assertNull(transport.requests.last().bodyText)

        transport.enqueueJson(DELETE, "$domains/www.example.com", envelope("null"))
        api.deleteDomain("acc", "site", "www.example.com", CloudflareMutationConfirmation("$domains/www.example.com"))

        val before = transport.requests.size
        expectConfirmationRequired { api.addDomain("acc", "site", "x.example.com", CloudflareMutationConfirmation("$domains/x")) }
        expectConfirmationRequired { api.deleteDomain("acc", "site", "www.example.com", CloudflareMutationConfirmation(domains)) }
        val invalid = runCatching { api.addDomain("acc", "site", "not a host", CloudflareMutationConfirmation(domains)) }.exceptionOrNull()
        assertEquals("Enter a valid hostname such as www.example.com.", (invalid as CloudflareOperationException).userMessage)
        assertEquals(before, transport.requests.size)
    }

    @Test
    fun directUploadRunsTheWranglerContractWithJwtOnlyOnAssetEndpoints() = runTest {
        val jwt = jwt(5)
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"$jwt\"}"))
        val folder = InMemoryBuildFolder(mapOf("index.html" to "<p>hi</p>", "app.js" to "go()", "copy.js" to "go()", "_headers" to "/*"))
        val indexHash = CloudflarePagesAssetHasher.assetHash("<p>hi</p>".toByteArray(), "html")
        val jsHash = CloudflarePagesAssetHasher.assetHash("go()".toByteArray(), "js")
        transport.enqueueJson(POST, "/pages/assets/check-missing", envelope("[\"$indexHash\"]"))
        transport.enqueueJson(POST, "/pages/assets/upload", envelope("null"))
        transport.enqueueJson(POST, "/pages/assets/upsert-hashes", envelope("true"))
        transport.enqueueJson(POST, "$project/deployments", envelope(deployment("new-deployment")))
        val progress = mutableListOf<CloudflarePagesDirectUploadProgress>()

        val result = api.directUpload(
            "acc",
            "site",
            folder,
            CloudflarePagesDirectUploadOptions(branch = "main", commitMessage = "Ship it"),
            CloudflareMutationConfirmation("$project/deployments"),
        ) { progress += it }

        assertEquals("new-deployment", result.deployment.id)
        assertEquals(3, result.assetCount)
        assertEquals(1, result.uploadedAssetCount)
        assertEquals(1, result.reusedAssetCount)
        assertEquals(
            listOf(
                "GET $project/upload-token",
                "POST /pages/assets/check-missing",
                "POST /pages/assets/upload",
                "POST /pages/assets/upsert-hashes",
                "POST $project/deployments",
            ),
            transport.requests.map { it.toString() },
        )
        val (token, checkMissing, upload, upsert, deploy) = transport.requests
        assertEquals(FakeCloudflareRestTransport.TOKEN, token.bearer)
        listOf(checkMissing, upload, upsert).forEach { assertEquals(jwt, it.bearer) }
        assertEquals(FakeCloudflareRestTransport.TOKEN, deploy.bearer)

        val hashes = checkMissing.bodyJson!!["hashes"]!!.arrayValue!!.map { it.stringValue }
        assertEquals(listOf(jsHash, indexHash), hashes)
        val uploaded = upload.bodyJson!!.arrayValue!!.single()
        assertEquals(indexHash, uploaded.str("key"))
        assertEquals("<p>hi</p>", String(Base64.getDecoder().decode(uploaded.str("value"))))
        assertEquals("text/html", uploaded["metadata"].str("contentType"))
        assertEquals(true, (uploaded["base64"] as ProviderJsonValue.Bool).value)

        assertEquals("multipart/form-data; boundary=test-boundary", deploy.request.contentType)
        val multipart = String(deploy.request.bodyCopy()!!, StandardCharsets.UTF_8)
        assertTrue(multipart.contains("{\"/app.js\":\"$jsHash\",\"/copy.js\":\"$jsHash\",\"/index.html\":\"$indexHash\"}"))
        assertTrue(multipart.contains("name=\"branch\"\r\n\r\nmain\r\n"))
        assertTrue(multipart.contains("name=\"commit_message\"\r\n\r\nShip it\r\n"))
        assertTrue(multipart.contains("name=\"commit_dirty\"\r\n\r\nfalse\r\n"))
        assertTrue(multipart.contains("name=\"_headers\"; filename=\"_headers\""))
        assertEquals(
            listOf(
                CloudflarePagesDirectUploadProgress.Stage.AUTHORIZING,
                CloudflarePagesDirectUploadProgress.Stage.HASHING,
                CloudflarePagesDirectUploadProgress.Stage.CHECKING,
                CloudflarePagesDirectUploadProgress.Stage.UPLOADING,
                CloudflarePagesDirectUploadProgress.Stage.DEPLOYING,
            ),
            progress.map { it.stage }.distinct(),
        )
    }

    @Test
    fun directUploadRequiresTheDeploymentsConfirmationBeforeAnyRequest() = runTest {
        val folder = InMemoryBuildFolder(mapOf("index.html" to "x"))
        expectConfirmationRequired {
            api.directUpload("acc", "site", folder, CloudflarePagesDirectUploadOptions(), CloudflareMutationConfirmation(project))
        }
        assertTrue(transport.requests.isEmpty())
        assertTrue(folder.reads.isEmpty())
    }

    @Test
    fun assetRequestsRefreshExpiredJwtsRetryServerErrorsAndTolerateUpsertFailure() = runTest {
        val first = jwt(10)
        val second = jwt(11)
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"$first\"}"))
        transport.enqueueJson(POST, "/pages/assets/check-missing", failure("expired"), status = 401)
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"$second\"}"))
        transport.enqueueJson(POST, "/pages/assets/check-missing", failure("busy"), status = 503)
        val hash = CloudflarePagesAssetHasher.assetHash("x".toByteArray(), "html")
        transport.enqueueJson(POST, "/pages/assets/check-missing", envelope("[\"$hash\"]"))
        transport.enqueueJson(POST, "/pages/assets/upload", "", status = 200)
        transport.alwaysJson(POST, "/pages/assets/upsert-hashes", failure("down"), status = 500)
        transport.enqueueJson(POST, "$project/deployments", envelope(deployment("dep")))

        api.directUpload(
            "acc",
            "site",
            InMemoryBuildFolder(mapOf("index.html" to "x")),
            CloudflarePagesDirectUploadOptions(),
            CloudflareMutationConfirmation("$project/deployments"),
        )

        val checks = transport.requests.filter { it.path == "/pages/assets/check-missing" }
        assertEquals(listOf(first, second, second), checks.map { it.bearer })
        assertEquals(2, transport.requests.count { it.path == "/pages/assets/upsert-hashes" })
        assertEquals("POST $project/deployments", transport.requests.last().toString())
        assertEquals(listOf(1_000L, 2_000L, 1_000L), sleeps)
    }

    @Test
    fun nonRetryableAssetFailuresStopTheUpload() = runTest {
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"${jwt(1)}\"}"))
        transport.enqueueJson(POST, "/pages/assets/check-missing", failure("Bad hashes", 8000), status = 400)
        val error = runCatching {
            api.directUpload(
                "acc",
                "site",
                InMemoryBuildFolder(mapOf("index.html" to "x")),
                CloudflarePagesDirectUploadOptions(),
                CloudflareMutationConfirmation("$project/deployments"),
            )
        }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Cloudflare request failed (400): Bad hashes", error.userMessage)
        assertFalse(transport.requests.any { it.path == "$project/deployments" })
    }

    @Test
    fun uploadTokenMustBeAUsableJwt() = runTest {
        transport.enqueueJson(GET, "$project/upload-token", envelope("{\"jwt\":\"  \"}"))
        val error = runCatching {
            api.directUpload(
                "acc",
                "site",
                InMemoryBuildFolder(mapOf("index.html" to "x")),
                CloudflarePagesDirectUploadOptions(),
                CloudflareMutationConfirmation("$project/deployments"),
            )
        }.exceptionOrNull() as CloudflareOperationException
        assertEquals(CloudflareOperationException.Kind.DECODING, error.kind)
    }

    private suspend fun expectConfirmationRequired(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(CloudflareOperationException.Kind.CONFIRMATION_REQUIRED, (error as CloudflareOperationException).kind)
    }

    private fun jwt(maximum: Int): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"max_file_count_allowed\":$maximum}".toByteArray())
        return "header.$payload.sig"
    }

    private fun domain(name: String) =
        "{\"id\":\"id-$name\",\"name\":\"$name\",\"status\":\"pending\",\"validation_data\":{\"method\":\"http\",\"status\":\"pending\"}}"

    private fun deployment(id: String) =
        "{\"id\":\"$id\",\"short_id\":\"${id.take(6)}\",\"environment\":\"production\",\"url\":\"https://$id.site.pages.dev\"," +
            "\"latest_stage\":{\"name\":\"deploy\",\"status\":\"success\"},\"deployment_trigger\":{\"type\":\"ad_hoc\",\"metadata\":{\"branch\":\"main\"}}," +
            "\"env_vars\":{\"API_KEY\":{\"type\":\"secret_text\",\"value\":\"super-secret\"}}}"

    companion object {
        private val PROJECT_JSON = """
            {"id":"p1","name":"site","subdomain":"site.pages.dev","domains":["site.example"],"production_branch":"main",
             "created_on":"2026-01-02T03:04:05Z","framework":"astro","framework_version":"4",
             "latest_deployment":{"id":"abc123def","short_id":"abc123","environment":"production","latest_stage":{"status":"success"}},
             "build_config":{"build_command":"npm run build","destination_dir":"dist","root_dir":"","build_caching":true,
                             "web_analytics_tag":"tag","web_analytics_token":"super-secret"},
             "source":{"type":"github","config":{"owner":"octo","repo_name":"site","production_branch":"main",
                       "production_deployments_enabled":true,"preview_deployment_setting":"all","pr_comments_enabled":true}},
             "deployment_configs":{"production":{"compatibility_date":"2024-01-01","limits":{"cpu_ms":30},"placement":{"mode":"smart"},
                 "env_vars":{"API_KEY":{"type":"secret_text","value":"super-secret"},"PUBLIC_URL":{"type":"plain_text","value":null}},
                 "kv_namespaces":{"CACHE":{"namespace_id":"ns"}},"d1_databases":{"DB":{"id":"db-id"}}},
               "preview":null}}
        """.trimIndent()
    }
}
