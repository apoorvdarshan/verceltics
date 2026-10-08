package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Domain
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountAuditEvent
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountDetail
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountMember
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareAccountRole
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsFormat
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.ui.components.ProviderMark
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedModalBottomSheet

/** Port of iOS `CloudflareAccountDetailView`. Settings come from a live `/accounts/{id}` read. */
@Composable
internal fun CloudflareAccountDetailScreen(
    context: CloudflareToolsContext,
    detail: CloudflareToolLoad<CloudflareAccountDetail>,
    onRetry: () -> Unit,
    onOpenOperations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = (detail as? CloudflareToolLoad.Loaded)?.value
    CloudflareToolsPage(
        "cloudflare.account",
        modifier,
        maximumContentWidth = 760.dp,
        spacing = 16.dp,
    ) { _ ->
        item("header") { CloudflareEdgeHeader(context) }
        item("operations") {
            ToolPanel(accentAlpha = 0.07f) {
                ToolNavigationRow(
                    title = "Account operations",
                    subtitle = "Members, roles, permissions, policies and audit logs",
                    icon = Icons.Rounded.AdminPanelSettings,
                    onClick = onOpenOperations,
                    testTag = "cloudflare.account.operations",
                )
            }
        }
        if (detail is CloudflareToolLoad.Failed) {
            item("error") {
                ToolBanner(
                    "Account settings: ${detail.message}",
                    isError = true,
                    actionLabel = "Retry",
                    onAction = onRetry,
                    testTag = "cloudflare.account.error",
                )
            }
        }
        item("identity") {
            ToolPanel {
                ToolSectionHeader("Identity", Icons.Rounded.Domain)
                ToolDetailRow("Account ID", context.accountId, Icons.Rounded.Numbers, monospace = true)
                ToolDetailRow("Name", loaded?.name ?: context.accountName, Icons.Rounded.Domain)
                ToolDetailRow(
                    "Type",
                    (loaded?.type ?: context.accountType)?.let(CloudflareToolsFormat::titleCase) ?: "Standard",
                    Icons.Rounded.Badge,
                )
                ToolDetailRow("Credential", context.credentialLabel, Icons.Rounded.Key)
                CloudflareToolsFormat.dateTime(loaded?.createdOn)?.let { ToolDetailRow("Created", it, Icons.Rounded.CalendarMonth) }
            }
        }
        item("security") {
            ToolPanel(testTag = "cloudflare.account.security") {
                ToolSectionHeader("Security policy", Icons.Rounded.Shield)
                when (detail) {
                    CloudflareToolLoad.Idle, CloudflareToolLoad.Loading -> ToolLoadingBlock("Reading account settings…")
                    else -> {
                        ToolDetailRow(
                            "Enforce two-factor authentication",
                            when (loaded?.enforceTwoFactor) {
                                null -> "Not returned"
                                true -> "Required"
                                false -> "Not required"
                            },
                            Icons.Rounded.Security,
                            valueColor = if (loaded?.enforceTwoFactor == true) {
                                CloudflareToolsColors.success()
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        ToolDetailRow("Abuse contact", loaded?.abuseContactEmail ?: "Not returned", Icons.Rounded.ReportProblem)
                    }
                }
            }
        }
        item("inventory") {
            ToolPanel {
                ToolSectionHeader("Resource inventory", Icons.Rounded.ViewInAr)
                InventoryRow("Zones", "Domains, DNS, analytics and cache", Icons.Rounded.Public, context.zoneCount, CloudflareToolsColors.Orange)
                ToolDivider(start = 64.dp)
                InventoryRow("Pages projects", "Sites and deployment history", Icons.Rounded.Description, context.pagesCount, CloudflareToolsColors.warning())
                ToolDivider(start = 64.dp)
                InventoryRow("Workers", "Edge scripts and deployments", Icons.Rounded.Code, context.workerCount, CloudflareToolsColors.success())
            }
        }
    }
}

/** iOS `CloudflareEdgeHeader`. */
@Composable
internal fun CloudflareEdgeHeader(context: CloudflareToolsContext) {
    val provider = IntegrationCatalog.provider("cloudflare")
    ToolPanel(accentAlpha = 0.06f, testTag = "cloudflare.account.header") {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (provider != null) ProviderMark(provider, size = 46.dp) else ToolIconTile(Icons.Rounded.Domain, size = 46.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(context.accountName, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(context.credentialLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill("Connected", CloudflareToolsColors.success())
            }
            Row(Modifier.fillMaxWidth()) {
                EdgeNode(context.zoneCount, "ZONES", Modifier.weight(1f))
                EdgeNode(context.pagesCount, "PAGES", Modifier.weight(1f))
                EdgeNode(context.workerCount, "WORKERS", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EdgeNode(value: Int, title: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(CloudflareToolsFormat.count(value), style = MaterialTheme.typography.titleMedium)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InventoryRow(title: String, subtitle: String, icon: ImageVector, count: Int, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIconTile(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(CloudflareToolsFormat.count(count), style = MonospaceSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
        }
    }
}

private sealed interface AccountSheet {
    data class Member(val id: String) : AccountSheet
    data class Role(val id: String) : AccountSheet
    data class Audit(val id: String) : AccountSheet
}

/** Port of iOS `CloudflareAccountOperationsView`. */
@Composable
internal fun CloudflareAccountOperationsScreen(
    context: CloudflareToolsContext,
    state: CloudflareAccountOperationsUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot
    val account = snapshot?.account
    var selectedMember by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRole by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAudit by rememberSaveable { mutableStateOf<String?>(null) }

    snapshot?.members?.firstOrNull { it.id == selectedMember }?.let { member ->
        MemberSheet(member) { selectedMember = null }
    }
    snapshot?.roles?.firstOrNull { it.id == selectedRole }?.let { role ->
        RoleSheet(role) { selectedRole = null }
    }
    snapshot?.auditEvents?.firstOrNull { it.id == selectedAudit }?.let { event ->
        AuditSheet(event) { selectedAudit = null }
    }

    CloudflareToolsPage(
        "cloudflare.accountOperations",
        modifier,
        maximumContentWidth = 820.dp,
        spacing = 16.dp,
        isRefreshing = state.isLoading,
        onRefresh = onRetry,
    ) { _ ->
        item("header") {
            ToolPanel(accentAlpha = 0.09f) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                    ToolIconTile(Icons.Rounded.AdminPanelSettings, size = 50.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(account?.name ?: context.accountName, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                        Text(context.credentialLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("ACCESS & AUDIT", style = MaterialTheme.typography.labelSmall, color = CloudflareToolsColors.Orange)
                    }
                    StatusPill(
                        if (state.isLoading) "Loading" else "Live",
                        if (state.isLoading) CloudflareToolsColors.warning() else CloudflareToolsColors.success(),
                    )
                }
            }
        }
        state.error?.let { error ->
            item("load-error") {
                ToolBanner(error, isError = true, actionLabel = "Retry", onAction = onRetry, testTag = "cloudflare.accountOperations.error")
            }
        }
        snapshot?.accountError?.let { error ->
            item("account-error") { ToolBanner("Account refresh: $error", isError = true) }
        }
        item("metadata") {
            ToolPanel {
                ToolSectionHeader("Account metadata", Icons.Rounded.Info)
                ToolDetailRow("Account ID", account?.id ?: context.accountId, Icons.Rounded.Numbers, monospace = true)
                ToolDetailRow("Name", account?.name ?: context.accountName, Icons.Rounded.Domain)
                ToolDetailRow("Type", account?.type?.let(CloudflareToolsFormat::titleCase) ?: "Not returned", Icons.Rounded.Badge)
                ToolDetailRow("API user", context.credentialLabel, Icons.Rounded.Key)
                CloudflareToolsFormat.dateTime(account?.createdOn)?.let { ToolDetailRow("Created", it, Icons.Rounded.CalendarMonth) }
                ToolDetailRow(
                    "Two-factor requirement",
                    when (account?.enforceTwoFactor) {
                        null -> "Not returned"
                        true -> "Required"
                        false -> "Not required"
                    },
                    Icons.Rounded.Security,
                    valueColor = if (account?.enforceTwoFactor == true) CloudflareToolsColors.success() else MaterialTheme.colorScheme.onSurface,
                )
                ToolDetailRow("Abuse contact", account?.abuseContactEmail ?: "Not returned", Icons.Rounded.ReportProblem)
            }
        }
        if (account?.isManaged == true) {
            item("managed") {
                ToolPanel {
                    ToolSectionHeader("Managed by", Icons.Rounded.Lan)
                    ToolDetailRow("Parent organization", account.managedByParentOrganizationName ?: "Name not returned", Icons.Rounded.AccountBalance)
                    ToolDetailRow("Parent organization ID", account.managedByParentOrganizationId ?: "Not returned", Icons.Rounded.Numbers, monospace = true)
                }
            }
        }
        sectionPanel(
            key = "members",
            title = "Members",
            icon = Icons.Rounded.Groups,
            items = snapshot?.members.orEmpty(),
            isLoading = state.isLoading,
            error = snapshot?.membersError,
            unavailableTitle = "Members unavailable",
            emptyTitle = "No members returned",
            emptyMessage = "Cloudflare did not return any memberships for this account.",
        ) { member -> MemberRow(member) { selectedMember = member.id } }
        sectionPanel(
            key = "roles",
            title = "Available roles",
            icon = Icons.Rounded.Key,
            items = snapshot?.roles.orEmpty(),
            isLoading = state.isLoading,
            error = snapshot?.rolesError,
            unavailableTitle = "Roles unavailable",
            emptyTitle = "No roles returned",
            emptyMessage = "No account roles were available to this Cloudflare credential.",
        ) { role -> RoleRow(role) { selectedRole = role.id } }
        sectionPanel(
            key = "audit",
            title = "Audit log · 7 days",
            icon = Icons.Rounded.History,
            items = snapshot?.auditEvents.orEmpty(),
            isLoading = state.isLoading,
            error = snapshot?.auditError,
            unavailableTitle = "Audit log unavailable",
            emptyTitle = "No recent activity",
            emptyMessage = "No account audit events were returned for the last seven days.",
        ) { event -> AuditRow(event) { selectedAudit = event.id } }
    }
}

private fun <T> LazyListScope.sectionPanel(
    key: String,
    title: String,
    icon: ImageVector,
    items: List<T>,
    isLoading: Boolean,
    error: String?,
    unavailableTitle: String,
    emptyTitle: String,
    emptyMessage: String,
    row: @Composable (T) -> Unit,
) {
    item("$key-panel") {
        ToolPanel(testTag = "cloudflare.accountOperations.$key") {
            ToolSectionHeader(title, icon, items.size)
            when {
                isLoading && items.isEmpty() -> ToolLoadingBlock("Loading ${title.lowercase()}…")
                error != null -> ToolEmptyBlock(unavailableTitle, error, Icons.Rounded.ReportProblem)
                items.isEmpty() -> ToolEmptyBlock(emptyTitle, emptyMessage, icon)
                else -> items.forEachIndexed { index, value ->
                    row(value)
                    if (index < items.lastIndex) ToolDivider(start = 63.dp)
                }
            }
        }
    }
}

@Composable
private fun TappableRow(onClick: () -> Unit, testTag: String, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(testTag),
        color = Color.Transparent,
    ) { content() }
}

@Composable
private fun MemberRow(member: CloudflareAccountMember, onClick: () -> Unit) {
    TappableRow(onClick, "cloudflare.accountOperations.member.${member.id}") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ToolIconTile(
                    if (member.twoFactorEnabled) Icons.Rounded.Security else Icons.Rounded.Person,
                    if (member.twoFactorEnabled) CloudflareToolsColors.success() else CloudflareToolsColors.Orange,
                    size = 36.dp,
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(member.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(member.resolvedEmail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (member.roles.isEmpty()) "Policy-based access" else member.roles.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(
                    member.status ?: "Unknown",
                    if (member.status == "accepted") CloudflareToolsColors.success() else CloudflareToolsColors.warning(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccessChip("${member.roles.size} roles")
                AccessChip("${member.policies.size} policies")
                AccessChip(if (member.twoFactorEnabled) "2FA on" else "2FA off")
            }
        }
    }
}

@Composable
private fun AccessChip(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
private fun RoleRow(role: CloudflareAccountRole, onClick: () -> Unit) {
    TappableRow(onClick, "cloudflare.accountOperations.role.${role.id}") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(role.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${role.permissions.size} grants", style = MaterialTheme.typography.labelSmall, color = CloudflareToolsColors.Orange)
            }
            role.description?.takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (role.permissions.isNotEmpty()) {
                Text(role.permissionSummary, style = MonospaceSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AuditRow(event: CloudflareAccountAuditEvent, onClick: () -> Unit) {
    val color = if (event.isFailure) CloudflareToolsColors.danger() else CloudflareToolsColors.success()
    TappableRow(onClick, "cloudflare.accountOperations.audit.${event.id}") {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIconTile(auditIcon(event.actionType), color, size = 34.dp)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${event.actorEmail ?: event.actorType ?: "Unknown actor"} · ${CloudflareToolsFormat.dateTime(event.actionTime) ?: "Unknown time"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun auditIcon(type: String?): ImageVector = when (type?.lowercase()) {
    "create" -> Icons.Rounded.AddCircle
    "delete" -> Icons.Rounded.Delete
    "update" -> Icons.Rounded.Edit
    else -> Icons.Rounded.Visibility
}

@Composable
private fun DetailSheet(title: String, testTag: String, onDismiss: () -> Unit, content: LazyListScope.() -> Unit) {
    ThemedModalBottomSheet(onDismissRequest = onDismiss, testTag = testTag) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("title") { Text(title, style = MaterialTheme.typography.headlineSmall) }
            content()
        }
    }
}

/** iOS `CloudflareAccountMemberDetailView`. */
@Composable
private fun MemberSheet(member: CloudflareAccountMember, onDismiss: () -> Unit) {
    DetailSheet("Member access", "cloudflare.accountOperations.memberSheet", onDismiss) {
        item("member") {
            ToolPanel {
                ToolSectionHeader("Member", Icons.Rounded.Person)
                ToolDetailRow("Name", member.displayName, Icons.Rounded.Person)
                ToolDetailRow("Email", member.resolvedEmail, Icons.Rounded.Email)
                ToolDetailRow("Membership ID", member.id, Icons.Rounded.Numbers, monospace = true)
                member.user?.id?.let { ToolDetailRow("User ID", it, Icons.Rounded.Numbers, monospace = true) }
                ToolDetailRow("Status", member.status?.let(CloudflareToolsFormat::titleCase) ?: "Not returned", Icons.Rounded.ToggleOn)
                ToolDetailRow(
                    "Two-factor authentication",
                    if (member.twoFactorEnabled) "Enabled" else "Not enabled",
                    Icons.Rounded.Security,
                    valueColor = if (member.twoFactorEnabled) CloudflareToolsColors.success() else CloudflareToolsColors.warning(),
                )
            }
        }
        item("roles") {
            ToolPanel {
                ToolSectionHeader("Assigned roles", Icons.Rounded.Key, member.roles.size)
                if (member.roles.isEmpty()) {
                    ToolEmptyBlock("None returned", "This membership has no legacy roles. Access may be policy based.", Icons.Rounded.Key)
                } else {
                    member.roles.forEach { ToolDetailRow(it.name, it.description ?: it.id, Icons.Rounded.Key) }
                }
            }
        }
        item("policies") {
            ToolPanel {
                ToolSectionHeader("Policies", Icons.Rounded.Checklist, member.policies.size)
                if (member.policies.isEmpty()) {
                    ToolEmptyBlock("No policies returned", "This membership may use legacy account roles instead.", Icons.Rounded.Checklist)
                } else {
                    member.policies.forEachIndexed { index, policy ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    policy.access?.uppercase() ?: "POLICY",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (policy.access == "deny") CloudflareToolsColors.danger() else CloudflareToolsColors.success(),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(policy.id, style = MonospaceSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            }
                            if (policy.permissionGroups.isNotEmpty()) PolicyValue("Permission groups", ProviderJsonValue.Arr(policy.permissionGroups))
                            if (policy.resourceGroups.isNotEmpty()) PolicyValue("Resource groups", ProviderJsonValue.Arr(policy.resourceGroups))
                        }
                        if (index < member.policies.lastIndex) ToolDivider(start = 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyValue(title: String, value: ProviderJsonValue) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(CloudflareToolsFormat.displayText(value), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** iOS `CloudflareAccountRoleDetailView`. */
@Composable
private fun RoleSheet(role: CloudflareAccountRole, onDismiss: () -> Unit) {
    DetailSheet("Role details", "cloudflare.accountOperations.roleSheet", onDismiss) {
        item("role") {
            ToolPanel {
                ToolSectionHeader("Role", Icons.Rounded.Key)
                ToolDetailRow("Name", role.name, Icons.Rounded.Badge)
                ToolDetailRow("Role ID", role.id, Icons.Rounded.Numbers, monospace = true)
                role.description?.let { ToolDetailRow("Description", it, Icons.Rounded.Info) }
            }
        }
        item("grants") {
            ToolPanel {
                ToolSectionHeader("Permission grants", Icons.Rounded.Shield, role.permissions.size)
                if (role.permissions.isEmpty()) {
                    ToolEmptyBlock("No grants returned", "Cloudflare did not include permission grants for this role.", Icons.Rounded.Shield)
                } else {
                    val entries = role.permissions.entries.sortedBy { it.key }
                    entries.forEachIndexed { index, (key, grant) ->
                        val color = when {
                            grant.write == true -> CloudflareToolsColors.Orange
                            grant.read == true -> CloudflareToolsColors.success()
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (grant.write == true) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(11.dp))
                            Text(CloudflareToolsFormat.titleCase(key), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            StatusPill(
                                when {
                                    grant.write == true -> "Read / Write"
                                    grant.read == true -> "Read"
                                    else -> "None"
                                },
                                color,
                            )
                        }
                        if (index < entries.lastIndex) ToolDivider(start = 47.dp)
                    }
                }
            }
        }
    }
}

/** iOS `CloudflareAuditEventDetailView`. */
@Composable
private fun AuditSheet(event: CloudflareAccountAuditEvent, onDismiss: () -> Unit) {
    DetailSheet("Audit event", "cloudflare.accountOperations.auditSheet", onDismiss) {
        auditPanel("action", "Action", Icons.Rounded.Bolt, listOf(
            "Description" to event.actionDescription,
            "Type" to event.actionType,
            "Result" to event.actionResult,
            "Time" to CloudflareToolsFormat.dateTime(event.actionTime),
            "Event ID" to event.eventId,
        ))
        auditPanel("actor", "Actor", Icons.Rounded.Person, listOf(
            "Email" to event.actorEmail,
            "Actor ID" to event.actorId,
            "Type" to event.actorType,
            "Context" to event.actorContext,
            "IP address" to event.actorIpAddress,
            "Token name" to event.actorTokenName,
            "Token ID" to event.actorTokenId,
        ))
        auditPanel("request", "Request", Icons.Rounded.Lan, listOf(
            "Method" to event.rawMethod,
            "Status code" to event.rawStatusCode?.toString(),
            "URI" to event.rawUri,
            "Ray ID" to event.rawCfRayId,
            "User agent" to event.rawUserAgent,
        ))
        auditPanel("resource", "Resource", Icons.Rounded.ViewInAr, listOf(
            "Product" to event.resourceProduct,
            "Type" to event.resourceType,
            "Resource ID" to event.resourceId,
            "Scope" to event.resourceScope?.let(CloudflareToolsFormat::displayText),
            "Zone" to event.zoneName,
            "Zone ID" to event.zoneId,
        ))
    }
}

private fun LazyListScope.auditPanel(key: String, title: String, icon: ImageVector, rows: List<Pair<String, String?>>) {
    val populated = rows.filter { !it.second.isNullOrEmpty() }
    item(key) {
        ToolPanel {
            ToolSectionHeader(title, icon)
            if (populated.isEmpty()) {
                ToolEmptyBlock("Nothing returned", "Cloudflare did not include ${title.lowercase()} details for this event.", icon)
            } else {
                populated.forEach { (label, value) -> ToolDetailRow(label, value.orEmpty()) }
            }
        }
    }
}
