package com.apoorvdarshan.verceltics.data.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonSanitizer
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.text.NumberFormat
import java.util.Locale

/** Normalization helpers shared by every provider's detail workspace (iOS `SiteIntegrationDetailClient`). */
object SiteDetailSupport {
    const val MAXIMUM_PAYLOAD_ROWS: Int = 20_000
    const val MAXIMUM_PAYLOAD_BYTES: Int = 8 * 1_024 * 1_024
    const val MAXIMUM_RAW_RESPONSE_BYTES: Int = 1 * 1_024 * 1_024
    const val MAXIMUM_RETAINED_RAW_PAGES_PER_ENDPOINT: Int = 2
    const val MAXIMUM_PAGINATION_PAGES: Int = 20
    const val MAXIMUM_PAGED_COLLECTION_ROWS: Int = 100_000
    const val MAXIMUM_DETAIL_RESPONSE_BYTES: Int = 16 * 1_024 * 1_024
    const val MEMORY_LIMIT_WARNING: String =
        "Some high-volume rows or raw response fields were omitted to keep this workspace within the on-device memory limit."

    fun rows(value: ProviderJsonValue): List<Map<String, ProviderJsonValue>> {
        value.arrayValue?.let { values ->
            return values.mapIndexed { index, item ->
                item.objectValue ?: mapOf(
                    "index" to ProviderJsonValue.Num.of(index),
                    "value" to item,
                )
            }
        }
        value.objectValue?.let { return listOf(it) }
        if (value.isNull) return emptyList()
        return listOf(mapOf("value" to value))
    }

    fun orderedColumns(rows: List<Map<String, ProviderJsonValue>>, preferred: List<String> = emptyList()): List<String> {
        val all = rows.flatMapTo(HashSet()) { it.keys }
        return preferred.filter(all::contains) + (all - preferred.toSet()).sorted()
    }

    fun flattenedFields(value: ProviderJsonValue, maximumDepth: Int): List<SiteDetailField> {
        val fields = ArrayList<SiteDetailField>()
        fun visit(node: ProviderJsonValue, path: String, depth: Int) {
            val objectValue = node.objectValue
            if (objectValue != null && depth < maximumDepth) {
                objectValue.keys.sorted().forEach { key ->
                    visit(objectValue[key] ?: ProviderJsonValue.Null, if (path.isEmpty()) key else "$path.$key", depth + 1)
                }
            } else {
                fields += SiteDetailField(path, humanized(path), node)
            }
        }
        visit(value, "", 0)
        return fields.filter { it.key.isNotEmpty() }
    }

    fun flattenedObject(value: ProviderJsonValue): Map<String, ProviderJsonValue> {
        val result = LinkedHashMap<String, ProviderJsonValue>()
        fun visit(node: ProviderJsonValue, path: String) {
            val objectValue = node.objectValue
            if (objectValue != null) {
                objectValue.keys.sorted().forEach { key ->
                    visit(objectValue[key] ?: ProviderJsonValue.Null, if (path.isEmpty()) key else "$path.$key")
                }
            } else {
                result[path.ifEmpty { "value" }] = node
            }
        }
        visit(value, "")
        return result
    }

    fun simpleXYSeries(
        id: String,
        title: String,
        rows: List<Map<String, ProviderJsonValue>>,
        xCandidates: List<String>,
        yCandidates: List<String>,
        metricKey: String = "value",
        metricLabel: String = "Value",
    ): SiteDetailSeries? {
        val points = rows.mapNotNull { row ->
            val x = xCandidates.firstNotNullOfOrNull { row[it]?.stringValue } ?: return@mapNotNull null
            val y = yCandidates.firstNotNullOfOrNull { row[it]?.numberValue } ?: return@mapNotNull null
            SiteDetailSeriesPoint(x, mapOf(metricKey to y))
        }
        if (points.isEmpty()) return null
        return SiteDetailSeries(id, title, mapOf(metricKey to metricLabel), points)
    }

