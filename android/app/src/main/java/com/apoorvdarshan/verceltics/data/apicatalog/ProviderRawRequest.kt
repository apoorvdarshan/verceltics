package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.HostingJson
import com.apoorvdarshan.verceltics.data.hosting.HostingResponseFormatException
import com.apoorvdarshan.verceltics.data.hosting.JsonObject
import com.apoorvdarshan.verceltics.data.hosting.JsonString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * One Complete API request typed into an explorer (iOS `rawRequest` arguments). The path is
 * provider-relative; the provider adapter attaches authentication and fixes the API host.
 * The body is either UTF-8 text or a binary payload (file or multipart upload), never both.
 */
class ProviderRawRequest(
    method: String,
    val path: String,
    val body: String? = null,
    binaryBody: ByteArray? = null,
    headers: Map<String, String> = emptyMap(),
    val contentType: String? = null,
) {
    val method: String = method.trim().uppercase(Locale.ROOT)
    val headers: Map<String, String> = headers.toMap()
    private val storedBinaryBody = binaryBody?.copyOf()

    init {
        require(body == null || binaryBody == null) { "Use either a text or a binary request body." }
    }

    val hasBody: Boolean
        get() = body != null || storedBinaryBody != null

    /** The wire bytes for the body, or null when the request has none. */
    fun bodyBytes(): ByteArray? = storedBinaryBody?.copyOf() ?: body?.toByteArray(StandardCharsets.UTF_8)

    override fun toString(): String =
        "ProviderRawRequest(method=$method, path=<redacted>, headerNames=${headers.keys}, " +
            "body=${if (hasBody) "<redacted>" else "none"})"
}

/** The full HTTP response shown by the explorer (iOS `HostingRawResponse`/`RegistrarRawResponse`). */
data class ProviderRawResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    /** UTF-8 text, or Base64 when [isBinary] (iOS falls back to `base64EncodedString`). */
    val body: String,
    val isBinary: Boolean = false,
    val byteCount: Int = body.length,
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299

    fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String =
        "ProviderRawResponse(statusCode=$statusCode, headerNames=${headers.map { it.first }}, bytes=$byteCount)"

    companion object {
        /** Builds a response from wire bytes, redacting any [secrets] the provider echoed back. */
        fun fromWire(
            statusCode: Int,
            headers: Map<String, List<String>>,
            body: ByteArray,
            secrets: List<String>,
        ): ProviderRawResponse {
            val text = decodeUtf8OrNull(body)
            val safeHeaders = headers.entries
                // Android's HTTP stack adds synthetic X-Android-* timing headers the provider never sent.
                .filter { it.key.isNotBlank() && !it.key.lowercase(Locale.ROOT).startsWith("x-android-") }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .map { (name, values) -> name to ProviderRequestSecurity.redact(values.joinToString(", "), secrets) }
            return ProviderRawResponse(
                statusCode = statusCode,
                headers = safeHeaders,
                body = if (text != null) ProviderRequestSecurity.redact(text, secrets) else Base64.getEncoder().encodeToString(body),
                isBinary = text == null,
                byteCount = body.size,
            )
        }

        private fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/** A user-facing explorer validation failure (bad path, header JSON, body/method mismatch…). */
class ProviderApiRequestException(message: String) : IllegalArgumentException(message)

/** The seven methods every explorer offers (iOS method picker). */
object ProviderApiMethods {
    val ALL: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

    private val READS = setOf("GET", "HEAD", "OPTIONS")

    /** iOS `HostingAPIExplorerView.isWrite`. */
    fun isWrite(method: String): Boolean = method.uppercase(Locale.ROOT) !in READS

    /** iOS `bodyIsOptional`: these methods start without a body editor. */
    fun bodyIsOptional(method: String): Boolean =
        method.uppercase(Locale.ROOT) in setOf("GET", "DELETE", "HEAD", "OPTIONS")

    /** Android's HTTP stack cannot send a body with GET or HEAD. */
    fun allowsBody(method: String): Boolean = method.uppercase(Locale.ROOT) !in setOf("GET", "HEAD")

    fun requireSupported(method: String): String {
        val normalized = method.trim().uppercase(Locale.ROOT)
        if (normalized !in ALL) throw ProviderApiRequestException("Use GET, POST, PUT, PATCH, DELETE, HEAD, or OPTIONS.")
        return normalized
    }
}

/** iOS `ProviderRequestSecurity` header and content-type rules plus Android-only helpers. */
object ProviderRequestSecurity {
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")
    private const val MAXIMUM_HEADER_VALUE_CHARACTERS = 1_024
    private const val MINIMUM_REDACTED_SECRET_CHARACTERS = 6

    /**
     * Trims every custom header, rejects line breaks, and silently drops headers the provider
     * transport controls ([isProtected]), exactly like iOS `validatedHeaders`.
     */
    fun validatedHeaders(headers: Map<String, String>, isProtected: (String) -> Boolean): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for ((rawName, rawValue) in headers) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name.isEmpty() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                throw ProviderApiRequestException("Header names and values cannot contain line breaks.")
            }
            if (isProtected(name.lowercase(Locale.ROOT))) continue
            if (!HEADER_NAME.matches(name)) throw ProviderApiRequestException("“$name” is not a valid HTTP header name.")
            if (value.length > MAXIMUM_HEADER_VALUE_CHARACTERS || value.any { it == '\u0000' }) {
                throw ProviderApiRequestException("The $name header value is too long or invalid.")
            }
            result[name] = value
        }
        return result
    }

    /** iOS `validatedContentType`: trimmed, no line breaks, empty means "use the default". */
    fun validatedContentType(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            throw ProviderApiRequestException("Header names and values cannot contain line breaks.")
        }
        if (trimmed.length > MAXIMUM_HEADER_VALUE_CHARACTERS) throw ProviderApiRequestException("The content type is too long.")
        return trimmed.ifEmpty { null }
    }

    /** iOS `parseHeaders`: the "Custom headers · JSON object" editor. */
    fun parseHeaderJson(text: String): Map<String, String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyMap()
        val invalid = ProviderApiRequestException("Custom headers must be a JSON object whose values are strings.")
        val value = try {
            HostingJson.parse(trimmed)
        } catch (_: HostingResponseFormatException) {
            throw invalid
        }
        val members = (value as? JsonObject)?.members ?: throw invalid
        return members.mapValues { (_, member) -> (member as? JsonString)?.value ?: throw invalid }
    }

    /** iOS `headerJSON`: pretty, key-sorted JSON (empty string for no headers). */
    fun headerJson(headers: Map<String, String>): String =
        if (headers.isEmpty()) "" else ProviderApiJson.pretty(JsonObject(headers.mapValues { JsonString(it.value) }))

    /** Replaces every echoed credential with `<redacted>` before a response reaches the screen. */
    fun redact(text: String, secrets: List<String>): String {
        var result = text
        secrets.asSequence()
            .filter { it.length >= MINIMUM_REDACTED_SECRET_CHARACTERS }
            .distinct()
            .sortedByDescending(String::length)
            .forEach { secret -> if (secret in result) result = result.replace(secret, "<redacted>") }
        return result
    }
}

