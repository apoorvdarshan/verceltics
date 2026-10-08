package com.apoorvdarshan.verceltics.ui.netlify

import androidx.compose.ui.text.style.TextAlign
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess

private val NetlifyAccent = Color(0xFF2ED1C7)
private val NetlifyWarning = Color(0xFFFFD83D)

@Composable
fun NetlifyRoute(
    viewModel: NetlifyViewModel,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    var lastHandledSearchRequestId by rememberSaveable { mutableIntStateOf(searchRequestId) }
    var siteSearchFocusRequestId by rememberSaveable { mutableIntStateOf(0) }
    val routeBack = {
        if (!viewModel.handleBack()) onBack()
    }
    // Site details are Pro: close a site restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, state.selectedSiteId) {
        if (proAccess.isConfirmedLocked && state.selectedSiteId != null) viewModel.closeSite()
    }
    DisposableEffect(viewModel) {
        viewModel.setRouteVisible(true)
        onDispose { viewModel.setRouteVisible(false) }
    }
    BackHandler(onBack = routeBack)
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            if (state.selectedSiteId != null) {
                viewModel.closeSite()
                withFrameNanos { }
            }
            siteSearchFocusRequestId += 1
        }
    }
    NetlifyScreen(
        state = state,
        onBack = routeBack,
        onConnect = viewModel::connect,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancelOperation,
        onOpenSite = { siteId -> proAccess.requestPro { viewModel.openSite(siteId) } },
        onRefreshSite = viewModel::refreshSelectedSite,
        onRequestDisconnect = viewModel::requestDisconnectConfirmation,
        onDismissDisconnect = viewModel::dismissDisconnectConfirmation,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        searchFocusRequestId = siteSearchFocusRequestId,
        modifier = modifier,
    )
}

@Composable
fun NetlifyScreen(
    state: NetlifyUiState,
    onBack: () -> Unit,
    onConnect: (SecretValue) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onOpenSite: (String) -> Unit,
    onRefreshSite: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    searchFocusRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    if (state.showDisconnectConfirmation) {
        ThemedAlertDialog(
            title = "Disconnect Netlify?",
            message = "The encrypted personal token and saved Netlify inventory will be removed from this device.",
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            enabled = state.operation != NetlifyOperation.DISCONNECTING,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmDisconnect()
            },
            onDismissRequest = onDismissDisconnect,
            testTag = "netlify.disconnectDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("netlify.screen"),
    ) {
        NetlifyTopBar(
            title = if (state.selectedSiteId == null) "Netlify" else "Site details",
            operation = state.operation,
            isLoadingSite = state.isLoadingSite,
            canRefresh = state.isConnected,
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBack()
            },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (state.selectedSiteId == null) onRefresh() else onRefreshSite()
            },
            onCancel = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCancel()
            },
        )

        when {
            state.status == NetlifyConnectionStatus.RESTORING -> LoadingState(
                "Opening saved Netlify workspace…",
                Modifier.weight(1f),
            )
            state.status == NetlifyConnectionStatus.DISCONNECTED -> NetlifyConnectionForm(
                state = state,
                onConnect = onConnect,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            state.status == NetlifyConnectionStatus.SAVED_UNAVAILABLE -> SavedConnectionRecovery(
                state = state,
                onRefresh = onRefresh,
                onDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
            state.selectedSiteId != null -> NetlifySiteDetail(
                state = state,
                modifier = Modifier.weight(1f),
            )
            else -> NetlifyDashboard(
                state = state,
                onOpenSite = onOpenSite,
                onDisconnect = onRequestDisconnect,
                searchFocusRequestId = searchFocusRequestId,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun NetlifyConnectionCard(
    state: NetlifyUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = remember { checkNotNull(IntegrationCatalog.provider("netlify")) }
    val haptic = LocalHapticFeedback.current
    val subtitle = state.dashboard?.let { dashboard ->
        "${dashboard.loadedSiteCount} loaded site${if (dashboard.loadedSiteCount == 1) "" else "s"} · ${cacheLabel(dashboard.cacheState)} data"
    } ?: state.savedAccount?.email ?: state.error ?: "Saved connection"
    val status = when (state.status) {
        NetlifyConnectionStatus.CONNECTED -> if (
            state.error != null || state.dashboard?.isPartial == true ||
            state.dashboard?.warnings?.isNotEmpty() == true
        ) "Attention" else "Connected"
        NetlifyConnectionStatus.SAVED_UNAVAILABLE -> "Attention"
        NetlifyConnectionStatus.RESTORING -> "Restoring"
        NetlifyConnectionStatus.DISCONNECTED -> "Disconnected"
    }
    val statusColor = if (status == "Attention") NetlifyWarning else NetlifyAccent
    val stacked = shouldStackNetlifyConnectionCard(LocalDensity.current.fontScale)
    OffsetPanel(
        modifier = modifier.heightIn(min = 88.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = NetlifyAccent.copy(alpha = 0.20f),
        shadowColor = NetlifyAccent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.hosting.netlifyConnection",
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
                    ProviderMark(provider = provider, size = 46.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            provider.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            subtitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    StatusPill(status, statusColor)
                }
            }
        } else Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMark(provider = provider, size = 46.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(status, statusColor)
        }
    }
}

internal fun shouldStackNetlifyConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

@Composable
private fun NetlifyTopBar(
    title: String,
    operation: NetlifyOperation?,
    isLoadingSite: Boolean,
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
            testTag = "netlify.back",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val isCancelable = operation == NetlifyOperation.CONNECTING ||
            operation == NetlifyOperation.REFRESHING
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = isCancelable || (canRefresh && operation == null && !isLoadingSite),
            onClick = if (isCancelable) onCancel else onRefresh,
            testTag = "netlify.refreshOrCancel",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isCancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                    operation != null || isLoadingSite -> CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    else -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Netlify")
                }
            }
        }
    }
}

