package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** iOS `CloudflareHTTPMethod`: every non-GET method can change live Cloudflare resources. */
enum class CloudflareHttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    ;

    val isMutation: Boolean get() = this != GET

    companion object {
        fun parse(value: String): CloudflareHttpMethod? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

/** iOS `CloudflareRequestBodyEncoding`. */
enum class CloudflareBodyEncoding(val label: String) {
    UTF8("UTF-8"),
    BASE64("Base64"),
}

/** iOS `CloudflareMutationConfirmation`: a write is only sent when confirmed for the exact path. */
data class CloudflareMutationConfirmation(val resourceId: String)

/** A multipart field from Cloudflare's OpenAPI schema (iOS `CloudflareOpenAPIMultipartField`). */
data class CloudflareMultipartFieldSpec(
    val name: String,
    val required: Boolean,
    val isFile: Boolean,
    val type: String? = null,
    val format: String? = null,
    val description: String? = null,
    val suggestedValue: String = "",
)

/**
 * iOS `CloudflareAPIOperationPreset`: a fully editable explorer request. Placeholders such as
 * `{account_id}` are only substituted by [resolved].
 */
data class CloudflareApiPreset(
    val id: String,
    val title: String,
    val summary: String,
    val method: CloudflareHttpMethod,
    val path: String,
    val query: String = "",
    val headers: String = "",
    val body: String = "",
    val contentType: String = "application/json",
    val bodyEncoding: CloudflareBodyEncoding = CloudflareBodyEncoding.UTF8,
    val multipartFields: List<CloudflareMultipartFieldSpec> = emptyList(),
    val readOnlyGraphQL: Boolean = false,
    val requiresApiToken: Boolean = false,
    /** Token permissions Cloudflare's schema lists for this operation, used for 403 guidance. */
    val permissions: List<String> = emptyList(),
) {
    fun resolved(accountId: String, zoneId: String?, now: Instant = Instant.now()): CloudflareApiPreset {
        val from = now.minus(7, ChronoUnit.DAYS)
        val instantFormat = DateTimeFormatter.ISO_INSTANT
        val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)
        val analyticsTo = instantFormat.format(now.truncatedTo(ChronoUnit.SECONDS))
        val analyticsFrom = instantFormat.format(from.truncatedTo(ChronoUnit.SECONDS))
        fun resolve(value: String): String = value
            .replace("{account_id}", accountId)
            .replace("{zone_id}", zoneId ?: "ZONE_ID")
            .replace("{analytics_from}", analyticsFrom)
            .replace("{analytics_to}", analyticsTo)
            .replace("{analytics_from_date}", dayFormat.format(from))
            .replace("{analytics_to_date}", dayFormat.format(now))
        return copy(
            path = resolve(path),
            query = resolve(query),
            headers = resolve(headers),
            body = resolve(body),
        )
    }
}

