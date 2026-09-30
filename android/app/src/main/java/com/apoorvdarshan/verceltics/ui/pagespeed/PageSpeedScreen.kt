package com.apoorvdarshan.verceltics.ui.pagespeed

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.pagespeed.PageSpeedMetricUnit
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.lang.ref.WeakReference

private val PageSpeedAccent = Color(0xFF4FBD7A)
private val PageSpeedWarning = Color(0xFFFFD83D)
private val InputShape = RoundedCornerShape(13.dp)

/** Integration-neutral entry point; the app shell can provide its gateway and navigation callback. */
@Composable
fun PageSpeedRoot(
    gateway: PageSpeedUiGateway,
    onBack: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val model: PageSpeedViewModel = viewModel(
        key = "pagespeed-${System.identityHashCode(gateway)}",
        factory = PageSpeedViewModel.Factory(gateway),
    )
    PageSpeedRoute(
        viewModel = model,
        onBack = onBack,
        onConnectionChanged = onConnectionChanged,
        modifier = modifier,
    )
}

@Composable
fun PageSpeedRoute(
    viewModel: PageSpeedViewModel,
    onBack: () -> Unit,
    onConnectionChanged: (Boolean) -> Unit = {},
    searchRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var lastHandledSearchRequestId by rememberSaveable { mutableIntStateOf(searchRequestId) }
    var siteFocusRequestId by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.status) {
        if (state.status != PageSpeedConnectionStatus.RESTORING) {
            onConnectionChanged(state.isConnected)
        }
    }
    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            siteFocusRequestId += 1
        }
    }
    PageSpeedScreen(
        state = state,
        onBack = onBack,
        onConnect = viewModel::connect,
        onRefresh = viewModel::refresh,
        onCancel = viewModel::cancelOperation,
        onRequestDisconnect = viewModel::requestDisconnectConfirmation,
        onDismissDisconnect = viewModel::dismissDisconnectConfirmation,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        searchFocusRequestId = siteFocusRequestId,
        modifier = modifier,
    )
}

