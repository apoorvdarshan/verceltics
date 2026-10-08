package com.apoorvdarshan.verceltics.data.apicatalog

import com.apoorvdarshan.verceltics.data.hosting.JsonArray
import com.apoorvdarshan.verceltics.data.hosting.JsonBoolean
import com.apoorvdarshan.verceltics.data.hosting.JsonNull
import com.apoorvdarshan.verceltics.data.hosting.JsonNumber
import com.apoorvdarshan.verceltics.data.hosting.JsonObject
import com.apoorvdarshan.verceltics.data.hosting.JsonString
import com.apoorvdarshan.verceltics.data.hosting.JsonValue
import com.apoorvdarshan.verceltics.data.hosting.asArray
import com.apoorvdarshan.verceltics.data.hosting.asObject
import java.util.Locale

/**
 * Port of iOS `RailwayGraphQLTemplateBuilder`: builds safe, editable starter requests from
 * Railway's live GraphQL schema. Required inputs receive type-aware placeholders, optional
 * arguments are omitted, and response selections include useful fields instead of only
 * `__typename`.
 */
object RailwayGraphQLTemplateBuilder {
    /** iOS `liveRailwayCatalog` introspection document, verbatim. */
    val INTROSPECTION_QUERY: String = """
        query VercelticsIntrospection {
          __schema {
            queryType { name }
            mutationType { name }
            types {
              kind name
              enumValues(includeDeprecated: true) { name }
              possibleTypes { name }
              inputFields {
                name description defaultValue
                type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
              }
              fields(includeDeprecated: true) {
                name description isDeprecated deprecationReason
                args {
                  name description defaultValue
                  type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
                }
                type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } }
              }
            }
          }
        }
    """.trimIndent()

    const val LIVE_API_VERSION: String = "Live GraphQL v2"
    const val LIVE_SOURCE_URL: String = "https://docs.railway.com/integrations/api"
    const val LIVE_SOURCE_DESCRIPTION: String =
        "Every query and mutation discovered live from the authenticated Railway GraphQL schema"

    /** The catalog shown when introspection succeeds. */
    fun liveCatalog(schema: JsonObject): ProviderApiCatalog = ProviderApiCatalog(
        id = ProviderApiCatalogIds.RAILWAY,
        title = "Railway",
        apiVersion = LIVE_API_VERSION,
        sourceUrl = LIVE_SOURCE_URL,
        sourceDescription = LIVE_SOURCE_DESCRIPTION,
        operations = operations(schema),
    )

    /** iOS fallback when live discovery fails: the bundled manual request stays usable. */
    fun fallbackCatalog(bundled: ProviderApiCatalog, reason: String): ProviderApiCatalog = bundled.copy(
        apiVersion = "${bundled.apiVersion} · Bundled fallback",
        sourceDescription = "Live schema discovery was unavailable. The bundled manual GraphQL request remains usable. $reason".trim(),
    )

