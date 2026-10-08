package com.apoorvdarshan.verceltics.ui.cloudflare

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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Domain
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareCredential
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.ThemedAuthTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.components.ThemedModalBottomSheet
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.lang.ref.WeakReference
import com.apoorvdarshan.verceltics.ui.billing.LocalProAccess
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.clip
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareAdvancedSection
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareAdvancedToolsUi
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareToolsHost
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareToolsRouteEffects
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.CloudflareToolsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.cloudflareAdvancedTools
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.cloudflareToolsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.tools.rememberCloudflareToolsViewModel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsHost

private val CloudflareAccent = Color(0xFFF26B14)
private val CloudflareSuccess = Color(0xFF35C86F)
private val CloudflareWarning = Color(0xFFFFD83D)

@Composable
fun CloudflareRoute(
    viewModel: CloudflareViewModel,
    onBack: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
    toolsViewModel: CloudflareToolsViewModel? = null,
    // Hook for the Cloudflare storage port ("Storage & databases"); the Advanced row is hidden while null.
    onOpenStorage: ((accountId: String) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val operationsStack by viewModel.operationsNavigator.stack.collectAsStateWithLifecycle()
    val proAccess = LocalProAccess.current
    val tools = toolsViewModel ?: rememberCloudflareToolsViewModel(viewModel)
    val toolsState by tools.uiState.collectAsStateWithLifecycle()
    var lastHandledSearchRequestId by rememberSaveable { mutableIntStateOf(0) }
    val routeBack = {
        if (!viewModel.handleBack()) onBack()
    }
    // Zone, Pages and Worker details and every operations screen are Pro: close ones restored from
    // saved state once access is locked.
    LaunchedEffect(proAccess.isConfirmedLocked, state.selectedResource, operationsStack.isEmpty()) {
        if (proAccess.isConfirmedLocked && (state.selectedResource != null || operationsStack.isNotEmpty())) {
            viewModel.closeResource()
        }
    }
    CloudflareToolsRouteEffects(tools, toolsState.isOpen, state, proAccess)
    val detailTitle = operationsStack.lastOrNull()?.title
        ?: state.selectedResource?.let { selection -> resourceDisplayName(state, selection) }
    val showsOperations = detailTitle != null && state.status == CloudflareConnectionStatus.CONNECTED &&
        state.dashboard != null
    DisposableEffect(viewModel) {
        viewModel.setRouteVisible(true)
        onDispose { viewModel.setRouteVisible(false) }
    }
    BackHandler(onBack = routeBack)
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            if (state.selectedResource != null || operationsStack.isNotEmpty()) viewModel.closeResource()
            tools.closeAll()
        }
    }
    // Keeps the dashboard's scroll position and search while a Cloudflare tool is on top.
    val dashboardStateHolder = rememberSaveableStateHolder()
    if (toolsState.isOpen && state.status == CloudflareConnectionStatus.CONNECTED && state.dashboard != null) {
        CloudflareToolsHost(viewModel = tools, context = cloudflareToolsContext(state), modifier = modifier)
        return
    }
    dashboardStateHolder.SaveableStateProvider("cloudflare.dashboard") { CloudflareScreen(
        state = state,
        onBack = routeBack,
        onConnect = viewModel::connect,
        onRefresh = {
            if (showsOperations) viewModel.operationsNavigator.requestRefresh() else viewModel.refresh()
        },
        onCancel = viewModel::cancelOperation,
        onSelectAccount = viewModel::selectAccount,
        onOpenResource = { kind, id -> proAccess.requestPro { viewModel.openResource(kind, id) } },
        onRequestDisconnect = viewModel::requestDisconnectConfirmation,
        onDismissDisconnect = viewModel::dismissDisconnectConfirmation,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        searchRequestId = searchRequestId,
        modifier = modifier,
        advancedTools = cloudflareAdvancedTools(
            tools,
            state,
            proAccess,
            onOpenStorage ?: { _ -> viewModel.openStorage(); Unit },
        ),
        detailTitle = detailTitle,
        detailContent = { contentModifier ->
            CloudflareOperationsHost(
                navigator = viewModel.operationsNavigator,
                client = viewModel.operationsClient,
                dashboard = state.dashboard,
                selectedResource = state.selectedResource,
                onCloseResource = viewModel::closeResource,
                onInventoryChanged = viewModel::refresh,
                modifier = contentModifier,
            )
        },
    ) }
}