@Composable
fun PageSpeedScreen(
    state: PageSpeedUiState,
    onBack: () -> Unit,
    onConnect: (siteUrl: String, apiKey: SecretValue) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    searchFocusRequestId: Int = 0,
    modifier: Modifier = Modifier,
) {
    var siteUrl by rememberSaveable { mutableStateOf(state.savedSiteUrl.orEmpty()) }
    val apiKeyController = remember { EphemeralSecretController() }
    var hasApiKey by remember { mutableStateOf(false) }
    var localFormError by remember { mutableStateOf<String?>(null) }
    var searchNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var lastHandledSearchFocusRequestId by rememberSaveable {
        mutableIntStateOf(searchFocusRequestId)
    }
    val siteUrlFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current

    if (state.status == PageSpeedConnectionStatus.DISCONNECTED) {
        ProtectCredentialWindow()
    }

    DisposableEffect(Unit) {
        onDispose { apiKeyController.clear() }
    }
    LaunchedEffect(state.savedSiteUrl) {
        if (siteUrl.isBlank()) siteUrl = state.savedSiteUrl.orEmpty()
    }
    LaunchedEffect(searchFocusRequestId, state.status) {
        if (
            searchFocusRequestId > 0 &&
            searchFocusRequestId != lastHandledSearchFocusRequestId &&
            state.status != PageSpeedConnectionStatus.RESTORING
        ) {
            lastHandledSearchFocusRequestId = searchFocusRequestId
            if (state.status == PageSpeedConnectionStatus.DISCONNECTED) {
                searchNotice = null
                siteUrlFocusRequester.requestFocus()
                keyboard?.show()
            } else {
                searchNotice = "PageSpeed is a single-site workspace. Disconnect to audit a different HTTPS URL."
            }
        }
    }
    LaunchedEffect(state.status) {
        if (state.status == PageSpeedConnectionStatus.CONNECTED) {
            apiKeyController.clear()
            hasApiKey = false
        }
    }

    if (state.showDisconnectConfirmation) {
        DisconnectDialog(
            onDismiss = onDismissDisconnect,
            onConfirm = onConfirmDisconnect,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("pagespeed.screen"),
    ) {
        PageSpeedTopBar(
            state = state,
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
        searchNotice?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .testTag("pagespeed.searchNotice"),
            ) {
                FeedbackPanel(message = message, isError = false)
            }
        }

        when (state.status) {
            PageSpeedConnectionStatus.RESTORING -> PageSpeedLoading(
                modifier = Modifier.weight(1f),
            )
            PageSpeedConnectionStatus.DISCONNECTED -> ConnectionForm(
                siteUrl = siteUrl,
                onSiteUrlChange = {
                    siteUrl = it
                    localFormError = null
                },
                apiKeyController = apiKeyController,
                hasApiKey = hasApiKey,
                onApiKeyPresenceChange = {
                    hasApiKey = it
                    localFormError = null
                },
                error = localFormError ?: state.error,
                operation = state.operation,
                siteUrlFocusRequester = siteUrlFocusRequester,
                onConnect = {
                    val secret = apiKeyController.consume()
                    hasApiKey = false
                    if (secret == null) {
                        localFormError = "Enter a Google API key."
                    } else if (siteUrl.isBlank()) {
                        localFormError = "Enter a complete HTTPS site URL."
                    } else {
                        val submittedUrl = siteUrl
                        onConnect(submittedUrl, secret)
                    }
                },
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            PageSpeedConnectionStatus.SAVED_UNAVAILABLE -> SavedConnectionRecovery(
                state = state,
                onRefresh = onRefresh,
                onDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
            PageSpeedConnectionStatus.CONNECTED -> Dashboard(
                state = state,
                onRefresh = onRefresh,
                onCancel = onCancel,
                onDisconnect = onRequestDisconnect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PageSpeedTopBar(
    state: PageSpeedUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemedGlassControl(
            modifier = Modifier.size(50.dp),
            onClick = onBack,
            testTag = "pagespeed.back",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            text = "PageSpeed & CrUX",
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val canRefresh = state.status == PageSpeedConnectionStatus.CONNECTED ||
            state.status == PageSpeedConnectionStatus.SAVED_UNAVAILABLE
        ThemedGlassControl(
            modifier = Modifier.size(50.dp),
            onClick = if (
                state.operation == PageSpeedOperation.CONNECTING ||
                state.operation == PageSpeedOperation.REFRESHING
            ) onCancel else onRefresh,
            enabled = canRefresh || state.operation == PageSpeedOperation.CONNECTING,
            testTag = "pagespeed.refreshOrCancel",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state.operation) {
                    PageSpeedOperation.CONNECTING,
                    PageSpeedOperation.REFRESHING,
                    -> Icon(Icons.Rounded.Cancel, contentDescription = "Cancel request")
                    PageSpeedOperation.RESTORING,
                    PageSpeedOperation.DISCONNECTING,
                    -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    null -> Icon(Icons.Rounded.Refresh, contentDescription = "Refresh audit")
                }
            }
        }
    }
}

@Composable
private fun PageSpeedLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pagespeed.loading"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = PageSpeedAccent)
            Spacer(Modifier.height(14.dp))
            Text("Opening saved audit…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionForm(
    siteUrl: String,
    onSiteUrlChange: (String) -> Unit,
    apiKeyController: EphemeralSecretController,
    hasApiKey: Boolean,
    onApiKeyPresenceChange: (Boolean) -> Unit,
    error: String?,
    operation: PageSpeedOperation?,
    siteUrlFocusRequester: FocusRequester,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pagespeed.connectionForm"),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "intro") {
            OffsetPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 184.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PageSpeedProviderMark()
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("AUDIT THIS PAGE", style = MaterialTheme.typography.labelSmall)
                            Text(
                                "Lab speed meets real-user experience",
                                style = MaterialTheme.typography.headlineMedium,
                            )
                        }
                    }
                    Text(
                        "Run mobile and desktop Lighthouse audits, then layer in Chrome UX p75 field data when Google has enough samples.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        item(key = "credentials") {
            OffsetPanel(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("CONNECT GOOGLE APIS", style = MaterialTheme.typography.headlineSmall)
                    BrandedInput(
                        value = siteUrl,
                        onValueChange = onSiteUrlChange,
                        label = "HTTPS site URL",
                        placeholder = "https://example.com/page",
                        icon = Icons.Rounded.Language,
                        keyboardType = KeyboardType.Uri,
                        focusRequester = siteUrlFocusRequester,
                        testTag = "pagespeed.siteUrl",
                    )
                    EphemeralApiKeyInput(
                        controller = apiKeyController,
                        onPresenceChange = onApiKeyPresenceChange,
                        label = "Google API key",
                        placeholder = "Enter API key",
                        testTag = "pagespeed.apiKey",
                        onDone = onConnect,
                    )
                    Text(
                        "Your API key is encrypted, stored only on this device, and excluded from backups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        error?.let { message ->
            item(key = "error") { FeedbackPanel(message = message, isError = true) }
        }
        item(key = "connect") {
            BrandedActionButton(
                text = if (operation == PageSpeedOperation.CONNECTING) "Connecting…" else "Connect and run audit",
                icon = Icons.Rounded.Speed,
                enabled = operation != PageSpeedOperation.CONNECTING && hasApiKey && siteUrl.isNotBlank(),
                onClick = onConnect,
                testTag = "pagespeed.connect",
            )
        }
        if (operation == PageSpeedOperation.CONNECTING) {
            item(key = "cancel") {
                BrandedActionButton(
                    text = "Cancel request",
                    icon = Icons.Rounded.Cancel,
                    containerColor = MaterialTheme.colorScheme.surface,
                    onClick = onCancel,
                    testTag = "pagespeed.cancelConnect",
                )
            }
        }
    }
}

@Composable
private fun SavedConnectionRecovery(
    state: PageSpeedUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pagespeed.savedUnavailable"),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            OffsetPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    StatusPill("Saved securely", PageSpeedAccent)
                    Text(
                        state.savedSiteUrl ?: "Saved PageSpeed connection",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "The encrypted API key remains on this device. A failed or offline refresh will not replace it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.error?.let { FeedbackPanel(it, isError = true) }
                    state.notice?.let { FeedbackPanel(it, isError = false) }
                    BrandedActionButton(
                        text = if (state.operation == PageSpeedOperation.REFRESHING) "Refreshing…" else "Refresh audit",
                        icon = Icons.Rounded.Refresh,
                        enabled = !state.isBusy,
                        onClick = onRefresh,
                        testTag = "pagespeed.recovery.refresh",
                    )
                    if (state.canDisconnect) {
                        BrandedActionButton(
                            text = "Disconnect",
                            icon = Icons.Rounded.DeleteOutline,
                            containerColor = MaterialTheme.colorScheme.surface,
                            enabled = !state.isBusy,
                            onClick = onDisconnect,
                            testTag = "pagespeed.recovery.disconnect",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Dashboard(
    state: PageSpeedUiState,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dashboard = requireNotNull(state.dashboard)
    val metricGroups = listOf(
        MetricGroupUi("MOBILE LAB", "Lighthouse · mobile", "pagespeed.mobile.", PageSpeedAccent),
        MetricGroupUi(
            "DESKTOP LAB",
            "Lighthouse · desktop",
            "pagespeed.desktop.",
            MaterialTheme.colorScheme.primary,
        ),
        MetricGroupUi(
            "FIELD P75",
            "Chrome UX · real users",
            "crux.",
            MaterialTheme.colorScheme.tertiary,
        ),
    )
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pagespeed.dashboard"),
        contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item(key = "hero") { AuditHero(dashboard) }
        item(key = "sources") { SourceRail(dashboard.sources) }
        if (dashboard.cacheState != PageSpeedCacheState.LIVE) {
            item(key = "cache") {
                FeedbackPanel(
                    message = if (dashboard.cacheState == PageSpeedCacheState.CACHED_STALE) {
                        "Showing a saved audit older than 30 minutes. Refresh when you are online."
                    } else {
                        "Showing the most recent saved audit while live data loads on demand."
                    },
                    isError = false,
                )
            }
        }
        dashboard.warnings.forEachIndexed { index, warning ->
            item(key = "warning-$index") {
                WarningPanel(warning)
            }
        }
        state.error?.let { message ->
            item(key = "refresh-error") { FeedbackPanel(message, isError = true) }
        }
        state.notice?.let { message ->
            item(key = "notice") { FeedbackPanel(message, isError = false) }
        }

        metricGroups.forEach { group ->
            val metrics = dashboard.metrics.filter { it.key.startsWith(group.prefix) }
            if (metrics.isNotEmpty()) {
                item(key = group.prefix) { MetricGroupPanel(group, metrics) }
            }
        }

        item(key = "updated") {
            Text(
                "Audited ${formatTimestamp(dashboard.fetchedAtMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandedActionButton(
                    text = if (state.operation == PageSpeedOperation.REFRESHING) "Cancel refresh" else "Refresh audit",
                    icon = if (state.operation == PageSpeedOperation.REFRESHING) Icons.Rounded.Cancel else Icons.Rounded.Refresh,
                    onClick = if (state.operation == PageSpeedOperation.REFRESHING) onCancel else onRefresh,
                    testTag = "pagespeed.dashboard.refresh",
                )
                BrandedActionButton(
                    text = "Disconnect",
                    icon = Icons.Rounded.DeleteOutline,
                    containerColor = MaterialTheme.colorScheme.surface,
                    enabled = !state.isBusy,
                    onClick = onDisconnect,
                    testTag = "pagespeed.dashboard.disconnect",
                )
            }
        }
    }
}

@Composable
private fun AuditHero(dashboard: PageSpeedDashboardUi) {
    val mobileScore = dashboard.metrics.firstOrNull { it.key == "pagespeed.mobile.performance" }
    val desktopScore = dashboard.metrics.firstOrNull { it.key == "pagespeed.desktop.performance" }
    val fieldLcp = dashboard.metrics.firstOrNull { it.key == "crux.largest_contentful_paint" }
    BoxWithConstraints {
        val stacked = shouldStackPageSpeedLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        OffsetPanel(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 224.dp),
            color = MaterialTheme.colorScheme.primary,
            testTag = "pagespeed.hero",
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    PageSpeedProviderMark()
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            dashboard.siteName,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = if (stacked) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            dashboard.siteUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (stacked) 4 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!stacked) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill(dashboard.status, statusColor(dashboard.status))
                    }
                }
                if (stacked) {
                    StatusPill(dashboard.status, statusColor(dashboard.status))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HeroMetric("MOBILE", mobileScore?.let(::formatMetric) ?: "—", Modifier.fillMaxWidth())
                        HeroMetric("DESKTOP", desktopScore?.let(::formatMetric) ?: "—", Modifier.fillMaxWidth())
                        HeroMetric("FIELD LCP", fieldLcp?.let(::formatMetric) ?: "—", Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HeroMetric("MOBILE", mobileScore?.let(::formatMetric) ?: "—", Modifier.weight(1f))
                        HeroMetric("DESKTOP", desktopScore?.let(::formatMetric) ?: "—", Modifier.weight(1f))
                        HeroMetric("FIELD LCP", fieldLcp?.let(::formatMetric) ?: "—", Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 90.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SourceRail(sources: PageSpeedSourcesUi) {
    BoxWithConstraints {
        val stacked = shouldStackPageSpeedLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        OffsetPanel(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            testTag = "pagespeed.sources",
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            Text(
                "AUDIT CHANNELS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
            )
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SourceSegment("MOBILE LAB", sources.mobile, Modifier.fillMaxWidth())
                        SourceSegment("DESKTOP LAB", sources.desktop, Modifier.fillMaxWidth())
                        SourceSegment("CRUX FIELD", sources.crux, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        SourceSegment("MOBILE LAB", sources.mobile, Modifier.weight(1f))
                        SourceSegment("DESKTOP LAB", sources.desktop, Modifier.weight(1f))
                        SourceSegment("CRUX FIELD", sources.crux, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceSegment(
    label: String,
    state: PageSpeedSourceUiState,
    modifier: Modifier = Modifier,
) {
    val available = state == PageSpeedSourceUiState.AVAILABLE
    val color = if (available) PageSpeedAccent else MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "$label ${if (available) "available" else "unavailable"}"
            },
        color = color.copy(alpha = 0.17f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.25.dp, color),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (available) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

private data class MetricGroupUi(
    val eyebrow: String,
    val subtitle: String,
    val prefix: String,
    val accent: Color,
)

@Composable
private fun MetricGroupPanel(group: MetricGroupUi, metrics: List<PageSpeedMetricUi>) {
    BoxWithConstraints {
        val stacked = shouldStackPageSpeedLayout(
            availableWidthDp = maxWidth.value,
            fontScale = LocalDensity.current.fontScale,
        )
        OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "pagespeed.metrics.${group.prefix}",
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(6.dp)
                        .height(38.dp)
                        .background(group.accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(group.eyebrow, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        group.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
                if (stacked) {
                    metrics.forEach { metric ->
                        MetricTile(metric, group.accent, Modifier.fillMaxWidth())
                    }
                } else {
                    metrics.chunked(2).forEach { rowMetrics ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowMetrics.forEach { metric ->
                                MetricTile(metric, group.accent, Modifier.weight(1f))
                            }
                            if (rowMetrics.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: PageSpeedMetricUi,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 92.dp),
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(formatMetric(metric), style = MaterialTheme.typography.headlineSmall)
            Text(
                metric.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun FeedbackPanel(message: String, isError: Boolean) {
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.13f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = accent,
        shadowColor = MaterialTheme.colorScheme.outline,
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = message
                },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = accent,
            )
            Spacer(Modifier.width(10.dp))
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WarningPanel(message: String) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = PageSpeedWarning,
        borderColor = MaterialTheme.colorScheme.outline,
        shadowColor = MaterialTheme.colorScheme.outline,
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PARTIAL AUDIT", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                Text(message, color = Color.Black, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EphemeralApiKeyInput(
    controller: EphemeralSecretController,
    onPresenceChange: (Boolean) -> Unit,
    label: String,
    placeholder: String,
    testTag: String,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
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
            shape = InputShape,
            border = BorderStroke(
                if (LocalVercelticsDarkTheme.current) 1.dp else 2.dp,
                colors.outline,
            ),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                AndroidView(
                    factory = { context ->
                        EditText(context).apply {
                            background = null
                            setSingleLine(true)
                            setSaveEnabled(false)
                            importantForAutofill = EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                            inputType = InputType.TYPE_CLASS_TEXT or
                                InputType.TYPE_TEXT_VARIATION_PASSWORD
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            textSize = 16f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            setPadding(0, 0, 0, 0)
                            hint = placeholder
                            contentDescription = label
                            addTextChangedListener(
                                object : TextWatcher {
                                    override fun beforeTextChanged(
                                        value: CharSequence?,
                                        start: Int,
                                        count: Int,
                                        after: Int,
                                    ) = Unit

                                    override fun onTextChanged(
                                        value: CharSequence?,
                                        start: Int,
                                        before: Int,
                                        count: Int,
                                    ) {
                                        onPresenceChange(!value.isNullOrBlank())
                                    }

                                    override fun afterTextChanged(value: Editable?) = Unit
                                },
                            )
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
                            contentDescription = label
                            password()
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

private class EphemeralSecretController {
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

    override fun toString(): String = "EphemeralSecretController(value=<redacted>)"
}

@Composable
private fun ProtectCredentialWindow() {
    val context = LocalView.current.context
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        val wasAlreadySecure = window?.let {
            it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        } ?: false
        if (!wasAlreadySecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (!wasAlreadySecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun BrandedInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isSecret: Boolean = false,
    testTag: String,
    onDone: () -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
            ),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                .compositeOver(MaterialTheme.colorScheme.surface),
            shape = InputShape,
            border = BorderStroke(
                if (LocalVercelticsDarkTheme.current) 1.dp else 2.dp,
                MaterialTheme.colorScheme.outline,
            ),
            tonalElevation = 0.dp,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
                    .testTag(testTag)
                    .semantics {
                        contentDescription = label
                        if (isSecret) password()
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = if (isSecret) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                decorationBox = { inner ->
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun BrandedActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    testTag: String,
) {
    val haptic = LocalHapticFeedback.current
    OffsetPanel(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .alpha(if (enabled) 1f else 0.48f),
        color = containerColor,
        onClick = if (enabled) {
            {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
        } else {
            null
        },
        testTag = testTag,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { role = Role.Button },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PageSpeedProviderMark() {
    val provider = remember { checkNotNull(IntegrationCatalog.provider("pageSpeed")) }
    Surface(
        modifier = Modifier.size(56.dp),
        color = Color.Black,
        contentColor = PageSpeedAccent,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.padding(13.dp), contentAlignment = Alignment.Center) {
            ProviderLogo(provider = provider, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DisconnectDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        OffsetPanel(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            testTag = "pagespeed.disconnectDialog",
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text("Disconnect PageSpeed & CrUX?", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "This removes the encrypted Google API key and saved audit from this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BrandedActionButton(
                    text = "Disconnect",
                    icon = Icons.Rounded.DeleteOutline,
                    containerColor = MaterialTheme.colorScheme.error,
                    onClick = onConfirm,
                    testTag = "pagespeed.disconnect.confirm",
                )
                BrandedActionButton(
                    text = "Keep connection",
                    icon = Icons.Rounded.CheckCircle,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    onClick = onDismiss,
                    testTag = "pagespeed.disconnect.dismiss",
                )
            }
        }
    }
}

private fun formatMetric(metric: PageSpeedMetricUi): String {
    metric.formattedValue?.takeIf(String::isNotBlank)?.let { return it }
    return when (metric.unit) {
        PageSpeedMetricUnit.SCORE -> "%.0f".format(Locale.US, metric.value)
        PageSpeedMetricUnit.MILLISECONDS ->
            "${NumberFormat.getIntegerInstance().format(metric.value)} ms"
        PageSpeedMetricUnit.RATIO -> "%.3f".format(Locale.US, metric.value)
    }
}

private fun formatTimestamp(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMillis))

internal fun shouldStackPageSpeedLayout(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 340f || fontScale >= 1.3f

private fun statusColor(status: String): Color = when (status.lowercase(Locale.ROOT)) {
    "good" -> PageSpeedAccent
    "needs work" -> PageSpeedWarning
    "poor" -> Color(0xFFE5484D)
    else -> PageSpeedAccent
}
