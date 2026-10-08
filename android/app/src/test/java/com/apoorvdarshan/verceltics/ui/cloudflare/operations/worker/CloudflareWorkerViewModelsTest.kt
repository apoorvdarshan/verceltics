package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailConnection
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailConnector
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareTailEndpoint
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerSecretText
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareWorkerViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val transport = FakeCloudflareRestTransport()
    private val client = FakeCloudflareRestTransport.client(transport)
    private val api = CloudflareWorkerOperationsApi(client)
    private val cache = CloudflareWorkerMemoryCache()
    private val script = "/accounts/acc/workers/scripts/edge-api"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun scriptDetailResponses() {
        transport.alwaysJson(
            CloudflareHttpMethod.GET,
            "/accounts/acc/workers/scripts",
            envelope("""[{"id":"edge-api","has_modules":true,"routes":[{"pattern":"api.example.com/*"}]}]"""),
        )
        transport.alwaysJson(
            CloudflareHttpMethod.GET,
            "$script/deployments",
            envelope("""{"deployments":[{"id":"dep-1","source":"wrangler"},{"id":"dep-2","strategy":"percentage"}]}"""),
        )
    }

    @Test
    fun detailLoadsWorkerAndDeploymentsThenCachesThem() = runTest(dispatcher) {
        scriptDetailResponses()
        val model = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", client.mutations, cache)
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(true, state.worker?.hasModules)
        assertEquals(listOf("dep-1", "dep-2"), state.deployments.map { it.id })
        assertFalse(state.isLoading)

        val requestsBefore = transport.requests.size
        val second = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", null, cache)
        assertFalse(second.state.value.isLoading)
        advanceUntilIdle()
        assertEquals("A fresh cache needs no network", requestsBefore, transport.requests.size)
    }

    @Test
    fun deletingTheWorkerSendsNothingUntilConfirmed() = runTest(dispatcher) {
        scriptDetailResponses()
        transport.enqueueJson(CloudflareHttpMethod.DELETE, script, envelope("null"))
        val model = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", client.mutations, cache)
        advanceUntilIdle()

        model.requestDeleteWorker()
        val prompt = model.confirmation.value!!
        assertEquals("Delete this Worker?", prompt.title)
        assertTrue(prompt.message.startsWith("This permanently removes the Worker script and can immediately interrupt routed traffic."))
        assertEquals("Delete Worker", prompt.confirmLabel)
        assertTrue(prompt.destructive)
        assertEquals("edge-api", prompt.resourceId)

        model.dismissPendingMutation()
        advanceUntilIdle()
        assertTrue(transport.mutations().isEmpty())

        model.requestDeleteWorker()
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("DELETE $script"), transport.mutations().map(Any::toString))
        assertTrue(model.state.value.didDeleteWorker)
        assertEquals(CloudflareActionBanner("Worker deleted.", isError = false), model.banner.value)
        assertNull(cache.get<Any>(CloudflareWorkerMemoryCache.workerKey("acc", "edge-api")))
    }

    @Test
    fun deletingADeploymentRemovesItAndFailuresBecomeBanners() = runTest(dispatcher) {
        scriptDetailResponses()
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/deployments/dep-1", envelope("null"))
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/deployments/dep-2", failure("Active deployment"), status = 400)
        val model = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", null, cache)
        advanceUntilIdle()
        val (first, second) = model.state.value.deployments

        model.requestDeleteDeployment(first)
        assertEquals("Delete this deployment?", model.confirmation.value?.title)
        assertEquals("dep-1", model.confirmation.value?.resourceId)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("dep-2"), model.state.value.deployments.map { it.id })
        assertEquals("Worker deployment deleted.", model.banner.value?.message)

        model.requestDeleteDeployment(second)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(CloudflareActionBanner("Cloudflare request failed (400): Active deployment", isError = true), model.banner.value)
        assertEquals(listOf("dep-2"), model.state.value.deployments.map { it.id })
    }

    @Test
    fun mutationsFromWorkerOperationsRefreshTheDetail() = runTest(dispatcher) {
        operationsResponses()
        transport.alwaysJson(CloudflareHttpMethod.POST, "$script/deployments", envelope("""{"id":"dep-3"}"""))
        val detail = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", client.mutations, cache)
        advanceUntilIdle()
        val listCalls = transport.requests.count { it.toString() == "GET $script/deployments" }

        val operations = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()
        operations.requestDeploy(operations.state.value.versions.first())
        operations.confirmPendingMutation()
        advanceUntilIdle()

        assertTrue(transport.requests.count { it.toString() == "GET $script/deployments" } > listCalls)
        assertEquals("Version deployed to 100% of traffic.", operations.banner.value?.message)
        assertFalse(detail.state.value.didDeleteWorker)
    }

    private fun operationsResponses() {
        scriptDetailResponses()
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/versions", envelope("""{"items":[{"id":"v1","number":1},{"id":"v2","number":2}]}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/secrets", envelope("""[{"name":"TOKEN","type":"secret_text"}]"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/schedules", envelope("""{"schedules":[{"cron":"0 0 * * *"}]}"""))
        transport.alwaysJson(
            CloudflareHttpMethod.GET,
            "/accounts/acc/workers/domains",
            envelope("""[{"id":"d1","hostname":"api.example.com","service":"edge-api"},{"id":"d2","hostname":"x.example.com","service":"other"}]"""),
        )
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/settings", envelope("""{"usage_model":"standard"}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/script-settings", envelope("""{"observability":{"enabled":true,"logs":{"enabled":true,"invocation_logs":true}}}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc/workers/subdomain", envelope("""{"subdomain":"team"}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/subdomain", envelope("""{"enabled":true,"previews_enabled":false}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "$script/tails", "{}", status = 403)
    }

    @Test
    fun operationsLoadEverySectionAndReportUnavailableOnes() = runTest(dispatcher) {
        operationsResponses()
        val model = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()
        val state = model.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf("v1", "v2"), state.versions.map { it.id })
        assertEquals(listOf("TOKEN"), state.secrets.map { it.name })
        assertEquals(listOf("0 0 * * *"), state.schedules.map { it.cron })
        assertEquals(listOf("api.example.com"), state.domains.map { it.hostname })
        assertEquals("standard", state.settings?.usageModel)
        assertEquals(true, state.scriptLevelSettings?.observability?.enabled)
        assertEquals("edge-api.team.workers.dev", model.workerDevHostname)
        assertEquals(listOf("Live tails: This Cloudflare user cannot access that resource."), state.warnings)
        assertTrue(transport.mutations().isEmpty())
    }

    @Test
    fun deployIsAFullTrafficDeploymentBehindConfirmation() = runTest(dispatcher) {
        operationsResponses()
        transport.alwaysJson(CloudflareHttpMethod.POST, "$script/deployments", envelope("""{"id":"dep-9"}"""))
        val model = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()

        model.requestDeploy(model.state.value.versions[0])
        val prompt = model.confirmation.value!!
        assertEquals("Deploy this version?", prompt.title)
        assertEquals("Deploy to 100%", prompt.confirmLabel)
        assertTrue(prompt.message.contains("Version 1 (v1) will serve 100% of requests to edge-api."))
        assertFalse(prompt.destructive)
        assertTrue(transport.mutations().isEmpty())

        model.confirmPendingMutation()
        advanceUntilIdle()
        val deploy = transport.mutations().single()
        assertEquals("POST $script/deployments", deploy.toString())
        assertEquals("Deployed from Verceltics", deploy.bodyJson.obj("annotations").str("workers/message"))
    }

    @Test
    fun secretValuesNeverAppearInPromptsAndDismissSendsNothing() = runTest(dispatcher) {
        operationsResponses()
        transport.enqueueJson(CloudflareHttpMethod.PUT, "$script/secrets", envelope("null"))
        val model = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()

        model.requestSaveSecret("TOKEN", CloudflareWorkerSecretText.of("hunter2"))
        val prompt = model.confirmation.value!!
        assertEquals("Save secret TOKEN?", prompt.title)
        assertTrue(prompt.message.startsWith("The existing value of TOKEN will be replaced on edge-api."))
        assertFalse(prompt.toString().contains("hunter2"))
        model.dismissPendingMutation()
        advanceUntilIdle()
        assertTrue(transport.mutations().isEmpty())

        val secret = CloudflareWorkerSecretText.of("hunter2")
        model.requestSaveSecret(" NEW_KEY ", secret)
        assertEquals("NEW_KEY", model.confirmation.value?.resourceId)
        model.confirmPendingMutation()
        advanceUntilIdle()
        val put = transport.mutations().single()
        assertEquals("hunter2", put.bodyJson.str("text"))
        assertEquals("NEW_KEY", put.bodyJson.str("name"))
        assertTrue(secret.isEmpty)
        assertEquals("Secret saved. Its value is not stored or displayed.", model.banner.value?.message)

        model.requestSaveSecret("bad name", CloudflareWorkerSecretText.of("x"))
        assertNull(model.confirmation.value)
        assertTrue(model.banner.value!!.isError)
    }

    @Test
    fun cronDomainAndDeleteActionsStateExactlyWhatChanges() = runTest(dispatcher) {
        operationsResponses()
        transport.alwaysJson(CloudflareHttpMethod.PUT, "$script/schedules", envelope("""{"schedules":[{"cron":"*/30 * * * *"},{"cron":"0 0 * * *"}]}"""))
        transport.enqueueJson(CloudflareHttpMethod.PUT, "/accounts/acc/workers/domains", envelope("""{"id":"d3","hostname":"new.example.com","service":"edge-api"}"""))
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/accounts/acc/workers/domains/d1", envelope("null"))
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "$script/secrets/TOKEN", envelope("null"))
        val model = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()

        model.requestAddSchedule("every hour")
        assertNull(model.confirmation.value)
        assertEquals("Cron expressions need five fields, such as */30 * * * *.", model.banner.value?.message)

        model.requestAddSchedule("*/30 * * * *")
        assertEquals("Add cron trigger?", model.confirmation.value?.title)
        assertTrue(model.confirmation.value!!.message.endsWith("Cron triggers after saving: */30 * * * *, 0 0 * * *."))
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(listOf("*/30 * * * *", "0 0 * * *"), model.state.value.schedules.map { it.cron })

        model.requestDeleteSchedule(model.state.value.schedules[1])
        assertEquals("Delete cron 0 0 * * *?", model.confirmation.value?.title)
        assertTrue(model.confirmation.value!!.destructive)
        model.confirmPendingMutation()
        advanceUntilIdle()
        val scheduleBody = transport.mutations().last().bodyJson as com.apoorvdarshan.verceltics.data.network.ProviderJsonValue.Arr
        assertEquals(listOf("*/30 * * * *"), scheduleBody.items.map { it.str("cron") })

        model.requestAttachDomain(" New.Example.com ")
        assertEquals("Attach new.example.com?", model.confirmation.value?.title)
        assertEquals("new.example.com", model.confirmation.value?.resourceId)
        model.confirmPendingMutation()
        advanceUntilIdle()

        model.requestDetachDomain(model.state.value.domains.first { it.id == "d1" })
        assertEquals("Detach api.example.com?", model.confirmation.value?.title)
        assertTrue(model.confirmation.value!!.destructive)
        model.confirmPendingMutation()
        advanceUntilIdle()

        model.requestDeleteSecret(model.state.value.secrets.single())
        assertEquals("Delete secret TOKEN?", model.confirmation.value?.title)
        model.confirmPendingMutation()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "PUT $script/schedules",
                "PUT $script/schedules",
                "PUT /accounts/acc/workers/domains",
                "DELETE /accounts/acc/workers/domains/d1",
                "DELETE $script/secrets/TOKEN",
            ),
            transport.mutations().map(Any::toString),
        )
    }

    @Test
    fun observabilityAndSubdomainPromptsSummarizeTheNewSettings() = runTest(dispatcher) {
        operationsResponses()
        transport.enqueueJson(CloudflareHttpMethod.PATCH, "$script/script-settings", envelope("""{"observability":{"enabled":false}}"""))
        transport.enqueueJson(CloudflareHttpMethod.POST, "$script/subdomain", envelope("""{"enabled":false,"previews_enabled":true}"""))
        val model = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", cache)
        advanceUntilIdle()

        model.requestUpdateObservability(enabled = false, logsEnabled = true, tracesEnabled = true)
        val observability = model.confirmation.value!!
        assertEquals("For edge-api: event collection Off, invocation logs Off, traces Off.", observability.message)
        assertTrue(observability.destructive)
        model.confirmPendingMutation()
        advanceUntilIdle()
        val patch = transport.mutations().single().bodyJson.obj("observability")!!
        assertEquals(false, patch.obj("logs").bool("enabled"))

        model.requestUpdateSubdomain(enabled = false, previewsEnabled = true)
        val subdomain = model.confirmation.value!!
        assertEquals(
            "For edge-api: production URL (edge-api.team.workers.dev) Off, version preview URLs On.",
            subdomain.message,
        )
        assertTrue(subdomain.destructive)
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals(false, model.state.value.subdomain?.enabled)
        assertEquals("workers.dev settings updated.", model.banner.value?.message)
    }

    @Test
    fun liveTailStartsOnlyAfterConfirmationAndCleansUp() = runTest(dispatcher) {
        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "$script/tails",
            envelope("""{"id":"t1","expires_at":"2026-01-01T00:00:00Z","url":"wss://tail.developers.workers.dev/t1"}"""),
        )
        transport.enqueue(CloudflareHttpMethod.DELETE, "$script/tails/t1", FakeCloudflareRestTransport.raw(ByteArray(0)))
        val connection = FakeTailConnection("{\"outcome\":\"ok\",\"logs\":[]}", "plain line")
        val connector = FakeTailConnector(connection)
        val model = liveTail(connector)

        model.requestStart()
        assertEquals("Start live tail?", model.confirmation.value?.title)
        assertEquals("edge-api", model.confirmation.value?.resourceId)
        advanceUntilIdle()
        assertTrue(transport.requests.isEmpty())
        assertNull(connector.endpoint)

        model.confirmPendingMutation()
        advanceUntilIdle()
        assertEquals("tail.developers.workers.dev", connector.endpoint?.host)
        val state = model.state.value
        assertEquals(listOf("{\n  \"logs\" : [],\n  \"outcome\" : \"ok\"\n}", "plain line"), state.lines.map { it.text })
        assertEquals("Live tail disconnected", state.status)

        model.stop()
        advanceUntilIdle()
        assertTrue(connection.closed)
        assertEquals("DELETE $script/tails/t1", transport.requests.last().toString())
        assertEquals("Tail stopped", model.state.value.status)
    }

    @Test
    fun unexpectedTailUrlsAreRejectedAndTheSessionDeleted() = runTest(dispatcher) {
        transport.enqueueJson(
            CloudflareHttpMethod.POST,
            "$script/tails",
            envelope("""{"id":"t2","expires_at":"2026-01-01T00:00:00Z","url":"wss://evil.example/t2"}"""),
        )
        transport.enqueue(CloudflareHttpMethod.DELETE, "$script/tails/t2", FakeCloudflareRestTransport.raw(ByteArray(0)))
        val connector = FakeTailConnector(FakeTailConnection())
        val model = liveTail(connector)
        model.requestStart()
        model.confirmPendingMutation()
        advanceUntilIdle()
        assertNull(connector.endpoint)
        assertEquals("Cloudflare returned an invalid live-tail URL.", model.state.value.error)
        assertEquals("Tail unavailable", model.state.value.status)
        assertEquals("DELETE $script/tails/t2", transport.requests.last().toString())
    }

    @Test
    fun liveTailKeepsOnlyTheNewest500Events() = runTest(dispatcher) {
        val model = liveTail(FakeTailConnector(FakeTailConnection()))
        repeat(510) { model.append("event $it") }
        val lines = model.state.value.lines
        assertEquals(500, lines.size)
        assertEquals("event 10", lines.first().text)
        assertEquals("event 509", lines.last().text)
    }

    private fun TestScope.liveTail(connector: CloudflareTailConnector) = CloudflareWorkerLiveTailViewModel(
        api = api,
        accountId = "acc",
        scriptName = "edge-api",
        connector = connector,
        ioDispatcher = dispatcher,
        cleanupScope = this,
        now = { Instant.EPOCH },
    )

    private class FakeTailConnection(vararg messages: String) : CloudflareTailConnection {
        private val queue = ArrayDeque(messages.toList())
        var closed = false

        override fun receive(): String? = queue.pollFirst()

        override fun close() {
            closed = true
        }
    }

    private class FakeTailConnector(private val connection: CloudflareTailConnection) : CloudflareTailConnector {
        var endpoint: CloudflareTailEndpoint? = null

        override fun connect(endpoint: CloudflareTailEndpoint): CloudflareTailConnection {
            this.endpoint = endpoint
            return connection
        }
    }
}
