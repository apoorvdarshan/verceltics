package com.apoorvdarshan.verceltics.ui.registrar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.registrar.registrarDaysUntil
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.theme.DarkWarning
import com.apoorvdarshan.verceltics.ui.theme.LightWarning
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** iOS `AppStatusTone`. */
internal enum class RegistrarTone {
    SUCCESS,
    WARNING,
    DANGER,
    PROGRESS,
    NEUTRAL,
}

/** Port of iOS `AppStatusTone.status(_:)`. */
internal fun registrarStatusTone(value: String): RegistrarTone {
    val status = value.lowercase(Locale.ROOT)
    fun has(vararg tokens: String) = tokens.any { status.contains(it) }
    return when {
        has(
            "inactive", "deactiv", "expired", "disabled", "deleted", "blocked", "fail", "error",
            "cancel", "suspend", "fatal", "stopped", "offline",
        ) -> RegistrarTone.DANGER
        has("build", "progress", "initial") -> RegistrarTone.PROGRESS
        has("pending", "queued", "starting", "warning", "paused", "not ready", "incomplete") ->
            RegistrarTone.WARNING
        has("active", "ready", "success", "live", "running", "published", "succeed", "complete") ->
            RegistrarTone.SUCCESS
        else -> RegistrarTone.NEUTRAL
    }
}

/** Expiry tone used by the domain rows and the detail hero. */
internal fun registrarExpiryTone(expiresAtMillis: Long?, nowMillis: Long): RegistrarTone {
    val days = expiresAtMillis?.let { registrarDaysUntil(it, nowMillis) } ?: return RegistrarTone.NEUTRAL
    return when {
        days < 0 -> RegistrarTone.DANGER
        days <= ATTENTION_DAYS -> RegistrarTone.WARNING
        else -> RegistrarTone.SUCCESS
    }
}

internal const val ATTENTION_DAYS = 30

/** Portfolio summary used by the header, stats and connection cards (iOS dashboard rules). */
internal data class RegistrarPortfolioSummary(
    val domainCount: Int,
    val attentionCount: Int,
    val autoRenewCount: Int,
    val unknownExpiryCount: Int,
) {
    /** Share of domains that do not need attention, for the expiry health bar. */
    val healthFraction: Float
        get() = if (domainCount == 0) 0f else (domainCount - attentionCount).coerceAtLeast(0).toFloat() / domainCount

    val healthLabel: String
        get() = when {
            domainCount == 0 -> "No data"
            attentionCount > 0 -> "$attentionCount need attention"
            unknownExpiryCount > 0 -> "$unknownExpiryCount unknown"
            else -> "Clear"
        }

    val healthTone: RegistrarTone
        get() = when {
            domainCount == 0 -> RegistrarTone.NEUTRAL
            attentionCount > 0 -> RegistrarTone.WARNING
            unknownExpiryCount > 0 -> RegistrarTone.NEUTRAL
            else -> RegistrarTone.SUCCESS
        }

    companion object {
        fun of(domains: List<RegistrarDomainUi>, nowMillis: Long): RegistrarPortfolioSummary {
            var attention = 0
            var unknown = 0
            var autoRenew = 0
            domains.forEach { domain ->
                val days = domain.expiresAtMillis?.let { registrarDaysUntil(it, nowMillis) }
                if (days == null) unknown += 1 else if (days <= ATTENTION_DAYS) attention += 1
                if (domain.autoRenew == true) autoRenew += 1
            }
            return RegistrarPortfolioSummary(domains.size, attention, autoRenew, unknown)
        }
    }
}

/** iOS search: domain name or status contains the query (case-insensitive). */
internal fun filterRegistrarDomains(domains: List<RegistrarDomainUi>, query: String): List<RegistrarDomainUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return domains
    return domains.filter {
        it.name.contains(normalized, ignoreCase = true) ||
            it.status?.contains(normalized, ignoreCase = true) == true
    }
}

/** Expiry tile value and unit, e.g. "18"/"DAYS", "4"/"EXPIRED" or "—"/"UNKNOWN". */
internal fun registrarExpiryValue(expiresAtMillis: Long?, nowMillis: Long): Pair<String, String> {
    val days = expiresAtMillis?.let { registrarDaysUntil(it, nowMillis) } ?: return "—" to "UNKNOWN"
    return formatCount(kotlin.math.abs(days.toLong())) to if (days < 0) "EXPIRED" else "DAYS"
}

internal fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

internal fun formatRegistrarDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

internal fun formatRegistrarDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

internal fun registrarCacheLabel(cacheState: RegistrarCacheState): String = when (cacheState) {
    RegistrarCacheState.LIVE -> "Live"
    RegistrarCacheState.CACHED_FRESH -> "Saved"
    RegistrarCacheState.CACHED_STALE -> "Stale"
}

internal fun registrarBooleanText(value: Boolean?): String = when (value) {
    true -> "On"
    false -> "Off"
    null -> "Not returned"
}

/** `https://<domain>` when the registrar name is a plain hostname, else null (button hidden). */
internal fun registrarDomainUrl(name: String): String? {
    val ascii = runCatching { java.net.IDN.toASCII(name.trim().trimEnd('.')) }.getOrNull() ?: return null
    if (ascii.isEmpty() || ascii.length > 253 || !HOSTNAME.matches(ascii)) return null
    return "https://${ascii.lowercase(Locale.ROOT)}"
}

private val HOSTNAME = Regex("(?i)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")

/** Only the fixed HTTPS registrar/domain links are opened externally. */
internal fun isOpenableRegistrarUrl(url: String): Boolean =
    url.startsWith("https://") && url.none { it.isWhitespace() || it.isISOControl() }

internal fun catalogProvider(providerId: String): IntegrationProvider? = IntegrationCatalog.provider(providerId)

@Composable
@ReadOnlyComposable
internal fun registrarWarningColor(): Color = if (LocalVercelticsDarkTheme.current) DarkWarning else LightWarning

@Composable
@ReadOnlyComposable
internal fun registrarToneColor(tone: RegistrarTone, accent: Color): Color = when (tone) {
    RegistrarTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    RegistrarTone.WARNING -> registrarWarningColor()
    RegistrarTone.DANGER -> MaterialTheme.colorScheme.error
    RegistrarTone.PROGRESS -> accent
    RegistrarTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun RegistrarFeedbackPanel(
    title: String?,
    message: String,
    isError: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val tint = if (isError) MaterialTheme.colorScheme.error else accent
    OffsetPanel(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        color = tint.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = tint.copy(alpha = 0.28f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = tint,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    title?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (title == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (actionText != null && onAction != null) {
                ThemedActionButton(
                    text = actionText,
                    onClick = onAction,
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = testTag?.let { "$it.action" },
                )
            }
        }
    }
}

@Composable
internal fun RegistrarWarningPanel(message: String, modifier: Modifier = Modifier) {
    val warning = registrarWarningColor()
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = warning.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = warning.copy(alpha = 0.32f),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = warning)
            Spacer(Modifier.width(9.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
