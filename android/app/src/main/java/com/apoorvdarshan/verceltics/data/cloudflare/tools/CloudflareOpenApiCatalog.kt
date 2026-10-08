package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** iOS `CloudflareOpenAPICatalog`, generated from Cloudflare's official OpenAPI schema. */
class CloudflareOpenApiCatalog(
    val schemaVersion: Int,
    val openApiVersion: String,
    val apiVersion: String,
    val sourceCommit: String,
    val sourceUrl: String,
    val operationCount: Int,
    val operations: List<CloudflareOpenApiOperation>,
) {
    private val byId: Map<String, CloudflareOpenApiOperation> = operations.associateBy { it.id }

    val tagCount: Int by lazy { operations.flatMap { it.tags }.toSet().size }

    val pathCount: Int by lazy { operations.map { it.path }.toSet().size }

    /** iOS tag directory: grouped by primary tag, most operations first, then by name. */
    val tagSummaries: List<CloudflareApiTagSummary> by lazy {
        operations.groupBy { it.primaryTag }
            .map { (tag, grouped) ->
                CloudflareApiTagSummary(tag, grouped.size, grouped.count { it.isMutation })
            }
            .sortedWith(
                compareByDescending<CloudflareApiTagSummary> { it.operationCount }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
    }

    fun operation(id: String): CloudflareOpenApiOperation? = byId[id]

    fun operationsForTag(tag: String): List<CloudflareOpenApiOperation> = operations.filter { it.primaryTag == tag }

    fun search(query: String, filter: CloudflareOperationFilter): List<CloudflareOpenApiOperation> =
        operations.filter { filter.includes(it) && it.matches(query) }

    fun visibleTags(filter: CloudflareOperationFilter): List<CloudflareApiTagSummary> = when (filter) {
        CloudflareOperationFilter.ALL -> tagSummaries
        CloudflareOperationFilter.READ -> tagSummaries.filter { it.operationCount > it.writeCount }
        CloudflareOperationFilter.WRITE -> tagSummaries.filter { it.writeCount > 0 }
    }
}

data class CloudflareApiTagSummary(val name: String, val operationCount: Int, val writeCount: Int)

enum class CloudflareOperationFilter(val label: String) {
    ALL("All"),
    READ("Read"),
    WRITE("Write"),
    ;

    fun includes(operation: CloudflareOpenApiOperation): Boolean = when (this) {
        ALL -> true
        READ -> !operation.isMutation
        WRITE -> operation.isMutation
    }
}

enum class CloudflareOpenApiParameterLocation(val wireName: String) {
    PATH("path"),
    QUERY("query"),
    HEADER("header"),
    ;

    companion object {
        fun parse(value: String): CloudflareOpenApiParameterLocation? = entries.firstOrNull { it.wireName == value }
    }
}

data class CloudflareOpenApiParameter(
    val name: String,
    val location: CloudflareOpenApiParameterLocation,
    val required: Boolean,
    val description: String,
    val type: String?,
    val format: String?,
    val defaultValue: ProviderJsonValue?,
    val example: ProviderJsonValue?,
    val minimum: Double?,
    val maximum: Double?,
    val minLength: Int?,
    val maxLength: Int?,
    val pattern: String?,
    val enumValues: List<ProviderJsonValue>?,
) {
    val key: String get() = "${location.wireName}:$name"

    /** iOS `suggestedValue`: default, then example, then the first enum value. */
    val suggestedValue: String
        get() = defaultValue?.let(CloudflareCatalogText::of)
            ?: example?.let(CloudflareCatalogText::of)
            ?: enumValues?.firstOrNull()?.let(CloudflareCatalogText::of)
            ?: ""

    val enumOptions: List<String> get() = enumValues.orEmpty().map(CloudflareCatalogText::of).distinct()

    val typeLabel: String get() = format ?: type ?: "value"
}

data class CloudflareOpenApiOperation(
    val id: String,
    val method: CloudflareHttpMethod,
    val path: String,
    val summary: String,
    val description: String,
    val tags: List<String>,
    val deprecated: Boolean,
    val permissions: List<String>,
    val supportsGlobalKey: Boolean,
    val supportsApiToken: Boolean,
    val supportsUserServiceKey: Boolean,
    val parameters: List<CloudflareOpenApiParameter>,
    val contentTypes: List<String>,
    val requestBodyRequired: Boolean,
    val bodyTemplate: String,
    val multipartFields: List<CloudflareMultipartFieldSpec>,
) {
    val primaryTag: String get() = tags.firstOrNull() ?: "Other"

    val isMutation: Boolean get() = method.isMutation

    val isMultipart: Boolean
        get() = contentTypes.any { it.contains("multipart/form-data", ignoreCase = true) }

    private val haystack: String by lazy {
        (listOf(summary, description, path, method.name, id) + tags + permissions)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
    }

    /** iOS `matches`: every whitespace-separated term must appear somewhere in the operation. */
    fun matches(query: String): Boolean {
        val terms = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase(Locale.ROOT) }
        if (terms.isEmpty()) return true
        return terms.all(haystack::contains)
    }
}

