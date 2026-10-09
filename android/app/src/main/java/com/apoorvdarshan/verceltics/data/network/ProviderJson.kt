package com.apoorvdarshan.verceltics.data.network

import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Lossless, immutable JSON tree for provider responses.
 *
 * Numbers keep their exact decimal text so 64-bit identifiers and high-precision ratios are not
 * rounded through binary floating point. The parser is pure Kotlin so provider normalization can
 * be unit tested on the JVM without Android's stubbed `org.json`.
 */
sealed class ProviderJsonValue {
    class Obj(fields: Map<String, ProviderJsonValue>) : ProviderJsonValue() {
        val fields: Map<String, ProviderJsonValue> = LinkedHashMap(fields)

        override fun equals(other: Any?): Boolean = other is Obj && other.fields == fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Obj(keys=${fields.keys})"
    }

    class Arr(items: List<ProviderJsonValue>) : ProviderJsonValue() {
        val items: List<ProviderJsonValue> = items.toList()

        override fun equals(other: Any?): Boolean = other is Arr && other.items == items

        override fun hashCode(): Int = items.hashCode()

        override fun toString(): String = "Arr(size=${items.size})"
    }

    data class Str(val value: String) : ProviderJsonValue()

    /** A JSON number stored as validated decimal text. Equality is numeric, not textual. */
    class Num private constructor(val text: String) : ProviderJsonValue() {
        val decimal: BigDecimal = BigDecimal(text)

        override fun equals(other: Any?): Boolean =
            other is Num && decimal.compareTo(other.decimal) == 0

        override fun hashCode(): Int = decimal.stripTrailingZeros().hashCode()

        override fun toString(): String = "Num($text)"

        companion object {
            private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

            fun parse(text: String): Num {
                require(NUMBER.matches(text)) { "Invalid JSON number." }
                return Num(text)
            }

            fun of(value: Long): Num = Num(value.toString())

            fun of(value: Int): Num = Num(value.toString())

            fun of(value: Double): Num {
                require(value.isFinite()) { "JSON numbers must be finite." }
                if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) {
                    return Num(value.toLong().toString())
                }
                return Num(BigDecimal.valueOf(value).toString())
            }

            fun of(value: BigDecimal): Num = parse(value.toString())
        }
    }

    data class Bool(val value: Boolean) : ProviderJsonValue()

    data object Null : ProviderJsonValue()

    operator fun get(key: String): ProviderJsonValue? = (this as? Obj)?.fields?.get(key)

    val objectValue: Map<String, ProviderJsonValue>? get() = (this as? Obj)?.fields

    val arrayValue: List<ProviderJsonValue>? get() = (this as? Arr)?.items

    /** Text for scalars (numbers keep their exact decimal text). Containers and null return null. */
    val stringValue: String?
        get() = when (this) {
            is Str -> value
            is Num -> text
            is Bool -> value.toString()
            else -> null
        }

    /** Finite numeric value of a number or numeric string. */
    val numberValue: Double?
        get() = when (this) {
            is Num -> decimal.toDouble().takeIf(Double::isFinite)
            is Str -> value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
            else -> null
        }

    /** Exact decimal used for sorting and cross-type numeric equality. */
    val decimalValue: BigDecimal?
        get() = when (this) {
            is Num -> decimal
            is Str -> value.trim().takeIf(String::isNotEmpty)?.let { runCatching { BigDecimal(it) }.getOrNull() }
            else -> null
        }

    val booleanValue: Boolean?
        get() = when (this) {
            is Bool -> value
            is Num -> when {
                decimal.compareTo(BigDecimal.ONE) == 0 -> true
                decimal.signum() == 0 -> false
                else -> null
            }
            is Str -> when (value.lowercase(Locale.ROOT)) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            else -> null
        }

    val isNull: Boolean get() = this === Null

    companion object {
        /** Converts plain Kotlin values (maps, lists, strings, numbers, booleans) into JSON. */
        fun from(value: Any?): ProviderJsonValue = when (value) {
            null -> Null
            is ProviderJsonValue -> value
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Int -> Num.of(value)
            is Long -> Num.of(value)
            is Double -> Num.of(value)
            is Float -> Num.of(value.toDouble())
            is BigDecimal -> Num.of(value)
            is Map<*, *> -> Obj(
                LinkedHashMap<String, ProviderJsonValue>().also { output ->
                    value.forEach { (key, child) -> output[key.toString()] = from(child) }
                },
            )
            is Iterable<*> -> Arr(value.map(::from))
            is Array<*> -> Arr(value.map(::from))
            else -> throw IllegalArgumentException("Unsupported JSON value type.")
        }
    }
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): ProviderJsonValue.Obj =
    ProviderJsonValue.from(linkedMapOf(*pairs)) as ProviderJsonValue.Obj