private fun resourceDisplayName(state: CloudflareUiState, selection: CloudflareResourceSelection): String {
    val inventory = state.dashboard?.inventory
    return when (selection.kind) {
        CloudflareResourceKind.ZONE -> inventory?.zones?.firstOrNull { it.id == selection.id }?.name
        CloudflareResourceKind.PAGES -> inventory?.pagesProjects?.firstOrNull { it.id == selection.id }?.name
        CloudflareResourceKind.WORKER -> inventory?.workers?.firstOrNull { it.id == selection.id }?.id
    } ?: resourceTitle(selection.kind)
}

@Composable
fun CloudflareScreen(
    state: CloudflareUiState,
    onBack: () -> Unit,
    onConnect: (CloudflareCredential) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onOpenResource: (CloudflareResourceKind, String) -> Unit,
    onRequestDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
    advancedTools: CloudflareAdvancedToolsUi? = null,
    /** Title for the open operations screen; null when the dashboard is showing. */
    detailTitle: String? = null,
    /** Live zone/Pages/Worker/storage operations; tests and previews fall back to read-only detail. */
    detailContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val showsOperations = detailContent != null && detailTitle != null &&
        state.status == CloudflareConnectionStatus.CONNECTED && state.dashboard != null
    if (state.showDisconnectConfirmation) {
        ThemedAlertDialog(
            title = "Disconnect Cloudflare?",
            message = "The encrypted Cloudflare credential and saved Cloudflare inventory will be removed from this device.",
            confirmText = "DISCONNECT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            enabled = state.operation != CloudflareOperation.DISCONNECTING,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmDisconnect()
            },
            onDismissRequest = onDismissDisconnect,
            testTag = "cloudflare.disconnectDialog",
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("cloudflare.screen"),
    ) {
        CloudflareTopBar(
            title = when {
                showsOperations -> requireNotNull(detailTitle)
                state.selectedResource == null -> "Cloudflare"
                else -> resourceTitle(state.selectedResource.kind)
            },
            operation = state.operation,
            canRefresh = state.isConnected,
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
        )

        when {
            state.status == CloudflareConnectionStatus.RESTORING -> CloudflareLoading(
                "Opening saved Cloudflare workspace…",
                Modifier.weight(1f),
            )
            state.status == CloudflareConnectionStatus.DISCONNECTED -> CloudflareConnectionForm(
                state = state,
                onConnect = onConnect,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            state.status == CloudflareConnectionStatus.SAVED_UNAVAILABLE -> CloudflareSavedRecovery(
                state = state,
                onRefresh = onRefresh,
                onDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
            showsOperations -> requireNotNull(detailContent)(Modifier.weight(1f))
            state.selectedResource != null -> CloudflareResourceDetail(
                state = state,
                modifier = Modifier.weight(1f),
            )
            else -> CloudflareDashboard(
                state = state,
                onSelectAccount = onSelectAccount,
                onOpenResource = onOpenResource,
                onDisconnect = onRequestDisconnect,
                searchRequestId = searchRequestId,
                modifier = Modifier.weight(1f),
                advancedTools = advancedTools,
            )
        }
    }
}

@Composable
fun CloudflareConnectionCard(
    state: CloudflareUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val provider = remember { checkNotNull(IntegrationCatalog.provider("cloudflare")) }
    val inventory = state.dashboard?.inventory
    val subtitle = inventory?.let {
        val freshness = state.dashboard?.cacheState?.let(::cacheLabel) ?: "Saved"
        "${it.loadedZoneCount} zones · ${it.loadedWorkerCount} workers · $freshness data"
    } ?: state.savedProfile?.displayName ?: state.error ?: "Saved connection"
    val status = when (state.status) {
        CloudflareConnectionStatus.CONNECTED -> if (
            state.error != null || state.dashboard?.isPartial == true ||
            state.dashboard?.warnings?.isNotEmpty() == true ||
            state.dashboard?.inventory?.warnings?.isNotEmpty() == true
        ) "Attention" else "Connected"
        CloudflareConnectionStatus.SAVED_UNAVAILABLE -> "Attention"
        CloudflareConnectionStatus.RESTORING -> "Restoring"
        CloudflareConnectionStatus.DISCONNECTED -> "Disconnected"
    }
    val haptic = LocalHapticFeedback.current
    val stacked = shouldStackCloudflareConnectionCard(LocalDensity.current.fontScale)
    OffsetPanel(
        modifier = modifier.heightIn(min = 88.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = CloudflareAccent.copy(alpha = 0.20f),
        shadowColor = CloudflareAccent,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = "workspace.hosting.cloudflareConnection",
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
                    StatusPill(
                        status,
                        if (status == "Attention") CloudflareWarning else CloudflareSuccess,
                    )
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
                Text(provider.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(
                status,
                if (status == "Attention") CloudflareWarning else CloudflareSuccess,
            )
        }
    }
}

internal fun shouldStackCloudflareConnectionCard(fontScale: Float): Boolean = fontScale >= 1.3f

@Composable
private fun CloudflareTopBar(
    title: String,
    operation: CloudflareOperation?,
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
            testTag = "cloudflare.back",
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
        val isCancelable = operation == CloudflareOperation.CONNECTING ||
            operation == CloudflareOperation.REFRESHING ||
            operation == CloudflareOperation.SWITCHING_ACCOUNT
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = isCancelable || (canRefresh && operation == null),
            onClick = if (isCancelable) onCancel else onRefresh,
            testTag = "cloudflare.refreshOrCancel",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isCancelable -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                    operation != null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Cloudflare")
                }
            }
        }
    }
}