    /** iOS detail `humanized`: last dotted component, underscores and camelCase split, capitalized. */
    fun humanized(value: String): String {
        val dotted = value.split('.').lastOrNull() ?: value
        val spaced = dotted.replace('_', ' ').replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        return spaced.split(' ').joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    fun slug(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun distributedLimits(total: Int, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val bounded = total.coerceAtLeast(0)
        val base = bounded / count
        val remainder = bounded % count
        return List(count) { base + if (it < remainder) 1 else 0 }
    }

    /** Generic secret redaction followed by provider-specific configuration redaction. */
    fun sanitize(value: ProviderJsonValue): ProviderJsonValue =
        sanitizeProviderConfiguration(ProviderJsonSanitizer.sanitize(value))

    /**
     * Redacts request configuration that can carry credentials under a non-secret name
     * (UptimeRobot `post_value`/`custom_http_headers`, Better Stack proxy user info).
     */
    fun sanitizeProviderConfiguration(value: ProviderJsonValue): ProviderJsonValue = when (value) {
        is ProviderJsonValue.Obj -> ProviderJsonValue.Obj(
            LinkedHashMap<String, ProviderJsonValue>().also { output ->
                value.fields.forEach { (key, child) ->
                    output[key] = when (ProviderJsonSanitizer.normalizedKey(key)) {
                        "postvalue", "customhttpheaders" -> ProviderJsonValue.Str(ProviderJsonSanitizer.REDACTED)
                        "proxyhost" -> (child as? ProviderJsonValue.Str)?.value
                            ?.let { host -> host.lastIndexOf('@').takeIf { it >= 0 }?.let { ProviderJsonValue.Str(host.substring(it + 1)) } }
                            ?: child
                        else -> sanitizeProviderConfiguration(child)
                    }
                }
            },
        )
        is ProviderJsonValue.Arr -> ProviderJsonValue.Arr(value.items.map(::sanitizeProviderConfiguration))
        else -> value
    }

    // MARK: Aggregate device budget

    /**
     * Applies one row and byte budget to the complete workspace before it reaches UI caches, so
     * several endpoint-level limits can never multiply into hundreds of megabytes.
     */
    fun boundedForDevice(payload: SiteDetailPayload): SiteDetailPayload {
        var remainingRows = MAXIMUM_PAYLOAD_ROWS
        var remainingBytes = (MAXIMUM_PAYLOAD_BYTES - 32 * 1_024).coerceAtLeast(0)
        var didTruncate = false

        val sections = payload.sections.map { section ->
            val fields = ArrayList<SiteDetailField>()
            for (field in section.fields) {
                val cost = estimatedByteCount(field.value) + estimatedStringByteCount(field.key) +
                    estimatedStringByteCount(field.label)
                if (cost > remainingBytes) {
                    didTruncate = true
                    continue
                }
                remainingBytes -= cost
                fields += field
            }
            section.copy(fields = fields)
        }

        val series = payload.series.map { item ->
            val points = ArrayList<SiteDetailSeriesPoint>()
            for (point in item.points) {
                val cost = estimatedByteCount(point)
                if (remainingRows <= 0 || cost > remainingBytes) {
                    didTruncate = true
                    break
                }
                remainingRows -= 1
                remainingBytes -= cost
                points += point
            }
            if (points.size < item.points.size) didTruncate = true
            item.copy(points = points)
        }

        val tables = payload.tables.map { table ->
            val retained = ArrayList<Map<String, ProviderJsonValue>>()
            for (row in table.rows) {
                val cost = estimatedRowByteCount(row)
                if (remainingRows <= 0 || cost > remainingBytes) {
                    didTruncate = true
                    break
                }
                remainingRows -= 1
                remainingBytes -= cost
                retained += row
            }
            val cursor = if (retained.size < table.rows.size) {
                val format = NumberFormat.getIntegerInstance()
                "Showing ${format.format(retained.size)} of ${format.format(table.rows.size)} retained rows (device limit)"
            } else {
                table.nextCursor
            }
            table.copy(rows = retained, nextCursor = cursor)
        }

        val rawResponses = LinkedHashMap<String, ProviderJsonValue>()
        var remainingRawBytes = minOf(MAXIMUM_RAW_RESPONSE_BYTES, remainingBytes)
        for (key in payload.rawResponses.keys.sorted()) {
            if (remainingRawBytes <= 0) break
            val value = payload.rawResponses[key] ?: continue
            val keyCost = estimatedStringByteCount(key) + 4
            if (keyCost >= remainingRawBytes) {
                didTruncate = true
                break
            }
            val budget = Budget(remainingRawBytes - keyCost)
            val initial = budget.remaining
            val retained = boundedJsonValue(value, budget)
            if (retained == null) {
                didTruncate = true
                continue
            }
            val used = keyCost + (initial - budget.remaining)
            remainingRawBytes -= used
            remainingBytes -= minOf(remainingBytes, used)
            rawResponses[key] = retained
            didTruncate = didTruncate || budget.truncated
        }
        if (rawResponses.size < payload.rawResponses.size) didTruncate = true

        val warnings = if (didTruncate) payload.warnings + MEMORY_LIMIT_WARNING else payload.warnings
        return payload.copy(
            sections = sections,
            series = series,
            tables = tables,
            rawResponses = rawResponses,
            warnings = warnings,
        )
    }

    private class Budget(var remaining: Int) {
        var truncated = false
    }

    private fun boundedJsonValue(value: ProviderJsonValue, budget: Budget): ProviderJsonValue? = when (value) {
        is ProviderJsonValue.Obj -> {
            if (budget.remaining < 2) {
                budget.truncated = true
                null
            } else {
                budget.remaining -= 2
                val retained = LinkedHashMap<String, ProviderJsonValue>()
                for (key in value.fields.keys.sorted()) {
                    val child = value.fields[key] ?: continue
                    val keyCost = estimatedStringByteCount(key) + 2
                    if (keyCost > budget.remaining) {
                        budget.truncated = true
                        break
                    }
                    val childBudget = Budget(budget.remaining - keyCost)
                    val bounded = boundedJsonValue(child, childBudget)
                    if (childBudget.truncated) budget.truncated = true
                    if (bounded == null) {
                        budget.truncated = true
                        break
                    }
                    budget.remaining = childBudget.remaining
                    retained[key] = bounded
                }
                if (retained.size < value.fields.size) budget.truncated = true
                ProviderJsonValue.Obj(retained)
            }
        }
        is ProviderJsonValue.Arr -> {
            if (budget.remaining < 2) {
                budget.truncated = true
                null
            } else {
                budget.remaining -= 2
                val retained = ArrayList<ProviderJsonValue>()
                for (child in value.items) {
                    val childBudget = Budget(budget.remaining)
                    val bounded = boundedJsonValue(child, childBudget)
                    if (childBudget.truncated) budget.truncated = true
                    if (bounded == null) {
                        budget.truncated = true
                        break
                    }
                    budget.remaining = childBudget.remaining
                    retained += bounded
                }
                if (retained.size < value.items.size) budget.truncated = true
                ProviderJsonValue.Arr(retained)
            }
        }
        is ProviderJsonValue.Str -> charge(budget, estimatedStringByteCount(value.value), value)
        is ProviderJsonValue.Num -> charge(budget, 32, value)
        is ProviderJsonValue.Bool -> charge(budget, 5, value)
        ProviderJsonValue.Null -> charge(budget, 4, value)
    }

    private fun charge(budget: Budget, cost: Int, value: ProviderJsonValue): ProviderJsonValue? {
        if (cost > budget.remaining) {
            budget.truncated = true
            return null
        }
        budget.remaining -= cost
        return value
    }

    private fun estimatedByteCount(point: SiteDetailSeriesPoint): Int =
        estimatedStringByteCount(point.x) + point.values.keys.fold(16) { total, key ->
            total + estimatedStringByteCount(key) + 32
        }

    private fun estimatedRowByteCount(row: Map<String, ProviderJsonValue>): Int =
        row.entries.fold(16) { total, (key, value) ->
            total + estimatedStringByteCount(key) + estimatedByteCount(value) + 4
        }

    private fun estimatedByteCount(value: ProviderJsonValue): Int = when (value) {
        is ProviderJsonValue.Obj -> estimatedRowByteCount(value.fields)
        is ProviderJsonValue.Arr -> value.items.fold(16) { total, item -> total + estimatedByteCount(item) + 1 }
        is ProviderJsonValue.Str -> estimatedStringByteCount(value.value)
        is ProviderJsonValue.Num -> 32
        is ProviderJsonValue.Bool -> 5
        ProviderJsonValue.Null -> 4
    }

    /** Six bytes per UTF-8 byte is a conservative bound for JSON escaping. */
    private fun estimatedStringByteCount(value: String): Int = utf8Length(value) * 6 + 2

    /** UTF-8 encoded length without allocating an encoded copy. */
    internal fun utf8Length(value: String): Int {
        var length = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            length += when {
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                Character.isHighSurrogate(character) && index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1]) -> {
                    index += 1
                    4
                }
                else -> 3
            }
            index += 1
        }
        return length
    }
}
