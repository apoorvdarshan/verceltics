package com.apoorvdarshan.verceltics.ui.screens

import androidx.compose.ui.text.style.TextAlign
import com.apoorvdarshan.verceltics.ui.components.ProviderSummaryCard
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.AppToolbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.domain.AuthenticationModeMetadata
import com.apoorvdarshan.verceltics.domain.CredentialField
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.VercelAccountUi
import com.apoorvdarshan.verceltics.ui.VercelConnectionStatus
import com.apoorvdarshan.verceltics.ui.VercelConnectionMutation
import com.apoorvdarshan.verceltics.ui.vercel.ProtectVercelCredentialWindow
import com.apoorvdarshan.verceltics.ui.vercel.VercelConnectErrorNotice
import com.apoorvdarshan.verceltics.ui.vercel.VercelSavedAccountsList
import com.apoorvdarshan.verceltics.ui.vercel.VercelTokenConnectForm
import com.apoorvdarshan.verceltics.ui.VercelConnectionUiState
import com.apoorvdarshan.verceltics.ui.VercelConnectionViewModel
import com.apoorvdarshan.verceltics.ui.VercelProjectUi
import com.apoorvdarshan.verceltics.ui.components.LabelChip
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.SectionHeading
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedGlassControl
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.contrastingContentColor
import com.apoorvdarshan.verceltics.ui.vercel.visibleVercelProjects
import java.text.DateFormat
import java.util.Date

@Composable
fun ProviderDetailScreen(
    provider: IntegrationProvider,
    vercelConnectionViewModel: VercelConnectionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (provider.id == "vercel") ProtectVercelCredentialWindow()
    LazyColumn(
        modifier = modifier.testTag("providerDetail.${provider.id}"),
        contentPadding = PaddingValues(start = 18.dp, top = 14.dp, end = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            DetailHeader(provider = provider, onBack = onBack)
        }
        item(key = "hero") {
            ProviderHero(provider)
        }
        if (provider.id == "vercel") {
            item(key = "vercel") {
                VercelConnectionPanel(
                    vercelConnectionViewModel = vercelConnectionViewModel,
                )
            }
        } else {
            item(key = "placeholder") {
                ProviderConnectPlaceholder(provider)
            }
        }
        item(key = "authHeading") {
            SectionHeading(
                eyebrow = "Secure connection",
                title = "Authentication",
            )
        }
        items(provider.authenticationModes, key = AuthenticationModeMetadata::id) { mode ->
            AuthenticationCard(mode)
        }
    }
}

@Composable
private fun DetailHeader(provider: IntegrationProvider, onBack: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    AppToolbar(title = provider.displayName, leading = {
        AppToolbarAction(onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onBack() }, testTag = "providerDetail.back") {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
    })
}

