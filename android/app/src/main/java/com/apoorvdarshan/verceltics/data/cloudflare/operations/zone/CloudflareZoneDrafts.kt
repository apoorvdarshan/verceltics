package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareDisplayText
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue

// Editor state and validation ported from `CloudflareDNSRecordEditor.swift` (DNS record editor and
// cache purge), and the zone setting, DNS settings and access-rule editors. Validation failures are
// `CloudflareOperationException.invalidRequest` with the iOS copy, raised before any request exists.

/** iOS `CloudflareDNSRecordEditor` state. */
data class CloudflareDnsRecordDraft(
    val type: String = "A",
    val name: String = "",
    val content: String = "",
    val ttl: Int = 1,
    val proxied: Boolean = false,
    val comment: String = "",
    val tags: String = "",
    val priority: String = "",
    val dataJson: String = "",
    val settingsJson: String = "",
    val privateRouting: Boolean = false,
) {
    /** Existing records use Cloudflare's `proxiable`; new A, AAAA and CNAME records can be proxied. */
    fun canProxy(existing: CloudflareDnsRecord?): Boolean =
        existing?.let { it.proxiable == true } ?: (type in PROXIABLE_TYPES)

    val showsPriority: Boolean get() = type in PRIORITY_TYPES

    val supportsPrivateRouting: Boolean get() = type in PRIVATE_ROUTING_TYPES

    /** Changing the type of a new record clears the proxy flag when it can no longer be proxied. */
    fun withType(value: String, existing: CloudflareDnsRecord?): CloudflareDnsRecordDraft {
        val next = copy(type = value)
        return if (next.canProxy(existing)) next else next.copy(proxied = false)
    }

    /** iOS `makeInput()`. */
    fun toInput(existing: CloudflareDnsRecord?): CloudflareDnsRecordInput {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw invalid("Enter a DNS record name.")
        val trimmedContent = content.trim()
        val data = parseJsonObject(dataJson, "Structured data")
        val settings = parseJsonObject(settingsJson, "Settings")
        if (trimmedContent.isEmpty() && data?.fields.isNullOrEmpty()) {
            throw invalid("Enter record content or structured data.")
        }
        val parsedPriority = priority.trim().takeIf(String::isNotEmpty)?.let { text ->
            text.toIntOrNull()?.takeIf { it >= 0 } ?: throw invalid("Priority must be a non-negative whole number.")
        }
        val parsedTags = tags.split(',').map(String::trim).filter(String::isNotEmpty)
        val canProxy = canProxy(existing)
        return CloudflareDnsRecordInput(
            type = type,
            name = trimmedName,
            content = trimmedContent.ifEmpty { null },
            ttl = ttl,
            proxied = if (canProxy) proxied else null,
            comment = comment.trim().ifEmpty { null },
            tags = parsedTags.ifEmpty { null },
            priority = parsedPriority,
            data = data,
            settings = settings,
            privateRouting = if (supportsPrivateRouting && (existing?.privateRouting != null || privateRouting)) privateRouting else null,
        )
    }

    companion object {
        val RECORD_TYPES = listOf(
            "A", "AAAA", "CNAME", "TXT", "MX", "SRV", "CAA", "NS", "PTR",
            "HTTPS", "SVCB", "DS", "DNSKEY", "TLSA", "LOC", "NAPTR", "SSHFP",
            "CERT", "SMIMEA", "URI",
        )

        val TTL_OPTIONS: List<Pair<String, Int>> = listOf(
            "Automatic" to 1,
            "30 seconds" to 30,
            "1 minute" to 60,
            "2 minutes" to 120,
            "5 minutes" to 300,
            "15 minutes" to 900,
            "30 minutes" to 1_800,
            "1 hour" to 3_600,
            "2 hours" to 7_200,
            "5 hours" to 18_000,
            "12 hours" to 43_200,
            "1 day" to 86_400,
        )

        private val PROXIABLE_TYPES = setOf("A", "AAAA", "CNAME")
        private val PRIORITY_TYPES = setOf("MX", "SRV", "URI")
        private val PRIVATE_ROUTING_TYPES = setOf("A", "AAAA")

        fun ttlLabel(ttl: Int): String = TTL_OPTIONS.firstOrNull { it.second == ttl }?.first ?: "$ttl seconds"

        fun from(record: CloudflareDnsRecord?): CloudflareDnsRecordDraft = if (record == null) {
            CloudflareDnsRecordDraft()
        } else {
            CloudflareDnsRecordDraft(
                type = record.type,
                name = record.name,
                content = record.content.orEmpty(),
                ttl = record.ttl ?: 1,
                proxied = record.proxied ?: false,
                comment = record.comment.orEmpty(),
                tags = record.tags.joinToString(", "),
                priority = record.priority?.toString().orEmpty(),
                dataJson = record.data?.let(CloudflarePrettyJson::write).orEmpty(),
                settingsJson = record.settings?.let(CloudflarePrettyJson::write).orEmpty(),
                privateRouting = record.privateRouting ?: false,
            )
        }

        private fun parseJsonObject(source: String, label: String): ProviderJsonValue.Obj? {
            val trimmed = source.trim()
            if (trimmed.isEmpty()) return null
            val parsed = runCatching { ProviderJsonParser.parse(trimmed) }.getOrNull()
            return parsed as? ProviderJsonValue.Obj ?: throw invalid("$label must be a valid JSON object.")
        }
    }
}