/** iOS `CloudflareJSONValue.catalogText`. */
object CloudflareCatalogText {
    fun of(value: ProviderJsonValue): String = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> value.text
        is ProviderJsonValue.Bool -> if (value.value) "true" else "false"
        ProviderJsonValue.Null -> ""
        is ProviderJsonValue.Obj, is ProviderJsonValue.Arr -> ProviderJsonWriter.write(value)
    }
}

/** Strict-but-tolerant decoder for the bundled `CloudflareAPICatalog.json`. */
object CloudflareOpenApiCatalogParser {
    fun parse(input: InputStream): CloudflareOpenApiCatalog =
        parse(input.use { String(it.readBytes(), StandardCharsets.UTF_8) })

    fun parse(text: String): CloudflareOpenApiCatalog {
        val root = ProviderJsonParser.parse(text) as? ProviderJsonValue.Obj
            ?: malformed("The bundled Cloudflare API catalog is not a JSON object.")
        val operations = root["operations"]?.arrayValue ?: malformed("The bundled Cloudflare API catalog has no operations.")
        val parsed = operations.mapNotNull(::operation)
        return CloudflareOpenApiCatalog(
            schemaVersion = root.int("schemaVersion") ?: 1,
            openApiVersion = root.string("openAPIVersion").orEmpty(),
            apiVersion = root.string("apiVersion").orEmpty(),
            sourceCommit = root.string("sourceCommit").orEmpty(),
            sourceUrl = root.string("sourceURL").orEmpty(),
            operationCount = root.int("operationCount") ?: parsed.size,
            operations = parsed,
        )
    }

    private fun operation(value: ProviderJsonValue): CloudflareOpenApiOperation? {
        val id = value.string("id") ?: return null
        val method = value.string("method")?.let(CloudflareHttpMethod::parse) ?: return null
        val path = value.string("path") ?: return null
        return CloudflareOpenApiOperation(
            id = id,
            method = method,
            path = path,
            summary = value.string("summary").orEmpty().ifEmpty { path },
            description = value.string("description").orEmpty(),
            tags = value.strings("tags"),
            deprecated = value.bool("deprecated") ?: false,
            permissions = value.strings("permissions"),
            supportsGlobalKey = value.bool("supportsGlobalKey") ?: false,
            supportsApiToken = value.bool("supportsAPIToken") ?: true,
            supportsUserServiceKey = value.bool("supportsUserServiceKey") ?: false,
            parameters = value["parameters"]?.arrayValue.orEmpty().mapNotNull(::parameter),
            contentTypes = value.strings("contentTypes"),
            requestBodyRequired = value.bool("requestBodyRequired") ?: false,
            bodyTemplate = value.string("bodyTemplate").orEmpty(),
            multipartFields = value["multipartFields"]?.arrayValue.orEmpty().mapNotNull(::multipartField),
        )
    }

    private fun parameter(value: ProviderJsonValue): CloudflareOpenApiParameter? {
        val name = value.string("name") ?: return null
        val location = value.string("location")?.let(CloudflareOpenApiParameterLocation::parse) ?: return null
        return CloudflareOpenApiParameter(
            name = name,
            location = location,
            required = value.bool("required") ?: false,
            description = value.string("description").orEmpty(),
            type = value.string("type"),
            format = value.string("format"),
            defaultValue = value["default"],
            example = value["example"],
            minimum = value["minimum"]?.numberValue,
            maximum = value["maximum"]?.numberValue,
            minLength = value.int("minLength"),
            maxLength = value.int("maxLength"),
            pattern = value.string("pattern"),
            enumValues = value["enumValues"]?.arrayValue,
        )
    }