@Composable
private fun ProviderHero(provider: IntegrationProvider) {
    OffsetPanel(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, borderColor = Color(provider.accentColor).copy(alpha = 0.20f), testTag = "providerDetail.hero") {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderMark(provider, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(provider.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(provider.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ProviderConnectPlaceholder(provider: IntegrationProvider) {
    OffsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 176.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        testTag = "providerDetail.connectPlaceholder",
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.onSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Connection unavailable", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${provider.displayName} connections are not available in this version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ThemedActionButton(
                text = "CONNECT ${provider.displayName.uppercase()}",
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AuthenticationCard(mode: AuthenticationModeMetadata) {
    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text(mode.displayName, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    mode.requiredFields.isEmpty() -> "Provider sign-in; no credential is pasted into the app."
                    else -> "Required: ${mode.requiredFields.joinToString { credentialName(it) }}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (mode.optionalFields.isNotEmpty()) {
                Text(
                    text = "Optional: ${mode.optionalFields.joinToString { credentialName(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            mode.notes?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun VercelConnectionPanel(
    vercelConnectionViewModel: VercelConnectionViewModel,
) {
    val state by vercelConnectionViewModel.uiState.collectAsStateWithLifecycle()
    var showDisconnectConfirmation by rememberSaveable { mutableStateOf(false) }
    var projectQuery by rememberSaveable { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val isSaved = state.status == VercelConnectionStatus.CONNECTED ||
        state.status == VercelConnectionStatus.SAVED_UNAVAILABLE
    val isAddingAccount = state.isAddingAccount && isSaved

    if (showDisconnectConfirmation) {
        ThemedAlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            title = state.activeAccount?.let { "Remove ${it.displayName}?" } ?: "Disconnect Vercel?",
            message = "The saved token will be removed from this device only.",
            confirmText = "REMOVE ACCOUNT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                showDisconnectConfirmation = false
                projectQuery = ""
                vercelConnectionViewModel.removeCurrentAccount()
            },
            testTag = "vercel.disconnectDialog",
        )
    }

    OffsetPanel(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "vercel.connection",
    ) {
        Column(Modifier.padding(18.dp)) {
            when {
                state.status == VercelConnectionStatus.RESTORING ->
                    RestoringVercelConnectionContent()

                // Adding keeps every saved account; the same identity only has its token rotated.
                isAddingAccount -> {
                    VercelTokenConnectForm(
                        title = "Add Vercel account",
                        subtitle = state.activeAccount?.let { "${it.displayName} stays connected." }
                            ?: "Use a personal access token. It never appears again after saving.",
                        isBusy = state.mutation == VercelConnectionMutation.CONNECTING,
                        error = state.connectError,
                        onConnect = vercelConnectionViewModel::connect,
                    )
                    Spacer(Modifier.height(8.dp))
                    ThemedActionButton(
                        text = "CANCEL",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vercelConnectionViewModel.cancelAddingAccount()
                        },
                        enabled = state.mutation != VercelConnectionMutation.CONNECTING,
                        tone = ThemedActionTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "vercel.cancelAddAccount",
                    )
                }

                state.status == VercelConnectionStatus.CONNECTED && state.dashboard != null ->
                    ConnectedVercelContent(
                        state = state,
                        projectQuery = projectQuery,
                        onProjectQueryChange = { projectQuery = it },
                        onRefresh = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vercelConnectionViewModel.refresh()
                        },
                        onSwitchAccount = { account -> vercelConnectionViewModel.switchAccount(account.id) },
                        onAddAccount = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            vercelConnectionViewModel.startAddingAccount()
                        },
                        onDisconnect = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            showDisconnectConfirmation = true
                        },
                    )

                state.status == VercelConnectionStatus.SAVED_UNAVAILABLE ->
                    SavedVercelUnavailableContent(
                        account = state.savedAccount,
                        accounts = state.savedAccounts,
                        loading = state.isBusy,
                        onRetry = vercelConnectionViewModel::refresh,
                        onSwitchAccount = { account -> vercelConnectionViewModel.switchAccount(account.id) },
                        onAddAccount = vercelConnectionViewModel::startAddingAccount,
                        onDisconnect = { showDisconnectConfirmation = true },
                    )

                else -> VercelTokenConnectForm(
                    title = "Connect Vercel",
                    subtitle = "Use a personal access token. It never appears again after saving.",
                    isBusy = state.isBusy,
                    error = state.error,
                    onConnect = vercelConnectionViewModel::connect,
                )
            }

            if (isSaved && !isAddingAccount) {
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    VercelConnectErrorNotice(it)
                }
            }
        }
    }
}

@Composable
private fun RestoringVercelConnectionContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .testTag("vercel.restoring")
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text("Restoring saved Vercel account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Checking the encrypted account on this device…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavedVercelUnavailableContent(
    account: VercelAccountUi?,
    accounts: List<VercelAccountUi>,
    loading: Boolean,
    onRetry: () -> Unit,
    onSwitchAccount: (VercelAccountUi) -> Unit,
    onAddAccount: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    StatusPill(text = "Saved securely", color = MaterialTheme.colorScheme.tertiary)
    Spacer(Modifier.height(10.dp))
    Text(
        account?.displayName ?: "Saved Vercel account",
        style = MaterialTheme.typography.headlineMedium,
    )
    account?.email?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "The encrypted account remains on this device. Its live dashboard could not be loaded; adding another account keeps it saved.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemedActionButton(
            text = if (loading) "RETRYING…" else "RETRY DASHBOARD",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onRetry()
            },
            enabled = !loading,
            isBusy = loading,
            modifier = Modifier.fillMaxWidth(),
        )
        ThemedActionButton(
            text = "ADD ANOTHER ACCOUNT",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onAddAccount()
            },
            enabled = !loading,
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            testTag = "vercel.addAccount",
        )
        ThemedActionButton(
            text = "REMOVE ACCOUNT",
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onDisconnect()
            },
            enabled = !loading,
            tone = ThemedActionTone.DESTRUCTIVE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (accounts.size > 1) {
        Spacer(Modifier.height(14.dp))
        VercelSavedAccountsList(
            accounts = accounts,
            activeAccountId = account?.id,
            enabled = !loading,
            onSwitch = onSwitchAccount,
        )
    }
}