/** An unparsed explorer response. Bodies stay in memory only and are never persisted. */
class CloudflareRawResponse private constructor(
    val statusCode: Int,
    headers: Map<String, String>,
    private val storedBody: ByteArray,
    val elapsedMillis: Long?,
    @Suppress("UNUSED_PARAMETER") ownsBody: Boolean,
) {
    constructor(
        statusCode: Int,
        headers: Map<String, String>,
        body: ByteArray,
        elapsedMillis: Long? = null,
    ) : this(statusCode, headers, body.copyOf(), elapsedMillis, ownsBody = true)

    val headers: Map<String, String> = headers.toSortedMap(String.CASE_INSENSITIVE_ORDER)

    val isSuccess: Boolean get() = statusCode in 200..299

    val bodySize: Int get() = storedBody.size

    fun bodyBytes(): ByteArray = storedBody.copyOf()

    val text: String get() = String(storedBody, StandardCharsets.UTF_8)

    /**
     * Whether the body is small enough to parse into a JSON tree on a phone. Explorer responses may
     * be up to 32 MB (iOS parity); larger bodies are previewed as leading text instead, so a huge
     * response can never multiply into hundreds of megabytes of strings and JSON nodes.
     */
    val isParseable: Boolean get() = storedBody.size <= MAXIMUM_PARSE_BYTES

    /**
     * iOS `prettyPrintedBody`: sorted-key, indented JSON, falling back to the UTF-8 text. Bodies over
     * [MAXIMUM_PARSE_BYTES] return a bounded leading-text preview instead.
     */
    val prettyPrintedBody: String by lazy {
        if (!isParseable) return@lazy preview(PREVIEW_FALLBACK_CHARACTERS).text
        val raw = text
        val parsed = runCatching { ProviderJsonParser.parse(raw) }.getOrNull()
        if (parsed == null) raw else CloudflarePrettyJson.write(parsed)
    }

    /**
     * At most [maximumCharacters] of the response for display or copy: pretty JSON when the body is
     * parseable, otherwise only the leading bytes are decoded.
     */
    fun preview(maximumCharacters: Int): CloudflareResponsePreview {
        require(maximumCharacters > 0)
        if (isParseable) {
            val pretty = prettyPrintedBody
            return CloudflareResponsePreview(pretty.take(maximumCharacters), truncated = pretty.length > maximumCharacters)
        }
        // UTF-8 needs at most four bytes per character, so this prefix always covers the limit.
        val byteCount = minOf(storedBody.size.toLong(), maximumCharacters.toLong() * 4L).toInt()
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(storedBody, 0, byteCount))
            .toString()
        return CloudflareResponsePreview(
            text = decoded.take(maximumCharacters),
            truncated = byteCount < storedBody.size || decoded.length > maximumCharacters,
        )
    }

    fun parsedJson(): ProviderJsonValue? =
        if (!isParseable) null else runCatching { ProviderJsonParser.parse(text) }.getOrNull()

    // The body is never mutated after construction, so the copy can share it.
    fun withElapsed(elapsedMillis: Long): CloudflareRawResponse =
        CloudflareRawResponse(statusCode, headers, storedBody, elapsedMillis, ownsBody = true)

    override fun toString(): String =
        "CloudflareRawResponse(statusCode=$statusCode, bodyBytes=${storedBody.size}, headerNames=${headers.keys})"

    companion object {
        /**
         * Takes ownership of [body] without copying it. Only for freshly read transport buffers that
         * nothing else references, so a 32 MB response is not duplicated once more.
         */
        internal fun adopting(
            statusCode: Int,
            headers: Map<String, String>,
            body: ByteArray,
            elapsedMillis: Long?,
        ): CloudflareRawResponse = CloudflareRawResponse(statusCode, headers, body, elapsedMillis, ownsBody = true)

        /** The previous 8 MB response ceiling: bodies up to this size are still parsed and pretty-printed. */
        const val MAXIMUM_PARSE_BYTES: Int = 8 * 1_024 * 1_024
        private const val PREVIEW_FALLBACK_CHARACTERS = 300_000
    }
}

/** A bounded slice of a response body for the explorer viewer and clipboard. */
data class CloudflareResponsePreview(val text: String, val truncated: Boolean)

/** Deterministic pretty JSON writer (two-space indentation, keys sorted like iOS `.sortedKeys`). */
object CloudflarePrettyJson {
    fun write(value: ProviderJsonValue): String = StringBuilder().also { append(it, value, 0) }.toString()

    private fun append(output: StringBuilder, value: ProviderJsonValue, depth: Int) {
        when (value) {
            is ProviderJsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    output.append("{}")
                    return
                }
                output.append("{\n")
                val keys = value.fields.keys.sorted()
                keys.forEachIndexed { index, key ->
                    indent(output, depth + 1)
                    appendString(output, key)
                    output.append(": ")
                    append(output, value.fields.getValue(key), depth + 1)
                    if (index < keys.lastIndex) output.append(',')
                    output.append('\n')
                }
                indent(output, depth)
                output.append('}')
            }
            is ProviderJsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    output.append("[]")
                    return
                }
                output.append("[\n")
                value.items.forEachIndexed { index, item ->
                    indent(output, depth + 1)
                    append(output, item, depth + 1)
                    if (index < value.items.lastIndex) output.append(',')
                    output.append('\n')
                }
                indent(output, depth)
                output.append(']')
            }
            is ProviderJsonValue.Str -> appendString(output, value.value)
            is ProviderJsonValue.Num -> output.append(value.text)
            is ProviderJsonValue.Bool -> output.append(value.value)
            ProviderJsonValue.Null -> output.append("null")
        }
    }

    private fun indent(output: StringBuilder, depth: Int) {
        repeat(depth) { output.append("  ") }
    }

    internal fun appendString(output: StringBuilder, value: String) {
        output.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000c' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (character.code < 0x20) {
                    output.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                } else {
                    output.append(character)
                }
            }
        }
        output.append('"')
    }

    /** JSON string literal for embedding a value into a request body (iOS `jsonString`). */
    fun quoted(value: String): String = StringBuilder().also { appendString(it, value) }.toString()
}

/** Why a tools request failed. Messages are safe to show and never contain credentials. */
enum class CloudflareToolsFailureKind {
    INVALID_REQUEST,
    CONFIRMATION_REQUIRED,
    AUTHENTICATION,
    AUTHORIZATION,
    NOT_FOUND,
    RATE_LIMITED,
    PROVIDER,
    INVALID_RESPONSE,
    NETWORK,
    NOT_CONNECTED,
}

class CloudflareToolsException(
    val kind: CloudflareToolsFailureKind,
    message: String,
    val statusCode: Int? = null,
) : Exception(message) {
    override fun toString(): String =
        "CloudflareToolsException(kind=$kind, statusCode=$statusCode, message=$message)"
}
