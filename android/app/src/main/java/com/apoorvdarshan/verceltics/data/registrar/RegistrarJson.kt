package com.apoorvdarshan.verceltics.data.registrar

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class RegistrarJsonException(message: String) : RuntimeException(message)

/**
 * Strict, bounded RFC 8259 parser producing the same loose value tree the iOS normalization code
 * reads from `JSONSerialization`: objects are `Map<String, Any?>`, arrays `List<Any?>`, numbers
 * [Long] or [Double], plus [String], [Boolean] and `null`.
 *
 * It is pure Kotlin so registrar normalization stays verifiable in JVM unit tests.
 */
internal object RegistrarJson {
    private const val MAX_DEPTH = 64
    private const val MAX_STRING_CHARACTERS = 1_048_576
    private const val MAX_CONTAINER_ITEMS = 500_000

    fun parse(bytes: ByteArray): Any? {
        val text = decodeUtf8(bytes)
        return Parser(text).parseDocument()
    }

    fun parse(text: String): Any? = Parser(text).parseDocument()

    /** Strict UTF-8 decoding; malformed input is a decoding failure rather than U+FFFD. */
    fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars: CharBuffer = try {
            decoder.decode(ByteBuffer.wrap(bytes))
        } catch (_: CharacterCodingException) {
            throw RegistrarJsonException("Response is not UTF-8.")
        }
        return try {
            chars.toString()
        } finally {
            if (chars.hasArray()) chars.array().fill('\u0000')
        }
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Any? {
            if (text.startsWith('﻿')) index = 1
            skipWhitespace()
            if (index >= text.length) fail("The response was empty.")
            val value = parseValue(depth = 0)
            skipWhitespace()
            if (index != text.length) fail("Unexpected data after the JSON value.")
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON nesting is too deep.")
            if (index >= text.length) fail("Unexpected end of JSON.")
            return when (val character = text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (character == '-' || character in '0'..'9') {
                    parseNumber()
                } else {
                    fail("Unexpected character in JSON.")
                }
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            index += 1
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected a JSON object key.")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("Expected ':' after a JSON object key.")
                index += 1
                skipWhitespace()
                result[key] = parseValue(depth + 1)
                if (result.size > MAX_CONTAINER_ITEMS) fail("JSON object is too large.")
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return result
                    }
                    else -> fail("Expected ',' or '}' in a JSON object.")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            index += 1
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return result
            }
            while (true) {
                skipWhitespace()
                result += parseValue(depth + 1)
                if (result.size > MAX_CONTAINER_ITEMS) fail("JSON array is too large.")
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return result
                    }
                    else -> fail("Expected ',' or ']' in a JSON array.")
                }
            }
        }

        private fun parseString(): String {
            index += 1
            val builder = StringBuilder()
            while (true) {
                if (index >= text.length) fail("Unterminated JSON string.")
                val character = text[index++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> appendEscape(builder)
                    character < ' ' -> fail("Unescaped control character in a JSON string.")
                    else -> builder.append(character)
                }
                if (builder.length > MAX_STRING_CHARACTERS) fail("JSON string is too long.")
            }
        }

        private fun appendEscape(builder: StringBuilder) {
            if (index >= text.length) fail("Unterminated JSON escape.")
            when (val escaped = text[index++]) {
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    if (index + 4 > text.length) fail("Invalid JSON unicode escape.")
                    val code = text.substring(index, index + 4).toIntOrNull(16)
                        ?: fail("Invalid JSON unicode escape.")
                    index += 4
                    builder.append(code.toChar())
                }
                else -> fail("Invalid JSON escape '\\$escaped'.")
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index += 1
            when {
                peek() == '0' -> index += 1
                peek() in '1'..'9' -> while (peek() in '0'..'9') index += 1
                else -> fail("Invalid JSON number.")
            }
            var integral = true
            if (peek() == '.') {
                integral = false
                index += 1
                if (peek() !in '0'..'9') fail("Invalid JSON number.")
                while (peek() in '0'..'9') index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                if (peek() !in '0'..'9') fail("Invalid JSON number.")
                while (peek() in '0'..'9') index += 1
            }
            val literal = text.substring(start, index)
            if (integral) literal.toLongOrNull()?.let { return it }
            val value = literal.toDoubleOrNull() ?: fail("Invalid JSON number.")
            if (!value.isFinite()) fail("JSON number is out of range.")
            return value
        }

        private fun literal(expected: String, value: Boolean?): Boolean? {
            if (!text.startsWith(expected, index)) fail("Invalid JSON literal.")
            index += expected.length
            return value
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipWhitespace() {
            while (index < text.length) {
                when (text[index]) {
                    ' ', '\t', '\n', '\r' -> index += 1
                    else -> return
                }
            }
        }

        private fun fail(message: String): Nothing = throw RegistrarJsonException(message)
    }
}