/** API-side DNS validation (iOS `validateDNSRecord`), applied to every create and update. */
fun validateCloudflareDnsRecord(record: CloudflareDnsRecordInput) {
    if (record.type.isBlank()) throw invalid("Choose a DNS record type.")
    if (record.name.isBlank()) throw invalid("Enter a DNS record name.")
    if (record.ttl != 1 && record.ttl !in 30..86_400) {
        throw invalid("DNS TTL must be Automatic (1) or between 30 and 86,400 seconds.")
    }
    if (record.content.isNullOrEmpty() && record.data?.fields.isNullOrEmpty()) {
        throw invalid("Enter record content or structured record data.")
    }
}

/** API-side purge validation (iOS `validateCachePurge`). */
fun validateCloudflareCachePurge(purge: CloudflareCachePurge) {
    val values = purge.entries ?: return
    if (values.isEmpty() || values.any(String::isBlank)) throw invalid("Cache purge values cannot be empty.")
}

/** iOS `PurgeKind`. */
enum class CloudflareCachePurgeKind(val title: String, val subtitle: String, val inputLabel: String) {
    FILES("Specific URLs", "Remove exact cached files", "URLs"),
    TAGS("Cache tags", "Remove objects with matching cache tags", "Cache tags"),
    HOSTS("Hostnames", "Remove cached content for selected hosts", "Hostnames"),
    PREFIXES("URL prefixes", "Remove every object under a URL prefix", "URL prefixes"),
    EVERYTHING("Everything", "Empty the entire zone cache", "Zone"),
    ;

    fun confirmationTitle(): String =
        if (this == EVERYTHING) "Purge all cached content?" else "Purge selected cached content?"

    fun confirmationMessage(zone: String): String = when (this) {
        EVERYTHING -> "Every cached object for $zone will be removed from Cloudflare’s edge."
        else -> "Only the entered ${inputLabel.lowercase()} will be purged for $zone."
    }

    /** iOS `purgeValue()`: one value per line or comma-separated; a full purge needs the typed zone name. */
    fun purge(values: String, confirmationText: String, zoneName: String): CloudflareCachePurge {
        if (this == EVERYTHING) {
            if (confirmationText != zoneName) throw invalid("Type the exact zone name to confirm a full cache purge.")
            return CloudflareCachePurge.Everything
        }
        val entries = values.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
        if (entries.isEmpty()) throw invalid("Enter at least one ${inputLabel.lowercase()}.")
        return when (this) {
            FILES -> CloudflareCachePurge.Files(entries)
            TAGS -> CloudflareCachePurge.Tags(entries)
            HOSTS -> CloudflareCachePurge.Hosts(entries)
            PREFIXES -> CloudflareCachePurge.Prefixes(entries)
            EVERYTHING -> CloudflareCachePurge.Everything
        }
    }
}

