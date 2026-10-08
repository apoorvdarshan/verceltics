package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Result of signing one request. [authorization] is the complete `Authorization` header value. */
internal class AwsSignature(
    val amzDate: String,
    val signedHeaders: String,
    val signature: String,
    val authorization: String,
    val canonicalRequest: String,
    val stringToSign: String,
) {
    override fun toString(): String = "AwsSignature(amzDate=$amzDate, signedHeaders=$signedHeaders)"
}

/**
 * AWS Signature Version 4 (`AWS4-HMAC-SHA256`), implemented with `javax.crypto` only.
 *
 * Port of iOS `HostingProviderAPI.awsSignedRequest`, with one deliberate correction: AWS requires
 * each path segment of the canonical URI to be URI-encoded twice for every service except S3, so
 * Amplify branch names such as `feature/login` sign correctly. Simple paths are unaffected.
 */
internal object AwsSigV4Signer {
    const val ALGORITHM: String = "AWS4-HMAC-SHA256"

    private val STANDARD_REGION = Regex("(?:af|ap|ca|eu|il|me|mx|sa|us)-[a-z]+-[1-9][0-9]*")
    private val AMZ_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /** iOS `awsStandardRegionPattern`: commercial partitions only; rejects authority tricks. */
    fun requireStandardRegion(region: String) {
        require(region.length <= 63 && STANDARD_REGION.matches(region)) {
            "Enter a valid AWS region such as us-east-1."
        }
    }

    fun amzDate(instant: Instant): String = AMZ_DATE.format(instant)

    /** RFC 3986 unreserved-only encoding (`ProviderAPIRequestEncoding.awsQueryComponent`). */
    fun encode(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val output = StringBuilder(bytes.size * 3)
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (unsigned < 0x80 && character in UNRESERVED) {
                output.append(character)
            } else {
                output.append('%').append("%02X".format(unsigned))
            }
        }
        return output.toString()
    }

    /** Canonical URI from an already-encoded request path: every segment is encoded again. */
    fun canonicalUri(encodedPath: String): String {
        if (encodedPath.isEmpty() || encodedPath == "/") return "/"
        return encodedPath.split('/').joinToString("/") { segment -> encode(segment) }
    }

    fun canonicalQuery(parameters: List<Pair<String, String>>): String = parameters
        .map { (name, value) -> encode(name) to encode(value) }
        .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        .joinToString("&") { (name, value) -> "$name=$value" }

    /**
     * Signs a request. [headers] must contain `host` and `x-amz-date` (any case); names are
     * lower-cased, values trimmed with inner whitespace runs collapsed, then sorted by name.
     */
    fun sign(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: List<Pair<String, String>>,
        payloadSha256Hex: String,
        accessKeyId: String,
        secretAccessKey: SecretValue,
        region: String,
        service: String,
        instant: Instant,
    ): AwsSignature {
        val amzDate = amzDate(instant)
        val dateStamp = amzDate.substring(0, 8)
        val normalizedHeaders = headers
            .map { (name, value) -> name.trim().lowercase(Locale.ROOT) to value.trim().replace(WHITESPACE_RUN, " ") }
            .sortedBy { it.first }
        require(normalizedHeaders.map { it.first }.toSet().size == normalizedHeaders.size) {
            "Duplicate signed header."
        }
        require(normalizedHeaders.any { it.first == "host" }) { "The host header must be signed." }
        require(normalizedHeaders.any { it.first == "x-amz-date" && it.second == amzDate }) {
            "The x-amz-date header must match the signing time."
        }
        val canonicalHeaders = normalizedHeaders.joinToString("") { (name, value) -> "$name:$value\n" }
        val signedHeaders = normalizedHeaders.joinToString(";") { it.first }
        val canonicalRequest = listOf(
            method.uppercase(Locale.ROOT),
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadSha256Hex,
        ).joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8)),
        ).joinToString("\n")
        val signingKey = signingKey(secretAccessKey, dateStamp, region, service)
        val signature = try {
            hmac(signingKey, stringToSign).toHex()
        } finally {
            signingKey.fill(0)
        }
        return AwsSignature(
            amzDate = amzDate,
            signedHeaders = signedHeaders,
            signature = signature,
            authorization = "$ALGORITHM Credential=$accessKeyId/$scope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature",
            canonicalRequest = canonicalRequest,
            stringToSign = stringToSign,
        )
    }

    /** kSigning = HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), service), "aws4_request"). */
    fun signingKey(secretAccessKey: SecretValue, dateStamp: String, region: String, service: String): ByteArray {
        val initialKey = secretAccessKey.use { secret -> "AWS4$secret".toByteArray(StandardCharsets.UTF_8) }
        val dateKey = try {
            hmac(initialKey, dateStamp)
        } finally {
            initialKey.fill(0)
        }
        val regionKey = try {
            hmac(dateKey, region)
        } finally {
            dateKey.fill(0)
        }
        val serviceKey = try {
            hmac(regionKey, service)
        } finally {
            regionKey.fill(0)
        }
        return try {
            hmac(serviceKey, "aws4_request")
        } finally {
            serviceKey.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private val WHITESPACE_RUN = Regex("\\s+")
}
