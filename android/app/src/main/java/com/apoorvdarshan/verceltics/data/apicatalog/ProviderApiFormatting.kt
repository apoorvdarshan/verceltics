package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.HostingJson
import com.apoorvdarshan.verceltics.data.hosting.HostingResponseFormatException
import com.apoorvdarshan.verceltics.data.hosting.JsonArray
import com.apoorvdarshan.verceltics.data.hosting.JsonBoolean
import com.apoorvdarshan.verceltics.data.hosting.JsonNull
import com.apoorvdarshan.verceltics.data.hosting.JsonNumber
import com.apoorvdarshan.verceltics.data.hosting.JsonObject
import com.apoorvdarshan.verceltics.data.hosting.JsonString
import com.apoorvdarshan.verceltics.data.hosting.JsonValue
import java.util.Locale

/** Pretty JSON with sorted keys, matching iOS `JSONSerialization` `.prettyPrinted, .sortedKeys`. */
object ProviderApiJson {
    fun pretty(value: JsonValue, sortedKeys: Boolean = true): String =
        StringBuilder().also { write(it, value, sortedKeys, 0) }.toString()

    private fun write(output: StringBuilder, value: JsonValue, sortedKeys: Boolean, depth: Int) {
        when (value) {
            is JsonObject -> {
                if (value.members.isEmpty()) {
                    output.append("{}")
                    return
                }
                val entries = if (sortedKeys) value.members.entries.sortedBy { it.key } else value.members.entries
                output.append("{\n")
                entries.forEachIndexed { index, (key, member) ->
                    indent(output, depth + 1)
                    output.append(HostingJson.quote(key)).append(": ")
                    write(output, member, sortedKeys, depth + 1)
                    if (index < entries.size - 1) output.append(',')
                    output.append('\n')
                }
                indent(output, depth)
                output.append('}')
            }
            is JsonArray -> {
                if (value.items.isEmpty()) {
                    output.append("[]")
                    return
                }
                output.append("[\n")
                value.items.forEachIndexed { index, item ->
                    indent(output, depth + 1)
                    write(output, item, sortedKeys, depth + 1)
                    if (index < value.items.size - 1) output.append(',')
                    output.append('\n')
                }
                indent(output, depth)
                output.append(']')
            }
            is JsonString -> output.append(HostingJson.quote(value.value))
            is JsonNumber -> output.append(value.raw)
            is JsonBoolean -> output.append(if (value.value) "true" else "false")
            JsonNull -> output.append("null")
        }
    }

    private fun indent(output: StringBuilder, depth: Int) {
        repeat(depth) { output.append("  ") }
    }
}

/**
 * Response viewer formatting (iOS `pretty`): JSON is pretty-printed with sorted keys, XML is
 * indented, anything else is shown as received. Very large bodies are shown unformatted.
 */
object ProviderApiResponseFormatter {
    const val EMPTY_BODY: String = "(empty response)"
    const val MAXIMUM_PRETTY_CHARACTERS: Int = 2_000_000

    fun formatBody(body: String, contentType: String? = null): String {
        if (body.isEmpty()) return EMPTY_BODY
        if (body.length > MAXIMUM_PRETTY_CHARACTERS) return body
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return body
        prettyJsonOrNull(trimmed)?.let { return it }
        val type = contentType.orEmpty().lowercase(Locale.ROOT)
        if (trimmed.startsWith("<") && ("xml" in type || trimmed.startsWith("<?xml") || !("html" in type))) {
            prettyXmlOrNull(trimmed)?.let { return it }
        }
        return body
    }

