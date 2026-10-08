package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.rememberReducedMotion

/**
 * Vercel-style project card (iOS `ProjectCard`): favicon, name with a fresh-deploy pulse, primary
 * domain, repository, framework and team, then the last commit and when it deployed. A long
 * press opens the project actions; TalkBack exposes the same actions as custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VercelProjectCard(
    project: VercelProjectUi,
    actions: List<VercelProjectAction>,
    onOpen: () -> Unit,
    onAction: (VercelProjectAction) -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val haptic = LocalHapticFeedback.current
    var menuExpanded by rememberSaveable(project.id) { mutableStateOf(false) }
    // TalkBack actions are remembered, so they must call the latest (Pro-gated) handler.
    val currentOnAction by rememberUpdatedState(onAction)
    val accessibilityActions = remember(actions) {
        actions.map { action ->
            CustomAccessibilityAction(action.label) {
                currentOnAction(action)
                true
            }
        }
    }
    Box(modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("workspace.hosting.project.${project.id}")
                .combinedClickable(
                    onClickLabel = "Open ${project.name} analytics",
                    onLongClickLabel = "Show project actions",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onOpen()
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    },
                )
                .semantics(mergeDescendants = true) { customActions = accessibilityActions },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            VercelProjectCardContent(project = project, nowMillis = nowMillis)
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            modifier = Modifier
                .widthIn(min = 220.dp)
                .testTag("workspace.hosting.projectMenu"),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            actions.forEach { action ->
                if (action is VercelProjectAction.ViewAnalytics && actions.size > 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                DropdownMenuItem(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("workspace.hosting.projectMenu.${action.testTagSuffix}"),
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        menuExpanded = false
                        onAction(action)
                    },
                )
            }
        }
    }
}

private val VercelProjectAction.icon: ImageVector
    get() = when (this) {
        is VercelProjectAction.OpenWebsite -> Icons.Rounded.Language
        is VercelProjectAction.CopyUrl -> Icons.Rounded.ContentCopy
        is VercelProjectAction.ViewOnVercel -> Icons.AutoMirrored.Rounded.OpenInNew
        VercelProjectAction.ViewAnalytics -> Icons.Rounded.BarChart
    }

@Composable
private fun VercelProjectCardContent(project: VercelProjectUi, nowMillis: Long) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            VercelProjectIcon(domain = project.primaryDomain, name = project.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isFreshVercelDeploy(project, nowMillis)) FreshDeployPulse()
                }
                project.primaryDomain?.let { domain ->
                    Text(
                        domain,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = secondary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }

        project.repository?.let { repository ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = secondary, modifier = Modifier.size(15.dp))
                Text(
                    repository,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }

        val teamName = project.scope?.takeIf { it.isTeam }?.name
        if (project.framework != null || teamName != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                project.framework?.let { framework ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = vercelFrameworkColorArgb(framework)?.let(::Color)
                            ?: MaterialTheme.colorScheme.onSurface
                        VercelStatusDot(color = dotColor, size = 6.dp)
                        Text(
                            prettyVercelFrameworkName(framework),
                            style = MaterialTheme.typography.labelMedium,
                            color = secondary,
                            maxLines = 1,
                        )
                    }
                }
                teamName?.let { name ->
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Groups, contentDescription = null, tint = secondary, modifier = Modifier.size(15.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        project.lastDeployment?.let { deployment ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                deployment.commitMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                deployment.createdAtMillis?.let { createdAt ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = secondary, modifier = Modifier.size(12.dp))
                        Text(
                            vercelRelativeTime(createdAt, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = secondary,
                        )
                    }
                }
            }
        }
    }
}

/** Green dot that breathes for deployments under 30 minutes old; steady with reduced motion. */
@Composable
private fun FreshDeployPulse() {
    val reducedMotion = rememberReducedMotion()
    val alpha = if (reducedMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "freshDeploy")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_200), RepeatMode.Reverse),
            label = "freshDeployAlpha",
        )
        animated
    }
    Box(
        Modifier
            .size(6.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
            .testTag("vercel.project.freshDeploy"),
    )
}
