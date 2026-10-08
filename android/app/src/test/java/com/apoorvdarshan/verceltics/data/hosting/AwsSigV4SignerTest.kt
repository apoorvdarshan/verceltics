package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Reference vectors from the AWS Signature Version 4 test suite and the AWS General Reference
 * (credential `AKIDEXAMPLE` / `wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY`).
 */
class AwsSigV4SignerTest {
    private val secret = SecretValue.of("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY")
    private val suiteTime = Instant.parse("2015-08-30T12:36:00Z")
    private val emptyPayload = sha256Hex(ByteArray(0))

    @Test
    fun derivesTheDocumentedSigningKey() {
        val key = AwsSigV4Signer.signingKey(secret, "20120215", "us-east-1", "iam")
        assertEquals("f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d", key.toHex())
    }

    @Test
    fun getVanilla() {
        val signature = suiteSign("GET", "", listOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"))
        assertEquals("GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n$emptyPayload", signature.canonicalRequest)
        assertEquals(
            "AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n" +
                "bb579772317eb040ac9ed261061d46c1f17a8133879d6129b6e1c25292927e63",
            signature.stringToSign,
        )
        assertEquals("5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", signature.signature)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            signature.authorization,
        )
    }

    @Test
    fun getVanillaQueryOrderKeyCaseSortsParameters() {
        val query = AwsSigV4Signer.canonicalQuery(listOf("Param2" to "value2", "Param1" to "value1"))
        assertEquals("Param1=value1&Param2=value2", query)
        val signature = suiteSign("GET", query, listOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"))
        assertEquals("b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500", signature.signature)
    }

    @Test
    fun postVanilla() {
        val signature = suiteSign("POST", "", listOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"))
        assertEquals("5da7c1a2acd57cee7505fc6676e4e544621c30862966e37dddb68e92efbe5d6b", signature.signature)
    }

    @Test
    fun postFormUrlEncodedSignsContentTypeAndPayloadHash() {
        val signature = AwsSigV4Signer.sign(
            method = "POST",
            canonicalUri = "/",
            canonicalQuery = "",
            headers = listOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Host" to "example.amazonaws.com",
                "X-Amz-Date" to "20150830T123600Z",
            ),
            payloadSha256Hex = sha256Hex("Param1=value1".toByteArray(StandardCharsets.UTF_8)),
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = secret,
            region = "us-east-1",
            service = "service",
            instant = suiteTime,
        )
        assertEquals("ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a", signature.signature)
    }

    @Test
    fun iamListUsersGeneralReferenceExample() {
        val signature = AwsSigV4Signer.sign(
            method = "GET",
            canonicalUri = "/",
            canonicalQuery = AwsSigV4Signer.canonicalQuery(listOf("Action" to "ListUsers", "Version" to "2010-05-08")),
            headers = listOf(
                "content-type" to "application/x-www-form-urlencoded; charset=utf-8",
                "host" to "iam.amazonaws.com",
                "x-amz-date" to "20150830T123600Z",
            ),
            payloadSha256Hex = emptyPayload,
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = secret,
            region = "us-east-1",
            service = "iam",
            instant = suiteTime,
        )
        assertEquals(
            "f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59",
            sha256Hex(signature.canonicalRequest.toByteArray(StandardCharsets.UTF_8)),
        )
        assertEquals("5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7", signature.signature)
    }

    @Test
    fun headerValuesAreTrimmedAndInnerWhitespaceCollapsed() {
        val spaced = suiteSign(
            "GET",
            "",
            listOf("Host" to "  example.amazonaws.com  ", "X-Amz-Date" to "20150830T123600Z", "My-Header" to " a   b \t c "),
        )
        assertEquals(true, spaced.canonicalRequest.contains("my-header:a b c\n"))
        assertEquals("host;my-header;x-amz-date", spaced.signedHeaders)
    }

    @Test
    fun queryEncodingUsesOnlyRfc3986UnreservedCharacters() {
        // iOS ProviderAPIRequestEncodingTests.testAWSQueryEncodingUsesOnlyRFC3986UnreservedCharacters
        assertEquals("a%2Bb%20%2F%3F%3D%26~", AwsSigV4Signer.encode("a+b /?=&~"))
        assertEquals("%E2%9C%93", AwsSigV4Signer.encode("✓"))
        assertEquals(
            "maxResults=50&nextToken=a%2Bb%3D%3D",
            AwsSigV4Signer.canonicalQuery(listOf("nextToken" to "a+b==", "maxResults" to "50")),
        )
    }

    @Test
    fun canonicalUriDoubleEncodesEachSegmentForNonS3Services() {
        assertEquals("/", AwsSigV4Signer.canonicalUri(""))
        assertEquals("/apps", AwsSigV4Signer.canonicalUri("/apps"))
        assertEquals(
            "/apps/d1/branches/feature%252Flogin/jobs",
            AwsSigV4Signer.canonicalUri("/apps/d1/branches/feature%2Flogin/jobs"),
        )
    }

    @Test
    fun signingRejectsMissingHostOrMismatchedDate() {
        assertThrows(IllegalArgumentException::class.java) {
            suiteSign("GET", "", listOf("X-Amz-Date" to "20150830T123600Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            suiteSign("GET", "", listOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150831T000000Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            suiteSign("GET", "", listOf("Host" to "a", "host" to "b", "X-Amz-Date" to "20150830T123600Z"))
        }
    }

    @Test
    fun signatureRenderingNeverIncludesTheSecret() {
        val signature = suiteSign("GET", "", listOf("Host" to "example.amazonaws.com", "X-Amz-Date" to "20150830T123600Z"))
        assertFalse(signature.toString().contains("wJalrXUtnFEMI"))
        assertFalse(signature.authorization.contains("wJalrXUtnFEMI"))
        assertFalse(signature.canonicalRequest.contains("wJalrXUtnFEMI"))
    }

    @Test
    fun regionValidationMatchesIosAuthorityGuards() {
        listOf("us-east-1", "ap-southeast-2", "eu-central-1", "il-central-1", "mx-central-1").forEach {
            AwsSigV4Signer.requireStandardRegion(it)
        }
        // iOS testAmplifyEndpointRejectsRegionsThatCouldChangeAuthority
        listOf(
            "evil.example/",
            "us-east-1.evil.example",
            "us-east-1@evil.example",
            "us-east-1.amazonaws.com@evil.example/",
            "us_east_1",
            "US-EAST-1",
            "us-east-0",
            "us-east-01",
            "us-gov-west-1",
            "cn-north-1",
            "zz-east-1",
            "us-east-1\n",
        ).forEach { region ->
            assertThrows(region, IllegalArgumentException::class.java) { AwsSigV4Signer.requireStandardRegion(region) }
            assertThrows(region, IllegalArgumentException::class.java) { HostingEndpoint.amplify(region) }
        }
    }

    private fun suiteSign(method: String, query: String, headers: List<Pair<String, String>>) = AwsSigV4Signer.sign(
        method = method,
        canonicalUri = "/",
        canonicalQuery = query,
        headers = headers,
        payloadSha256Hex = emptyPayload,
        accessKeyId = "AKIDEXAMPLE",
        secretAccessKey = secret,
        region = "us-east-1",
        service = "service",
        instant = suiteTime,
    )
}
