package com.apoorvdarshan.verceltics.data.apicatalog

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderRawRequestTest {
    @Test
    fun protectedHeadersAreDroppedCaseInsensitivelyAndValuesTrimmed() {
        val protected = setOf("authorization", "host", "content-type", "x-api-key")
        val headers = ProviderRequestSecurity.validatedHeaders(
            linkedMapOf(
                "Authorization" to "Bearer stolen",
                " HOST " to "evil.example",
                "Content-Type" to "text/plain",
                "x-API-key" to "typed",
                " X-Request-Id " to "  trace-1  ",
                "Accept" to "application/xml",
            ),
        ) { it in protected }
        assertEquals(linkedMapOf("X-Request-Id" to "trace-1", "Accept" to "application/xml"), headers)
    }

    @Test
    fun headerInjectionAndInvalidNamesAreRejected() {
        listOf(
            mapOf("X-Test" to "a\r\nInjected: yes"),
            mapOf("X-\nTest" to "a"),
            mapOf("" to "a"),
            mapOf("Bad Header" to "a"),
            mapOf("X-Long" to "a".repeat(1_025)),
        ).forEach { headers ->
            try {
                ProviderRequestSecurity.validatedHeaders(headers) { false }
                fail("Expected $headers to be rejected")
            } catch (_: ProviderApiRequestException) {
            }
        }
    }

    @Test
    fun contentTypeValidationMatchesIos() {
        assertNull(ProviderRequestSecurity.validatedContentType(null))
        assertNull(ProviderRequestSecurity.validatedContentType("   "))
        assertEquals("application/json", ProviderRequestSecurity.validatedContentType(" application/json "))
        try {
            ProviderRequestSecurity.validatedContentType("text/plain\r\nX-Evil: 1")
            fail("Expected a rejected content type")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Header names and values cannot contain line breaks.", error.message)
        }
    }

    @Test
    fun headerJsonRoundTripsAndRejectsNonStringValues() {
        assertEquals(emptyMap<String, String>(), ProviderRequestSecurity.parseHeaderJson("  "))
        assertEquals(mapOf("A" to "1", "B" to "two"), ProviderRequestSecurity.parseHeaderJson("{\"A\":\"1\",\"B\":\"two\"}"))
        listOf("[]", "{\"A\":1}", "{\"A\":null}", "not json", "\"text\"").forEach { text ->
            try {
                ProviderRequestSecurity.parseHeaderJson(text)
                fail("Expected $text to be rejected")
            } catch (error: ProviderApiRequestException) {
                assertEquals("Custom headers must be a JSON object whose values are strings.", error.message)
            }
        }
        assertEquals("", ProviderRequestSecurity.headerJson(emptyMap()))
        assertEquals("{\n  \"A\": \"1\",\n  \"b\": \"2\"\n}", ProviderRequestSecurity.headerJson(mapOf("b" to "2", "A" to "1")))
    }

    @Test
    fun rawPathsMustStayProviderRelative() {
        listOf(
            "sites" to "Enter a provider-relative path beginning with /.",
            "https://evil.example/sites" to "Enter a provider-relative path beginning with /.",
            "//evil.example/sites" to "Enter a provider-relative path beginning with /.",
            "/sites#frag" to "Remove the # fragment from the request path.",
            "/sites/\u0000x" to "The request path cannot contain control characters.",
            "/sites/../account" to "Provider path traversal is not allowed.",
            "/sites/%2e%2E/account" to "Provider path traversal is not allowed.",
            "/./sites" to "Provider path traversal is not allowed.",
        ).forEach { (path, message) ->
            try {
                ProviderRawPath.parse(path)
                fail("Expected $path to be rejected")
            } catch (error: ProviderApiRequestException) {
                assertEquals(path, message, error.message)
            }
        }
        try {
            ProviderRawPath.parse("domains", "Enter a registrar-relative path beginning with /.")
            fail("Expected rejection")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Enter a registrar-relative path beginning with /.", error.message)
        }
    }

    @Test
    fun illegalUrlCharactersArePercentEncodedAndValidEscapesKept() {
        val target = ProviderRawPath.parse("  /sites/my site/{id}/✓/%2F/a%zz?q=hello world&x=%41&flag  ")
        assertEquals("/sites/my%20site/%7Bid%7D/%E2%9C%93/%2F/a%25zz", target.encodedPath)
        assertEquals("q=hello%20world&x=%41&flag", target.encodedQuery)
        assertEquals(listOf("q" to "hello world", "x" to "A", "flag" to ""), target.queryParameters)
        assertEquals(listOf("sites", "my site", "{id}", "✓", "/", "a%zz"), target.decodedSegments)
    }

    @Test
    fun plusStaysLiteralAndEmptyQueriesDisappear() {
        assertEquals(listOf("q" to "a+b"), ProviderRawPath.parse("/search?q=a+b").queryParameters)
        assertNull(ProviderRawPath.parse("/search?").encodedQuery)
        assertEquals("/", ProviderRawPath.parse("/").encodedPath)
    }

    @Test
    fun tooManyQueryParametersAreRejected() {
        val query = (1..101).joinToString("&") { "p$it=1" }
        try {
            ProviderRawPath.parse("/x?$query")
            fail("Expected rejection")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Use at most 100 query parameters.", error.message)
        }
    }

    @Test
    fun strictPathReencodesEverySegmentLikeAwsSdks() {
        assertEquals("/apps/a%20b/branches/feature%2Flogin", ProviderRawPath.strictPath(ProviderRawPath.parse("/apps/a b/branches/feature%2Flogin")))
        assertEquals("/apps/x%3Ay", ProviderRawPath.strictPath(ProviderRawPath.parse("/apps/x:y")))
    }

    @Test
    fun redactionReplacesEveryEchoedSecretLongestFirst() {
        val redacted = ProviderRequestSecurity.redact(
            "token=abcdef123456 key=abcdef short=abc",
            listOf("abcdef", "abcdef123456", "abc"),
        )
        assertEquals("token=<redacted> key=<redacted> short=abc", redacted)
    }

    @Test
    fun wireResponsesDecodeTextOrFallBackToBase64() {
        val text = ProviderRawResponse.fromWire(
            404,
            mapOf(
                "content-type" to listOf("application/json"),
                "X-Echo" to listOf("secret-token-1", "b"),
                "X-Android-Received-Millis" to listOf("1"),
                "" to listOf("HTTP/1.1 404"),
            ),
            "{\"token\":\"secret-token-1\"}".toByteArray(StandardCharsets.UTF_8),
            listOf("secret-token-1"),
        )
        assertEquals(404, text.statusCode)
        assertFalse(text.isSuccess)
        assertFalse(text.isBinary)
        assertEquals("{\"token\":\"<redacted>\"}", text.body)
        assertEquals(listOf("content-type" to "application/json", "X-Echo" to "<redacted>, b"), text.headers)
        assertEquals("application/json", text.header("Content-Type"))

        val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 1)
        val binary = ProviderRawResponse.fromWire(200, emptyMap(), bytes, emptyList())
        assertTrue(binary.isBinary)
        assertTrue(binary.isSuccess)
        assertEquals(4, binary.byteCount)
        assertArrayEquals(bytes, Base64.getDecoder().decode(binary.body))
    }

    @Test
    fun requestsKeepEitherATextOrABinaryBody() {
        val text = ProviderRawRequest(" post ", "/x", body = "{}")
        assertEquals("POST", text.method)
        assertArrayEquals("{}".toByteArray(), text.bodyBytes())
        val binary = ProviderRawRequest("PUT", "/x", binaryBody = byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), binary.bodyBytes())
        assertNull(ProviderRawRequest("GET", "/x").bodyBytes())
        assertFalse(ProviderRawRequest("GET", "/x").toString().contains("/x"))
        try {
            ProviderRawRequest("POST", "/x", body = "a", binaryBody = byteArrayOf(1))
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun methodRulesMatchIosAndAndroid() {
        assertEquals(listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), ProviderApiMethods.ALL)
        assertFalse(ProviderApiMethods.isWrite("get"))
        assertFalse(ProviderApiMethods.isWrite("HEAD"))
        assertFalse(ProviderApiMethods.isWrite("OPTIONS"))
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { assertTrue(it, ProviderApiMethods.isWrite(it)) }
        assertTrue(ProviderApiMethods.bodyIsOptional("DELETE"))
        assertFalse(ProviderApiMethods.bodyIsOptional("PATCH"))
        assertFalse(ProviderApiMethods.allowsBody("GET"))
        assertFalse(ProviderApiMethods.allowsBody("HEAD"))
        assertTrue(ProviderApiMethods.allowsBody("DELETE"))
        assertEquals("PATCH", ProviderApiMethods.requireSupported(" patch "))
        try {
            ProviderApiMethods.requireSupported("TRACE")
            fail("Expected rejection")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.", error.message)
        }
    }

    @Test
    fun graphQlClassifierOnlyTrustsProvableQueries() {
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"query { me { id name email } }\"}"))
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"{ me { id } }\"}"))
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"query Q(\$id: String!) { project(id: \$id) { mutationCount } }\",\"variables\":{}}"))
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"# mutation in a comment\\nquery { me { id } }\"}"))
        assertTrue(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"query { search(text: \\\"mutation {\\\") { id } }\"}"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"mutation { deploy { id } }\"}"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"query A { me { id } } mutation B { x }\"}"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"subscription { logs }\"}"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("not json"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("{}"))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery(""))
        assertFalse(GraphQLRequestClassifier.isReadOnlyQuery("{\"query\":\"query { me { id }\"}"))
    }
}
