package com.apoorvdarshan.verceltics.ui.sites

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.sites.SiteMetricUnit
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import com.apoorvdarshan.verceltics.data.sites.UmamiSiteAdapter
import java.math.BigDecimal
import java.net.URI
import java.text.Collator
import java.text.NumberFormat
import java.util.Locale

/** One connection form field. The credential field is entered through a native secret input. */
data class SiteServiceFieldSpec(
    val key: String,
    val label: String,
    val placeholder: String,
    val isSecret: Boolean = false,
    val isUrl: Boolean = false,
    val isOptional: Boolean = false,
)

/** iOS `SiteServiceConnectionView` / `SiteIntegrationProvider` copy, keyed by provider id. */
object SiteServiceCopy {
    const val CREDENTIAL_FIELD: String = "credential"
    const val GOOGLE_CREDENTIALS_URL: String = "https://console.cloud.google.com/apis/credentials"
    const val UMAMI_CLOUD_BASE_URL: String = "https://api.umami.is/v1"

    fun subtitle(providerId: String): String = when (providerId) {
        "googleAnalytics" -> "GA4 visitors, sessions, traffic, events and realtime"
        "bingWebmaster" -> "Bing search traffic, crawling and verified sites"
        "clarity" -> "Behavioral insights, sessions and interaction signals"
        "plausible" -> "Privacy-friendly visitors, visits, views and engagement"
        "umami" -> "30-day traffic across Cloud or self-hosted sites"
        "uptimeRobot" -> "Monitor state, uptime ratios and response time"
        "betterStack" -> "Monitor state, check cadence and availability"
        else -> "Site service"
    }

    fun instructionOne(providerId: String): String = when (providerId) {
        "bingWebmaster" -> "Generate an API key in Bing Webmaster Tools → API Access"
        "clarity" -> "Generate a project token in Settings → Data Export"
        "plausible" -> "Create a Stats API key in your Plausible account settings"
        "umami" -> "Create a Cloud API key or a token for your self-hosted instance"
        "uptimeRobot" -> "Create a read-only key in Integrations & API"
        "betterStack" -> "Create a Uptime API token in Better Stack"
        else -> "Configure the Google OAuth client"
    }

    fun instructionTwo(providerId: String): String = when (providerId) {
        "bingWebmaster" -> "The key can read every verified site in that Bing account"
        "clarity" -> "Clarity export tokens belong to one project and expose the previous three days"
        "plausible" -> "Enter the exact Site ID used by the Plausible dashboard"
        "umami" -> "Choose Cloud or provide the HTTPS base URL of your self-hosted instance"
        "uptimeRobot" -> "A read-only key exposes monitor state, uptime, and response time"
        "betterStack" -> "The token reads monitors, check cadence, and availability state"
        else -> "Authorize read-only access"
    }

    const val INSTRUCTION_THREE: String = "Paste the requested details below and connect"

    const val CREDENTIAL_SECURITY_NOTE: String =
        "Credentials stay encrypted on this device and are sent only to the service’s official API endpoint."

    const val GOOGLE_READY_MESSAGE: String =
        "Sign in with Google to grant read-only access. Tokens refresh securely and remain encrypted on this device."

    fun credentialPageUrl(providerId: String): String = when (providerId) {
        "bingWebmaster" -> "https://www.bing.com/webmasters/home"
        "clarity" -> "https://clarity.microsoft.com/projects"
        "plausible" -> "https://plausible.io/settings/api-keys"
        "umami" -> "https://cloud.umami.is/settings/api-keys"
        "uptimeRobot" -> "https://dashboard.uptimerobot.com/integrations"
        "betterStack" -> "https://betterstack.com/settings/api-tokens"
        else -> GOOGLE_CREDENTIALS_URL
    }

    fun oauthCapabilities(providerId: String): List<String> = when (providerId) {
        "googleAnalytics" -> listOf("Property discovery", "Traffic and engagement", "Realtime reporting")
        else -> emptyList()
    }

    /** Provider dashboard opened by the Pro-gated "Open in …" action. */
    fun providerDashboardUrl(providerId: String): String = when (providerId) {
        "googleAnalytics" -> "https://analytics.google.com/"
        "bingWebmaster" -> "https://www.bing.com/webmasters/home"
        "clarity" -> "https://clarity.microsoft.com/projects"
        "plausible" -> "https://plausible.io/sites"
        "umami" -> "https://cloud.umami.is/"
        "uptimeRobot" -> "https://dashboard.uptimerobot.com/monitors"
        "betterStack" -> "https://uptime.betterstack.com/"
        else -> "https://www.google.com/"
    }

