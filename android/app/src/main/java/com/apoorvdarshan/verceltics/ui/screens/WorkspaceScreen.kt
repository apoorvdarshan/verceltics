package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import kotlinx.coroutines.flow.first
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl

/**
 * Native Android counterpart to the SwiftUI workspace roots.
 *
 * Connection state stays outside this view. The app shell can supply truthful connected content
 * without coupling the catalog UI to provider storage or network code.
 */
@Composable
fun WorkspaceScreen(
    workspace: Workspace,
    onConnectProvider: (IntegrationProvider) -> Unit,
    onAccountAction: () -> Unit,
    modifier: Modifier = Modifier,
    persistenceError: String? = null,
    connectedContent: (@Composable () -> Unit)? = null,
    searchRequestId: Int = 0,
    connectedProviderIds: Set<String> = emptySet(),
) {
    var showsConnectionCatalog by rememberSaveable(workspace.id) { mutableStateOf(false) }
    var selectedCategoryId by rememberSaveable(workspace.id) { mutableStateOf(workspace.id) }
    var lastHandledSearchRequestId by rememberSaveable(workspace.id) { mutableIntStateOf(0) }
    var catalogFocusRequestId by rememberSaveable(workspace.id) { mutableIntStateOf(0) }
    val selectedCategory = Workspace.entries.firstOrNull { it.id == selectedCategoryId } ?: workspace

    LaunchedEffect(searchRequestId) {
        if (searchRequestId > 0 && searchRequestId != lastHandledSearchRequestId) {
            lastHandledSearchRequestId = searchRequestId
            selectedCategoryId = workspace.id
            showsConnectionCatalog = true
            catalogFocusRequestId += 1
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("workspace.${workspace.id}"),
    ) {
        WorkspaceTopBar(
            workspace = workspace,
            onAccountAction = {
                selectedCategoryId = workspace.id
                showsConnectionCatalog = true
                onAccountAction()
            },
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(
                    if (connectedContent == null) {
                        "workspace.${workspace.id}.empty"
                    } else {
                        "workspace.${workspace.id}.connected"
                    },
                ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 24.dp),
            verticalArrangement = if (connectedContent == null) {
                Arrangement.Center
            } else {
                Arrangement.spacedBy(16.dp)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (persistenceError != null) {
                item(key = "persistence-error") {
                    PersistenceErrorBanner(
                        workspace = workspace,
                        message = persistenceError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (connectedContent == null) {
                item(key = "empty-state") {
                    WorkspaceEmptyState(
                        workspace = workspace,
                        onConnect = {
                            selectedCategoryId = workspace.id
                            showsConnectionCatalog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                    )
                }
            } else {
                item(key = "connected-content") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                    ) {
                        connectedContent()
                    }
                }
                item(key = "additional-connection") {
                    WorkspaceAdditionalConnectionState(
                        workspace = workspace,
                        onConnect = {
                            selectedCategoryId = workspace.id
                            showsConnectionCatalog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 560.dp),
                    )
                }
            }
        }
    }

    if (showsConnectionCatalog) {
        Dialog(
            onDismissRequest = { showsConnectionCatalog = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().testTag("connection.catalog"),
                color = MaterialTheme.colorScheme.background,
            ) {
                ConnectionCatalog(
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategoryId = it.id },
                    onDismiss = { showsConnectionCatalog = false },
                    focusRequestId = catalogFocusRequestId,
                    connectedProviderIds = connectedProviderIds,
                    onProviderSelected = { provider ->
                        showsConnectionCatalog = false
                        onConnectProvider(provider)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceAdditionalConnectionState(
    workspace: Workspace,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = workspaceAdditionalConnectionCopy(workspace)
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = copy.title,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = copy.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConnect()
            },
            modifier = Modifier
                .heightIn(min = 46.dp)
                .testTag("workspace.${workspace.id}.connect")
                .semantics { role = Role.Button },
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = copy.actionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTopBar(
    workspace: Workspace,
    onAccountAction: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val usesAdaptiveLayout = usesAdaptiveWorkspaceTopBar(
            availableWidthDp = maxWidth.value,
            fontScale = fontScale,
        )
        val accountAction = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onAccountAction()
        }

        if (usesAdaptiveLayout) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceAccountControl(
                    workspace = workspace,
                    onClick = accountAction,
                    modifier = Modifier
                        .width(68.dp)
                        .height(50.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = workspace.displayName,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Start,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 70.dp)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                WorkspaceAccountControl(
                    workspace = workspace,
                    onClick = accountAction,
                    modifier = Modifier
                        .width(68.dp)
                        .height(50.dp)
                        .align(Alignment.CenterStart),
                )

                Text(
                    text = workspace.displayName,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 78.dp)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Pure layout policy so accessibility and narrow-window behavior can be regression tested. */
internal fun usesAdaptiveWorkspaceTopBar(
    availableWidthDp: Float,
    fontScale: Float,
): Boolean = availableWidthDp < 340f || fontScale >= 1.3f

@Composable
private fun WorkspaceAccountControl(
    workspace: Workspace,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThemedGlassControl(
        modifier = modifier,
        onClick = onClick,
        testTag = "workspace.${workspace.id}.account",
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Manage ${workspace.displayName.lowercase()} accounts"
                    role = Role.Button
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.primary,
                border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.primary),
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = workspaceIcon(workspace),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceEmptyState(
    workspace: Workspace,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = workspaceEmptyCopy(workspace)
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier.padding(horizontal = 18.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                .compositeOver(MaterialTheme.colorScheme.surface),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.primary),
            tonalElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = workspaceIcon(workspace),
                    contentDescription = null,
                    modifier = Modifier.size(29.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = copy.title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = copy.message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConnect()
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 154.dp)
                .testTag("workspace.${workspace.id}.connect")
                .semantics { role = Role.Button },
            shape = RoundedCornerShape(13.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = copy.actionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PersistenceErrorBanner(
    workspace: Workspace,
    message: String,
    modifier: Modifier = Modifier,
) {
    OffsetPanel(
        modifier = modifier.heightIn(min = 104.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        borderColor = MaterialTheme.colorScheme.error,
        shadowColor = MaterialTheme.colorScheme.error,
        shadowOffset = 4.dp,
        testTag = "workspace.${workspace.id}.persistenceError",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                contentColor = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = persistenceErrorTitle(workspace),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCatalog(
    selectedCategory: Workspace,
    onCategorySelected: (Workspace) -> Unit,
    onDismiss: () -> Unit,
    focusRequestId: Int,
    connectedProviderIds: Set<String>,
    onProviderSelected: (IntegrationProvider) -> Unit,
) {
    val providers = IntegrationCatalog.providers(selectedCategory)
    var query by rememberSaveable(selectedCategory.id) { mutableStateOf("") }
    var lastHandledFocusRequestId by rememberSaveable(selectedCategory.id) {
        mutableIntStateOf(0)
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    val visibleProviders = remember(providers, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            providers
        } else {
            providers.filter { provider ->
                provider.displayName.contains(normalized, ignoreCase = true) ||
                    provider.description.contains(normalized, ignoreCase = true) ||
                    provider.authenticationModes.any { mode ->
                        mode.displayName.contains(normalized, ignoreCase = true)
                    }
            }
        }
    }

    LaunchedEffect(focusRequestId) {
        if (focusRequestId > 0 && focusRequestId != lastHandledFocusRequestId) {
            // The full-screen dialog must own window focus before it can open the keyboard.
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            lastHandledFocusRequestId = focusRequestId
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Connect an integration",
                modifier = Modifier.weight(1f).semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("connection.catalog.close")) {
                Icon(Icons.Rounded.Close, contentDescription = "Close integration picker")
            }
        }
        ConnectionCategoryPicker(
            selectedCategory = selectedCategory,
            onSelected = onCategorySelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        ControlSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search ${selectedCategory.displayName.lowercase()}",
            focusRequester = focusRequester,
            onSearch = { keyboard?.hide() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 20.dp),
            testTag = "connection.catalog.search",
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .testTag("connection.catalog.${selectedCategory.id}"),
            contentPadding = PaddingValues(start = 18.dp, top = 24.dp, end = 18.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "section-heading") {
                Text(
                    text = connectionSectionTitle(selectedCategory),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics { heading() },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                    ),
                )
            }
            items(visibleProviders, key = IntegrationProvider::id) { provider ->
                val isSupported = provider.id in SupportedProviderIds
                ConnectionProviderRow(
                    provider = provider,
                    isSupported = isSupported,
                    isConnected = isSupported && provider.id in connectedProviderIds,
                    onClick = { onProviderSelected(provider) },
                )
            }
            if (visibleProviders.isEmpty()) {
                item(key = "no-results") {
                    Text(
                        text = "No ${selectedCategory.displayName.lowercase()} integrations match “$query”.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionProviderRow(
    provider: IntegrationProvider,
    isSupported: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val accent = Color(provider.accentColor)
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("provider.${provider.id}")
            .semantics {
                when {
                    !isSupported -> {
                        contentDescription = "${provider.displayName}. Planned integration"
                    }
                    isConnected -> {
                        contentDescription = "${provider.displayName}. Connected. Open integration"
                        stateDescription = "Connected"
                    }
                }
            },
        enabled = isSupported,
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMark(
                provider = provider,
                size = 42.dp,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) {
                        "Open ${provider.displayName}"
                    } else {
                        "Connect ${provider.displayName}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = provider.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isConnected) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Surface(
                        modifier = Modifier.testTag("provider.${provider.id}.connected"),
                        shape = RoundedCornerShape(10.dp),
                        color = ConnectedProviderGreen.copy(alpha = 0.18f)
                            .compositeOver(MaterialTheme.colorScheme.surface),
                        contentColor = ConnectedProviderGreen,
                        border = BorderStroke(1.dp, ConnectedProviderGreen),
                        tonalElevation = 0.dp,
                    ) {
                        Text(
                            text = "CONNECTED",
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.4.sp,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else if (isSupported) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "Open ${provider.displayName} connection",
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = "PLANNED",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp,
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCategoryPicker(
    selectedCategory: Workspace,
    onSelected: (Workspace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val fontScale = LocalDensity.current.fontScale
    val usesStackedLayout = usesStackedConnectionCategoryLayout(fontScale)
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .testTag("connection.categoryPicker"),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
    ) {
        if (usesStackedLayout) {
            Column(Modifier.fillMaxWidth()) {
                Workspace.entries.forEach { category ->
                    ConnectionCategoryButton(
                        category = category,
                        isSelected = category == selectedCategory,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelected(category)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        showsIcon = true,
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Workspace.entries.forEach { category ->
                    ConnectionCategoryButton(
                        category = category,
                        isSelected = category == selectedCategory,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelected(category)
                        },
                        modifier = Modifier.weight(1f),
                        showsIcon = false,
                    )
                }
            }
        }
    }
}

internal fun usesStackedConnectionCategoryLayout(fontScale: Float): Boolean = fontScale >= 1.3f

@Composable
private fun ConnectionCategoryButton(
    category: Workspace,
    isSelected: Boolean,
    onClick: () -> Unit,
    showsIcon: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { if (!isSelected) onClick() },
        modifier = modifier
            .heightIn(min = 50.dp)
            .testTag("connection.category.${category.id}")
            .semantics {
                contentDescription = category.displayName
                role = Role.Tab
                selected = isSelected
            },
        shape = RoundedCornerShape(13.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.outline) else null,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showsIcon) {
                Icon(
                    imageVector = workspaceIcon(category),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                text = category.displayName,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private data class WorkspaceEmptyCopy(
    val title: String,
    val message: String,
    val actionTitle: String,
)

private fun workspaceAdditionalConnectionCopy(workspace: Workspace): WorkspaceEmptyCopy =
    when (workspace) {
        Workspace.HOSTING -> WorkspaceEmptyCopy(
            title = "Add another hosting account",
            message = "Keep each provider in its own focused dashboard.",
            actionTitle = "Connect another",
        )
        Workspace.REGISTRARS -> WorkspaceEmptyCopy(
            title = "Add another registrar",
            message = "Connect another registrar without replacing your saved account.",
            actionTitle = "Connect another",
        )
        Workspace.SITES -> WorkspaceEmptyCopy(
            title = "Add another site service",
            message = "Connect search, analytics, uptime, or another performance provider.",
            actionTitle = "Connect another",
        )
    }

private fun workspaceEmptyCopy(workspace: Workspace): WorkspaceEmptyCopy = when (workspace) {
    Workspace.HOSTING -> WorkspaceEmptyCopy(
        title = "No hosting account",
        message = "Connect a hosting platform to see projects, deployments, logs, domains, and analytics.",
        actionTitle = "Connect hosting",
    )

    Workspace.REGISTRARS -> WorkspaceEmptyCopy(
        title = "No registrar account",
        message = "Connect a registrar to track expiry, renewal, privacy, locks, and nameservers.",
        actionTitle = "Connect registrar",
    )

    Workspace.SITES -> WorkspaceEmptyCopy(
        title = "Connect a site service",
        message = "View search, analytics, performance, and uptime providers in separate focused dashboards.",
        actionTitle = "Connect a service",
    )
}

private fun persistenceErrorTitle(workspace: Workspace): String = when (workspace) {
    Workspace.HOSTING -> "Saved hosting accounts need attention"
    Workspace.REGISTRARS -> "Saved registrar accounts need attention"
    Workspace.SITES -> "Saved site services need attention"
}

private fun connectionSectionTitle(workspace: Workspace): String = when (workspace) {
    Workspace.HOSTING -> "CONNECT A HOSTING PLATFORM"
    Workspace.REGISTRARS -> "CONNECT A REGISTRAR"
    Workspace.SITES -> "CONNECT A SITE SERVICE"
}

private val SupportedProviderIds = setOf(
    "vercel",
    "cloudflare",
    "netlify",
    "pageSpeed",
    "googleSearchConsole",
)

private val ConnectedProviderGreen = Color(0xFF2E9E58)
