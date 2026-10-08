package com.apoorvdarshan.verceltics.ui.registrar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.data.registrar.RegistrarProvider
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.ui.components.AccountMenuButton
import com.apoorvdarshan.verceltics.ui.components.ProviderLogo

/**
 * Port of iOS `RegistrarAccountMenu` for one registrar: switch between its saved accounts, add
 * another account without disconnecting, and remove the current account or (with several) all of
 * them. Removal only requests a destructive confirmation; nothing is deleted from the menu itself.
 */
@Composable
internal fun RegistrarAccountMenu(
    provider: RegistrarProvider,
    catalogProvider: IntegrationProvider,
    providerState: RegistrarProviderUiState,
    onSwitchAccount: (accountId: String) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveCurrent: () -> Unit,
    onRemoveAll: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var expanded by rememberSaveable(provider.id) { mutableStateOf(false) }
    val accounts = providerState.savedAccounts
    val activeAccount = providerState.activeAccount
    val enabled = !providerState.isBusy
    fun select(action: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        expanded = false
        action()
    }

    Box {
        AccountMenuButton(
            provider = catalogProvider,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                expanded = true
            },
            contentDescription = "Switch ${provider.displayName} account. Current: ${activeAccount?.displayName ?: "none"}",
            testTag = "registrar.accountMenu.button",
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 260.dp, max = 340.dp)
                .testTag("registrar.accountMenu"),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(13.dp),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                "${provider.displayName} accounts".uppercase(),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            )
            accounts.forEach { account ->
                val isActive = account.id == activeAccount?.id
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
                        .testTag("registrar.accountMenu.account.${account.id}"),
                    text = {
                        Text(
                            account.displayName,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        if (isActive) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            ProviderLogo(catalogProvider, Modifier.size(20.dp))
                        }
                    },
                    enabled = isActive || enabled,
                    onClick = { select { if (!isActive) onSwitchAccount(account.id) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MenuAction(
                text = "Add ${provider.displayName} account",
                icon = Icons.Rounded.AddCircle,
                enabled = enabled,
                destructive = false,
                testTag = "registrar.accountMenu.add",
                onClick = { select(onAddAccount) },
            )
            if (activeAccount != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MenuAction(
                    text = "Remove current account",
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    enabled = enabled,
                    destructive = true,
                    testTag = "registrar.accountMenu.removeCurrent",
                    onClick = { select(onRemoveCurrent) },
                )
                if (accounts.size > 1) {
                    MenuAction(
                        text = "Remove all ${provider.displayName} accounts",
                        icon = Icons.Rounded.DeleteOutline,
                        enabled = enabled,
                        destructive = true,
                        testTag = "registrar.accountMenu.removeAll",
                        onClick = { select(onRemoveAll) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    destructive: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .then(if (destructive) Modifier.background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) else Modifier)
            .testTag(testTag),
        text = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        colors = MenuDefaults.itemColors(textColor = tint, leadingIconColor = tint),
        onClick = onClick,
    )
}