    fun fields(providerId: String, umamiAuthMode: String = UmamiSiteAdapter.CLOUD): List<SiteServiceFieldSpec> =
        when (providerId) {
            "bingWebmaster" -> listOf(
                SiteServiceFieldSpec(CREDENTIAL_FIELD, "Bing Webmaster API key", "Paste API key", isSecret = true),
            )
            "clarity" -> listOf(
                SiteServiceFieldSpec(SiteServiceFieldKeys.PROJECT_NAME, "Project name", "My website"),
                SiteServiceFieldSpec(
                    SiteServiceFieldKeys.SITE_URL, "Site URL (optional)", "https://example.com",
                    isUrl = true, isOptional = true,
                ),
                SiteServiceFieldSpec(CREDENTIAL_FIELD, "Clarity export token", "Paste bearer token", isSecret = true),
            )
            "plausible" -> listOf(
                SiteServiceFieldSpec(SiteServiceFieldKeys.SITE_ID, "Site ID", "example.com", isUrl = true),
                SiteServiceFieldSpec(CREDENTIAL_FIELD, "Plausible Stats API key", "Paste API key", isSecret = true),
            )
            "umami" -> buildList {
                if (umamiAuthMode == UmamiSiteAdapter.SELF_HOSTED) {
                    add(
                        SiteServiceFieldSpec(
                            SiteServiceFieldKeys.BASE_URL, "Self-hosted base URL", "https://analytics.example.com",
                            isUrl = true,
                        ),
                    )
                }
                add(
                    SiteServiceFieldSpec(
                        CREDENTIAL_FIELD,
                        if (umamiAuthMode == UmamiSiteAdapter.SELF_HOSTED) "Self-hosted bearer token" else "Umami Cloud API key",
                        "Paste credential",
                        isSecret = true,
                    ),
                )
            }
            "uptimeRobot" -> listOf(
                SiteServiceFieldSpec(CREDENTIAL_FIELD, "UptimeRobot read-only API key", "Paste API key", isSecret = true),
            )
            "betterStack" -> listOf(
                SiteServiceFieldSpec(CREDENTIAL_FIELD, "Better Stack API token", "Paste bearer token", isSecret = true),
            )
            else -> emptyList()
        }

    /** iOS `canConnect`: credential present plus provider-specific required fields. */
    fun canConnect(
        providerId: String,
        hasCredential: Boolean,
        fields: Map<String, String>,
        umamiAuthMode: String,
    ): Boolean {
        if (!hasCredential) return false
        fun value(key: String) = fields[key].orEmpty().trim()
        return when (providerId) {
            "clarity" -> value(SiteServiceFieldKeys.PROJECT_NAME).isNotEmpty() &&
                (value(SiteServiceFieldKeys.SITE_URL).isEmpty() || isHttpsUrl(value(SiteServiceFieldKeys.SITE_URL)))
            "plausible" -> value(SiteServiceFieldKeys.SITE_ID).isNotEmpty()
            "umami" -> umamiAuthMode == UmamiSiteAdapter.CLOUD || isHttpsUrl(value(SiteServiceFieldKeys.BASE_URL))
            "bingWebmaster", "uptimeRobot", "betterStack" -> true
            else -> false
        }
    }

    fun isHttpsUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    fun resourceNoun(providerId: String, count: Int): String {
        val singular = SiteProvider.fromId(providerId)?.resourceNoun ?: "Resource"
        return if (count == 1) singular else if (singular == "Property") "Properties" else "${singular}s"
    }

    /** Account-menu "add" action, naming what another account adds (iOS "Add Site Service"). */
    fun addAccountLabel(providerId: String): String = when (providerId) {
        "googleAnalytics" -> "Add Google account"
        "plausible" -> "Add Plausible site"
        "clarity" -> "Add Clarity project"
        else -> "Add ${SiteProvider.fromId(providerId)?.displayName ?: "site service"} account"
    }
}

