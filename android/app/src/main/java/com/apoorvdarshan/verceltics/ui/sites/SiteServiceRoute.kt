package com.apoorvdarshan.verceltics.ui.sites

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.sites.SiteProvider
import com.apoorvdarshan.verceltics.data.sites.UmamiSiteAdapter
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
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import java.time.LocalDate

/** Every callback the site-service screens can raise; defaults keep previews and tests short. */
class SiteServiceScreenActions(
    val onBack: () -> Unit = {},
    val onConnect: (SiteServiceConnectionInputUi) -> Unit = {},
    val onConnectGoogle: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onOpenDetail: (resourceId: String?) -> Unit = {},
    val onOpenExternal: (url: String) -> Unit = {},
    val onOpenCredentialPage: (url: String) -> Unit = {},
    val onRequestDisconnect: () -> Unit = {},
    val onDismissDisconnect: () -> Unit = {},
    val onConfirmDisconnect: () -> Unit = {},
    val onSwitchAccount: (accountId: String) -> Unit = {},
    val onAddAccount: () -> Unit = {},
    val onCancelAddAccount: () -> Unit = {},
    val onRequestRemoveAll: () -> Unit = {},
    val onDismissRemoveAll: () -> Unit = {},
    val onConfirmRemoveAll: () -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onRefreshDetail: () -> Unit = {},
    val onSelectResource: (String) -> Unit = {},
    val onSelectRange: (SiteDetailRangePresetUi) -> Unit = {},
    val onCustomRange: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val onClarityDays: (Int) -> Unit = {},
    val onToggleClarityDimension: (String) -> Unit = {},
    val onShowRawResponses: (Boolean) -> Unit = {},
)

/**
 * Full route for one site service: connect form, overview with resources and search, refresh,
 * cached/offline states, confirmed disconnect, and the Pro-gated detail workspace.
 */
@Composable
fun SiteServiceRoute(
    viewModel: SiteServicesViewModel,
    providerId: String,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val uriHandler = LocalUriHandler.current
    var lastHandledSearchRequestId by rememberSaveable(providerId) { mutableIntStateOf(searchRequestId) }
    var searchFocusRequestId by rememberSaveable(providerId) { mutableIntStateOf(0) }
    val detailOpen = state.detail?.providerId == providerId

    DisposableEffect(viewModel, providerId) {
        viewModel.setActiveRoute(providerId)
        onDispose { if (viewModel.uiState.value.activeProviderId == providerId) viewModel.setActiveRoute(null) }
    }
    BackHandler {
        if (!viewModel.handleBack()) onBack()
    }
    // Detail workspaces are Pro: close one restored from saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, detailOpen) {
        if (proAccess.isConfirmedLocked && detailOpen) viewModel.closeDetail()
    }
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            if (viewModel.uiState.value.detail?.providerId == providerId) {
                viewModel.closeDetail()
                withFrameNanos { }
            }
            searchFocusRequestId += 1
        }
    }
    fun openExternal(url: String) {
        if (!SiteServiceCopy.isHttpsUrl(url)) return
        runCatching { uriHandler.openUri(url) }
    }
    SiteServiceScreen(
        state = state,
        providerId = providerId,
        actions = SiteServiceScreenActions(
            onBack = { if (!viewModel.handleBack()) onBack() },
            onConnect = { input -> viewModel.connect(providerId, input) },
            onConnectGoogle = { viewModel.connectGoogle(providerId) },
            onRefresh = { viewModel.refresh(providerId) },
            onCancel = { viewModel.cancelOperation(providerId) },
            onSearchChange = viewModel::updateResourceSearch,
            onOpenDetail = { resourceId -> proAccess.requestPro { viewModel.openDetail(providerId, resourceId) } },
            onOpenExternal = { url -> proAccess.requestPro { openExternal(url) } },
            onOpenCredentialPage = ::openExternal,
            onRequestDisconnect = { viewModel.requestDisconnectConfirmation(providerId) },
            onDismissDisconnect = { viewModel.dismissDisconnectConfirmation(providerId) },
            onConfirmDisconnect = { viewModel.confirmDisconnect(providerId) },
            onSwitchAccount = { accountId -> viewModel.switchAccount(providerId, accountId) },
            onAddAccount = { viewModel.startAddingAccount(providerId) },
            onCancelAddAccount = { viewModel.cancelAddingAccount(providerId) },
            onRequestRemoveAll = { viewModel.requestRemoveAllConfirmation(providerId) },
            onDismissRemoveAll = { viewModel.dismissRemoveAllConfirmation(providerId) },
            onConfirmRemoveAll = { viewModel.confirmRemoveAll(providerId) },
            onCloseDetail = viewModel::closeDetail,
            onRefreshDetail = viewModel::refreshDetail,
            onSelectResource = viewModel::selectDetailResource,
            onSelectRange = viewModel::selectDetailRange,
            onCustomRange = { start, end -> viewModel.setCustomDetailRange(start, end) },
            onClarityDays = viewModel::setClarityDays,
            onToggleClarityDimension = viewModel::toggleClarityDimension,
            onShowRawResponses = viewModel::showRawResponses,
        ),
        searchFocusRequestId = searchFocusRequestId,
        modifier = modifier,
    )
}

