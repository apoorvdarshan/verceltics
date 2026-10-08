package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.network.CancelableCall
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue

/** iOS `CloudflareGraphQLScope`. */
enum class CloudflareGraphQLScope(val label: String, val rootType: String) {
    ZONE("Zone", "Zone"),
    ACCOUNT("Account", "Account"),
}

/** iOS `CloudflareGraphQLDataset`: plan availability and limits for one analytics dataset. */
data class CloudflareGraphQLDataset(
    val name: String,
    val description: String,
    val enabled: Boolean?,
    val availableFields: List<String>,
    val maxDuration: Long?,
    val notOlderThan: Long?,
    val maxPageSize: Long?,
    val maxNumberOfFields: Long?,
) {
    val isLocked: Boolean get() = enabled == false

    fun matches(query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return name.contains(needle, ignoreCase = true) ||
            description.contains(needle, ignoreCase = true) ||
            availableFields.any { it.contains(needle, ignoreCase = true) }
    }
}

data class CloudflareGraphQLIntrospectionField(
    val name: String,
    val description: String,
    val typeName: String?,
)

/** Pure query builders and parsers behind the iOS GraphQL dataset directory. */
object CloudflareGraphQLDatasets {
    const val SETTINGS_CHUNK_SIZE = 20
    private const val SELECTION_FIELD_LIMIT = 12

    val INTROSPECTION_QUERY: String = """
        query CatalogType(${'$'}name: String!) {
          __type(name: ${'$'}name) {
            fields {
              name
              description
              type { kind name ofType { kind name ofType { kind name } } }
            }
          }
        }
    """.trimIndent()

