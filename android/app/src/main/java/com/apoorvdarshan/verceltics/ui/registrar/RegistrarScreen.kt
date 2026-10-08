package com.apoorvdarshan.verceltics.ui.registrar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.data.registrar.registrarDaysUntil
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.apiexplorer.CompleteApiEntryCard
import com.apoorvdarshan.verceltics.ui.apiexplorer.DashboardAndCompleteApiActions
import com.apoorvdarshan.verceltics.ui.apiexplorer.ProviderApiWorkspace
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import com.apoorvdarshan.verceltics.ui.components.AppPullToRefresh
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog

/**
 * Registrar route for one provider id. Shows the connection form until that registrar is
 * connected, then the active account's domain portfolio and Pro-gated domain details. The account
 * menu switches between saved accounts, adds another one through the same protected form, and
 * removes the current or every account after confirmation. Back closes the detail, a dialog or
 * the add-account form before asking the app shell to leave the route.
 */
@Composable
fun RegistrarRoute(
    viewModel: RegistrarViewModel,
    providerId: String,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val uriHandler = LocalUriHandler.current
    val provider = RegistrarProvider.fromId(providerId)
    val providerState = state.provider(providerId)
    var lastHandledSearchRequestId by rememberSaveable(providerId) { mutableIntStateOf(searchRequestId) }
    var searchFocusRequestId by rememberSaveable(providerId) { mutableIntStateOf(0) }
    val routeBack = {
        if (!viewModel.handleBack(providerId)) onBack()
    }
    val openUrl: (String) -> Unit = { url ->
        if (isOpenableRegistrarUrl(url)) runCatching { uriHandler.openUri(url) }
    }
    val apiWorkspace = remember(viewModel, providerId) { viewModel.apiWorkspace(providerId) }
    val isApiOpen = apiWorkspace?.state?.collectAsStateWithLifecycle()?.value?.isOpen == true

    // Domain details are Pro: close a detail restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, state.selectedDomainId) {
        if (proAccess.isConfirmedLocked && state.selectedDomainId != null) viewModel.closeDomain()
    }
    // Complete API is Pro too, and needs a live connection.
    LaunchedEffect(proAccess.isConfirmedLocked, isApiOpen, providerState.status, providerState.isAddingAccount) {
        if (isApiOpen && (proAccess.isConfirmedLocked || providerState.showsConnectionForm)) {
            viewModel.closeApiWorkspace(providerId)
        }
    }
    DisposableEffect(viewModel, providerId) {
        viewModel.setVisibleProvider(providerId)
        onDispose { viewModel.clearVisibleProvider(providerId) }
    }
    BackHandler(onBack = routeBack)
    LaunchedEffect(providerId, providerState.status, providerState.showsConnectionForm, providerState.activeAccount?.id) {
        when {
            providerState.showsConnectionForm ->
                if (provider?.showsPublicIpv4Helper == true) viewModel.detectPublicIpv4(providerId, force = false)
            providerState.status == RegistrarConnectionStatus.CONNECTED -> viewModel.refreshIfStale(providerId)
        }
    }
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            viewModel.closeApiWorkspace(providerId)
            viewModel.cancelAddingAccount(providerId)
            if (state.selectedDomainId != null) {
                viewModel.closeDomain()
                withFrameNanos { }
            }
            searchFocusRequestId += 1
        }
    }
    if (apiWorkspace != null && isApiOpen && providerState.status == RegistrarConnectionStatus.CONNECTED &&
        !providerState.isAddingAccount
    ) {
        ProviderApiWorkspace(controller = apiWorkspace, onOpenLink = openUrl, modifier = modifier)
        return
    }

    RegistrarScreen(
        state = state,
        providerId = providerId,
        onBack = routeBack,
        onConnect = viewModel::connect,
        onCancel = { viewModel.cancelOperation(providerId) },
        onRefresh = { viewModel.refresh(providerId) },
        onOpenDomain = { domainId -> proAccess.requestPro { viewModel.openDomain(providerId, domainId) } },
        onOpenDashboard = { provider?.let { registrar -> proAccess.requestPro { openUrl(registrar.dashboardUrl) } } },
        onOpenUrl = openUrl,
        onRequestDisconnect = { viewModel.requestDisconnectConfirmation(providerId) },
        onDismissDisconnect = viewModel::dismissDisconnectConfirmation,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onDetectPublicIpv4 = { viewModel.detectPublicIpv4(providerId) },
        searchFocusRequestId = searchFocusRequestId,
        modifier = modifier,
        onOpenCompleteApi = { domainId -> proAccess.requestPro { viewModel.openApiWorkspace(providerId, domainId) } },
        onSwitchAccount = { accountId -> viewModel.switchAccount(providerId, accountId) },
        onAddAccount = { viewModel.startAddingAccount(providerId) },
        onCancelAddAccount = { viewModel.cancelAddingAccount(providerId) },
        onRequestRemoveAll = { viewModel.requestRemoveAllAccounts(providerId) },
    )
}

