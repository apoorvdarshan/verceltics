package com.apoorvdarshan.verceltics.ui.searchconsole

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.components.AppPullToRefresh
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountMenu
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountOptionUi
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountRemoval
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountRemovalDialog
import com.apoorvdarshan.verceltics.ui.sites.SiteAccountsUi
import com.apoorvdarshan.verceltics.ui.sites.SiteLayout
import com.apoorvdarshan.verceltics.ui.sites.SiteWidthClass
import com.apoorvdarshan.verceltics.ui.sites.adaptiveGridItems
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

internal val SearchConsoleAccent = Color(0xFFFF6B1A)
internal val SearchConsoleSuccess = Color(0xFF42C96B)
internal val SearchConsoleWarning = Color(0xFFFFD83D)
internal val SearchConsoleDanger = Color(0xFFE65353)

@Composable
fun SearchConsoleConnectionCard(
    state: SearchConsoleUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = remember { searchConsoleProvider() }
    val haptic = LocalHapticFeedback.current
    val dashboard = state.dashboard
    val cacheDescription = when (dashboard?.cacheState) {
        SearchConsoleCacheState.LIVE -> "live data"
        SearchConsoleCacheState.CACHED_FRESH -> "recent saved data"
        SearchConsoleCacheState.CACHED_STALE -> "stale saved data"
        null -> when (state.status) {
            SearchConsoleConnectionStatus.SAVED_UNAVAILABLE -> "saved connection needs attention"
            SearchConsoleConnectionStatus.RESTORING -> "checking saved connection"
            SearchConsoleConnectionStatus.DISCONNECTED -> "no saved connection"
            SearchConsoleConnectionStatus.CONNECTED -> "connection data unavailable"
        }
    }
    val status = searchConsoleConnectionCardStatus(state)
    val statusColor = when (status) {
        "Connected" -> SearchConsoleSuccess
        "Attention" -> SearchConsoleWarning
        else -> SearchConsoleAccent
    }
    val subtitle = dashboard?.let {
        "${it.loadedPropertyCount} propert${if (it.loadedPropertyCount == 1) "y" else "ies"} · $cacheDescription"
    } ?: state.savedAccount?.displayName ?: state.error ?: when (state.status) {
        SearchConsoleConnectionStatus.SAVED_UNAVAILABLE -> "Open to recover this connection"
        SearchConsoleConnectionStatus.RESTORING -> "Checking saved connection"
        SearchConsoleConnectionStatus.DISCONNECTED -> "Not connected"
        SearchConsoleConnectionStatus.CONNECTED -> "Open Search Console"
    }
    val stacked = shouldStackSearchConsoleConnectionCard(LocalDensity.current.fontScale)

    OffsetPanel(
        modifier = modifier
            .heightIn(min = 88.dp)
            .testTag("workspace.sites.searchConsoleConnection")
            .semantics { stateDescription = "$status, $cacheDescription" },
        color = MaterialTheme.colorScheme.surface,
        borderColor = SearchConsoleAccent.copy(alpha = 0.20f),
        shadowColor = SearchConsoleAccent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProviderMark(provider, size = 46.dp)
                    Spacer(Modifier.width(13.dp))
                    SearchConsoleConnectionCopy(
                        title = provider.displayName,
                        subtitle = subtitle,
                        subtitleMaxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    StatusPill(status, statusColor)
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderMark(provider, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                SearchConsoleConnectionCopy(
                    title = provider.displayName,
                    subtitle = subtitle,
                    subtitleMaxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(status, statusColor)
            }
        }
    }
}

@Composable
private fun SearchConsoleConnectionCopy(
    title: String,
    subtitle: String,
    subtitleMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = if (subtitleMaxLines > 1) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = subtitleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun searchConsoleConnectionCardStatus(state: SearchConsoleUiState): String =
    when (state.status) {
        SearchConsoleConnectionStatus.CONNECTED -> if (
            state.error != null ||
            state.dashboard == null ||
            state.dashboard.isPartial ||
            state.dashboard.warnings.isNotEmpty() ||
            state.dashboard.cacheState == SearchConsoleCacheState.CACHED_STALE
        ) {
            "Attention"
        } else {
            "Connected"
        }
        SearchConsoleConnectionStatus.SAVED_UNAVAILABLE -> "Attention"
        SearchConsoleConnectionStatus.RESTORING -> "Restoring"
        SearchConsoleConnectionStatus.DISCONNECTED -> "Disconnected"
    }

internal fun shouldStackSearchConsoleConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

/** On-page performance controls (iOS chips, menus and column headers). */
class SearchConsolePerformanceActions(
    val onSelectDatePreset: (SearchConsoleDatePresetUi) -> Unit = {},
    val onSelectSearchType: (SearchConsoleSearchTypeUi) -> Unit = {},
    val onSelectDimension: (SearchConsoleDimensionUi) -> Unit = {},
    val onToggleDimension: (SearchConsoleDimensionUi) -> Unit = {},
    val onSelectDataState: (SearchConsoleDataStateUi) -> Unit = {},
    val onSelectAggregation: (SearchConsoleAggregationUi) -> Unit = {},
    val onApplyFilters: (List<SearchConsoleFilterUi>) -> Unit = {},
    val onRemoveFilter: (Int) -> Unit = {},
    val onToggleSort: (SearchConsoleSortFieldUi) -> Unit = {},
)

@Composable
fun SearchConsoleRoute(
    viewModel: SearchConsoleViewModel,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val routeBack = {
        if (!viewModel.handleBack()) onBack()
    }
    DisposableEffect(viewModel) {
        viewModel.setRouteVisible(true)
        onDispose { viewModel.setRouteVisible(false) }
    }
    LaunchedEffect(searchRequestId) {
        viewModel.handleSearchRequest(searchRequestId)
    }
    // Property reports are Pro: close a property restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, state.selectedPropertyUrl) {
        if (proAccess.isConfirmedLocked && state.selectedPropertyUrl != null) viewModel.closeProperty()
    }
    BackHandler(onBack = routeBack)
    val performanceActions = remember(viewModel) {
        SearchConsolePerformanceActions(
            onSelectDatePreset = viewModel::selectDatePreset,
            onSelectSearchType = viewModel::selectSearchType,
            onSelectDimension = viewModel::selectBreakdownDimension,
            onToggleDimension = viewModel::toggleBreakdownDimension,
            onSelectDataState = viewModel::selectDataState,
            onSelectAggregation = viewModel::selectAggregation,
            onApplyFilters = viewModel::applyPerformanceFilters,
            onRemoveFilter = viewModel::removePerformanceFilter,
            onToggleSort = viewModel::toggleBreakdownSort,
        )
    }
    SearchConsoleScreen(
        state = state,
        onBack = routeBack,
        onConnect = viewModel::connect,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancelOperation,
        onSearchChange = viewModel::updatePropertySearch,
        onOpenProperty = { siteUrl -> proAccess.requestPro { viewModel.openProperty(siteUrl) } },
        onRequestPropertySwitcher = viewModel::requestPropertySwitcher,
        onDismissPropertySwitcher = viewModel::dismissPropertySwitcher,
        onPropertySearchFocused = viewModel::acknowledgePropertySearchFocus,
        onRefreshProperty = viewModel::refreshSelectedProperty,
        onSelectSection = viewModel::selectSection,
        onPerformanceQueryChange = viewModel::applyPerformanceQuery,
        onSelectPerformanceMetric = viewModel::selectPerformanceMetric,
        onPreviousPerformancePage = viewModel::previousPerformancePage,
        onNextPerformancePage = viewModel::nextPerformancePage,
        onInspectionUrlChange = viewModel::updateInspectionUrl,
        onInspect = viewModel::inspectUrl,
        onRequestDisconnect = viewModel::requestDisconnectConfirmation,
        onDismissDisconnect = viewModel::dismissDisconnectConfirmation,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        performanceActions = performanceActions,
        onRetryPropertySummaries = viewModel::retryPropertySummaries,
        onSwitchAccount = viewModel::switchAccount,
        onAddAccount = viewModel::startAddingAccount,
        onCancelAddAccount = viewModel::cancelAddingAccount,
        onRequestRemoveAll = viewModel::requestRemoveAllConfirmation,
        onDismissRemoveAll = viewModel::dismissRemoveAllConfirmation,
        onConfirmRemoveAll = viewModel::confirmRemoveAll,
        modifier = modifier,
    )
}

@Composable
fun SearchConsoleScreen(
    state: SearchConsoleUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenProperty: (String) -> Unit,
    onRequestPropertySwitcher: () -> Unit = {},
    onDismissPropertySwitcher: () -> Unit = {},
    onPropertySearchFocused: () -> Unit = {},
    onRefreshProperty: () -> Unit,
    onSelectSection: (SearchConsoleDetailSection) -> Unit,
    onPerformanceQueryChange: (SearchConsolePerformanceQueryUi) -> Unit = {},
    onSelectPerformanceMetric: (SearchConsoleMetricUi) -> Unit = {},
    onPreviousPerformancePage: () -> Unit = {},
    onNextPerformancePage: () -> Unit = {},
    onInspectionUrlChange: (String) -> Unit,
    onInspect: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    performanceActions: SearchConsolePerformanceActions = SearchConsolePerformanceActions(),
    onRetryPropertySummaries: () -> Unit = {},
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onCancelAddAccount: () -> Unit = {},
    onRequestRemoveAll: () -> Unit = {},
    onDismissRemoveAll: () -> Unit = {},
    onConfirmRemoveAll: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val propertySearchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(
        state.shouldFocusPropertySearch,
        state.showPropertySwitcher,
        state.dashboard,
    ) {
        if (state.shouldFocusPropertySearch && state.dashboard != null) {
            propertySearchFocusRequester.requestFocus()
            onPropertySearchFocused()
        }
    }
    if (state.showPropertySwitcher) {
        PropertySwitcherDialog(
            state = state,
            focusRequester = propertySearchFocusRequester,
            onSearchChange = onSearchChange,
            onOpenProperty = onOpenProperty,
            onDismiss = onDismissPropertySwitcher,
        )
    }
    if (state.showDisconnectConfirmation) {
        SiteAccountRemovalDialog(
            removal = SiteAccountRemoval.CURRENT,
            serviceName = "Google Search Console",
            accountTitle = state.accounts.active?.title ?: (state.dashboard?.account ?: state.savedAccount)?.displayName,
            credentialNoun = "Google credential",
            enabled = state.operation != SearchConsoleOperation.DISCONNECTING,
            onConfirm = onConfirmDisconnect,
            onDismiss = onDismissDisconnect,
            testTag = "searchConsole.disconnectDialog",
        )
    }
    if (state.showRemoveAllConfirmation) {
        SiteAccountRemovalDialog(
            removal = SiteAccountRemoval.ALL,
            serviceName = "Google Search Console",
            accountTitle = null,
            credentialNoun = "Google credential",
            enabled = state.operation != SearchConsoleOperation.DISCONNECTING,
            onConfirm = onConfirmRemoveAll,
            onDismiss = onDismissRemoveAll,
            testTag = "searchConsole.removeAllDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("searchConsole.screen"),
    ) {
        val showsAccountMenu = state.isConnected && !state.isAddingAccount && state.selectedPropertyUrl == null
        SearchConsoleTopBar(
            title = if (state.selectedPropertyUrl == null) "Search Console" else "Property details",
            operation = state.operation,
            isLoadingProperty = state.isLoadingProperty,
            canRefresh = state.isConnected && !state.isAddingAccount,
            accountMenu = if (showsAccountMenu) {
                {
                    SiteAccountMenu(
                        provider = searchConsoleProvider(),
                        accounts = state.accounts.takeIf { it.accounts.isNotEmpty() } ?: fallbackAccounts(state),
                        addLabel = "Add Google account",
                        onSwitch = onSwitchAccount,
                        onAdd = onAddAccount,
                        onRemoveCurrent = onRequestDisconnect,
                        onRemoveAll = onRequestRemoveAll,
                        testTagPrefix = "searchConsole",
                        enabled = state.operation == null || state.operation == SearchConsoleOperation.REFRESHING,
                    )
                }
            } else {
                null
            },
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBack()
            },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (state.selectedPropertyUrl == null) onRefresh() else onRefreshProperty()
            },
            onCancel = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCancel()
            },
        )

        when {
            state.status == SearchConsoleConnectionStatus.RESTORING -> LoadingState(
                "Opening saved Search Console workspace…",
                Modifier.weight(1f),
            )
            state.showsConnectPanel -> SearchConsoleConnectionPanel(
                readiness = state.oauthReadiness,
                isAuthorizing = state.operation == SearchConsoleOperation.AUTHORIZING,
                error = state.error,
                notice = state.notice,
                onConnect = onConnect,
                addingAccount = state.isAddingAccount,
                currentAccountName = state.accounts.active?.title ?: state.dashboard?.account?.displayName,
                onCancelAdd = onCancelAddAccount,
                modifier = Modifier.weight(1f),
            )
            state.selectedPropertyUrl != null -> AppPullToRefresh(
                isRefreshing = state.isLoadingProperty,
                onRefresh = onRefreshProperty,
                enabled = state.operation == null,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                testTag = "searchConsole.detail.pullToRefresh",
            ) {
                SearchConsolePropertyDetail(
                    state = state,
                    onRequestPropertySwitcher = onRequestPropertySwitcher,
                    onSelectSection = onSelectSection,
                    onPerformanceQueryChange = onPerformanceQueryChange,
                    onSelectPerformanceMetric = onSelectPerformanceMetric,
                    onPreviousPerformancePage = onPreviousPerformancePage,
                    onNextPerformancePage = onNextPerformancePage,
                    onInspectionUrlChange = onInspectionUrlChange,
                    onInspect = onInspect,
                    performanceActions = performanceActions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.dashboard != null -> AppPullToRefresh(
                isRefreshing = state.operation == SearchConsoleOperation.REFRESHING,
                onRefresh = onRefresh,
                enabled = state.operation == null || state.operation == SearchConsoleOperation.REFRESHING,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                testTag = "searchConsole.pullToRefresh",
            ) {
                SearchConsoleDashboard(
                    state = state,
                    onSearchChange = onSearchChange,
                    searchFocusRequester = propertySearchFocusRequester,
                    onOpenProperty = onOpenProperty,
                    onRequestDisconnect = onRequestDisconnect,
                    onRetrySummaries = onRetryPropertySummaries,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> AppPullToRefresh(
                isRefreshing = state.operation == SearchConsoleOperation.REFRESHING,
                onRefresh = onRefresh,
                enabled = state.operation == null || state.operation == SearchConsoleOperation.REFRESHING,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                testTag = "searchConsole.recovery.pullToRefresh",
            ) {
                SavedConnectionRecovery(
                    state = state,
                    onRefresh = onRefresh,
                    onRequestDisconnect = onRequestDisconnect,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SearchConsoleTopBar(
    title: String,
    operation: SearchConsoleOperation?,
    isLoadingProperty: Boolean,
    canRefresh: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    accountMenu: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            onClick = onBack,
            testTag = "searchConsole.back",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        accountMenu?.invoke()
        val isCancelable = operation == SearchConsoleOperation.AUTHORIZING ||
            operation == SearchConsoleOperation.REFRESHING
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = isCancelable || (canRefresh && operation == null && !isLoadingProperty),
            onClick = if (isCancelable) onCancel else onRefresh,
            testTag = "searchConsole.refreshOrCancel",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isCancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                    operation != null || isLoadingProperty -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    else -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Search Console")
                }
            }
        }
    }
}

@Composable
private fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchConsoleConnectionPanel(
    readiness: SearchConsoleOAuthReadinessUi,
    isAuthorizing: Boolean,
    error: String?,
    notice: String?,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    addingAccount: Boolean = false,
    currentAccountName: String? = null,
    onCancelAdd: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("searchConsole.connectionPanel"),
        contentPadding = SiteLayout.padding(maxWidth, 20.dp, SiteLayout.FormMaxWidth, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                ProviderMark(searchConsoleProvider(), size = 72.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (addingAccount) "Add a Google account" else "Connect Google Search Console",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    if (addingAccount) {
                        "Your current account stays connected. Signing in to a saved account refreshes it in place."
                    } else {
                        "Search performance, indexing, sitemaps, and URL inspection"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            OffsetPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 286.dp),
                color = MaterialTheme.colorScheme.surface,
                borderColor = MaterialTheme.colorScheme.outline,
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconTile(Icons.Rounded.Key, SearchConsoleAccent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                when (readiness) {
                                    SearchConsoleOAuthReadinessUi.Ready ->
                                        "Sign in with Google to grant read-only access. Tokens refresh securely and remain encrypted on this device."
                                    is SearchConsoleOAuthReadinessUi.ConfigurationNeeded -> readiness.message
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        CapabilityRow("Verified property discovery")
                        CapabilityRow("28-day search performance")
                        CapabilityRow("Sitemaps and URL inspection")
                    }
                    when (readiness) {
                        SearchConsoleOAuthReadinessUi.Ready -> ThemedActionButton(
                            text = if (isAuthorizing) "WAITING FOR GOOGLE…" else "CONTINUE WITH GOOGLE",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onConnect()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isAuthorizing,
                            isBusy = isAuthorizing,
                            testTag = "searchConsole.connect",
                        )
                        is SearchConsoleOAuthReadinessUi.ConfigurationNeeded -> Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("searchConsole.configurationNeeded"),
                            color = SearchConsoleWarning.copy(alpha = 0.22f)
                                .compositeOver(MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.PauseCircle, contentDescription = null)
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    "GOOGLE SIGN-IN UNAVAILABLE",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        error?.let { message ->
            item { FeedbackPanel("Connection failed", message, MaterialTheme.colorScheme.error) }
        }
        notice?.let { message ->
            item { FeedbackPanel("SEARCH STATUS", message, SearchConsoleWarning) }
        }
        if (addingAccount) {
            item {
                ThemedActionButton(
                    text = "BACK TO ${(currentAccountName ?: "SAVED ACCOUNT").uppercase()}",
                    onClick = onCancelAdd,
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "searchConsole.cancelAddAccount",
                )
            }
        }
    }
    }
}

/** Menu accounts when only a dashboard is known (fixtures and gateways without an index). */
private fun fallbackAccounts(state: SearchConsoleUiState): SiteAccountsUi {
    val account = state.dashboard?.account ?: state.savedAccount ?: return SiteAccountsUi.EMPTY
    return SiteAccountsUi(listOf(SiteAccountOptionUi(account.id, account.displayName)), account.id)
}

@Composable
private fun CapabilityRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = SearchConsoleAccent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Property overview. Phones keep one column; regular windows cap the content (iOS
 * `dashboardMaxWidth`) and grid the property cards, and expanded windows move the account summary
 * into a side pane next to the searchable property list.
 */
@Composable
private fun SearchConsoleDashboard(
    state: SearchConsoleUiState,
    onSearchChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    onOpenProperty: (String) -> Unit,
    onRequestDisconnect: () -> Unit,
    onRetrySummaries: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboard = checkNotNull(state.dashboard)
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compactPadding = 20.dp
        val spacing = 14.dp
        if (SiteWidthClass.of(maxWidth) == SiteWidthClass.EXPANDED) {
            val horizontal = SiteLayout.horizontalPadding(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth)
            val listWidth = SiteLayout.contentWidth(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth) -
                SiteLayout.SidePaneWidth - spacing
            val columns = SiteLayout.columns(maxWidth, listWidth, minimum = 310.dp, spacing = spacing, maximumColumns = 3)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontal),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(SiteLayout.SidePaneWidth)
                        .fillMaxHeight()
                        .testTag("searchConsole.dashboard.summaryPane"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    dashboardSummaryItems(state, dashboard, onRetrySummaries)
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("searchConsole.dashboard"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    dashboardPropertyItems(state, onSearchChange, searchFocusRequester, onOpenProperty, onRequestDisconnect, haptic, columns)
                }
            }
        } else {
            val contentWidth = SiteLayout.contentWidth(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth)
            val columns = SiteLayout.columns(maxWidth, contentWidth, minimum = 310.dp, spacing = spacing, maximumColumns = 3)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("searchConsole.dashboard"),
                contentPadding = SiteLayout.padding(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                dashboardSummaryItems(state, dashboard, onRetrySummaries)
                dashboardPropertyItems(state, onSearchChange, searchFocusRequester, onOpenProperty, onRequestDisconnect, haptic, columns)
            }
        }
    }
}

private fun LazyListScope.dashboardSummaryItems(
    state: SearchConsoleUiState,
    dashboard: SearchConsoleDashboardUi,
    onRetrySummaries: () -> Unit,
) {
    item {
        SearchConsoleAccountPanel(
            dashboard = dashboard,
            totals = state.overviewTotals,
            isLoadingSummaries = state.isLoadingPropertySummaries,
        )
    }
    state.notice?.let { message -> item { FeedbackPanel("Saved data", message, SearchConsoleWarning) } }
    state.error?.let { message -> item { FeedbackPanel("Refresh failed", message, MaterialTheme.colorScheme.error) } }
    state.propertySummaryError?.let { message ->
        item {
            FeedbackPanel("28-day overview incomplete", message, SearchConsoleWarning) {
                ThemedActionButton(
                    text = "RETRY OVERVIEW",
                    onClick = onRetrySummaries,
                    tone = ThemedActionTone.NEUTRAL,
                    enabled = !state.isLoadingPropertySummaries,
                    testTag = "searchConsole.overview.retry",
                )
            }
        }
    }
    if (dashboard.isPartial || dashboard.warnings.isNotEmpty()) {
        item {
            FeedbackPanel(
                title = "PARTIAL PROPERTY LIST",
                message = dashboard.warnings.firstOrNull()
                    ?: "The visible property list is intentionally bounded. Refresh online for current data.",
                color = SearchConsoleWarning,
            )
        }
    }
}

private fun LazyListScope.dashboardPropertyItems(
    state: SearchConsoleUiState,
    onSearchChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    onOpenProperty: (String) -> Unit,
    onRequestDisconnect: () -> Unit,
    haptic: HapticFeedback,
    columns: Int,
) {
    item {
        ControlSearchField(
            value = state.propertySearch,
            onValueChange = onSearchChange,
            placeholder = "Search properties",
            focusRequester = searchFocusRequester,
            testTag = "searchConsole.propertySearch",
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "VERIFIED PROPERTIES",
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                ),
            )
            LabelChip("${state.visibleProperties.size}")
        }
    }
    if (state.visibleProperties.isEmpty()) {
        item {
            EmptyPanel(
                if (state.propertySearch.isBlank()) {
                    "Google did not return any verified Search Console properties."
                } else {
                    "No properties match “${state.propertySearch}”."
                },
            )
        }
    } else {
        adaptiveGridItems(state.visibleProperties, columns, key = SearchConsolePropertyUi::siteUrl, spacing = 14.dp) { property ->
            PropertyRow(
                property = property,
                summary = state.propertySummaries[property.siteUrl],
                isLoadingSummary = state.isLoadingPropertySummaries,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onOpenProperty(property.siteUrl)
            }
        }
    }
    item {
        ThemedActionButton(
            text = if (state.accounts.hasMultiple) "REMOVE THIS GOOGLE ACCOUNT" else "DISCONNECT GOOGLE ACCOUNT",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                onRequestDisconnect()
            },
            modifier = Modifier.fillMaxWidth(),
            tone = ThemedActionTone.DESTRUCTIVE,
            testTag = "searchConsole.disconnect",
        )
    }
}

@Composable
private fun SearchConsoleAccountPanel(
    dashboard: SearchConsoleDashboardUi,
    totals: SearchConsoleOverviewTotalsUi?,
    isLoadingSummaries: Boolean,
) {
    val stale = dashboard.cacheState == SearchConsoleCacheState.CACHED_STALE
    ProviderSummaryCard(
        provider = searchConsoleProvider(),
        title = dashboard.account.displayName,
        subtitle = "${dashboard.loadedPropertyCount} verified ${if (dashboard.loadedPropertyCount == 1) "property" else "properties"}",
        status = if (stale) "Cached" else "Connected",
        statusColor = if (stale) SearchConsoleWarning else SearchConsoleSuccess,
        detail = "Updated ${formatTimestamp(dashboard.fetchedAtMillis)}",
    ) {
        Text(
            when (dashboard.cacheState) {
                SearchConsoleCacheState.LIVE -> "LIVE FROM GOOGLE"
                SearchConsoleCacheState.CACHED_FRESH -> "SAVED · RECENT"
                SearchConsoleCacheState.CACHED_STALE -> "SAVED · REFRESHING"
            },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
        OverviewTotals(totals, isLoadingSummaries, dashboard.cacheState)
    }
}

/** iOS service overview metrics: properties plus 28-day totals across every verified property. */
@Composable
private fun OverviewTotals(
    totals: SearchConsoleOverviewTotalsUi?,
    isLoading: Boolean,
    cacheState: SearchConsoleCacheState,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("searchConsole.overview.totals"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LAST 28 DAYS",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
            )
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SearchConsoleAccent)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${totals?.loadedProperties ?: 0}/${totals?.properties ?: "…"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (totals?.isPartial == true) {
                LabelChip("Partial")
            }
        }
        if (totals == null) {
            Text(
                when {
                    isLoading -> "Loading clicks, impressions, sitemaps and index status…"
                    cacheState == SearchConsoleCacheState.LIVE -> "No 28-day overview is available yet."
                    else -> "Overview metrics load with the next live refresh."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val stats = listOf(
                "Properties" to compactWholeNumber(totals.properties.toLong()),
                "Clicks" to compactNumber(totals.clicks),
                "Impressions" to compactNumber(totals.impressions),
                "CTR" to formatPercent(totals.ctr),
                "Avg position" to (totals.position?.let(::formatDecimal) ?: "—"),
                "Sitemaps" to compactWholeNumber(totals.sitemaps.toLong()),
            )
            stats.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) -> CompactStat(label, value, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun CompactStat(label: String, value: String, modifier: Modifier = Modifier, tint: Color = SearchConsoleAccent) {
    Surface(
        modifier = modifier.heightIn(min = 58.dp),
        shape = RoundedCornerShape(11.dp),
        color = tint.copy(alpha = 0.075f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, tint.copy(alpha = 0.25f)),
    ) {
        Column(
            Modifier
                .padding(horizontal = 9.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun PropertyRow(
    property: SearchConsolePropertyUi,
    summary: SearchConsolePropertySummaryUi?,
    isLoadingSummary: Boolean,
    onClick: () -> Unit,
) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        onClick = onClick,
        testTag = "searchConsole.property.${property.siteUrl}",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Language, SearchConsoleAccent)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        property.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        summary?.indexStatus ?: property.siteUrl,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabelChip(property.permission)
                        summary?.indexVerdict?.let { verdict ->
                            StatusPill(searchConsoleHumanized(verdict), searchConsoleVerdictColor(verdict))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open property")
            }
            when {
                summary != null -> Column(
                    Modifier.testTag("searchConsole.property.summary.${property.siteUrl}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactStat("Clicks", summary.clicks?.let(::compactNumber) ?: "—", Modifier.weight(1f))
                        CompactStat("Impr.", summary.impressions?.let(::compactNumber) ?: "—", Modifier.weight(1f))
                        CompactStat("CTR", summary.ctr?.let(::formatPercent) ?: "—", Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactStat("Position", summary.position?.let(::formatDecimal) ?: "—", Modifier.weight(1f))
                        CompactStat("Sitemaps", summary.sitemapCount?.toLong()?.let(::compactWholeNumber) ?: "—", Modifier.weight(1f))
                        CompactStat(
                            "Last crawl",
                            summary.lastCrawlTime?.let { formatSearchConsoleTimelineLabel(it) } ?: "—",
                            Modifier.weight(1f),
                        )
                    }
                    if (summary.isPartial) {
                        Text(
                            summary.warnings.firstOrNull() ?: "Some 28-day overview data could not load.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                isLoadingSummary -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SearchConsoleAccent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Loading 28-day overview…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PropertySwitcherDialog(
    state: SearchConsoleUiState,
    focusRequester: FocusRequester,
    onSearchChange: (String) -> Unit,
    onOpenProperty: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 620.dp)
                .heightIn(max = 680.dp)
                .testTag("searchConsole.propertySwitcher"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SWITCH PROPERTY", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Keep your current report mode while changing sites.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ThemedGlassControl(
                        modifier = Modifier.size(48.dp),
                        onClick = onDismiss,
                        testTag = "searchConsole.propertySwitcher.close",
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Cancel, contentDescription = "Close property switcher")
                        }
                    }
                }
                ControlSearchField(
                    value = state.propertySearch,
                    onValueChange = onSearchChange,
                    placeholder = "Search verified properties",
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "searchConsole.propertySwitcher.search",
                    focusRequester = focusRequester,
                )
                if (state.visibleProperties.isEmpty()) {
                    EmptyPanel(
                        if (state.propertySearch.isBlank()) {
                            "Google did not return any verified properties."
                        } else {
                            "No properties match “${state.propertySearch}”."
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.visibleProperties, key = SearchConsolePropertyUi::siteUrl) { property ->
                            PropertyRow(
                                property = property,
                                summary = state.propertySummaries[property.siteUrl],
                                isLoadingSummary = false,
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onOpenProperty(property.siteUrl)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedConnectionRecovery(
    state: SearchConsoleUiState,
    onRefresh: () -> Unit,
    onRequestDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = SiteLayout.padding(maxWidth, 20.dp, SiteLayout.FormMaxWidth, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            FeedbackPanel(
                title = "SAVED GOOGLE CONNECTION",
                message = state.error
                    ?: "The encrypted account is still saved, but no property list is available.",
                color = SearchConsoleWarning,
            )
        }
        state.notice?.let { notice ->
            item { FeedbackPanel("SEARCH STATUS", notice, SearchConsoleWarning) }
        }
        state.savedAccount?.let { account ->
            item {
                OffsetPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 94.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(searchConsoleProvider(), size = 54.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("Encrypted on this device", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            ThemedActionButton(
                text = "RETRY PROPERTY REFRESH",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onRefresh()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.operation == null,
                isBusy = state.operation == SearchConsoleOperation.REFRESHING,
                testTag = "searchConsole.retry",
            )
        }
        item {
            ThemedActionButton(
                text = if (state.accounts.hasMultiple) "REMOVE THIS GOOGLE ACCOUNT" else "DISCONNECT GOOGLE ACCOUNT",
                onClick = onRequestDisconnect,
                modifier = Modifier.fillMaxWidth(),
                tone = ThemedActionTone.DESTRUCTIVE,
                testTag = "searchConsole.recovery.disconnect",
            )
        }
    }
    }
}

// MARK: - Shared panels

@Composable
internal fun FeedbackPanel(
    title: String,
    message: String,
    color: Color,
    action: (@Composable () -> Unit)? = null,
) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        color = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = color,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (color == MaterialTheme.colorScheme.error || color == SearchConsoleDanger) {
                    Icons.Rounded.ErrorOutline
                } else {
                    Icons.Rounded.WarningAmber
                },
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                action?.let {
                    Spacer(Modifier.height(6.dp))
                    it()
                }
            }
        }
    }
}

@Composable
internal fun EmptyPanel(message: String, title: String? = null) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun LoadingPanel(message: String) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp),
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun IconTile(icon: ImageVector, tint: Color, size: Int = 46) {
    Surface(
        modifier = Modifier.size(size.dp),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.15f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.25.dp, tint),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size((size * 0.48f).dp))
        }
    }
}

/** A label/value row whose value can be selected and copied, like iOS `.textSelection(.enabled)`. */
@Composable
internal fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    val valueStyle = if (monospace) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    } else {
        MaterialTheme.typography.bodySmall
    }
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            SelectionContainer { Text(value, style = valueStyle) }
        }
    } else {
        Row {
            Text(
                label.uppercase(),
                modifier = Modifier.width(116.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            SelectionContainer(Modifier.weight(1f)) { Text(value, style = valueStyle) }
        }
    }
}

@Composable
internal fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.1.sp,
        ),
    )
}

// MARK: - Formatting

internal fun searchConsoleProvider() = checkNotNull(
    IntegrationCatalog.all.firstOrNull { it.id == "googleSearchConsole" },
) { "Google Search Console is missing from the integration catalog." }

internal fun compactNumber(value: Double): String {
    val absolute = kotlin.math.abs(value)
    return when {
        absolute >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        absolute >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> compactWholeNumber(value.roundToLong())
    }
}

internal fun compactWholeNumber(value: Long): String = String.format(Locale.US, "%,d", value)

internal fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

internal fun formatDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

internal fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** iOS `SearchConsoleFormatting.verdictTone`, mapped onto the provider palette. */
internal fun searchConsoleVerdictColor(value: String?): Color {
    val normalized = value?.lowercase(Locale.ROOT) ?: return SearchConsoleWarning
    return when {
        listOf("fail", "blocked", "not", "error").any(normalized::contains) -> SearchConsoleDanger
        listOf("pass", "allowed", "success", "indexed").any(normalized::contains) -> SearchConsoleSuccess
        else -> SearchConsoleWarning
    }
}

internal val SearchConsoleDetailSection.displayLabel: String
    get() = when (this) {
        SearchConsoleDetailSection.PERFORMANCE -> "Performance"
        SearchConsoleDetailSection.SITEMAPS -> "Sitemaps"
        SearchConsoleDetailSection.INSPECT -> "Inspect"
    }

internal val SearchConsoleDatePresetUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDatePresetUi.DAYS_7 -> "7 days"
        SearchConsoleDatePresetUi.DAYS_28 -> "28 days"
        SearchConsoleDatePresetUi.DAYS_90 -> "3 months"
        SearchConsoleDatePresetUi.DAYS_180 -> "6 months"
        SearchConsoleDatePresetUi.DAYS_365 -> "12 months"
        SearchConsoleDatePresetUi.DAYS_480 -> "16 months"
        SearchConsoleDatePresetUi.CUSTOM -> "Custom"
    }

