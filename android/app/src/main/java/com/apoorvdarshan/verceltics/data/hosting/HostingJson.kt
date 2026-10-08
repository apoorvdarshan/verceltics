package com.apoorvdarshan.verceltics.data.hosting

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Immutable JSON tree used by the hosting adapters.
 *
 * Hosting providers return loosely documented dynamic payloads (the iOS client reads them with
 * `JSONSerialization`). A tiny pure-Kotlin parser keeps the normalization rules identical on the
 * JVM test runner and on device, without relying on Android's stubbed `org.json`/`JsonReader`.
 */
sealed interface JsonValue

data class JsonObject(val members: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = members[key]

    val isEmpty: Boolean
        get() = members.isEmpty()

    companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

/** Numbers keep their exact source text so large identifiers are never rounded. */
data class JsonNumber(val raw: String) : JsonValue {
    fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()

    /** Mirrors `NSNumber.intValue`: fractional values truncate toward zero. */
    fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toBigDecimalOrNull()?.let { decimal ->
        runCatching { decimal.toBigInteger().longValueExact() }.getOrNull()
    }
}

data class JsonBoolean(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

class HostingResponseFormatException(message: String) : RuntimeException(message)

/** Strict RFC 8259 parser/writer with explicit depth and size limits. */
object HostingJson {
    const val MAX_DEPTH: Int = 64
    const val MAX_INPUT_BYTES: Int = 8 * 1_024 * 1_024

    fun parse(bytes: ByteArray): JsonValue {
        if (bytes.size > MAX_INPUT_BYTES) {
            throw HostingResponseFormatException("The provider response is too large.")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw HostingResponseFormatException("The provider response is not UTF-8.")
        }
        return parse(text)
    }

    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    /** Serializes [value]. Sorted keys give a stable fingerprint for de-duplication. */
    fun write(value: JsonValue, sortedKeys: Boolean = false): String =
        StringBuilder().also { writeValue(it, value, sortedKeys, 0) }.toString()

    fun quote(value: String): String = StringBuilder().also { writeString(it, value) }.toString()

    private fun writeValue(output: StringBuilder, value: JsonValue, sortedKeys: Boolean, depth: Int) {
        require(depth <= MAX_DEPTH) { "JSON nesting is too deep." }
        when (value) {
            is JsonObject -> {
                output.append('{')
                val entries = if (sortedKeys) value.members.entries.sortedBy { it.key } else value.members.entries
                entries.forEachIndexed { index, (key, member) ->
                    if (index > 0) output.append(',')
                    writeString(output, key)
                    output.append(':')
                    writeValue(output, member, sortedKeys, depth + 1)
                }
                output.append('}')
            }
            is JsonArray -> {
                output.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) output.append(',')
                    writeValue(output, item, sortedKeys, depth + 1)
                }
                output.append(']')
            }
            is JsonString -> writeString(output, value.value)
            is JsonNumber -> output.append(value.raw)
            is JsonBoolean -> output.append(if (value.value) "true" else "false")
            JsonNull -> output.append("null")
        }
    }

    private fun writeString(output: StringBuilder, value: String) {
        output.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                else -> if (character < ' ' || character == ' ' || character == ' ') {
                    output.append("\\u%04x".format(character.code))
                } else {
                    output.append(character)
                }
            }
        }
        output.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("Unexpected trailing content")
            return value
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("Nesting is too deep")
            if (index >= text.length) fail("Unexpected end of input")
            return when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonString(parseString())
                't' -> literal("true", JsonBoolean(true))
                'f' -> literal("false", JsonBoolean(false))
                'n' -> literal("null", JsonNull)
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            index += 1
            val members = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return JsonObject(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected an object key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                members[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return JsonObject(members)
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            index += 1
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                items += parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return JsonArray(items)
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val output = StringBuilder()
            while (true) {
                if (index >= text.length) fail("Unterminated string")
                val character = text[index++]
                when {
                    character == '"' -> return output.toString()
                    character == '\\' -> {
                        if (index >= text.length) fail("Unterminated escape")
                        when (val escaped = text[index++]) {
                            '"' -> output.append('"')
                            '\\' -> output.append('\\')
                            '/' -> output.append('/')
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000C')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Truncated unicode escape")
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: fail("Invalid unicode escape")
                                output.append(code.toChar())
                                index += 4
                            }
                            else -> fail("Invalid escape '\\$escaped'")
                        }
                    }
                    character < ' ' -> fail("Unescaped control character in string")
                    else -> output.append(character)
                }
            }
        }

        private fun parseNumber(): JsonNumber {
            val start = index
            if (peek() == '-') index += 1
            when {
                peek() == '0' -> index += 1
                peek() in '1'..'9' -> while (peek() in '0'..'9') index += 1
                else -> fail("Unexpected character")
            }
            if (peek() == '.') {
                index += 1
                if (peek() !in '0'..'9') fail("Invalid fraction")
                while (peek() in '0'..'9') index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                if (peek() !in '0'..'9') fail("Invalid exponent")
                while (peek() in '0'..'9') index += 1
            }
            return JsonNumber(text.substring(start, index))
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("Invalid literal")
            index += word.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun expect(character: Char) {
            if (peek() != character) fail("Expected '$character'")
            index += 1
        }

        private fun fail(reason: String): Nothing =
            throw HostingResponseFormatException("The provider returned invalid JSON ($reason).")
    }
}