@Composable
fun RegistrarScreen(
    state: RegistrarUiState,
    providerId: String,
    onBack: () -> Unit,
    onConnect: (RegistrarConnectRequest) -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDomain: (String) -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onDetectPublicIpv4: () -> Unit,
    modifier: Modifier = Modifier,
    searchFocusRequestId: Int = 0,
    nowMillis: Long = System.currentTimeMillis(),
    /** Pro-gated iOS "Complete API": the dashboard passes null, a domain detail its id. */
    onOpenCompleteApi: (domainId: String?) -> Unit = {},
    /** Account menu: make another saved account of this registrar active. */
    onSwitchAccount: (accountId: String) -> Unit = {},
    /** Account menu: open the connection form for another account without disconnecting. */
    onAddAccount: () -> Unit = {},
    /** Leaves the add-account form for the active account's portfolio. */
    onCancelAddAccount: () -> Unit = {},
    /** Account menu: ask to remove every saved account of this registrar. */
    onRequestRemoveAll: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val provider = RegistrarProvider.fromId(providerId)
    val catalog = remember(providerId) { catalogProvider(providerId) }
    val providerState = state.provider(providerId)
    val selectedDomain = state.selectedDomain(providerId)
    val removal = state.removalConfirmation?.takeIf { it.providerId == providerId }

    if (provider != null && removal != null) {
        val copy = registrarRemovalCopy(provider, removal)
        ThemedAlertDialog(
            title = copy.title,
            message = copy.message,
            confirmText = copy.confirmText,
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = copy.dismissText,
            enabled = providerState.operation != RegistrarOperation.DISCONNECTING,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmDisconnect()
            },
            onDismissRequest = onDismissDisconnect,
            testTag = "registrar.disconnectDialog",
        )
    }

    val showsAccountMenu = provider != null && catalog != null && selectedDomain == null &&
        providerState.isConnected && !providerState.isAddingAccount && providerState.savedAccounts.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("registrar.screen"),
    ) {
        RegistrarTopBar(
            title = when {
                selectedDomain != null -> selectedDomain.name
                provider != null -> provider.displayName
                else -> "Registrars"
            },
            operation = providerState.operation,
            canRefresh = providerState.isConnected && !providerState.isAddingAccount,
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onBack()
            },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onRefresh()
            },
            onCancel = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onCancel()
            },
            accountMenu = if (showsAccountMenu) {
                {
                    RegistrarAccountMenu(
                        provider = provider,
                        catalogProvider = catalog,
                        providerState = providerState,
                        onSwitchAccount = onSwitchAccount,
                        onAddAccount = onAddAccount,
                        onRemoveCurrent = onRequestDisconnect,
                        onRemoveAll = onRequestRemoveAll,
                    )
                }
            } else {
                null
            },
        )

        when {
            provider == null || catalog == null -> UnsupportedRegistrar(Modifier.weight(1f))
            providerState.status == RegistrarConnectionStatus.RESTORING ->
                RegistrarLoadingState("Opening saved ${provider.displayName} portfolio…", Modifier.weight(1f))
            providerState.showsConnectionForm -> RegistrarConnectionForm(
                provider = provider,
                catalogProvider = catalog,
                providerState = providerState,
                restoreError = state.restoreError.takeUnless { providerState.isAddingAccount },
                publicIpv4 = state.publicIpv4,
                onConnect = onConnect,
                onCancel = onCancel,
                onOpenUrl = onOpenUrl,
                onDetectPublicIpv4 = onDetectPublicIpv4,
                modifier = Modifier.weight(1f),
                isAddingAccount = providerState.isAddingAccount,
                onCancelAddAccount = onCancelAddAccount,
            )
            providerState.status == RegistrarConnectionStatus.SAVED_UNAVAILABLE -> RegistrarSavedRecovery(
                provider = provider,
                catalogProvider = catalog,
                providerState = providerState,
                onRefresh = onRefresh,
                onDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
            selectedDomain != null -> RegistrarDomainDetail(
                provider = provider,
                catalogProvider = catalog,
                domain = selectedDomain,
                nowMillis = nowMillis,
                isRefreshing = providerState.operation == RegistrarOperation.REFRESHING,
                onRefresh = onRefresh,
                onOpenUrl = onOpenUrl,
                onOpenDashboard = onOpenDashboard,
                onOpenCompleteApi = { onOpenCompleteApi(selectedDomain.id) },
                modifier = Modifier.weight(1f),
            )
            else -> RegistrarDashboard(
                provider = provider,
                catalogProvider = catalog,
                providerState = providerState,
                nowMillis = nowMillis,
                onOpenDomain = onOpenDomain,
                onOpenDashboard = onOpenDashboard,
                onOpenCompleteApi = { onOpenCompleteApi(null) },
                onRetry = onRefresh,
                onDisconnect = onRequestDisconnect,
                searchFocusRequestId = searchFocusRequestId,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One card per connected registrar for the Registrars tab. */
@Composable
fun RegistrarConnectionCards(
    state: RegistrarUiState,
    onOpenProvider: (providerId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectedProviderIds
    Column(
        modifier = modifier.testTag("workspace.registrars.connections"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        connected.forEach { providerId ->
            val catalog = catalogProvider(providerId) ?: return@forEach
            RegistrarConnectionCard(
                providerState = state.provider(providerId),
                catalogProvider = catalog,
                onClick = { onOpenProvider(providerId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RegistrarConnectionCard(
    providerState: RegistrarProviderUiState,
    catalogProvider: IntegrationProvider,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val haptic = LocalHapticFeedback.current
    val accent = Color(catalogProvider.accentColor)
    val dashboard = providerState.dashboard
    val summary = dashboard?.let { RegistrarPortfolioSummary.of(it.domains, nowMillis) }
    val accountCount = providerState.savedAccounts.size
    val subtitle = when {
        dashboard != null && summary != null -> listOfNotNull(
            dashboard.account.displayName,
            "${formatCount(accountCount.toLong())} accounts".takeIf { accountCount > 1 },
            "${formatCount(summary.domainCount.toLong())} domain${if (summary.domainCount == 1) "" else "s"}",
            "${registrarCacheLabel(dashboard.cacheState)} data",
        ).joinToString(" · ")
        else -> providerState.savedAccount?.displayName ?: providerState.error ?: "Saved connection"
    }
    val attention = providerState.status == RegistrarConnectionStatus.SAVED_UNAVAILABLE ||
        providerState.error != null || dashboard?.isPartial == true || (summary?.attentionCount ?: 0) > 0
    val status = when {
        providerState.status == RegistrarConnectionStatus.RESTORING -> "Restoring"
        providerState.operation == RegistrarOperation.REFRESHING -> "Refreshing"
        attention -> "Attention"
        else -> "Connected"
    }
    val statusColor = if (status == "Attention") registrarWarningColor() else MaterialTheme.colorScheme.tertiary
    val stacked = shouldStackRegistrarConnectionCard(LocalDensity.current.fontScale)
    OffsetPanel(
        modifier = modifier.heightIn(min = 88.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.20f),
        shadowColor = accent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.registrars.connection.${catalogProvider.id}",
    ) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderMark(provider = catalogProvider, size = 46.dp)
                    Spacer(Modifier.width(13.dp))
                    CardCopy(catalogProvider.displayName, subtitle, maxSubtitleLines = 3)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { StatusPill(status, statusColor) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(provider = catalogProvider, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                CardCopy(catalogProvider.displayName, subtitle, maxSubtitleLines = 2)
                Spacer(Modifier.width(10.dp))
                StatusPill(status, statusColor)
            }
        }
    }
}

@Composable
private fun RowScope.CardCopy(title: String, subtitle: String, maxSubtitleLines: Int) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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

internal fun shouldStackRegistrarConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

/** Three stat tiles fit side by side only with enough width at a normal font scale. */
internal fun shouldStackRegistrarStats(availableWidthDp: Float, fontScale: Float): Boolean =
    availableWidthDp < 300f || fontScale >= 1.5f

@Composable
private fun RegistrarTopBar(
    title: String,
    operation: RegistrarOperation?,
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
        AppToolbarAction(modifier = Modifier.size(48.dp), onClick = onBack, testTag = "registrar.back") {
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
        accountMenu?.invoke()
        val isCancelable = operation == RegistrarOperation.CONNECTING || operation == RegistrarOperation.REFRESHING
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = isCancelable || (canRefresh && operation == null),
            onClick = if (isCancelable) onCancel else onRefresh,
            testTag = "registrar.refreshOrCancel",
        ) {
            when {
                isCancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                operation != null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                canRefresh -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh domains")
                else -> Unit
            }
        }
    }
}

@Composable
private fun RegistrarLoadingState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().testTag("registrar.loading"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnsupportedRegistrar(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(24.dp)
            .testTag("registrar.unsupported"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Connection unavailable",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RegistrarSavedRecovery(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    providerState: RegistrarProviderUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = Color(catalogProvider.accentColor)
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("registrar.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("recovery") {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, borderColor = accent.copy(alpha = 0.24f)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(provider = catalogProvider, size = 46.dp)
                        Spacer(Modifier.width(12.dp))
                        StatusPill("Saved securely", accent)
                    }
                    Text(
                        providerState.savedAccount?.displayName ?: "Saved ${provider.displayName} account",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "The encrypted connection remains on this device. Retry online or remove it explicitly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    providerState.error?.let {
                        RegistrarFeedbackPanel(title = null, message = it, isError = true, accent = accent)
                    }
                    providerState.notice?.let {
                        RegistrarFeedbackPanel(title = null, message = it, isError = false, accent = accent)
                    }
                    ThemedActionButton(
                        if (providerState.operation == RegistrarOperation.REFRESHING) "REFRESHING…" else "REFRESH",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onRefresh()
                        },
                        enabled = !providerState.isBusy,
                        isBusy = providerState.operation == RegistrarOperation.REFRESHING,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "registrar.recovery.refresh",
                    )
                    ThemedActionButton(
                        if (providerState.savedAccounts.size > 1) "REMOVE CURRENT ACCOUNT" else "DISCONNECT",
                        onClick = onDisconnect,
                        enabled = !providerState.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "registrar.disconnect",
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistrarDashboard(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    providerState: RegistrarProviderUiState,
    nowMillis: Long,
    onOpenDomain: (String) -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenCompleteApi: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    searchFocusRequestId: Int,
    modifier: Modifier = Modifier,
) {
    val dashboard = requireNotNull(providerState.dashboard)
    val accent = Color(catalogProvider.accentColor)
    val haptic = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }
    // Like iOS (the dashboard is re-created per account), each account starts with an empty search.
    var query by rememberSaveable(provider.id, dashboard.account.id) { mutableStateOf("") }
    var lastHandledSearchFocusRequestId by rememberSaveable(provider.id) { mutableIntStateOf(0) }
    val summary = remember(dashboard.domains, nowMillis / MINUTE_MILLIS) {
        RegistrarPortfolioSummary.of(dashboard.domains, nowMillis)
    }
    val visibleDomains = remember(dashboard.domains, query) { filterRegistrarDomains(dashboard.domains, query) }
    val accountCount = providerState.savedAccounts.size

    LaunchedEffect(searchFocusRequestId) {
        if (searchFocusRequestId > 0 && searchFocusRequestId != lastHandledSearchFocusRequestId) {
            lastHandledSearchFocusRequestId = searchFocusRequestId
            runCatching { searchFocusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = RegistrarLayout.dashboard(maxWidth.value)
        AppPullToRefresh(
            isRefreshing = providerState.operation == RegistrarOperation.REFRESHING,
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
            testTag = "registrar.pullToRefresh",
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("registrar.dashboard"),
                contentPadding = PaddingValues(
                    start = layout.sidePaddingDp.dp,
                    top = 6.dp,
                    end = layout.sidePaddingDp.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("portfolio") {
                    PortfolioHeader(provider, catalogProvider, providerState, dashboard, summary, accent)
                }
                providerState.error?.let { message ->
                    item("error") {
                        val savedChangeFailed = providerState.failedOperation == RegistrarOperation.DISCONNECTING ||
                            providerState.failedOperation == RegistrarOperation.SWITCHING
                        RegistrarFeedbackPanel(
                            title = if (savedChangeFailed) "Saved registrar change failed" else "Couldn’t refresh domains",
                            message = message,
                            isError = true,
                            accent = accent,
                            actionText = if (savedChangeFailed) null else "TRY AGAIN",
                            onAction = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                onRetry()
                            },
                            testTag = if (savedChangeFailed) "registrar.changeError" else "registrar.refreshError",
                        )
                    }
                }
                providerState.notice?.let { message ->
                    item("notice") {
                        RegistrarFeedbackPanel(title = null, message = message, isError = false, accent = accent)
                    }
                }
                if (dashboard.isPartial) {
                    item("partial") {
                        RegistrarWarningPanel(
                            dashboard.warnings.joinToString(" ").ifBlank { "The registrar returned a partial domain portfolio." },
                        )
                    }
                }
                item("stats") { PortfolioStats(summary, accent, threeColumns = layout.isRegular) }
                item("actions") {
                    DashboardAndCompleteApiActions(
                        onOpenDashboard = onOpenDashboard,
                        onOpenCompleteApi = onOpenCompleteApi,
                        dashboardTestTag = "registrar.openDashboard",
                        completeApiTestTag = "registrar.completeApi",
                        // iOS caps the action pair at 470 pt on regular width.
                        modifier = if (layout.isRegular) Modifier.widthIn(max = 470.dp) else Modifier,
                    )
                }
                item("search") {
                    ControlSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search domains",
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "registrar.search",
                        focusRequester = searchFocusRequester,
                        onSearch = { keyboard?.hide() },
                    )
                }
                item("heading") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) { heading() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Domain portfolio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatCount(visibleDomains.size.toLong()),
                            color = accent,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag("registrar.portfolioCount"),
                        )
                    }
                }
                if (visibleDomains.isEmpty()) {
                    item("empty") {
                        RegistrarEmptyState(
                            icon = if (query.isBlank()) Icons.Rounded.Language else Icons.Rounded.Search,
                            title = if (query.isBlank()) "No domains returned" else "No matching domains",
                            message = if (query.isBlank()) {
                                "This registrar did not return any domains for the connected account."
                            } else {
                                "Nothing matches “${query.trim()}”."
                            },
                        )
                    }
                } else if (layout.columns <= 1) {
                    items(visibleDomains, key = RegistrarDomainUi::id) { domain ->
                        RegistrarDomainRow(domain, accent, nowMillis) {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            keyboard?.hide()
                            onOpenDomain(domain.id)
                        }
                    }
                } else {
                    // iOS LazyVGrid with adaptive 340 dp columns on regular width.
                    items(visibleDomains.chunked(layout.columns), key = { row -> "domains:${row.first().id}" }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("registrar.domainGridRow"),
                            horizontalArrangement = Arrangement.spacedBy(RegistrarLayout.DOMAIN_SPACING_DP.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { domain ->
                                Box(Modifier.weight(1f)) {
                                    RegistrarDomainRow(domain, accent, nowMillis) {
                                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                        keyboard?.hide()
                                        onOpenDomain(domain.id)
                                    }
                                }
                            }
                            repeat(layout.columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                item("disconnect") {
                    ThemedActionButton(
                        registrarRemoveCurrentLabel(provider, accountCount),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDisconnect()
                        },
                        enabled = !providerState.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier
                            .then(if (layout.isRegular) Modifier.widthIn(max = 470.dp) else Modifier)
                            .fillMaxWidth(),
                        testTag = "registrar.disconnect",
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioHeader(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    providerState: RegistrarProviderUiState,
    dashboard: RegistrarDashboardUi,
    summary: RegistrarPortfolioSummary,
    accent: Color,
) {
    val healthColor = registrarToneColor(summary.healthTone, accent)
    val attention = providerState.error != null || dashboard.isPartial
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registrar.portfolio"),
        color = accent.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent.copy(alpha = 0.24f),
        shadowColor = accent,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(provider = catalogProvider, size = 55.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        dashboard.account.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        provider.apiDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${registrarCacheLabel(dashboard.cacheState)} data · updated ${formatRegistrarDateTime(dashboard.fetchedAtMillis)}",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("registrar.cacheState"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(
                    if (attention) "Attention" else "Connected",
                    if (attention) registrarWarningColor() else MaterialTheme.colorScheme.tertiary,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier
                    .testTag("registrar.expiryHealth")
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Expiry health: ${summary.healthLabel}"
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "EXPIRY HEALTH",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    )
                    Text(
                        summary.healthLabel,
                        color = healthColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(summary.healthFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .background(healthColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioStats(summary: RegistrarPortfolioSummary, accent: Color, threeColumns: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("registrar.stats")) {
        // iOS: three flexible columns on regular width; phones keep the adaptive fallback.
        val stacked = !threeColumns && shouldStackRegistrarStats(maxWidth.value, LocalDensity.current.fontScale)
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Domains", summary.domainCount, Icons.Rounded.Public, accent, Modifier.fillMaxWidth(), "registrar.stat.domains")
                StatTile("Attention", summary.attentionCount, Icons.Rounded.EventBusy, accent, Modifier.fillMaxWidth(), "registrar.stat.attention")
                StatTile("Auto renew", summary.autoRenewCount, Icons.Rounded.Autorenew, accent, Modifier.fillMaxWidth(), "registrar.stat.autoRenew")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Domains", summary.domainCount, Icons.Rounded.Public, accent, Modifier.weight(1f), "registrar.stat.domains")
                StatTile("Attention", summary.attentionCount, Icons.Rounded.EventBusy, accent, Modifier.weight(1f), "registrar.stat.attention")
                StatTile("Auto renew", summary.autoRenewCount, Icons.Rounded.Autorenew, accent, Modifier.weight(1f), "registrar.stat.autoRenew")
            }
        }
    }
}

@Composable
private fun StatTile(
    title: String,
    value: Int,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier,
    testTag: String,
) {
    OffsetPanel(
        modifier = modifier
            .heightIn(min = 92.dp)
            .testTag(testTag)
            .clearAndSetSemantics { contentDescription = "$title: ${formatCount(value.toLong())}" },
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Text(
                formatCount(value.toLong()),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                title.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RegistrarDomainRow(domain: RegistrarDomainUi, accent: Color, nowMillis: Long, onClick: () -> Unit) {
    val tone = registrarExpiryTone(domain.expiresAtMillis, nowMillis)
    val expiryColor = if (domain.expiresAtMillis == null) accent else registrarToneColor(tone, accent)
    val (value, unit) = registrarExpiryValue(domain.expiresAtMillis, nowMillis)
    val details = buildList {
        if (domain.autoRenew == true) add("Auto")
        if (domain.locked == true) add("Locked")
        domain.expiresAtMillis?.let { add("Expires ${formatRegistrarDate(it)}") }
    }
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        onClick = onClick,
        testTag = "registrar.domain.${domain.id}",
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(expiryColor.copy(alpha = 0.11f))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(value, color = expiryColor, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(unit, color = expiryColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    domain.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open ${domain.name} details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RegistrarEmptyState(icon: ImageVector, title: String, message: String) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registrar.empty"),
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RegistrarDomainDetail(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    domain: RegistrarDomainUi,
    nowMillis: Long,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenCompleteApi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(catalogProvider.accentColor)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = RegistrarLayout.detail(maxWidth.value)
        AppPullToRefresh(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            testTag = "registrar.detail.pullToRefresh",
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("registrar.domainDetail"),
                contentPadding = PaddingValues(
                    start = layout.sidePaddingDp.dp,
                    top = 6.dp,
                    end = layout.sidePaddingDp.dp,
                    bottom = 32.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item("hero") {
                    DomainHero(provider, catalogProvider, domain, nowMillis, onOpenUrl, onOpenDashboard)
                }
                if (layout.columns >= 2) {
                    // iOS: properties and nameservers side by side on regular width.
                    item("panels") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("registrar.detail.panels"),
                            horizontalArrangement = Arrangement.spacedBy(RegistrarLayout.DETAIL_SPACING_DP.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(Modifier.weight(1f)) { DomainProperties(domain, accent) }
                            Box(Modifier.weight(1f)) { DomainNameservers(domain, accent) }
                        }
                    }
                } else {
                    item("properties") { DomainProperties(domain, accent) }
                    item("nameservers") { DomainNameservers(domain, accent) }
                }
                item("complete-api") {
                    CompleteApiEntryCard(
                        accent = accent,
                        onClick = onOpenCompleteApi,
                        testTag = "registrar.detail.completeApi",
                        title = "Complete registrar API",
                        subtitle = "Search every indexed read and write operation, then inspect the full raw response",
                    )
                }
            }
        }
    }
}

@Composable
private fun DomainHero(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    domain: RegistrarDomainUi,
    nowMillis: Long,
    onOpenUrl: (String) -> Unit,
    onOpenDashboard: () -> Unit,
) {
    val accent = Color(catalogProvider.accentColor)
    val haptic = LocalHapticFeedback.current
    val days = domain.expiresAtMillis?.let { registrarDaysUntil(it, nowMillis) }
    val expiryColor = registrarToneColor(registrarExpiryTone(domain.expiresAtMillis, nowMillis), accent)
    val statusText = domain.status?.uppercase() ?: provider.displayName.uppercase()
    val statusColor = registrarToneColor(registrarStatusTone(domain.status.orEmpty()), accent)
    val domainUrl = remember(domain.name) { registrarDomainUrl(domain.name) }
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent.copy(alpha = 0.24f),
        shadowColor = accent,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderMark(provider = catalogProvider, size = 54.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        domain.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(statusText, statusColor)
                }
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .testTag("registrar.detail.expiry")
                    .semantics(mergeDescendants = true) { },
            ) {
                Text(
                    days?.let { formatCount(kotlin.math.abs(it.toLong())) } ?: "—",
                    color = expiryColor,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        days == null -> "expiry unavailable"
                        days < 0 -> "days expired"
                        else -> "days left"
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                domain.expiresAtMillis?.let {
                    Text(
                        formatRegistrarDate(it),
                        modifier = Modifier.padding(bottom = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (domainUrl != null) {
                    RegistrarTintedAction(
                        text = "Open domain",
                        icon = Icons.AutoMirrored.Rounded.OpenInNew,
                        accent = MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onOpenUrl(domainUrl)
                        },
                        modifier = Modifier.weight(1f),
                        testTag = "registrar.detail.openDomain",
                    )
                }
                RegistrarTintedAction(
                    text = "Registrar",
                    icon = Icons.Rounded.Language,
                    accent = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onOpenDashboard()
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "registrar.detail.openRegistrar",
                )
            }
        }
    }
}

@Composable
private fun DomainProperties(domain: RegistrarDomainUi, accent: Color) {
    DetailPanel(testTag = "registrar.detail.properties") {
        PropertyRow("Auto renewal", registrarBooleanText(domain.autoRenew), Icons.Rounded.Autorenew, accent)
        PropertyDivider()
        PropertyRow("Transfer lock", registrarBooleanText(domain.locked), Icons.Rounded.Lock, accent)
        PropertyDivider()
        PropertyRow("WHOIS privacy", registrarBooleanText(domain.privacyEnabled), Icons.Rounded.VisibilityOff, accent)
        domain.createdAtMillis?.let {
            PropertyDivider()
            PropertyRow("Registered", formatRegistrarDate(it), Icons.Rounded.EventAvailable, accent)
        }
    }
}

@Composable
private fun DomainNameservers(domain: RegistrarDomainUi, accent: Color) {
    DetailPanel(testTag = "registrar.detail.nameservers") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Nameservers",
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (domain.nameservers.isEmpty()) {
                Text(
                    REGISTRAR_EMPTY_NAMESERVERS_HINT,
                    modifier = Modifier.testTag("registrar.detail.nameservers.empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                domain.nameservers.forEach { nameserver ->
                    Text(
                        nameserver,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPanel(testTag: String, content: @Composable ColumnScope.() -> Unit) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(content = content)
    }
}

@Composable
private fun PropertyRow(title: String, value: String, icon: ImageVector, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 15.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PropertyDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 45.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private const val MINUTE_MILLIS = 60_000L
