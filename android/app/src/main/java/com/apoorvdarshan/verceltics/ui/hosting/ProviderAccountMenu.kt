package com.apoorvdarshan.verceltics.ui.hosting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.AppToolbarAction
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog

/**
 * One saved provider account in an account menu (iOS `ProviderAccountMenu`). Display-only: the
 * credential never leaves the data layer, and [id] is an opaque storage id.
 */
@Immutable
data class ProviderAccountUi(
    val id: String,
    val displayName: String,
    /** Email or credential label shown under the name. */
    val detail: String? = null,
    /** HTTPS avatar returned by the provider (Netlify, Render, …); null shows the provider mark. */
    val avatarUrl: String? = null,
    val isActive: Boolean = false,
    /** False when the saved record could not be opened; it can still be removed. */
    val isReadable: Boolean = true,
)

/** Account-menu actions; each removal opens a destructive confirmation owned by the screen. */
class ProviderAccountMenuActions(
    val onSwitchAccount: (accountId: String) -> Unit = {},
    val onAddAccount: () -> Unit = {},
    val onRemoveCurrent: () -> Unit = {},
    val onRemoveAll: () -> Unit = {},
)

/**
 * Toolbar account switcher shared by the hosting, Netlify and Cloudflare dashboards: lists every
 * saved account (active one checked), "Add account" (opens the connect form without disconnecting),
 * "Remove current account" and, with more than one account, "Remove all accounts".
 */
@Composable
fun ProviderAccountMenu(
    provider: IntegrationProvider,
    accounts: List<ProviderAccountUi>,
    actions: ProviderAccountMenuActions,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = accounts.firstOrNull { it.isActive }
    Box(modifier) {
        AppToolbarAction(
            modifier = Modifier.size(48.dp),
            enabled = enabled,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                expanded = true
            },
            testTag = "$testTagPrefix.accountMenuButton",
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Switch ${provider.displayName} account"
                        stateDescription = active?.displayName ?: "No active account"
                        role = Role.Button
                    },
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProviderAccountAvatar(provider = provider, avatarUrl = active?.avatarUrl, size = 24.dp)
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 280.dp, max = 340.dp)
                .testTag("$testTagPrefix.accountMenu"),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                provider.displayName.uppercase(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "${provider.displayName} accounts" }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 54.dp)
                        .then(
                            if (account.isActive) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            selected = account.isActive
                            if (account.isActive) stateDescription = "Active account"
                        }
                        .testTag("$testTagPrefix.account.${account.id}"),
                    text = {
                        Column {
                            Text(
                                account.displayName,
                                fontWeight = if (account.isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            account.detail?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (account.isActive) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            ProviderAccountAvatar(provider = provider, avatarUrl = account.avatarUrl, size = 22.dp)
                        }
                    },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.onSurface),
                    enabled = account.isActive || account.isReadable,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        if (!account.isActive) actions.onSwitchAccount(account.id)
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("$testTagPrefix.addAccount"),
                text = { Text("Add account") },
                leadingIcon = { Icon(Icons.Rounded.AddCircle, contentDescription = null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconColor = MaterialTheme.colorScheme.primary,
                ),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    expanded = false
                    actions.onAddAccount()
                },
            )
            if (active != null || accounts.isNotEmpty()) {
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .testTag("$testTagPrefix.removeCurrentAccount"),
                    text = { Text("Remove current account") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        actions.onRemoveCurrent()
                    },
                )
            }
            if (accounts.size > 1) {
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .testTag("$testTagPrefix.removeAllAccounts"),
                    text = { Text("Remove all accounts") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null) },
                    colors = MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        actions.onRemoveAll()
                    },
                )
            }
        }
    }
}

/**
 * iOS confirmation dialogs for "Remove <account>?" and "Remove all <provider> accounts?". Removal
 * only deletes credentials stored on this device.
 */
@Composable
fun ProviderAccountRemovalDialogs(
    providerName: String,
    currentAccountName: String?,
    showRemoveCurrent: Boolean,
    showRemoveAll: Boolean,
    accountCount: Int,
    enabled: Boolean,
    onConfirmRemoveCurrent: () -> Unit,
    onDismissRemoveCurrent: () -> Unit,
    onConfirmRemoveAll: () -> Unit,
    onDismissRemoveAll: () -> Unit,
    testTagPrefix: String,
    removeCurrentMessage: String = "The encrypted credentials and saved $providerName inventory for this account are removed from this device only.",
) {
    val haptic = LocalHapticFeedback.current
    if (showRemoveCurrent) {
        ThemedAlertDialog(
            title = "Remove ${currentAccountName ?: "this $providerName account"}?",
            message = removeCurrentMessage + if (accountCount > 1) " Your other $providerName accounts stay connected." else "",
            confirmText = "REMOVE ACCOUNT",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "KEEP ACCOUNT",
            enabled = enabled,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmRemoveCurrent()
            },
            onDismissRequest = onDismissRemoveCurrent,
            testTag = "$testTagPrefix.disconnectDialog",
        )
    }
    if (showRemoveAll) {
        ThemedAlertDialog(
            title = "Remove all $providerName accounts?",
            message = "All $accountCount saved $providerName accounts and their offline inventories are removed from this device only.",
            confirmText = "REMOVE ALL ACCOUNTS",
            confirmTone = ThemedActionTone.DESTRUCTIVE,
            dismissText = "CANCEL",
            enabled = enabled,
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onConfirmRemoveAll()
            },
            onDismissRequest = onDismissRemoveAll,
            testTag = "$testTagPrefix.removeAllDialog",
        )
    }
}