/** A provider-relative request target after normalization. */
class ProviderRawTarget(
    /** Absolute path, percent-encoded, starting with exactly one `/`. */
    val encodedPath: String,
    /** The query as typed, with any characters illegal in a URL percent-encoded; null if none. */
    val encodedQuery: String?,
) {
    /** Decoded query pairs, in order. `+` stays a literal plus (iOS `URLComponents` semantics). */
    val queryParameters: List<Pair<String, String>>
        get() = encodedQuery.orEmpty().split('&').filter(String::isNotEmpty).map { item ->
            val separator = item.indexOf('=')
            if (separator < 0) {
                ProviderRawPath.percentDecode(item) to ""
            } else {
                ProviderRawPath.percentDecode(item.substring(0, separator)) to ProviderRawPath.percentDecode(item.substring(separator + 1))
            }
        }

    /** Decoded path segments (no leading empty segment). */
    val decodedSegments: List<String>
        get() = encodedPath.split('/').drop(1).map(ProviderRawPath::percentDecode)

    override fun toString(): String = "ProviderRawTarget(path=$encodedPath, hasQuery=${encodedQuery != null})"
}

/**
 * Parses an explorer path such as `/sites/abc/deploys?per_page=10`. It must begin with exactly one
 * `/`, may not carry a fragment, control characters or dot segments, and characters that are not
 * legal in a URL are percent-encoded (valid `%XX` escapes are kept). The result can only ever be
 * appended to a provider's fixed origin, so it can never change the host.
 */
object ProviderRawPath {
    const val MAXIMUM_CHARACTERS: Int = 8_192
    const val MAXIMUM_QUERY_PARAMETERS: Int = 100
    private const val PATH_CHARACTERS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/"
    private const val QUERY_CHARACTERS = "$PATH_CHARACTERS?"
    private const val HEX = "0123456789ABCDEF"

