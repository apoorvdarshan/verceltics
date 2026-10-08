package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartField
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflareStorageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val transport = StorageFakeTransport().apply {
        respond("GET /accounts/acc/storage/kv/namespaces/ns/keys", "[{\"name\":\"theme\"}]")
        respond("DELETE /accounts/acc/storage/kv/namespaces/ns/values/theme", "null")
        respond("GET /accounts/acc/d1/database/db", "{\"uuid\":\"db\",\"name\":\"main\"}")
        respond("POST /accounts/acc/d1/database/db/query", "[{\"success\":true,\"results\":[{\"name\":\"users\"}]}]")
        respond("GET /accounts/acc/d1/database", "[{\"uuid\":\"db\",\"name\":\"main\"}]")
        respond("GET /accounts/acc/storage/kv/namespaces", "[{\"id\":\"ns\",\"title\":\"CACHE\"}]")
        respond("GET /accounts/acc/r2/buckets", "{\"buckets\":[{\"name\":\"media\"}]}")
    }
    private val api = CloudflareStorageApi(
        CloudflareRestClient(credentialProvider = { CloudflareCredential.apiToken("token") }, executor = Executor { it.run() }, transport = transport),
    )

    @Test
    fun deletingAKvKeyStatesTheChangeAndOnlyConfirmSendsTheDelete() {
        val viewModel = CloudflareKVNamespaceViewModel(api, "acc", "ns", "CACHE")
        compose.setContent { VercelticsTheme { CloudflareKVNamespaceScreen(viewModel) } }

        compose.onNodeWithTag("cloudflare.storage.kvScreen").performScrollToNode(hasTestTag("cloudflare.storage.kv.deleteKey.theme"))
        compose.onNodeWithTag("cloudflare.storage.kv.deleteKey.theme").performClick()
        compose.onNodeWithTag("cloudflare.confirmation").assertIsDisplayed()
        compose.onNodeWithText("Delete this KV key?").assertIsDisplayed()
        compose.onNodeWithText("theme will be permanently removed.").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.runOnIdle { assertTrue(transport.mutations().isEmpty()) }

        compose.onNodeWithTag("cloudflare.storage.kv.deleteKey.theme").performClick()
        compose.onNodeWithText("DELETE KEY").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("DELETE /accounts/acc/storage/kv/namespaces/ns/values/theme"), transport.mutations()) }
    }

    @Test
    fun runningSqlAlwaysAsksFirst() {
        val viewModel = CloudflareD1DatabaseViewModel(api, "acc", "db", "main")
        compose.setContent { VercelticsTheme { CloudflareD1DatabaseScreen(viewModel) } }

        compose.onNodeWithTag("cloudflare.storage.d1Screen").performScrollToNode(hasTestTag("cloudflare.storage.d1.sql"))
        compose.onNodeWithTag("cloudflare.storage.d1.sql").performTextClearance()
        compose.onNodeWithTag("cloudflare.storage.d1.sql").performTextInput("SELECT * FROM users;")
        compose.onNodeWithTag("cloudflare.storage.d1Screen").performScrollToNode(hasTestTag("cloudflare.storage.d1.run"))
        compose.onNodeWithTag("cloudflare.storage.d1.run").performClick()
        compose.onNodeWithText("Run this SQL statement?").assertIsDisplayed()
        compose.runOnIdle { assertTrue(transport.mutations().isEmpty()) }
        compose.onNodeWithText("RUN SQL").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        compose.onNodeWithTag("cloudflare.storage.d1Screen").performScrollToNode(hasText("Query returned 1 row."))
        compose.onNodeWithText("Query returned 1 row.").assertIsDisplayed()
    }

    @Test
    fun dashboardCreateSheetConfirmsBeforeCreating() {
        val viewModel = CloudflareStorageDashboardViewModel(api, "acc")
        compose.setContent {
            VercelticsTheme {
                CloudflareStorageDashboardScreen(viewModel, "Production", onOpenD1 = {}, onOpenKV = {}, onOpenR2 = {})
            }
        }
        compose.waitUntil(5_000) { !viewModel.state.value.isLoading }
        compose.onNodeWithTag("cloudflare.storage.dashboard").performScrollToNode(hasTestTag("cloudflare.storage.kv.ns"))
        compose.onNodeWithTag("cloudflare.storage.kv.ns").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.dashboard").performScrollToNode(hasTestTag("cloudflare.storage.create.kv"))
        compose.onNodeWithTag("cloudflare.storage.create.kv").performClick()
        compose.onNodeWithTag("cloudflare.storage.create.name").performTextInput("SESSIONS")
        compose.onNodeWithTag("cloudflare.storage.create.submit").performClick()
        compose.onNodeWithText("Create this KV namespace?").assertIsDisplayed()
        compose.onNodeWithText("Cloudflare will create the KV namespace SESSIONS in this account.").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.runOnIdle { assertTrue(transport.mutations().isEmpty()) }
    }

    @Test
    fun d1ResultsPageThroughEveryRow() {
        val rows = (1..250).joinToString(",") { "{\"id\":$it}" }
        transport.respond("POST /accounts/acc/d1/database/db/query", "[{\"success\":true,\"results\":[$rows]}]")
        val viewModel = CloudflareD1DatabaseViewModel(api, "acc", "db", "main")
        compose.setContent { VercelticsTheme { CloudflareD1DatabaseScreen(viewModel) } }

        compose.onNodeWithTag("cloudflare.storage.d1Screen").performScrollToNode(hasTestTag("cloudflare.storage.d1.run"))
        compose.onNodeWithTag("cloudflare.storage.d1.run").performClick()
        compose.onNodeWithText("RUN SQL").performClick()
        compose.waitUntil(5_000) { viewModel.state.value.queryResults.isNotEmpty() }

        compose.onNodeWithTag("cloudflare.storage.d1Screen").performScrollToNode(hasTestTag("cloudflare.storage.d1.pageLabel"))
        compose.onNodeWithText("Rows 1–100 of 250").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.d1.nextPage").performClick()
        compose.onNodeWithText("Rows 101–200 of 250").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.d1.nextPage").performClick()
        compose.onNodeWithText("Rows 201–250 of 250").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.d1.nextPage").assertIsNotEnabled()
    }

    @Test
    fun r2CorsPresetIsReplacedOnlyAfterConfirmation() {
        transport.respond("GET /accounts/acc/r2/buckets/media", "{\"name\":\"media\"}")
        transport.respond("GET /accounts/acc/r2/buckets/media/objects", "[]")
        transport.respond("GET /accounts/acc/r2/buckets/media/cors", "{\"rules\":[]}")
        transport.respond("PUT /accounts/acc/r2/buckets/media/cors", "{}")
        val viewModel = CloudflareR2BucketViewModel(api, null, "acc", "media", null)
        compose.setContent { VercelticsTheme { CloudflareR2BucketScreen(viewModel) } }

        compose.onNodeWithTag("cloudflare.storage.r2Screen").performScrollToNode(hasTestTag("cloudflare.storage.r2.config.CORS.edit"))
        compose.onNodeWithTag("cloudflare.storage.r2.config.CORS.edit").performClick()
        compose.onNodeWithTag("cloudflare.storage.r2.rulesSheet").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.r2.rulesPreset.cors-public-read").performClick()
        compose.onNodeWithTag("cloudflare.storage.r2.rulesSubmit").performScrollTo().performClick()
        compose.onNodeWithText("Replace CORS rules?").assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.runOnIdle { assertTrue(transport.mutations().isEmpty()) }

        compose.onNodeWithTag("cloudflare.storage.r2.rulesSubmit").performScrollTo().performClick()
        compose.onNodeWithText("REPLACE RULES").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf("PUT /accounts/acc/r2/buckets/media/cors"), transport.mutations()) }
    }

    @Test
    fun globalApiKeyStorageExplainsThatR2NeedsAScopedToken() {
        val viewModel = CloudflareStorageDashboardViewModel(api, "acc", allowsR2 = false)
        compose.setContent {
            VercelticsTheme {
                CloudflareStorageDashboardScreen(viewModel, "Production", onOpenD1 = {}, onOpenKV = {}, onOpenR2 = {})
            }
        }
        compose.waitUntil(5_000) { !viewModel.state.value.isLoading }
        compose.onNodeWithTag("cloudflare.storage.dashboard").performScrollToNode(hasTestTag("cloudflare.storage.r2.requiresToken"))
        compose.onNodeWithText("R2 requires a scoped token").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.storage.create.r2").assertDoesNotExist()
        compose.runOnIdle { assertTrue(transport.sentRequests().none { it.contains("/r2/") }) }
    }

    @Test
    fun pullingDownTheDashboardAndKvNamespaceReloadsThem() {
        val dashboard = CloudflareStorageDashboardViewModel(api, "acc")
        compose.setContent {
            VercelticsTheme {
                CloudflareStorageDashboardScreen(dashboard, "Production", onOpenD1 = {}, onOpenKV = {}, onOpenR2 = {})
            }
        }
        compose.waitUntil(5_000) { !dashboard.state.value.isLoading }
        val namespaceLoads = transport.sentRequests().count { it == "GET /accounts/acc/storage/kv/namespaces" }
        compose.onNodeWithTag("cloudflare.storage.dashboard.pullToRefresh").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) {
            transport.sentRequests().count { it == "GET /accounts/acc/storage/kv/namespaces" } > namespaceLoads &&
                !dashboard.state.value.isRefreshing
        }
        compose.onNodeWithTag("cloudflare.storage.dashboard").performScrollToNode(hasTestTag("cloudflare.storage.kv.ns"))
        compose.onNodeWithTag("cloudflare.storage.kv.ns").assertIsDisplayed()
    }

    @Test
    fun multipartComposerEnforcesRequiredFields() {
        compose.setContent {
            VercelticsTheme {
                CloudflareMultipartComposerSheet(
                    schemaFields = listOf(CloudflareMultipartField("file", isFile = true, required = true)),
                    onCompose = {},
                    onDismiss = {},
                    files = object : CloudflareStorageFiles {
                        override suspend fun read(uri: String, maximumBytes: Int) = CloudflarePickedFile("a.txt", null, byteArrayOf(1))
                        override suspend fun write(uri: String, data: ByteArray) = Unit
                    },
                )
            }
        }
        compose.onNodeWithTag("cloudflare.multipart.compose").performClick()
        compose.onNodeWithText("Add values for required fields: file.").assertIsDisplayed()
    }
}

/** Minimal scripted transport for Compose tests (unit-test fakes are not visible to androidTest). */
private class StorageFakeTransport : CloudflareRestTransport {
    private val responses = mutableMapOf<String, String>()
    private val sent = CopyOnWriteArrayList<String>()

    fun respond(key: String, result: String) {
        responses[key] = "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":$result}"
    }

    fun mutations(): List<String> = sent.filterNot { it.startsWith("GET ") }

    fun sentRequests(): List<String> = sent.toList()

    override fun newCall(request: CloudflareRestRequest, credential: CloudflareCredential): CancelableCall<CloudflareRestResponse> =
        object : CancelableCall<CloudflareRestResponse> {
            override fun execute(): CloudflareRestResponse {
                val key = "${request.method} ${request.apiPath}"
                sent += key
                val body = responses[key] ?: return CloudflareRestResponse(404, emptyMap(), "{}".toByteArray())
                return CloudflareRestResponse(200, emptyMap(), body.toByteArray())
            }

            override fun cancel() = Unit
        }
}
