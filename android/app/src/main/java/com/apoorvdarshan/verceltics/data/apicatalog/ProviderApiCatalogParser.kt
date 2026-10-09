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
import com.apoorvdarshan.verceltics.data.hosting.asArray
import com.apoorvdarshan.verceltics.data.hosting.asObject

/**
 * Reads `ProviderAPICatalog.json` (`{schemaVersion, generatedAt, providers: [...]}`).
 *
 * The bundle is 1.6 MB, but a screen only ever needs one provider. [extract] scans the document
 * without allocating a tree, slices out the matching provider object and parses only that slice,
 * which keeps peak memory a fraction of a full decode.
 */
object ProviderApiCatalogParser {
    /** Parses the catalog for [catalogId], or null when the bundle has no such provider. */
    fun extract(document: String, catalogId: String): ProviderApiCatalog? {
        val slice = providerSlices(document).firstOrNull { it.id == catalogId } ?: return null
        return parseProvider(HostingJson.parse(document.substring(slice.start, slice.end)))
    }

    /** Provider ids in bundle order. */
    fun providerIds(document: String): List<String> = providerSlices(document).mapNotNull { it.id }

    /** Full decode of every provider (tests and tooling only; screens use [extract]). */
    fun parseAll(document: String): List<ProviderApiCatalog> =
        HostingJson.parse(document).asObject()["providers"].asArray().map(::parseProvider)

    internal fun parseProvider(value: JsonValue): ProviderApiCatalog {
        val provider = value.asObject()
        val id = provider.text("id")
        if (id.isEmpty()) throw ProviderApiCatalogException("The bundled API catalog has a provider without an id.")
        return ProviderApiCatalog(
            id = id,
            title = provider.text("title").ifEmpty { id },
            apiVersion = provider.text("apiVersion"),
            sourceUrl = provider.text("sourceURL"),
            sourceDescription = provider.text("sourceDescription"),
            operations = provider["operations"].asArray().mapNotNull(::parseOperation),
        )
    }

    private fun parseOperation(value: JsonValue): ProviderApiOperation? {
        val operation = value.asObject()
        val id = operation.text("id")
        val method = operation.text("method").uppercase()
        val path = operation.text("path")
        if (id.isEmpty() || method.isEmpty() || !path.startsWith("/")) return null
        return ProviderApiOperation(
            id = id,
            method = method,
            path = path,
            summary = operation.text("summary").ifEmpty { "$method $path" },
            description = operation.text("description"),
            tags = operation["tags"].asArray().mapNotNull { (it as? JsonString)?.value?.takeIf(String::isNotEmpty) },
            deprecated = (operation["deprecated"] as? JsonBoolean)?.value == true,
            parameters = operation["parameters"].asArray().mapNotNull(::parseParameter),
            contentTypes = operation["contentTypes"].asArray().mapNotNull { (it as? JsonString)?.value?.takeIf(String::isNotBlank) },
            requestBodyRequired = (operation["requestBodyRequired"] as? JsonBoolean)?.value == true,
            bodyTemplate = operation.text("bodyTemplate"),
            multipartFields = operation["multipartFields"].asArray().mapNotNull(::parseMultipartField),
        )
    }

    private fun parseParameter(value: JsonValue): ProviderApiParameter? {
        val parameter = value.asObject()
        val name = parameter.text("name")
        val location = ProviderApiParameterLocation.fromWireValue(parameter.text("location")) ?: return null
        if (name.isEmpty()) return null
        return ProviderApiParameter(
            name = name,
            location = location,
            required = (parameter["required"] as? JsonBoolean)?.value == true,
            description = parameter.text("description"),
            type = parameter.text("type").ifEmpty { "string" },
            example = catalogText(parameter["example"]),
            enumValues = parameter["enumValues"].asArray().map(::catalogText).filter(String::isNotEmpty),
        )
    }

    private fun parseMultipartField(value: JsonValue): ProviderApiMultipartField? {
        val field = value.asObject()
        val name = field.text("name")
        if (name.isEmpty()) return null
        val suggested = field["default"]?.takeUnless { it is JsonNull }
            ?: field["example"]?.takeUnless { it is JsonNull }
            ?: field["enumValues"].asArray().firstOrNull()
        return ProviderApiMultipartField(
            name = name,
            required = (field["required"] as? JsonBoolean)?.value == true,
            isFile = (field["isFile"] as? JsonBoolean)?.value == true,
            type = field.text("type").ifEmpty { null },
            format = field.text("format").ifEmpty { null },
            description = field.text("description").ifEmpty { null },
            suggestedValue = catalogText(suggested),
        )
    }

