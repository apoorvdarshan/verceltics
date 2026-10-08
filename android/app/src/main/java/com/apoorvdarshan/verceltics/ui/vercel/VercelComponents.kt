package com.apoorvdarshan.verceltics.ui.vercel

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.SkeletonBlock
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.shimmer
import com.apoorvdarshan.verceltics.ui.theme.DarkWarning
import com.apoorvdarshan.verceltics.ui.theme.LightWarning
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.util.Locale

@Composable
@ReadOnlyComposable
internal fun vercelWarningColor(): Color = if (LocalVercelticsDarkTheme.current) DarkWarning else LightWarning

@Composable
@ReadOnlyComposable
internal fun vercelToneColor(tone: VercelStatusTone): Color = when (tone) {
    VercelStatusTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    VercelStatusTone.WARNING -> vercelWarningColor()
    VercelStatusTone.DANGER -> MaterialTheme.colorScheme.error
    VercelStatusTone.PROGRESS -> MaterialTheme.colorScheme.primary
    VercelStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "5 minutes ago", "Yesterday"… localized by the platform; "Just now" under a minute. */
internal fun vercelRelativeTime(timeMillis: Long, nowMillis: Long = System.currentTimeMillis()): String =
    if (kotlin.math.abs(nowMillis - timeMillis) < DateUtils.MINUTE_IN_MILLIS) {
        "Just now"
    } else {
        DateUtils.getRelativeTimeSpanString(timeMillis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
    }

/** Square tinted icon tile (iOS `AppIconTile`). */
@Composable
internal fun VercelIconTile(icon: ImageVector, tint: Color, size: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Inline banner with an optional action (iOS `AppFeedbackBanner`). */
@Composable
internal fun VercelFeedbackBanner(
    title: String,
    message: String,
    tint: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Warning,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = tint.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = tint.copy(alpha = 0.30f),
        testTag = testTag,
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VercelIconTile(icon = icon, tint = tint, size = 34.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionTitle != null && onAction != null) {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onAction()
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .then(if (testTag == null) Modifier else Modifier.testTag("$testTag.action")),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
                    ) {
                        Text(actionTitle, color = tint, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Centered icon, title, message and an optional primary action (iOS `AppEmptyState`). */
@Composable
internal fun VercelEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 12.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        VercelIconTile(icon = icon, tint = MaterialTheme.colorScheme.primary, size = 50.dp)
        Column(
            modifier = Modifier.widthIn(max = 380.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionTitle != null && onAction != null) {
            ThemedActionButton(
                text = actionTitle,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAction()
                },
                modifier = Modifier.widthIn(min = 160.dp),
                testTag = testTag?.let { "$it.action" },
            )
        }
    }
}

/** Titled card with an icon tile header and a divider (iOS `infoPanel`). */
@Composable
internal fun VercelInfoPanel(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = testTag,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .semantics { heading() },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VercelIconTile(icon = icon, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

/** Icon, uppercase caption and value; tappable rows show an external-link arrow. */
@Composable
internal fun VercelDetailRow(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
    if (onClick == null) {
        Box(rowModifier) { content() }
    } else {
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            },
            modifier = rowModifier.semantics { role = Role.Button },
            color = Color.Transparent,
        ) { content() }
    }
}

/** Capsule badge tinted by status (iOS `AppStatusBadge`). */
@Composable
internal fun VercelStatusBadge(text: String, tone: VercelStatusTone, modifier: Modifier = Modifier) {
    val color = vercelToneColor(tone)
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = color,
        shape = CircleShape,
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.25f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun VercelStatusDot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Placeholder card used while projects load (iOS `SkeletonCard`). */
@Composable
internal fun VercelSkeletonCard(modifier: Modifier = Modifier) {
    OffsetPanel(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shimmer()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(width = 40.dp, height = 40.dp, strong = true, shape = RoundedCornerShape(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkeletonBlock(width = 120.dp, height = 14.dp, strong = true)
                    SkeletonBlock(width = 180.dp, height = 10.dp)
                }
            }
            SkeletonBlock(width = 140.dp, height = 20.dp, shape = RoundedCornerShape(10.dp))
            SkeletonBlock(width = 220.dp, height = 10.dp)
            SkeletonBlock(width = 100.dp, height = 10.dp)
        }
    }
}

/** Stats, chart and breakdown placeholders shown before the first analytics result. */
@Composable
internal fun VercelAnalyticsSkeleton(chartHeight: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workspace.hosting.analytics.loading")
            .shimmer(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(3) {
                OffsetPanel(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonBlock(width = 60.dp, height = 12.dp, strong = true)
                        SkeletonBlock(width = 44.dp, height = 24.dp, strong = true)
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
        )
        repeat(3) {
            OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(width = 80.dp, height = 14.dp, strong = true)
                    repeat(4) { SkeletonBlock(width = null, height = 36.dp, modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
