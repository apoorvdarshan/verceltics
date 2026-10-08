package com.apoorvdarshan.verceltics.ui.hosting

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
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.hosting.HostingCredentials
import com.apoorvdarshan.verceltics.data.hosting.HostingProvider
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.apiexplorer.CompleteApiEntryCard
import com.apoorvdarshan.verceltics.ui.apiexplorer.DashboardAndCompleteApiActions
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiWorkspace
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import java.net.URI
import java.text.DateFormat
import java.util.Date

/**
 * App-shell entry point for one generic hosting provider. Lists, search, refresh, connect and
 * disconnect are free; resource details and the provider console link require Verceltics Pro.
 */
@Composable
fun HostingProviderRoute(
    viewModel: HostingProvidersViewModel,
    providerId: String,
    onBack: () -> Unit,
    onRequestGoogleSignIn: (Set<String>) -> Unit = {},
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.uiState.collectAsStateWithLifecycle()
    val state = providers.provider(providerId)
    val proAccess = LocalProAccess.current
    val uriHandler = LocalUriHandler.current
    var lastHandledSearchRequestId by rememberSaveable(providerId) { mutableIntStateOf(searchRequestId) }
    var searchFocusRequestId by rememberSaveable(providerId) { mutableIntStateOf(0) }
    val routeBack = {
        if (!viewModel.handleBack(providerId)) onBack()
    }
    val openLink: (String) -> Unit = { url -> openHttpsLink(url) { uriHandler.openUri(it) } }
    val apiWorkspace = remember(viewModel, providerId) { viewModel.apiWorkspace(providerId) }
    val isApiOpen = apiWorkspace?.state?.collectAsStateWithLifecycle()?.value?.isOpen == true

    // Resource details are Pro: close one restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, state.selectedResourceId) {
        if (proAccess.isConfirmedLocked && state.selectedResourceId != null) viewModel.closeResource(providerId)
    }
    // Complete API is Pro too, and needs a live connection.
    LaunchedEffect(proAccess.isConfirmedLocked, isApiOpen, state.status) {
        if (isApiOpen && (proAccess.isConfirmedLocked || state.status == HostingConnectionStatus.DISCONNECTED)) {
            viewModel.closeApiWorkspace(providerId)
        }
    }
    DisposableEffect(viewModel, providerId) {
        viewModel.setRouteVisible(providerId, true)
        onDispose { viewModel.setRouteVisible(providerId, false) }
    }
    BackHandler(onBack = routeBack)
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            viewModel.closeApiWorkspace(providerId)
            if (state.selectedResourceId != null) {
                viewModel.closeResource(providerId)
                withFrameNanos { }
            }
            searchFocusRequestId += 1
        }
    }
    val signInRequest = state.googleSignInRequest
    LaunchedEffect(signInRequest?.id) {
        if (signInRequest != null) {
            viewModel.onGoogleSignInRequestHandled(providerId, signInRequest.id)
            onRequestGoogleSignIn(signInRequest.scopes)
        }
    }

    if (apiWorkspace != null && isApiOpen && state.status == HostingConnectionStatus.CONNECTED) {
        ProviderApiWorkspace(controller = apiWorkspace, onOpenLink = openLink, modifier = modifier)
        return
    }

    HostingProviderScreen(
        providerId = providerId,
        state = state,
        callbacks = HostingProviderScreenCallbacks(
            onBack = routeBack,
            onConnect = viewModel::connect,
            onRefresh = { viewModel.refresh(providerId) },
            onCancel = { viewModel.cancelOperation(providerId) },
            onOpenResource = { resourceId -> proAccess.requestPro { viewModel.openResource(providerId, resourceId) } },
            onRefreshResource = { viewModel.refreshSelectedResource(providerId) },
            onOpenDashboard = { url -> proAccess.requestPro { openLink(url) } },
            onOpenLink = openLink,
            onRequestDisconnect = { viewModel.requestDisconnectConfirmation(providerId) },
            onDismissDisconnect = { viewModel.dismissDisconnectConfirmation(providerId) },
            onConfirmDisconnect = { viewModel.confirmDisconnect(providerId) },
            onRequestPrimaryAction = { viewModel.requestPrimaryAction(providerId) },
            onDismissPrimaryAction = { viewModel.dismissPrimaryAction(providerId) },
            onConfirmPrimaryAction = { viewModel.confirmPrimaryAction(providerId) },
            onContinueWithGoogle = { viewModel.requestGoogleSignIn(providerId) },
            onOpenCompleteApi = { resourceId -> proAccess.requestPro { viewModel.openApiWorkspace(providerId, resourceId) } },
        ),
        searchFocusRequestId = searchFocusRequestId,
        modifier = modifier,
    )
}

