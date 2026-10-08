package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HostingHttpRequestTest {
    private val instant = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun fixedOriginsAndPathSegmentsAreEncodedExactlyOnce() {
        val request = HostingHttpRequest(
            endpoint = HostingEndpoint.HEROKU,
            method = HostingHttpMethod.GET,
            pathSegments = listOf("apps", "my app/../x?y#z", "dynos"),
            query = listOf("org_slug" to "a b&c=d"),
            auth = HostingAuth.None,
        )
        assertEquals("/apps/my%20app%2F..%2Fx%3Fy%23z/dynos", request.encodedPath)
        assertEquals("org_slug=a%20b%26c%3Dd", request.encodedQuery)
        val uri = request.uri()
        assertEquals("https", uri.scheme)
        assertEquals("api.heroku.com", uri.host)
        assertEquals(-1, uri.port)
        assertNull(uri.userInfo)
        assertEquals("https://api.heroku.com/apps/my%20app%2F..%2Fx%3Fy%23z/dynos?org_slug=a%20b%26c%3Dd", uri.toASCIIString())
    }

    @Test
    fun everyProviderUsesItsDocumentedApiOrigin() {
        assertEquals("backboard.railway.com", HostingEndpoint.RAILWAY.host)
        assertEquals("api.render.com", HostingEndpoint.RENDER.host)
        assertEquals("api.digitalocean.com", HostingEndpoint.DIGITAL_OCEAN.host)
        assertEquals("api.heroku.com", HostingEndpoint.HEROKU.host)
        assertEquals("api.machines.dev", HostingEndpoint.FLY.host)
        assertEquals("firebasehosting.googleapis.com", HostingEndpoint.FIREBASE.host)
        assertEquals("openidconnect.googleapis.com", HostingEndpoint.GOOGLE_OPENID.host)
        // iOS testAmplifyEndpointUsesExactAWSHostForValidRegion
        val amplify = HostingEndpoint.amplify("ap-southeast-2")
        assertEquals("amplify.ap-southeast-2.amazonaws.com", amplify.host)
        val uri = HostingHttpRequest(amplify, HostingHttpMethod.GET, listOf("apps"), listOf("maxResults" to "1"), auth = HostingAuth.None).uri()
        assertEquals("https://amplify.ap-southeast-2.amazonaws.com/apps?maxResults=1", uri.toASCIIString())
    }

    @Test
    fun requestShapeIsValidatedBeforeAnythingIsSent() {
        fun request(
            segments: List<String> = listOf("apps"),
            headers: Map<String, String> = emptyMap(),
            method: HostingHttpMethod = HostingHttpMethod.GET,
            body: ByteArray? = null,
        ) = HostingHttpRequest(HostingEndpoint.RENDER, method, segments, headers = headers, body = body, auth = HostingAuth.None)

        assertThrows(IllegalArgumentException::class.java) { request(segments = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { request(segments = listOf("apps", "..")) }
        assertThrows(IllegalArgumentException::class.java) { request(segments = listOf("apps", "")) }
        listOf("Authorization", "authorization", "Host", "Project-Access-Token", "Content-Type", "X-Amz-Date", "Cookie")
            .forEach { name ->
                assertThrows(name, IllegalArgumentException::class.java) { request(headers = mapOf(name to "x")) }
            }
        assertThrows(IllegalArgumentException::class.java) { request(headers = mapOf("Range" to "id ..\r\nX-Evil: 1")) }
        assertThrows(IllegalArgumentException::class.java) { request(headers = mapOf("Bad Header" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { request(body = "{}".toByteArray()) }
        request(headers = mapOf("Range" to "id ..; max=200"), method = HostingHttpMethod.POST, body = "{}".toByteArray())
    }

    @Test
    fun bearerAndRailwayProjectTokensUseTheirDocumentedHeaders() {
        val bearer = HostingHttpRequest(
            HostingEndpoint.RENDER,
            HostingHttpMethod.POST,
            listOf("v1", "services", "srv-1", "deploys"),
            body = "{}".toByteArray(),
            auth = HostingAuth.Bearer(SecretValue.of("render-secret")),
        ).prepare(instant)
        assertEquals("Bearer render-secret", bearer.header("Authorization"))
        assertEquals("application/json", bearer.header("Content-Type"))
        assertEquals("application/json", bearer.header("Accept"))
        assertEquals("identity", bearer.header("Accept-Encoding"))
        assertEquals("{}", bearer.body?.toString(StandardCharsets.UTF_8))
        assertFalse(bearer.toString().contains("render-secret"))

        val project = HostingHttpRequest(
            HostingEndpoint.RAILWAY,
            HostingHttpMethod.POST,
            listOf("graphql", "v2"),
            body = "{}".toByteArray(),
            auth = HostingAuth.RailwayProjectToken(SecretValue.of("project-secret")),
        ).prepare(instant)
        assertEquals("project-secret", project.header("Project-Access-Token"))
        assertNull(project.header("Authorization"))

        val heroku = HostingHttpRequest(
            HostingEndpoint.HEROKU,
            HostingHttpMethod.GET,
            listOf("apps"),
            headers = mapOf("Accept" to "application/vnd.heroku+json; version=3", "Range" to "id ..; max=200; order=asc"),
            auth = HostingAuth.Bearer(SecretValue.of("heroku-secret")),
        ).prepare(instant)
        assertEquals("application/vnd.heroku+json; version=3", heroku.header("Accept"))
        assertEquals(1, heroku.headers.count { it.first.equals("Accept", ignoreCase = true) })
        assertEquals("id ..; max=200; order=asc", heroku.header("Range"))
        assertNull(heroku.header("Content-Type"))
    }

    @Test
    fun amplifyRequestsAreSignedWithSigV4ForTheirRegion() {
        val prepared = amplifyRequest(
            method = HostingHttpMethod.GET,
            segments = listOf("apps"),
            query = listOf("maxResults" to "1"),
            body = null,
            sessionToken = null,
        ).prepare(instant)

        val canonical = listOf(
            "GET",
            "/apps",
            "maxResults=1",
            "content-type:application/json\nhost:amplify.us-east-1.amazonaws.com\nx-amz-date:20261008T120000Z\n",
            "content-type;host;x-amz-date",
            sha256(ByteArray(0)),
        ).joinToString("\n")
        val expectedSignature = referenceSignature(canonical, "20261008T120000Z", "us-east-1")
        assertEquals("20261008T120000Z", prepared.header("X-Amz-Date"))
        assertEquals("application/json", prepared.header("Content-Type"))
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261008/us-east-1/amplify/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, Signature=$expectedSignature",
            prepared.header("Authorization"),
        )
        assertNull(prepared.header("X-Amz-Security-Token"))
        assertFalse(prepared.toString().contains(SECRET))
    }

    @Test
    fun amplifySessionTokensAreSignedAndBranchPathsDoubleEncoded() {
        val body = """{"jobType":"RELEASE"}"""
        val prepared = amplifyRequest(
            method = HostingHttpMethod.POST,
            segments = listOf("apps", "d1", "branches", "feature/login", "jobs"),
            query = emptyList(),
            body = body.toByteArray(),
            sessionToken = "session-token-value",
        ).prepare(instant)

        assertEquals("/apps/d1/branches/feature%2Flogin/jobs", prepared.uri.rawPath)
        val canonical = listOf(
            "POST",
            "/apps/d1/branches/feature%252Flogin/jobs",
            "",
            "content-type:application/json\nhost:amplify.us-east-1.amazonaws.com\nx-amz-date:20261008T120000Z\n" +
                "x-amz-security-token:session-token-value\n",
            "content-type;host;x-amz-date;x-amz-security-token",
            sha256(body.toByteArray()),
        ).joinToString("\n")
        assertEquals("session-token-value", prepared.header("X-Amz-Security-Token"))
        assertTrue(prepared.header("Authorization")!!.endsWith("Signature=" + referenceSignature(canonical, "20261008T120000Z", "us-east-1")))
    }

    @Test
    fun awsCredentialsCannotSignForAnotherRegionsEndpoint() {
        val mismatched = HostingHttpRequest(
            HostingEndpoint.amplify("eu-west-1"),
            HostingHttpMethod.GET,
            listOf("apps"),
            auth = HostingAuth.AwsSigV4("AKIAIOSFODNN7EXAMPLE", SecretValue.of(SECRET), null, "us-east-1", "amplify"),
        )
        assertThrows(IllegalStateException::class.java) { mismatched.prepare(instant) }
    }

    @Test
    fun requestAndAuthRenderingIsRedacted() {
        val request = HostingHttpRequest(
            HostingEndpoint.FLY,
            HostingHttpMethod.POST,
            listOf("v1", "apps", "x", "machines", "m", "restart"),
            body = "{\"secret\":\"body-secret\"}".toByteArray(),
            auth = HostingAuth.Bearer(SecretValue.of("fly-secret")),
        )
        val rendered = request.toString() + request.auth.toString()
        assertFalse(rendered.contains("fly-secret"))
        assertFalse(rendered.contains("body-secret"))
    }

    private fun amplifyRequest(
        method: HostingHttpMethod,
        segments: List<String>,
        query: List<Pair<String, String>>,
        body: ByteArray?,
        sessionToken: String?,
    ) = HostingHttpRequest(
        endpoint = HostingEndpoint.amplify("us-east-1"),
        method = method,
        pathSegments = segments,
        query = query,
        body = body,
        auth = HostingAuth.AwsSigV4(
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = SecretValue.of(SECRET),
            sessionToken = sessionToken?.let(SecretValue::of),
            region = "us-east-1",
            service = "amplify",
        ),
    )

    /** Independent SigV4 computation (plain javax.crypto) used to check the transport wiring. */
    private fun referenceSignature(canonicalRequest: String, amzDate: String, region: String): String {
        val date = amzDate.substring(0, 8)
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$date/$region/amplify/aws4_request\n${sha256(canonicalRequest.toByteArray())}"
        var key = "AWS4$SECRET".toByteArray()
        listOf(date, region, "amplify", "aws4_request").forEach { key = hmac(key, it) }
        return hmac(key, stringToSign).joinToString("") { "%02x".format(it) }
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    }
}