    fun prettyJsonOrNull(text: String): String? {
        val first = text.firstOrNull() ?: return null
        if (first != '{' && first != '[' && first != '"' && first != '-' && !first.isDigit() &&
            text != "true" && text != "false" && text != "null"
        ) {
            return null
        }
        return try {
            ProviderApiJson.pretty(HostingJson.parse(text))
        } catch (_: HostingResponseFormatException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Indents well-formed XML; returns null for anything it cannot balance. */
    fun prettyXmlOrNull(text: String): String? {
        val tokens = xmlTokens(text) ?: return null
        val output = StringBuilder(text.length + text.length / 4)
        var depth = 0
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when (token.kind) {
                XmlKind.OPEN -> {
                    val text0 = tokens.getOrNull(index + 1)
                    val close = tokens.getOrNull(index + 2)
                    if (text0?.kind == XmlKind.TEXT && close?.kind == XmlKind.CLOSE) {
                        line(output, depth, token.value + text0.value + close.value)
                        index += 3
                        continue
                    }
                    if (text0?.kind == XmlKind.CLOSE) {
                        line(output, depth, token.value + text0.value)
                        index += 2
                        continue
                    }
                    line(output, depth, token.value)
                    depth += 1
                }
                XmlKind.CLOSE -> {
                    depth -= 1
                    if (depth < 0) return null
                    line(output, depth, token.value)
                }
                XmlKind.TEXT, XmlKind.STANDALONE -> line(output, depth, token.value)
            }
            index += 1
        }
        if (depth != 0) return null
        return output.toString().trimEnd()
    }

    /** First [limit] characters for on-screen display, with whether anything was cut. */
    fun displayText(text: String, limit: Int = MAXIMUM_DISPLAY_CHARACTERS): Pair<String, Boolean> =
        if (text.length <= limit) text to false else text.substring(0, limit) to true

    /** iOS response "Headers" segment: `Name: value`, sorted case-insensitively. */
    fun headerText(headers: List<Pair<String, String>>): String =
        headers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
            .joinToString("\n") { (name, value) -> "$name: $value" }

    const val MAXIMUM_DISPLAY_CHARACTERS: Int = 100_000

    private enum class XmlKind { OPEN, CLOSE, STANDALONE, TEXT }

    private class XmlToken(val kind: XmlKind, val value: String)

    private fun xmlTokens(text: String): List<XmlToken>? {
        val tokens = mutableListOf<XmlToken>()
        var index = 0
        while (index < text.length) {
            if (text[index] == '<') {
                val (end, kind) = when {
                    text.startsWith("<!--", index) -> text.indexOf("-->", index).let { if (it < 0) return null else it + 3 } to XmlKind.STANDALONE
                    text.startsWith("<![CDATA[", index) -> text.indexOf("]]>", index).let { if (it < 0) return null else it + 3 } to XmlKind.TEXT
                    text.startsWith("<?", index) -> text.indexOf("?>", index).let { if (it < 0) return null else it + 2 } to XmlKind.STANDALONE
                    text.startsWith("<!", index) -> text.indexOf('>', index).let { if (it < 0) return null else it + 1 } to XmlKind.STANDALONE
                    else -> {
                        val close = tagEnd(text, index) ?: return null
                        val tag = text.substring(index, close)
                        val kind = when {
                            tag.startsWith("</") -> XmlKind.CLOSE
                            tag.endsWith("/>") -> XmlKind.STANDALONE
                            else -> XmlKind.OPEN
                        }
                        close to kind
                    }
                }
                tokens += XmlToken(kind, text.substring(index, end))
                index = end
            } else {
                val next = text.indexOf('<', index).let { if (it < 0) text.length else it }
                val value = text.substring(index, next).trim()
                if (value.isNotEmpty()) tokens += XmlToken(XmlKind.TEXT, value)
                index = next
            }
        }
        return tokens.takeIf { it.any { token -> token.kind != XmlKind.TEXT } }
    }

    /** End (exclusive) of the tag starting at [start], honoring quoted attribute values. */
    private fun tagEnd(text: String, start: Int): Int? {
        var quote: Char? = null
        var index = start + 1
        while (index < text.length) {
            val character = text[index]
            when {
                quote != null -> if (character == quote) quote = null
                character == '"' || character == '\'' -> quote = character
                character == '>' -> return index + 1
                character == '<' -> return null
            }
            index += 1
        }
        return null
    }

    private fun line(output: StringBuilder, depth: Int, value: String) {
        repeat(depth) { output.append("  ") }
        output.append(value).append('\n')
    }
}