/** Callbacks for [HostingProviderScreen]. Defaults keep previews and UI tests concise. */
class HostingProviderScreenCallbacks(
    val onBack: () -> Unit = {},
    val onConnect: (HostingCredentials) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenResource: (resourceId: String) -> Unit = {},
    val onRefreshResource: () -> Unit = {},
    /** Pro-gated provider console link (iOS "Dashboard"). */
    val onOpenDashboard: (url: String) -> Unit = {},
    /** Ungated links: credential help pages and the resource's own public URL. */
    val onOpenLink: (url: String) -> Unit = {},
    val onRequestDisconnect: () -> Unit = {},
    val onDismissDisconnect: () -> Unit = {},
    val onConfirmDisconnect: () -> Unit = {},
    val onRequestPrimaryAction: () -> Unit = {},
    val onDismissPrimaryAction: () -> Unit = {},
    val onConfirmPrimaryAction: () -> Unit = {},
    val onContinueWithGoogle: () -> Unit = {},
    /** Pro-gated iOS "Complete API": the dashboard passes null, a resource detail its id. */
    val onOpenCompleteApi: (resourceId: String?) -> Unit = {},
)

@Composable
fun HostingProviderScreen(
    providerId: String,
    state: HostingProviderUiState,
    callbacks: HostingProviderScreenCallbacks,
    searchFocusRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val provider = HostingProvider.fromId(providerId)
    val catalogProvider = IntegrationCatalog.provider(providerId)
    if (provider == null || catalogProvider == null) {
        Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HostingTopBar("Hosting", null, false, false, false, callbacks.onBack, {}, {})
            HostingFeedbackPanel(null, "This hosting provider is not supported.", isError = true, modifier = Modifier.padding(18.dp))
        }
        return
    }
    val haptic = LocalHapticFeedback.current
    val selected = state.selectedResource

    if (state.showDisconnectConfirmation) {
        ThemedAlertDialog(
            title = "Disconnect ${provider.displayName}?",
            message = if (provider == HostingProvider.FIREBASE) {
                "The saved Firebase Hosting project and inventory will be removed from this device. Google sign-in is managed separately."
            } else {
                "The encrypted credentials and saved ${provider.displayName} inventory will be removed from this device."
            },
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            enabled = state.operation != HostingOperation.DISCONNECTING,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onConfirmDisconnect()
            },
            onDismissRequest = callbacks.onDismissDisconnect,
            testTag = "hosting.$providerId.disconnectDialog",
        )
    }
    val actionLabel = provider.primaryActionLabel
    if (state.showActionConfirmation && selected != null && actionLabel != null) {
        ThemedAlertDialog(
            title = "$actionLabel ${selected.name}?",
            message = "This sends a real write request to ${provider.displayName}.",
            confirmText = actionLabel.uppercase(),
            dismissText = "CANCEL",
            enabled = !state.isPerformingAction,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onConfirmPrimaryAction()
            },
            onDismissRequest = callbacks.onDismissPrimaryAction,
            testTag = "hosting.$providerId.actionDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("hosting.$providerId.screen"),
    ) {
        val inDetail = state.status == HostingConnectionStatus.CONNECTED && selected != null
        HostingTopBar(
            title = if (inDetail) selected.name else provider.displayName,
            operation = state.operation,
            isLoading = state.isLoadingResource && inDetail,
            canRefresh = state.isConnected,
            isDetail = inDetail,
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onBack()
            },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (inDetail) callbacks.onRefreshResource() else callbacks.onRefresh()
            },
            onCancel = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onCancel()
            },
            providerName = provider.displayName,
        )
        when {
            state.status == HostingConnectionStatus.RESTORING -> HostingLoading(
                "Opening saved ${provider.displayName} workspace…",
                Color(catalogProvider.accentColor),
                Modifier.weight(1f),
            )
            state.status == HostingConnectionStatus.DISCONNECTED -> HostingConnectionForm(
                provider = provider,
                catalogProvider = catalogProvider,
                state = state,
                onConnect = callbacks.onConnect,
                onCancel = callbacks.onCancel,
                onOpenLink = callbacks.onOpenLink,
                modifier = Modifier.weight(1f),
            )
            state.status == HostingConnectionStatus.SAVED_UNAVAILABLE -> HostingSavedRecovery(
                provider = provider,
                catalogProvider = catalogProvider,
                state = state,
                callbacks = callbacks,
                modifier = Modifier.weight(1f),
            )
            selected != null -> HostingResourceDetail(
                provider = provider,
                catalogProvider = catalogProvider,
                state = state,
                resource = selected,
                callbacks = callbacks,
                modifier = Modifier.weight(1f),
            )
            else -> key(providerId) {
                HostingDashboard(
                    provider = provider,
                    catalogProvider = catalogProvider,
                    state = state,
                    callbacks = callbacks,
                    searchFocusRequestId = searchFocusRequestId,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One card per connected provider for the Hosting tab, in catalog order. */
@Composable
fun HostingProviderConnectionCards(
    state: HostingProvidersUiState,
    onOpenProvider: (providerId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectedProviderIds
    if (connected.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        connected.forEach { providerId ->
            HostingProviderConnectionCard(
                state = state.provider(providerId),
                onClick = { onOpenProvider(providerId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HostingProviderConnectionCard(
    state: HostingProviderUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = HostingProvider.fromId(state.providerId) ?: return
    val catalogProvider = IntegrationCatalog.provider(state.providerId) ?: return
    val haptic = LocalHapticFeedback.current
    val accent = Color(catalogProvider.accentColor)
    val subtitle = connectionCardSubtitle(provider, state)
    val status = connectionCardStatus(state)
    val statusColor = if (status == "Attention") HostingWarningColor else accent
    val stacked = shouldStackHostingConnectionCard(LocalDensity.current.fontScale)
    OffsetPanel(
        modifier = modifier.heightIn(min = 88.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.20f),
        shadowColor = accent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.hosting.${state.providerId}Connection",
    ) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ProviderMark(provider = catalogProvider, size = 46.dp)
                    Spacer(Modifier.width(13.dp))
                    CardCopy(catalogProvider.displayName, subtitle, maxSubtitleLines = 2, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StatusPill(status, statusColor) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(provider = catalogProvider, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                CardCopy(catalogProvider.displayName, subtitle, maxSubtitleLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatusPill(status, statusColor)
            }
        }
    }
}

@Composable
private fun CardCopy(title: String, subtitle: String, maxSubtitleLines: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxSubtitleLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun shouldStackHostingConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

/** Each detail action needs ~124 dp for labels such as "START RELEASE" to stay on one line. */
internal fun shouldStackDetailActions(availableWidthDp: Float, buttonCount: Int, fontScale: Float): Boolean =
    fontScale >= 1.3f || availableWidthDp < buttonCount * 124f

internal fun connectionCardSubtitle(provider: HostingProvider, state: HostingProviderUiState): String =
    state.dashboard?.let { dashboard ->
        "${countLabel(dashboard.loadedResourceCount, provider.resourceTitle)} · ${cacheLabel(dashboard.cacheState)} data"
    } ?: state.savedAccount?.email ?: state.error ?: "Saved connection"

internal fun connectionCardStatus(state: HostingProviderUiState): String = when (state.status) {
    HostingConnectionStatus.CONNECTED -> if (
        state.error != null || state.dashboard?.truncatedForDisplay == true || state.dashboard?.warnings?.isNotEmpty() == true
    ) {
        "Attention"
    } else {
        "Connected"
    }
    HostingConnectionStatus.SAVED_UNAVAILABLE -> "Attention"
    HostingConnectionStatus.RESTORING -> "Restoring"
    HostingConnectionStatus.DISCONNECTED -> "Disconnected"
}

@Composable
private fun HostingTopBar(
    title: String,
    operation: HostingOperation?,
    isLoading: Boolean,
    canRefresh: Boolean,
    isDetail: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    providerName: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppToolbarAction(modifier = Modifier.size(48.dp), onClick = onBack, testTag = "hosting.back") {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
        val isCancelable = !isDetail && (operation == HostingOperation.CONNECTING || operation == HostingOperation.REFRESHING)
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = isCancelable || (canRefresh && operation == null && !isLoading),
            onClick = if (isCancelable) onCancel else onRefresh,
            testTag = "hosting.refreshOrCancel",
        ) {
            when {
                isCancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                operation != null || isLoading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh $providerName")
            }
        }
    }
}

@Composable
private fun HostingLoading(message: String, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = accent)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HostingSavedRecovery(
    provider: HostingProvider,
    catalogProvider: IntegrationProvider,
    state: HostingProviderUiState,
    callbacks: HostingProviderScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hosting.${provider.id}.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("recovery") {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(catalogProvider, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        StatusPill("Saved securely", Color(catalogProvider.accentColor))
                    }
                    Text(
                        state.savedAccount?.displayName ?: "Saved ${provider.displayName} account",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "The encrypted connection remains on this device. Retry online or remove it explicitly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let { HostingFeedbackPanel(null, it, isError = true) }
                    state.notice?.let { HostingFeedbackPanel(null, it, isError = false) }
                    if (state.googleSignInRequired && provider == HostingProvider.FIREBASE) {
                        ThemedActionButton(
                            "CONTINUE WITH GOOGLE",
                            onClick = callbacks.onContinueWithGoogle,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "hosting.${provider.id}.continueWithGoogle",
                        )
                    }
                    ThemedActionButton(
                        if (state.operation == HostingOperation.REFRESHING) "REFRESHING…" else "REFRESH",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            callbacks.onRefresh()
                        },
                        enabled = !state.isBusy,
                        isBusy = state.operation == HostingOperation.REFRESHING,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "hosting.${provider.id}.recovery.refresh",
                    )
                    ThemedActionButton(
                        "DISCONNECT",
                        onClick = callbacks.onRequestDisconnect,
                        enabled = !state.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "hosting.${provider.id}.disconnect",
                    )
                }
            }
        }
    }
}

@Composable
private fun HostingDashboard(
    provider: HostingProvider,
    catalogProvider: IntegrationProvider,
    state: HostingProviderUiState,
    callbacks: HostingProviderScreenCallbacks,
    searchFocusRequestId: Int,
    modifier: Modifier = Modifier,
) {
    val dashboard = requireNotNull(state.dashboard)
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    var query by rememberSaveable { mutableStateOf("") }
    var lastHandledSearchFocusRequestId by rememberSaveable { mutableIntStateOf(0) }
    val accent = Color(catalogProvider.accentColor)
    val visibleResources = remember(dashboard.resources, query) { filterResources(dashboard.resources, query) }

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
            .testTag("hosting.${provider.id}.dashboard"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            val attention = state.error != null || dashboard.truncatedForDisplay || dashboard.warnings.isNotEmpty()
            ProviderSummaryCard(
                provider = catalogProvider,
                title = dashboard.account.displayName,
                subtitle = dashboard.account.email ?: catalogProvider.description,
                status = if (attention) "Attention" else "Connected",
                statusColor = if (attention) HostingWarningColor else MaterialTheme.colorScheme.tertiary,
                detail = "${cacheLabel(dashboard.cacheState)} data · ${countLabel(dashboard.loadedResourceCount, provider.resourceTitle)}",
                testTag = "hosting.${provider.id}.summary",
            )
        }
        state.error?.let { message ->
            item("error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HostingFeedbackPanel("Couldn’t refresh ${provider.displayName}", message, isError = true)
                    if (state.googleSignInRequired && provider == HostingProvider.FIREBASE) {
                        ThemedActionButton(
                            "CONTINUE WITH GOOGLE",
                            onClick = callbacks.onContinueWithGoogle,
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "hosting.${provider.id}.continueWithGoogle",
                        )
                    } else {
                        ThemedActionButton(
                            "TRY AGAIN",
                            onClick = callbacks.onRefresh,
                            enabled = !state.isBusy,
                            tone = ThemedActionTone.NEUTRAL,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "hosting.${provider.id}.retry",
                        )
                    }
                }
            }
        }
        state.notice?.let { item("notice") { HostingFeedbackPanel(null, it, isError = false) } }
        if (dashboard.truncatedForDisplay || dashboard.warnings.isNotEmpty()) {
            item("inventory-warning") { HostingWarningPanel(inventoryDisclosure(dashboard)) }
        }
        item("actions") {
            DashboardAndCompleteApiActions(
                onOpenDashboard = { callbacks.onOpenDashboard(dashboard.dashboardUrl) },
                onOpenCompleteApi = { callbacks.onOpenCompleteApi(null) },
                dashboardTestTag = "hosting.${provider.id}.openDashboard",
                completeApiTestTag = "hosting.${provider.id}.completeApi",
            )
        }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ${provider.displayName}",
                modifier = Modifier.fillMaxWidth(),
                testTag = "hosting.${provider.id}.search",
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
                Text(provider.resourceTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (query.isBlank()) dashboard.resources.size.toString() else "${visibleResources.size} of ${dashboard.resources.size}",
                    color = accent.copy(alpha = 0.9f).compositeOver(MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (visibleResources.isEmpty()) {
            item("empty") {
                val searching = query.isNotBlank()
                OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, testTag = "hosting.${provider.id}.empty") {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (searching) "No matching resources" else "No resources returned",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (searching) {
                                "Nothing matches “${query.trim()}”."
                            } else {
                                "This provider did not return any ${provider.resourceTitle.lowercase()} for the connected account."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(visibleResources, key = { "resource-${it.id}" }) { resource ->
                HostingResourceRow(provider, accent, resource) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    callbacks.onOpenResource(resource.id)
                }
            }
        }
        item("disconnect") {
            ThemedActionButton(
                "DISCONNECT ${provider.displayName.uppercase()}",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    callbacks.onRequestDisconnect()
                },
                enabled = !state.isBusy,
                tone = ThemedActionTone.DESTRUCTIVE,
                modifier = Modifier.fillMaxWidth(),
                testTag = "hosting.${provider.id}.disconnect",
            )
        }
    }
}

@Composable
private fun HostingResourceRow(
    provider: HostingProvider,
    accent: Color,
    resource: HostingResourceUi,
    onClick: () -> Unit,
) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.20f),
        shadowOffset = 3.dp,
        onClick = onClick,
        testTag = "hosting.${provider.id}.resource.${resource.id}",
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            HostingIconTile(resourceIcon(provider), accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(resource.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                resourceCaption(resource)?.let { caption ->
                    Text(
                        caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            resource.status?.let { status ->
                Spacer(Modifier.width(8.dp))
                StatusPill(status, hostingStatusTone(status).color())
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = "Open ${resource.name} details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HostingResourceDetail(
    provider: HostingProvider,
    catalogProvider: IntegrationProvider,
    state: HostingProviderUiState,
    resource: HostingResourceUi,
    callbacks: HostingProviderScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = Color(catalogProvider.accentColor)
    val workspace = state.resourceWorkspace?.takeIf { it.resourceId == resource.id }
    val historyTitle = provider.historyTitle
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hosting.${provider.id}.resourceDetail"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                borderColor = accent.copy(alpha = 0.22f),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(catalogProvider, size = 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(resource.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            listOfNotNull(resource.kind, resource.region).filter(String::isNotBlank).joinToString(" · ")
                                .takeIf(String::isNotEmpty)?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                        }
                        resource.status?.let { status ->
                            Spacer(Modifier.width(8.dp))
                            StatusPill(status, hostingStatusTone(status).color())
                        }
                    }
                    resource.subtitle?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val buttons = buildList<Triple<String, String, () -> Unit>> {
                            resource.url?.let { url -> add(Triple("OPEN", "hosting.${provider.id}.openResource") { callbacks.onOpenLink(url) }) }
                            add(Triple("DASHBOARD", "hosting.${provider.id}.openResourceDashboard") { callbacks.onOpenDashboard(resource.dashboardUrl) })
                            provider.primaryActionLabel?.let { label ->
                                add(
                                    Triple(label.uppercase(), "hosting.${provider.id}.primaryAction") {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        callbacks.onRequestPrimaryAction()
                                    },
                                )
                            }
                        }
                        val stacked = shouldStackDetailActions(maxWidth.value, buttons.size, LocalDensity.current.fontScale)
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                buttons.forEach { (title, tag, action) ->
                                    DetailActionButton(title, tag, action, state, title == provider.primaryActionLabel?.uppercase(), Modifier.fillMaxWidth())
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                buttons.forEach { (title, tag, action) ->
                                    DetailActionButton(title, tag, action, state, title == provider.primaryActionLabel?.uppercase(), Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        item("complete-api") {
            CompleteApiEntryCard(
                accent = accent,
                onClick = { callbacks.onOpenCompleteApi(resource.id) },
                testTag = "hosting.${provider.id}.resourceCompleteApi",
            )
        }
        state.actionMessage?.let { item("action-success") { HostingFeedbackPanel("Request accepted", it, isError = false) } }
        state.actionError?.let { item("action-error") { HostingFeedbackPanel("Request failed", it, isError = true) } }
        state.resourceError?.let { item("resource-error") { HostingFeedbackPanel(null, it, isError = true) } }
        item("history-heading") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(historyTitle, style = MaterialTheme.typography.titleMedium)
                workspace?.let {
                    Text(
                        if (it.truncatedForDisplay) "${it.deployments.size} of ${it.loadedDeploymentCount}" else it.deployments.size.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        when {
            workspace == null && state.isLoadingResource -> item("history-loading") {
                HostingLoading("Loading ${historyTitle.lowercase()}…", accent, Modifier.heightIn(min = 160.dp))
            }
            workspace == null -> Unit
            workspace.deployments.isEmpty() -> item("history-empty") {
                OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, testTag = "hosting.${provider.id}.historyEmpty") {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No ${historyTitle.lowercase()}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${provider.displayName} did not return any ${historyTitle.lowercase()} for this resource.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> {
                if (workspace.truncatedForDisplay) {
                    item("history-truncated") {
                        HostingWarningPanel(
                            "Showing the latest ${workspace.deployments.size} of ${workspace.loadedDeploymentCount} " +
                                "${historyTitle.lowercase()}.",
                        )
                    }
                }
                items(workspace.deployments, key = { "deployment-${it.id}" }) { deployment ->
                    HostingDeploymentRow(provider, deployment)
                }
            }
        }
    }
}

@Composable
private fun DetailActionButton(
    title: String,
    testTag: String,
    onClick: () -> Unit,
    state: HostingProviderUiState,
    isPrimaryAction: Boolean,
    modifier: Modifier,
) {
    ThemedActionButton(
        title,
        onClick = onClick,
        enabled = !(isPrimaryAction && state.isPerformingAction),
        isBusy = isPrimaryAction && state.isPerformingAction,
        tone = if (isPrimaryAction) ThemedActionTone.PRIMARY else ThemedActionTone.NEUTRAL,
        modifier = modifier,
        testTag = testTag,
    )
}

@Composable
private fun HostingDeploymentRow(provider: HostingProvider, deployment: HostingDeploymentUi) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowOffset = 2.dp,
        testTag = "hosting.${provider.id}.deployment.${deployment.id}",
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(deployment.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        StatusPill(deployment.status, hostingStatusTone(deployment.status).color())
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            deployment.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusPill(deployment.status, hostingStatusTone(deployment.status).color())
                    }
                }
            }
            deployment.commitMessage?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val footer = listOfNotNull(
                deployment.branch?.takeIf(String::isNotBlank),
                deployment.createdAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
            )
            if (footer.isNotEmpty()) {
                Text(footer.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HostingIconTile(icon: ImageVector, accent: Color) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(11.dp),
        color = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = accent,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f)),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
internal fun HostingFeedbackPanel(title: String?, message: String, isError: Boolean, modifier: Modifier = Modifier) {
    val accent = if (isError) MaterialTheme.colorScheme.error else HostingSuccessColor
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface), RoundedCornerShape(14.dp))
            .semantics(mergeDescendants = true) {
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                contentDescription = listOfNotNull(title, message).joinToString(". ")
            }
            .padding(13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, contentDescription = null, tint = accent)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HostingWarningPanel(message: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = HostingWarningColor.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = HostingWarningColor.copy(alpha = 0.35f),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = HostingWarningColor)
            Spacer(Modifier.width(9.dp))
            Text(message, Modifier.weight(1f))
        }
    }
}

// region Pure presentation helpers (unit tested)

/** iOS `AppStatusTone.status(_:)`. */
internal enum class HostingStatusTone {
    SUCCESS,
    WARNING,
    DANGER,
    PROGRESS,
    NEUTRAL,
}

internal fun hostingStatusTone(status: String): HostingStatusTone {
    val value = status.lowercase()
    fun any(vararg needles: String) = needles.any { it in value }
    return when {
        any(
            "inactive", "deactiv", "expired", "disabled", "deleted", "blocked", "fail", "error", "cancel",
            "suspend", "fatal", "stopped", "offline",
        ) -> HostingStatusTone.DANGER
        any("build", "progress", "initial") -> HostingStatusTone.PROGRESS
        any("pending", "queued", "starting", "warning", "paused", "not ready", "incomplete") -> HostingStatusTone.WARNING
        any("active", "ready", "success", "live", "running", "published", "succeed", "complete") -> HostingStatusTone.SUCCESS
        else -> HostingStatusTone.NEUTRAL
    }
}

@Composable
private fun HostingStatusTone.color(): Color = when (this) {
    HostingStatusTone.SUCCESS -> HostingSuccessColor
    HostingStatusTone.WARNING -> HostingWarningColor
    HostingStatusTone.DANGER -> HostingDangerColor
    HostingStatusTone.PROGRESS -> HostingProgressColor
    HostingStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** iOS dashboard search: name, subtitle, status and kind (plus region on Android). */
internal fun filterResources(resources: List<HostingResourceUi>, query: String): List<HostingResourceUi> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return resources
    return resources.filter { resource ->
        listOfNotNull(resource.name, resource.subtitle, resource.status, resource.kind, resource.region)
            .any { it.contains(normalized, ignoreCase = true) }
    }
}

/** iOS resource row caption: kind · region · subtitle. */
internal fun resourceCaption(resource: HostingResourceUi): String? =
    listOfNotNull(resource.kind, resource.region, resource.subtitle)
        .filter(String::isNotBlank)
        .joinToString(" · ")
        .takeIf(String::isNotEmpty)

internal fun countLabel(count: Int, pluralTitle: String): String {
    val plural = pluralTitle.lowercase()
    val noun = if (count == 1) {
        when (plural) {
            "build jobs" -> "build job"
            else -> plural.removeSuffix("s")
        }
    } else {
        plural
    }
    return "$count $noun"
}

internal fun cacheLabel(cacheState: HostingCacheState): String = when (cacheState) {
    HostingCacheState.LIVE -> "Live"
    HostingCacheState.CACHED_FRESH -> "Saved"
    HostingCacheState.CACHED_STALE -> "Stale"
}

private fun inventoryDisclosure(dashboard: HostingDashboardUi): String = buildList {
    addAll(dashboard.warnings)
    if (dashboard.truncatedForDisplay) {
        add("Showing ${dashboard.resources.size} of ${dashboard.loadedResourceCount} loaded resources.")
    }
}.joinToString(" ")

private fun resourceIcon(provider: HostingProvider): ImageVector = when (provider) {
    HostingProvider.FIREBASE -> Icons.Rounded.Public
    HostingProvider.RAILWAY -> Icons.Rounded.Inventory2
    HostingProvider.RENDER -> Icons.Rounded.Dns
    HostingProvider.DIGITAL_OCEAN, HostingProvider.HEROKU, HostingProvider.FLY, HostingProvider.AWS_AMPLIFY -> Icons.Rounded.Apps
}

/** Only absolute HTTPS links leave the app; anything else is ignored. */
internal fun openHttpsLink(url: String, open: (String) -> Unit) {
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) return
    runCatching { open(url) }
}

private val HostingSuccessColor = Color(0xFF2F9B55)
private val HostingWarningColor = Color(0xFFE3A008)
private val HostingDangerColor = Color(0xFFC53D55)
private val HostingProgressColor = Color(0xFF3B82F6)

// endregion