    fun parse(path: String, relativePathMessage: String = "Enter a provider-relative path beginning with /."): ProviderRawTarget {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) throw ProviderApiRequestException(relativePathMessage)
        if (trimmed.length > MAXIMUM_CHARACTERS) throw ProviderApiRequestException("The request path is too long.")
        if (trimmed.any(Char::isISOControl)) throw ProviderApiRequestException("The request path cannot contain control characters.")
        if ('#' in trimmed) throw ProviderApiRequestException("Remove the # fragment from the request path.")
        val separator = trimmed.indexOf('?')
        val rawPath = if (separator < 0) trimmed else trimmed.substring(0, separator)
        val rawQuery = if (separator < 0) null else trimmed.substring(separator + 1)
        val encodedPath = encodeIllegal(rawPath, PATH_CHARACTERS)
        if (encodedPath.split('/').any { percentDecode(it) == "." || percentDecode(it) == ".." }) {
            throw ProviderApiRequestException("Provider path traversal is not allowed.")
        }
        val encodedQuery = rawQuery?.let { encodeIllegal(it, QUERY_CHARACTERS) }?.takeIf(String::isNotEmpty)
        val target = ProviderRawTarget(encodedPath, encodedQuery)
        if (target.queryParameters.size > MAXIMUM_QUERY_PARAMETERS) {
            throw ProviderApiRequestException("Use at most $MAXIMUM_QUERY_PARAMETERS query parameters.")
        }
        return target
    }

    /** Strict re-encoding of every segment (unreserved characters only), as AWS SDKs send paths. */
    fun strictPath(target: ProviderRawTarget): String =
        "/" + target.decodedSegments.joinToString("/") { ProviderApiRequestEncoding.awsQueryComponent(it) }

    /** Lenient percent-decoding: malformed escapes are kept literally, `+` is not a space. */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%' && index + 2 < value.length && isHex(value[index + 1]) && isHex(value[index + 2])) {
                output.write((Character.digit(value[index + 1], 16) shl 4) or Character.digit(value[index + 2], 16))
                index += 3
            } else {
                val end = if (Character.isHighSurrogate(character) && index + 1 < value.length) index + 2 else index + 1
                val bytes = value.substring(index, end).toByteArray(StandardCharsets.UTF_8)
                output.write(bytes, 0, bytes.size)
                index = end
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun encodeIllegal(value: String, allowed: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '%' && index + 2 < value.length && isHex(value[index + 1]) && isHex(value[index + 2]) -> {
                    output.append('%').append(value[index + 1].uppercaseChar()).append(value[index + 2].uppercaseChar())
                    index += 3
                    continue
                }
                character.code < 0x80 && character in allowed -> output.append(character)
                else -> {
                    val end = if (Character.isHighSurrogate(character) && index + 1 < value.length) index + 2 else index + 1
                    value.substring(index, end).toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                        val unsigned = byte.toInt() and 0xff
                        output.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0f])
                    }
                    index = end
                    continue
                }
            }
            index += 1
        }
        return output.toString()
    }

    private fun isHex(character: Char): Boolean = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
}

/**
 * Detects whether a Railway GraphQL body is clearly a read-only query. Anything that is not
 * provably a query (mutations, subscriptions, unparseable bodies) needs write confirmation.
 */
object GraphQLRequestClassifier {
    private val COMMENT = Regex("#[^\\n\\r]*")
    private val STRING_LITERAL = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"")
    private val OPERATION = Regex("(?<![A-Za-z0-9_])(query|mutation|subscription)(?![A-Za-z0-9_])")

    fun isReadOnlyQuery(body: String?): Boolean {
        val trimmed = body?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val document = try {
            (HostingJson.parse(trimmed) as? JsonObject)?.get("query") as? JsonString
        } catch (_: HostingResponseFormatException) {
            null
        } ?: return false
        val source = document.value.replace(COMMENT, " ").replace(STRING_LITERAL, "\"\"")
        // Only top-level operation keywords count: strip every nested selection set first.
        val topLevel = buildString {
            var depth = 0
            source.forEach { character ->
                when (character) {
                    '{' -> {
                        if (depth == 0) append(" { ")
                        depth += 1
                    }
                    '}' -> depth -= 1
                    else -> if (depth == 0) append(character)
                }
            }
            if (depth != 0) return false
        }
        val keywords = OPERATION.findAll(topLevel).map { it.value }.toList()
        val anonymous = topLevel.trimStart().startsWith("{")
        if (keywords.isEmpty()) return anonymous
        return keywords.all { it == "query" }
    }
}