@Composable
private fun ConnectedVercelContent(
    state: VercelConnectionUiState,
    projectQuery: String,
    onProjectQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSwitchAccount: (VercelAccountUi) -> Unit,
    onAddAccount: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val dashboard = requireNotNull(state.dashboard)
    val visibleProjects = remember(dashboard.projects, projectQuery) {
        visibleVercelProjects(dashboard.projects, projectQuery)
    }
    val previewProjects = remember(visibleProjects) { providerDetailProjectPreview(visibleProjects) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            StatusPill(text = "Connected", color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(10.dp))
            Text(
                text = dashboard.account.displayName,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            dashboard.account.email?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ThemedGlassControl(
            onClick = onRefresh,
            enabled = !state.isBusy,
            modifier = Modifier.size(48.dp),
            testTag = "vercel.refresh",
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = if (state.isBusy) {
                            "Refreshing Vercel projects"
                        } else {
                            "Refresh Vercel projects"
                        }
                        if (state.isBusy) {
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        }
    }

    if (state.isBusy) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    dashboard.warning?.let { warning ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = warning,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                    RoundedCornerShape(13.dp),
                )
                .padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("PROJECTS", style = MaterialTheme.typography.labelMedium)
        LabelChip(text = "${visibleProjects.size}/${dashboard.projects.size}")
    }
    Spacer(Modifier.height(8.dp))
    if (dashboard.projects.size > 1) {
        ControlSearchField(
            value = projectQuery,
            onValueChange = onProjectQueryChange,
            placeholder = "Search Vercel projects",
            modifier = Modifier.fillMaxWidth(),
            testTag = "vercel.projects.search",
        )
        Spacer(Modifier.height(10.dp))
    }
    if (dashboard.projects.isEmpty()) {
        Text(
            text = "No projects were returned for this account.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else if (visibleProjects.isEmpty()) {
        Text(
            text = "No Vercel project matches “$projectQuery”.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            previewProjects.forEach { project ->
                VercelProjectRow(project)
            }
            if (visibleProjects.size > previewProjects.size) {
                Text(
                    text = "Showing ${previewProjects.size} of ${visibleProjects.size}. " +
                        "Open Hosting to browse the complete project list.",
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (state.savedAccounts.size > 1) {
        Spacer(Modifier.height(16.dp))
        VercelSavedAccountsList(
            accounts = state.savedAccounts,
            activeAccountId = dashboard.account.id,
            enabled = !state.isBusy,
            onSwitch = onSwitchAccount,
        )
    }
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemedActionButton(
            text = "ADD ANOTHER ACCOUNT",
            onClick = onAddAccount,
            enabled = !state.isBusy,
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            testTag = "vercel.addAccount",
        )
        ThemedActionButton(
            text = "REMOVE CURRENT ACCOUNT",
            onClick = onDisconnect,
            enabled = !state.isBusy,
            tone = ThemedActionTone.DESTRUCTIVE,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("vercel.disconnect"),
        )
    }
}

@Composable
private fun VercelProjectRow(project: VercelProjectUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(13.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    project.framework,
                    project.updatedAtMillis?.let(::formattedDate),
                ).joinToString(" · ").ifBlank { "Project ${project.id.take(8)}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun providerDetailProjectPreview(
    projects: List<VercelProjectUi>,
): List<VercelProjectUi> = projects.take(PROVIDER_DETAIL_PROJECT_PREVIEW_LIMIT)

private const val PROVIDER_DETAIL_PROJECT_PREVIEW_LIMIT = 12

private fun credentialName(field: CredentialField): String = field.name
    .lowercase()
    .replace('_', ' ')

private fun formattedDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