internal val SearchConsoleSearchTypeUi.displayLabel: String
    get() = when (this) {
        SearchConsoleSearchTypeUi.WEB -> "Web"
        SearchConsoleSearchTypeUi.IMAGE -> "Image"
        SearchConsoleSearchTypeUi.VIDEO -> "Video"
        SearchConsoleSearchTypeUi.NEWS -> "News"
        SearchConsoleSearchTypeUi.DISCOVER -> "Discover"
        SearchConsoleSearchTypeUi.GOOGLE_NEWS -> "Google News"
    }

internal val SearchConsoleDataStateUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDataStateUi.FINAL -> "Final only"
        SearchConsoleDataStateUi.ALL -> "All available"
        SearchConsoleDataStateUi.HOURLY_ALL -> "Hourly"
    }

/** iOS `SearchConsoleDataState.explanation`. */
internal val SearchConsoleDataStateUi.explanation: String
    get() = when (this) {
        SearchConsoleDataStateUi.FINAL -> "Only fully processed Search Console data."
        SearchConsoleDataStateUi.ALL -> "Includes fresh rows that Google may still update."
        SearchConsoleDataStateUi.HOURLY_ALL -> "Fresh hourly rows, including incomplete data."
    }

internal val SearchConsoleAggregationUi.displayLabel: String
    get() = when (this) {
        SearchConsoleAggregationUi.AUTO -> "Automatic"
        SearchConsoleAggregationUi.BY_PAGE -> "By page"
        SearchConsoleAggregationUi.BY_PROPERTY -> "By property"
        SearchConsoleAggregationUi.BY_NEWS_SHOWCASE_PANEL -> "News Showcase panel"
    }

