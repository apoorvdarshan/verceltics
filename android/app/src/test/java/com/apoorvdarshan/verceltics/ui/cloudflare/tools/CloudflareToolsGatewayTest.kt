package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationEvent
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerDraft
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareGraphQLScope
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareOpenApiCatalogStore
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsApi
import com.apoorvdarshan.verceltics.data.cloudflare.tools.RecordingToolsTransport
import com.apoorvdarshan.verceltics.data.cloudflare.tools.jsonResponse
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareToolsGatewayTest {
    private val direct = Executor { it.run() }
    private val catalogStore = CloudflareOpenApiCatalogStore { ByteArrayInputStream(FakeToolsGateway.CATALOG.toByteArray()) }

    @Test
    fun nativeGatewayUsesTheSavedTokenAndNeverLeaksIt() = runTest {
        val transport = RecordingToolsTransport()
        val gateway = NativeCloudflareToolsGateway(
            credentialSource = { CloudflareCredential.apiToken("saved-cloudflare-token") },
            catalogStore = catalogStore,
            api = CloudflareToolsApi(transport),
            executor = direct,
        )
        assertFalse(gateway.isOfflineSample)
        val response = gateway.execute(CloudflareExplorerDraft(path = "/accounts"), null, null).getOrThrow()
        assertEquals(200, response.statusCode)
        assertEquals(listOf("saved-cloudflare-token"), transport.tokens)
        assertFalse(response.toString().contains("saved-cloudflare-token"))

        val unconfirmed = gateway.execute(CloudflareExplorerDraft(method = CloudflareHttpMethod.DELETE, path = "/zones/z"), null, null)
        assertEquals("Confirm the change to /zones/z before continuing.", unconfirmed.exceptionOrNull()?.message)
        assertTrue(unconfirmed.exceptionOrNull() is CloudflareToolsUiException)
        assertEquals(1, transport.requests.size)

        assertEquals(1, gateway.loadCatalog().getOrThrow().operations.size)
    }

    @Test
    fun globalApiKeyToolsSendEmailAndKeyHeadersOnEveryRequest() = runTest {
        val transport = RecordingToolsTransport { request ->
            when {
                request.uri.rawPath.endsWith("/members") || request.uri.rawPath.endsWith("/roles") ->
                    jsonResponse(200, """{"success":true,"result":[]}""")
                request.uri.rawPath.endsWith("/logs/audit") -> jsonResponse(200, """{"success":true,"result":[]}""")
                request.uri.rawPath.endsWith("/graphql") ->
                    jsonResponse(200, """{"data":{"__type":{"fields":[{"name":"settings","type":{"name":"AccountSettings"}}]}}}""")
                else -> jsonResponse(200, """{"success":true,"result":{"id":"acc","name":"Studio"}}""")
            }
        }
        val gateway = NativeCloudflareToolsGateway(
            credentialSource = { CloudflareCredential.globalApiKey("Owner@Example.com", "global-key") },
            catalogStore = catalogStore,
            api = CloudflareToolsApi(transport),
            executor = direct,
        )
        gateway.execute(CloudflareExplorerDraft(path = "/accounts"), null, null).getOrThrow()
        gateway.loadAccountDetail("acc").getOrThrow()
        gateway.loadAccountOperations("acc").getOrThrow()
        gateway.loadDatasets(CloudflareGraphQLScope.ACCOUNT, "acc", null)

        assertTrue(transport.authHeaders.size >= 6)
        transport.authHeaders.forEach { headers ->
            assertEquals(mapOf("X-Auth-Email" to "owner@example.com", "X-Auth-Key" to "global-key"), headers)
        }
    }

    @Test
    fun successfulExplorerWritesReachTheMutationSinkButReadsAndFailuresDoNot() = runTest {
        var status = 200
        val transport = RecordingToolsTransport { jsonResponse(status, """{"success":true,"result":{}}""") }
        val events = mutableListOf<CloudflareMutationEvent>()
        val gateway = NativeCloudflareToolsGateway(
            credentialSource = { CloudflareCredential.apiToken("t") },
            catalogStore = catalogStore,
            api = CloudflareToolsApi(transport, mutationSink = { events += it }),
            executor = direct,
        )

        gateway.execute(CloudflareExplorerDraft(path = "/zones"), null, null).getOrThrow()
        assertTrue(events.isEmpty())

        gateway.execute(
            CloudflareExplorerDraft(method = CloudflareHttpMethod.POST, path = "/zones", bodyText = "{}"),
            CloudflareMutationConfirmation("/zones"),
            null,
        ).getOrThrow()
        assertEquals(listOf("POST /zones"), events.map { "${it.method} ${it.apiPath}" })

        status = 400
        gateway.execute(
            CloudflareExplorerDraft(method = CloudflareHttpMethod.DELETE, path = "/zones/z"),
            CloudflareMutationConfirmation("/zones/z"),
            null,
        ).getOrThrow()
        assertEquals(1, events.size)

        status = 200
        gateway.execute(
            CloudflareExplorerDraft(
                method = CloudflareHttpMethod.POST,
                path = "/graphql",
                bodyText = """{"query":"query { viewer { zones { zoneTag } } }"}""",
                readOnlyGraphQL = true,
            ),
            null,
            null,
        ).getOrThrow()
        assertEquals(1, events.size)
    }

    @Test
    fun disconnectedGatewayExplainsWhatToDo() = runTest {
        val transport = RecordingToolsTransport()
        val gateway = NativeCloudflareToolsGateway({ null }, catalogStore, CloudflareToolsApi(transport), direct)
        listOf(
            gateway.execute(CloudflareExplorerDraft(), null, null).exceptionOrNull(),
            gateway.loadAccountDetail("acc").exceptionOrNull(),
            gateway.loadAccountOperations("acc").exceptionOrNull(),
            gateway.loadDatasets(CloudflareGraphQLScope.ACCOUNT, "acc", null).exceptionOrNull(),
        ).forEach { assertEquals("Connect a Cloudflare account first.", it?.message) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun accountOperationsReportEachSectionIndependently() = runTest {
        val transport = RecordingToolsTransport { request ->
            when {
                request.uri.rawPath.endsWith("/members") ->
                    jsonResponse(200, """{"success":true,"result":[{"id":"m2","email":"zed@example.com"},{"id":"m1","email":"amy@example.com"}]}""")
                request.uri.rawPath.endsWith("/roles") -> jsonResponse(403, """{"errors":[{"message":"Forbidden"}]}""")
                request.uri.rawPath.endsWith("/logs/audit") -> jsonResponse(503, "{}")
                else -> jsonResponse(200, """{"success":true,"result":{"id":"acc","name":"Studio"}}""")
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC)
        val gateway = NativeCloudflareToolsGateway({ CloudflareCredential.apiToken("t") }, catalogStore, CloudflareToolsApi(transport), direct, clock)
        val snapshot = gateway.loadAccountOperations("acc").getOrThrow()
        assertEquals("Studio", snapshot.account?.name)
        assertEquals(listOf("amy@example.com", "zed@example.com"), snapshot.members.map { it.resolvedEmail })
        assertTrue(snapshot.roles.isEmpty())
        assertEquals(
            "This API token can’t access account roles. Add the “Account Settings Read” permission to the token, then try again.\nCloudflare: Forbidden",
            snapshot.rolesError,
        )
        assertEquals("Cloudflare request failed (503).", snapshot.auditError)
        assertNull(snapshot.accountError)
        assertNull(snapshot.membersError)
        assertFalse(snapshot.allSucceeded)
        val audit = transport.requests.first { it.uri.rawPath.endsWith("/logs/audit") }
        assertTrue(audit.uri.rawQuery.contains("since=2026-10-02T00%3A00%3A00Z"))
        assertTrue(audit.uri.rawQuery.contains("before=2026-10-09T00%3A00%3A00Z"))
    }

    @Test
    fun sampleGatewayValidatesButNeverSends() = runTest {
        val gateway = SampleCloudflareToolsGateway(catalogStore, direct)
        assertTrue(gateway.isOfflineSample)
        val response = gateway.execute(
            CloudflareExplorerDraft(method = CloudflareHttpMethod.POST, path = "/zones", bodyText = "{}"),
            CloudflareMutationConfirmation("/zones"),
            null,
        ).getOrThrow()
        assertTrue(response.prettyPrintedBody.contains("Sample workspace: no request was sent to Cloudflare."))
        assertTrue(response.prettyPrintedBody.contains("/client/v4/zones"))
        assertEquals(
            "Confirm the change to /zones before continuing.",
            gateway.execute(CloudflareExplorerDraft(method = CloudflareHttpMethod.POST, path = "/zones"), null, null).exceptionOrNull()?.message,
        )
        assertTrue(gateway.loadDatasets(CloudflareGraphQLScope.ZONE, "acc", "zone").getOrThrow().any { it.isLocked })
        assertEquals("acc-x", gateway.loadAccountDetail("acc-x").getOrThrow().id)
        assertTrue(gateway.loadAccountOperations("acc-x").getOrThrow().allSucceeded)
        assertEquals(1, gateway.loadCatalog().getOrThrow().operations.size)
        assertEquals(
            "The bundled Cloudflare API catalog is missing.",
            SampleCloudflareToolsGateway(null, direct).loadCatalog().exceptionOrNull()?.message,
        )
    }
}