class ProviderJsonException(message: String) : Exception(message)

/** Strict RFC 8259 parser with depth and size bounds. */
object ProviderJsonParser {
    const val DEFAULT_MAXIMUM_DEPTH: Int = 96
    const val DEFAULT_MAXIMUM_CHARACTERS: Int = 24 * 1_024 * 1_024

    fun parse(
        bytes: ByteArray,
        maximumDepth: Int = DEFAULT_MAXIMUM_DEPTH,
    ): ProviderJsonValue = parse(String(bytes, StandardCharsets.UTF_8), maximumDepth)

    fun parse(
        text: String,
        maximumDepth: Int = DEFAULT_MAXIMUM_DEPTH,
        maximumCharacters: Int = DEFAULT_MAXIMUM_CHARACTERS,
    ): ProviderJsonValue {
        if (text.length > maximumCharacters) throw ProviderJsonException("The JSON document is too large.")
        val reader = Reader(text, maximumDepth)
        reader.skipByteOrderMark()
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd) throw ProviderJsonException("Unexpected trailing JSON content.")
        return value
    }

    private class Reader(private val text: String, private val maximumDepth: Int) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipByteOrderMark() {
            if (text.startsWith('\uFEFF')) index = 1
        }

        fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\n', '\r', '\t' -> index += 1
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): ProviderJsonValue {
            if (depth > maximumDepth) throw ProviderJsonException("The JSON document is nested too deeply.")
            if (atEnd) throw ProviderJsonException("Unexpected end of JSON.")
            return when (val character = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> ProviderJsonValue.Str(readString())
                't' -> readLiteral("true", ProviderJsonValue.Bool(true))
                'f' -> readLiteral("false", ProviderJsonValue.Bool(false))
                'n' -> readLiteral("null", ProviderJsonValue.Null)
                else -> if (character == '-' || character in '0'..'9') {
                    readNumber()
                } else {
                    throw ProviderJsonException("Unexpected JSON character.")
                }
            }
        }

        private fun readObject(depth: Int): ProviderJsonValue {
            index += 1
            val fields = LinkedHashMap<String, ProviderJsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return ProviderJsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw ProviderJsonException("Expected a JSON object key.")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return ProviderJsonValue.Obj(fields)
                    }
                    else -> throw ProviderJsonException("Expected ',' or '}' in a JSON object.")
                }
            }
        }

        private fun readArray(depth: Int): ProviderJsonValue {
            index += 1
            val items = ArrayList<ProviderJsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return ProviderJsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return ProviderJsonValue.Arr(items)
                    }
                    else -> throw ProviderJsonException("Expected ',' or ']' in a JSON array.")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val output = StringBuilder()
            while (true) {
                if (atEnd) throw ProviderJsonException("Unterminated JSON string.")
                val character = text[index++]
                when {
                    character == '"' -> return output.toString()
                    character == '\\' -> {
                        if (atEnd) throw ProviderJsonException("Unterminated JSON escape.")
                        when (val escaped = text[index++]) {
                            '"' -> output.append('"')
                            '\\' -> output.append('\\')
                            '/' -> output.append('/')
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000c')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> output.append(readUnicodeEscape())
                            else -> throw ProviderJsonException("Invalid JSON escape \\$escaped.")
                        }
                    }
                    character.code < 0x20 -> throw ProviderJsonException("Unescaped control character in JSON string.")
                    else -> output.append(character)
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            if (index + 4 > text.length) throw ProviderJsonException("Truncated JSON unicode escape.")
            val code = text.substring(index, index + 4).toIntOrNull(16)
                ?: throw ProviderJsonException("Invalid JSON unicode escape.")
            index += 4
            return code.toChar()
        }

        private fun readNumber(): ProviderJsonValue {
            val start = index
            if (peek() == '-') index += 1
            while (!atEnd && (text[index] in '0'..'9' || text[index] in ".eE+-")) index += 1
            val literal = text.substring(start, index)
            return try {
                ProviderJsonValue.Num.parse(literal)
            } catch (_: IllegalArgumentException) {
                throw ProviderJsonException("Invalid JSON number.")
            }
        }

        private fun readLiteral(literal: String, value: ProviderJsonValue): ProviderJsonValue {
            if (!text.startsWith(literal, index)) throw ProviderJsonException("Invalid JSON literal.")
            index += literal.length
            return value
        }

        private fun peek(): Char? = if (atEnd) null else text[index]

        private fun expect(character: Char) {
            if (peek() != character) throw ProviderJsonException("Expected '$character' in JSON.")
            index += 1
        }
    }
}

object ProviderJsonWriter {
    fun write(value: ProviderJsonValue): String = StringBuilder().also { append(it, value) }.toString()

    fun writeBytes(value: ProviderJsonValue): ByteArray = write(value).toByteArray(StandardCharsets.UTF_8)

