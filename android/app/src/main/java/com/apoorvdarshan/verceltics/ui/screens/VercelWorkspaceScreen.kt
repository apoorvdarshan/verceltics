package com.apoorvdarshan.verceltics.ui.screens

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.SkeletonBlock
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.shimmer
import com.apoorvdarshan.verceltics.ui.vercel.LocalVercelFaviconSource
import com.apoorvdarshan.verceltics.ui.vercel.VercelDeploymentDetailScreen
import com.apoorvdarshan.verceltics.ui.vercel.VercelEmptyState
import com.apoorvdarshan.verceltics.ui.vercel.VercelFaviconSource
import com.apoorvdarshan.verceltics.ui.vercel.VercelFeedbackBanner
import com.apoorvdarshan.verceltics.ui.vercel.VercelProjectAction
import com.apoorvdarshan.verceltics.ui.vercel.VercelProjectCard
import com.apoorvdarshan.verceltics.ui.vercel.VercelProjectsFirstLoad
import com.apoorvdarshan.verceltics.ui.vercel.VercelSkeletonCard
import com.apoorvdarshan.verceltics.ui.vercel.isOpenableVercelUrl
import com.apoorvdarshan.verceltics.ui.vercel.vercelProjectActions
import com.apoorvdarshan.verceltics.ui.vercel.vercelWarningColor
import com.apoorvdarshan.verceltics.ui.vercel.visibleVercelProjects
import kotlinx.coroutines.launch

/** Where to create a project when the account has none (iOS `EmptyStateView` "Open Vercel"). */
internal const val VERCEL_NEW_PROJECT_URL = "https://vercel.com/new"