enum class SiteStatusTone {
    SUCCESS,
    WARNING,
    DANGER,
    PROGRESS,
    NEUTRAL,
}

data class SiteStatusPresentation(val text: String, val tone: SiteStatusTone)

/** Formatting and status rules ported from iOS `SitesView` and `SiteDetailValueFormatter`. */
object SiteServiceFormat {
    fun metric(metric: SiteMetricUi, locale: Locale = Locale.getDefault()): String {
        metric.formattedValue?.takeIf(String::isNotEmpty)?.let { return it }
        val value = metric.value
        return when (metric.unit) {
            SiteMetricUnit.COUNT -> decimal(value, 0, 0, locale)
            SiteMetricUnit.PERCENT -> decimal(value, 1, 1, locale) + "%"
            SiteMetricUnit.MILLISECONDS -> decimal(value, 0, 0, locale) + " ms"
            SiteMetricUnit.SECONDS -> decimal(value, 1, 1, locale) + " s"
            SiteMetricUnit.BYTES -> bytes(value, locale)
            SiteMetricUnit.SCORE -> decimal(if (value <= 1) value * 100 else value, 0, 0, locale)
            SiteMetricUnit.RATIO -> decimal(value, 2, 2, locale)
            SiteMetricUnit.POSITION -> decimal(value, 1, 1, locale)
            SiteMetricUnit.NONE -> decimal(value, 0, 2, locale)
        }
    }

