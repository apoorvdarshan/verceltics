package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import java.text.DateFormat
import java.util.Date

internal fun siteCatalogProvider(providerId: String): IntegrationProvider =
    checkNotNull(IntegrationCatalog.provider(providerId)) { "Unknown site service $providerId." }

internal fun siteAccent(providerId: String): Color =
    IntegrationCatalog.provider(providerId)?.let { Color(it.accentColor) } ?: Color(0xFF4FA1FF)

internal val SiteWarningColor = Color(0xFFE29A00)

@Composable
internal fun siteToneColor(tone: SiteStatusTone): Color = when (tone) {
    SiteStatusTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    SiteStatusTone.WARNING -> SiteWarningColor
    SiteStatusTone.DANGER -> MaterialTheme.colorScheme.error
    SiteStatusTone.PROGRESS -> MaterialTheme.colorScheme.primary
    SiteStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun formatSiteTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

internal fun cacheLabel(cacheState: SiteServiceCacheState): String = when (cacheState) {
    SiteServiceCacheState.LIVE -> "Live"
    SiteServiceCacheState.CACHED_FRESH -> "Saved"
    SiteServiceCacheState.CACHED_STALE -> "Stale"
}

/** Compact value-over-label metric used on overview and resource cards (iOS `compactMetric`). */
@Composable
internal fun SiteCompactMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SiteFeedbackPanel(
    title: String,
    message: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Info,
    testTag: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = color.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action?.invoke()
        }
    }
}

@Composable
internal fun SiteErrorPanel(title: String, message: String, testTag: String? = null, action: (@Composable () -> Unit)? = null) =
    SiteFeedbackPanel(title, message, MaterialTheme.colorScheme.error, icon = Icons.Rounded.ErrorOutline, testTag = testTag, action = action)

@Composable
internal fun SiteWarningPanel(title: String, message: String, testTag: String? = null) =
    SiteFeedbackPanel(title, message, SiteWarningColor, icon = Icons.Rounded.WarningAmber, testTag = testTag)

@Composable
internal fun SiteNoticePanel(title: String, message: String, accent: Color, testTag: String? = null) =
    SiteFeedbackPanel(title, message, accent, icon = Icons.Rounded.CheckCircle, testTag = testTag)

@Composable
internal fun SiteLoadingState(message: String, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = accent)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Monospace section label with a count chip (iOS `AppSectionHeader`). */
@Composable
internal fun SiteSectionHeader(title: String, count: Int?, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            modifier = Modifier.weight(1f),
            color = accent,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp),
        )
        count?.let { LabelChip(it.toString()) }
    }
}
