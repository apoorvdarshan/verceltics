package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.ui.text.style.TextAlign
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelConnectionStatus
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelDashboardUi
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import java.text.DateFormat
import java.util.Date
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess

/**
 * Native Hosting root for the first connected Android provider slice.
 *
 * The screen restores the existing encrypted Vercel account without moving or rewriting it. When
 * no account exists it delegates to the same disconnected workspace composition used by iOS.
 */
@Composable
fun VercelWorkspaceScreen(
    vercelConnectionViewModel: VercelConnectionViewModel,
    searchRequestId: Int,
    refreshRequestId: Int,
    onConnectProvider: (IntegrationProvider) -> Unit,
    connectedProviderContent: (@Composable () -> Unit)? = null,
    connectedProviderIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val state by vercelConnectionViewModel.uiState.collectAsStateWithLifecycle()
    val analyticsState by vercelConnectionViewModel.analyticsState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastHandledRefreshRequestId by rememberSaveable { mutableIntStateOf(0) }
    var lastHandledSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var unavailableSearchNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(refreshRequestId) {
        if (refreshRequestId > 0 && refreshRequestId != lastHandledRefreshRequestId) {
            lastHandledRefreshRequestId = refreshRequestId
            vercelConnectionViewModel.refresh()
        }
    }

    LaunchedEffect(searchRequestId, state.status, state.isSearchAvailable) {
        if (state.status != VercelConnectionStatus.RESTORING &&
            searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId
        ) {
            lastHandledSearchRequestId = searchRequestId
            if (state.isSearchAvailable) {
                unavailableSearchNotice = null
                withFrameNanos { }
                if (selectedProjectId != null) {
                    selectedProjectId = null
                    withFrameNanos { }
                }
                searchFocusRequester.requestFocus()
                withFrameNanos { }
                keyboard?.show()
            } else if (state.status == VercelConnectionStatus.SAVED_UNAVAILABLE) {
                unavailableSearchNotice =
                    "Project search is unavailable while the saved Vercel dashboard is offline. Retry the connection first."
            }
        }
    }

    LaunchedEffect(state.status, state.dashboard?.projects) {
        val projectId = selectedProjectId ?: return@LaunchedEffect
        val projectStillExists = state.dashboard?.projects?.any { it.id == projectId }
        if (shouldClearSavedProjectSelection(state.status, projectStillExists)) {
            selectedProjectId = null
            vercelConnectionViewModel.closeProjectAnalytics()
        }
    }

    // Project analytics is Pro: close a project restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, selectedProjectId) {
        if (proAccess.isConfirmedLocked && selectedProjectId != null) {
            selectedProjectId = null
            vercelConnectionViewModel.closeProjectAnalytics()
        }
    }

    val selectedProject = selectedProjectId?.let { projectId ->
        state.dashboard?.projects?.firstOrNull { it.id == projectId }
    }

    LaunchedEffect(selectedProject?.id) {
        selectedProject?.let(vercelConnectionViewModel::openProjectAnalytics)
    }

    BackHandler(enabled = selectedProject != null) {
        selectedProjectId = null
        vercelConnectionViewModel.closeProjectAnalytics()
    }

    when {
        selectedProject != null -> VercelAnalyticsScreen(
            project = selectedProject,
            account = requireNotNull(state.dashboard).account,
            state = analyticsState,
            onBack = {
                selectedProjectId = null
                vercelConnectionViewModel.closeProjectAnalytics()
            },
            onRefresh = vercelConnectionViewModel::refreshProjectAnalytics,
            onRangeSelected = vercelConnectionViewModel::selectAnalyticsRange,
            onEnvironmentSelected = vercelConnectionViewModel::selectAnalyticsEnvironment,
            modifier = modifier,
        )

        state.status == VercelConnectionStatus.RESTORING -> HostingLoadingScreen(modifier)
        state.status == VercelConnectionStatus.SAVED_UNAVAILABLE -> SavedVercelUnavailableWorkspace(
            account = state.savedAccount,
            error = state.error ?: "The saved Vercel account could not be loaded.",
            searchNotice = unavailableSearchNotice,
            isRefreshing = state.isBusy,
            onRetry = vercelConnectionViewModel::refresh,
            onDisconnect = {
                selectedProjectId = null
                vercelConnectionViewModel.disconnect()
            },
            connectedProviderContent = connectedProviderContent,
            modifier = modifier,
        )

        state.status == VercelConnectionStatus.DISCONNECTED -> WorkspaceScreen(
            workspace = Workspace.HOSTING,
            onConnectProvider = onConnectProvider,
            onAccountAction = {},
            persistenceError = state.error,
            connectedContent = connectedProviderContent,
            connectedProviderIds = connectedProviderIds,
            searchRequestId = searchRequestId,
            modifier = modifier,
        )

        else -> ConnectedVercelWorkspace(
            dashboard = requireNotNull(state.dashboard),
            isRefreshing = state.isBusy,
            error = state.error,
            searchFocusRequester = searchFocusRequester,
            onRefresh = {
                if (!state.isBusy) vercelConnectionViewModel.refresh()
            },
            onManageConnection = {
                IntegrationCatalog.provider("vercel")?.let(onConnectProvider)
            },
            onDisconnect = {
                selectedProjectId = null
                vercelConnectionViewModel.disconnect()
            },
            onProjectSelected = { project ->
                proAccess.requestPro {
                    selectedProjectId = project.id
                    vercelConnectionViewModel.openProjectAnalytics(project)
                }
            },
            connectedProviderIds = connectedProviderIds,
            onConnectNetlify = {
                IntegrationCatalog.provider("netlify")?.let(onConnectProvider)
            },
            onConnectCloudflare = {
                IntegrationCatalog.provider("cloudflare")?.let(onConnectProvider)
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun SavedVercelUnavailableWorkspace(
    account: VercelAccountUi?,
    error: String,
    searchNotice: String?,
    isRefreshing: Boolean,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    connectedProviderContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    if (showDisconnectConfirmation) {
        ThemedAlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            title = "Disconnect saved Vercel account?",
            message = "The encrypted token will be removed from this device.",
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                showDisconnectConfirmation = false
                onDisconnect()
            },
            enabled = !isRefreshing,
            testTag = "workspace.hosting.savedUnavailable.disconnectDialog",
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item(key = "recovery") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusPill(text = "Saved securely", color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        account?.displayName ?: "Saved Vercel account",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "The encrypted account is still on this device, but its live dashboard is unavailable. Reconnecting will not overwrite it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HostingFeedbackBanner(error)
                    searchNotice?.let { message -> HostingWarningBanner(message) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemedActionButton(
                            text = if (isRefreshing) "RETRYING…" else "RETRY",
                            enabled = !isRefreshing,
                            isBusy = isRefreshing,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onRetry()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ThemedActionButton(
                            text = "DISCONNECT",
                            enabled = !isRefreshing,
                            tone = ThemedActionTone.DESTRUCTIVE,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                showDisconnectConfirmation = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        connectedProviderContent?.let { content ->
            item(key = "connected-provider") { content() }
        }
    }
}

@Composable
private fun ConnectedVercelWorkspace(
    dashboard: VercelDashboardUi,
    isRefreshing: Boolean,
    error: String?,
    searchFocusRequester: FocusRequester,
    onRefresh: () -> Unit,
    onManageConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onProjectSelected: (VercelProjectUi) -> Unit,
    connectedProviderIds: Set<String>,
    onConnectNetlify: () -> Unit,
    onConnectCloudflare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val visibleProjects = remember(dashboard.projects, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            dashboard.projects
        } else {
            dashboard.projects.filter { project ->
                project.name.contains(normalized, ignoreCase = true) ||
                    project.framework?.contains(normalized, ignoreCase = true) == true
            }
        }
    }

    if (showDisconnectConfirmation) {
        ThemedAlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            title = "Disconnect Vercel?",
            message = "The saved token will be removed from this device.",
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                showDisconnectConfirmation = false
                query = ""
                onDisconnect()
            },
            enabled = !isRefreshing,
            testTag = "workspace.hosting.disconnectDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.hosting.connected"),
    ) {
        AppToolbar(
            title = "Hosting",
            leading = {
            Box {
                AppToolbarAction(
                    modifier = Modifier
                        .size(48.dp),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        accountMenuExpanded = true
                    },
                    testTag = "workspace.hosting.account",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = "Switch connected hosting account"
                                role = Role.Button
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IntegrationCatalog.provider("vercel")?.let { provider ->
                            ProviderLogo(provider, Modifier.size(24.dp), monochrome = true)
                        }
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = accountMenuExpanded,
                    onDismissRequest = { accountMenuExpanded = false },
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 340.dp)
                        .testTag("workspace.hosting.accountMenu"),
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(13.dp),
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
                ) {
                    DropdownMenuItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 54.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .semantics {
                                selected = true
                                stateDescription = "Connected account"
                            },
                        text = {
                            Column {
                                Text(dashboard.account.displayName, fontWeight = FontWeight.Bold)
                                dashboard.account.email?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.tertiary,
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            accountMenuExpanded = false
                        },
                    )
                    listOf("cloudflare" to onConnectCloudflare, "netlify" to onConnectNetlify).forEach { (id, openProvider) ->
                        val provider = requireNotNull(IntegrationCatalog.provider(id))
                        DropdownMenuItem(
                            modifier = Modifier.testTag("workspace.hosting.connect${if (id == "cloudflare") "Cloudflare" else "Netlify"}"),
                            text = { Text(if (id in connectedProviderIds) provider.displayName else "Connect ${provider.displayName}") },
                            leadingIcon = { ProviderLogo(provider, Modifier.size(22.dp)) },
                            onClick = { accountMenuExpanded = false; openProvider() },
                        )
                    }
                    DropdownMenuItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp),
                        text = { Text("Manage connection") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.AddCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            accountMenuExpanded = false
                            onManageConnection()
                        },
                    )
                    DropdownMenuItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                        text = { Text("Remove current account") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            accountMenuExpanded = false
                            showDisconnectConfirmation = true
                        },
                    )
                }
            }
            },
            trailing = {
                AppToolbarAction(
                    modifier = Modifier.size(48.dp),
                    enabled = !isRefreshing,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onRefresh()
                    },
                    testTag = "workspace.hosting.refresh",
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, "Refresh hosting projects", Modifier.size(22.dp))
                    }
                }
            },
        )

        ControlSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search Vercel projects",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            testTag = "workspace.hosting.searchField",
            focusRequester = searchFocusRequester,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(start = 18.dp, top = 2.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "account-summary") {
                ProviderSummaryCard(
                    provider = requireNotNull(IntegrationCatalog.provider("vercel")),
                    title = dashboard.account.displayName,
                    subtitle = dashboard.account.email ?: "Vercel account",
                    status = "Connected",
                    detail = "${dashboard.projects.size} projects",
                    testTag = "workspace.hosting.summary",
                )
            }

            if (isRefreshing) {
                item(key = "refreshing") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }

            if (error != null) {
                item(key = "error") {
                    HostingFeedbackBanner(error)
                }
            }

            dashboard.warning?.let { warning ->
                item(key = "partial-warning") {
                    HostingWarningBanner(warning)
                }
            }

            item(key = "heading") {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                ) {
                    val useStackedLayout = shouldUseStackedVercelLayout(
                        availableWidthDp = maxWidth.value,
                        fontScale = LocalDensity.current.fontScale,
                    )
                    if (useStackedLayout) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Projects", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "${visibleProjects.size} shown · ${dashboard.projects.size} total",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Projects", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "${visibleProjects.size}/${dashboard.projects.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (dashboard.projects.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No projects were returned for this account.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (visibleProjects.isEmpty()) {
                item(key = "no-match") {
                    Text(
                        "No Vercel project matches “$query”.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(visibleProjects, key = VercelProjectUi::id) { project ->
                    VercelWorkspaceProjectRow(
                        project = project,
                        onClick = { onProjectSelected(project) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VercelWorkspaceProjectRow(
    project: VercelProjectUi,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowOffset = 3.dp,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.hosting.project.${project.id}",
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val useStackedLayout = shouldUseStackedVercelLayout(
                availableWidthDp = maxWidth.value,
                fontScale = LocalDensity.current.fontScale,
            )
            if (useStackedLayout) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VercelProjectMark()
                        Text(
                            text = project.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        VercelProjectOpenIcon(project.name)
                    }
                    Text(
                        text = projectMetadata(project),
                        modifier = Modifier.padding(start = 54.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VercelProjectMark()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = projectMetadata(project),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    VercelProjectOpenIcon(project.name)
                }
            }
        }
    }
}

@Composable
private fun VercelAccountSummaryText(
    account: VercelAccountUi,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = account.displayName,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        account.email?.let { email ->
            Text(
                text = email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VercelProjectMark() {
    Surface(
        modifier = Modifier.size(34.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IntegrationCatalog.provider("vercel")?.let { provider ->
                ProviderLogo(
                    provider = provider,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun VercelProjectOpenIcon(projectName: String) {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
        contentDescription = "Open $projectName analytics",
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun projectMetadata(project: VercelProjectUi): String =
    listOfNotNull(
        project.framework,
        project.updatedAtMillis?.let(::formatProjectDate),
    ).joinToString(" · ").ifBlank { "Project ${project.id.take(8)}" }

@Composable
private fun HostingFeedbackBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .background(
                MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HostingWarningBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("!", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HostingLoadingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("workspace.hosting.loading"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(98.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
        )
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
            )
        }
    }
}

private fun formatProjectDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))

/** Keeps identity, project, and data rows readable on narrow windows and at larger font scales. */
internal fun shouldUseStackedVercelLayout(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 280f || fontScale >= 1.30f

internal fun shouldClearSavedProjectSelection(
    status: VercelConnectionStatus,
    projectStillExists: Boolean?,
): Boolean = when (status) {
    VercelConnectionStatus.RESTORING -> false
    VercelConnectionStatus.CONNECTED -> projectStillExists != true
    VercelConnectionStatus.DISCONNECTED,
    VercelConnectionStatus.SAVED_UNAVAILABLE,
    -> true
}