    private fun multipartField(value: ProviderJsonValue): CloudflareMultipartFieldSpec? {
        val name = value.string("name") ?: return null
        val suggested = value["default"]?.let(CloudflareCatalogText::of)
            ?: value["example"]?.let(CloudflareCatalogText::of)
            ?: value["enumValues"]?.arrayValue?.firstOrNull()?.let(CloudflareCatalogText::of)
            ?: ""
        return CloudflareMultipartFieldSpec(
            name = name,
            required = value.bool("required") ?: false,
            isFile = value.bool("isFile") ?: false,
            type = value.string("type"),
            format = value.string("format"),
            description = value.string("description"),
            suggestedValue = suggested,
        )
    }

    private fun ProviderJsonValue.string(key: String): String? = (this[key] as? ProviderJsonValue.Str)?.value

    private fun ProviderJsonValue.bool(key: String): Boolean? = (this[key] as? ProviderJsonValue.Bool)?.value

    private fun ProviderJsonValue.int(key: String): Int? =
        (this[key] as? ProviderJsonValue.Num)?.decimal?.let { runCatching { it.intValueExact() }.getOrNull() }

    private fun ProviderJsonValue.strings(key: String): List<String> =
        this[key]?.arrayValue.orEmpty().mapNotNull { (it as? ProviderJsonValue.Str)?.value }

    private fun malformed(message: String): Nothing =
        throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_RESPONSE, message)
}

/**
 * iOS `CloudflareOpenAPICatalogStore`: loads the 4 MB catalog once per process. [load] blocks, so
 * callers must run it off the main thread.
 */
class CloudflareOpenApiCatalogStore(private val source: () -> InputStream) {
    @Volatile
    private var cached: CloudflareOpenApiCatalog? = null

    val loadedCatalog: CloudflareOpenApiCatalog? get() = cached

    @Synchronized
    fun load(): CloudflareOpenApiCatalog {
        cached?.let { return it }
        val catalog = try {
            CloudflareOpenApiCatalogParser.parse(source())
        } catch (error: CloudflareToolsException) {
            throw error
        } catch (_: Exception) {
            throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "The bundled Cloudflare API catalog could not be read.",
            )
        }
        cached = catalog
        return catalog
    }

    companion object {
        const val ASSET_NAME = "CloudflareAPICatalog.json"
        const val LICENSE_ASSET_NAME = "CloudflareAPISchemaLicense.txt"
    }
}

/** iOS `CloudflareGeneratedOperationView`: initial parameter values and the explorer preset. */
object CloudflareOperationRequestBuilder {
    /** Account and zone path parameters are prefilled from the current context. */
    fun initialValues(
        operation: CloudflareOpenApiOperation,
        accountId: String,
        firstZoneId: String?,
    ): Map<String, String> = operation.parameters.associate { parameter ->
        val lowercased = parameter.name.lowercase(Locale.ROOT)
        val value = when {
            parameter.location == CloudflareOpenApiParameterLocation.PATH && lowercased.contains("account") -> accountId
            parameter.location == CloudflareOpenApiParameterLocation.PATH && lowercased.contains("zone") &&
                firstZoneId != null -> firstZoneId
            else -> parameter.suggestedValue
        }
        parameter.key to value
    }

    fun initialContentType(operation: CloudflareOpenApiOperation): String =
        operation.contentTypes.firstOrNull()?.takeIf(String::isNotEmpty) ?: "application/json"

    fun preset(
        operation: CloudflareOpenApiOperation,
        values: Map<String, String>,
        body: String,
        contentType: String,
    ): CloudflareApiPreset {
        val query = operation.parameters
            .filter { it.location == CloudflareOpenApiParameterLocation.QUERY && values[it.key].orEmpty().isNotEmpty() }
            .joinToString("\n") { "${it.name}=${values[it.key].orEmpty()}" }
        val headers = operation.parameters
            .filter { it.location == CloudflareOpenApiParameterLocation.HEADER && values[it.key].orEmpty().isNotEmpty() }
            .joinToString("\n") { "${it.name}: ${values[it.key].orEmpty()}" }
        var path = operation.path
        operation.parameters.filter { it.location == CloudflareOpenApiParameterLocation.PATH }.forEach { parameter ->
            val raw = values[parameter.key].orEmpty()
            val value = if (raw.isEmpty()) {
                parameter.name.uppercase(Locale.ROOT)
            } else {
                CloudflareExplorerRequestBuilder.encodePathSegment(raw)
            }
            path = path.replace("{${parameter.name}}", value)
        }
        return CloudflareApiPreset(
            id = operation.id,
            title = operation.summary,
            summary = operation.description.ifEmpty { operation.path },
            method = operation.method,
            path = path,
            query = query,
            headers = headers,
            body = body,
            contentType = contentType,
            multipartFields = operation.multipartFields,
            permissions = operation.permissions,
        )
    }
}