internal val SearchConsoleDimensionUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDimensionUi.DATE -> "Date"
        SearchConsoleDimensionUi.HOUR -> "Hour"
        SearchConsoleDimensionUi.QUERY -> "Query"
        SearchConsoleDimensionUi.PAGE -> "Page"
        SearchConsoleDimensionUi.COUNTRY -> "Country"
        SearchConsoleDimensionUi.DEVICE -> "Device"
        SearchConsoleDimensionUi.SEARCH_APPEARANCE -> "Search appearance"
    }

internal fun SearchConsoleDimensionUi.isFilterable(): Boolean =
    this != SearchConsoleDimensionUi.DATE && this != SearchConsoleDimensionUi.HOUR

internal val SearchConsoleFilterOperatorUi.displayLabel: String
    get() = when (this) {
        SearchConsoleFilterOperatorUi.CONTAINS -> "Contains"
        SearchConsoleFilterOperatorUi.EQUALS -> "Exactly matches"
        SearchConsoleFilterOperatorUi.NOT_CONTAINS -> "Does not contain"
        SearchConsoleFilterOperatorUi.NOT_EQUALS -> "Does not match"
        SearchConsoleFilterOperatorUi.INCLUDING_REGEX -> "Matches regex"
        SearchConsoleFilterOperatorUi.EXCLUDING_REGEX -> "Does not match regex"
    }

