package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog

/** One saved account in a site-service account menu. Never carries a credential. */
data class SiteAccountOptionUi(
    val id: String,
    val title: String,
    val detail: String? = null,
)

/** Saved accounts for one service plus the active one (iOS `SiteStore.accounts`). */
data class SiteAccountsUi(
    val accounts: List<SiteAccountOptionUi>,
    val activeAccountId: String?,
) {
    val active: SiteAccountOptionUi? get() = accounts.firstOrNull { it.id == activeAccountId }

    val hasMultiple: Boolean get() = accounts.size > 1

    companion object {
        val EMPTY: SiteAccountsUi = SiteAccountsUi(emptyList(), null)
    }
}

/** Which destructive account action is awaiting confirmation. */
enum class SiteAccountRemoval {
    CURRENT,
    ALL,
}

/**
 * Toolbar account switcher shared by the site services, Search Console and PageSpeed (iOS
 * `SiteAccountMenu`): switch accounts, add another without disconnecting, Remove Current, and Remove
 * All. Removals only request confirmation; [SiteAccountRemovalDialog] performs them.
 */
@Composable
fun SiteAccountMenu(
    provider: IntegrationProvider,
    accounts: SiteAccountsUi,
    addLabel: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onRemoveCurrent: () -> Unit,
    onRemoveAll: () -> Unit,
    testTagPrefix: String,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val active = accounts.active
    Box {
        AccountMenuButton(
            provider = provider,
            onClick = {
                if (enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    expanded = true
                }
            },
            contentDescription = "Switch ${provider.displayName} account" +
                (active?.let { ", current: ${it.title}" } ?: ""),
            testTag = "$testTagPrefix.accountMenu",
        )
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 260.dp, max = 340.dp)
                .testTag("$testTagPrefix.accountMenu.list"),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                provider.displayName.uppercase(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            accounts.accounts.forEach { account ->
                val isActive = account.id == accounts.activeAccountId
                DropdownMenuItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .then(
                            if (isActive) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            selected = isActive
                            if (isActive) stateDescription = "Current account"
                        }
                        .testTag("$testTagPrefix.account.${account.id}"),
                    text = {
                        Column {
                            Text(
                                account.title,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 2,
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
                        if (isActive) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            ProviderLogo(provider, Modifier.size(20.dp), monochrome = true)
                        }
                    },
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        expanded = false
                        if (!isActive) onSwitch(account.id)
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .testTag("$testTagPrefix.addAccount"),
                text = { Text(addLabel) },
                leadingIcon = { Icon(Icons.Rounded.AddCircle, contentDescription = null) },
                colors = MenuDefaults.itemColors(leadingIconColor = MaterialTheme.colorScheme.primary),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    expanded = false
                    onAdd()
                },
            )
            if (active != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        expanded = false
                        onRemoveCurrent()
                    },
                )
                if (accounts.hasMultiple) {
                    DropdownMenuItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .testTag("$testTagPrefix.removeAllAccounts"),
                        text = { Text("Remove all accounts") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                            expanded = false
                            onRemoveAll()
                        },
                    )
                }
            }
        }
    }
}

/** Destructive confirmation for Remove Current / Remove All (iOS confirmation dialog copy). */
@Composable
fun SiteAccountRemovalDialog(
    removal: SiteAccountRemoval,
    serviceName: String,
    accountTitle: String?,
    credentialNoun: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
) {
    val haptic = LocalHapticFeedback.current
    ThemedAlertDialog(
        title = when (removal) {
            SiteAccountRemoval.CURRENT -> "Remove ${accountTitle ?: "this $serviceName account"}?"
            SiteAccountRemoval.ALL -> "Remove all $serviceName accounts?"
        },
        message = when (removal) {
            SiteAccountRemoval.CURRENT ->
                "The encrypted $credentialNoun and saved data for this account are removed from this device only."
            SiteAccountRemoval.ALL ->
                "Every saved $serviceName account, its encrypted $credentialNoun, and its saved data are removed from this device only."
        },
        confirmText = when (removal) {
            SiteAccountRemoval.CURRENT -> "REMOVE ACCOUNT"
            SiteAccountRemoval.ALL -> "REMOVE ALL"
        },
        confirmTone = ThemedActionTone.DESTRUCTIVE,
        dismissText = "KEEP",
        enabled = enabled,
        onConfirm = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onConfirm()
        },
        onDismissRequest = onDismiss,
        testTag = testTag,
    )
}
