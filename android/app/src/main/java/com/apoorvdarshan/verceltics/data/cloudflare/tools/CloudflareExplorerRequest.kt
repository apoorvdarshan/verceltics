package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderEndpointPolicy
import com.apoorvdarshan.verceltics.data.network.ProviderJsonException
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/** Everything the explorer editor holds. No credential ever appears in a draft. */
data class CloudflareExplorerDraft(
    val method: CloudflareHttpMethod = CloudflareHttpMethod.GET,
    val path: String = "/accounts",
    val queryText: String = "",
    val headerText: String = "",
    val bodyText: String = "",
    val contentType: String = "application/json",
    val bodyEncoding: CloudflareBodyEncoding = CloudflareBodyEncoding.UTF8,
    val readOnlyGraphQL: Boolean = false,
) {
    companion object {
        fun from(preset: CloudflareApiPreset): CloudflareExplorerDraft = CloudflareExplorerDraft(
            method = preset.method,
            path = preset.path,
            queryText = preset.query,
            headerText = preset.headers,
            bodyText = preset.body,
            contentType = preset.contentType,
            bodyEncoding = preset.bodyEncoding,
            readOnlyGraphQL = preset.readOnlyGraphQL,
        )
    }
}

/** A validated request pinned to `https://api.cloudflare.com/client/v4`. */
class CloudflareToolsHttpRequest(
    val method: CloudflareHttpMethod,
    val uri: URI,
    headers: Map<String, String>,
    body: ByteArray?,
    val contentType: String?,
) {
    val headers: Map<String, String> = LinkedHashMap(headers)
    private val storedBody = body?.copyOf()

    init {
        require(CloudflareExplorerRequestBuilder.isPinnedCloudflareUri(uri)) {
            "Cloudflare tools may only call https://api.cloudflare.com/client/v4."
        }
        require(storedBody == null || method != CloudflareHttpMethod.GET) { "GET requests cannot carry a body." }
        require(storedBody == null || storedBody.size <= CloudflareExplorerRequestBuilder.MAXIMUM_BODY_BYTES) {
            "The request body is too large."
        }
        require(contentType == null || contentType.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Invalid content type."
        }
        this.headers.forEach { (name, value) ->
            CloudflareExplorerRequestBuilder.validateHeader(name, value)
        }
    }

    fun bodyCopy(): ByteArray? = storedBody?.copyOf()

    val bodySize: Int get() = storedBody?.size ?: 0

    override fun toString(): String =
        "CloudflareToolsHttpRequest(method=$method, path=${uri.rawPath}, " +
            "query=${if (uri.rawQuery == null) "none" else "<redacted>"}, headerNames=${headers.keys}, " +
            "body=${if (storedBody == null) "none" else "<redacted ${storedBody.size} bytes>"})"
}

/**
 * Port of iOS `CloudflareAPI.rawRequest` validation plus the explorer's query/header parsing.
 *
 * - Paths are relative to `/client/v4`; absolute URLs, query strings, fragments and `..` are rejected.
 * - Writes require a [CloudflareMutationConfirmation] for the exact path, except verified read-only
 *   GraphQL queries.
 * - Credential and transport-owned headers cannot be set by the user.
 */
object CloudflareExplorerRequestBuilder {
    const val API_HOST = "api.cloudflare.com"
    const val API_ORIGIN = "https://api.cloudflare.com/"
    const val API_PREFIX = "/client/v4"
    const val MAXIMUM_BODY_BYTES = 25 * 1_024 * 1_024
    private const val MAXIMUM_HEADERS = 64
    private const val MAXIMUM_QUERY_PARAMETERS = 128
    private const val MAXIMUM_PATH_CHARACTERS = 4_096
    private const val MAXIMUM_HEADER_VALUE_CHARACTERS = 8_192

    /**
     * iOS protected headers (`authorization`, `content-length`, `content-type`, `host`,
     * `x-auth-email`, `x-auth-key`) plus headers the Android transport owns.
     */
    val PROTECTED_HEADERS: Set<String> = setOf(
        "authorization",
        "proxy-authorization",
        "content-length",
        "content-type",
        "host",
        "x-auth-email",
        "x-auth-key",
        "x-auth-user-service-key",
        "accept-encoding",
        "transfer-encoding",
        "connection",
        "cookie",
    )