    fun operations(schema: JsonObject): List<ProviderApiOperation> {
        val types = (schema["types"] as? JsonArray)?.items?.map { it.asObject() }
            ?: throw ProviderApiCatalogException("Railway did not return a usable GraphQL schema.")
        val typesByName = types.mapNotNull { type -> (type["name"] as? JsonString)?.value?.let { it to type } }.toMap()
        val queryName = (schema["queryType"].asObject()["name"] as? JsonString)?.value
        val mutationName = (schema["mutationType"].asObject()["name"] as? JsonString)?.value
        val operations = mutableListOf<ProviderApiOperation>()

        for (rootType in types) {
            val rootTypeName = (rootType["name"] as? JsonString)?.value ?: continue
            if (rootTypeName != queryName && rootTypeName != mutationName) continue
            val fields = rootType["fields"] as? JsonArray ?: continue
            val isMutation = rootTypeName == mutationName

            for (fieldValue in fields.items) {
                val field = fieldValue.asObject()
                val fieldName = (field["name"] as? JsonString)?.value ?: continue
                val arguments = field["args"].asArray().map { it.asObject() }
                val declarations = mutableListOf<String>()
                val calls = mutableListOf<String>()
                val variables = linkedMapOf<String, JsonValue>()
                for (argument in arguments.filter(::isRequired)) {
                    val name = (argument["name"] as? JsonString)?.value ?: continue
                    val type = argument["type"] as? JsonObject ?: continue
                    declarations += "\$$name: ${graphQLTypeName(type)}"
                    calls += "$name: \$$name"
                    variables[name] = placeholder(type, typesByName, emptySet())
                }
                val declaration = if (declarations.isEmpty()) "" else "(${declarations.joinToString(", ")})"
                val call = if (calls.isEmpty()) "" else "(${calls.joinToString(", ")})"
                val responseSelection = selection(field["type"].asObject(), typesByName, depth = 0, visited = emptySet())
                val operationKind = if (isMutation) "mutation" else "query"
                val document = "$operationKind Verceltics_$fieldName$declaration { $fieldName$call$responseSelection }"
                val request = ProviderApiJson.pretty(
                    JsonObject(linkedMapOf("query" to JsonString(document), "variables" to JsonObject(variables))),
                )

                var description = (field["description"] as? JsonString)?.value
                    ?: (field["deprecationReason"] as? JsonString)?.value
                    ?: ""
                val optionalArguments = arguments.filterNot(::isRequired).mapNotNull { argument ->
                    val name = (argument["name"] as? JsonString)?.value ?: return@mapNotNull null
                    val type = argument["type"] as? JsonObject ?: return@mapNotNull null
                    "$name: ${graphQLTypeName(type)}"
                }
                if (optionalArguments.isNotEmpty()) {
                    if (description.isNotEmpty()) description += "\n\n"
                    description += "Optional arguments omitted from the safe starter request: ${optionalArguments.joinToString(", ")}."
                }
                if ("REPLACE_ME" in request) {
                    if (description.isNotEmpty()) description += "\n\n"
                    description += "Replace every REPLACE_ME value before sending."
                }

                operations += ProviderApiOperation(
                    id = "railway.$rootTypeName.$fieldName",
                    method = "POST",
                    path = "/graphql/v2",
                    summary = fieldName,
                    description = description,
                    tags = listOf(if (isMutation) "Mutations" else "Queries"),
                    deprecated = (field["isDeprecated"] as? JsonBoolean)?.value == true,
                    parameters = emptyList(),
                    contentTypes = listOf("application/json"),
                    requestBodyRequired = true,
                    bodyTemplate = request,
                    multipartFields = emptyList(),
                )
            }
        }
        return operations.sortedWith(compareBy<ProviderApiOperation>({ it.primaryTag }, { it.summary }))
    }

    private fun graphQLTypeName(value: JsonObject): String {
        val kind = (value["kind"] as? JsonString)?.value.orEmpty()
        val nested = value["ofType"] as? JsonObject
        if (kind == "NON_NULL" && nested != null) return graphQLTypeName(nested) + "!"
        if (kind == "LIST" && nested != null) return "[${graphQLTypeName(nested)}]"
        return (value["name"] as? JsonString)?.value ?: "String"
    }

    private fun isRequired(value: JsonObject): Boolean {
        val type = value["type"] as? JsonObject ?: return false
        if ((type["kind"] as? JsonString)?.value != "NON_NULL") return false
        val defaultValue = value["defaultValue"]
        return defaultValue == null || defaultValue is JsonNull
    }

    private fun placeholder(type: JsonObject, types: Map<String, JsonObject>, visited: Set<String>): JsonValue {
        val kind = (type["kind"] as? JsonString)?.value.orEmpty()
        val nested = type["ofType"] as? JsonObject
        if (kind == "NON_NULL" && nested != null) return placeholder(nested, types, visited)
        if (kind == "LIST") return JsonArray(emptyList())

        val name = (type["name"] as? JsonString)?.value ?: "String"
        val definition = types[name]
        val resolvedKind = (definition?.get("kind") as? JsonString)?.value ?: kind
        if (resolvedKind == "ENUM") {
            return JsonString(
                (definition?.get("enumValues").asArray().firstOrNull().asObject()["name"] as? JsonString)?.value ?: "REPLACE_ME",
            )
        }
        if (resolvedKind == "INPUT_OBJECT") {
            if (name in visited) return JsonObject.EMPTY
            val nextVisited = visited + name
            val members = linkedMapOf<String, JsonValue>()
            definition?.get("inputFields").asArray().map { it.asObject() }.filter(::isRequired).forEach { field ->
                val fieldName = (field["name"] as? JsonString)?.value ?: return@forEach
                val fieldType = field["type"] as? JsonObject ?: return@forEach
                members[fieldName] = placeholder(fieldType, types, nextVisited)
            }
            return JsonObject(members)
        }

        return when (name.lowercase(Locale.ROOT)) {
            "int", "bigint", "long", "positiveint", "nonnegativeint" -> JsonNumber("1")
            "float", "decimal" -> JsonNumber("1.0")
            "boolean" -> JsonBoolean(false)
            "json", "jsonobject" -> JsonObject.EMPTY
            "datetime", "timestamp" -> JsonString("2026-01-01T00:00:00.000Z")
            "date" -> JsonString("2026-01-01")
            "url", "uri" -> JsonString("https://example.com")
            "email" -> JsonString("user@example.com")
            else -> JsonString("REPLACE_ME")
        }
    }

