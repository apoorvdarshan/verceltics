package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestTransport
import com.apoorvdarshan.verceltics.data.network.CancelableCall
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

class CloudflareZoneOperationsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val transport = object : CloudflareRestTransport {
        override fun newCall(request: CloudflareRestRequest, credential: SecretValue): CancelableCall<CloudflareRestResponse> =
            object : CancelableCall<CloudflareRestResponse> {
                override fun execute(): CloudflareRestResponse {
                    requests += "${request.method} ${request.apiPath}"
                    val result = when (request.apiPath) {
                        "/zones/z" -> """{"id":"z","name":"example.com","status":"active","paused":false}"""
                        "/zones/z/dnssec" -> if (request.method == CloudflareHttpMethod.GET) """{"status":"active","key_tag":2371}""" else "\"ok\""
                        "/zones/z/settings" -> """[{"id":"ssl","value":"full","editable":true}]"""
                        "/zones/z/dns_settings" -> """{"zone_mode":"standard"}"""
                        "/zones/z/dns_records/usage" -> """{"record_usage":4}"""
                        "/zones/z/dns_analytics/report" -> """{"rows":0,"totals":{}}"""
                        "/zones/z/settings/security_level" -> """{"value":"medium"}"""
                        "/zones/z/firewall/access_rules/rules" ->
                            """[{"id":"r1","mode":"block","configuration":{"target":"ip","value":"198.51.100.1"}}]"""
                        "/zones/z/firewall/access_rules/rules/r1" -> """{"id":"r1"}"""
                        "/zones/z/bot_management", "/zones/z/api_gateway/configuration" -> "null"
                        else -> "[]"
                    }
                    val body = """{"success":true,"errors":[],"messages":[],"result":$result}"""
                    return CloudflareRestResponse(200, emptyMap(), body.toByteArray(StandardCharsets.UTF_8))
                }

                override fun cancel() = Unit
            }
    }

    private fun show(route: CloudflareOperationsRoute) {
        val context = CloudflareOperationsContext(
            client = CloudflareRestClient({ SecretValue.of("test-token") }, Executor { it.run() }, transport),
            accountId = "acc",
            accountName = "Studio",
            refreshSignal = 0,
            navigateAction = {},
            closeAction = {},
            closeResourceAction = {},
            inventoryChangedAction = {},
        )
        compose.setContent { VercelticsTheme { CloudflareZoneDestination(route, context) } }
    }

    @Test
    fun disablingDnssecIsDestructiveAndConfirmed() {
        show(CloudflareZoneRoutes.operations("z", "example.com"))
        compose.waitUntil(5_000) { requests.contains("GET /zones/z/dnssec") }
        compose.onNodeWithTag("cloudflare.zoneOperations").performScrollToNode(hasTestTag("cloudflare.zoneOps.dnssecToggle"))
        compose.onNodeWithTag("cloudflare.zoneOps.dnssecToggle").performClick()
        compose.onNodeWithText("Disable DNSSEC?").assertIsDisplayed()
        assertTrue(requests.none { it == "DELETE /zones/z/dnssec" })
        compose.onNodeWithText("DISABLE DNSSEC").performClick()
        compose.waitUntil(5_000) { requests.contains("DELETE /zones/z/dnssec") }
    }

    @Test
    fun deletingAnAccessRuleNamesTheRuleAndWaitsForConfirmation() {
        show(CloudflareZoneRoutes.security("z", "example.com"))
        compose.waitUntil(5_000) { requests.contains("GET /zones/z/firewall/access_rules/rules") }
        compose.onNodeWithTag("cloudflare.securityCenter").performScrollToNode(hasTestTag("cloudflare.security.access"))
        compose.onNodeWithTag("cloudflare.security.access.r1.delete").performClick()
        compose.onNodeWithText("Delete this IP access rule?").assertIsDisplayed()
        compose.onNodeWithText(
            "198.51.100.1 (block) will be removed from example.com. Traffic matching this rule will immediately stop using its current action.",
        ).assertIsDisplayed()
        compose.onNodeWithText("CANCEL").performClick()
        compose.waitForIdle()
        assertTrue(requests.none { it.startsWith("DELETE") })

        compose.onNodeWithTag("cloudflare.security.access.r1.delete").performClick()
        compose.onNodeWithText("DELETE RULE").performClick()
        compose.waitUntil(5_000) { requests.any { it.startsWith("DELETE") } }
        assertEquals(listOf("DELETE /zones/z/firewall/access_rules/rules/r1"), requests.filter { it.startsWith("DELETE") })
    }
}