    private val endpointPolicy = ProviderEndpointPolicy(API_ORIGIN)
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val MUTATION_KEYWORD = Regex("\\bmutation\\b", RegexOption.IGNORE_CASE)
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val PATH_EXTRA = "!$&'()*+,;=:@/"

    fun isPinnedCloudflareUri(uri: URI): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(API_HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null &&
            endpointPolicy.isSameOrigin(uri) &&
            (uri.rawPath ?: "").startsWith("$API_PREFIX/")

    /** iOS `normalizeExplorerPath`, hardened for Android's URI handling. */
    fun normalizePath(input: String): String {
        var path = input.trim()
        if (path.isEmpty()) invalid("Enter a relative Cloudflare API path.")
        if (path.contains("://") || path.contains('?') || path.contains('#')) {
            invalid("Use a relative /client/v4 path and put query parameters in the query fields.")
        }
        if (path.length > MAXIMUM_PATH_CHARACTERS) invalid("The Cloudflare API path is too long.")
        if (path.any { it == '\\' || it.isISOControl() || (it.isWhitespace() && it != ' ') }) {
            invalid("The Cloudflare API path is invalid.")
        }

        path = when {
            path == API_PREFIX || path == "client/v4" -> "/"
            path.startsWith("$API_PREFIX/") -> path.removePrefix(API_PREFIX)
            path.startsWith("client/v4/") -> path.removePrefix("client/v4")
            !path.startsWith("/") -> "/$path"
            else -> path
        }

        val encoded = encodePath(path)
        val decoded = percentDecodeOrNull(encoded)
            ?: invalid("The Cloudflare API path contains an invalid percent-encoding.")
        if (decoded.split('/').any { it == ".." || it == "." }) {
            invalid("Parent path components are not allowed.")
        }
        return encoded
    }

    /** The full path shown in confirmations, e.g. `/client/v4/zones/abc`. */
    fun displayPath(path: String): String {
        val normalized = runCatching { normalizePath(path) }.getOrNull()
            ?: path.trim().let { if (it.startsWith("/")) it else "/$it" }
        return API_PREFIX + normalized
    }

    /** iOS `parseQuery`: newline or `&` separated `key=value`; keys are unique and sorted. */
    fun parseQuery(text: String): List<Pair<String, String>> {
        val lines = text.replace("&", "\n")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = LinkedHashMap<String, String>()
        for (line in lines) {
            val separator = line.indexOf('=')
            if (separator < 0) invalid("Query parameter “$line” must use key=value format.")
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isEmpty()) invalid("Query parameter “$line” must use key=value format.")
            result[percentDecodeOrNull(key) ?: key] = percentDecodeOrNull(value) ?: value
        }
        if (result.size > MAXIMUM_QUERY_PARAMETERS) invalid("Too many query parameters.")
        return result.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** iOS `parseHeaders`: one `Name: value` (or `Name=value`) per line. */
    fun parseHeaders(text: String): Map<String, String> {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val result = LinkedHashMap<String, String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            val equals = line.indexOf('=')
            val separator = if (colon >= 0) colon else equals
            if (separator < 0) invalid("Request header “$line” must use Name: value format.")
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty()) invalid("Request header “$line” must use Name: value format.")
            result[name] = value
        }
        if (result.size > MAXIMUM_HEADERS) invalid("Too many request headers.")
        result.forEach { (name, value) -> validateHeader(name, value) }
        return result
    }

    fun isProtectedHeader(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in PROTECTED_HEADERS

    internal fun validateHeader(name: String, value: String) {
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty() || normalized in PROTECTED_HEADERS || !HEADER_NAME.matches(name) ||
            value.any { it == '\r' || it == '\n' || it == '\u0000' } ||
            value.length > MAXIMUM_HEADER_VALUE_CHARACTERS
        ) {
            invalid("The custom header $name is not allowed.")
        }
    }

    /** Decodes the editor body. Blank bodies, and every GET body, are omitted like iOS. */
    fun decodeBody(
        method: CloudflareHttpMethod,
        bodyText: String,
        encoding: CloudflareBodyEncoding,
        contentType: String?,
    ): ByteArray? {
        if (method == CloudflareHttpMethod.GET || bodyText.isBlank()) return null
        val bytes = when (encoding) {
            CloudflareBodyEncoding.UTF8 -> bodyText.toByteArray(StandardCharsets.UTF_8)
            CloudflareBodyEncoding.BASE64 -> {
                val normalized = bodyText.filterNot(Char::isWhitespace)
                try {
                    Base64.getDecoder().decode(normalized)
                } catch (_: IllegalArgumentException) {
                    invalid("The request body is not valid Base64 data.")
                }
            }
        }
        if (bytes.size > MAXIMUM_BODY_BYTES) invalid("Request bodies must be 25 MB or smaller.")
        if (contentType?.lowercase(Locale.ROOT)?.contains("json") == true) validateJson(bytes)
        return bytes
    }

    private fun validateJson(bytes: ByteArray) {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            invalid("The request body is not valid JSON: it is not UTF-8 text.")
        }
        try {
            ProviderJsonParser.parse(text)
        } catch (error: ProviderJsonException) {
            invalid("The request body is not valid JSON: ${error.message}")
        }
    }

    /** iOS `isReadOnlyGraphQLBody`: a JSON object whose `query` has no `mutation` keyword. */
    fun isReadOnlyGraphQLBody(body: ByteArray): Boolean {
        val root = runCatching { ProviderJsonParser.parse(body) }.getOrNull() as? ProviderJsonValue.Obj
            ?: return false
        val query = (root.fields["query"] as? ProviderJsonValue.Str)?.value ?: return false
        return !MUTATION_KEYWORD.containsMatchIn(query)
    }

    fun isVerifiedReadOnlyGraphQL(
        method: CloudflareHttpMethod,
        normalizedPath: String,
        allowReadOnlyGraphQL: Boolean,
        body: ByteArray?,
    ): Boolean = allowReadOnlyGraphQL &&
        method == CloudflareHttpMethod.POST &&
        normalizedPath == "/graphql" &&
        body != null &&
        isReadOnlyGraphQLBody(body)

    /**
     * Whether the explorer must show its write confirmation before sending. Every non-GET request
     * needs one unless it is a verified read-only GraphQL query; an unparsable draft that would
     * write still asks, and validation then reports the problem.
     */
    fun requiresWriteConfirmation(draft: CloudflareExplorerDraft, attachedBody: ByteArray? = null): Boolean {
        if (!draft.method.isMutation) return false
        if (!draft.readOnlyGraphQL || attachedBody != null) return true
        return runCatching {
            val path = normalizePath(draft.path)
            val body = decodeBody(draft.method, draft.bodyText, draft.bodyEncoding, draft.contentType)
            !isVerifiedReadOnlyGraphQL(draft.method, path, draft.readOnlyGraphQL, body)
        }.getOrDefault(true)
    }

    /**
     * Validates and builds a pinned request. [attachedBody] replaces the editor text when the user
     * imported a file or composed a multipart body that is too large to edit as Base64.
     */
    fun build(
        draft: CloudflareExplorerDraft,
        confirmation: CloudflareMutationConfirmation?,
        attachedBody: ByteArray? = null,
    ): CloudflareToolsHttpRequest {
        val normalizedPath = normalizePath(draft.path)
        val contentType = draft.contentType.trim().takeIf { draft.method != CloudflareHttpMethod.GET }
        if (contentType != null && contentType.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            invalid("The Content-Type header is invalid.")
        }
        val body = if (draft.method == CloudflareHttpMethod.GET) {
            null
        } else if (attachedBody != null) {
            if (attachedBody.size > MAXIMUM_BODY_BYTES) invalid("Request bodies must be 25 MB or smaller.")
            attachedBody.copyOf()
        } else {
            decodeBody(draft.method, draft.bodyText, draft.bodyEncoding, contentType)
        }

        val readOnlyGraphQL = isVerifiedReadOnlyGraphQL(draft.method, normalizedPath, draft.readOnlyGraphQL, body)
        if (draft.method.isMutation && !readOnlyGraphQL) {
            val confirmed = confirmation != null &&
                (confirmation.resourceId == draft.path || confirmation.resourceId == normalizedPath)
            if (!confirmed) {
                throw CloudflareToolsException(
                    CloudflareToolsFailureKind.CONFIRMATION_REQUIRED,
                    "Confirm the change to ${draft.path} before continuing.",
                )
            }
        }

        val headers = parseHeaders(draft.headerText)
        val query = parseQuery(draft.queryText)
        val uri = buildUri(normalizedPath, query)
        return CloudflareToolsHttpRequest(
            method = draft.method,
            uri = uri,
            headers = headers,
            body = body,
            contentType = if (body != null) contentType?.takeIf(String::isNotEmpty) else null,
        )
    }

    /** Builds `https://api.cloudflare.com/client/v4<path>?<query>` and re-checks the pin. */
    fun buildUri(normalizedPath: String, query: List<Pair<String, String>> = emptyList()): URI {
        require(normalizedPath.startsWith("/")) { "Cloudflare paths must be relative to /client/v4." }
        val queryString = query.takeIf { it.isNotEmpty() }?.joinToString("&") { (name, value) ->
            "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"
        }
        val raw = "https://$API_HOST$API_PREFIX$normalizedPath" + (queryString?.let { "?$it" } ?: "")
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            invalid("The Cloudflare API path is invalid.")
        }
        if (!isPinnedCloudflareUri(uri)) invalid("The Cloudflare API path is invalid.")
        return uri
    }

    /** Percent-encodes a single path segment value (iOS `pathEncoded` / `pathSegment`). */
    fun encodePathSegment(value: String): String = percentEncode(value, UNRESERVED + "!$&'()*+,;=:@")

    fun encodeQueryComponent(value: String): String = percentEncode(value, UNRESERVED)

    /** Encodes characters outside RFC 3986 `pchar` / `/` while keeping existing `%XX` escapes. */
    private fun encodePath(path: String): String {
        val output = StringBuilder(path.length)
        var index = 0
        while (index < path.length) {
            val character = path[index]
            when {
                character == '%' -> {
                    if (index + 2 <= path.lastIndex && isHex(path[index + 1]) && isHex(path[index + 2])) {
                        output.append('%').append(path[index + 1].uppercaseChar()).append(path[index + 2].uppercaseChar())
                        index += 3
                        continue
                    }
                    invalid("The Cloudflare API path contains an invalid percent-encoding.")
                }
                character in UNRESERVED || character in PATH_EXTRA -> output.append(character)
                else -> {
                    val end = if (Character.isHighSurrogate(character) && index + 1 < path.length) index + 2 else index + 1
                    path.substring(index, end).toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                        output.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
                    }
                    index = end
                    continue
                }
            }
            index += 1
        }
        return output.toString()
    }

    private fun percentEncode(value: String, allowed: String): String {
        val output = StringBuilder(value.length)
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xFF
            val character = code.toChar()
            if (code < 0x80 && character in allowed) {
                output.append(character)
            } else {
                output.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
            }
        }
        return output.toString()
    }

    /** iOS `removingPercentEncoding`: `%XX` → bytes → UTF-8, `+` untouched; null when invalid. */
    fun percentDecodeOrNull(value: String): String? {
        if ('%' !in value) return value
        val output = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%') {
                if (index + 2 > value.lastIndex) return null
                val high = Character.digit(value[index + 1], 16)
                val low = Character.digit(value[index + 2], 16)
                if (high < 0 || low < 0) return null
                output.write((high shl 4) or low)
                index += 3
            } else {
                val end = if (Character.isHighSurrogate(character) && index + 1 < value.length) index + 2 else index + 1
                val bytes = value.substring(index, end).toByteArray(StandardCharsets.UTF_8)
                output.write(bytes, 0, bytes.size)
                index = end
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(output.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun isHex(character: Char): Boolean = Character.digit(character, 16) >= 0

    private val HEX = "0123456789ABCDEF".toCharArray()

    private fun invalid(message: String): Nothing =
        throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_REQUEST, message)
}
