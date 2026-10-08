package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.network.ResponseTooLargeException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of iOS `PublicIPv4LookupTests`. */
class PublicIpv4LookupTest {
    @Test
    fun resolveReturnsCanonicalPublicIpv4AndUsesCredentialFreeRequest() {
        val transport = FakeRegistrarTransport { ok(" 008.008.004.004\n") }

        val address = PublicIpv4Lookup(transport).newResolveCall().execute()

        assertEquals("8.8.4.4", address)
        val request = transport.requests.single()
        assertEquals(SecureRegistrarHttpTransport.IPIFY_ORIGIN, request.origin)
        assertEquals("/", request.path)
        assertEquals("text/plain", request.accept)
        assertEquals("no-store", request.headers["Cache-Control"])
        assertTrue(request.secretHeaderNames.isEmpty())
        assertTrue(request.query.isEmpty())
        assertEquals(PublicIpv4Lookup.MAXIMUM_RESPONSE_BYTES, request.maximumResponseBytes)
        assertEquals(64, PublicIpv4Lookup.MAXIMUM_RESPONSE_BYTES)
    }

    @Test
    fun resolveRejectsNonSuccessResponse() {
        val transport = FakeRegistrarTransport { FakeRegistrarResponse(503, "temporarily unavailable") }

        val error = assertThrows(PublicIpv4LookupException::class.java) {
            PublicIpv4Lookup(transport).newResolveCall().execute()
        }

        assertEquals(PublicIpv4LookupFailure.REQUEST_FAILED, error.failure)
        assertEquals(503, error.statusCode)
        assertEquals("The public IP service returned HTTP 503.", error.message)
    }

    @Test
    fun resolveRejectsInvalidAndPrivateAddresses() {
        listOf("not-an-ip", "2001:4860:4860::8888", "192.168.1.5", "100.64.0.1").forEach { value ->
            val transport = FakeRegistrarTransport { ok(value) }
            val error = assertThrows(PublicIpv4LookupException::class.java) {
                PublicIpv4Lookup(transport).newResolveCall().execute()
            }
            assertEquals(value, PublicIpv4LookupFailure.INVALID_ADDRESS, error.failure)
        }
    }

    @Test
    fun resolvePreservesTransportFailures() {
        val offline = IOException("offline")
        val tooLarge = ResponseTooLargeException(PublicIpv4Lookup.MAXIMUM_RESPONSE_BYTES)
        listOf(offline, tooLarge).forEach { failure ->
            val transport = FakeRegistrarTransport { FakeRegistrarResponse(error = failure) }
            try {
                PublicIpv4Lookup(transport).newResolveCall().execute()
                fail("Expected the transport failure to propagate.")
            } catch (error: IOException) {
                assertSame(failure, error)
            }
        }
    }

    @Test
    fun secureTransportAcceptsTheSmallLookupLimitButRejectsLargerThanItsCap() {
        val transport = SecureRegistrarHttpTransport()
        val call = transport.newGetCall(
            RegistrarHttpRequest(
                origin = SecureRegistrarHttpTransport.IPIFY_ORIGIN,
                path = "/",
                accept = "text/plain",
                maximumResponseBytes = PublicIpv4Lookup.MAXIMUM_RESPONSE_BYTES,
            ),
        )
        call.cancel()
        assertThrows(IllegalArgumentException::class.java) {
            transport.newGetCall(
                RegistrarHttpRequest(
                    origin = SecureRegistrarHttpTransport.IPIFY_ORIGIN,
                    path = "/",
                    maximumResponseBytes = SecureRegistrarHttpTransport.DEFAULT_MAXIMUM_RESPONSE_BYTES + 1,
                ),
            )
        }
    }

    @Test
    fun publicIpv4ValidationRejectsNonRoutableAndReservedRanges() {
        assertEquals("1.1.1.1", PublicIpv4Lookup.normalizedPublicIpv4(" 1.1.1.1\n"))
        assertEquals("8.8.4.4", PublicIpv4Lookup.normalizedPublicIpv4("008.008.004.004"))
        listOf(
            "",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.999",
            "1..3.4",
            "1.2.3.-4",
            "0.1.2.3",
            "10.0.0.1",
            "127.0.0.1",
            "100.64.0.1",
            "100.127.255.255",
            "169.254.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "198.18.0.1",
            "198.19.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
            "2001:db8::1",
        ).forEach { assertNull(it, PublicIpv4Lookup.normalizedPublicIpv4(it)) }
        listOf("100.63.0.1", "100.128.0.1", "172.15.0.1", "172.32.0.1", "192.0.1.1", "198.20.0.1").forEach {
            assertEquals(it, PublicIpv4Lookup.normalizedPublicIpv4(it))
        }
    }
}