@Composable
fun SiteServiceScreen(
    state: SiteServicesUiState,
    providerId: String,
    actions: SiteServiceScreenActions,
    modifier: Modifier = Modifier,
    searchFocusRequestId: Int = 0,
) {
    val haptic = LocalHapticFeedback.current
    val service = state.service(providerId)
    val provider = remember(providerId) { siteCatalogProvider(providerId) }
    val accent = siteAccent(providerId)
    val detail = state.detail?.takeIf {
        it.providerId == providerId && service.dashboard != null && !service.isAddingAccount
    }
    val usesGoogle = SiteProvider.fromId(providerId)?.usesGoogleOAuth == true

    if (service.showDisconnectConfirmation) {
        SiteAccountRemovalDialog(
            removal = SiteAccountRemoval.CURRENT,
            serviceName = provider.displayName,
            accountTitle = service.accounts.active?.title ?: service.savedAccountName,
            credentialNoun = if (usesGoogle) "Google credential" else "credential",
            enabled = service.operation != SiteServiceOperation.DISCONNECTING,
            onConfirm = actions.onConfirmDisconnect,
            onDismiss = actions.onDismissDisconnect,
            testTag = "siteService.disconnectDialog",
        )
    }
    if (service.showRemoveAllConfirmation) {
        SiteAccountRemovalDialog(
            removal = SiteAccountRemoval.ALL,
            serviceName = provider.displayName,
            accountTitle = null,
            credentialNoun = if (usesGoogle) "Google credential" else "credential",
            enabled = service.operation != SiteServiceOperation.DISCONNECTING,
            onConfirm = actions.onConfirmRemoveAll,
            onDismiss = actions.onDismissRemoveAll,
            testTag = "siteService.removeAllDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("siteService.screen"),
    ) {
        val title = when {
            detail?.showRawResponses == true -> "API response"
            detail != null -> "${provider.displayName} details"
            else -> provider.displayName
        }
        val cancelable = service.operation == SiteServiceOperation.CONNECTING ||
            service.operation == SiteServiceOperation.AUTHORIZING ||
            service.operation == SiteServiceOperation.REFRESHING
        val busy = service.isBusy || (detail != null && (detail.isLoading || detail.isRefreshing))
        val showsAccountMenu = service.isConnected && !service.isAddingAccount && detail == null
        SiteServiceTopBar(
            title = title,
            cancelable = cancelable,
            busy = busy,
            canRefresh = service.isConnected && !service.isAddingAccount,
            refreshLabel = "Refresh ${provider.displayName}",
            accountMenu = if (showsAccountMenu) {
                {
                    SiteAccountMenu(
                        provider = provider,
                        accounts = service.accounts.takeIf { it.accounts.isNotEmpty() }
                            ?: fallbackAccounts(service),
                        addLabel = SiteServiceCopy.addAccountLabel(providerId),
                        onSwitch = actions.onSwitchAccount,
                        onAdd = actions.onAddAccount,
                        onRemoveCurrent = actions.onRequestDisconnect,
                        onRemoveAll = actions.onRequestRemoveAll,
                        testTagPrefix = "siteService",
                        enabled = service.operation == null || service.operation == SiteServiceOperation.REFRESHING,
                    )
                }
            } else {
                null
            },
            onBack = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                actions.onBack()
            },
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                if (detail != null) actions.onRefreshDetail() else actions.onRefresh()
            },
            onCancel = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                actions.onCancel()
            },
        )
        val bodyModifier = Modifier.weight(1f)
        when {
            service.status == SiteServiceConnectionStatus.RESTORING -> SiteLoadingState(
                "Opening saved ${provider.displayName}…",
                accent,
                bodyModifier.testTag("siteService.restoring"),
            )
            service.showsConnectForm ->
                if (usesGoogle) {
                    GoogleConnectionPanel(state, service, providerId, actions, bodyModifier)
                } else {
                    ApiKeyConnectionForm(service, providerId, actions, bodyModifier)
                }
            detail != null -> if (detail.showRawResponses && detail.payload != null) {
                SiteRawResponseExplorer(detail.payload, providerId, bodyModifier)
            } else {
                AppPullToRefresh(
                    isRefreshing = detail.isLoading || detail.isRefreshing,
                    onRefresh = actions.onRefreshDetail,
                    modifier = bodyModifier.fillMaxWidth(),
                    testTag = "siteService.detail.pullToRefresh",
                ) {
                    SiteServiceDetailContent(service, detail, providerId, actions, Modifier.fillMaxSize())
                }
            }
            service.dashboard != null -> AppPullToRefresh(
                isRefreshing = service.operation == SiteServiceOperation.REFRESHING,
                onRefresh = actions.onRefresh,
                enabled = service.operation == null || service.operation == SiteServiceOperation.REFRESHING,
                modifier = bodyModifier.fillMaxWidth(),
                testTag = "siteService.pullToRefresh",
            ) {
                SiteServiceOverview(
                    service = service,
                    dashboard = service.dashboard,
                    search = state.resourceSearch,
                    providerId = providerId,
                    actions = actions,
                    searchFocusRequestId = searchFocusRequestId,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> AppPullToRefresh(
                isRefreshing = service.operation == SiteServiceOperation.REFRESHING,
                onRefresh = actions.onRefresh,
                enabled = service.operation == null || service.operation == SiteServiceOperation.REFRESHING,
                modifier = bodyModifier.fillMaxWidth(),
                testTag = "siteService.recovery.pullToRefresh",
            ) {
                SavedServiceRecovery(service, providerId, actions, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SiteServiceTopBar(
    title: String,
    cancelable: Boolean,
    busy: Boolean,
    canRefresh: Boolean,
    refreshLabel: String,
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
        AppToolbarAction(modifier = Modifier.size(48.dp), onClick = onBack, testTag = "siteService.back") {
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
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = cancelable || (canRefresh && !busy),
            onClick = if (cancelable) onCancel else onRefresh,
            testTag = "siteService.refreshOrCancel",
        ) {
            when {
                cancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Rounded.Refresh, contentDescription = refreshLabel)
            }
        }
    }
}

// MARK: - Connect

@Composable
private fun ConnectionHeader(providerId: String, addingAccount: Boolean = false) {
    val provider = siteCatalogProvider(providerId)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        ProviderMark(provider, size = 72.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            if (addingAccount) "Add a ${provider.displayName} account" else "Connect ${provider.displayName}",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (addingAccount) {
                "Your current account stays connected. Same-identity credentials replace that account in place."
            } else {
                SiteServiceCopy.subtitle(providerId)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** "Back to the saved account" while adding another account (never shown for a first connect). */
@Composable
private fun CancelAddAccountButton(service: SiteServiceState, actions: SiteServiceScreenActions) {
    if (!service.isAddingAccount) return
    ThemedActionButton(
        "BACK TO ${(service.accounts.active?.title ?: service.savedAccountName ?: "SAVED ACCOUNT").uppercase()}",
        onClick = actions.onCancelAddAccount,
        tone = ThemedActionTone.NEUTRAL,
        modifier = Modifier.fillMaxWidth(),
        testTag = "siteService.cancelAddAccount",
    )
}

/** Accounts for the menu when only a dashboard is known (fixtures and restores without an index). */
private fun fallbackAccounts(service: SiteServiceState): SiteAccountsUi {
    val dashboard = service.dashboard
    val id = dashboard?.accountId ?: "current"
    val title = dashboard?.accountName ?: service.savedAccountName ?: return SiteAccountsUi.EMPTY
    return SiteAccountsUi(listOf(SiteAccountOptionUi(id, title, dashboard?.accountDetail)), id)
}

@Composable
private fun GoogleConnectionPanel(
    state: SiteServicesUiState,
    service: SiteServiceState,
    providerId: String,
    actions: SiteServiceScreenActions,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = siteAccent(providerId)
    val readiness = state.googleOAuthReadiness
    val authorizing = service.operation == SiteServiceOperation.AUTHORIZING
    BoxWithConstraints(modifier.fillMaxWidth()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("siteService.googleConnection"),
        contentPadding = SiteLayout.padding(maxWidth, 20.dp, SiteLayout.FormMaxWidth, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("header") { ConnectionHeader(providerId, service.isAddingAccount) }
        service.error?.let { message -> item("error") { SiteErrorPanel("Connection failed", message) } }
        service.notice?.let { message -> item("notice") { SiteNoticePanel("Status", message, accent) } }
        item("panel") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                borderColor = accent.copy(alpha = 0.24f),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        SiteIconTile(Icons.Rounded.Key, accent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("OAuth access is prepared", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when (readiness) {
                                    SiteGoogleOAuthReadinessUi.Ready -> SiteServiceCopy.GOOGLE_READY_MESSAGE
                                    is SiteGoogleOAuthReadinessUi.ConfigurationNeeded -> readiness.message
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        "READ-ONLY ACCESS",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    SiteServiceCopy.oauthCapabilities(providerId).forEach { capability ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(capability, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    ThemedActionButton(
                        "OPEN GOOGLE CLOUD CREDENTIALS",
                        onClick = { actions.onOpenCredentialPage(SiteServiceCopy.GOOGLE_CREDENTIALS_URL) },
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "siteService.credentialPage",
                    )
                    when (readiness) {
                        SiteGoogleOAuthReadinessUi.Ready -> ThemedActionButton(
                            if (authorizing) "WAITING FOR GOOGLE…" else "CONTINUE WITH GOOGLE",
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                actions.onConnectGoogle()
                            },
                            enabled = !service.isBusy,
                            isBusy = authorizing,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "siteService.connectGoogle",
                        )
                        is SiteGoogleOAuthReadinessUi.ConfigurationNeeded -> Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("siteService.configurationNeeded"),
                            color = SiteWarningColor.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.PauseCircle, contentDescription = null)
                                Spacer(Modifier.width(9.dp))
                                Text("GOOGLE SIGN-IN UNAVAILABLE", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        if (service.isAddingAccount) {
            item("cancelAdd") { CancelAddAccountButton(service, actions) }
        }
    }
    }
}

@Composable
private fun ApiKeyConnectionForm(
    service: SiteServiceState,
    providerId: String,
    actions: SiteServiceScreenActions,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val accent = siteAccent(providerId)
    val controller = remember(providerId) { SiteSecretController() }
    var hasCredential by remember(providerId) { mutableStateOf(false) }
    var umamiMode by rememberSaveable(providerId) { mutableStateOf(UmamiSiteAdapter.CLOUD) }
    val fieldValues = remember(providerId) { mutableStateMapOf<String, String>() }
    val connecting = service.operation == SiteServiceOperation.CONNECTING
    val fields = SiteServiceCopy.fields(providerId, umamiMode)
    val values = fieldValues.toMap() + if (providerId == "umami") mapOf(SiteServiceFieldKeys.AUTH_MODE to umamiMode) else emptyMap()
    val canConnect = SiteServiceCopy.canConnect(providerId, hasCredential, values, umamiMode)
    DisposableEffect(controller) { onDispose(controller::clear) }

    fun submit() {
        if (!SiteServiceCopy.canConnect(providerId, hasCredential, values, umamiMode) || service.isBusy) return
        val credential = controller.consume() ?: return
        hasCredential = false
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        actions.onConnect(SiteServiceConnectionInputUi(credential, values))
    }

    // A plain scrolling column (not a lazy list) keeps the native secret field attached while the
    // user scrolls, so a typed credential is never wiped by item recycling.
    // Capped and centered on tablets; on phones the cap is wider than the screen, so it is a no-op.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("siteService.connectionForm")
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = SiteLayout.FormMaxWidth)
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ConnectionHeader(providerId, service.isAddingAccount)
        service.error?.let { message -> SiteErrorPanel("Connection failed", message, "siteService.connectError") }
        service.notice?.let { message -> SiteNoticePanel("Status", message, accent) }
        run {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, borderColor = accent.copy(alpha = 0.24f)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect securely", style = MaterialTheme.typography.titleSmall)
                    StepRow(1, SiteServiceCopy.instructionOne(providerId), accent)
                    StepRow(2, SiteServiceCopy.instructionTwo(providerId), accent)
                    StepRow(3, SiteServiceCopy.INSTRUCTION_THREE, accent)
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            SiteServiceCopy.CREDENTIAL_SECURITY_NOTE,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        run {
            ThemedActionButton(
                "OPEN ${siteCatalogProvider(providerId).displayName.uppercase()} CREDENTIALS",
                onClick = { actions.onOpenCredentialPage(SiteServiceCopy.credentialPageUrl(providerId)) },
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "siteService.credentialPage",
            )
        }
        if (providerId == "umami") {
            run {
                UmamiModePicker(umamiMode, accent) { mode ->
                    umamiMode = mode
                    if (mode == UmamiSiteAdapter.CLOUD) fieldValues.remove(SiteServiceFieldKeys.BASE_URL)
                }
            }
        }
        fields.forEach { field ->
            // Keyed so switching Umami modes never recreates the native secret field.
            key(field.key) {
                if (field.isSecret) {
                    SiteSecretInput(
                        controller = controller,
                        label = field.label,
                        placeholder = field.placeholder,
                        accent = accent,
                        enabled = !connecting,
                        onPresenceChange = { hasCredential = it },
                        onDone = ::submit,
                        testTag = "siteService.credential",
                    )
                } else {
                    ThemedAuthTextField(
                        value = fieldValues[field.key].orEmpty(),
                        onValueChange = { fieldValues[field.key] = it.take(2_048) },
                        label = field.label,
                        enabled = !connecting,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.isUrl) KeyboardType.Uri else KeyboardType.Text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("siteService.field.${field.key}"),
                    )
                }
            }
        }
        run {
            if (connecting) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemedActionButton(
                        "CONNECTING…",
                        onClick = {},
                        isBusy = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ThemedActionButton(
                        "CANCEL REQUEST",
                        onClick = actions.onCancel,
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "siteService.cancel",
                    )
                }
            } else {
                ThemedActionButton(
                    if (service.isAddingAccount) {
                        "ADD ${siteCatalogProvider(providerId).displayName.uppercase()} ACCOUNT"
                    } else {
                        "CONNECT ${siteCatalogProvider(providerId).displayName.uppercase()}"
                    },
                    onClick = ::submit,
                    enabled = canConnect && !service.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "siteService.connect",
                )
                CancelAddAccountButton(service, actions)
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String, accent: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(24.dp)
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UmamiModePicker(selected: String, accent: Color, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(UmamiSiteAdapter.CLOUD to "Umami Cloud", UmamiSiteAdapter.SELF_HOSTED to "Self-hosted").forEach { (mode, label) ->
            SiteChoiceChip(
                label = label,
                selected = selected == mode,
                accent = accent,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f),
                testTag = "siteService.umamiMode.$mode",
            )
        }
    }
}

@Composable
internal fun SiteChoiceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 44.dp)
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.18f).compositeOver(colors.surface) else colors.surface,
        contentColor = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.55f) else colors.outline),
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SiteIconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, size: Int = 42) {
    Box(
        Modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

// MARK: - Overview

/**
 * Service overview. Phones keep one column; regular windows cap the content (iOS
 * `dashboardMaxWidth`) and lay resources out as an adaptive grid, and expanded windows move the
 * summary into a side pane next to the resource list.
 */
@Composable
private fun SiteServiceOverview(
    service: SiteServiceState,
    dashboard: SiteServiceDashboardUi,
    search: String,
    providerId: String,
    actions: SiteServiceScreenActions,
    searchFocusRequestId: Int,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var lastHandledFocus by rememberSaveable(providerId) { mutableIntStateOf(0) }
    val resources = remember(dashboard.resources, search, providerId) {
        SiteServiceFormat.filteredResources(providerId, dashboard.resources, search)
    }
    LaunchedEffect(searchFocusRequestId) {
        if (searchFocusRequestId > 0 && searchFocusRequestId != lastHandledFocus) {
            lastHandledFocus = searchFocusRequestId
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthClass = SiteWidthClass.of(maxWidth)
        val compactPadding = 18.dp
        val spacing = 12.dp
        if (widthClass == SiteWidthClass.EXPANDED) {
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
                        .testTag("siteService.overview.summaryPane"),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    overviewSummaryItems(service, dashboard, providerId, actions, haptic)
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("siteService.overview"),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    overviewResourceItems(service, dashboard, resources, search, providerId, actions, haptic, focusRequester, columns)
                    overviewActionItems(service, providerId, actions, haptic)
                }
            }
        } else {
            val contentWidth = SiteLayout.contentWidth(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth)
            val columns = SiteLayout.columns(maxWidth, contentWidth, minimum = 310.dp, spacing = spacing, maximumColumns = 3)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("siteService.overview"),
                contentPadding = SiteLayout.padding(maxWidth, compactPadding, SiteLayout.DashboardMaxWidth, top = 6.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                overviewSummaryItems(service, dashboard, providerId, actions, haptic)
                overviewResourceItems(service, dashboard, resources, search, providerId, actions, haptic, focusRequester, columns)
                overviewActionItems(service, providerId, actions, haptic)
            }
        }
    }
}

private fun LazyListScope.overviewSummaryItems(
    service: SiteServiceState,
    dashboard: SiteServiceDashboardUi,
    providerId: String,
    actions: SiteServiceScreenActions,
    haptic: HapticFeedback,
) {
    val provider = siteCatalogProvider(providerId)
    item("summary") {
        ServiceSummaryCard(service, dashboard, providerId) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            actions.onOpenDetail(null)
        }
    }
    service.error?.let { message ->
        item("error") {
            SiteErrorPanel("${provider.displayName} could not refresh", message, "siteService.refreshError") {
                ThemedActionButton(
                    "TRY AGAIN",
                    onClick = actions.onRefresh,
                    enabled = !service.isBusy,
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    service.notice?.let { message -> item("notice") { SiteNoticePanel("Saved data", message, siteAccent(providerId)) } }
    if (dashboard.warnings.isNotEmpty()) {
        item("warnings") {
            SiteWarningPanel(
                "${dashboard.warnings.size} data ${if (dashboard.warnings.size == 1) "note" else "notes"}",
                dashboard.warnings.take(3).joinToString("\n"),
                "siteService.warnings",
            )
        }
    }
}

private fun LazyListScope.overviewResourceItems(
    service: SiteServiceState,
    dashboard: SiteServiceDashboardUi,
    resources: List<SiteResourceUi>,
    search: String,
    providerId: String,
    actions: SiteServiceScreenActions,
    haptic: HapticFeedback,
    focusRequester: FocusRequester,
    columns: Int,
) {
    val provider = siteCatalogProvider(providerId)
    val accent = siteAccent(providerId)
    val plural = SiteServiceCopy.resourceNoun(providerId, 2)
    item("search") {
        val keyboard = LocalSoftwareKeyboardController.current
        ControlSearchField(
            value = search,
            onValueChange = actions.onSearchChange,
            placeholder = "Search ${provider.displayName} ${plural.lowercase()}",
            focusRequester = focusRequester,
            onSearch = { keyboard?.hide() },
            modifier = Modifier.fillMaxWidth(),
            testTag = "siteService.search",
        )
    }
    item("heading") { SiteSectionHeader(plural, resources.size, accent) }
    if (resources.isEmpty()) {
        item("empty") {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, testTag = "siteService.empty") {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (search.isBlank()) "No ${plural.lowercase()} returned yet" else "No matching ${plural.lowercase()}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (search.isBlank()) {
                            "${provider.displayName} has not returned any ${plural.lowercase()} yet. Refresh this service or reconnect it."
                        } else {
                            "Nothing matches “$search”."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ThemedActionButton(
                        if (search.isBlank()) "REFRESH SERVICE" else "CLEAR SEARCH",
                        onClick = { if (search.isBlank()) actions.onRefresh() else actions.onSearchChange("") },
                        enabled = !service.isBusy,
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    } else {
        adaptiveGridItems(resources, columns, key = { "resource-${it.id}" }, spacing = 12.dp) { resource ->
            SiteResourceCard(resource, providerId) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                actions.onOpenDetail(resource.id)
            }
        }
    }
    if (dashboard.resourcesTruncatedForDisplay) {
        item("truncated") {
            Text(
                "Showing ${dashboard.resources.size} of ${dashboard.loadedResourceCount} ${plural.lowercase()}. Use search to narrow the list.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun LazyListScope.overviewActionItems(
    service: SiteServiceState,
    providerId: String,
    actions: SiteServiceScreenActions,
    haptic: HapticFeedback,
) {
    val provider = siteCatalogProvider(providerId)
    item("openProvider") {
        ThemedActionButton(
            "OPEN IN ${provider.displayName.uppercase()}",
            onClick = { actions.onOpenExternal(SiteServiceCopy.providerDashboardUrl(providerId)) },
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            testTag = "siteService.openProvider",
        )
    }
    item("disconnect") {
        ThemedActionButton(
            if (service.accounts.hasMultiple) "REMOVE THIS ACCOUNT" else "DISCONNECT ${provider.displayName.uppercase()}",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                actions.onRequestDisconnect()
            },
            enabled = !service.isBusy,
            tone = ThemedActionTone.DESTRUCTIVE,
            modifier = Modifier.fillMaxWidth(),
            testTag = "siteService.disconnect",
        )
    }
}

@Composable
private fun ServiceSummaryCard(
    service: SiteServiceState,
    dashboard: SiteServiceDashboardUi,
    providerId: String,
    onOpen: () -> Unit,
) {
    val provider = siteCatalogProvider(providerId)
    val accent = siteAccent(providerId)
    val status = SiteServiceFormat.serviceStatus(service)
    val metrics = SiteServiceFormat.headlineMetrics(dashboard)
    val count = dashboard.loadedResourceCount
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.24f),
        shadowColor = accent,
        onClick = onOpen,
        testTag = "siteService.summary",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ProviderMark(provider, size = 48.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(provider.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        dashboard.accountName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dashboard.accountDetail?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    SiteServiceCopy.subtitle(providerId),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(status.text, siteToneColor(status.tone))
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                SiteCompactMetric(count.toString(), SiteServiceCopy.resourceNoun(providerId, count))
                metrics.forEach { SiteCompactMetric(SiteServiceFormat.metric(it), it.label, Modifier.weight(1f, fill = false)) }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Open the ${provider.displayName} workspace",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${cacheLabel(dashboard.cacheState)} data · Updated ${formatSiteTimestamp(dashboard.fetchedAtMillis)} · read-only",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SiteResourceCard(resource: SiteResourceUi, providerId: String, onClick: () -> Unit) {
    val provider = siteCatalogProvider(providerId)
    val accent = siteAccent(providerId)
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = accent.copy(alpha = 0.18f),
        shadowColor = MaterialTheme.colorScheme.outline,
        onClick = onClick,
        testTag = "siteService.resource.${resource.id}",
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ProviderMark(provider, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(resource.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        resource.status ?: resource.subtitle ?: "Connected",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Open ${resource.name} in ${provider.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                resource.metrics.take(2).forEach {
                    SiteCompactMetric(SiteServiceFormat.metric(it), it.label, Modifier.weight(1f, fill = false))
                }
                if (resource.metrics.isEmpty() && resource.subtitle != null && resource.status != null) {
                    Text(
                        resource.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedServiceRecovery(
    service: SiteServiceState,
    providerId: String,
    actions: SiteServiceScreenActions,
    modifier: Modifier,
) {
    val provider = siteCatalogProvider(providerId)
    val accent = siteAccent(providerId)
    BoxWithConstraints(modifier.fillMaxWidth()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("siteService.savedUnavailable"),
        contentPadding = SiteLayout.padding(maxWidth, 18.dp, SiteLayout.FormMaxWidth, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface, borderColor = accent.copy(alpha = 0.24f)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusPill("Saved securely", accent)
                    Text(service.savedAccountName ?: "Saved ${provider.displayName} connection", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "The encrypted connection remains on this device. Retry online, switch accounts, or remove it explicitly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    service.error?.let { SiteErrorPanel("Needs attention", it) }
                    service.notice?.let { SiteNoticePanel("Status", it, accent) }
                    ThemedActionButton(
                        if (service.operation == SiteServiceOperation.REFRESHING) "REFRESHING…" else "REFRESH",
                        onClick = actions.onRefresh,
                        enabled = !service.isBusy,
                        isBusy = service.operation == SiteServiceOperation.REFRESHING,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "siteService.recovery.refresh",
                    )
                    ThemedActionButton(
                        if (service.accounts.hasMultiple) "REMOVE THIS ACCOUNT" else "DISCONNECT",
                        onClick = actions.onRequestDisconnect,
                        enabled = !service.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "siteService.disconnect",
                    )
                }
            }
        }
    }
    }
}

/** Opens a resource's own HTTPS page when it has one, else the provider dashboard. */
internal fun externalUrl(providerId: String, resource: SiteResourceUi?): String =
    resource?.url?.takeIf(SiteServiceCopy::isHttpsUrl) ?: SiteServiceCopy.providerDashboardUrl(providerId)