/** iOS `CloudflareZoneSettingEditor` value handling. */
object CloudflareZoneSettingEditing {
    fun isOnOffString(value: ProviderJsonValue): Boolean =
        value is ProviderJsonValue.Str && (value.value == "on" || value.value == "off")

    fun isNumeric(value: ProviderJsonValue): Boolean = value is ProviderJsonValue.Num

    /** Settings with a fixed set of values get a picker instead of free text. */
    fun options(settingId: String): List<String> = when (settingId) {
        "ssl" -> listOf("off", "flexible", "full", "strict", "origin_pull")
        "min_tls_version" -> listOf("1.0", "1.1", "1.2", "1.3")
        "security_level" -> listOf("off", "essentially_off", "low", "medium", "high", "under_attack")
        "cache_level" -> listOf("basic", "simplified", "aggressive")
        "pseudo_ipv4" -> listOf("off", "add_header", "overwrite_header")
        "rocket_loader" -> listOf("off", "manual", "on")
        else -> emptyList()
    }

    fun initialText(value: ProviderJsonValue): String = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> value.text
        is ProviderJsonValue.Bool -> value.value.toString()
        else -> value.cloudflareDisplayText
    }

    fun initialToggle(value: ProviderJsonValue): Boolean = when (value) {
        is ProviderJsonValue.Str -> value.value.lowercase() == "on"
        is ProviderJsonValue.Num -> value.decimal.signum() != 0
        is ProviderJsonValue.Bool -> value.value
        else -> false
    }

    private fun isIntegral(value: ProviderJsonValue.Num): Boolean =
        value.decimal.stripTrailingZeros().scale() <= 0

    /** iOS `proposedValue`; null when the entered text is not valid for the setting's type. */
    fun proposedValue(original: ProviderJsonValue, text: String, toggle: Boolean): ProviderJsonValue? = when (original) {
        is ProviderJsonValue.Str -> ProviderJsonValue.Str(if (isOnOffString(original)) (if (toggle) "on" else "off") else text)
        is ProviderJsonValue.Num -> if (isIntegral(original)) {
            text.trim().toLongOrNull()?.let { ProviderJsonValue.Num.of(it) }
        } else {
            text.trim().toDoubleOrNull()?.takeIf(Double::isFinite)?.let { ProviderJsonValue.Num.of(it) }
        }
        is ProviderJsonValue.Bool -> ProviderJsonValue.Bool(toggle)
        else -> null
    }

    fun confirmationMessage(original: ProviderJsonValue, proposed: ProviderJsonValue?): String =
        "This changes the live Cloudflare setting from ${original.cloudflareDisplayText} to " +
            "${proposed?.cloudflareDisplayText ?: "an invalid value"}."
}

