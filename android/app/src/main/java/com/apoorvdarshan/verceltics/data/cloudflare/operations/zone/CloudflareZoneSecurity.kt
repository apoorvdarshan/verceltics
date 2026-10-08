package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue

// Port of `CloudflareSecurityModels.swift` and the item normalization in `CloudflareSecurityAPI.swift`.

/** iOS `CloudflareSecurityCategory`; [label] doubles as the category id, as on iOS. */
enum class CloudflareSecurityCategory(val label: String) {
    WAF_RULESETS("WAF rulesets"),
    ACCESS_RULES("IP access rules"),
    RATE_LIMITS("Rate limits"),
    CERTIFICATES("Certificates"),
    PAGE_SHIELD("Page Shield"),
    BOT_MANAGEMENT("Bot management"),
    API_SHIELD("API Shield"),
}

data class CloudflareSecurityItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val status: String?,
    val raw: ProviderJsonValue,
) {
    /** "status · subtitle" as shown in iOS rows. */
    val rowSubtitle: String get() = listOfNotNull(status, subtitle).joinToString(" · ")
}

data class CloudflareSecuritySnapshot(
    val rulesets: List<CloudflareSecurityItem> = emptyList(),
    val accessRules: List<CloudflareSecurityItem> = emptyList(),
    val rateLimits: List<CloudflareSecurityItem> = emptyList(),
    val certificates: List<CloudflareSecurityItem> = emptyList(),
    val pageShield: List<CloudflareSecurityItem> = emptyList(),
    val botManagement: List<CloudflareSecurityItem> = emptyList(),
    val apiShield: List<CloudflareSecurityItem> = emptyList(),
    val securityLevel: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val totalItems: Int
        get() = rulesets.size + accessRules.size + rateLimits.size + certificates.size +
            pageShield.size + botManagement.size + apiShield.size

    fun with(category: CloudflareSecurityCategory, items: List<CloudflareSecurityItem>): CloudflareSecuritySnapshot = when (category) {
        CloudflareSecurityCategory.WAF_RULESETS -> copy(rulesets = items)
        CloudflareSecurityCategory.ACCESS_RULES -> copy(accessRules = items)
        CloudflareSecurityCategory.RATE_LIMITS -> copy(rateLimits = items)
        CloudflareSecurityCategory.CERTIFICATES -> copy(certificates = items)
        CloudflareSecurityCategory.PAGE_SHIELD -> copy(pageShield = items)
        CloudflareSecurityCategory.BOT_MANAGEMENT -> copy(botManagement = items)
        CloudflareSecurityCategory.API_SHIELD -> copy(apiShield = items)
    }
}

/** iOS security levels offered in the "Change" menu. */
val CLOUDFLARE_SECURITY_LEVELS = listOf("essentially_off", "low", "medium", "high", "under_attack")

/** iOS access-rule editor choices. */
val CLOUDFLARE_ACCESS_RULE_TARGETS = listOf("ip", "ip_range", "asn", "country")
val CLOUDFLARE_ACCESS_RULE_MODES = listOf("block", "challenge", "js_challenge", "whitelist")

/** "under_attack" → "Under Attack" (Swift `.capitalized` after replacing underscores). */
fun cloudflareHumanize(value: String): String = value.replace('_', ' ')
    .split(' ')
    .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

object CloudflareSecurityParser {
    /** iOS `securityDisplayValue`. */
    fun displayValue(value: ProviderJsonValue): String = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> CloudflareFormat.decimal(value.decimal)
        is ProviderJsonValue.Bool -> if (value.value) "Enabled" else "Disabled"
        is ProviderJsonValue.Obj -> "${value.fields.size} properties"
        is ProviderJsonValue.Arr -> "${value.items.size} items"
        ProviderJsonValue.Null -> "Not returned"
    }

    /** iOS `securityItems(from:category:startIndex:)`. */
    fun items(result: ProviderJsonValue, category: CloudflareSecurityCategory, startIndex: Int): List<CloudflareSecurityItem> = when (result) {
        is ProviderJsonValue.Arr -> result.items.mapIndexed { index, value -> item(value, category, startIndex + index) }
        is ProviderJsonValue.Obj -> {
            val nested = result["items"] as? ProviderJsonValue.Arr
            nested?.items?.mapIndexed { index, value -> item(value, category, startIndex + index) }
                ?: listOf(item(result, category, startIndex))
        }
        else -> emptyList()
    }

    /** iOS `securityItem(from:category:fallbackIndex:)`. */
    fun item(value: ProviderJsonValue, category: CloudflareSecurityCategory, fallbackIndex: Int): CloudflareSecurityItem {
        if (value !is ProviderJsonValue.Obj) {
            return CloudflareSecurityItem(
                id = "${category.label}-$fallbackIndex",
                title = category.label,
                subtitle = displayValue(value),
                status = null,
                raw = value,
            )
        }
        val configurationValue = value.obj("configuration").strictStr("value")
        val id = value.strictStr("id") ?: value.strictStr("uuid") ?: "${category.label}-$fallbackIndex"
        val title = value.strictStr("name")
            ?: configurationValue
            ?: value.strictStr("hostname")
            ?: value.strictStr("description")
            ?: value.strictStr("type")
            ?: id.take(20)
        val subtitle = listOf("description", "phase", "kind", "type")
            .mapNotNull { value.strictStr(it) }
            .filter { it.isNotEmpty() && it != title }
            .joinToString(" · ")
        val status = value.strictStr("status")
            ?: value.strictStr("action")
            ?: value.strictStr("mode")
            ?: value["enabled"]?.let(::displayValue)
        return CloudflareSecurityItem(id, title, subtitle.ifEmpty { null }, status, value)
    }

    /** Rules inside a ruleset (`result.rules`). */
    fun rulesetRules(result: ProviderJsonValue): List<CloudflareSecurityItem> =
        (result["rules"] as? ProviderJsonValue.Arr)?.items
            ?.mapIndexed { index, rule -> item(rule, CloudflareSecurityCategory.WAF_RULESETS, index) }
            .orEmpty()
}