// region iOS-compatible dynamic accessors

internal fun JsonValue?.asObject(): JsonObject = this as? JsonObject ?: JsonObject.EMPTY

internal fun JsonValue?.asArray(): List<JsonValue> = (this as? JsonArray)?.items.orEmpty()

/**
 * Mirrors the iOS `string(_:_:)` helper: the first non-empty string (or number rendered as text)
 * among [keys]. Dotted keys read nested objects.
 */
internal fun JsonObject.string(vararg keys: String): String? {
    for (key in keys) {
        if ('.' in key) {
            val nested = nestedValue(key)
            if (nested is JsonString && nested.value.isNotEmpty()) return nested.value
        }
        when (val value = members[key]) {
            is JsonString -> if (value.value.isNotEmpty()) return value.value
            is JsonNumber -> return value.raw
            else -> Unit
        }
    }
    return null
}

internal fun JsonObject.bool(key: String): Boolean? = when (val value = members[key]) {
    is JsonBoolean -> value.value
    is JsonNumber -> value.toDoubleOrNull()?.let { it != 0.0 }
    else -> null
}

internal fun JsonValue?.integer(): Long? = when (this) {
    is JsonNumber -> toLongOrNull()
    is JsonString -> value.toLongOrNull()
    else -> null
}

/** Epoch milliseconds from ISO-8601 text or epoch seconds/milliseconds, as on iOS. */
internal fun JsonValue?.dateMillis(): Long? = when (this) {
    is JsonNumber -> toDoubleOrNull()?.let(::epochMillis)
    is JsonString -> value.toDoubleOrNull()?.let(::epochMillis) ?: parseIsoInstant(value)
    else -> null
}

private fun epochMillis(raw: Double): Long? {
    if (raw.isNaN() || raw.isInfinite() || raw < 0) return null
    val millis = if (raw > 10_000_000_000.0) raw else raw * 1_000.0
    return millis.takeIf { it <= MAX_EPOCH_MILLIS }?.toLong()
}

internal fun parseIsoInstant(value: String): Long? = try {
    OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
} catch (_: DateTimeParseException) {
    try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun JsonObject.nestedValue(path: String): JsonValue? {
    var current: JsonValue = this
    for (component in path.split('.')) {
        current = (current as? JsonObject)?.members?.get(component) ?: return null
    }
    return current
}

/** `jsonFingerprint` on iOS: SHA-256 of the sorted-key serialization. */
internal fun jsonFingerprint(value: JsonValue): String = sha256Hex(
    HostingJson.write(value, sortedKeys = true).toByteArray(StandardCharsets.UTF_8),
)

/** iOS `stableIdentifier`: a provider id, or a namespaced hash of identifying fields. */
internal fun stableIdentifier(existing: String?, namespace: String, values: List<String?>): String? {
    if (!existing.isNullOrEmpty()) return existing
    val components = values.mapNotNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
    if (components.isEmpty()) return null
    val digest = sha256Hex(components.joinToString("\u001F").toByteArray(StandardCharsets.UTF_8))
    return "$namespace-${digest.take(20)}"
}

internal fun fallbackIdentifier(namespace: String, value: JsonValue): String =
    "$namespace-${jsonFingerprint(value).take(20)}"

/** iOS `displayJSON`: strings as-is, structures as sorted-key JSON. */
internal fun displayJson(value: JsonValue?): String? = when (value) {
    null, JsonNull -> null
    is JsonString -> value.value
    else -> HostingJson.write(value, sortedKeys = true)
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String {
    val output = StringBuilder(size * 2)
    forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        output.append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0x0f])
    }
    return output.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
private const val MAX_EPOCH_MILLIS = 253_402_300_799_000.0 // 9999-12-31T23:59:59Z

// endregion