@Composable
private fun CloudflareLoading(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = CloudflareAccent)
            Spacer(Modifier.height(14.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS Cloudflare credential help, shared by the connection form and its tests. */
internal object CloudflareConnectCopy {
    const val API_TOKENS_URL: String = "https://dash.cloudflare.com/profile/api-tokens"

    fun title(mode: CloudflareAuthMode): String = when (mode) {
        CloudflareAuthMode.GLOBAL_API_KEY -> "Connect with Global API Key"
        CloudflareAuthMode.API_TOKEN -> "Connect with scoped API token"
    }

    fun steps(mode: CloudflareAuthMode): List<String> = when (mode) {
        CloudflareAuthMode.GLOBAL_API_KEY -> listOf(
            "Open Cloudflare My Profile → API Tokens",
            "In API Keys, tap View beside Global API Key",
            "Complete identity verification",
            "Paste your login email and key below",
        )
        CloudflareAuthMode.API_TOKEN -> listOf(
            "Open Cloudflare My Profile → API Tokens",
            "Create a custom token with the product permissions you need",
            "Include Account Read so the app can discover your accounts",
            "Paste the token below",
        )
    }

    fun storageNote(mode: CloudflareAuthMode): String = when (mode) {
        CloudflareAuthMode.GLOBAL_API_KEY ->
            "Encrypted and stored only on this device. The Global API Key has the same Cloudflare access as your user, including write access."
        CloudflareAuthMode.API_TOKEN ->
            "Encrypted and stored only on this device. The app can only use permissions and resources included in this token."
    }

    fun missingSecret(mode: CloudflareAuthMode): String = when (mode) {
        CloudflareAuthMode.GLOBAL_API_KEY -> "Enter your Cloudflare Global API Key."
        CloudflareAuthMode.API_TOKEN -> "Enter a Cloudflare API token."
    }
}

@Composable
private fun CloudflareConnectionForm(
    state: CloudflareUiState,
    onConnect: (CloudflareCredential) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember { CloudflareEphemeralTokenController() }
    // iOS opens the Cloudflare form on the Global API Key mode.
    var mode by rememberSaveable { mutableStateOf(CloudflareAuthMode.GLOBAL_API_KEY) }
    // The login email is not a secret; the key or token only lives in the ephemeral EditText.
    var email by rememberSaveable { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    DisposableEffect(Unit) { onDispose(controller::clear) }
    LaunchedEffect(state.status) {
        if (state.status == CloudflareConnectionStatus.CONNECTED) {
            controller.clear()
            hasToken = false
        }
    }
    val emailIsValid = CloudflareCredential.validateEmail(email) == null
    val canConnect = hasToken && (mode == CloudflareAuthMode.API_TOKEN || emailIsValid)
    fun submit() {
        if (mode == CloudflareAuthMode.GLOBAL_API_KEY) {
            CloudflareCredential.validateEmail(email)?.let {
                localError = it
                return
            }
        }
        val secret = controller.consume() ?: run {
            localError = CloudflareConnectCopy.missingSecret(mode)
            return
        }
        hasToken = false
        onConnect(
            when (mode) {
                CloudflareAuthMode.GLOBAL_API_KEY -> CloudflareCredential.GlobalApiKey(email, secret)
                CloudflareAuthMode.API_TOKEN -> CloudflareCredential.ApiToken(secret)
            },
        )
    }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.connectionForm"),
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
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProviderMark(checkNotNull(IntegrationCatalog.provider("cloudflare")), size = 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CLOUDFLARE", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                            Text(
                                "Zones, Pages, and Workers",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                    CloudflareAuthModePicker(
                        selected = mode,
                        enabled = state.operation != CloudflareOperation.CONNECTING,
                        onSelect = { selected ->
                            if (selected != mode) {
                                controller.clear()
                                hasToken = false
                                localError = null
                                mode = selected
                            }
                        },
                    )
                    Text(
                        CloudflareConnectCopy.title(mode),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("cloudflare.connect.title"),
                    )
                    CloudflareConnectCopy.steps(mode).forEachIndexed { index, step ->
                        CloudflareStepRow(number = index + 1, text = step)
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = CloudflareAccent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(
                            CloudflareConnectCopy.storageNote(mode),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item("open-tokens") {
            ThemedActionButton(
                "OPEN CLOUDFLARE API TOKENS",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    runCatching { uriHandler.openUri(CloudflareConnectCopy.API_TOKENS_URL) }
                },
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.openApiTokens",
            )
        }
        item("token") {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("CONNECT CLOUDFLARE", style = MaterialTheme.typography.headlineSmall)
                    if (mode == CloudflareAuthMode.GLOBAL_API_KEY) {
                        ThemedAuthTextField(
                            value = email,
                            onValueChange = {
                                email = it.take(320)
                                localError = null
                            },
                            label = "Cloudflare login email",
                            enabled = state.operation != CloudflareOperation.CONNECTING,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                                autoCorrectEnabled = false,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cloudflare.email"),
                        )
                    }
                    key(mode) {
                        CloudflareEphemeralTokenInput(
                            controller = controller,
                            label = if (mode == CloudflareAuthMode.GLOBAL_API_KEY) "GLOBAL API KEY" else "SCOPED API TOKEN",
                            hintText = if (mode == CloudflareAuthMode.GLOBAL_API_KEY) "Paste Global API Key" else "Paste scoped API token",
                            accessibilityName = if (mode == CloudflareAuthMode.GLOBAL_API_KEY) {
                                "Cloudflare Global API Key"
                            } else {
                                "Cloudflare scoped API token"
                            },
                            testTag = if (mode == CloudflareAuthMode.GLOBAL_API_KEY) "cloudflare.globalKey" else "cloudflare.token",
                            onPresenceChange = {
                                hasToken = it
                                localError = null
                            },
                            onDone = ::submit,
                        )
                    }
                    (localError ?: state.error)?.let { CloudflareFeedback(it, true) }
                    state.notice?.let { CloudflareFeedback(it, false) }
                    if (state.operation == CloudflareOperation.CONNECTING) {
                        ThemedActionButton(
                            "CANCEL REQUEST",
                            onClick = onCancel,
                            tone = ThemedActionTone.NEUTRAL,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "cloudflare.cancel",
                        )
                    } else {
                        ThemedActionButton(
                            "CONNECT SECURELY",
                            enabled = canConnect,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                submit()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "cloudflare.connect",
                        )
                    }
                }
            }
        }
    }
}

/** iOS segmented `Picker("Authentication")`: Global key first, then API token. */
@Composable
private fun CloudflareAuthModePicker(
    selected: CloudflareAuthMode,
    enabled: Boolean,
    onSelect: (CloudflareAuthMode) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CloudflareAuthMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Surface(
                    onClick = { onSelect(mode) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .testTag(
                            if (mode == CloudflareAuthMode.GLOBAL_API_KEY) "cloudflare.authMode.globalKey" else "cloudflare.authMode.apiToken",
                        )
                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                        },
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.dp, CloudflareAccent) else null,
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                        Text(
                            mode.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** iOS `StepRow`: a numbered connection step. */
@Composable
private fun CloudflareStepRow(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(CloudflareAccent.copy(alpha = 0.14f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelSmall, color = CloudflareAccent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CloudflareSavedRecovery(
    state: CloudflareUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OffsetPanel(Modifier.fillMaxWidth(), MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusPill("Saved securely", CloudflareAccent)
                    Text(
                        state.savedProfile?.displayName ?: "Saved Cloudflare account",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "The encrypted connection remains on this device. Retry online or remove it explicitly.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let { CloudflareFeedback(it, true) }
                    state.notice?.let { CloudflareFeedback(it, false) }
                    ThemedActionButton(
                        if (state.operation == CloudflareOperation.REFRESHING) "REFRESHING…" else "REFRESH",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onRefresh()
                        },
                        enabled = !state.isBusy,
                        isBusy = state.operation == CloudflareOperation.REFRESHING,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "cloudflare.recovery.refresh",
                    )
                    ThemedActionButton(
                        "DISCONNECT",
                        onClick = onDisconnect,
                        enabled = !state.isBusy,
                        tone = ThemedActionTone.DESTRUCTIVE,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "cloudflare.disconnect",
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudflareDashboard(
    state: CloudflareUiState,
    onSelectAccount: (String) -> Unit,
    onOpenResource: (CloudflareResourceKind, String) -> Unit,
    onDisconnect: () -> Unit,
    searchRequestId: Int,
    modifier: Modifier = Modifier,
    advancedTools: CloudflareAdvancedToolsUi? = null,
) {
    val dashboard = requireNotNull(state.dashboard)
    val inventory = dashboard.inventory
    var query by rememberSaveable(dashboard.selectedAccountId) { mutableStateOf("") }
    var showAccountPicker by rememberSaveable { mutableStateOf(false) }
    var lastHandledSearchRequestId by rememberSaveable(dashboard.selectedAccountId) {
        mutableIntStateOf(0)
    }
    val haptic = LocalHapticFeedback.current
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            searchFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    if (showAccountPicker) {
        CloudflareAccountPicker(
            dashboard = dashboard,
            enabled = !state.isBusy,
            onDismiss = { showAccountPicker = false },
            onSelect = { id ->
                showAccountPicker = false
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onSelectAccount(id)
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.dashboard"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            val openAccount = advancedTools?.onOpenAccount
            if (openAccount == null) {
                CloudflareCommandCard(dashboard, hasOperationError = state.error != null)
            } else {
                // iOS account header tap: opens account details (Pro-gated by the route).
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClickLabel = "Open account details", role = Role.Button) {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            openAccount()
                        }
                        .testTag("cloudflare.summary.openAccount"),
                ) {
                    CloudflareCommandCard(dashboard, hasOperationError = state.error != null)
                }
            }
        }
        if (dashboard.accounts.size > 1) {
            item("account-picker") {
                ThemedActionButton(
                    text = "ACCOUNT  ·  ${dashboard.selectedAccount?.name ?: "SELECT"}",
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        showAccountPicker = true
                    },
                    enabled = !state.isBusy,
                    tone = ThemedActionTone.NEUTRAL,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "cloudflare.accountPicker",
                )
            }
        }
        state.error?.let { item("error") { CloudflareFeedback(it, true) } }
        state.notice?.let { item("notice") { CloudflareFeedback(it, false) } }
        // iOS `CloudflareWriteNotice`: writes are available and always confirmed.
        item("write-notice") { CloudflareWriteNotice() }
        if (dashboard.isPartial || dashboard.warnings.isNotEmpty() || inventory?.warnings?.isNotEmpty() == true) {
            item("warning") {
                CloudflareWarningPanel(inventoryDisclosure(dashboard))
            }
        }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search Cloudflare inventory",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.search",
                focusRequester = searchFocusRequester,
            )
        }
        inventory?.let { loaded ->
            val zones = loaded.zones.filter { it.matches(query) }
            item("heading-zones") {
                CloudflareListHeading("Zones", zones.size, loaded.loadedZoneCount, "cloudflare.section.zones")
            }
            if (zones.isEmpty()) item("empty-zones") { CloudflareEmpty(query, "zones") }
            items(zones, key = { "zone-${it.id}" }) { zone ->
                CloudflareResourceRow(
                    title = zone.name,
                    subtitle = listOfNotNull(zone.planName, zone.type).joinToString(" · ").ifBlank { "Cloudflare zone" },
                    status = if (zone.isActive) "Active" else zone.status ?: "Unknown",
                    icon = Icons.Rounded.Public,
                    testTag = "cloudflare.zone.${zone.id}",
                    onClick = { onOpenResource(CloudflareResourceKind.ZONE, zone.id) },
                )
            }
            val pages = loaded.pagesProjects.filter { it.matches(query) }
            item("heading-pages") {
                CloudflareListHeading("Pages projects", pages.size, loaded.loadedPagesProjectCount, "cloudflare.section.pages")
            }
            if (pages.isEmpty()) item("empty-pages") { CloudflareEmpty(query, "Pages projects") }
            items(pages, key = { "pages-${it.id}" }) { project ->
                CloudflareResourceRow(
                    title = project.name,
                    subtitle = project.domains.firstOrNull() ?: project.subdomain ?: "Cloudflare Pages",
                    status = project.latestDeploymentStatus ?: "Project",
                    icon = Icons.Rounded.Description,
                    testTag = "cloudflare.pages.${project.id}",
                    onClick = { onOpenResource(CloudflareResourceKind.PAGES, project.id) },
                )
            }
            val workers = loaded.workers.filter { it.matches(query) }
            item("heading-workers") {
                CloudflareListHeading("Workers", workers.size, loaded.loadedWorkerCount, "cloudflare.section.workers")
            }
            if (workers.isEmpty()) item("empty-workers") { CloudflareEmpty(query, "Workers") }
            items(workers, key = { "worker-${it.id}" }) { worker ->
                CloudflareResourceRow(
                    title = worker.id,
                    subtitle = listOfNotNull(
                        worker.handlers.joinToString(", ").ifBlank { null },
                        worker.routes.size.takeIf { it > 0 }?.let { if (it == 1) "1 route" else "$it routes" },
                    ).joinToString(" · ").ifBlank { "Worker script" },
                    status = if (worker.hasModules == true) "Modules" else "Script",
                    icon = Icons.Rounded.Code,
                    testTag = "cloudflare.worker.${worker.id}",
                    onClick = { onOpenResource(CloudflareResourceKind.WORKER, worker.id) },
                )
            }
        } ?: item("no-account") {
            CloudflareWarningPanel("This Cloudflare credential returned no accessible account inventory.")
        }
        advancedTools?.let { tools -> item("advanced") { CloudflareAdvancedSection(tools) } }
        item("disconnect") {
            ThemedActionButton(
                "DISCONNECT CLOUDFLARE",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDisconnect()
                },
                tone = ThemedActionTone.DESTRUCTIVE,
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.disconnect",
            )
        }
    }
}

@Composable
private fun CloudflareCommandCard(dashboard: CloudflareDashboardUi, hasOperationError: Boolean) {
    val inventory = dashboard.inventory
    val attention = hasOperationError || dashboard.isPartial || dashboard.warnings.isNotEmpty() || inventory?.warnings?.isNotEmpty() == true
    ProviderSummaryCard(
        provider = requireNotNull(IntegrationCatalog.provider("cloudflare")),
        title = dashboard.selectedAccount?.name ?: dashboard.profile.displayName,
        subtitle = cloudflareCredentialSubtitle(dashboard.profile),
        status = if (attention) "Attention" else "Connected",
        statusColor = if (attention) CloudflareWarning else CloudflareSuccess,
        detail = "${cacheLabel(dashboard.cacheState)} data",
        testTag = "cloudflare.summary",
    ) {
        if (LocalDensity.current.fontScale >= 1.45f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudflareMetric("${inventory?.loadedZoneCount ?: 0}", "Zones", Modifier.fillMaxWidth())
                CloudflareMetric("${inventory?.loadedPagesProjectCount ?: 0}", "Pages", Modifier.fillMaxWidth())
                CloudflareMetric("${inventory?.loadedWorkerCount ?: 0}", "Workers", Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudflareMetric("${inventory?.loadedZoneCount ?: 0}", "Zones", Modifier.weight(1f))
                CloudflareMetric("${inventory?.loadedPagesProjectCount ?: 0}", "Pages", Modifier.weight(1f))
                CloudflareMetric("${inventory?.loadedWorkerCount ?: 0}", "Workers", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CloudflareMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CloudflareAccountPicker(
    dashboard: CloudflareDashboardUi,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ThemedModalBottomSheet(
        onDismissRequest = onDismiss,
        testTag = "cloudflare.accountSheet",
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag("cloudflare.accountList"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item("title") {
                Text(
                    "CLOUDFLARE ACCOUNTS",
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            items(dashboard.accounts, key = CloudflareAccountUi::id) { account ->
                val isSelected = account.id == dashboard.selectedAccountId
                Surface(
                    onClick = { onSelect(account.id) },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 5.dp)
                        .testTag("cloudflare.account.${account.id}")
                        .semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                    color = if (isSelected) {
                        CloudflareAccent.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) CloudflareAccent else MaterialTheme.colorScheme.outline,
                    ),
                    shape = RoundedCornerShape(13.dp),
                    tonalElevation = 0.dp,
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Domain, contentDescription = null, tint = CloudflareAccent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            account.type?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (isSelected) StatusPill("Current", CloudflareSuccess)
                    }
                }
            }
            if (dashboard.accountsTruncatedForDisplay) {
                item("truncation") {
                    Text(
                        "Showing ${dashboard.accounts.size} of ${dashboard.loadedAccountCount} accounts.",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudflareListHeading(label: String, visible: Int, loaded: Int, testTag: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            if (visible == loaded) visible.toString() else "$visible of $loaded",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun CloudflareResourceRow(
    title: String,
    subtitle: String,
    status: String,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = CloudflareAccent.copy(alpha = 0.20f),
        shadowColor = MaterialTheme.colorScheme.outline,
        shadowOffset = 3.dp,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        testTag = testTag,
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = CloudflareAccent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
                contentColor = CloudflareAccent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(status.uppercase(), color = CloudflareAccent, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Open $title")
            }
        }
    }
}

@Composable
private fun CloudflareResourceDetail(state: CloudflareUiState, modifier: Modifier = Modifier) {
    val dashboard = requireNotNull(state.dashboard)
    val selection = requireNotNull(state.selectedResource)
    val inventory = dashboard.inventory
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("cloudflare.resourceDetail"),
        contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (selection.kind) {
            CloudflareResourceKind.ZONE -> inventory?.zones?.firstOrNull { it.id == selection.id }?.let { zone ->
                item("hero") {
                    CloudflareDetailHero(
                        "ZONE",
                        zone.name,
                        Icons.Rounded.Public,
                        if (zone.isActive) "Active" else zone.status,
                        if (zone.isActive) CloudflareSuccess else CloudflareWarning,
                    )
                }
                item("status") { CloudflareKeyValue("Status", zone.status ?: "Not reported") }
                item("plan") { CloudflareKeyValue("Plan", zone.planName ?: "Not reported") }
                item("type") { CloudflareKeyValue("Type", zone.type ?: "Not reported") }
                item("account") { CloudflareKeyValue("Account", zone.accountName ?: dashboard.selectedAccount?.name ?: "Not reported") }
                item("paused") { CloudflareKeyValue("Paused", zone.paused?.let { if (it) "Yes" else "No" } ?: "Not reported") }
                item("id") { CloudflareKeyValue("Zone ID", zone.id) }
            }
            CloudflareResourceKind.PAGES -> inventory?.pagesProjects?.firstOrNull { it.id == selection.id }?.let { project ->
                item("hero") {
                    CloudflareDetailHero(
                        "PAGES PROJECT",
                        project.name,
                        Icons.Rounded.Description,
                        project.latestDeploymentStatus,
                        deploymentStatusColor(project.latestDeploymentStatus),
                    )
                }
                item("status") { CloudflareKeyValue("Latest deployment", project.latestDeploymentStatus ?: "Not reported") }
                item("branch") { CloudflareKeyValue("Production branch", project.productionBranch ?: "Not reported") }
                project.subdomain?.let { item("subdomain") { CloudflareKeyValue("Subdomain", it) } }
                if (project.domains.isEmpty()) {
                    item("domains-empty") { CloudflareKeyValue("Domains", "No domains returned") }
                } else {
                    items(project.domains, key = { "domain-$it" }) { domain -> CloudflareKeyValue("Domain", domain) }
                }
                item("id") { CloudflareKeyValue("Project ID", project.id) }
            }
            CloudflareResourceKind.WORKER -> inventory?.workers?.firstOrNull { it.id == selection.id }?.let { worker ->
                item("hero") {
                    CloudflareDetailHero(
                        "WORKER SCRIPT",
                        worker.id,
                        Icons.Rounded.Code,
                        if (worker.hasModules == true) "Modules" else "Script",
                        CloudflareAccent,
                    )
                }
                item("modified") { CloudflareKeyValue("Modified", worker.modifiedOn ?: "Not reported") }
                item("compatibility") { CloudflareKeyValue("Compatibility date", worker.compatibilityDate ?: "Not reported") }
                item("assets") { CloudflareKeyValue("Static assets", worker.hasAssets?.let { if (it) "Included" else "None" } ?: "Not reported") }
                item("modules") { CloudflareKeyValue("Module worker", worker.hasModules?.let { if (it) "Yes" else "No" } ?: "Not reported") }
                item("handlers") { CloudflareKeyValue("Handlers", worker.handlers.joinToString(", ").ifBlank { "None reported" }) }
            }
        }
        item("disclosure") {
            Text(
                "Read-only Cloudflare data fetched for ${dashboard.selectedAccount?.name ?: "this account"}. No mutation controls are available.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CloudflareDetailHero(eyebrow: String, title: String, icon: ImageVector, status: String?, statusColor: Color) {
    OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, borderColor = CloudflareAccent.copy(alpha = 0.20f)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(CloudflareAccent.copy(alpha = 0.10f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = CloudflareAccent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            status?.let { StatusPill(it, statusColor) }
        }
    }
}

@Composable
private fun CloudflareKeyValue(label: String, value: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
        color = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.outline,
        shadowOffset = 2.dp,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), color = CloudflareAccent, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CloudflareFeedback(message: String, isError: Boolean) {
    val accent = if (isError) MaterialTheme.colorScheme.error else CloudflareAccent
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        color = accent.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent,
        shadowColor = accent,
        shadowOffset = 2.dp,
    ) {
        Text(message, Modifier.padding(13.dp), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CloudflareWarningPanel(message: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = CloudflareWarning,
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
private fun CloudflareEmpty(query: String, label: String) {
    Text(
        if (query.isBlank()) "Cloudflare returned no $label for this account." else "No $label match “$query”.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CloudflareEphemeralTokenInput(
    controller: CloudflareEphemeralTokenController,
    onPresenceChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    label: String = "SCOPED API TOKEN",
    hintText: String = "Enter Cloudflare token",
    accessibilityName: String = "Cloudflare scoped API token",
    testTag: String = "cloudflare.token",
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
            ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            color = colors.primary.copy(alpha = 0.07f).compositeOver(colors.surface),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(if (LocalVercelticsDarkTheme.current) 1.dp else 2.dp, colors.outline),
            tonalElevation = 0.dp,
        ) {
            Row(Modifier.padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = CloudflareAccent)
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
                            hint = hintText
                            contentDescription = accessibilityName
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
                        .testTag(testTag)
                        .semantics {
                            contentDescription = accessibilityName
                            password()
                            this[SemanticsProperties.EditableText] = AnnotatedString("")
                            this[SemanticsActions.RequestFocus] = AccessibilityAction(
                                label = "Focus $accessibilityName",
                                action = controller::requestFocus,
                            )
                            this[SemanticsActions.SetText] = AccessibilityAction(
                                label = "Enter $accessibilityName",
                                action = { value -> controller.replace(value.text) },
                            )
                            this[SemanticsActions.InsertTextAtCursor] = AccessibilityAction(
                                label = "Type $accessibilityName",
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

private class CloudflareEphemeralTokenController {
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
                editText.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(editText, 0)
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
}

private fun CloudflareZoneUi.matches(query: String): Boolean = query.isBlank() ||
    name.contains(query, ignoreCase = true) ||
    planName?.contains(query, ignoreCase = true) == true ||
    status?.contains(query, ignoreCase = true) == true

private fun CloudflarePagesProjectUi.matches(query: String): Boolean = query.isBlank() ||
    name.contains(query, ignoreCase = true) ||
    subdomain?.contains(query, ignoreCase = true) == true ||
    domains.any { it.contains(query, ignoreCase = true) }


/**
 * The summary card's credential line. iOS labels the connection with `email ?? "Scoped API token"`
 * and the dashboard is writable (every write is confirmed), so it never claims to be read-only.
 */
internal fun cloudflareCredentialSubtitle(profile: CloudflareProfileUi): String = when (profile.authMode) {
    CloudflareAuthMode.GLOBAL_API_KEY -> "${profile.credentialLabel} · Global API Key · writes confirmed"
    CloudflareAuthMode.API_TOKEN -> "Scoped API token ${profile.tokenStatus.lowercase()} · writes confirmed"
}

private fun resourceTitle(kind: CloudflareResourceKind): String = when (kind) {
    CloudflareResourceKind.ZONE -> "Zone details"
    CloudflareResourceKind.PAGES -> "Pages details"
    CloudflareResourceKind.WORKER -> "Worker details"
}

private fun cacheLabel(cacheState: CloudflareCacheState): String = when (cacheState) {
    CloudflareCacheState.LIVE -> "Live"
    CloudflareCacheState.CACHED_FRESH -> "Saved"
    CloudflareCacheState.CACHED_STALE -> "Stale"
}

@Composable
private fun deploymentStatusColor(status: String?): Color = when (status?.lowercase()) {
    "success", "active", "ready", "deployed" -> CloudflareSuccess
    "failed", "failure", "error", "canceled", "cancelled" -> MaterialTheme.colorScheme.error
    else -> CloudflareWarning
}

private fun inventoryDisclosure(dashboard: CloudflareDashboardUi): String = buildList {
    if (!dashboard.accountsComplete) add("The account list is incomplete.")
    if (dashboard.accountsTruncatedForDisplay) {
        add("Showing ${dashboard.accounts.size} of ${dashboard.loadedAccountCount} accounts.")
    }
    dashboard.inventory?.let { inventory ->
        if (!inventory.zonesComplete) add("Zone inventory is incomplete.")
        if (!inventory.pagesComplete) add("Pages inventory is incomplete.")
        if (!inventory.workersComplete) add("Worker inventory is incomplete.")
        if (inventory.zonesTruncatedForDisplay) add("Showing ${inventory.zones.size} of ${inventory.loadedZoneCount} zones.")
        if (inventory.pagesTruncatedForDisplay) add("Showing ${inventory.pagesProjects.size} of ${inventory.loadedPagesProjectCount} Pages projects.")
        if (inventory.workersTruncatedForDisplay) add("Showing ${inventory.workers.size} of ${inventory.loadedWorkerCount} Workers.")
        addAll(inventory.warnings)
    }
    addAll(dashboard.warnings)
}.distinct().joinToString(" ").ifBlank { "Some Cloudflare inventory may be incomplete." }