/**
 * Native Hosting root for the first connected Android provider slice.
 *
 * The screen restores the existing encrypted Vercel account without moving or rewriting it. When
 * no account exists it delegates to the same disconnected workspace composition used by iOS.
 *
 * [refreshRequestId] (bumped when the app returns to the foreground) only reloads projects older
 * than the 15-minute freshness window; the toolbar and pull-to-refresh always reload.
 * [onProjectsFirstLoaded] runs once per process, the first time projects load successfully.
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
    onProjectsFirstLoaded: () -> Unit = {},
) {
    val state by vercelConnectionViewModel.uiState.collectAsStateWithLifecycle()
    val analyticsState by vercelConnectionViewModel.analyticsState.collectAsStateWithLifecycle()
    val deploymentState by vercelConnectionViewModel.deploymentState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var selectedProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedDeploymentId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastHandledRefreshRequestId by rememberSaveable { mutableIntStateOf(0) }
    var lastHandledSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    var unavailableSearchNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnProjectsFirstLoaded by rememberUpdatedState(onProjectsFirstLoaded)
    val faviconSource = remember(vercelConnectionViewModel) {
        object : VercelFaviconSource {
            override suspend fun load(domain: String) = vercelConnectionViewModel.loadFavicon(domain)

            override fun cached(domain: String) = vercelConnectionViewModel.cachedFavicon(domain)
        }
    }
    // Survives the trip into a deployment and back, like an iOS navigation stack.
    val analyticsGridState = rememberSaveable(selectedProjectId, saver = LazyGridState.Saver) { LazyGridState() }
    val openUrl: (String) -> Unit = { url ->
        if (isOpenableVercelUrl(url)) runCatching { uriHandler.openUri(url) }
    }

    fun closeProject() {
        selectedDeploymentId = null
        selectedProjectId = null
        vercelConnectionViewModel.closeProjectAnalytics()
    }

    LaunchedEffect(refreshRequestId) {
        if (refreshRequestId > 0 && refreshRequestId != lastHandledRefreshRequestId) {
            lastHandledRefreshRequestId = refreshRequestId
            vercelConnectionViewModel.refreshIfStale()
        }
    }

    LaunchedEffect(state.status, state.dashboard?.projects?.size, state.error) {
        if (
            shouldNotifyProjectsFirstLoaded(state.status, state.dashboard?.projects?.size ?: 0, state.error) &&
            VercelProjectsFirstLoad.consume()
        ) {
            currentOnProjectsFirstLoaded()
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
                    closeProject()
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
        if (shouldClearSavedProjectSelection(state.status, projectStillExists)) closeProject()
    }

    // Project analytics is Pro: close a project restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, selectedProjectId) {
        if (proAccess.isConfirmedLocked && selectedProjectId != null) closeProject()
    }

    val selectedProject = selectedProjectId?.let { projectId ->
        state.dashboard?.projects?.firstOrNull { it.id == projectId }
    }
    val selectedDeployment = selectedDeploymentId?.let { deploymentId ->
        analyticsState.recentDeployments.firstOrNull { it.id == deploymentId }
    }

    LaunchedEffect(selectedProject?.id) {
        selectedProject?.let(vercelConnectionViewModel::openProjectAnalytics)
    }

    LaunchedEffect(selectedDeployment?.id) {
        selectedDeployment?.let(vercelConnectionViewModel::openDeployment)
    }

    // A deployment restored from saved state disappears once fresh project context omits it.
    LaunchedEffect(selectedDeploymentId, analyticsState.hasLoadedContext, analyticsState.isContextLoading) {
        if (
            selectedDeploymentId != null && selectedDeployment == null &&
            analyticsState.hasLoadedContext && !analyticsState.isContextLoading
        ) {
            selectedDeploymentId = null
            vercelConnectionViewModel.closeDeployment()
        }
    }

    BackHandler(enabled = selectedProject != null && selectedDeployment == null) { closeProject() }
    BackHandler(enabled = selectedProject != null && selectedDeployment != null) {
        selectedDeploymentId = null
        vercelConnectionViewModel.closeDeployment()
    }

    CompositionLocalProvider(LocalVercelFaviconSource provides faviconSource) {
        when {
            selectedProject != null && selectedDeployment != null -> VercelDeploymentDetailScreen(
                project = analyticsState.projectDetails ?: selectedProject,
                deployment = selectedDeployment,
                state = deploymentState,
                onBack = {
                    selectedDeploymentId = null
                    vercelConnectionViewModel.closeDeployment()
                },
                onRefresh = vercelConnectionViewModel::refreshDeploymentEvents,
                onOpenUrl = openUrl,
                modifier = modifier,
            )

            selectedProject != null -> VercelAnalyticsScreen(
                project = selectedProject,
                state = analyticsState,
                onBack = ::closeProject,
                onRefresh = vercelConnectionViewModel::refreshProjectAnalytics,
                onRangeSelected = vercelConnectionViewModel::selectAnalyticsRange,
                onEnvironmentSelected = vercelConnectionViewModel::selectAnalyticsEnvironment,
                onOpenDeployment = { deployment ->
                    selectedDeploymentId = deployment.id
                    vercelConnectionViewModel.openDeployment(deployment)
                },
                onOpenUrl = openUrl,
                gridState = analyticsGridState,
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
                    closeProject()
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
                isBusy = state.isBusy,
                isRefreshing = state.isRefreshing,
                error = state.error,
                searchFocusRequester = searchFocusRequester,
                onRefresh = {
                    if (!state.isBusy) vercelConnectionViewModel.refresh()
                },
                onManageConnection = {
                    IntegrationCatalog.provider("vercel")?.let(onConnectProvider)
                },
                onDisconnect = {
                    closeProject()
                    vercelConnectionViewModel.disconnect()
                },
                onProjectSelected = { project ->
                    proAccess.requestPro {
                        selectedProjectId = project.id
                        vercelConnectionViewModel.openProjectAnalytics(project)
                    }
                },
                onProjectAction = { project, action ->
                    // Every long-press action is Pro, exactly like opening the project.
                    proAccess.requestPro {
                        when (action) {
                            is VercelProjectAction.OpenWebsite -> openUrl(action.url)
                            is VercelProjectAction.ViewOnVercel -> openUrl(action.url)
                            is VercelProjectAction.CopyUrl -> scope.launch {
                                runCatching {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Project URL", action.url)))
                                }
                            }
                            VercelProjectAction.ViewAnalytics -> {
                                selectedProjectId = project.id
                                vercelConnectionViewModel.openProjectAnalytics(project)
                            }
                        }
                    }
                },
                onOpenUrl = openUrl,
                connectedProviderIds = connectedProviderIds,
                onOpenHostingProvider = onConnectProvider,
                modifier = modifier,
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectedVercelWorkspace(
    dashboard: VercelDashboardUi,
    isBusy: Boolean,
    isRefreshing: Boolean,
    error: String?,
    searchFocusRequester: FocusRequester,
    onRefresh: () -> Unit,
    onManageConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onProjectSelected: (VercelProjectUi) -> Unit,
    onProjectAction: (VercelProjectUi, VercelProjectAction) -> Unit,
    onOpenUrl: (String) -> Unit,
    connectedProviderIds: Set<String>,
    onOpenHostingProvider: (IntegrationProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var accountMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    var pullRefreshing by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val visibleProjects = remember(dashboard.projects, query) { visibleVercelProjects(dashboard.projects, query) }
    val nowMillis = remember(dashboard) { System.currentTimeMillis() }
    LaunchedEffect(pullRefreshing, isRefreshing) {
        if (pullRefreshing && !isRefreshing) pullRefreshing = false
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
            enabled = !isBusy,
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
                        modifier = Modifier.size(48.dp),
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
                        // Connected hosting platforms first, then the ones still to connect.
                        IntegrationCatalog.providers(Workspace.HOSTING)
                            .filter { it.id != "vercel" }
                            .sortedBy { it.id !in connectedProviderIds }
                            .forEach { provider ->
                                DropdownMenuItem(
                                    modifier = Modifier.testTag(
                                        "workspace.hosting.connect${provider.id.replaceFirstChar(Char::uppercaseChar)}",
                                    ),
                                    text = {
                                        Text(
                                            if (provider.id in connectedProviderIds) {
                                                provider.displayName
                                            } else {
                                                "Connect ${provider.displayName}"
                                            },
                                        )
                                    },
                                    leadingIcon = { ProviderLogo(provider, Modifier.size(22.dp)) },
                                    onClick = {
                                        accountMenuExpanded = false
                                        onOpenHostingProvider(provider)
                                    },
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
                    enabled = !isBusy,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onRefresh()
                    },
                    testTag = "workspace.hosting.refresh",
                ) {
                    if (isBusy) {
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

        PullToRefreshBox(
            isRefreshing = pullRefreshing && isRefreshing,
            onRefresh = {
                if (!isBusy) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    pullRefreshing = true
                    onRefresh()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, top = 2.dp, end = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fullWidthItem("account-summary") {
                    ProviderSummaryCard(
                        provider = requireNotNull(IntegrationCatalog.provider("vercel")),
                        title = dashboard.account.displayName,
                        subtitle = dashboard.account.email ?: "Vercel account",
                        status = "Connected",
                        detail = "${dashboard.projects.size} projects",
                        testTag = "workspace.hosting.summary",
                    )
                }
                projectListContent(
                    dashboard = dashboard,
                    visibleProjects = visibleProjects,
                    query = query,
                    error = error,
                    nowMillis = nowMillis,
                    onRefresh = onRefresh,
                    onClearSearch = { query = "" },
                    onOpenUrl = onOpenUrl,
                    onProjectSelected = onProjectSelected,
                    onProjectAction = onProjectAction,
                )
            }
        }
    }
}

private fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

private fun LazyGridScope.projectListContent(
    dashboard: VercelDashboardUi,
    visibleProjects: List<VercelProjectUi>,
    query: String,
    error: String?,
    nowMillis: Long,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onProjectSelected: (VercelProjectUi) -> Unit,
    onProjectAction: (VercelProjectUi, VercelProjectAction) -> Unit,
) {
    if (dashboard.projects.isEmpty()) {
        val failure = error ?: dashboard.warning
        fullWidthItem("empty") {
            if (failure != null) {
                VercelEmptyState(
                    icon = Icons.Rounded.Warning,
                    title = "Couldn’t load data",
                    message = failure,
                    actionTitle = "Try again",
                    onAction = onRefresh,
                    testTag = "workspace.hosting.loadError",
                )
            } else {
                VercelEmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "No projects",
                    message = "Create a Vercel project, then refresh this screen.",
                    actionTitle = "Open Vercel",
                    onAction = { onOpenUrl(VERCEL_NEW_PROJECT_URL) },
                    testTag = "workspace.hosting.noProjects",
                )
            }
        }
        return
    }

    if (error != null) {
        fullWidthItem("error") {
            VercelFeedbackBanner(
                title = "Couldn’t refresh projects",
                message = error,
                tint = vercelWarningColor(),
                actionTitle = "Try again",
                onAction = onRefresh,
                testTag = "workspace.hosting.refreshError",
            )
        }
    }
    dashboard.warning?.let { warning ->
        fullWidthItem("partial-warning") {
            VercelFeedbackBanner(
                title = "Some project scopes did not load",
                message = warning,
                tint = vercelWarningColor(),
                testTag = "workspace.hosting.partialWarning",
            )
        }
    }

    if (visibleProjects.isEmpty()) {
        fullWidthItem("no-match") {
            VercelEmptyState(
                icon = Icons.Rounded.Search,
                title = "No matches",
                message = "Nothing in your projects matches “$query”.",
                actionTitle = "Clear search",
                onAction = onClearSearch,
                testTag = "workspace.hosting.noMatches",
            )
        }
        return
    }

    fullWidthItem("heading") { ProjectsHeading(visible = visibleProjects.size, total = dashboard.projects.size) }
    items(visibleProjects, key = { "project:${it.id}" }) { project ->
        VercelProjectCard(
            project = project,
            actions = remember(project, dashboard.account) { vercelProjectActions(project, dashboard.account) },
            onOpen = { onProjectSelected(project) },
            onAction = { action -> onProjectAction(project, action) },
            nowMillis = nowMillis,
        )
    }
}

@Composable
private fun ProjectsHeading(visible: Int, total: Int) {
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
                    "$visible shown · $total total",
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
                    "$visible/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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

/** Shimmering search bar and project-card placeholders while the saved account restores. */
@Composable
private fun HostingLoadingScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .testTag("workspace.hosting.loading")
            .semantics { contentDescription = "Loading Vercel projects" },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkeletonBlock(
            width = null,
            height = 48.dp,
            strong = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shimmer(),
        )
        repeat(6) { VercelSkeletonCard() }
    }
}

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

/** iOS prompts for a rating once projects first load without an error and the list is non-empty. */
internal fun shouldNotifyProjectsFirstLoaded(
    status: VercelConnectionStatus,
    projectCount: Int,
    error: String?,
): Boolean = status == VercelConnectionStatus.CONNECTED && projectCount > 0 && error == null
