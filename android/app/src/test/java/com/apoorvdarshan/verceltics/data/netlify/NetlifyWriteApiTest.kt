package com.apoorvdarshan.verceltics.data.netlify

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.hosting.FakeHostingTransport
import com.apoorvdarshan.verceltics.data.hosting.HostingApiException
import com.apoorvdarshan.verceltics.data.hosting.HostingAuth
import com.apoorvdarshan.verceltics.data.hosting.HostingFailureKind
import com.apoorvdarshan.verceltics.data.hosting.HostingHttpMethod
import com.apoorvdarshan.verceltics.data.hosting.bearerToken
import com.apoorvdarshan.verceltics.data.hosting.bodyText
import com.apoorvdarshan.verceltics.data.hosting.jsonResponse
import com.apoorvdarshan.verceltics.data.hosting.prepare
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetlifyWriteApiTest {
    @Test
    fun redeployRequestMatchesIosBuildsEndpoint() {
        val request = NetlifyRedeployRequest.build(SecretValue.of("nfp_token"), "  3f9c-site_id.1  ")

        assertEquals(HostingHttpMethod.POST, request.method)
        assertEquals("api.netlify.com", request.endpoint.host)
        assertEquals("/api/v1/sites/3f9c-site_id.1/builds", request.encodedPath)
        assertNull(request.encodedQuery)
        assertEquals("{}", request.bodyText())
        assertEquals("application/json", request.contentType)
        assertTrue(request.auth is HostingAuth.Bearer)
        assertEquals("nfp_token", request.bearerToken())

        val prepared = request.prepare(Instant.EPOCH)
        assertEquals("https://api.netlify.com/api/v1/sites/3f9c-site_id.1/builds", prepared.uri.toString())
        assertEquals("Bearer nfp_token", prepared.header("Authorization"))
        assertEquals("application/json", prepared.header("Content-Type"))
        assertFalse(request.toString().contains("nfp_token"))
    }

    @Test
    fun redeployRejectsSiteIdsThatCouldEscapeTheSitePath() {
        listOf("", "   ", "../user", "a/b", "site?x=1", "site#frag", "%2e%2e", "x".repeat(257)).forEach { siteId ->
            assertThrows(IllegalArgumentException::class.java) {
                NetlifyRedeployRequest.build(SecretValue.of("token"), siteId)
            }
        }
    }

    @Test
    fun redeploySendsExactlyOneWriteAndAcceptsAnyTwoHundredStatus() = runBlocking {
        val transport = FakeHostingTransport { jsonResponse("""{"id":"build-1"}""", status = 201) }

        NetlifyWriteApi(transport).redeploy(SecretValue.of("token"), "site-1")

        assertEquals(1, transport.requests.size)
        assertEquals(listOf("/api/v1/sites/site-1/builds"), transport.paths())
    }

    @Test
    fun redeployFailuresUseAppAuthoredMessagesOnly() = runBlocking {
        val cases = mapOf(
            401 to HostingFailureKind.AUTHENTICATION,
            403 to HostingFailureKind.PERMISSION,
            404 to HostingFailureKind.NOT_FOUND,
            422 to HostingFailureKind.CONFIGURATION,
            429 to HostingFailureKind.RATE_LIMITED,
            503 to HostingFailureKind.TEMPORARY,
            418 to HostingFailureKind.TEMPORARY,
        )
        cases.forEach { (status, kind) ->
            val transport = FakeHostingTransport { jsonResponse("""{"message":"leaked nfp_token"}""", status = status) }
            val error = runCatching { NetlifyWriteApi(transport).redeploy(SecretValue.of("nfp_token"), "site-1") }
                .exceptionOrNull() as HostingApiException
            assertEquals(kind, error.failure.kind)
            assertEquals(status, error.failure.statusCode)
            assertFalse(error.failure.message.contains("leaked"))
        }

        val offline = FakeHostingTransport { throw IOException("socket closed") }
        val network = runCatching { NetlifyWriteApi(offline).redeploy(SecretValue.of("t"), "site-1") }
            .exceptionOrNull() as HostingApiException
        assertEquals(HostingFailureKind.NETWORK, network.failure.kind)
    }

    @Test
    fun netlifyLinksMatchIos() {
        assertEquals("https://app.netlify.com/", NetlifyLinks.DASHBOARD_URL)
        assertEquals("https://app.netlify.com/user/applications#personal-access-tokens", NetlifyLinks.CREDENTIALS_URL)
        assertEquals("https://app.netlify.com/sites/my-site/overview", NetlifyLinks.siteDashboardUrl("my-site"))
        assertEquals("https://app.netlify.com/sites/a%2Fb%20c/overview", NetlifyLinks.siteDashboardUrl("a/b c"))
        assertEquals(NetlifyLinks.DASHBOARD_URL, NetlifyLinks.siteDashboardUrl("  "))
    }
}