    fun parseIntrospection(root: ProviderJsonValue?, type: String): List<CloudflareGraphQLIntrospectionField> {
        val fields = root?.get("data")?.get("__type")?.get("fields")?.arrayValue
            ?: throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "Cloudflare did not return fields for GraphQL type $type.",
            )
        return fields.mapNotNull { field ->
            val name = (field["name"] as? ProviderJsonValue.Str)?.value ?: return@mapNotNull null
            CloudflareGraphQLIntrospectionField(
                name = name,
                description = (field["description"] as? ProviderJsonValue.Str)?.value.orEmpty(),
                typeName = deepTypeName(field["type"]),
            )
        }
    }

    /** Unwraps `NON_NULL` / `LIST` wrappers to the named type. */
    fun deepTypeName(type: ProviderJsonValue?): String? {
        if (type !is ProviderJsonValue.Obj) return null
        (type["name"] as? ProviderJsonValue.Str)?.value?.let { return it }
        return deepTypeName(type["ofType"])
    }

    fun settingsQuery(scope: CloudflareGraphQLScope, datasetNames: List<String>): String {
        val selections = datasetNames.joinToString("\n") {
            "$it { enabled availableFields maxDuration notOlderThan maxPageSize maxNumberOfFields }"
        }
        val scopeSelection = when (scope) {
            CloudflareGraphQLScope.ZONE -> "zones(filter: { zoneTag: \$tag })"
            CloudflareGraphQLScope.ACCOUNT -> "accounts(filter: { accountTag: \$tag })"
        }
        return """
            |query DatasetSettings(${'$'}tag: string) {
            |  viewer {
            |    $scopeSelection {
            |      settings {
            |        $selections
            |      }
            |    }
            |  }
            |}
        """.trimMargin()
    }

    fun settingsVariables(scope: CloudflareGraphQLScope, zoneId: String?, accountId: String): Map<String, ProviderJsonValue> =
        mapOf(
            "tag" to ProviderJsonValue.Str(
                when (scope) {
                    CloudflareGraphQLScope.ZONE -> zoneId.orEmpty()
                    CloudflareGraphQLScope.ACCOUNT -> accountId
                },
            ),
        )

    fun parseSettings(root: ProviderJsonValue?, scope: CloudflareGraphQLScope): Map<String, ProviderJsonValue> {
        val scopeKey = if (scope == CloudflareGraphQLScope.ZONE) "zones" else "accounts"
        return root?.get("data")?.get("viewer")?.get(scopeKey)?.arrayValue?.firstOrNull()
            ?.get("settings")?.objectValue.orEmpty()
    }

    fun dataset(field: CloudflareGraphQLIntrospectionField, settings: ProviderJsonValue?): CloudflareGraphQLDataset {
        fun long(key: String): Long? = (settings?.get(key) as? ProviderJsonValue.Num)?.decimal
            ?.let { runCatching { it.longValueExact() }.getOrNull() }
        return CloudflareGraphQLDataset(
            name = field.name,
            description = field.description,
            enabled = (settings?.get("enabled") as? ProviderJsonValue.Bool)?.value,
            availableFields = settings?.get("availableFields")?.arrayValue.orEmpty()
                .mapNotNull { (it as? ProviderJsonValue.Str)?.value },
            maxDuration = long("maxDuration"),
            notOlderThan = long("notOlderThan"),
            maxPageSize = long("maxPageSize"),
            maxNumberOfFields = long("maxNumberOfFields"),
        )
    }

    /** Enabled datasets first, then case-insensitive by name. */
    fun sorted(datasets: List<CloudflareGraphQLDataset>): List<CloudflareGraphQLDataset> = datasets.sortedWith(
        compareBy<CloudflareGraphQLDataset> { if (it.enabled == true) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

    /**
     * iOS `graphqlSelection`: turns up to 12 `a_b_c` field paths into a nested selection set,
     * e.g. `["sum_requests", "dimensions_date"]` → `dimensions { date } sum { requests }`.
     */
    fun selection(fields: List<String>): String {
        val candidates = fields.take(SELECTION_FIELD_LIMIT)
        if (candidates.isEmpty()) return "__typename"
        return render(candidates.map { field -> field.split('_').filter { it.isNotEmpty() } })
    }

    private fun render(paths: List<List<String>>): String {
        val groups = paths.filter { it.isNotEmpty() }.groupBy { it.first() }
        return groups.keys.sorted().joinToString(" ") { key ->
            val remainders = groups.getValue(key).map { it.drop(1) }
            val hasDirectField = remainders.any { it.isEmpty() }
            val nested = remainders.filter { it.isNotEmpty() }
            if (nested.isEmpty() || hasDirectField) key else "$key { ${render(nested)} }"
        }
    }

    /** iOS dataset-detail preset: a read-only GraphQL query that opens in the explorer. */
    fun preset(
        dataset: CloudflareGraphQLDataset,
        scope: CloudflareGraphQLScope,
        accountId: String,
        zoneId: String?,
    ): CloudflareApiPreset {
        val scopeName = if (scope == CloudflareGraphQLScope.ZONE) "zones" else "accounts"
        val tagName = if (scope == CloudflareGraphQLScope.ZONE) "zoneTag" else "accountTag"
        val tag = if (scope == CloudflareGraphQLScope.ZONE) zoneId ?: "ZONE_ID" else accountId
        val query = "query { viewer { $scopeName(filter: { $tagName: \"$tag\" }) { ${dataset.name}(limit: 10) " +
            "{ ${selection(dataset.availableFields)} } } } }"
        return CloudflareApiPreset(
            id = "graphql-${dataset.name}",
            title = dataset.name,
            summary = "Edit filters or fields, then query this live Cloudflare GraphQL dataset.",
            method = CloudflareHttpMethod.POST,
            path = "/graphql",
            body = "{\n  \"query\": ${CloudflarePrettyJson.quoted(query)}\n}",
            readOnlyGraphQL = true,
            permissions = listOf(requiredPermission(scope)),
        )
    }

    fun requiredPermission(scope: CloudflareGraphQLScope): String = when (scope) {
        CloudflareGraphQLScope.ZONE -> "Analytics Read"
        CloudflareGraphQLScope.ACCOUNT -> "Account Analytics Read"
    }

    /** iOS `durationLabel`: abbreviated days/hours or hours/minutes, at most two units. */
    fun durationLabel(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "Not returned"
        val parts = if (seconds >= 86_400) {
            listOf(seconds / 86_400 to "d", (seconds % 86_400) / 3_600 to "h")
        } else {
            listOf(seconds / 3_600 to "h", (seconds % 3_600) / 60 to "m")
        }
        val shown = parts.filter { it.first > 0 }
        return if (shown.isEmpty()) "${seconds}s" else shown.joinToString(" ") { "${it.first}${it.second}" }
    }
}

/**
 * iOS `CloudflareGraphQLDatasetCatalogViewModel.load`: introspects the scope's root type, then its
 * settings type, then reads every dataset's settings in chunks of 20.
 */
class CloudflareGraphQLDatasetLoader(private val api: CloudflareToolsApi) {
    fun newLoadCall(
        token: SecretValue,
        scope: CloudflareGraphQLScope,
        accountId: String,
        zoneId: String?,
    ): CancelableCall<List<CloudflareGraphQLDataset>> = SequentialCloudflareCall {
        val permission = CloudflareGraphQLDatasets.requiredPermission(scope)
        fun introspect(type: String): List<CloudflareGraphQLIntrospectionField> {
            val response = child(
                api.newGraphQLCall(
                    token,
                    CloudflareGraphQLDatasets.INTROSPECTION_QUERY,
                    mapOf("name" to ProviderJsonValue.Str(type)),
                    permission,
                ),
            )
            return CloudflareGraphQLDatasets.parseIntrospection(response.parsedJson(), type)
        }

        val rootFields = introspect(scope.rootType)
        val settingsType = rootFields.firstOrNull { it.name == "settings" }?.typeName
            ?: throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "Cloudflare did not expose a settings type for ${scope.label.lowercase()} analytics.",
            )
        val settingsFields = introspect(settingsType).filter { it.name != "__typename" }
        val settings = LinkedHashMap<String, ProviderJsonValue>()
        settingsFields.chunked(CloudflareGraphQLDatasets.SETTINGS_CHUNK_SIZE).forEach { chunk ->
            val response = child(
                api.newGraphQLCall(
                    token,
                    CloudflareGraphQLDatasets.settingsQuery(scope, chunk.map { it.name }),
                    CloudflareGraphQLDatasets.settingsVariables(scope, zoneId, accountId),
                    permission,
                ),
            )
            settings.putAll(CloudflareGraphQLDatasets.parseSettings(response.parsedJson(), scope))
        }
        CloudflareGraphQLDatasets.sorted(
            settingsFields.map { field -> CloudflareGraphQLDatasets.dataset(field, settings[field.name]) },
        )
    }
}