@Composable
private fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = NetlifyAccent)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetlifyConnectionForm(
    state: NetlifyUiState,
    onConnect: (SecretValue) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { EphemeralTokenController() }
    var hasToken by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    DisposableEffect(Unit) { onDispose(controller::clear) }
    LaunchedEffect(state.status) {
        if (state.status == NetlifyConnectionStatus.CONNECTED) {
            controller.clear()
            hasToken = false
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("netlify.connectionForm"),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("intro") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                borderColor = MaterialTheme.colorScheme.outline,
                shadowColor = MaterialTheme.colorScheme.outline,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(
                            provider = checkNotNull(IntegrationCatalog.provider("netlify")),
                            size = 56.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NETLIFY", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                            Text(
                                "Sites, deployments, and builds",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    Text(
                        "Your token is encrypted and stored only on this device. You can view deployments and builds; changes are unavailable.",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item("token") {
            OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("CONNECT NETLIFY", style = MaterialTheme.typography.headlineSmall)
                    EphemeralTokenInput(
                        controller = controller,
                        onPresenceChange = {
                            hasToken = it
                            localError = null
                        },
                        onDone = {
                            controller.consume()?.let(onConnect)
                                ?: run { localError = "Enter a Netlify personal access token." }
                        },
                    )
                    (localError ?: state.error)?.let { FeedbackPanel(it, true) }
                    state.notice?.let { FeedbackPanel(it, false) }
                    if (state.operation == NetlifyOperation.CONNECTING) {
                        ThemedActionButton(
                            "CANCEL REQUEST",
                            onClick = onCancel,
                            tone = ThemedActionTone.NEUTRAL,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "netlify.cancel",
                        )
                    } else {
                        ThemedActionButton(
                            "CONNECT SECURELY",
                            enabled = hasToken,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                controller.consume()?.let {
                                    hasToken = false
                                    onConnect(it)
                                } ?: run { localError = "Enter a Netlify personal access token." }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "netlify.connect",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedConnectionRecovery(
    state: NetlifyUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("netlify.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusPill("Saved securely", NetlifyAccent)
                    Text(
                        state.savedAccount?.displayName ?: "Saved Netlify account",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "The encrypted connection remains on this device. Retry online or remove it explicitly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let { FeedbackPanel(it, true) }
                    state.notice?.let { FeedbackPanel(it, false) }
                    ThemedActionButton(
                        if (state.operation == NetlifyOperation.REFRESHING) "REFRESHING…" else "REFRESH",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onRefresh()
                        },
                        enabled = !state.isBusy,
                        isBusy = state.operation == NetlifyOperation.REFRESHING,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "netlify.recovery.refresh",
                    )
                    ThemedActionButton(
                        "DISCONNECT",
                        onClick = onDisconnect,
                        enabled = !state.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "netlify.disconnect",
                    )
                }
            }
        }
    }
}

@Composable
private fun NetlifyDashboard(
    state: NetlifyUiState,
    onOpenSite: (String) -> Unit,
    onDisconnect: () -> Unit,
    searchFocusRequestId: Int,
    modifier: Modifier = Modifier,
) {
    val dashboard = requireNotNull(state.dashboard)
    val haptic = LocalHapticFeedback.current
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var lastHandledSearchFocusRequestId by rememberSaveable { mutableIntStateOf(0) }
    val visibleSites = remember(dashboard.sites, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) dashboard.sites else dashboard.sites.filter { site ->
            site.name.contains(normalized, ignoreCase = true) ||
                site.subtitle?.contains(normalized, ignoreCase = true) == true ||
                site.url?.contains(normalized, ignoreCase = true) == true ||
                site.status?.contains(normalized, ignoreCase = true) == true
        }
    }

    LaunchedEffect(searchFocusRequestId) {
        if (searchFocusRequestId > 0 && searchFocusRequestId != lastHandledSearchFocusRequestId) {
            lastHandledSearchFocusRequestId = searchFocusRequestId
            searchFocusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("netlify.dashboard"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            val attention = state.error != null || dashboard.isPartial || dashboard.warnings.isNotEmpty()
            ProviderSummaryCard(
                provider = requireNotNull(IntegrationCatalog.provider("netlify")),
                title = dashboard.account.displayName,
                subtitle = dashboard.account.email ?: "Netlify account",
                status = if (attention) "Attention" else "Connected",
                statusColor = if (attention) NetlifyWarning else MaterialTheme.colorScheme.tertiary,
                detail = "${cacheLabel(dashboard.cacheState)} data · read-only",
                testTag = "netlify.summary",
            )
        }
        state.error?.let { item("error") { FeedbackPanel(it, true) } }
        state.notice?.let { item("notice") { FeedbackPanel(it, false) } }
        if (dashboard.isPartial || dashboard.warnings.isNotEmpty()) {
            item("inventory-warning") {
                WarningPanel(inventoryDisclosure(dashboard))
            }
        }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search Netlify sites",
                modifier = Modifier.fillMaxWidth(),
                testTag = "netlify.search",
                focusRequester = searchFocusRequester,
                onSearch = { keyboard?.hide() },
            )
        }
        item("heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sites", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (dashboard.inventoryTruncatedForDisplay) {
                        "${dashboard.sites.size} of ${dashboard.loadedSiteCount}"
                    } else {
                        dashboard.sites.size.toString()
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (visibleSites.isEmpty()) {
            item("empty") {
                Text(
                    if (query.isBlank()) "Netlify returned no sites for this account." else "No Netlify sites match “$query”.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(visibleSites, key = NetlifySiteUi::id) { site ->
                NetlifySiteRow(site) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onOpenSite(site.id)
                }
            }
        }
        item("disconnect") {
            ThemedActionButton(
                "DISCONNECT NETLIFY",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDisconnect()
                },
                tone = ThemedActionTone.DESTRUCTIVE,
                modifier = Modifier.fillMaxWidth(),
                testTag = "netlify.disconnect",
            )
        }
    }
}

internal fun shouldStackNetlifySummary(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 340f || fontScale >= 1.3f

@Composable
private fun NetlifySiteRow(site: NetlifySiteUi, onClick: () -> Unit) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = NetlifyAccent.copy(alpha = 0.20f),
        shadowColor = MaterialTheme.colorScheme.outline,
        shadowOffset = 3.dp,
        onClick = onClick,
        testTag = "netlify.site.${site.id}",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(43.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black,
                contentColor = NetlifyAccent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CloudQueue, contentDescription = null)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    site.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(site.subtitle, site.status).joinToString(" · ").ifBlank { "Netlify site" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Open ${site.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetlifySiteDetail(state: NetlifyUiState, modifier: Modifier = Modifier) {
    val selected = state.dashboard?.sites?.firstOrNull { it.id == state.selectedSiteId }
    val workspace = state.selectedSiteWorkspace
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("netlify.siteDetail"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("site-summary") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                borderColor = MaterialTheme.colorScheme.outline,
                shadowColor = MaterialTheme.colorScheme.outline,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("NETLIFY SITE", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                    Text(
                        selected?.name ?: state.selectedSiteId.orEmpty(),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    selected?.url?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        state.siteError?.let { item("site-error") { FeedbackPanel(it, true) } }
        if (state.isLoadingSite && workspace == null) {
            item("loading") { LoadingState("Loading read-only site resources…", Modifier.heightIn(min = 180.dp)) }
        }
        workspace?.let { loaded ->
            item("details-heading") { SectionHeading("Domains & build controls") }
            when (val details = loaded.details) {
                is NetlifyResourceUi.Available -> {
                    if (details.value.domains.isEmpty()) {
                        item("domains-empty") { EmptyResource("No domains were returned for this site.") }
                    } else {
                        items(details.value.domains, key = { "domain-${it.kind}-${it.name}" }) { domain ->
                            KeyValuePanel(domain.kind.replace('_', ' '), domain.name)
                        }
                    }
                    item("build-controls") {
                        BuildControlsPanel(details.value.buildControls)
                    }
                    details.value.publishedDeployment?.let { deployment ->
                        item("published-deployment-heading") { SectionHeading("Published deployment") }
                        item("published-deployment") {
                            HistoryPanel(
                                title = deployment.title,
                                status = deployment.status,
                                detail = listOfNotNull(
                                    deployment.branch,
                                    deployment.commitMessage,
                                    deployment.url,
                                ).joinToString(" · "),
                                timeMillis = deployment.createdAtMillis,
                                testTag = "netlify.publishedDeployment.${deployment.id}",
                            )
                        }
                    }
                }
                is NetlifyResourceUi.Unavailable -> item("details-unavailable") {
                    FeedbackPanel(details.message, true)
                }
            }
            item("deploy-heading") {
                CollectionHeading("Deployments", loaded.deployments)
            }
            loaded.deployments.warning?.let { item("deploy-warning") { WarningPanel(it) } }
            if (loaded.deployments.items.isEmpty()) {
                item("deploy-empty") { EmptyResource("No deployments are available.") }
            } else {
                items(loaded.deployments.items, key = { "deploy-${it.id}" }) { deployment ->
                    HistoryPanel(
                        title = deployment.title,
                        status = deployment.status,
                        detail = listOfNotNull(deployment.branch, deployment.commitMessage).joinToString(" · "),
                        timeMillis = deployment.createdAtMillis,
                        testTag = "netlify.deploy.${deployment.id}",
                    )
                }
            }
            item("build-heading") { CollectionHeading("Builds", loaded.builds) }
            loaded.builds.warning?.let { item("build-warning") { WarningPanel(it) } }
            if (loaded.builds.items.isEmpty()) {
                item("build-empty") { EmptyResource("No builds are available.") }
            } else {
                items(loaded.builds.items, key = { "build-${it.id}" }) { build ->
                    HistoryPanel(
                        title = build.commitSha?.take(10) ?: "Build ${build.id.take(10)}",
                        status = when (build.isDone) {
                            true -> if (build.error == null) "Complete" else "Failed"
                            false -> "Running"
                            null -> "Unknown"
                        },
                        detail = build.error ?: build.deploymentId?.let { "Deploy ${it.take(10)}" }.orEmpty(),
                        timeMillis = build.createdAtMillis,
                        testTag = "netlify.build.${build.id}",
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun <T> CollectionHeading(title: String, collection: NetlifyCollectionUi<T>) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (collection.truncatedForDisplay) {
                "${collection.items.size} of ${collection.loadedItemCount}"
            } else {
                collection.items.size.toString()
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun BuildControlsPanel(controls: NetlifyBuildControlsUi?) {
    val pairs = controls?.let {
        listOfNotNull(
            "Builds" to when (it.buildsStopped) { true -> "Stopped"; false -> "Enabled"; null -> "Unknown" },
            it.provider?.let { value -> "Provider" to value },
            it.repositoryUrl?.let { value -> "Repository" to value },
            it.repositoryPath?.let { value -> "Repository path" to value },
            it.repositoryBranch?.let { value -> "Branch" to value },
            it.allowedBranches.takeIf { values -> values.isNotEmpty() }
                ?.let { values -> "Allowed branches" to values.joinToString(", ") },
            it.baseDirectory?.let { value -> "Base" to value },
            it.publishDirectory?.let { value -> "Publish" to value },
            it.functionsDirectory?.let { value -> "Functions" to value },
            it.buildCommand?.let { value -> "Command" to value },
        )
    }.orEmpty()
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("BUILD CONTROLS · READ ONLY", style = MaterialTheme.typography.labelSmall)
            if (pairs.isEmpty()) {
                Text("No build configuration was returned.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                pairs.forEach { (label, value) ->
                    Text(
                        "$label  $value",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyValuePanel(label: String, value: String) {
    OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HistoryPanel(
    title: String,
    status: String,
    detail: String,
    timeMillis: Long?,
    testTag: String,
) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowOffset = 2.dp,
        testTag = testTag,
    ) {
        BoxWithConstraints {
            val stacked = maxWidth < 350.dp || LocalDensity.current.fontScale >= 1.3f
            if (stacked) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryCopy(title, detail, timeMillis, Modifier.fillMaxWidth())
                    StatusPill(status, statusColor(status))
                }
            } else {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                    HistoryCopy(title, detail, timeMillis, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    StatusPill(status, statusColor(status))
                }
            }
        }
    }
}

@Composable
private fun HistoryCopy(title: String, detail: String, timeMillis: Long?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (detail.isNotBlank()) {
            Text(
                detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        timeMillis?.let {
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MetricTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 78.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.88f),
        contentColor = Color.Black,
        border = BorderStroke(1.5.dp, Color.Black),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(11.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FeedbackPanel(message: String, isError: Boolean) {
    val accent = if (isError) MaterialTheme.colorScheme.error else NetlifyAccent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface))
            .semantics {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                contentDescription = message
            }
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = accent,
        )
        Spacer(Modifier.width(9.dp))
        Text(message, Modifier.weight(1f))
    }
}

@Composable
private fun WarningPanel(message: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = NetlifyWarning,
        borderColor = MaterialTheme.colorScheme.outline,
        shadowColor = MaterialTheme.colorScheme.outline,
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(9.dp))
            Text(message, Modifier.weight(1f), color = Color.Black)
        }
    }
}

@Composable
private fun EmptyResource(message: String) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EphemeralTokenInput(
    controller: EphemeralTokenController,
    onPresenceChange: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "PERSONAL ACCESS TOKEN",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
            ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            color = colors.primary.copy(alpha = 0.07f).compositeOver(colors.surface),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(if (LocalVercelticsDarkTheme.current) 1.dp else 2.dp, colors.outline),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = NetlifyAccent)
                Spacer(Modifier.width(10.dp))
                AndroidView(
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            setSingleLine(true)
                            setSaveEnabled(false)
                            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            textSize = 16f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            setPadding(0, 0, 0, 0)
                            hint = "Enter Netlify token"
                            contentDescription = "Netlify personal access token"
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    onPresenceChange(!s.isNullOrBlank())
                                }
                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    onDone()
                                    true
                                } else {
                                    false
                                }
                            }
                            controller.attach(this)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 54.dp)
                        .testTag("netlify.token")
                        .semantics {
                            contentDescription = "Netlify personal access token"
                            password()
                            // AndroidView does not automatically publish Compose focus/edit
                            // actions. Delegate them to the native password field while exposing
                            // an intentionally empty EditableText so the secret never enters the
                            // Compose semantics/state tree.
                            this[SemanticsProperties.EditableText] = AnnotatedString("")
                            this[SemanticsActions.RequestFocus] = AccessibilityAction(
                                label = "Focus Netlify personal access token",
                                action = controller::requestFocus,
                            )
                            this[SemanticsActions.SetText] = AccessibilityAction(
                                label = "Enter Netlify personal access token",
                                action = { value -> controller.replace(value.text) },
                            )
                            this[SemanticsActions.InsertTextAtCursor] = AccessibilityAction(
                                label = "Type Netlify personal access token",
                                action = { value -> controller.insert(value.text) },
                            )
                        },
                    update = { field ->
                        field.setTextColor(colors.onSurface.toArgb())
                        field.setHintTextColor(colors.onSurfaceVariant.toArgb())
                    },
                    onRelease = { field ->
                        controller.detach(field)
                        field.text?.clear()
                    },
                )
            }
        }
    }
}

private class EphemeralTokenController {
    private var field = WeakReference<EditText>(null)

    fun attach(editText: EditText) {
        field = WeakReference(editText)
    }

    fun detach(editText: EditText) {
        if (field.get() === editText) field.clear()
    }

    fun consume(): SecretValue? {
        val editable = field.get()?.text ?: return null
        val value = editable.toString().trim()
        editable.clear()
        return runCatching { SecretValue.of(value) }.getOrNull()
    }

    fun clear() {
        field.get()?.text?.clear()
    }

    fun requestFocus(): Boolean {
        val editText = field.get() ?: return false
        val focused = editText.requestFocus()
        if (focused) {
            editText.post {
                editText.context.getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(editText, 0)
            }
        }
        return focused
    }

    fun replace(value: String): Boolean {
        val editText = field.get() ?: return false
        editText.setText(value)
        editText.setSelection(editText.text?.length ?: 0)
        return true
    }

    fun insert(value: String): Boolean {
        val editText = field.get() ?: return false
        val editable = editText.text ?: return false
        val selection = editText.selectionStart.coerceIn(0, editable.length)
        editable.insert(selection, value)
        editText.setSelection((selection + value.length).coerceAtMost(editable.length))
        return true
    }

    override fun toString(): String = "EphemeralTokenController(value=<redacted>)"
}

private fun cacheLabel(cacheState: NetlifyCacheState): String = when (cacheState) {
    NetlifyCacheState.LIVE -> "Live"
    NetlifyCacheState.CACHED_FRESH -> "Saved"
    NetlifyCacheState.CACHED_STALE -> "Stale"
}

private fun inventoryDisclosure(dashboard: NetlifyDashboardUi): String = buildList {
    addAll(dashboard.warnings)
    if (dashboard.inventoryTruncatedForDisplay) {
        add("Showing ${dashboard.sites.size} of ${dashboard.loadedSiteCount} loaded sites. The inventory is bounded for this screen.")
    }
    if (!dashboard.providerInventoryComplete && dashboard.warnings.isEmpty()) {
        add("Netlify returned a partial site inventory.")
    }
}.joinToString(" ")

private fun statusColor(status: String): Color = when {
    status.contains("fail", true) || status.contains("error", true) -> Color(0xFFC53D55)
    status.contains("complete", true) || status.contains("ready", true) || status.contains("publish", true) -> Color(0xFF2F9B55)
    else -> NetlifyAccent
}
