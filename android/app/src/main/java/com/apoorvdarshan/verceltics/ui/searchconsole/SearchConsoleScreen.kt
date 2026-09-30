package com.apoorvdarshan.verceltics.ui.searchconsole

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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

private val SearchConsoleAccent = Color(0xFFFF6B1A)
private val SearchConsoleSuccess = Color(0xFF42C96B)
private val SearchConsoleWarning = Color(0xFFFFD83D)

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

@Composable
fun SearchConsoleRoute(
    viewModel: SearchConsoleViewModel,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
    BackHandler(onBack = routeBack)
    SearchConsoleScreen(
        state = state,
        onBack = routeBack,
        onConnect = viewModel::connect,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancelOperation,
        onSearchChange = viewModel::updatePropertySearch,
        onOpenProperty = viewModel::openProperty,
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
        ThemedAlertDialog(
            title = "Disconnect Google Search Console?",
            message = "The encrypted Google credential and saved property list will be removed from this device.",
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            enabled = state.operation != SearchConsoleOperation.DISCONNECTING,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmDisconnect()
            },
            onDismissRequest = onDismissDisconnect,
            testTag = "searchConsole.disconnectDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("searchConsole.screen"),
    ) {
        SearchConsoleTopBar(
            title = if (state.selectedPropertyUrl == null) "Search Console" else "Property details",
            operation = state.operation,
            isLoadingProperty = state.isLoadingProperty,
            canRefresh = state.isConnected,
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
            state.status == SearchConsoleConnectionStatus.DISCONNECTED -> SearchConsoleConnectionPanel(
                readiness = state.oauthReadiness,
                isAuthorizing = state.operation == SearchConsoleOperation.AUTHORIZING,
                error = state.error,
                notice = state.notice,
                onConnect = onConnect,
                modifier = Modifier.weight(1f),
            )
            state.selectedPropertyUrl != null -> SearchConsolePropertyDetail(
                state = state,
                onRequestPropertySwitcher = onRequestPropertySwitcher,
                onSelectSection = onSelectSection,
                onPerformanceQueryChange = onPerformanceQueryChange,
                onSelectPerformanceMetric = onSelectPerformanceMetric,
                onPreviousPerformancePage = onPreviousPerformancePage,
                onNextPerformancePage = onNextPerformancePage,
                onInspectionUrlChange = onInspectionUrlChange,
                onInspect = onInspect,
                modifier = Modifier.weight(1f),
            )
            state.dashboard != null -> SearchConsoleDashboard(
                state = state,
                onSearchChange = onSearchChange,
                searchFocusRequester = propertySearchFocusRequester,
                onOpenProperty = onOpenProperty,
                onRequestDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
            else -> SavedConnectionRecovery(
                state = state,
                onRefresh = onRefresh,
                onRequestDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
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
) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                ProviderMark(searchConsoleProvider(), size = 72.dp)
                Spacer(Modifier.height(14.dp))
                Text("Connect Google Search Console", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Search performance, indexing, sitemaps, and URL inspection",
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
    }
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

@Composable
private fun SearchConsoleDashboard(
    state: SearchConsoleUiState,
    onSearchChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    onOpenProperty: (String) -> Unit,
    onRequestDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboard = checkNotNull(state.dashboard)
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SearchConsoleAccountPanel(dashboard)
        }
        state.notice?.let { message -> item { FeedbackPanel("Saved data", message, SearchConsoleWarning) } }
        state.error?.let { message -> item { FeedbackPanel("Refresh failed", message, MaterialTheme.colorScheme.error) } }
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
            items(state.visibleProperties, key = SearchConsolePropertyUi::siteUrl) { property ->
                PropertyRow(property) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onOpenProperty(property.siteUrl)
                }
            }
        }
        item {
            ThemedActionButton(
                text = "DISCONNECT GOOGLE ACCOUNT",
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
}

@Composable
private fun SearchConsoleAccountPanel(dashboard: SearchConsoleDashboardUi) {
    val stale = dashboard.cacheState == SearchConsoleCacheState.CACHED_STALE
    ProviderSummaryCard(
        provider = searchConsoleProvider(),
        title = dashboard.account.displayName,
        subtitle = "${dashboard.loadedPropertyCount} verified ${if (dashboard.loadedPropertyCount == 1) "property" else "properties"}",
        status = if (stale) "Cached" else "Connected",
        statusColor = if (stale) SearchConsoleWarning else SearchConsoleSuccess,
        detail = "Updated ${formatTimestamp(dashboard.fetchedAtMillis)}",
    )
}

@Composable
private fun SearchConsoleAccountCopy(
    dashboard: SearchConsoleDashboardUi,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    dashboard.account.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${dashboard.loadedPropertyCount} verified ${if (dashboard.loadedPropertyCount == 1) "property" else "properties"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when (dashboard.cacheState) {
                        SearchConsoleCacheState.LIVE -> "LIVE FROM GOOGLE"
                        SearchConsoleCacheState.CACHED_FRESH -> "SAVED · RECENT"
                        SearchConsoleCacheState.CACHED_STALE -> "SAVED · REFRESHING"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "Updated ${formatTimestamp(dashboard.fetchedAtMillis)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
    }
}

@Composable
private fun PropertyRow(property: SearchConsolePropertyUi, onClick: () -> Unit) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        onClick = onClick,
        testTag = "searchConsole.property.${property.siteUrl}",
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    property.siteUrl,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LabelChip(property.permission)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open property")
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
                            PropertyRow(property) {
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
private fun PerformanceControlsDialog(
    query: SearchConsolePerformanceQueryUi,
    onApply: (SearchConsolePerformanceQueryUi) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(query) { mutableStateOf(query) }
    var startDate by remember(query) { mutableStateOf(query.startDate) }
    var endDate by remember(query) { mutableStateOf(query.endDate) }
    var filterDimension by remember(query) { mutableStateOf(SearchConsoleDimensionUi.QUERY) }
    var filterOperator by remember(query) { mutableStateOf(SearchConsoleFilterOperatorUi.CONTAINS) }
    var filterExpression by remember(query) { mutableStateOf("") }
    var validationError by remember(query) { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 680.dp)
                .heightIn(max = 780.dp)
                .testTag("searchConsole.performanceControls"),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Tune, SearchConsoleAccent)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("BUILD PERFORMANCE REPORT", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Google Search Console · read only",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ThemedGlassControl(
                        modifier = Modifier.size(48.dp),
                        onClick = onDismiss,
                        testTag = "searchConsole.performanceControls.close",
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Cancel, contentDescription = "Close report controls")
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 6.dp),
                ) {
                    item {
                        ControlGroup("DATE RANGE") {
                            ChoiceGrid(
                                values = SearchConsoleDatePresetUi.entries,
                                selected = { it == draft.preset },
                                label = SearchConsoleDatePresetUi::displayLabel,
                                onSelect = { preset ->
                                    draft = if (preset == SearchConsoleDatePresetUi.CUSTOM) {
                                        draft.copy(preset = preset, page = 0)
                                    } else {
                                        draft.withPreset(preset)
                                    }
                                    startDate = draft.startDate
                                    endDate = draft.endDate
                                },
                            )
                            if (draft.preset == SearchConsoleDatePresetUi.CUSTOM) {
                                Spacer(Modifier.height(10.dp))
                                ThemedAuthTextField(
                                    value = startDate,
                                    onValueChange = { startDate = it.take(10); validationError = null },
                                    label = "Start date · YYYY-MM-DD",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                ThemedAuthTextField(
                                    value = endDate,
                                    onValueChange = { endDate = it.take(10); validationError = null },
                                    label = "End date · YYYY-MM-DD",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item {
                        ControlGroup("SEARCH TYPE") {
                            ChoiceGrid(
                                SearchConsoleSearchTypeUi.entries,
                                selected = { it == draft.searchType },
                                label = SearchConsoleSearchTypeUi::displayLabel,
                                onSelect = { draft = draft.copy(searchType = it, page = 0) },
                            )
                        }
                    }
                    item {
                        ControlGroup("DATA FRESHNESS") {
                            ChoiceGrid(
                                SearchConsoleDataStateUi.entries,
                                selected = { it == draft.dataState },
                                label = SearchConsoleDataStateUi::displayLabel,
                                onSelect = { draft = draft.copy(dataState = it, page = 0) },
                            )
                        }
                    }
                    item {
                        ControlGroup("AGGREGATION") {
                            ChoiceGrid(
                                SearchConsoleAggregationUi.entries,
                                selected = { it == draft.aggregation },
                                label = SearchConsoleAggregationUi::displayLabel,
                                onSelect = { draft = draft.copy(aggregation = it, page = 0) },
                                enabled = { aggregation ->
                                    aggregation != SearchConsoleAggregationUi.BY_PROPERTY ||
                                        SearchConsoleDimensionUi.PAGE !in draft.dimensions &&
                                        draft.filters.none { it.dimension == SearchConsoleDimensionUi.PAGE }
                                },
                            )
                        }
                    }
                    item {
                        ControlGroup("BREAKDOWN DIMENSIONS") {
                            ChoiceGrid(
                                SearchConsoleDimensionUi.entries,
                                selected = { it in draft.dimensions },
                                label = SearchConsoleDimensionUi::displayLabel,
                                onSelect = { dimension ->
                                    val updated = if (dimension in draft.dimensions) {
                                        draft.dimensions.minus(dimension).ifEmpty { draft.dimensions }
                                    } else {
                                        draft.dimensions + dimension
                                    }
                                    draft = draft.copy(
                                        dimensions = updated,
                                        aggregation = if (
                                            SearchConsoleDimensionUi.PAGE in updated &&
                                            draft.aggregation == SearchConsoleAggregationUi.BY_PROPERTY
                                        ) SearchConsoleAggregationUi.AUTO else draft.aggregation,
                                        page = 0,
                                    )
                                },
                            )
                        }
                    }
                    item {
                        ControlGroup("AND FILTERS") {
                            draft.filters.forEachIndexed { index, filter ->
                                FilterSummaryRow(filter) {
                                    draft = draft.copy(
                                        filters = draft.filters.filterIndexed { itemIndex, _ -> itemIndex != index },
                                        page = 0,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            ChoiceGrid(
                                SearchConsoleDimensionUi.entries.filter(SearchConsoleDimensionUi::isFilterable),
                                selected = { it == filterDimension },
                                label = SearchConsoleDimensionUi::displayLabel,
                                onSelect = { filterDimension = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            ChoiceGrid(
                                SearchConsoleFilterOperatorUi.entries,
                                selected = { it == filterOperator },
                                label = SearchConsoleFilterOperatorUi::displayLabel,
                                onSelect = { filterOperator = it },
                            )
                            Spacer(Modifier.height(8.dp))
                            ThemedAuthTextField(
                                value = filterExpression,
                                onValueChange = { filterExpression = it.take(4_096) },
                                label = "Filter expression",
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            ThemedActionButton(
                                text = "ADD FILTER",
                                onClick = {
                                    if (filterExpression.isNotBlank() && draft.filters.size < 32) {
                                        draft = draft.copy(
                                            filters = draft.filters + SearchConsoleFilterUi(
                                                filterDimension,
                                                filterOperator,
                                                filterExpression.trim(),
                                            ),
                                            aggregation = if (
                                                filterDimension == SearchConsoleDimensionUi.PAGE &&
                                                draft.aggregation == SearchConsoleAggregationUi.BY_PROPERTY
                                            ) SearchConsoleAggregationUi.AUTO else draft.aggregation,
                                            page = 0,
                                        )
                                        filterExpression = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = filterExpression.isNotBlank() && draft.filters.size < 32,
                                tone = ThemedActionTone.NEUTRAL,
                            )
                        }
                    }
                    item {
                        ControlGroup("SORT AND PAGE SIZE") {
                            ChoiceGrid(
                                SearchConsoleSortFieldUi.entries,
                                selected = { it == draft.sortField },
                                label = SearchConsoleSortFieldUi::displayLabel,
                                onSelect = { draft = draft.copy(sortField = it, page = 0) },
                            )
                            Spacer(Modifier.height(8.dp))
                            ChoiceGrid(
                                listOf(false, true),
                                selected = { it == draft.sortAscending },
                                label = { if (it) "Ascending" else "Descending" },
                                onSelect = { draft = draft.copy(sortAscending = it, page = 0) },
                            )
                            Spacer(Modifier.height(8.dp))
                            ChoiceGrid(
                                listOf(25, 50, 100),
                                selected = { it == draft.pageSize },
                                label = { "$it rows" },
                                onSelect = { draft = draft.copy(pageSize = it, page = 0) },
                            )
                        }
                    }
                    validationError?.let { message ->
                        item { FeedbackPanel("Check date range", message, MaterialTheme.colorScheme.error) }
                    }
                }
                ThemedActionButton(
                    text = "RUN REPORT",
                    onClick = {
                        val candidate = runCatching {
                            val start = LocalDate.parse(startDate.trim())
                            val end = LocalDate.parse(endDate.trim())
                            require(!start.isAfter(end))
                            draft.copy(
                                startDate = start.toString(),
                                endDate = end.toString(),
                                page = 0,
                            )
                        }.getOrNull()
                        if (candidate == null) {
                            validationError = "Use valid YYYY-MM-DD dates with the start on or before the end."
                        }
                        if (candidate != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onApply(candidate)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "searchConsole.performanceControls.apply",
                )
            }
        }
    }
}

@Composable
private fun ControlGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ChoiceGrid(
    values: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    val singleColumn = LocalDensity.current.fontScale >= 1.3f
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.chunked(if (singleColumn) 1 else 2).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowValues.forEach { value ->
                    val isSelected = selected(value)
                    val isEnabled = enabled(value)
                    Surface(
                        onClick = { onSelect(value) },
                        enabled = isEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 46.dp)
                            .semantics {
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                        shape = RoundedCornerShape(13.dp),
                        color = if (isSelected) SearchConsoleAccent else MaterialTheme.colorScheme.surface,
                        contentColor = when {
                            !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            isSelected -> Color.Black
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(label(value), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (!singleColumn && rowValues.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilterSummaryRow(filter: SearchConsoleFilterUi, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = SearchConsoleAccent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, SearchConsoleAccent),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${filter.dimension.displayLabel} ${filter.operator.displayLabel} “${filter.expression}”",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            Surface(onClick = onRemove, color = Color.Transparent) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Remove filter",
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun PropertyHeroContent(property: SearchConsolePropertyUi?, selectedPropertyUrl: String?, onSwitch: () -> Unit) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderMark(searchConsoleProvider(), size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(property?.displayName ?: selectedPropertyUrl.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(property?.permission ?: "Verified property", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Read-only Google data", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemedActionButton(text = "Switch property", onClick = onSwitch, tone = ThemedActionTone.NEUTRAL, testTag = "searchConsole.switchProperty")
        }
    }
}

@Composable
private fun SearchConsolePropertyDetail(
    state: SearchConsoleUiState,
    onRequestPropertySwitcher: () -> Unit,
    onSelectSection: (SearchConsoleDetailSection) -> Unit,
    onPerformanceQueryChange: (SearchConsolePerformanceQueryUi) -> Unit,
    onSelectPerformanceMetric: (SearchConsoleMetricUi) -> Unit,
    onPreviousPerformancePage: () -> Unit,
    onNextPerformancePage: () -> Unit,
    onInspectionUrlChange: (String) -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val property = state.dashboard?.properties?.firstOrNull {
        it.siteUrl == state.selectedPropertyUrl
    }
    val haptic = LocalHapticFeedback.current
    var showPerformanceControls by rememberSaveable { mutableStateOf(false) }
    if (showPerformanceControls) {
        PerformanceControlsDialog(
            query = state.performanceQuery,
            onApply = {
                showPerformanceControls = false
                onPerformanceQueryChange(it)
            },
            onDismiss = { showPerformanceControls = false },
        )
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OffsetPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp),
                color = SearchConsoleAccent.copy(alpha = 0.14f)
                    .compositeOver(MaterialTheme.colorScheme.surface),
                borderColor = MaterialTheme.colorScheme.outline,
            ) {
                PropertyHeroContent(property, state.selectedPropertyUrl, onRequestPropertySwitcher)
            }
        }
        item {
            DetailSectionPicker(
                selectedSection = state.selectedSection,
                onSelect = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSelectSection(it)
                },
            )
        }
        state.propertyError?.let { message ->
            item { FeedbackPanel("Property unavailable", message, MaterialTheme.colorScheme.error) }
        }
        if (state.isLoadingProperty && state.propertyWorkspace == null) {
            item {
                LoadingPanel("Loading ${state.selectedSection.displayLabel.lowercase()}…")
            }
        } else {
            when (state.selectedSection) {
                SearchConsoleDetailSection.PERFORMANCE -> performanceItems(
                    state = state,
                    onOpenControls = { showPerformanceControls = true },
                    onSelectMetric = onSelectPerformanceMetric,
                    onPreviousPage = onPreviousPerformancePage,
                    onNextPage = onNextPerformancePage,
                )
                SearchConsoleDetailSection.SITEMAPS -> sitemapItems(state.propertyWorkspace?.sitemaps)
                SearchConsoleDetailSection.INSPECT -> inspectionItems(
                    state = state,
                    onInspectionUrlChange = onInspectionUrlChange,
                    onInspect = onInspect,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.performanceItems(
    state: SearchConsoleUiState,
    onOpenControls: () -> Unit,
    onSelectMetric: (SearchConsoleMetricUi) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val resource = state.propertyWorkspace?.performance
    item {
        PerformanceQueryBar(
            query = state.performanceQuery,
            isLoading = state.isLoadingPerformance,
            onClick = onOpenControls,
        )
    }
    state.performanceError?.let { message ->
        item { FeedbackPanel("Performance refresh failed", message, MaterialTheme.colorScheme.error) }
    }
    when (resource) {
        null -> item { EmptyPanel("Performance has not loaded yet.") }
        is SearchConsoleResourceUi.Unavailable -> item {
            FeedbackPanel("Performance unavailable", resource.message, MaterialTheme.colorScheme.error)
        }
        is SearchConsoleResourceUi.Available -> {
            resource.warning?.let { warning ->
                item { FeedbackPanel("Partial performance", warning, SearchConsoleWarning) }
            }
            val performance = resource.value
            item {
                AdaptiveMetrics(performance, state.selectedPerformanceMetric, onSelectMetric)
            }
            if (performance.firstIncompleteDate != null || performance.firstIncompleteHour != null) {
                item {
                    FeedbackPanel(
                        "GOOGLE DATA IS STILL PROCESSING",
                        "Results from ${performance.firstIncompleteHour ?: performance.firstIncompleteDate} onward may change.",
                        SearchConsoleWarning,
                    )
                }
            }
            item {
                Text(
                    "${state.performanceQuery.preset.displayLabel.uppercase()} · " +
                        state.selectedPerformanceMetric.displayLabel.uppercase(),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.1.sp,
                    ),
                )
            }
            if (performance.timeline.isEmpty()) {
                item { EmptyPanel("No Search Analytics rows were returned for this period.") }
            } else {
                items(performance.timeline, key = SearchConsoleTimelinePointUi::label) { point ->
                    TimelineRow(
                        point = point,
                        metric = state.selectedPerformanceMetric,
                        maximum = performance.timeline.maxOfOrNull {
                            it.metricValue(state.selectedPerformanceMetric)
                        } ?: 0.0,
                    )
                }
            }
            item {
                Text(
                    state.performanceQuery.dimensions.joinToString(" + ") { it.displayLabel.uppercase() },
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.1.sp,
                    ),
                )
            }
            if (performance.breakdownRows.isEmpty()) {
                item { EmptyPanel("No breakdown rows match these dimensions and filters.") }
            } else {
                items(performance.breakdownRows, key = { row -> row.keys.joinToString("\u0000") }) { row ->
                    BreakdownRow(row, state.performanceQuery.dimensions)
                }
                item {
                    PerformancePagination(
                        page = state.performanceQuery.page,
                        pageSize = state.performanceQuery.pageSize,
                        loadedRows = performance.loadedBreakdownRowCount,
                        hasPrevious = performance.hasPreviousPage,
                        hasNext = performance.hasNextPage,
                        isLoading = state.isLoadingPerformance,
                        onPrevious = onPreviousPage,
                        onNext = onNextPage,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sitemapItems(
    resource: SearchConsoleResourceUi<List<SearchConsoleSitemapUi>>?,
) {
    when (resource) {
        null -> item { EmptyPanel("Sitemaps have not loaded yet.") }
        is SearchConsoleResourceUi.Unavailable -> item {
            FeedbackPanel("Sitemaps unavailable", resource.message, MaterialTheme.colorScheme.error)
        }
        is SearchConsoleResourceUi.Available -> {
            resource.warning?.let { warning ->
                item { FeedbackPanel("Partial sitemaps", warning, SearchConsoleWarning) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SUBMITTED SITEMAPS",
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.1.sp,
                        ),
                    )
                    LabelChip("${resource.value.size}")
                }
            }
            if (resource.value.isNotEmpty()) {
                item { SitemapAggregateSummary(resource.value) }
            }
            if (resource.value.isEmpty()) {
                item { EmptyPanel("No submitted sitemaps were returned for this property.") }
            } else {
                items(resource.value, key = SearchConsoleSitemapUi::path) { sitemap ->
                    SitemapCard(sitemap)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inspectionItems(
    state: SearchConsoleUiState,
    onInspectionUrlChange: (String) -> Unit,
    onInspect: () -> Unit,
) {
    item {
        OffsetPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 178.dp),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("INSPECT A URL", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Enter a URL that belongs to this property. This performs Google’s read-only index inspection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                ThemedAuthTextField(
                    value = state.inspectionUrl,
                    onValueChange = onInspectionUrlChange,
                    label = "Fully-qualified URL",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("searchConsole.inspectionUrl"),
                    enabled = !state.isInspecting,
                )
                ThemedActionButton(
                    text = if (state.isInspecting) "INSPECTING…" else "RUN URL INSPECTION",
                    onClick = onInspect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.inspectionUrl.isNotBlank() && !state.isInspecting,
                    isBusy = state.isInspecting,
                    testTag = "searchConsole.inspect",
                )
            }
        }
    }
    state.inspectionError?.let { message ->
        item { FeedbackPanel("Inspection failed", message, MaterialTheme.colorScheme.error) }
    }
    state.inspection?.let { inspection ->
        item {
            InspectionSummaryCard(inspection)
        }
        item {
            InspectionAreaCard(
                title = "AMP",
                verdict = inspection.ampVerdict,
                issues = inspection.issues.filter { it.area == SearchConsoleInspectionAreaUi.AMP },
            )
        }
        item {
            InspectionAreaCard(
                title = "MOBILE USABILITY",
                verdict = inspection.mobileVerdict,
                issues = inspection.issues.filter { it.area == SearchConsoleInspectionAreaUi.MOBILE },
            )
        }
        item {
            InspectionAreaCard(
                title = "RICH RESULTS",
                verdict = inspection.richResultsVerdict,
                issues = inspection.issues.filter { it.area == SearchConsoleInspectionAreaUi.RICH_RESULTS },
            )
        }
        inspection.inspectionResultLink?.let { link ->
            item { InspectionResultLink(link) }
        }
    }
}

@Composable
private fun DetailSectionPicker(
    selectedSection: SearchConsoleDetailSection,
    onSelect: (SearchConsoleDetailSection) -> Unit,
) {
    if (LocalDensity.current.fontScale >= 1.35f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchConsoleDetailSection.entries.forEach { section ->
                DetailSectionTab(section, section == selectedSection, onSelect, Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchConsoleDetailSection.entries.forEach { section ->
                DetailSectionTab(section, section == selectedSection, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DetailSectionTab(
    section: SearchConsoleDetailSection,
    selected: Boolean,
    onSelect: (SearchConsoleDetailSection) -> Unit,
    modifier: Modifier,
) {
    Surface(
        onClick = { onSelect(section) },
        modifier = modifier
            .heightIn(min = 54.dp)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .testTag("searchConsole.section.${section.name.lowercase()}"),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(section.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(section.displayLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PerformanceQueryBar(
    query: SearchConsolePerformanceQueryUi,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp),
        color = SearchConsoleAccent.copy(alpha = 0.12f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = SearchConsoleAccent.copy(alpha = 0.20f),
        shadowColor = SearchConsoleAccent,
        onClick = onClick,
        testTag = "searchConsole.performance.query",
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Tune, SearchConsoleAccent)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${query.preset.displayLabel} · ${query.searchType.displayLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${query.startDate} → ${query.endDate} · ${query.dimensions.joinToString(" + ") { it.displayLabel }}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (query.filters.isNotEmpty()) {
                    Text(
                        "${query.filters.size} AND filter${if (query.filters.size == 1) "" else "s"}",
                        color = SearchConsoleAccent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("EDIT", color = SearchConsoleAccent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AdaptiveMetrics(
    performance: SearchConsolePerformanceUi,
    selectedMetric: SearchConsoleMetricUi,
    onSelectMetric: (SearchConsoleMetricUi) -> Unit,
) {
    val useSingleColumn = LocalDensity.current.fontScale >= 1.35f
    if (useSingleColumn) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("CLICKS", compactNumber(performance.clicks), Modifier.fillMaxWidth(), selectedMetric == SearchConsoleMetricUi.CLICKS) { onSelectMetric(SearchConsoleMetricUi.CLICKS) }
            MetricCard("IMPRESSIONS", compactNumber(performance.impressions), Modifier.fillMaxWidth(), selectedMetric == SearchConsoleMetricUi.IMPRESSIONS) { onSelectMetric(SearchConsoleMetricUi.IMPRESSIONS) }
            MetricCard("CTR", formatPercent(performance.ctr), Modifier.fillMaxWidth(), selectedMetric == SearchConsoleMetricUi.CTR) { onSelectMetric(SearchConsoleMetricUi.CTR) }
            MetricCard("AVERAGE POSITION", formatDecimal(performance.position), Modifier.fillMaxWidth(), selectedMetric == SearchConsoleMetricUi.POSITION) { onSelectMetric(SearchConsoleMetricUi.POSITION) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("CLICKS", compactNumber(performance.clicks), Modifier.weight(1f), selectedMetric == SearchConsoleMetricUi.CLICKS) { onSelectMetric(SearchConsoleMetricUi.CLICKS) }
                MetricCard("IMPRESSIONS", compactNumber(performance.impressions), Modifier.weight(1f), selectedMetric == SearchConsoleMetricUi.IMPRESSIONS) { onSelectMetric(SearchConsoleMetricUi.IMPRESSIONS) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("CTR", formatPercent(performance.ctr), Modifier.weight(1f), selectedMetric == SearchConsoleMetricUi.CTR) { onSelectMetric(SearchConsoleMetricUi.CTR) }
                MetricCard("AVERAGE POSITION", formatDecimal(performance.position), Modifier.weight(1f), selectedMetric == SearchConsoleMetricUi.POSITION) { onSelectMetric(SearchConsoleMetricUi.POSITION) }
            }
        }
    }
}

@Composable
private fun BreakdownRow(
    row: SearchConsoleBreakdownRowUi,
    dimensions: List<SearchConsoleDimensionUi>,
) {
    OffsetPanel(modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            dimensions.forEachIndexed { index, dimension ->
                LabeledValue(dimension.displayLabel, row.keys.getOrNull(index) ?: "—")
            }
            Text(
                "${compactNumber(row.clicks)} clicks · ${compactNumber(row.impressions)} impressions · " +
                    "${formatPercent(row.ctr)} CTR · ${formatDecimal(row.position)} position",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PerformancePagination(
    page: Int,
    pageSize: Int,
    loadedRows: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    OffsetPanel(modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemedGlassControl(
                modifier = Modifier.size(48.dp),
                enabled = hasPrevious && !isLoading,
                onClick = onPrevious,
                testTag = "searchConsole.performance.previousPage",
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous report page")
                }
            }
            Text(
                "Page ${page + 1} · $pageSize rows · $loadedRows loaded (100,000 maximum per report)",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            ThemedGlassControl(
                modifier = Modifier.size(48.dp),
                enabled = hasNext && !isLoading,
                onClick = onNext,
                testTag = "searchConsole.performance.nextPage",
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next report page")
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    OffsetPanel(
        modifier = modifier.heightIn(min = 108.dp),
        color = (if (selected) SearchConsoleAccent else MaterialTheme.colorScheme.primary)
            .copy(alpha = if (selected) 0.28f else 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = if (selected) SearchConsoleAccent else MaterialTheme.colorScheme.outline,
        shadowColor = if (selected) SearchConsoleAccent else MaterialTheme.colorScheme.outline,
        onClick = onClick,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .semantics {
                    stateDescription = "$label, $value${if (selected) ", selected chart metric" else ""}"
                    if (onClick != null) this.selected = selected
                },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun TimelineRow(
    point: SearchConsoleTimelinePointUi,
    metric: SearchConsoleMetricUi,
    maximum: Double,
) {
    val value = point.metricValue(metric)
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(point.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${metric.format(value)} · ${compactNumber(point.impressions)} impressions",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LinearProgressIndicator(
                progress = {
                    if (maximum <= 0.0) 0f else (value / maximum).toFloat().coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun SitemapAggregateSummary(sitemaps: List<SearchConsoleSitemapUi>) {
    val submitted = sitemaps.sumOf { sitemap -> sitemap.contents.sumOf(SearchConsoleSitemapContentUi::submitted) }
    val indexed = sitemaps.sumOf { sitemap -> sitemap.contents.sumOf { it.indexed ?: 0L } }
    val issues = sitemaps.sumOf { it.errors + it.warnings }
    val singleColumn = LocalDensity.current.fontScale >= 1.35f
    if (singleColumn) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            MetricCard("SITEMAPS", compactWholeNumber(sitemaps.size.toLong()), Modifier.fillMaxWidth())
            MetricCard("SUBMITTED URLS", compactWholeNumber(submitted), Modifier.fillMaxWidth())
            MetricCard("INDEXED URLS", compactWholeNumber(indexed), Modifier.fillMaxWidth())
            MetricCard("ISSUES", compactWholeNumber(issues), Modifier.fillMaxWidth())
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("SITEMAPS", compactWholeNumber(sitemaps.size.toLong()), Modifier.weight(1f))
                MetricCard("SUBMITTED", compactWholeNumber(submitted), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                MetricCard("INDEXED", compactWholeNumber(indexed), Modifier.weight(1f))
                MetricCard("ISSUES", compactWholeNumber(issues), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SitemapCard(sitemap: SearchConsoleSitemapUi) {
    var expanded by rememberSaveable(sitemap.path) { mutableStateOf(false) }
    val totalSubmitted = sitemap.contents.sumOf(SearchConsoleSitemapContentUi::submitted)
    val totalIndexed = sitemap.contents.sumOf { it.indexed ?: 0L }
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 128.dp),
        color = if (sitemap.errors > 0) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface)
        } else {
            MaterialTheme.colorScheme.surface
        },
        onClick = { expanded = !expanded },
        testTag = "searchConsole.sitemap.${sitemap.path}",
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.AutoMirrored.Rounded.Rule, SearchConsoleAccent, size = 42)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        sitemap.path,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sitemap.lastSubmitted?.let { "Submitted $it" } ?: "Submission time not reported",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusPill(
                    if (sitemap.isPending) "PENDING" else if (sitemap.errors > 0) "ERROR" else "READY",
                    if (sitemap.isPending) SearchConsoleWarning else if (sitemap.errors > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        SearchConsoleSuccess
                    },
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse sitemap" else "Expand sitemap",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelChip("${sitemap.errors} ERRORS")
                LabelChip("${sitemap.warnings} WARNINGS")
                if (sitemap.isIndex) LabelChip("INDEX")
            }
            Text(
                "${compactWholeNumber(totalIndexed)} indexed / ${compactWholeNumber(totalSubmitted)} submitted",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                LabeledValue("Type", sitemap.type?.let(::humanize) ?: "Not reported")
                LabeledValue("Submitted", sitemap.lastSubmitted ?: "Not reported")
                LabeledValue("Downloaded", sitemap.lastDownloaded ?: "Not reported")
                sitemap.contents.forEach { content ->
                    Surface(
                        color = SearchConsoleAccent.copy(alpha = 0.08f)
                            .compositeOver(MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(humanize(content.type), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${content.indexed?.let(::compactWholeNumber) ?: "—"} indexed · " +
                                    "${compactWholeNumber(content.submitted)} submitted",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else if (sitemap.contents.isNotEmpty()) {
                Text(
                    "${sitemap.contents.size} content type${if (sitemap.contents.size == 1) "" else "s"} · tap for details",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InspectionSummaryCard(inspection: SearchConsoleInspectionUi) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 250.dp),
        color = SearchConsoleAccent.copy(alpha = 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.AutoMirrored.Rounded.FactCheck, SearchConsoleAccent)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("INDEX STATUS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        humanize(inspection.coverageState ?: "Not reported"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StatusPill(
                    humanize(inspection.verdict ?: "UNKNOWN"),
                    verdictColor(inspection.verdict),
                )
            }
            LabeledValue("Indexing", humanize(inspection.indexingState ?: "Not reported"))
            LabeledValue("Robots.txt", humanize(inspection.robotsTxtState ?: "Not reported"))
            LabeledValue("Page fetch", humanize(inspection.pageFetchState ?: "Not reported"))
            LabeledValue("Crawled as", humanize(inspection.crawledAs ?: "Not reported"))
            LabeledValue("Last crawl", inspection.lastCrawlTime ?: "Not reported")
            LabeledValue("Google canonical", inspection.googleCanonical ?: "Not reported")
            LabeledValue("User canonical", inspection.userCanonical ?: "Not reported")
            LabeledValue(
                "Sitemaps",
                inspection.sitemaps.joinToString("\n").ifBlank { "None reported" },
            )
            LabeledValue(
                "Referrers",
                inspection.referringUrls.joinToString("\n").ifBlank { "None reported" },
            )
        }
    }
}

@Composable
private fun InspectionAreaCard(
    title: String,
    verdict: String?,
    issues: List<SearchConsoleInspectionIssueUi>,
) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
        color = verdictColor(verdict).copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = verdictColor(verdict),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StatusPill(humanize(verdict ?: "Not reported"), verdictColor(verdict))
            }
            if (issues.isEmpty()) {
                Text(
                    "Google reported no ${title.lowercase()} issues.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                issues.forEach { issue ->
                    Surface(
                        color = SearchConsoleWarning.copy(alpha = 0.12f)
                            .compositeOver(MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SearchConsoleWarning),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(issue.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOfNotNull(issue.severity, issue.detail)
                                    .joinToString(" · ")
                                    .ifBlank { "Google reported an issue." },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionResultLink(link: String) {
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    ThemedActionButton(
        text = "OPEN THIS RESULT IN SEARCH CONSOLE",
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            uriHandler.openUri(link)
        },
        modifier = Modifier.fillMaxWidth(),
        tone = ThemedActionTone.NEUTRAL,
        testTag = "searchConsole.inspection.openResult",
    )
}

@Composable
private fun LabeledValue(label: String, value: String) {
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label.uppercase(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    } else Row {
        Text(
            label.uppercase(),
            modifier = Modifier.width(116.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
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
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
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
                text = "DISCONNECT GOOGLE ACCOUNT",
                onClick = onRequestDisconnect,
                modifier = Modifier.fillMaxWidth(),
                tone = ThemedActionTone.DESTRUCTIVE,
            )
        }
    }
}

@Composable
private fun FeedbackPanel(title: String, message: String, color: Color) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp),
        color = color.copy(alpha = 0.14f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = color,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (color == MaterialTheme.colorScheme.error) Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EmptyPanel(message: String) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp),
    ) {
        Box(Modifier.padding(18.dp), contentAlignment = Alignment.Center) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoadingPanel(message: String) {
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
private fun IconTile(icon: ImageVector, tint: Color, size: Int = 46) {
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

private val SearchConsoleDetailSection.displayLabel: String
    get() = when (this) {
        SearchConsoleDetailSection.PERFORMANCE -> "Performance"
        SearchConsoleDetailSection.SITEMAPS -> "Sitemaps"
        SearchConsoleDetailSection.INSPECT -> "Inspect"
    }

private val SearchConsoleDetailSection.icon: ImageVector
    get() = when (this) {
        SearchConsoleDetailSection.PERFORMANCE -> Icons.Rounded.Analytics
        SearchConsoleDetailSection.SITEMAPS -> Icons.AutoMirrored.Rounded.Rule
        SearchConsoleDetailSection.INSPECT -> Icons.AutoMirrored.Rounded.FactCheck
    }

private fun searchConsoleProvider() = checkNotNull(
    IntegrationCatalog.all.firstOrNull { it.id == "googleSearchConsole" },
) { "Google Search Console is missing from the integration catalog." }

private fun compactNumber(value: Double): String {
    val absolute = kotlin.math.abs(value)
    return when {
        absolute >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        absolute >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> compactWholeNumber(value.roundToLong())
    }
}

private fun compactWholeNumber(value: Long): String = String.format(Locale.US, "%,d", value)

private fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

private fun humanize(value: String): String = value
    .replace('_', ' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .lowercase()
    .replaceFirstChar { it.uppercase() }

private fun verdictColor(verdict: String?): Color = when (verdict?.uppercase()) {
    "PASS", "VALID" -> SearchConsoleSuccess
    "NEUTRAL", "UNKNOWN", null -> SearchConsoleWarning
    else -> Color(0xFFE65353)
}

private val SearchConsoleDatePresetUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDatePresetUi.DAYS_7 -> "7 days"
        SearchConsoleDatePresetUi.DAYS_28 -> "28 days"
        SearchConsoleDatePresetUi.DAYS_90 -> "3 months"
        SearchConsoleDatePresetUi.DAYS_180 -> "6 months"
        SearchConsoleDatePresetUi.DAYS_365 -> "12 months"
        SearchConsoleDatePresetUi.DAYS_480 -> "16 months"
        SearchConsoleDatePresetUi.CUSTOM -> "Custom"
    }

private fun SearchConsolePerformanceQueryUi.withPreset(
    preset: SearchConsoleDatePresetUi,
    today: LocalDate = LocalDate.now(),
): SearchConsolePerformanceQueryUi {
    require(preset != SearchConsoleDatePresetUi.CUSTOM)
    val end = today.minusDays(1)
    return copy(
        preset = preset,
        startDate = end.minusDays(preset.days - 1).toString(),
        endDate = end.toString(),
        page = 0,
    )
}

private val SearchConsoleSearchTypeUi.displayLabel: String
    get() = when (this) {
        SearchConsoleSearchTypeUi.WEB -> "Web"
        SearchConsoleSearchTypeUi.IMAGE -> "Image"
        SearchConsoleSearchTypeUi.VIDEO -> "Video"
        SearchConsoleSearchTypeUi.NEWS -> "News"
        SearchConsoleSearchTypeUi.DISCOVER -> "Discover"
        SearchConsoleSearchTypeUi.GOOGLE_NEWS -> "Google News"
    }

private val SearchConsoleDataStateUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDataStateUi.FINAL -> "Final"
        SearchConsoleDataStateUi.ALL -> "All data"
        SearchConsoleDataStateUi.HOURLY_ALL -> "Hourly · all"
    }

private val SearchConsoleAggregationUi.displayLabel: String
    get() = when (this) {
        SearchConsoleAggregationUi.AUTO -> "Automatic"
        SearchConsoleAggregationUi.BY_PAGE -> "By page"
        SearchConsoleAggregationUi.BY_PROPERTY -> "By property"
    }

private val SearchConsoleDimensionUi.displayLabel: String
    get() = when (this) {
        SearchConsoleDimensionUi.DATE -> "Date"
        SearchConsoleDimensionUi.HOUR -> "Hour"
        SearchConsoleDimensionUi.QUERY -> "Query"
        SearchConsoleDimensionUi.PAGE -> "Page"
        SearchConsoleDimensionUi.COUNTRY -> "Country"
        SearchConsoleDimensionUi.DEVICE -> "Device"
        SearchConsoleDimensionUi.SEARCH_APPEARANCE -> "Search appearance"
    }

private fun SearchConsoleDimensionUi.isFilterable(): Boolean =
    this != SearchConsoleDimensionUi.DATE && this != SearchConsoleDimensionUi.HOUR

private val SearchConsoleFilterOperatorUi.displayLabel: String
    get() = when (this) {
        SearchConsoleFilterOperatorUi.CONTAINS -> "Contains"
        SearchConsoleFilterOperatorUi.EQUALS -> "Equals"
        SearchConsoleFilterOperatorUi.NOT_CONTAINS -> "Does not contain"
        SearchConsoleFilterOperatorUi.NOT_EQUALS -> "Does not equal"
        SearchConsoleFilterOperatorUi.INCLUDING_REGEX -> "Matches regex"
        SearchConsoleFilterOperatorUi.EXCLUDING_REGEX -> "Excludes regex"
    }

private val SearchConsoleSortFieldUi.displayLabel: String
    get() = when (this) {
        SearchConsoleSortFieldUi.CLICKS -> "Clicks"
        SearchConsoleSortFieldUi.IMPRESSIONS -> "Impressions"
        SearchConsoleSortFieldUi.CTR -> "CTR"
        SearchConsoleSortFieldUi.POSITION -> "Position"
    }

private val SearchConsoleMetricUi.displayLabel: String
    get() = when (this) {
        SearchConsoleMetricUi.CLICKS -> "Clicks"
        SearchConsoleMetricUi.IMPRESSIONS -> "Impressions"
        SearchConsoleMetricUi.CTR -> "CTR"
        SearchConsoleMetricUi.POSITION -> "Average position"
    }

private fun SearchConsoleTimelinePointUi.metricValue(metric: SearchConsoleMetricUi): Double = when (metric) {
    SearchConsoleMetricUi.CLICKS -> clicks
    SearchConsoleMetricUi.IMPRESSIONS -> impressions
    SearchConsoleMetricUi.CTR -> ctr
    SearchConsoleMetricUi.POSITION -> position
}

private fun SearchConsoleMetricUi.format(value: Double): String = when (this) {
    SearchConsoleMetricUi.CLICKS,
    SearchConsoleMetricUi.IMPRESSIONS,
    -> compactNumber(value)
    SearchConsoleMetricUi.CTR -> formatPercent(value)
    SearchConsoleMetricUi.POSITION -> formatDecimal(value)
}
