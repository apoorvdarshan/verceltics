package com.apoorvdarshan.verceltics.ui.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.GoogleAccessTokenSource
import com.apoorvdarshan.verceltics.data.hosting.HostingConnectionStore
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpMethod
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpRequest
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.data.hosting.HostingProviderApi
import com.apoorvdarshan.verceltics.data.hosting.HostingStoreFixture
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHostingProviderUiGatewayTest {
    @Test
    fun connectValidatesSavesEncryptedAndRestoresOfflineWithLinks() = runBlocking {
        Fixture(renderRoutes()).use { fixture ->
            val dashboard = fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("render-secret"))).getOrThrow()
            assertEquals("render", dashboard.providerId)
            assertEquals("Studio", dashboard.account.displayName)
            assertEquals(HostingCacheState.LIVE, dashboard.cacheState)
            assertEquals("https://dashboard.render.com/", dashboard.dashboardUrl)
            val web = dashboard.resources.single()
            assertEquals("https://dashboard.render.com/srv-web", web.dashboardUrl)
            assertEquals("Web Service", web.kind)

            val restored = fixture.gateway.restore().getOrThrow()
            val render = restored.getValue("render") as HostingRestoreUi.Available
            assertEquals(HostingCacheState.CACHED_FRESH, render.dashboard.cacheState)
            assertEquals(dashboard.resources, render.dashboard.resources)
            assertEquals(HostingRestoreUi.NotConnected, restored.getValue("heroku"))
            assertEquals(HostingProvider.entries.size, restored.size)
            assertFalse(restored.toString().contains("render-secret"))
        }
    }

    @Test
    fun failedValidationSavesNothingAndSurfacesTheSafeMessage() = runBlocking {
        Fixture(FakeHostingTransport { jsonResponse("""{"message":"echo render-secret"}""", status = 401) }).use { fixture ->
            val failure = fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("render-secret"))).exceptionOrNull()
            assertTrue(failure is HostingUiException)
            assertEquals("Render rejected these credentials.", failure!!.message)
            assertFalse((failure as HostingUiException).requiresGoogleSignIn)
            assertNull(fixture.storage.repository.load(HostingProvider.RENDER))
        }
    }

    @Test
    fun refreshLoadResourceActionAndDisconnectUseTheSavedCredentials() = runBlocking {
        Fixture(renderRoutes()).use { fixture ->
            val dashboard = fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("render-secret"))).getOrThrow()
            fixture.transport.requests.clear()

            val refreshed = fixture.gateway.refresh("render").getOrThrow()
            assertEquals(dashboard.resources, refreshed.resources)
            assertTrue(fixture.transport.requests.all { (it.auth as com.apoorvdarshan.verceltics.data.hosting.HostingAuth.Bearer).token == SecretValue.of("render-secret") })
            assertTrue(fixture.transport.requests.none { it.encodedPath == "/v1/owners" })

            val workspace = fixture.gateway.loadResource("render", refreshed.resources.single()).getOrThrow()
            assertEquals(listOf("Fix headers"), workspace.deployments.map { it.title })
            assertEquals(1, workspace.loadedDeploymentCount)

            val message = fixture.gateway.performPrimaryAction("render", refreshed.resources.single(), null).getOrThrow()
            assertEquals("Redeploy request accepted.", message)
            assertEquals(HostingHttpMethod.POST, fixture.transport.requests.last().method)

            fixture.gateway.disconnect("render").getOrThrow()
            assertNull(fixture.storage.repository.load(HostingProvider.RENDER))
            assertEquals("Connect Render first.", fixture.gateway.refresh("render").exceptionOrNull()?.message)
        }
    }

    @Test
    fun inventoryAndHistoryArePagedCompletelyWithoutTheOldDisplayCaps() = runBlocking {
        // 450 services and 260 deploys: beyond the former 200-resource / 100-history caps.
        Fixture(FakeHostingTransport { request -> pagedRenderResponse(request, services = 450, deploys = 260) }).use { fixture ->
            val dashboard = fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("render-secret"))).getOrThrow()
            assertEquals(450, dashboard.resources.size)
            assertEquals(450, dashboard.loadedResourceCount)
            assertEquals((0 until 450).map { "srv-$it" }.toSet(), dashboard.resources.map { it.id }.toSet())
            assertTrue(dashboard.warnings.isEmpty())

            val refreshed = fixture.gateway.refresh("render").getOrThrow()
            assertEquals(450, refreshed.resources.size)

            val workspace = fixture.gateway.loadResource("render", refreshed.resources.first()).getOrThrow()
            assertEquals(260, workspace.deployments.size)
            assertEquals(260, workspace.loadedDeploymentCount)
            assertEquals((0 until 260).map { "dep-$it" }.toSet(), workspace.deployments.map { it.id }.toSet())
        }
    }

    @Test
    fun firebaseWithoutGoogleTokenAsksForSignIn() = runBlocking {
        Fixture(FakeHostingTransport { error("must not send") }, GoogleAccessTokenSource { null }).use { fixture ->
            val failure = fixture.gateway.connect(HostingCredentials.Firebase("studio-prod")).exceptionOrNull() as HostingUiException
            assertTrue(failure.requiresGoogleSignIn)
            assertTrue(fixture.transport.requests.isEmpty())
            assertEquals(
                "Firebase Hosting has no safe one-tap action here.",
                fixture.gateway.performPrimaryAction("firebase", resourceUi("site"), null).exceptionOrNull()?.message,
            )
        }
    }

    @Test
    fun cancellationDuringTheEncryptedSaveRollsBackTheEmptySlot() = runBlocking {
        Fixture(renderRoutes()).use { fixture ->
            val slot = fixture.storage.stores.getValue(HostingProvider.RENDER)
            slot.blockNextWrite()
            val job = async(Dispatchers.Default) {
                fixture.gateway.connect(HostingCredentials.Render(SecretValue.of("cancelled")))
            }
            assertTrue(slot.awaitWriteStarted())
            job.cancel()
            slot.releaseBlockedWrite()
            job.cancelAndJoin()
            // The single storage thread runs the rollback after the interrupted write.
            fixture.drainStorage()
            assertNull(fixture.storage.repository.load(HostingProvider.RENDER))
        }
    }

    @Test
    fun unknownProvidersAndUnreadableSlotsStaySafe() = runBlocking {
        Fixture(renderRoutes()).use { fixture ->
            assertEquals("This hosting provider is not supported.", fixture.gateway.refresh("netlify").exceptionOrNull()?.message)
            fixture.storage.stores.getValue(HostingProvider.HEROKU).bytes = byteArrayOf(4, 5, 6)
            val heroku = fixture.gateway.restore().getOrThrow().getValue("heroku") as HostingRestoreUi.SavedUnavailable
            assertEquals("The saved Heroku connection could not be opened. It was not deleted or replaced.", heroku.message)
        }
    }

    private fun renderRoutes() = FakeHostingTransport { request -> renderResponse(request) }

    private fun renderResponse(request: HostingHttpRequest): HttpResponse = when ("${request.method} ${request.encodedPath}") {
        "GET /v1/owners" -> jsonResponse("""[{"owner":{"id":"tea_1","name":"Studio","email":"ops@studio.example"}}]""")
        "GET /v1/services" -> if (request.query.any { it.first == "cursor" }) {
            jsonResponse("[]")
        } else {
            jsonResponse("""[{"cursor":"c1","service":{"id":"srv-web","name":"studio-web","type":"web_service","serviceDetails":{"region":"oregon"}}}]""")
        }
        "GET /v1/services/srv-web/deploys" -> if (request.query.any { it.first == "cursor" }) {
            jsonResponse("[]")
        } else {
            jsonResponse("""[{"cursor":"d1","deploy":{"id":"dep-1","status":"live","commit":{"message":"Fix headers"}}}]""")
        }
        "POST /v1/services/srv-web/deploys" -> jsonResponse("""{"id":"dep-2"}""", status = 201)
        else -> jsonResponse("{}", status = 404)
    }

    /** Render cursor pagination: the cursor lives on each item; the next page starts after it. */
    private fun pagedRenderResponse(request: HostingHttpRequest, services: Int, deploys: Int): HttpResponse {
        fun page(total: Int, entry: (Int) -> String): HttpResponse {
            val start = request.query.firstOrNull { it.first == "cursor" }?.second?.removePrefix("c")?.toInt()?.plus(1) ?: 0
            val limit = request.query.first { it.first == "limit" }.second.toInt()
            val end = minOf(total, start + limit)
            return jsonResponse((start until end).joinToString(",", "[", "]", transform = entry))
        }
        val path = request.encodedPath
        return when {
            path == "/v1/owners" -> jsonResponse("""[{"owner":{"id":"tea_1","name":"Studio","email":"ops@studio.example"}}]""")
            path == "/v1/services" -> page(services) { index ->
                """{"cursor":"c$index","service":{"id":"srv-$index","name":"service-$index","type":"web_service"}}"""
            }
            path.endsWith("/deploys") -> page(deploys) { index ->
                """{"cursor":"c$index","deploy":{"id":"dep-$index","status":"live","commit":{"message":"Deploy $index"}}}"""
            }
            else -> jsonResponse("{}", status = 404)
        }
    }

    private fun resourceUi(id: String) = HostingResourceUi(id, id, null, null, null, null, null, null, "https://example.com")

    private class Fixture(
        val transport: FakeHostingTransport,
        tokenSource: GoogleAccessTokenSource = GoogleAccessTokenSource.Unavailable,
    ) : AutoCloseable {
        val storage = HostingStoreFixture()
        private val storageExecutor = Executors.newSingleThreadExecutor()
        val gateway = NativeHostingProviderUiGateway(
            connectionStore = HostingConnectionStore(storage.repository) { 1_000L },
            api = HostingProviderApi(transport, tokenSource),
            storageExecutor = storageExecutor,
            workDispatcher = Dispatchers.Default,
            nowMillis = { 1_000L },
        )

        fun drainStorage() {
            storageExecutor.submit {}.get()
        }

        override fun close() {
            storageExecutor.shutdownNow()
        }
    }
}