/** iOS `CloudflareZoneDNSSettingsEditor` state. */
data class CloudflareZoneDnsSettingsDraft(
    val flattenAllCnames: Boolean,
    val foundationDns: Boolean,
    val multiProvider: Boolean,
    val secondaryOverrides: Boolean,
    val zoneMode: String,
    val nameServerTtl: String,
) {
    /** Only fields that differ from what Cloudflare returned (iOS `changes`). */
    fun changes(original: CloudflareZoneDnsSettings): Map<String, ProviderJsonValue> = buildMap {
        if (flattenAllCnames != original.flattenAllCnames) put("flatten_all_cnames", ProviderJsonValue.Bool(flattenAllCnames))
        if (foundationDns != original.foundationDns) put("foundation_dns", ProviderJsonValue.Bool(foundationDns))
        if (multiProvider != original.multiProvider) put("multi_provider", ProviderJsonValue.Bool(multiProvider))
        if (secondaryOverrides != original.secondaryOverrides) put("secondary_overrides", ProviderJsonValue.Bool(secondaryOverrides))
        if (zoneMode != original.zoneMode) put("zone_mode", ProviderJsonValue.Str(zoneMode))
        nameServerTtl.trim().toLongOrNull()?.let { ttl ->
            if (ttl.toDouble() != original.nameServerTtl) put("ns_ttl", ProviderJsonValue.Num.of(ttl))
        }
    }

    /** A blank TTL is accepted only when Cloudflare returned none; otherwise it must be 30–86,400. */
    fun ttlIsValid(original: CloudflareZoneDnsSettings): Boolean {
        val text = nameServerTtl.trim()
        if (text.isEmpty()) return original.nameServerTtl == null
        return text.toIntOrNull()?.let { it in 30..86_400 } == true
    }

    /** Returns the PATCH body or throws the iOS validation copy. */
    fun validatedChanges(original: CloudflareZoneDnsSettings): Map<String, ProviderJsonValue> {
        val changes = changes(original)
        if (changes.isEmpty()) throw invalid("No DNS settings have changed.")
        if (!ttlIsValid(original)) throw invalid("Nameserver TTL must be between 30 and 86,400 seconds.")
        return changes
    }

    companion object {
        val ZONE_MODES: List<Pair<String, String>> = listOf(
            "standard" to "Standard",
            "cdn_only" to "CDN only",
            "dns_only" to "DNS only",
        )

        fun from(settings: CloudflareZoneDnsSettings) = CloudflareZoneDnsSettingsDraft(
            flattenAllCnames = settings.flattenAllCnames ?: false,
            foundationDns = settings.foundationDns ?: false,
            multiProvider = settings.multiProvider ?: false,
            secondaryOverrides = settings.secondaryOverrides ?: false,
            zoneMode = settings.zoneMode ?: "standard",
            nameServerTtl = settings.nameServerTtl?.toLong()?.toString().orEmpty(),
        )
    }
}

/** iOS `CloudflareAccessRuleEditor` state. */
data class CloudflareAccessRuleDraft(
    val target: String = "ip",
    val value: String = "",
    val mode: String = "block",
    val notes: String = "",
) {
    val valuePlaceholder: String
        get() = when (target) {
            "ip" -> "IP address, e.g. 203.0.113.10"
            "ip_range" -> "CIDR range, e.g. 203.0.113.0/24"
            "asn" -> "ASN number"
            "country" -> "Two-letter country code"
            else -> "Value"
        }

    val canSubmit: Boolean get() = value.isNotBlank()

    val trimmedValue: String get() = value.trim()

    val notesOrNull: String? get() = notes.takeIf(String::isNotEmpty)
}

/** Sorted-key, two-space-indented JSON for editors and raw configuration views. */
object CloudflarePrettyJson {
    fun write(value: ProviderJsonValue): String = StringBuilder().also { append(it, value, 0) }.toString()

    private fun append(output: StringBuilder, value: ProviderJsonValue, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        val closing = "  ".repeat(depth)
        when (value) {
            is ProviderJsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    output.append("{}")
                    return
                }
                output.append("{\n")
                value.fields.entries.sortedBy { it.key }.forEachIndexed { index, (key, child) ->
                    output.append(indent)
                    appendString(output, key)
                    output.append(": ")
                    append(output, child, depth + 1)
                    if (index < value.fields.size - 1) output.append(',')
                    output.append('\n')
                }
                output.append(closing).append('}')
            }
            is ProviderJsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    output.append("[]")
                    return
                }
                output.append("[\n")
                value.items.forEachIndexed { index, child ->
                    output.append(indent)
                    append(output, child, depth + 1)
                    if (index < value.items.size - 1) output.append(',')
                    output.append('\n')
                }
                output.append(closing).append(']')
            }
            is ProviderJsonValue.Str -> appendString(output, value.value)
            is ProviderJsonValue.Num -> output.append(value.text)
            is ProviderJsonValue.Bool -> output.append(value.value)
            ProviderJsonValue.Null -> output.append("null")
        }
    }

    private fun appendString(output: StringBuilder, value: String) {
        output.append(com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter.write(ProviderJsonValue.Str(value)))
    }
}

private fun invalid(message: String) = CloudflareOperationException.invalidRequest(message)
