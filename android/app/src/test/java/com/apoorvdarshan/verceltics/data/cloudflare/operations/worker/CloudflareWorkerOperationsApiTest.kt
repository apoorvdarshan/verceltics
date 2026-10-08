package com.apoorvdarshan.verceltics.data.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.double
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareWorkerOperationsApiTest {
    private val transport = FakeCloudflareRestTransport()
    private val api = CloudflareWorkerOperationsApi(FakeCloudflareRestTransport.client(transport))
    private val script = "/accounts/acc/workers/scripts/edge-api"

    @Test
    fun scriptListParsesEveryIosFieldAndFindsTheWorker() = runTest {
        transport.alwaysJson(
            CloudflareHttpMethod.GET,
            "/accounts/acc/workers/scripts",
            envelope(
                """[{"id":"edge-api","created_on":"2026-01-02T03:04:05Z","compatibility_date":"2026-01-01",
                "compatibility_flags":["nodejs_compat"],"handlers":["fetch","scheduled"],"has_modules":true,
                "has_assets":false,"usage_model":"standard","etag":"abc","logpush":true,"tags":["a"],
                "routes":[{"pattern":"api.example.com/*","script":"edge-api"},{"id":"r2","pattern":"x.example.com"}],
                "named_handlers":[{}],"tail_consumers":[{},{}],"placement_mode":"smart"},
                {"created_on":"2026-01-01"}]""",
            ),
        )
        val scripts = api.fetchWorkerScripts("acc")
        assertEquals(listOf("edge-api", "unknown-worker"), scripts.map { it.id })
        val worker = api.fetchWorkerScript("acc", "edge-api")
        assertEquals(listOf("nodejs_compat"), worker.compatibilityFlags)
        assertEquals(listOf("api.example.com/*", "r2"), worker.routes.map { it.id })
        assertEquals(1, worker.namedHandlerCount)
        assertEquals(2, worker.tailConsumerCount)
        assertEquals(true, worker.logpush)
        assertEquals("smart", worker.placementMode)
        assertEquals(1767323045L, worker.createdDate?.epochSecond)

        val missing = runCatching { api.fetchWorkerScript("acc", "gone") }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Cloudflare request failed (404): Cloudflare did not return Worker gone.", missing.userMessage)
    }

    @Test
    fun deploymentsParseAnnotationsAndVersions() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "$script/deployments",
            envelope(
                """{"deployments":[{"id":"dep-1","source":"wrangler","strategy":"percentage","author_email":"a@b.c",
                "versions":[{"version_id":"v1","percentage":100}],
                "annotations":{"workers/message":"Ship it","workers/triggered_by":"upload"}},{"source":"no-id"}]}""",
            ),
        )
        val deployments = api.fetchDeployments("acc", "edge-api")
        assertEquals(1, deployments.size)
        val deployment = deployments.single()
        assertEquals("Ship it", deployment.message)
        assertEquals("upload", deployment.triggeredBy)
        assertEquals(listOf(CloudflareWorkerDeploymentVersion("v1", 100.0)), deployment.versions)
    }

    @Test
    fun deleteWorkerAndDeploymentRequireTheExactResource() = runTest {
        expectConfirmationRequired { api.deleteWorker("acc", "edge-api", CloudflareMutationConfirmation("other")) }
        expectConfirmationRequired { api.deleteDeployment("acc", "edge-api", "dep-1", CloudflareMutationConfirmation("edge-api")) }
        assertTrue(transport.requests.isEmpty())

        transport.enqueueJson(CloudflareHttpMethod.DELETE, script, envelope("null"))
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/deployments/dep-1", envelope("null"))
        api.deleteWorker("acc", "edge-api", CloudflareMutationConfirmation("edge-api"))
        api.deleteDeployment("acc", "edge-api", "dep-1", CloudflareMutationConfirmation("dep-1"))
        assertEquals(listOf("DELETE $script", "DELETE $script/deployments/dep-1"), transport.requests.map(Any::toString))
        assertNull(transport.requests[0].bodyText)
    }

    @Test
    fun versionsUseDeployableFilterAndVersionDetailParsesBindings() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "$script/versions",
            envelope("""{"items":[{"id":"v2","number":2,"metadata":{"source":"api","author_email":"a@b.c","created_on":"2026-02-01T00:00:00Z"}},{"number":9}]}"""),
        )
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "$script/versions/v2",
            envelope("""{"id":"v2","number":2,"startup_time_ms":12.5,"resources":{"bindings":[{"name":"DB","type":"d1_database"},"bad"]}}"""),
        )
        val versions = api.fetchVersions("acc", "edge-api")
        assertEquals(listOf("deployable" to "true"), transport.requests.single().query)
        assertEquals(listOf("Version 2"), versions.map { it.displayTitle })
        assertEquals("api", versions.single().metadata?.source)

        val detail = api.fetchVersion("acc", "edge-api", "v2")
        assertEquals(12.5, detail.startupTimeMilliseconds)
        assertEquals(
            listOf(CloudflareWorkerBindingSummary("DB", "D1 Database"), CloudflareWorkerBindingSummary("Binding", "Unknown type")),
            detail.bindings,
        )
    }

    @Test
    fun deployVersionSendsAFullTrafficPercentageDeployment() = runTest {
        expectConfirmationRequired { api.deployVersion("acc", "edge-api", "v2", "x", CloudflareMutationConfirmation("edge-api")) }
        transport.alwaysJson(CloudflareHttpMethod.POST, "$script/deployments", envelope("""{"id":"dep-9"}"""))

        val deployment = api.deployVersion("acc", "edge-api", "v2", "  Deployed from Verceltics  ", CloudflareMutationConfirmation("v2"))
        assertEquals("dep-9", deployment.id)
        val body = transport.requests.single().bodyJson!!
        assertEquals("percentage", body.str("strategy"))
        val version = body.arr("versions").single()
        assertEquals("v2", version.str("version_id"))
        assertEquals(100.0, version.double("percentage"))
        assertEquals("Deployed from Verceltics", body.obj("annotations").str("workers/message"))
        assertEquals("verceltics", body.obj("annotations").str("workers/triggered_by"))

        api.deployVersion("acc", "edge-api", "v2", "   ", CloudflareMutationConfirmation("v2"))
        val annotations = transport.requests.last().bodyJson.obj("annotations")!!
        assertEquals(setOf("workers/triggered_by"), annotations.fields.keys)
    }

    @Test
    fun secretsAreSentOnceAndWipedAndNeverPrinted() = runTest {
        val secret = CloudflareWorkerSecretText.of("s3cr3t-value\nline two")
        assertFalse(secret.toString().contains("s3cr3t"))
        expectConfirmationRequired { api.putSecret("acc", "edge-api", "API_KEY", secret, CloudflareMutationConfirmation("OTHER")) }
        assertFalse(secret.isEmpty)
        assertTrue(transport.requests.isEmpty())

        transport.enqueue(CloudflareHttpMethod.PUT, "$script/secrets", FakeCloudflareRestTransport.raw(ByteArray(0)))
        api.putSecret("acc", "edge-api", "  API_KEY ", secret, CloudflareMutationConfirmation("API_KEY"))
        val body = transport.requests.single().bodyJson!!
        assertEquals("API_KEY", body.str("name"))
        assertEquals("s3cr3t-value\nline two", body.str("text"))
        assertEquals("secret_text", body.str("type"))
        assertTrue(secret.isEmpty)
        assertFalse(transport.requests.single().request.toString().contains("s3cr3t"))

        val empty = CloudflareWorkerSecretText.of("")
        val error = runCatching {
            api.putSecret("acc", "edge-api", "API_KEY", empty, CloudflareMutationConfirmation("API_KEY"))
        }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Enter a secret value.", error.userMessage)
        val badName = runCatching {
            api.putSecret("acc", "edge-api", "has space", CloudflareWorkerSecretText.of("x"), CloudflareMutationConfirmation("has space"))
        }.exceptionOrNull() as CloudflareOperationException
        assertEquals(CloudflareOperationException.Kind.INVALID_REQUEST, badName.kind)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun deleteSecretEncodesTheNameAndSurfacesUnsuccessfulEnvelopes() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/secrets/MY%20KEY%2F1", envelope("null"))
        api.deleteSecret("acc", "edge-api", "MY KEY/1", CloudflareMutationConfirmation("MY KEY/1"))

        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/secrets/X", failure("Secret is in use"))
        val error = runCatching { api.deleteSecret("acc", "edge-api", "X", CloudflareMutationConfirmation("X")) }
            .exceptionOrNull() as CloudflareOperationException
        assertEquals("Secret is in use [code 1000]", error.userMessage)
    }

    @Test
    fun schedulesReplaceTheWholeListWithValidatedExpressions() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.PUT, "$script/schedules", envelope("""{"schedules":[{"cron":"*/30 * * * *"},{"cron":"0 0 * * *"}]}"""))
        val existing = listOf(CloudflareWorkerScheduleInfo("0 0 * * *"), CloudflareWorkerScheduleInfo("*/30 * * * *"))
        val values = CloudflareWorkerOperationsApi.schedulesAdding(existing, " 0 0 * * * ")
        assertEquals(listOf("*/30 * * * *", "0 0 * * *"), values)
        assertEquals(listOf("*/30 * * * *"), CloudflareWorkerOperationsApi.schedulesRemoving(existing, "0 0 * * *"))

        val saved = api.updateSchedules("acc", "edge-api", values, CloudflareMutationConfirmation("edge-api"))
        assertEquals(2, saved.size)
        val body = transport.requests.single().bodyJson as ProviderJsonValue.Arr
        assertEquals(values, body.items.map { it.str("cron") })

        assertFalse(CloudflareWorkerOperationsApi.isValidCronExpression("* * * *"))
        assertTrue(CloudflareWorkerOperationsApi.isValidCronExpression("  */5   * * * * "))
        val invalid = runCatching {
            api.updateSchedules("acc", "edge-api", listOf("every day"), CloudflareMutationConfirmation("edge-api"))
        }.exceptionOrNull() as CloudflareOperationException
        assertEquals(CloudflareOperationException.Kind.INVALID_REQUEST, invalid.kind)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun domainsPageByTotalCountAndFilterByService() = runTest {
        val first = (1..50).joinToString(",") { """{"id":"d$it","hostname":"h$it.example.com","service":"${if (it == 1) "EDGE-API" else "other"}"}""" }
        transport.enqueueJson(CloudflareHttpMethod.GET, "/accounts/acc/workers/domains", envelope("[$first]", """{"total_count":51}"""))
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/accounts/acc/workers/domains",
            envelope("""[{"id":"d51","hostname":"api.example.com","service":"edge-api","zone_name":"example.com","cert_id":"c"},{"id":"broken"}]""", """{"total_count":51}"""),
        )
        val domains = api.fetchDomains("acc", "edge-api")
        assertEquals(listOf("h1.example.com", "api.example.com"), domains.map { it.hostname })
        assertEquals(listOf("1", "2"), transport.requests.map { request -> request.query.first { it.first == "page" }.second })
        assertEquals("example.com", domains.last().zoneName)
    }

    @Test
    fun attachAndDetachDomainsUseNormalizedHostnamesAndRecordIds() = runTest {
        assertFalse(CloudflareWorkerOperationsApi.isValidHostname("localhost"))
        assertFalse(CloudflareWorkerOperationsApi.isValidHostname("api example.com"))
        assertTrue(CloudflareWorkerOperationsApi.isValidHostname(" API.Example.com "))
        expectConfirmationRequired { api.attachDomain("acc", "API.example.com", "edge-api", CloudflareMutationConfirmation("API.example.com")) }

        transport.enqueueJson(CloudflareHttpMethod.PUT, "/accounts/acc/workers/domains", envelope("""{"id":"d1","hostname":"api.example.com","service":"edge-api"}"""))
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/accounts/acc/workers/domains/d1", envelope("null"))
        api.attachDomain("acc", " API.example.com ", "edge-api", CloudflareMutationConfirmation("api.example.com"))
        api.detachDomain("acc", "d1", CloudflareMutationConfirmation("d1"))

        val body = transport.requests.first().bodyJson!!
        assertEquals("api.example.com", body.str("hostname"))
        assertEquals("edge-api", body.str("service"))
        assertEquals("production", body.str("environment"))
        assertEquals("DELETE /accounts/acc/workers/domains/d1", transport.requests.last().toString())
    }

    @Test
    fun subdomainAndObservabilityUpdatesMatchIosBodies() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.POST, "$script/subdomain", envelope("""{"enabled":false,"previews_enabled":true}"""))
        transport.enqueueJson(
            CloudflareHttpMethod.PATCH,
            "$script/script-settings",
            envelope("""{"logpush":false,"observability":{"enabled":true,"logs":{"enabled":true,"invocation_logs":true},"traces":{"enabled":false}}}"""),
        )
        expectConfirmationRequired {
            api.updateWorkerSubdomain("acc", "edge-api", true, true, CloudflareMutationConfirmation("acc"))
        }
        val subdomain = api.updateWorkerSubdomain("acc", "edge-api", false, true, CloudflareMutationConfirmation("edge-api"))
        assertEquals(CloudflareWorkerSubdomainSettings(enabled = false, previewsEnabled = true), subdomain)
        val subdomainBody = transport.requests[0].bodyJson!!
        assertEquals(false, subdomainBody.bool("enabled"))
        assertEquals(true, subdomainBody.bool("previews_enabled"))

        val settings = api.updateObservability("acc", "edge-api", true, true, false, CloudflareMutationConfirmation("edge-api"))
        assertEquals(true, settings.observability?.logs?.invocationLogs)
        val observability = transport.requests[1].bodyJson.obj("observability")!!
        assertEquals(true, observability.bool("enabled"))
        assertEquals(true, observability.obj("logs").bool("enabled"))
        assertEquals(true, observability.obj("logs").bool("invocation_logs"))
        assertEquals(false, observability.obj("traces").bool("enabled"))
        assertEquals(CloudflareHttpMethod.PATCH, transport.requests[1].method)
    }

    @Test
    fun readSectionsParseSettingsSecretsSubdomainsAndTails() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "$script/settings",
            envelope("""{"annotations":{"workers/message":"m","n":1},"bindings":[{"name":"KV","type":"kv_namespace"}],"compatibility_date":"2026-01-01","usage_model":"standard","observability":{"enabled":true,"head_sampling_rate":0.5}}"""),
        )
        transport.enqueueJson(CloudflareHttpMethod.GET, "$script/secrets", envelope("""[{"name":"TOKEN"},{"type":"secret_text"}]"""))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/accounts/acc/workers/subdomain", envelope("""{"subdomain":"team"}"""))
        transport.enqueueJson(CloudflareHttpMethod.GET, "$script/tails", envelope("""[{"id":"t1","expires_at":"2026-01-01T00:00:00Z","url":"wss://tail.developers.workers.dev/t1"}]"""))

        val settings = api.fetchScriptSettings("acc", "edge-api")
        assertEquals(mapOf("workers/message" to "m"), settings.annotations)
        assertEquals(0.5, settings.observability?.headSamplingRate)
        assertEquals(1, settings.bindings.size)
        assertEquals(listOf(CloudflareWorkerSecretInfo("TOKEN", "secret_text", null, emptyList())), api.fetchSecrets("acc", "edge-api"))
        assertEquals("team", api.fetchAccountSubdomain("acc"))
        val tail = api.fetchTails("acc", "edge-api").single()
        assertFalse(tail.toString().contains("wss://"))
    }

    @Test
    fun tailsAreCreatedBehindConfirmationAndCleanedUp() = runTest {
        expectConfirmationRequired { api.createTail("acc", "edge-api", CloudflareMutationConfirmation("other")) }
        transport.enqueueJson(CloudflareHttpMethod.POST, "$script/tails", envelope("""{"id":"t1","expires_at":"2026-01-01T00:00:00Z","url":"wss://tail.developers.workers.dev/t1"}"""))
        transport.enqueue(CloudflareHttpMethod.DELETE, "$script/tails/t1", FakeCloudflareRestTransport.raw(ByteArray(0)))
        val tail = api.createTail("acc", "edge-api", CloudflareMutationConfirmation("edge-api"))
        assertEquals("{}", transport.requests.single().bodyText)
        api.deleteTail("acc", "edge-api", tail)
        assertEquals("DELETE $script/tails/t1", transport.requests.last().toString())
    }

    @Test
    fun contentIsPrettyPrintedTextOrSummarizedBinaryAndBoundedForDisplay() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "$script/content/v2", """{"b":1,"a":[true]}""")
        val json = api.fetchContent("acc", "edge-api")
        assertEquals("{\n  \"a\" : [\n    true\n  ],\n  \"b\" : 1\n}", json.text)
        assertEquals("*/*", transport.requests.single().request.accept)
        assertFalse(json.toString().contains("\"a\""))

        val script = CloudflareWorkerOperationsApi.formatContent("export default {}".toByteArray(), "application/javascript")
        assertEquals("export default {}", script.text)
        val binary = CloudflareWorkerOperationsApi.formatContent(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00), null)
        assertTrue(binary.text.startsWith("Binary or multipart Worker content (3 bytes)"))
        val huge = CloudflareWorkerOperationsApi.formatContent(ByteArray(600 * 1024) { 'a'.code.toByte() }, null)
        assertTrue(huge.truncatedForDisplay)
        assertEquals(CloudflareWorkerOperationsApi.MAXIMUM_DISPLAYED_CONTENT_CHARACTERS, huge.text.length)
    }

    @Test
    fun scriptNamesAreEncodedAsOnePathSegment() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/accounts/acc/workers/scripts/a%2F..%3Fb/schedules", envelope("""{"schedules":[]}"""))
        assertTrue(api.fetchSchedules("acc", "a/..?b").isEmpty())
    }

    private suspend fun expectConfirmationRequired(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertEquals(CloudflareOperationException.Kind.CONFIRMATION_REQUIRED, (error as CloudflareOperationException).kind)
    }
}
