package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRequestAuth
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.worker.CloudflareWorkerOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareStorageApi
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerRequestBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsApi
import com.apoorvdarshan.verceltics.data.cloudflare.tools.RecordingToolsTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareCredentialTest {
    private val globalKey = CloudflareCredential.globalApiKey("  Owner@Example.COM ", "global-key-0123456789")

    @Test
    fun apiTokensUseBearerAndGlobalKeysUseEmailAndKeyHeaders() {
        assertEquals(
            mapOf("Authorization" to "Bearer scoped-token"),
            headers(CloudflareCredential.apiToken("scoped-token")),
        )
        assertEquals(
            mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key-0123456789"),
            headers(globalKey),
        )
        assertEquals(CloudflareAuthMode.GLOBAL_API_KEY, globalKey.authMode)
        assertEquals(CloudflareAuthMode.API_TOKEN, CloudflareCredential.apiToken("t").authMode)
    }

    @Test
    fun credentialsNeverPrintSecretsOrEmails() {
        val text = globalKey.toString() + CloudflareCredential.apiToken("scoped-secret").toString()
        assertFalse(text.contains("global-key-0123456789"))
        assertFalse(text.contains("scoped-secret"))
        assertFalse(text.contains("owner@example.com"))
    }

    @Test
    fun emailValidationMatchesIosAndBlocksHeaderInjection() {
        assertNull(CloudflareCredential.validateEmail("owner@example.com"))
        listOf("", "   ", "owner", "@example.com", "owner@", "own er@example.com", "owner@example.com\r\nX-Evil: 1")
            .forEach { assertNotNull(it, CloudflareCredential.validateEmail(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareCredential.GlobalApiKey("owner@example.com\nX-Evil: 1", SecretValue.of("key"))
        }
        assertThrows(IllegalArgumentException::class.java) { CloudflareCredential.globalApiKey("owner@example.com", "  ") }
    }

    @Test
    fun authModesFollowIosOrderAndStorageIds() {
        assertEquals(listOf(CloudflareAuthMode.GLOBAL_API_KEY, CloudflareAuthMode.API_TOKEN), CloudflareAuthMode.entries)
        assertEquals(listOf("Global key", "API token"), CloudflareAuthMode.entries.map { it.displayName })
        CloudflareAuthMode.entries.forEach { assertEquals(it, CloudflareAuthMode.fromStorageId(it.storageId)) }
        assertThrows(IllegalArgumentException::class.java) { CloudflareAuthMode.fromStorageId("password") }
    }

    @Test
    fun operationsZoneWorkerPagesAndStorageClientsSendGlobalKeyHeaders() = runTest {
        val transport = FakeCloudflareRestTransport()
        val client = FakeCloudflareRestTransport.client(transport, globalKey)
        transport.alwaysJson(CloudflareHttpMethod.GET, "/zones/zone-1", envelope("""{"id":"zone-1","name":"example.com"}"""))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc/workers/scripts", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc/pages/projects/site/deployments", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc/d1/database", envelope("[]"))
        transport.alwaysJson(CloudflareHttpMethod.GET, "/accounts/acc/storage/kv/namespaces", envelope("[]"))

        runCatching { CloudflareZoneOperationsApi(client).fetchZone("zone-1") }
        CloudflareWorkerOperationsApi(client).fetchWorkerScripts("acc")
        CloudflarePagesApi(client).fetchDeployments("acc", "site")
        CloudflareStorageApi(client).fetchD1Databases("acc")
        CloudflareStorageApi(client).fetchKVNamespaces("acc")

        assertEquals(5, transport.requests.size)
        transport.requests.forEach { recorded ->
            assertEquals(
                mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key-0123456789"),
                recorded.authHeaders,
            )
        }
    }

    @Test
    fun pagesUploadJwtStillUsesItsOwnBearerForGlobalKeyAccounts() = runTest {
        val transport = FakeCloudflareRestTransport()
        val client = FakeCloudflareRestTransport.client(transport, globalKey)
        transport.enqueueJson(CloudflareHttpMethod.POST, "/pages/assets/check-missing", envelope("[]"))
        client.result(
            CloudflareRestRequest(
                CloudflareHttpMethod.POST,
                listOf("pages", "assets", "check-missing"),
                body = "{}".toByteArray(),
                auth = CloudflareRequestAuth.PagesUploadToken(SecretValue.of("upload-jwt")),
            ),
        )
        assertEquals("upload-jwt", transport.requests.single().bearer)
    }

    @Test
    fun explorerGraphQlAndAccountToolsSendGlobalKeyHeaders() {
        val transport = RecordingToolsTransport()
        val api = CloudflareToolsApi(transport)
        api.newRawRequestCall(globalKey, CloudflareExplorerDraft(path = "/accounts"), null).execute()
        api.newGraphQLCall(globalKey, "query { viewer { zones { zoneTag } } }").execute()
        runCatching { api.newAccountDetailCall(globalKey, "acc").execute() }

        assertEquals(3, transport.authHeaders.size)
        transport.authHeaders.forEach {
            assertEquals(mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key-0123456789"), it)
        }
    }

    @Test
    fun customExplorerHeadersCanNeverOverrideCredentialHeaders() {
        CloudflareCredential.AUTHENTICATION_HEADERS.forEach { name ->
            assertTrue(name, CloudflareExplorerRequestBuilder.isProtectedHeader(name))
            assertThrows(IllegalArgumentException::class.java) {
                CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"), headers = mapOf(name to "x"))
            }
        }
    }

    private fun headers(credential: CloudflareCredential): Map<String, String> =
        LinkedHashMap<String, String>().also { map -> credential.applyHeaders { name, value -> map[name] = value } }
}