internal fun SearchConsoleSortFieldUi.displayLabel(dimensions: List<SearchConsoleDimensionUi>): String = when (this) {
    SearchConsoleSortFieldUi.DIMENSION -> dimensions.firstOrNull()?.displayLabel ?: "Dimension"
    SearchConsoleSortFieldUi.CLICKS -> "Clicks"
    SearchConsoleSortFieldUi.IMPRESSIONS -> "Impressions"
    SearchConsoleSortFieldUi.CTR -> "CTR"
    SearchConsoleSortFieldUi.POSITION -> "Position"
}

internal val SearchConsoleMetricUi.displayLabel: String
    get() = when (this) {
        SearchConsoleMetricUi.CLICKS -> "Clicks"
        SearchConsoleMetricUi.IMPRESSIONS -> "Impressions"
        SearchConsoleMetricUi.CTR -> "CTR"
        SearchConsoleMetricUi.POSITION -> "Average position"
    }

/** iOS `SearchConsoleMetricKind.color`. */
internal val SearchConsoleMetricUi.color: Color
    get() = when (this) {
        SearchConsoleMetricUi.CLICKS -> SearchConsoleAccent
        SearchConsoleMetricUi.IMPRESSIONS -> Color(0xFF8C63ED)
        SearchConsoleMetricUi.CTR -> Color(0xFF1AA18C)
        SearchConsoleMetricUi.POSITION -> Color(0xFFE8911F)
    }

internal fun SearchConsoleMetricUi.format(value: Double): String = when (this) {
    SearchConsoleMetricUi.CLICKS,
    SearchConsoleMetricUi.IMPRESSIONS,
    -> compactNumber(value)
    SearchConsoleMetricUi.CTR -> formatPercent(value)
    SearchConsoleMetricUi.POSITION -> formatDecimal(value)
}
