package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelDeploymentDetailUiState
import com.apoorvdarshan.verceltics.ui.VercelDeploymentEventUi
import com.apoorvdarshan.verceltics.ui.VercelDeploymentUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Maximum build events rendered, matching iOS. */
const val VERCEL_MAX_RENDERED_EVENTS: Int = 80

/**
 * One deployment (iOS `DeploymentDetailView`): status, Open and Inspect links, details, and the
 * newest build events with a retry banner when a refresh fails over cached events.
 */
@Composable
fun VercelDeploymentDetailScreen(
    project: VercelProjectUi,
    deployment: VercelDeploymentUi,
    state: VercelDeploymentDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.deployment"),
    ) {
        AppToolbar(
            title = "Deployment",
            leading = {
                AppToolbarAction(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onBack()
                    },
                    testTag = "workspace.hosting.deployment.back",
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to ${project.name} analytics")
                }
            },
            trailing = {
                AppToolbarAction(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onRefresh()
                    },
                    enabled = !state.isLoading,
                    testTag = "workspace.hosting.deployment.refresh",
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = if (state.isLoading) "Refreshing build events" else "Refresh build events"
                                if (state.isLoading) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                        }
                    }
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") { DeploymentHeader(project, deployment, onOpenUrl) }
            item(key = "details") { DeploymentDetails(deployment) }
            item(key = "events-header") {
                VercelInfoPanel(
                    title = "Build Events",
                    icon = Icons.Rounded.Terminal,
                    testTag = "workspace.hosting.deployment.events",
                ) {
                    when {
                        state.isLoading && !state.hasLoaded -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text(
                                "Loading events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        state.error != null && !state.hasLoaded -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 28.dp)
                                .testTag("workspace.hosting.deployment.events.error"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.Warning, contentDescription = null, tint = vercelWarningColor())
                            Text(
                                state.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }

                        else -> {
                            if (state.error != null) {
                                VercelFeedbackBanner(
                                    title = "Event refresh failed",
                                    message = "${state.error} Showing the last successful result.",
                                    tint = vercelWarningColor(),
                                    actionTitle = "Retry",
                                    onAction = onRefresh,
                                    modifier = Modifier.padding(12.dp),
                                    testTag = "workspace.hosting.deployment.events.retry",
                                )
                            }
                            if (state.events.isEmpty()) {
                                Text(
                                    "No build events returned",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 30.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                state.events.take(VERCEL_MAX_RENDERED_EVENTS).forEach { event ->
                                    DeploymentEventRow(event, timeFormat.format(Date(event.createdAtMillis)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeploymentHeader(
    project: VercelProjectUi,
    deployment: VercelDeploymentUi,
    onOpenUrl: (String) -> Unit,
) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "workspace.hosting.deployment.header",
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                VercelProjectIcon(domain = project.primaryDomain, name = project.name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        deployment.url ?: project.primaryDomain ?: "Deployment",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                VercelStatusBadge(
                    text = capitalizedVercelState(deployment.state),
                    tone = vercelStatusTone(deployment.state),
                )
            }
            val openUrl = vercelWebsiteUrl(deployment.url)
            val inspectUrl = vercelInspectorUrl(deployment.inspectorUrl)
            if (openUrl != null || inspectUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    openUrl?.let {
                        DeploymentLinkButton("Open", Icons.AutoMirrored.Rounded.OpenInNew, "workspace.hosting.deployment.open") {
                            onOpenUrl(it)
                        }
                    }
                    inspectUrl?.let {
                        DeploymentLinkButton("Inspect", Icons.Rounded.Search, "workspace.hosting.deployment.inspect") {
                            onOpenUrl(it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeploymentLinkButton(title: String, icon: ImageVector, testTag: String, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier
            .heightIn(min = 44.dp)
            .testTag(testTag),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeploymentDetails(deployment: VercelDeploymentUi) {
    VercelInfoPanel(
        title = "Details",
        icon = Icons.Rounded.Inventory2,
        testTag = "workspace.hosting.deployment.details",
    ) {
        VercelDetailRow(Icons.Rounded.TrackChanges, "Target", deployment.target)
        VercelDetailRow(
            Icons.Rounded.Schedule,
            "Created",
            deployment.createdAtMillis?.let { vercelRelativeTime(it) } ?: "Unknown",
        )
        deployment.creator?.let { VercelDetailRow(Icons.Rounded.Person, "Creator", it) }
        deployment.repository?.let { VercelDetailRow(Icons.Rounded.Code, "Repository", it) }
        deployment.branch?.let { VercelDetailRow(Icons.AutoMirrored.Rounded.CallSplit, "Branch", it) }
        shortVercelSha(deployment.commitSha, length = 12)?.let { VercelDetailRow(Icons.Rounded.Numbers, "Commit", it) }
        deployment.commitMessage?.let { VercelDetailRow(Icons.Rounded.ChatBubble, "Message", it) }
    }
}

@Composable
private fun DeploymentEventRow(event: VercelDeploymentEventUi, time: String) {
    val color = vercelToneColor(vercelEventTone(event.type, event.statusCode))
    Box(Modifier.fillMaxWidth().testTag("workspace.hosting.deployment.event")) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            VercelStatusDot(color = color, size = 7.dp, modifier = Modifier.padding(top = 6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        event.type.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        maxLines = 1,
                    )
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SelectionContainer {
                    Text(
                        event.message,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