    /** iOS `CloudflareJSONValue.catalogText`: scalars as text, structures as compact JSON. */
    private fun catalogText(value: JsonValue?): String = when (value) {
        null, JsonNull -> ""
        is JsonString -> value.value
        is JsonNumber -> value.raw
        is JsonBoolean -> if (value.value) "true" else "false"
        is JsonObject, is JsonArray -> HostingJson.write(value)
    }

    private fun JsonObject.text(key: String): String = when (val value = this[key]) {
        is JsonString -> value.value
        is JsonNumber -> value.raw
        else -> ""
    }

    // region Allocation-free scanning

    private class ProviderSlice(val id: String?, val start: Int, val end: Int)

    private fun providerSlices(document: String): List<ProviderSlice> {
        val scanner = JsonScanner(document)
        val slices = mutableListOf<ProviderSlice>()
        scanner.skipByteOrderMark()
        scanner.skipWhitespace()
        scanner.expect('{')
        scanner.forEachMember { key ->
            if (key != "providers") {
                scanner.skipValue()
                return@forEachMember
            }
            scanner.expect('[')
            scanner.forEachElement {
                val start = scanner.index
                var id: String? = null
                scanner.expect('{')
                scanner.forEachMember { providerKey ->
                    if (providerKey == "id" && scanner.peek() == '"') id = scanner.readString() else scanner.skipValue()
                }
                slices += ProviderSlice(id, start, scanner.index)
            }
        }
        return slices
    }

    /** Minimal RFC 8259 tokenizer that can skip values without building them. */
    private class JsonScanner(private val text: String) {
        var index = 0
            private set

        fun skipByteOrderMark() {
            if (text.startsWith("\uFEFF")) index = 1
        }

        fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) index += 1
        }

        fun expect(character: Char) {
            skipWhitespace()
            if (peek() != character) fail("Expected '$character'")
            index += 1
        }

        /** Calls [onMember] positioned at each member value of the object just opened. */
        inline fun forEachMember(onMember: (String) -> Unit) {
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                expect(':')
                skipWhitespace()
                onMember(key)
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        /** Calls [onElement] positioned at each element of the array just opened. */
        inline fun forEachElement(onElement: () -> Unit) {
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return
            }
            while (true) {
                skipWhitespace()
                onElement()
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        fun readString(): String {
            if (peek() != '"') fail("Expected a string")
            index += 1
            val output = StringBuilder()
            while (true) {
                if (index >= text.length) fail("Unterminated string")
                val character = text[index++]
                when {
                    character == '"' -> return output.toString()
                    character == '\\' -> {
                        if (index >= text.length) fail("Unterminated escape")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000C')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Truncated unicode escape")
                                output.append(text.substring(index, index + 4).toIntOrNull(16)?.toChar() ?: fail("Invalid unicode escape"))
                                index += 4
                            }
                            else -> fail("Invalid escape")
                        }
                    }
                    else -> output.append(character)
                }
            }
        }

        fun skipValue() {
            skipWhitespace()
            when (peek()) {
                '{' -> {
                    index += 1
                    forEachMember { skipValue() }
                }
                '[' -> {
                    index += 1
                    forEachElement { skipValue() }
                }
                '"' -> skipString()
                else -> {
                    val start = index
                    while (index < text.length && text[index].let { it.isLetterOrDigit() || it == '-' || it == '+' || it == '.' }) {
                        index += 1
                    }
                    if (index == start) fail("Unexpected character")
                }
            }
        }

        private fun skipString() {
            index += 1
            while (true) {
                if (index >= text.length) fail("Unterminated string")
                when (text[index++]) {
                    '"' -> return
                    '\\' -> index += 1
                }
            }
        }

        fun fail(reason: String): Nothing =
            throw HostingResponseFormatException("The bundled API catalog is invalid ($reason at $index).")
    }

    // endregion
}
