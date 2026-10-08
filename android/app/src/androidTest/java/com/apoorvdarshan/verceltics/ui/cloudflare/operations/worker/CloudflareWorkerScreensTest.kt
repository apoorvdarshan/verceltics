package com.apoorvdarshan.verceltics.ui.cloudflare.operations.worker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareWorkerUi
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflareWorkerScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val transport = RecordingTransport()
    private val api = CloudflareWorkerOperationsApi(
        CloudflareRestClient(
            credentialProvider = { CloudflareCredential.apiToken("test-token") },
            executor = Executor { it.run() },
            transport = transport,
        ),
    )
    private val script = "/accounts/acc/workers/scripts/edge-api"
    private val fallback = CloudflareWorkerUi("edge-api", null, "2026-01-01", listOf("fetch"), false, true)

    private fun scriptResponses() {
        transport.respond(CloudflareHttpMethod.GET, "/accounts/acc/workers/scripts", envelope("""[{"id":"edge-api","has_modules":true}]"""))
        transport.respond(CloudflareHttpMethod.GET, "$script/deployments", envelope("""{"deployments":[{"id":"dep-1","source":"wrangler"}]}"""))
    }

    @Test
    fun deleteWorkerAsksWithExactDestructiveCopyAndCancelSendsNothing() {
        scriptResponses()
        transport.respond(CloudflareHttpMethod.DELETE, script, envelope("null"))
        var deleted = false
        val viewModel = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", null, CloudflareWorkerMemoryCache())
        compose.setContent {
            VercelticsTheme {
                CloudflareWorkerDetailScreen(viewModel, fallback, 0, onOpenOperations = {}, onWorkerDeleted = { deleted = true })
            }
        }

        compose.onNodeWithTag("cloudflare.workerDetail").performScrollToNode(hasTestTag("cloudflare.worker.delete"))
        compose.onNodeWithTag("cloudflare.worker.delete").performClick()
        compose.onNodeWithTag("cloudflare.confirmation").assertIsDisplayed()
        compose.onNodeWithText("Delete this Worker?").assertIsDisplayed()
        compose.onNodeWithText(
            "This permanently removes the Worker script and can immediately interrupt routed traffic.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()
        assertTrue(transport.mutations().isEmpty())

        compose.onNodeWithTag("cloudflare.worker.delete").performClick()
        compose.onNodeWithText("DELETE WORKER").performClick()
        compose.waitUntil(5_000) { deleted }
        assertEquals(listOf("DELETE $script"), transport.mutations())
    }

    @Test
    fun deleteDeploymentConfirmsBeforeSending() {
        scriptResponses()
        transport.respond(CloudflareHttpMethod.DELETE, "$script/deployments/dep-1", envelope("null"))
        val viewModel = CloudflareWorkerDetailViewModel(api, "acc", "edge-api", null, CloudflareWorkerMemoryCache())
        compose.setContent {
            VercelticsTheme { CloudflareWorkerDetailScreen(viewModel, fallback, 0, onOpenOperations = {}, onWorkerDeleted = {}) }
        }

        compose.onNodeWithTag("cloudflare.workerDetail").performScrollToNode(hasTestTag("cloudflare.worker.deployment.dep-1.delete"))
        compose.onNodeWithTag("cloudflare.worker.deployment.dep-1.delete").performClick()
        compose.onNodeWithText("Delete this deployment?").assertIsDisplayed()
        assertTrue(transport.mutations().isEmpty())
        compose.onNodeWithText("DELETE DEPLOYMENT").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        assertEquals(listOf("DELETE $script/deployments/dep-1"), transport.mutations())
    }

    @Test
    fun deployingAVersionRequiresConfirmation() {
        scriptResponses()
        transport.respond(CloudflareHttpMethod.GET, "$script/versions", envelope("""{"items":[{"id":"v1","number":1}]}"""))
        transport.respond(CloudflareHttpMethod.POST, "$script/deployments", envelope("""{"id":"dep-2"}"""))
        val viewModel = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", CloudflareWorkerMemoryCache())
        compose.setContent {
            VercelticsTheme {
                CloudflareWorkerOperationsScreen(viewModel, 0, onOpenSource = {}, onOpenLiveLogs = {}, onOpenVersion = {})
            }
        }

        compose.onNodeWithTag("cloudflare.workerOperations").performScrollToNode(hasTestTag("cloudflare.worker.version.v1.deploy"))
        compose.onNodeWithTag("cloudflare.worker.version.v1.deploy").performClick()
        compose.onNodeWithText("Deploy this version?").assertIsDisplayed()
        compose.onNodeWithText("The selected version will receive all production traffic.", substring = true).assertIsDisplayed()
        assertTrue(transport.mutations().isEmpty())
        compose.onNodeWithText("DEPLOY TO 100%").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        assertEquals(listOf("POST $script/deployments"), transport.mutations())
    }

    @Test
    fun secretEditorSendsTheValueOnlyAfterConfirmation() {
        scriptResponses()
        transport.respond(CloudflareHttpMethod.GET, "$script/secrets", envelope("[]"))
        transport.respond(CloudflareHttpMethod.PUT, "$script/secrets", envelope("null"))
        val viewModel = CloudflareWorkerOperationsViewModel(api, "acc", "edge-api", CloudflareWorkerMemoryCache())
        compose.setContent {
            VercelticsTheme {
                CloudflareWorkerOperationsScreen(viewModel, 0, onOpenSource = {}, onOpenLiveLogs = {}, onOpenVersion = {})
            }
        }

        compose.onNodeWithTag("cloudflare.workerOperations").performScrollToNode(hasTestTag("cloudflare.worker.secrets.add"))
        compose.onNodeWithTag("cloudflare.worker.secrets.add").performClick()
        compose.onNodeWithTag("cloudflare.worker.secretEditor.name").performTextInput("API_KEY")
        compose.onNodeWithTag("cloudflare.worker.secretEditor.value").performTextInput("hunter2")
        compose.onNodeWithTag("cloudflare.worker.secretEditor.save").performClick()
        compose.onNodeWithText("Save secret API_KEY?").assertIsDisplayed()
        assertTrue(transport.mutations().isEmpty())
        compose.onNodeWithText("SAVE SECRET").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        assertEquals(listOf("PUT $script/secrets"), transport.mutations())
        assertTrue(transport.lastBody()!!.contains("\"text\":\"hunter2\""))
    }

    private fun envelope(result: String) = "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":$result}"

    private class RecordingTransport : CloudflareRestTransport {
        val requests = CopyOnWriteArrayList<CloudflareRestRequest>()
        private val responses = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun respond(method: CloudflareHttpMethod, path: String, json: String) {
            responses["$method $path"] = json
        }

        fun mutations(): List<String> = requests.filter { it.method.isMutation }.map { "${it.method} ${it.apiPath}" }

        fun lastBody(): String? = requests.lastOrNull()?.bodyText()

        override fun newCall(request: CloudflareRestRequest, credential: CloudflareCredential): CancelableCall<CloudflareRestResponse> =
            object : CancelableCall<CloudflareRestResponse> {
                override fun execute(): CloudflareRestResponse {
                    requests += request
                    val body = responses["${request.method} ${request.apiPath}"]
                        ?: return CloudflareRestResponse(404, emptyMap(), "{\"success\":false,\"errors\":[]}".toByteArray())
                    return CloudflareRestResponse(200, emptyMap(), body.toByteArray())
                }

                override fun cancel() = Unit
            }
    }
}