    private fun selection(type: JsonObject, types: Map<String, JsonObject>, depth: Int, visited: Set<String>): String {
        val name = namedTypeName(type) ?: return ""
        val definition = types[name] ?: return ""
        val kind = (definition["kind"] as? JsonString)?.value.orEmpty()
        if (kind == "SCALAR" || kind == "ENUM") return ""
        if (depth >= 4 || name in visited) return " { __typename }"

        val nextVisited = visited + name
        if (kind == "UNION") {
            val fragments = definition["possibleTypes"].asArray()
                .mapNotNull { (it.asObject()["name"] as? JsonString)?.value }
                .take(2)
                .mapNotNull { possibleType ->
                    val possibleDefinition = types[possibleType] ?: return@mapNotNull null
                    val nested = selection(
                        JsonObject(
                            linkedMapOf(
                                "kind" to JsonString((possibleDefinition["kind"] as? JsonString)?.value ?: "OBJECT"),
                                "name" to JsonString(possibleType),
                            ),
                        ),
                        types,
                        depth + 1,
                        nextVisited,
                    )
                    "... on $possibleType$nested"
                }
            return if (fragments.isEmpty()) " { __typename }" else " { __typename ${fragments.joinToString(" ")} }"
        }

        val fields = definition["fields"].asArray().map { it.asObject() }.filter { field ->
            if ((field["isDeprecated"] as? JsonBoolean)?.value == true) return@filter false
            field["args"].asArray().none { isRequired(it.asObject()) }
        }
        val sortedFields = fields.sortedBy(::outputPriority)
        val selections = mutableListOf<String>()

        for (field in sortedFields) {
            if (!isLeaf(field["type"].asObject(), types)) continue
            val fieldName = (field["name"] as? JsonString)?.value ?: continue
            selections += fieldName
            if (selections.size == 8) break
        }

        var nestedCount = 0
        for (field in sortedFields) {
            if (isLeaf(field["type"].asObject(), types)) continue
            if (nestedCount >= 3) continue
            val fieldName = (field["name"] as? JsonString)?.value ?: continue
            val fieldType = field["type"] as? JsonObject ?: continue
            val nested = selection(fieldType, types, depth + 1, nextVisited)
            if (nested.isEmpty()) continue
            selections += "$fieldName$nested"
            nestedCount += 1
        }
        return if (selections.isEmpty()) " { __typename }" else " { ${selections.joinToString(" ")} }"
    }

    private fun namedTypeName(value: JsonObject): String? {
        (value["name"] as? JsonString)?.value?.let { return it }
        val nested = value["ofType"] as? JsonObject ?: return null
        return namedTypeName(nested)
    }

    private fun isLeaf(value: JsonObject, types: Map<String, JsonObject>): Boolean {
        val name = namedTypeName(value) ?: return true
        val kind = (types[name]?.get("kind") as? JsonString)?.value ?: (value["kind"] as? JsonString)?.value
        return kind == "SCALAR" || kind == "ENUM"
    }

    private fun outputPriority(field: JsonObject): String {
        val name = (field["name"] as? JsonString)?.value.orEmpty()
        val rank = PREFERRED_OUTPUT_FIELDS.indexOf(name).let { if (it < 0) PREFERRED_OUTPUT_FIELDS.size else it }
        return String.format(Locale.ROOT, "%03d-%s", rank, name.lowercase(Locale.ROOT))
    }

    private val PREFERRED_OUTPUT_FIELDS = listOf(
        "id", "name", "status", "success", "message", "url", "email",
        "createdAt", "updatedAt", "totalCount", "hasNextPage", "endCursor",
        "edges", "nodes", "node", "pageInfo", "data", "results",
    )
}
