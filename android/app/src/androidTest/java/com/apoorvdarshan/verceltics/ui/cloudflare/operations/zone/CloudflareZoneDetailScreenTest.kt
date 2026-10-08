package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

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
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.theme.VercelticsTheme
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudflareZoneDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val transport = ZoneScriptedTransport()
    private val navigated = Collections.synchronizedList(mutableListOf<CloudflareOperationsRoute>())

    private fun setContent() {
        val client = CloudflareRestClient(
            credentialProvider = { CloudflareCredential.apiToken("test-token") },
            executor = Executor { it.run() },
            transport = transport,
        )
        val context = CloudflareOperationsContext(
            client = client,
            accountId = "acc",
            accountName = "Studio",
            refreshSignal = 0,
            navigateAction = { navigated += it },
            closeAction = {},
            closeResourceAction = {},
            inventoryChangedAction = {},
        )
        compose.setContent {
            VercelticsTheme {
                CloudflareZoneDetailDestination(
                    zone = CloudflareZoneUi("z", "example.com", "active", "full", false, "Studio", "Pro"),
                    context = context,
                )
            }
        }
    }

    private fun openDeleteDialog() {
        compose.waitUntil(5_000) { transport.requests.any { it.endsWith("/zones/z/dns_records") } }
        compose.onNodeWithTag("cloudflare.zoneDetail").performScrollToNode(hasTestTag("cloudflare.zone.dns.a"))
        compose.onNodeWithTag("cloudflare.zone.dns.a.menu").performClick()
        compose.onNodeWithTag("cloudflare.zone.dns.a.delete").performClick()
    }

    @Test
    fun deletingADnsRecordShowsTheExactChangeAndSendsDeleteOnlyOnConfirm() {
        setContent()
        openDeleteDialog()

        compose.onNodeWithTag("cloudflare.confirmation").assertIsDisplayed()
        compose.onNodeWithText("Delete this DNS record?").assertIsDisplayed()
        compose.onNodeWithText("www.example.com → 203.0.113.10 will be permanently removed.").assertIsDisplayed()
        assertTrue(transport.requests.none { it.startsWith("DELETE") })

        compose.onNodeWithText("DELETE A RECORD").performClick()
        compose.waitUntil(5_000) { transport.requests.any { it.startsWith("DELETE") } }
        assertEquals(listOf("DELETE /zones/z/dns_records/a"), transport.requests.filter { it.startsWith("DELETE") })
        compose.onNodeWithText("DNS record deleted.").assertIsDisplayed()
    }

    @Test
    fun dismissingTheConfirmationSendsNothing() {
        setContent()
        openDeleteDialog()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()
        assertTrue(transport.requests.none { it.startsWith("DELETE") })
        compose.onNodeWithTag("cloudflare.zone.dns.a").assertIsDisplayed()
    }

    @Test
    fun fullCachePurgeNeedsTheZoneNameAndADestructiveConfirmation() {
        setContent()
        compose.onNodeWithTag("cloudflare.zone.purge").performClick()
        compose.onNodeWithTag("cloudflare.zone.purgeSheet").assertIsDisplayed()
        compose.onNodeWithTag("cloudflare.zone.purgeSheet.kind.EVERYTHING").performClick()
        compose.onNodeWithTag("cloudflare.zone.purgeSheet.confirmation").performTextInput("example.com")
        compose.onNodeWithTag("cloudflare.zone.purgeSheet.continue").performClick()

        compose.onNodeWithText("Purge all cached content?").assertIsDisplayed()
        compose.onNodeWithText("Every cached object for example.com will be removed from Cloudflare’s edge.").assertIsDisplayed()
        assertTrue(transport.requests.none { it.contains("purge_cache") })
        compose.onNodeWithText("PURGE CACHE").performClick()
        compose.waitUntil(5_000) { transport.requests.any { it == "POST /zones/z/purge_cache" } }
        assertEquals("""{"purge_everything":true}""", transport.bodies["POST /zones/z/purge_cache"])
    }

    @Test
    fun controlCenterLinksPushZoneRoutes() {
        setContent()
        compose.onNodeWithTag("cloudflare.zone.securityLink").performClick()
        compose.onNodeWithTag("cloudflare.zone.operationsLink").performClick()
        compose.runOnIdle {
            assertEquals(listOf(CloudflareZoneRoutes.SECURITY, CloudflareZoneRoutes.OPERATIONS), navigated.map { it.screen })
            assertEquals(listOf("z", "example.com"), navigated.first().args)
        }
    }
}

/** Scripted Cloudflare responses for the zone detail screen. */
private class ZoneScriptedTransport : CloudflareRestTransport {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    override fun newCall(request: CloudflareRestRequest, credential: CloudflareCredential): CancelableCall<CloudflareRestResponse> =
        object : CancelableCall<CloudflareRestResponse> {
            override fun execute(): CloudflareRestResponse {
                val key = "${request.method} ${request.apiPath}"
                requests += key
                request.bodyText()?.let { bodies[key] = it }
                val body = when {
                    request.method == CloudflareHttpMethod.GET && request.apiPath == "/zones/z" ->
                        envelope("""{"id":"z","name":"example.com","status":"active","paused":false,"account":{"id":"acc","name":"Studio"},"plan":{"name":"Pro"}}""")
                    request.method == CloudflareHttpMethod.GET && request.apiPath == "/zones/z/dns_records" ->
                        envelope(
                            """[{"id":"a","type":"A","name":"www.example.com","content":"203.0.113.10","proxied":true,"proxiable":true}]""",
                            """{"total_pages":1}""",
                        )
                    request.apiPath == "/graphql" -> """{"data":{"viewer":{"zones":[]}},"errors":[{"message":"Analytics disabled in tests"}]}"""
                    request.method == CloudflareHttpMethod.DELETE -> envelope("""{"id":"a"}""")
                    request.method == CloudflareHttpMethod.POST && request.apiPath == "/zones/z/purge_cache" -> envelope("""{"id":"z"}""")
                    else -> """{"success":false,"errors":[{"code":7003,"message":"Unexpected test request"}]}"""
                }
                return CloudflareRestResponse(200, emptyMap(), body.toByteArray(StandardCharsets.UTF_8))
            }

            override fun cancel() = Unit
        }

    private fun envelope(result: String, info: String? = null) =
        """{"success":true,"errors":[],"messages":[],"result":$result${info?.let { ",\"result_info\":$it" }.orEmpty()}}"""
}
