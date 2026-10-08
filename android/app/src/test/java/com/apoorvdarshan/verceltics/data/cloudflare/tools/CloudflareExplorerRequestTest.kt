package com.apoorvdarshan.verceltics.data.cloudflare.tools

import java.net.URI
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareExplorerRequestTest {
    private val builder = CloudflareExplorerRequestBuilder

    @Test
    fun normalizePathMatchesIosPrefixRules() {
        assertEquals("/accounts", builder.normalizePath("/accounts"))
        assertEquals("/accounts", builder.normalizePath("  accounts  "))
        assertEquals("/zones/abc", builder.normalizePath("/client/v4/zones/abc"))
        assertEquals("/zones/abc", builder.normalizePath("client/v4/zones/abc"))
        assertEquals("/", builder.normalizePath("/client/v4"))
        assertEquals("/", builder.normalizePath("client/v4"))
    }

    @Test
    fun normalizePathRejectsAbsoluteUrlsQueriesFragmentsAndTraversal() {
        assertInvalid("Enter a relative Cloudflare API path.") { builder.normalizePath("   ") }
        val relativeMessage = "Use a relative /client/v4 path and put query parameters in the query fields."
        assertInvalid(relativeMessage) { builder.normalizePath("https://evil.example/client/v4/zones") }
        assertInvalid(relativeMessage) { builder.normalizePath("/zones?page=2") }
        assertInvalid(relativeMessage) { builder.normalizePath("/zones#fragment") }
        assertInvalid("Parent path components are not allowed.") { builder.normalizePath("/accounts/../user") }
        assertInvalid("Parent path components are not allowed.") { builder.normalizePath("/accounts/%2E%2E/user") }
        assertInvalid("Parent path components are not allowed.") { builder.normalizePath("/accounts/./user") }
        assertInvalid("The Cloudflare API path contains an invalid percent-encoding.") { builder.normalizePath("/zones/%zz") }
        assertInvalid("The Cloudflare API path contains an invalid percent-encoding.") { builder.normalizePath("/zones/abc%") }
        assertInvalid("The Cloudflare API path is invalid.") { builder.normalizePath("/zones\\abc") }
        assertInvalid("The Cloudflare API path is invalid.") { builder.normalizePath("/zones/a\tb") }
    }

    @Test
    fun normalizePathEncodesUnsafeCharactersAndKeepsValidEscapes() {
        assertEquals("/kv/my%20key", builder.normalizePath("/kv/my key"))
        assertEquals("/kv/caf%C3%A9", builder.normalizePath("/kv/café"))
        assertEquals("/kv/a%2Fb", builder.normalizePath("/kv/a%2fb"))
        assertEquals("/accounts/x/@scope:value", builder.normalizePath("/accounts/x/@scope:value"))
        assertEquals("/client/v4/zones/abc", builder.displayPath("zones/abc"))
    }

    @Test
    fun queryParsingFollowsIosSemantics() {
        assertEquals(
            listOf("account.id" to "abc", "page" to "1", "per_page" to "50"),
            builder.parseQuery("per_page=50\n page = 1 &account.id=abc\n\n"),
        )
        assertEquals(listOf("name" to "a b+c", "q" to "x"), builder.parseQuery("q=1\nq=x\nname=a%20b+c"))
        assertEquals(listOf("broken" to "%zz"), builder.parseQuery("broken=%zz"))
        assertEquals(listOf("empty" to ""), builder.parseQuery("empty="))
        assertInvalid("Query parameter “flag” must use key=value format.") { builder.parseQuery("flag") }
        assertInvalid("Query parameter “=value” must use key=value format.") { builder.parseQuery("=value") }
    }

    @Test
    fun headerParsingAcceptsColonOrEqualsAndProtectsCredentials() {
        assertEquals(
            mapOf("If-Match" to "etag-1", "Accept" to "application/json", "X-Custom" to "a=b"),
            builder.parseHeaders("If-Match: etag-1\nAccept=application/json\nX-Custom: a=b"),
        )
        assertInvalid("Request header “Missing” must use Name: value format.") { builder.parseHeaders("Missing") }
        listOf(
            "Authorization: Bearer stolen",
            "authorization: x",
            "Content-Type: text/plain",
            "Content-Length: 10",
            "Host: evil.example",
            "X-Auth-Key: key",
            "X-Auth-Email: me@example.com",
            "Accept-Encoding: gzip",
            "Cookie: a=b",
        ).forEach { line ->
            val name = line.substringBefore(':').substringBefore('=')
            assertInvalid("The custom header $name is not allowed.") { builder.parseHeaders(line) }
        }
        assertInvalid("The custom header Bad Header is not allowed.") { builder.parseHeaders("Bad Header: x") }
        assertTrue(builder.isProtectedHeader(" AUTHORIZATION "))
        assertFalse(builder.isProtectedHeader("If-None-Match"))
    }

    @Test
    fun bodyDecodingHandlesUtf8Base64AndJsonValidation() {
        assertNull(builder.decodeBody(CloudflareHttpMethod.GET, "{\"a\":1}", CloudflareBodyEncoding.UTF8, "application/json"))
        assertNull(builder.decodeBody(CloudflareHttpMethod.POST, "  \n", CloudflareBodyEncoding.UTF8, "application/json"))
        assertArrayEquals(
            "{\"a\":1}".toByteArray(),
            builder.decodeBody(CloudflareHttpMethod.POST, "{\"a\":1}", CloudflareBodyEncoding.UTF8, "application/json"),
        )
        assertArrayEquals(
            "hello world".toByteArray(),
            builder.decodeBody(CloudflareHttpMethod.PUT, "aGVsbG8g\nd29y bGQ=", CloudflareBodyEncoding.BASE64, "application/octet-stream"),
        )
        assertInvalid("The request body is not valid Base64 data.") {
            builder.decodeBody(CloudflareHttpMethod.PUT, "not base64!", CloudflareBodyEncoding.BASE64, null)
        }
        val error = assertThrows(CloudflareToolsException::class.java) {
            builder.decodeBody(CloudflareHttpMethod.POST, "{\"a\":", CloudflareBodyEncoding.UTF8, "application/json; charset=utf-8")
        }
        assertTrue(error.message!!.startsWith("The request body is not valid JSON:"))
        // JSON fragments are allowed, like iOS `.fragmentsAllowed`.
        assertArrayEquals("\"x\"".toByteArray(), builder.decodeBody(CloudflareHttpMethod.POST, "\"x\"", CloudflareBodyEncoding.UTF8, "application/merge-patch+json"))
        // Non-JSON content types are sent as typed.
        assertArrayEquals("{oops".toByteArray(), builder.decodeBody(CloudflareHttpMethod.POST, "{oops", CloudflareBodyEncoding.UTF8, "text/plain"))
    }

    @Test
    fun writeDetectionRequiresConfirmationForEveryWriteExceptVerifiedReadOnlyGraphQL() {
        val get = CloudflareExplorerDraft(method = CloudflareHttpMethod.GET, path = "/zones")
        assertFalse(builder.requiresWriteConfirmation(get))
        CloudflareHttpMethod.entries.filter { it.isMutation }.forEach { method ->
            assertTrue(method.name, builder.requiresWriteConfirmation(get.copy(method = method)))
        }
        val graphQL = CloudflareExplorerDraft(
            method = CloudflareHttpMethod.POST,
            path = "/graphql",
            bodyText = "{\"query\":\"query { viewer { zones { zoneTag } } }\"}",
            readOnlyGraphQL = true,
        )
        assertFalse(builder.requiresWriteConfirmation(graphQL))
        assertFalse(builder.requiresWriteConfirmation(graphQL.copy(path = "/client/v4/graphql")))
        assertTrue(builder.requiresWriteConfirmation(graphQL.copy(readOnlyGraphQL = false)))
        assertTrue(builder.requiresWriteConfirmation(graphQL.copy(bodyText = "{\"query\":\"Mutation { deleteThing }\"}")))
        assertTrue(builder.requiresWriteConfirmation(graphQL.copy(bodyText = "{\"query\":")))
        assertTrue(builder.requiresWriteConfirmation(graphQL.copy(path = "/accounts")))
        assertTrue(builder.requiresWriteConfirmation(graphQL, attachedBody = byteArrayOf(1)))
        // A word that merely contains "mutation" is still read-only.
        assertFalse(builder.requiresWriteConfirmation(graphQL.copy(bodyText = "{\"query\":\"query { mutations_total }\"}")))
    }

    @Test
    fun buildRequiresConfirmationForTheExactPath() {
        val draft = CloudflareExplorerDraft(method = CloudflareHttpMethod.DELETE, path = "zones/abc")
        val missing = assertThrows(CloudflareToolsException::class.java) { builder.build(draft, null) }
        assertEquals(CloudflareToolsFailureKind.CONFIRMATION_REQUIRED, missing.kind)
        assertEquals("Confirm the change to zones/abc before continuing.", missing.message)
        assertThrows(CloudflareToolsException::class.java) {
            builder.build(draft, CloudflareMutationConfirmation("/zones/other"))
        }
        assertEquals("/client/v4/zones/abc", builder.build(draft, CloudflareMutationConfirmation("zones/abc")).uri.rawPath)
        assertEquals("/client/v4/zones/abc", builder.build(draft, CloudflareMutationConfirmation("/zones/abc")).uri.rawPath)
    }

    @Test
    fun buildPinsRequestsToCloudflareAndEncodesQuery() {
        val request = builder.build(
            CloudflareExplorerDraft(
                method = CloudflareHttpMethod.GET,
                path = "//evil.example/zones",
                queryText = "name=a b&account.id=abc",
                headerText = "If-None-Match: \"etag\"",
                bodyText = "{\"ignored\":true}",
            ),
            confirmation = null,
        )
        assertEquals("https", request.uri.scheme)
        assertEquals("api.cloudflare.com", request.uri.host)
        assertEquals("/client/v4//evil.example/zones", request.uri.rawPath)
        assertEquals("account.id=abc&name=a%20b", request.uri.rawQuery)
        assertEquals(mapOf("If-None-Match" to "\"etag\""), request.headers)
        assertNull(request.bodyCopy())
        assertNull(request.contentType)
        assertFalse(request.toString().contains("account.id"))
    }

    @Test
    fun buildCarriesBodiesAndContentTypes() {
        val post = builder.build(
            CloudflareExplorerDraft(
                method = CloudflareHttpMethod.POST,
                path = "/zones",
                bodyText = "{\"name\":\"example.com\"}",
                contentType = " application/json ",
            ),
            CloudflareMutationConfirmation("/zones"),
        )
        assertEquals("application/json", post.contentType)
        assertEquals("{\"name\":\"example.com\"}", String(post.bodyCopy()!!, StandardCharsets.UTF_8))
        assertFalse(post.toString().contains("example.com"))

        val emptyDelete = builder.build(
            CloudflareExplorerDraft(method = CloudflareHttpMethod.DELETE, path = "/zones/abc"),
            CloudflareMutationConfirmation("/zones/abc"),
        )
        assertNull(emptyDelete.bodyCopy())
        assertNull(emptyDelete.contentType)

        val attached = builder.build(
            CloudflareExplorerDraft(method = CloudflareHttpMethod.PUT, path = "/x", bodyText = "ignored", contentType = "application/octet-stream"),
            CloudflareMutationConfirmation("/x"),
            attachedBody = byteArrayOf(0, 1, 2),
        )
        assertArrayEquals(byteArrayOf(0, 1, 2), attached.bodyCopy())

        assertInvalid("The Content-Type header is invalid.") {
            builder.build(
                CloudflareExplorerDraft(method = CloudflareHttpMethod.POST, path = "/x", contentType = "text/plain\r\nX-Evil: 1"),
                CloudflareMutationConfirmation("/x"),
            )
        }
    }

    @Test
    fun readOnlyGraphQLSkipsConfirmationButMutationsDoNot() {
        val readOnly = CloudflareExplorerDraft(
            method = CloudflareHttpMethod.POST,
            path = "/graphql",
            bodyText = "{\"query\":\"{ viewer { accounts { accountTag } } }\"}",
            readOnlyGraphQL = true,
        )
        assertEquals("/client/v4/graphql", builder.build(readOnly, null).uri.rawPath)
        assertThrows(CloudflareToolsException::class.java) {
            builder.build(readOnly.copy(bodyText = "{\"query\":\"mutation { x }\"}"), null)
        }
    }

    @Test
    fun hostPinningRejectsEveryOtherOrigin() {
        assertTrue(builder.isPinnedCloudflareUri(URI("https://api.cloudflare.com/client/v4/zones")))
        assertTrue(builder.isPinnedCloudflareUri(URI("https://api.cloudflare.com:443/client/v4/")))
        listOf(
            "http://api.cloudflare.com/client/v4/zones",
            "https://api.cloudflare.com.evil.example/client/v4/zones",
            "https://evil.example/client/v4/zones",
            "https://api.cloudflare.com:8443/client/v4/zones",
            "https://user@api.cloudflare.com/client/v4/zones",
            "https://api.cloudflare.com/client/v3/zones",
            "https://api.cloudflare.com/zones",
            "https://api.cloudflare.com/client/v4/zones#x",
        ).forEach { assertFalse(it, builder.isPinnedCloudflareUri(URI(it))) }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareToolsHttpRequest(CloudflareHttpMethod.GET, URI("https://evil.example/client/v4/x"), emptyMap(), null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CloudflareToolsHttpRequest(
                CloudflareHttpMethod.GET,
                URI("https://api.cloudflare.com/client/v4/x"),
                emptyMap(),
                byteArrayOf(1),
                null,
            )
        }
        assertThrows(CloudflareToolsException::class.java) {
            CloudflareToolsHttpRequest(
                CloudflareHttpMethod.GET,
                URI("https://api.cloudflare.com/client/v4/x"),
                mapOf("Authorization" to "Bearer x"),
                null,
                null,
            )
        }
    }

    @Test
    fun percentHelpersRoundTrip() {
        assertEquals("a%2Fb%20c", builder.encodePathSegment("a/b c"))
        assertEquals("a%2Fb%20c%3A%40", builder.encodeQueryComponent("a/b c:@"))
        assertEquals("a b+c/é", builder.percentDecodeOrNull("a%20b+c%2F%C3%A9"))
        assertNull(builder.percentDecodeOrNull("%C3"))
        assertNull(builder.percentDecodeOrNull("%G0"))
    }

    @Test
    fun draftFromPresetCopiesEveryEditableField() {
        val preset = CloudflareApiPreset(
            id = "x",
            title = "T",
            summary = "S",
            method = CloudflareHttpMethod.PATCH,
            path = "/zones/1",
            query = "a=1",
            headers = "If-Match: e",
            body = "{}",
            contentType = "application/merge-patch+json",
            bodyEncoding = CloudflareBodyEncoding.BASE64,
            readOnlyGraphQL = true,
        )
        assertEquals(
            CloudflareExplorerDraft(
                method = CloudflareHttpMethod.PATCH,
                path = "/zones/1",
                queryText = "a=1",
                headerText = "If-Match: e",
                bodyText = "{}",
                contentType = "application/merge-patch+json",
                bodyEncoding = CloudflareBodyEncoding.BASE64,
                readOnlyGraphQL = true,
            ),
            CloudflareExplorerDraft.from(preset),
        )
    }

    private fun assertInvalid(message: String, block: () -> Unit) {
        val error = assertThrows(CloudflareToolsException::class.java) { block() }
        assertEquals(CloudflareToolsFailureKind.INVALID_REQUEST, error.kind)
        assertEquals(message, error.message)
    }
}
