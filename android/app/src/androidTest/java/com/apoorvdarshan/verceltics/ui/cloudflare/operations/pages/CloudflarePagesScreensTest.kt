package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflarePagesProjectUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflarePagesScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = "/accounts/acc/pages/projects/site"
    private val transport = ScriptedPagesTransport().apply {
        respond(CloudflareHttpMethod.GET, project, envelope(PROJECT))
        respond(CloudflareHttpMethod.GET, "$project/deployments", envelope("[$DEPLOYMENT]", "{\"total_pages\":1}"))
        respond(CloudflareHttpMethod.GET, "$project/domains", envelope("[]", "{\"total_pages\":1}"))
        respond(CloudflareHttpMethod.DELETE, "$project/deployments/dep-1", envelope("null"))
        respond(CloudflareHttpMethod.POST, "$project/deployments/dep-1/rollback", envelope(DEPLOYMENT))
        respond(CloudflareHttpMethod.DELETE, project, envelope("null"))
    }
    private val client = CloudflareRestClient(
        credentialProvider = { CloudflareCredential.apiToken("token") },
        executor = Executor { it.run() },
        transport = transport,
    )
    private val summary = CloudflarePagesProjectUi("p1", "site", "site.pages.dev", emptyList(), "main", "success")

    @Test
    fun deletingADeploymentShowsExactConfirmationAndOnlyConfirmSends() {
        val model = CloudflarePagesProjectDetailViewModel(CloudflarePagesApi(client), "acc", "site")
        compose.setContent {
            VercelticsTheme { CloudflarePagesProjectDetailScreen(model, summary, onOpenOperations = {}, onOpenDeployment = {}) }
        }
        openDeploymentMenu()
        compose.onNodeWithTag("cloudflare.pages.deployment.dep-1.delete").performClick()
        compose.onNodeWithTag("cloudflare.confirmation").assertIsDisplayed()
        compose.onNodeWithText("Delete this deployment?").assertIsDisplayed()
        compose.onNodeWithText("Deployment dep-1 of site will be permanently removed from Cloudflare Pages.").assertIsDisplayed()

        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()
        assertTrue(transport.mutations().isEmpty())

        openDeploymentMenu()
        compose.onNodeWithTag("cloudflare.pages.deployment.dep-1.delete").performClick()
        compose.onNodeWithText("DELETE DEPLOYMENT").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        assertEquals(listOf("DELETE $project/deployments/dep-1"), transport.mutations())
    }

    @Test
    fun rollbackUsesDestructiveConfirmationCopy() {
        val model = CloudflarePagesProjectDetailViewModel(CloudflarePagesApi(client), "acc", "site")
        compose.setContent {
            VercelticsTheme { CloudflarePagesProjectDetailScreen(model, summary, onOpenOperations = {}, onOpenDeployment = {}) }
        }
        openDeploymentMenu()
        compose.onNodeWithTag("cloudflare.pages.deployment.dep-1.rollback").performClick()
        compose.onNodeWithText("Roll production back?").assertIsDisplayed()
        compose.onNodeWithText("Cloudflare will make deployment dep-1 the active production version of site.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(model.confirmation.value!!.destructive) }
        compose.onNodeWithText("ROLL BACK").performClick()
        compose.waitUntil(5_000) { transport.mutations().isNotEmpty() }
        assertEquals(listOf("POST $project/deployments/dep-1/rollback"), transport.mutations())
    }

    @Test
    fun deletingTheProjectFromOperationsRequiresConfirmation() {
        val model = CloudflarePagesOperationsViewModel(CloudflarePagesApi(client), "acc", "site")
        compose.setContent { VercelticsTheme { CloudflarePagesOperationsScreen(model, onChooseFolder = {}) } }
        // The project controls are the last lazy item, so they are only composed once scrolled to.
        compose.waitUntil(5_000) { model.state.value.project != null }
        compose.onNodeWithTag("cloudflare.pages.operations").performScrollToNode(hasTestTag("cloudflare.pages.deleteProject"))
        compose.onNodeWithTag("cloudflare.pages.deleteProject").performClick()
        compose.onNodeWithText("Delete this Pages project?").assertIsDisplayed()
        compose.onNodeWithText("site, its deployments and Pages configuration will be permanently removed. This cannot be undone.")
            .assertIsDisplayed()
        assertTrue(transport.mutations().isEmpty())
        compose.onNodeWithText("DELETE PROJECT").performClick()
        compose.waitUntil(5_000) { model.state.value.didDeleteProject }
        assertEquals(listOf("DELETE $project"), transport.mutations())
    }

    @Test
    fun sampleDataFallsBackToReadOnlyDetail() {
        val context = CloudflareOperationsContext(
            client = null,
            accountId = "acc",
            accountName = "Studio",
            refreshSignal = 0,
            navigateAction = {},
            closeAction = {},
            closeResourceAction = {},
            inventoryChangedAction = {},
        )
        compose.setContent { VercelticsTheme { CloudflarePagesProjectDetailDestination(summary, context) } }
        compose.onNodeWithTag("cloudflare.pagesDetail.readOnly").assertIsDisplayed()
        assertTrue(transport.requests.isEmpty())
    }

    private fun openDeploymentMenu() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("cloudflare.pages.deployment.dep-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("cloudflare.pages.detail").performScrollToNode(hasTestTag("cloudflare.pages.deployment.dep-1"))
        compose.onNodeWithTag("cloudflare.pages.deployment.dep-1.menu").performClick()
    }

    private fun envelope(result: String, info: String? = null) =
        "{\"success\":true,\"errors\":[],\"messages\":[],\"result\":$result" + (info?.let { ",\"result_info\":$it" } ?: "") + "}"

    private companion object {
        const val DEPLOYMENT =
            "{\"id\":\"dep-1\",\"short_id\":\"dep-1\",\"environment\":\"production\",\"url\":\"https://dep-1.site.pages.dev\"," +
                "\"latest_stage\":{\"status\":\"success\"}}"
        const val PROJECT =
            "{\"id\":\"p1\",\"name\":\"site\",\"subdomain\":\"site.pages.dev\",\"domains\":[],\"production_branch\":\"main\"," +
                "\"build_config\":{\"build_command\":\"npm run build\",\"destination_dir\":\"dist\",\"root_dir\":\"\",\"build_caching\":true}}"
    }
}

/** Minimal scripted transport for instrumented Pages tests; records every request. */
private class ScriptedPagesTransport : CloudflareRestTransport {
    val requests = CopyOnWriteArrayList<CloudflareRestRequest>()
    private val responses = mutableMapOf<String, String>()

    fun respond(method: CloudflareHttpMethod, path: String, json: String) {
        responses["$method $path"] = json
    }

    fun mutations(): List<String> = requests.filter { it.method.isMutation }.map { "${it.method} ${it.apiPath}" }

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
