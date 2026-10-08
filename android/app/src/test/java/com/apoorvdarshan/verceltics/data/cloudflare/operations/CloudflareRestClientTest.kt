package com.apoorvdarshan.verceltics.data.cloudflare.operations

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.envelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.FakeCloudflareRestTransport.Companion.failure
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.net.URI
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareRestClientTest {
    private val transport = FakeCloudflareRestTransport()
    private val client = FakeCloudflareRestTransport.client(transport)

    @Test
    fun requestsArePinnedToTheOfficialApiOriginAndEncodeEverySegment() {
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.GET,
            listOf("accounts", "acc", "storage", "kv", "namespaces", "ns", "values", "a/b c?d#e%"),
            query = listOf("prefix" to "x&y=z"),
        )
        val uri = request.uri()
        assertEquals("https", uri.scheme)
        assertEquals("api.cloudflare.com", uri.host)
        assertEquals("/client/v4/accounts/acc/storage/kv/namespaces/ns/values/a%2Fb%20c%3Fd%23e%25", uri.rawPath)
        assertEquals("prefix=x%26y%3Dz", uri.rawQuery)
        assertTrue(CloudflareRestRequest.isPinnedCloudflareApiUri(uri))
    }

    @Test
    fun hostPinningRejectsEveryOtherOrigin() {
        listOf(
            "https://evil.example/client/v4/zones",
            "http://api.cloudflare.com/client/v4/zones",
            "https://api.cloudflare.com.evil.example/client/v4/zones",
            "https://user@api.cloudflare.com/client/v4/zones",
            "https://api.cloudflare.com:8443/client/v4/zones",
            "https://api.cloudflare.com/other/zones",
        ).forEach { assertFalse(it, CloudflareRestRequest.isPinnedCloudflareApiUri(URI(it))) }
        assertTrue(CloudflareRestRequest.isPinnedCloudflareApiUri(URI("https://api.cloudflare.com/client/v4/zones")))
    }

    @Test
    fun traversalProtectedHeadersAndGetBodiesAreRejected() {
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones", "..")) }
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones", "")) }
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"), headers = mapOf("Authorization" to "x")) }
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"), headers = mapOf("X-Auth-Key" to "x")) }
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"), headers = mapOf("x-ok" to "a\r\nb")) }
        expectInvalid { CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"), body = byteArrayOf(1)) }
    }

    @Test
    fun toStringRedactsQueryAndBody() {
        val request = CloudflareRestRequest.json(
            CloudflareHttpMethod.POST,
            listOf("zones", "z", "purge_cache"),
            ProviderJsonValue.from(mapOf("secret" to "value")),
            query = listOf("token" to "abc"),
        )
        val text = request.toString()
        assertFalse(text.contains("value"))
        assertFalse(text.contains("abc"))
    }

    @Test
    fun envelopeResultUsesAccountTokenAndEmitsMutationEvents() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/r", envelope("{\"id\":\"r\"}"))
        val event = async(start = CoroutineStart.UNDISPATCHED) { client.mutations.first() }
        val result = client.result(CloudflareRestRequest(CloudflareHttpMethod.DELETE, listOf("zones", "z", "dns_records", "r")))
        assertEquals("r", result.str("id"))
        assertEquals(FakeCloudflareRestTransport.TOKEN, transport.requests.single().bearer)
        assertEquals(CloudflareMutationEvent(CloudflareHttpMethod.DELETE, "/zones/z/dns_records/r"), event.await())
    }

    @Test
    fun httpFailuresMapToIosErrorCopy() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/a", failure("bad"), status = 401)
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/b", failure("No permission"), status = 403)
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/c", "{}", status = 403)
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/d", failure("Record exists", 81057), status = 400)
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/e", "not json", status = 500)

        assertEquals("Cloudflare rejected these credentials.", errorFor("a").userMessage)
        assertEquals("No permission", errorFor("b").userMessage)
        assertEquals("This Cloudflare user cannot access that resource.", errorFor("c").userMessage)
        assertEquals("Cloudflare request failed (400): Record exists", errorFor("d").userMessage)
        assertEquals("Cloudflare request failed (500).", errorFor("e").userMessage)
    }

    @Test
    fun unsuccessfulEnvelopesSurfaceIssuesWithCodesAndPointers() = runTest {
        transport.enqueueJson(
            CloudflareHttpMethod.GET,
            "/zones/a",
            "{\"success\":false,\"errors\":[{\"code\":9000,\"message\":\"Invalid\",\"source\":{\"pointer\":\"/name\"}}]}",
        )
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/b", "{\"success\":false,\"errors\":[]}")
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/c", "[1,2]")
        val api = errorFor("a")
        assertEquals(CloudflareOperationException.Kind.API, api.kind)
        assertEquals("Invalid [code 9000 · /name]", api.userMessage)
        assertEquals("Cloudflare request failed (200): Cloudflare reported an unsuccessful request.", errorFor("b").userMessage)
        assertEquals("Cloudflare returned data the app could not parse.", errorFor("c").userMessage)
    }

    @Test
    fun providerTextIsBoundedAndStrippedOfControlCharacters() {
        val error = CloudflareOperationException.requestFailed(400, "bad\u0007" + "x".repeat(2_000))
        assertFalse(error.userMessage.contains('\u0007'))
        assertTrue(error.userMessage.length < 700)
    }

    @Test
    fun pageWalkFollowsTotalPagesAndStopsOnRepeatedPages() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/dns_records", envelope("[{\"id\":\"1\"}]", "{\"page\":1,\"total_pages\":2}"))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/zones/z/dns_records", envelope("[{\"id\":\"2\"}]", "{\"page\":2,\"total_pages\":2}"))
        val items = client.allPages(listOf("zones", "z", "dns_records"), perPage = 1)
        assertEquals(listOf("1", "2"), items.map { it.str("id") })
        assertEquals(listOf("1", "2"), transport.requests.map { request -> request.query.first { it.first == "page" }.second })

        transport.alwaysJson(CloudflareHttpMethod.GET, "/repeat", envelope("[{\"id\":\"same\"}]", "{\"total_pages\":9}"))
        val error = runCatching { client.allPages(listOf("repeat"), perPage = 1) }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Cloudflare repeated a results page, so loading stopped safely.", error.userMessage)
    }

    @Test
    fun readOnlyGraphQLIsNotReportedAsAMutation() {
        fun graphQL(query: String) = CloudflareRestRequest.json(
            CloudflareHttpMethod.POST,
            listOf("graphql"),
            ProviderJsonValue.from(mapOf("query" to query)),
        )
        assertTrue(CloudflareRestClient.isReadOnlyGraphQL(graphQL("query Zone { viewer { zones { settings { hourly: httpRequests1hGroups { enabled } } } } }")))
        assertFalse(CloudflareRestClient.isReadOnlyGraphQL(graphQL("mutation { doThing }")))
        assertFalse(
            CloudflareRestClient.isReadOnlyGraphQL(
                CloudflareRestRequest(CloudflareHttpMethod.POST, listOf("zones", "z", "purge_cache"), body = "{}".toByteArray()),
            ),
        )
    }

    @Test
    fun paginationGuardMatchesIosSafetyTests() {
        val repeated = CloudflarePaginationGuard()
        repeated.record(2, 42)
        val repeatError = runCatching { repeated.record(2, 42) }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Cloudflare repeated a results page, so loading stopped safely.", repeatError.userMessage)

        val terminal = CloudflarePaginationGuard()
        terminal.record(1, 7)
        terminal.record(0, null)

        val bounded = runCatching { CloudflarePaginationGuard().record(100_001, 1) }.exceptionOrNull() as CloudflareOperationException
        assertEquals("Cloudflare returned too many paginated results. Narrow the request and try again.", bounded.userMessage)
    }

    @Test
    fun cursorWalkFollowsCursorsUntilExhausted() = runTest {
        transport.enqueueJson(CloudflareHttpMethod.GET, "/keys", envelope("[{\"name\":\"a\"}]", "{\"cursor\":\"c1\"}"))
        transport.enqueueJson(CloudflareHttpMethod.GET, "/keys", envelope("[{\"name\":\"b\"}]", "{\"cursor\":\"\"}"))
        val page = client.allCursorPages(listOf("keys"))
        assertEquals(listOf("a", "b"), page.items.map { it.str("name") })
        assertNull(page.nextCursor)
        assertEquals(listOf(null, "c1"), transport.requests.map { request -> request.query.firstOrNull { it.first == "cursor" }?.second })
    }

    @Test
    fun confirmationMustNameTheExactResource() {
        requireCloudflareConfirmation(CloudflareMutationConfirmation("record-1"), "record-1")
        listOf(null, CloudflareMutationConfirmation("record-2")).forEach { confirmation ->
            val error = runCatching { requireCloudflareConfirmation(confirmation, "record-1") }.exceptionOrNull()
            assertEquals(CloudflareOperationException.Kind.CONFIRMATION_REQUIRED, (error as CloudflareOperationException).kind)
            assertEquals("Confirm the change to record-1 before continuing.", error.userMessage)
        }
    }

    @Test
    fun missingCredentialFailsBeforeAnyRequest() = runTest {
        val disconnected = CloudflareRestClient(
            credentialProvider = { throw CloudflareOperationException.notConnected() },
            executor = Executor { it.run() },
            transport = transport,
        )
        val error = runCatching { disconnected.result(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones"))) }.exceptionOrNull()
        assertEquals(CloudflareOperationException.Kind.NOT_CONNECTED, (error as CloudflareOperationException).kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun pagesUploadTokenReplacesTheAccountCredential() = runTest {
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
    fun optionalProductClassificationMatchesIos() {
        assertTrue(isCloudflareOptionalProductUnavailable(CloudflareOperationException.forbidden("")))
        assertTrue(isCloudflareOptionalProductUnavailable(CloudflareOperationException.requestFailed(404, "")))
        assertTrue(isCloudflareOptionalProductUnavailable(CloudflareOperationException.api(listOf(CloudflareApiIssue(1, "Feature not enabled")))))
        assertFalse(isCloudflareOptionalProductUnavailable(CloudflareOperationException.requestFailed(500, "")))
        assertFalse(isCloudflareOptionalProductUnavailable(IllegalStateException()))
    }

    @Test
    fun displayTextAndFormattingFollowIos() {
        assertEquals("On", ProviderJsonValue.Bool(true).cloudflareDisplayText)
        assertEquals("Not set", ProviderJsonValue.Null.cloudflareDisplayText)
        assertEquals("a: 1, b c: x", ProviderJsonValue.from(mapOf("b_c" to "x", "a" to 1)).cloudflareDisplayText)
        assertEquals("1.5 KB", CloudflareFormat.bytes(1_500))
        assertEquals("999 bytes", CloudflareFormat.bytes(999))
        assertEquals("1.2K", CloudflareFormat.compact(1_234))
        assertEquals("3.4M", CloudflareFormat.compact(3_400_000))
        assertEquals("2024-01-02T03:04:05Z", CloudflareDates.parse("2024-01-02T03:04:05.123456Z")?.let(CloudflareDates::iso8601))
        assertEquals("2024-01-02T00:00:00Z", CloudflareDates.parse("2024-01-02")?.let(CloudflareDates::iso8601))
        assertNull(CloudflareDates.parse("yesterday"))
    }

    private suspend fun errorFor(zone: String): CloudflareOperationException =
        runCatching { client.result(CloudflareRestRequest(CloudflareHttpMethod.GET, listOf("zones", zone))) }
            .exceptionOrNull() as? CloudflareOperationException ?: throw AssertionError("Expected a Cloudflare error")

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }
}
