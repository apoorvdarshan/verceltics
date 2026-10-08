package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderRawRequest
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegistrarRawApiTest {
    /** Records explorer requests with their materialized URI (secrets included, test-only). */
    private class RawTransport(private val respond: (RegistrarRawHttpRequest) -> HttpResponse) : RegistrarHttpTransport {
        val requests = mutableListOf<RegistrarRawHttpRequest>()
        val uris = mutableListOf<String>()
        private val secure = SecureRegistrarHttpTransport()

        override fun newGetCall(request: RegistrarHttpRequest): CancelableCall<HttpResponse> = error("Unused")

        override fun newRawCall(request: RegistrarRawHttpRequest): CancelableCall<HttpResponse> {
            requests += request
            uris += secure.prepareRawUri(request).toString()
            return object : CancelableCall<HttpResponse> {
                override fun execute(): HttpResponse = respond(request)

                override fun cancel() = Unit
            }
        }
    }

    private fun ok(body: String = "{}", status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
        HttpResponse(status, body.toByteArray(StandardCharsets.UTF_8), headers)

    private fun credentials(
        provider: RegistrarProvider,
        key: String = "primary-key-123",
        secret: String? = "secondary-456",
        username: String = "studio",
        clientIp: String = "8.8.8.8",
    ) = RegistrarCredentials.fromInput(
        provider = provider,
        apiKey = SecretValue.of(key),
        apiSecret = secret?.let(SecretValue::of),
        username = username,
        clientIp = clientIp,
    )

    @Test
    fun namecheapAppendsAuthenticationAfterTheTypedCommandAndDropsTypedCredentials() {
        val transport = RawTransport { ok("<ApiResponse Status=\"OK\"/>") }
        val response = RegistrarRawApi(transport).newRawCall(
            credentials(RegistrarProvider.NAMECHEAP),
            ProviderRawRequest("GET", "/xml.response?Command=namecheap.domains.getInfo&DomainName=a.com&ApiKey=typed&clientip=1.1.1.1"),
        ).execute()
        assertEquals(
            "https://api.namecheap.com/xml.response?Command=namecheap.domains.getInfo&DomainName=a.com" +
                "&ApiUser=studio&UserName=studio&ClientIp=8.8.8.8&ApiKey=primary-key-123",
            transport.uris.single(),
        )
        val request = transport.requests.single()
        assertEquals(listOf("ApiKey"), request.secretQueryParameters.map { it.first })
        assertNull(request.bodyCopy())
        assertNull(request.contentType)
        assertEquals("<ApiResponse Status=\"OK\"/>", response.body)
        assertFalse(request.toString().contains("primary-key-123"))
    }

    @Test
    fun nameSiloIsGetOnlyAndAddsVersionTypeAndKey() {
        val transport = RawTransport { ok() }
        val api = RegistrarRawApi(transport)
        val nameSilo = credentials(RegistrarProvider.NAME_SILO, secret = null)
        try {
            api.newRawCall(nameSilo, ProviderRawRequest("POST", "/api/registerDomain")).execute()
            fail("Expected rejection")
        } catch (error: RegistrarApiException) {
            assertEquals("NameSilo requires every API operation to use GET.", error.message)
        }
        api.newRawCall(nameSilo, ProviderRawRequest("GET", "/api/listDomains?key=typed")).execute()
        assertEquals("https://www.namesilo.com/api/listDomains?version=1&type=json&key=primary-key-123", transport.uris.single())
    }

    @Test
    fun dynadotUsesItsQueryKey() {
        val transport = RawTransport { ok() }
        RegistrarRawApi(transport).newRawCall(
            credentials(RegistrarProvider.DYNADOT, secret = null),
            ProviderRawRequest("GET", "/api3.json?command=list_domain"),
        ).execute()
        assertEquals("https://api.dynadot.com/api3.json?command=list_domain&key=primary-key-123", transport.uris.single())
    }

    @Test
    fun headerAuthenticatedRegistrarsAttachCredentialsPrivately() {
        val cases = mapOf(
            RegistrarProvider.NAME_DOT_COM to mapOf(
                "Authorization" to "Basic " + Base64.getEncoder().encodeToString("studio:primary-key-123".toByteArray()),
            ),
            RegistrarProvider.PORKBUN to mapOf("X-API-Key" to "primary-key-123", "X-Secret-API-Key" to "secondary-456"),
            RegistrarProvider.SPACESHIP to mapOf("X-API-Key" to "primary-key-123", "X-API-Secret" to "secondary-456"),
            RegistrarProvider.GANDI to mapOf("Authorization" to "Bearer primary-key-123"),
            RegistrarProvider.GO_DADDY to mapOf("Authorization" to "sso-key primary-key-123:secondary-456"),
        )
        cases.forEach { (provider, expectedHeaders) ->
            val transport = RawTransport { ok() }
            RegistrarRawApi(transport).newRawCall(
                credentials(provider, secret = if (provider.requiresSecret) "secondary-456" else null),
                ProviderRawRequest("GET", "/v1/domains"),
            ).execute()
            val request = transport.requests.single()
            assertEquals(provider.id, expectedHeaders, request.secretHeaders.associate { (name, secret) -> name to secret.use { it } })
            assertTrue(provider.id, request.secretQueryParameters.isEmpty())
            assertTrue(provider.id, transport.uris.single().startsWith(provider.origin.trimEnd('/') + provider.apiPathPrefix + "/v1/domains"))
        }
    }

    @Test
    fun protectedHeadersAreDroppedAndAcceptCanBeOverridden() {
        val transport = RawTransport { ok() }
        RegistrarRawApi(transport).newRawCall(
            credentials(RegistrarProvider.GANDI, secret = null),
            ProviderRawRequest(
                "GET",
                "/v5/domain/domains",
                headers = mapOf(
                    "Authorization" to "Bearer attacker",
                    "X-API-Key" to "attacker",
                    "Host" to "evil.example",
                    "Content-Type" to "text/plain",
                    "Accept" to "application/xml",
                    "X-Request-Id" to "trace-1",
                ),
            ),
        ).execute()
        val request = transport.requests.single()
        assertEquals(mapOf("X-Request-Id" to "trace-1"), request.headers)
        assertEquals("application/xml", request.accept)
        assertEquals("Bearer primary-key-123", request.secretHeaders.single().second.use { it })
        assertTrue(RegistrarRawApi.isProtectedHeader("X-SECRET-API-KEY"))
        assertFalse(RegistrarRawApi.isProtectedHeader("Accept"))
    }

    @Test
    fun porkbunBodiesUseJsonByDefault() {
        val transport = RawTransport { ok() }
        RegistrarRawApi(transport).newRawCall(
            credentials(RegistrarProvider.PORKBUN),
            ProviderRawRequest("POST", "/domain/listAll", body = "{}"),
        ).execute()
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertArrayEquals("{}".toByteArray(), request.bodyCopy())
        assertEquals("application/json", request.contentType)
        assertEquals("https://api.porkbun.com/api/json/v3/domain/listAll", transport.uris.single())
    }

    @Test
    fun getBodiesAndForeignPathsAreRejected() {
        val api = RegistrarRawApi(RawTransport { ok() })
        val goDaddy = credentials(RegistrarProvider.GO_DADDY)
        listOf(
            ProviderRawRequest("GET", "/v1/domains", body = "{}") to
                "GET requests can't include a body on Android. Clear the request body or choose another method.",
            ProviderRawRequest("GET", "https://evil.example/v1") to "Enter a registrar-relative path beginning with /.",
            ProviderRawRequest("GET", "//evil.example/v1") to "Enter a registrar-relative path beginning with /.",
            ProviderRawRequest("GET", "/v1/../../x") to "Provider path traversal is not allowed.",
        ).forEach { (request, message) ->
            try {
                api.newRawCall(goDaddy, request).execute()
                fail("Expected ${request.path} to be rejected")
            } catch (error: RegistrarApiException) {
                assertEquals(message, error.message)
                assertEquals(RegistrarFailureKind.CONFIGURATION, error.kind)
            }
        }
    }

    @Test
    fun httpErrorsAreResponsesAndEchoedSecretsAreRedacted() {
        val basic = Base64.getEncoder().encodeToString("studio:primary-key-123".toByteArray())
        val transport = RawTransport {
            ok("{\"message\":\"bad key primary-key-123 / $basic\"}", status = 401, headers = mapOf("WWW-Authenticate" to listOf("Basic")))
        }
        val response = RegistrarRawApi(transport).newRawCall(
            credentials(RegistrarProvider.NAME_DOT_COM, secret = null),
            ProviderRawRequest("GET", "/core/v1/hello"),
        ).execute()
        assertEquals(401, response.statusCode)
        assertEquals("{\"message\":\"bad key <redacted> / <redacted>\"}", response.body)
        assertEquals(listOf("WWW-Authenticate" to "Basic"), response.headers)
    }

    @Test
    fun transportFailuresBecomeRegistrarErrors() {
        val api = RegistrarRawApi(RawTransport { throw IOException("reset") })
        try {
            api.newRawCall(credentials(RegistrarProvider.GANDI, secret = null), ProviderRawRequest("GET", "/v5/domain/domains")).execute()
            fail("Expected a network error")
        } catch (error: RegistrarApiException) {
            assertEquals(RegistrarFailureKind.NETWORK, error.kind)
            assertEquals("Gandi could not be reached. Check your connection and try again.", error.message)
        }
    }

    @Test
    fun likelyWriteDetectionMatchesIos() {
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.GANDI, "POST", "/v5/domain/domains"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.PORKBUN, "POST", "/ping"))
        assertFalse(RegistrarRawApi.isLikelyWrite(RegistrarProvider.GANDI, "GET", "/v5/domain/domains/delete"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAMECHEAP, "GET", "/xml.response?Command=namecheap.domains.dns.setHosts"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAMECHEAP, "GET", "/xml.response?Command=namecheap.domains.create"))
        assertFalse(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAMECHEAP, "GET", "/xml.response?Command=namecheap.domains.getList"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.DYNADOT, "GET", "/api3.json?command=register&domain=a.com"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.DYNADOT, "GET", "/api3.json?command=set_ns"))
        assertFalse(RegistrarRawApi.isLikelyWrite(RegistrarProvider.DYNADOT, "GET", "/api3.json?command=list_domain"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAME_SILO, "GET", "/api/dnsDeleteRecord?domain=a.com"))
        assertTrue(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAME_SILO, "GET", "/api/registerDomain"))
        assertFalse(RegistrarRawApi.isLikelyWrite(RegistrarProvider.NAME_SILO, "GET", "/api/listDomains"))
        assertFalse(RegistrarRawApi.isLikelyWrite(RegistrarProvider.GO_DADDY, "HEAD", "/v1/domains"))
    }

    @Test
    fun suggestedPathsAndDefaultsMatchIos() {
        val expected = mapOf(
            RegistrarProvider.NAME_DOT_COM to ("/core/v1/domains?perPage=250" to "/core/v1/domains/a.com"),
            RegistrarProvider.NAMECHEAP to (
                "/xml.response?Command=namecheap.domains.getList&ListType=ALL&PageSize=100" to
                    "/xml.response?Command=namecheap.domains.getInfo&DomainName=a.com"
                ),
            RegistrarProvider.PORKBUN to ("/domain/listAll" to "/dns/retrieve/a.com"),
            RegistrarProvider.SPACESHIP to ("/v1/domains?take=100&skip=0" to "/v1/domains/a.com"),
            RegistrarProvider.DYNADOT to ("/api3.json?command=list_domain" to "/api3.json?command=domain_info&domain=a.com"),
            RegistrarProvider.NAME_SILO to ("/api/listDomains" to "/api/getDomainInfo?domain=a.com"),
            RegistrarProvider.GANDI to ("/v5/domain/domains?per_page=100&page=1" to "/v5/domain/domains/a.com"),
            RegistrarProvider.GO_DADDY to ("/v1/domains?limit=1000&includes=nameServers" to "/v1/domains/a.com"),
        )
        assertEquals(RegistrarProvider.entries.toSet(), expected.keys)
        expected.forEach { (provider, paths) ->
            assertEquals(provider.id, paths.first, RegistrarRawApi.suggestedPath(provider))
            assertEquals(provider.id, paths.second, RegistrarRawApi.suggestedPath(provider, "a.com"))
        }
        assertEquals("POST", RegistrarRawApi.defaultMethod(RegistrarProvider.PORKBUN))
        assertEquals("{}", RegistrarRawApi.defaultBody(RegistrarProvider.PORKBUN))
        assertEquals("GET", RegistrarRawApi.defaultMethod(RegistrarProvider.GANDI))
        assertEquals("", RegistrarRawApi.defaultBody(RegistrarProvider.GANDI))
    }

    @Test
    fun secureTransportOnlyBuildsUrisOnRegistrarHosts() {
        val transport = SecureRegistrarHttpTransport()
        val uri = transport.prepareRawUri(
            RegistrarRawHttpRequest(
                origin = RegistrarProvider.GANDI.origin,
                method = "GET",
                encodedPath = "/v5/domain/@evil.example",
                encodedQuery = "page=1",
                queryParameters = listOf("a b" to "c&d"),
            ),
        )
        assertEquals("https://api.gandi.net/v5/domain/@evil.example?page=1&a%20b=c%26d", uri.toString())
        assertEquals("api.gandi.net", uri.host)
        listOf(
            RegistrarRawHttpRequest(origin = "https://evil.example/", method = "GET", encodedPath = "/x", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "GET", encodedPath = "//evil.example", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "GET", encodedPath = "/a b", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "GET", encodedPath = "/a?b", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "GET", encodedPath = "/v5/%2e%2E/admin", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "GET", encodedPath = "/v5/../admin", encodedQuery = null),
        ).forEach { request ->
            try {
                transport.prepareRawUri(request)
                fail("Expected ${request.encodedPath} on ${request.origin} to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        listOf(
            RegistrarRawHttpRequest(origin = SecureRegistrarHttpTransport.IPIFY_ORIGIN, method = "GET", encodedPath = "/", encodedQuery = null),
            RegistrarRawHttpRequest(origin = RegistrarProvider.GANDI.origin, method = "TRACE", encodedPath = "/", encodedQuery = null),
            RegistrarRawHttpRequest(
                origin = RegistrarProvider.GANDI.origin,
                method = "GET",
                encodedPath = "/",
                encodedQuery = null,
                headers = mapOf("Authorization" to "x"),
            ),
            RegistrarRawHttpRequest(
                origin = RegistrarProvider.GANDI.origin,
                method = "GET",
                encodedPath = "/",
                encodedQuery = null,
                body = byteArrayOf(1),
            ),
        ).forEach { request ->
            try {
                transport.newRawCall(request)
                fail("Expected $request to be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