    private fun append(output: StringBuilder, value: ProviderJsonValue) {
        when (value) {
            is ProviderJsonValue.Obj -> {
                output.append('{')
                var first = true
                value.fields.forEach { (key, child) ->
                    if (!first) output.append(',')
                    first = false
                    appendString(output, key)
                    output.append(':')
                    append(output, child)
                }
                output.append('}')
            }
            is ProviderJsonValue.Arr -> {
                output.append('[')
                value.items.forEachIndexed { index, child ->
                    if (index > 0) output.append(',')
                    append(output, child)
                }
                output.append(']')
            }
            is ProviderJsonValue.Str -> appendString(output, value.value)
            is ProviderJsonValue.Num -> output.append(value.text)
            is ProviderJsonValue.Bool -> output.append(value.value)
            ProviderJsonValue.Null -> output.append("null")
        }
    }

    private fun appendString(output: StringBuilder, value: String) {
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
}

/**
 * Removes provider-returned credentials and write secrets while preserving every other field.
 * Mirrors the iOS `SiteIntegrationJSONValue.sanitizingSecrets()` key policy.
 */
object ProviderJsonSanitizer {
    const val REDACTED: String = "[REDACTED]"

    fun sanitize(value: ProviderJsonValue): ProviderJsonValue = when (value) {
        is ProviderJsonValue.Obj -> ProviderJsonValue.Obj(
            LinkedHashMap<String, ProviderJsonValue>().also { output ->
                value.fields.forEach { (key, child) ->
                    output[key] = if (isSensitiveKey(key)) {
                        ProviderJsonValue.Str(REDACTED)
                    } else {
                        sanitize(child)
                    }
                }
            },
        )
        is ProviderJsonValue.Arr -> ProviderJsonValue.Arr(value.items.map(::sanitize))
        is ProviderJsonValue.Str -> ProviderJsonValue.Str(sanitizedUrlString(value.value))
        else -> value
    }

    fun normalizedKey(key: String): String =
        key.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun isSensitiveKey(key: String): Boolean {
        val normalized = normalizedKey(key)
        val candidates = buildList {
            add(normalized)
            if (normalized.startsWith("x") && normalized.length > 1) add(normalized.drop(1))
        }
        return candidates.any { candidate ->
            candidate in SENSITIVE_KEYS ||
                (candidate.startsWith("header") && candidate.removePrefix("header") in SENSITIVE_KEYS) ||
                (candidate.endsWith("header") && candidate.removeSuffix("header") in SENSITIVE_KEYS) ||
                SENSITIVE_SUFFIXES.any(candidate::endsWith)
        }
    }

    /** Drops URL user info and redacts secret-looking query values in absolute HTTP(S) URLs. */
    fun sanitizedUrlString(value: String): String {
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.host.isNullOrEmpty()) return value
        var changed = uri.rawUserInfo != null
        val rawQuery = uri.rawQuery
        val sanitizedQuery = rawQuery?.split('&')?.joinToString("&") { pair ->
            val separator = pair.indexOf('=')
            if (separator < 0) return@joinToString pair
            val rawName = pair.substring(0, separator)
            val name = runCatching { URLDecoder.decode(rawName, StandardCharsets.UTF_8.name()) }.getOrDefault(rawName)
            if (isSensitiveKey(name)) {
                changed = true
                "$rawName=${URLEncoder.encode(REDACTED, StandardCharsets.UTF_8.name())}"
            } else {
                pair
            }
        }
        if (!changed) return value
        return buildString {
            append(uri.scheme)
            append("://")
            append(uri.host)
            if (uri.port >= 0) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            if (sanitizedQuery != null) append('?').append(sanitizedQuery)
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    private val SENSITIVE_SUFFIXES = listOf(
        "authorization", "apikey", "authtoken", "accesstoken", "refreshtoken",
        "bearertoken", "clientsecret", "apisecret", "secretkey", "privatekey",
        "signingkey", "encryptionkey", "password", "credential",
    )

    private val SENSITIVE_KEYS = setOf(
        "authorization", "authentication", "authorizationheader", "proxyauthorization",
        "apikey", "accesskey", "secretkey",
        "privatekey", "signingkey", "encryptionkey", "accesskeyid", "secretaccesskey",
        "token", "sealedtoken", "accesstoken", "refreshtoken", "idtoken", "authtoken", "oauthtoken",
        "bearertoken", "clienttoken", "verificationtoken", "webhooktoken",
        "password", "httppassword", "proxypassword", "databasepassword",
        "secret", "clientsecret", "apisecret", "signingsecret", "webhooksecret",
        "credential", "credentials", "clientcredential", "clientcredentials",
        "httpusername", "requestheaders", "requestbody", "environmentvariables",
        "playwrightscript", "cookie", "cookies", "setcookie",
    )
}