    private fun decimal(value: Double, minimumFraction: Int, maximumFraction: Int, locale: Locale): String {
        if (!value.isFinite()) return "—"
        return NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = minimumFraction
            maximumFractionDigits = maximumFraction
            isGroupingUsed = true
        }.format(value)
    }

    private fun bytes(value: Double, locale: Locale): String {
        val units = listOf("bytes", "KB", "MB", "GB", "TB")
        var scaled = value
        var index = 0
        while (kotlin.math.abs(scaled) >= 1_000 && index < units.lastIndex) {
            scaled /= 1_000
            index += 1
        }
        return if (index == 0) "${decimal(scaled, 0, 0, locale)} ${units[index]}" else "${decimal(scaled, 1, 1, locale)} ${units[index]}"
    }

    /** iOS `SiteDetailValueFormatter.display`. */
    fun display(value: ProviderJsonValue, locale: Locale = Locale.getDefault()): String = when (value) {
        is ProviderJsonValue.Str -> value.value.ifEmpty { "—" }
        is ProviderJsonValue.Num -> number(value, locale)
        is ProviderJsonValue.Bool -> if (value.value) "Yes" else "No"
        ProviderJsonValue.Null -> "—"
        is ProviderJsonValue.Arr -> if (value.items.isEmpty()) "[]" else value.items.joinToString(", ") { display(it, locale) }
        is ProviderJsonValue.Obj -> if (value.fields.isEmpty()) {
            "{}"
        } else {
            value.fields.keys.sorted().joinToString(" · ") { key ->
                "${humanized(key)}: ${display(value.fields[key] ?: ProviderJsonValue.Null, locale)}"
            }
        }
    }

    private fun number(value: ProviderJsonValue.Num, locale: Locale): String {
        val decimal = value.decimal
        val isWhole = decimal.signum() == 0 || decimal.stripTrailingZeros().scale() <= 0
        // Identifiers beyond double precision keep their exact digits.
        if (isWhole && decimal.abs() >= BigDecimal("1e15")) return value.text
        val format = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            minimumFractionDigits = 0
            maximumFractionDigits = if (isWhole) 0 else 3
        }
        return format.format(decimal)
    }

    /** iOS column humanizer: separators to spaces, short all-caps words kept, others capitalized. */
    fun humanized(value: String): String = value
        .replace('.', ' ').replace('_', ' ').replace('-', ' ')
        .split(' ')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word ->
            if (word == word.uppercase(Locale.ROOT) && word.length <= 5) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

    fun compare(left: ProviderJsonValue, right: ProviderJsonValue, collator: Collator = Collator.getInstance()): Int {
        val leftNumber = left.decimalValue
        val rightNumber = right.decimalValue
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
        return collator.compare(display(left), display(right))
    }

    /** iOS `statusTone(_:)`. */
    fun tone(status: String): SiteStatusTone {
        val value = status.lowercase(Locale.ROOT)
        return when {
            DANGER_WORDS.any(value::contains) -> SiteStatusTone.DANGER
            WARNING_WORDS.any(value::contains) -> SiteStatusTone.WARNING
            PROGRESS_WORDS.any(value::contains) -> SiteStatusTone.PROGRESS
            value.split(Regex("[^\\p{L}\\p{N}]+")).contains("up") || SUCCESS_WORDS.any(value::contains) -> SiteStatusTone.SUCCESS
            else -> SiteStatusTone.NEUTRAL
        }
    }

    /** iOS `siteServiceStatus(snapshot:refreshError:)` adapted to Android service state. */
    fun serviceStatus(service: SiteServiceState): SiteStatusPresentation {
        if (service.status == SiteServiceConnectionStatus.RESTORING) return SiteStatusPresentation("Restoring", SiteStatusTone.PROGRESS)
        if (service.operation == SiteServiceOperation.REFRESHING && service.dashboard == null) {
            return SiteStatusPresentation("Loading", SiteStatusTone.PROGRESS)
        }
        if (service.error != null) {
            return SiteStatusPresentation(
                if (service.dashboard == null) "Attention" else "Refresh failed",
                SiteStatusTone.DANGER,
            )
        }
        val dashboard = service.dashboard ?: return SiteStatusPresentation(
            if (service.status == SiteServiceConnectionStatus.SAVED_UNAVAILABLE) "Attention" else "Loading",
            if (service.status == SiteServiceConnectionStatus.SAVED_UNAVAILABLE) SiteStatusTone.WARNING else SiteStatusTone.PROGRESS,
        )
        val status = dashboard.status?.takeIf(String::isNotBlank)
        if (status != null) {
            val tone = tone(status)
            if (tone == SiteStatusTone.DANGER) return SiteStatusPresentation(status, tone)
            if (dashboard.warnings.isNotEmpty()) return SiteStatusPresentation("Needs review", SiteStatusTone.WARNING)
            return SiteStatusPresentation(status, tone)
        }
        if (dashboard.warnings.isNotEmpty()) return SiteStatusPresentation("Needs review", SiteStatusTone.WARNING)
        return SiteStatusPresentation("Connected", SiteStatusTone.SUCCESS)
    }

    /** Overview cards show up to two headline metrics, skipping the resource count. */
    fun headlineMetrics(dashboard: SiteServiceDashboardUi?, limit: Int = 2): List<SiteMetricUi> =
        dashboard?.metrics.orEmpty().filterNot(::isResourceCountMetric).take(limit)

    fun isResourceCountMetric(metric: SiteMetricUi): Boolean =
        metric.label.replace(" · Partial", "").lowercase(Locale.ROOT) in setOf("properties", "sites", "monitors")

    /** iOS `SitesView.resources` search and name sort. */
    fun filteredResources(
        providerId: String,
        resources: List<SiteResourceUi>,
        query: String,
        locale: Locale = Locale.getDefault(),
    ): List<SiteResourceUi> {
        val needle = query.trim()
        val providerName = SiteProvider.fromId(providerId)?.displayName.orEmpty()
        val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        return resources.filter { resource ->
            needle.isEmpty() ||
                resource.name.contains(needle, ignoreCase = true) ||
                providerName.contains(needle, ignoreCase = true) ||
                resource.status?.contains(needle, ignoreCase = true) == true ||
                resource.subtitle?.contains(needle, ignoreCase = true) == true ||
                resource.metrics.any {
                    it.label.contains(needle, ignoreCase = true) || metric(it, locale).contains(needle, ignoreCase = true)
                }
        }.sortedWith { left, right -> collator.compare(left.name, right.name) }
    }

    private val DANGER_WORDS = listOf(
        "down", "error", "fail", "offline", "poor", "expired", "blocked", "disabled", "inactive", "unhealthy",
    )
    private val WARNING_WORDS = listOf(
        "paused", "warning", "pending", "unknown", "degraded", "attention", "needs work", "not checked",
        "not ready", "not connected", "not verified", "unverified", "not ok", "needs review",
    )
    private val PROGRESS_WORDS = listOf("refreshing", "loading", "checking", "progress", "initializing", "restoring")
    private val SUCCESS_WORDS = listOf("active", "verified", "connected", "good", "healthy", "operational", "ok", "clear", "reporting", "live")
}
