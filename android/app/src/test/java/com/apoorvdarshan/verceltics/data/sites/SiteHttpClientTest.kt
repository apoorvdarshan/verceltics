package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.HttpResponse
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsRequest
import com.apoorvdarshan.verceltics.data.network.ProviderHttpsTransport
import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteHttpClientTest {
    private val url = URI("https://api.example.com/v1/items")

    @Test
    fun retryDelayMatchesIosScheduleAndRejectsNonFiniteHeaders() {
        assertEquals(0.5, SiteHttpClient.retryDelaySeconds("NaN", 0), 0.0)
        assertEquals(1.0, SiteHttpClient.retryDelaySeconds("infinity", 1), 0.0)
        assertEquals(8.0, SiteHttpClient.retryDelaySeconds("999", 0), 0.0)
        assertEquals(0.1, SiteHttpClient.retryDelaySeconds("0", 0), 0.0)
        assertEquals(2.0, SiteHttpClient.retryDelaySeconds(" 2 ", 3), 0.0)
        assertEquals(0.5, SiteHttpClient.retryDelaySeconds(null, -3), 0.0)
        assertEquals(8.0, SiteHttpClient.retryDelaySeconds(null, 4), 0.0)
        assertEquals(8.0, SiteHttpClient.retryDelaySeconds(null, 40), 0.0)
        assertEquals(4_000L, SiteHttpClient.retryDelayMillis(null, 3))
    }

    @Test
    fun transientStatusesRetryWithBackoffAndHonourRetryAfter() = runTest {
        var calls = 0
        val transport = FakeProviderTransport {
            calls += 1
            when (calls) {
                1 -> FakeResponse(503, json("message" to "busy"))
                2 -> FakeResponse(429, "{}", mapOf("Retry-After" to listOf("2")))
                else -> ok(json("ok" to true))
            }
        }
        val sleeps = ArrayList<Long>()
        val client = SiteHttpClient(transport, DirectExecutor) { sleeps += it }

        val result = client.requestJson(SiteHttpRequest(url))

        assertEquals(true, result["ok"]?.booleanValue)
        assertEquals(listOf(500L, 2_000L), sleeps)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun exhaustedRetriesSurfaceProviderMessageAndStatus() = runTest {
        val transport = FakeProviderTransport { FakeResponse(503, json("error" to mapOf("message" to "Service unavailable"))) }
        val sleeps = ArrayList<Long>()
        val error = runCatching {
            SiteHttpClient(transport, DirectExecutor) { sleeps += it }.requestJson(SiteHttpRequest(url))
        }.exceptionOrNull() as SiteServiceException

        assertEquals(SiteServiceException.Kind.REQUEST_FAILED, error.kind)
        assertEquals(503, error.statusCode)
        assertEquals("Request failed (HTTP 503): Service unavailable", error.message)
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(500L, 1_000L), sleeps)
    }

    @Test
    fun clientErrorsAreNotRetriedAndUnauthorizedIsDetectable() = runTest {
        val transport = FakeProviderTransport { FakeResponse(401, json("message" to "Invalid credentials")) }
        val error = runCatching {
            SiteHttpClient(transport, DirectExecutor) { error("no sleep") }.requestJson(SiteHttpRequest(url))
        }.exceptionOrNull() as SiteServiceException

        assertTrue(error.isUnauthorized)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun detailStyleUsesIosDetailCopyWithoutProviderText() = runTest {
        val transport = FakeProviderTransport { FakeResponse(404, json("message" to "missing")) }
        val error = runCatching {
            SiteHttpClient(transport, DirectExecutor).requestJson(SiteHttpRequest(url), maximumAttempts = 1, style = SiteErrorStyle.DETAIL)
        }.exceptionOrNull() as SiteServiceException
        assertEquals("The provider request failed (HTTP 404).", error.message)
    }

    @Test
    fun providerMessagesThatEchoACredentialAreDropped() = runTest {
        val transport = FakeProviderTransport { FakeResponse(400, json("message" to "Key super-secret-key is invalid")) }
        val error = runCatching {
            SiteHttpClient(transport, DirectExecutor).requestJson(
                SiteHttpRequest(url, bearerToken = SecretValue.of("super-secret-key")),
            )
        }.exceptionOrNull() as SiteServiceException
        assertEquals("Request failed (HTTP 400).", error.message)
        assertFalse(error.toString().contains("super-secret-key"))
    }

    @Test
    fun plainTextErrorBodiesAreTruncated() {
        val long = "x".repeat(400)
        assertEquals(300, SiteHttpClient.errorMessage(long.toByteArray()).length)
        assertEquals("first", SiteHttpClient.errorMessage(json("errors" to listOf(mapOf("message" to "first"))).toByteArray()))
        assertEquals("described", SiteHttpClient.errorMessage(json("error_description" to "described").toByteArray()))
    }

    @Test
    fun retryableNetworkErrorsRetryAndOthersFailFast() = runTest {
        val timeoutThenSuccess = ThrowingTransport(listOf(SocketTimeoutException("slow")), ok(json("ok" to 1)))
        val sleeps = ArrayList<Long>()
        val value = SiteHttpClient(timeoutThenSuccess, DirectExecutor) { sleeps += it }.requestJson(SiteHttpRequest(url))
        assertEquals(1.0, value["ok"]?.numberValue)
        assertEquals(listOf(500L), sleeps)

        val dns = ThrowingTransport(List(3) { UnknownHostException("dns") }, ok("{}"))
        val dnsError = runCatching {
            SiteHttpClient(dns, DirectExecutor) {}.requestJson(SiteHttpRequest(url))
        }.exceptionOrNull() as SiteServiceException
        assertEquals(SiteServiceException.Kind.NETWORK, dnsError.kind)
        assertEquals(3, dns.calls)

        val tls = ThrowingTransport(listOf(SSLHandshakeException("bad cert")), ok("{}"))
        runCatching { SiteHttpClient(tls, DirectExecutor) {}.requestJson(SiteHttpRequest(url)) }
        assertEquals(1, tls.calls)

        val tooLarge = ThrowingTransport(listOf(ResponseTooLargeException(10)), ok("{}"))
        val tooLargeError = runCatching { SiteHttpClient(tooLarge, DirectExecutor) {}.requestJson(SiteHttpRequest(url)) }
            .exceptionOrNull()
        assertEquals(1, tooLarge.calls)
        assertTrue(tooLargeError?.message!!.contains("size limit"))
    }

    @Test
    fun emptySuccessBodyIsAnEmptyObjectAndInvalidJsonIsADecodingError() = runTest {
        assertEquals(
            emptyMap<String, Any>(),
            SiteHttpClient(FakeProviderTransport { ok("") }, DirectExecutor).requestJson(SiteHttpRequest(url)).objectValue,
        )
        val error = runCatching {
            SiteHttpClient(FakeProviderTransport { ok("<html>") }, DirectExecutor).requestJson(SiteHttpRequest(url))
        }.exceptionOrNull() as SiteServiceException
        assertEquals(SiteServiceException.Kind.DECODING, error.kind)
        assertTrue(error.message!!.startsWith("Could not read the provider response"))
    }

    @Test
    fun formAndJsonBodiesAreRebuiltForEveryAttempt() = runTest {
        var calls = 0
        val transport = FakeProviderTransport {
            calls += 1
            if (calls == 1) FakeResponse(500) else ok("{}")
        }
        SiteHttpClient(transport, DirectExecutor) {}.requestJson(
            SiteHttpRequest(
                url,
                method = "POST",
                secretFormFields = listOf("api_key" to SecretValue.of("form-secret")),
                formFields = listOf("format" to "json", "offset" to "0"),
            ),
        )
        transport.requests.forEach { request ->
            assertEquals(mapOf("api_key" to "form-secret", "format" to "json", "offset" to "0"), request.form)
        }
        assertEquals(2, transport.requests.size)
    }

    private class ThrowingTransport(
        private val failures: List<IOException>,
        private val success: FakeResponse,
    ) : ProviderHttpsTransport {
        var calls = 0

        override fun newCall(request: ProviderHttpsRequest): CancelableCall<HttpResponse> = object : CancelableCall<HttpResponse> {
            override fun execute(): HttpResponse {
                val index = calls++
                failures.getOrNull(index)?.let { throw it }
                return HttpResponse(success.status, success.body.toByteArray(), success.headers)
            }

            override fun cancel() = Unit
        }
    }
}
