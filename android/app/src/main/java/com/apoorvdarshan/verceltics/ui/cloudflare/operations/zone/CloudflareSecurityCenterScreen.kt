package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CLOUDFLARE_ACCESS_RULE_MODES
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CLOUDFLARE_ACCESS_RULE_TARGETS
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CLOUDFLARE_SECURITY_LEVELS
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAccessRuleDraft
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflarePrettyJson
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareSecurityItem
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.cloudflareHumanize
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsChoiceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsIconTile
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMonospaceBlock
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

/** Port of iOS `CloudflareSecurityCenterView`. */
@Composable
internal fun CloudflareSecurityCenterScreen(
    viewModel: CloudflareSecurityCenterViewModel,
    zoneName: String,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    LaunchedEffect(context.refreshSignal) { viewModel.onRefreshSignal(context.refreshSignal) }

    CloudflareConfirmationHost(viewModel)
    if (state.showingAccessRuleEditor) {
        CloudflareAccessRuleEditorSheet(
            error = state.accessRuleEditorError,
            isSaving = CloudflareSecurityCenterViewModel.WORKING_ACCESS_ADD in working,
            onSave = viewModel::requestCreateAccessRule,
            onDismiss = viewModel::dismissAccessRuleEditor,
        )
    }
    state.selectedItem?.let { item -> CloudflareSecurityItemSheet(item, onDismiss = viewModel::dismissItem) }

    val snapshot = state.snapshot
    CloudflareOpsScreen(
        "cloudflare.securityCenter",
        modifier,
        maximumContentWidth = 900.dp,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = { viewModel.load(force = true) },
    ) {
        item("posture") {
            CloudflareOpsPanel(accent = 0.09f) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(zoneName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "SECURITY CONTROL PLANE",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = CloudflareOpsColors.Orange,
                            )
                        }
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                        } else {
                            CloudflareOpsStatusPill("${snapshot.totalItems} ITEMS", CloudflareOpsColors.Green)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PostureValue("WAF", snapshot.rulesets.size, Modifier.weight(1f))
                        PostureValue("ACCESS", snapshot.accessRules.size, Modifier.weight(1f))
                        PostureValue("RATE", snapshot.rateLimits.size, Modifier.weight(1f))
                        PostureValue("TLS", snapshot.certificates.size, Modifier.weight(1f))
                    }
                }
            }
        }
        item("security-level") {
            SecurityLevelPanel(
                level = snapshot.securityLevel,
                working = CloudflareSecurityCenterViewModel.WORKING_SECURITY_LEVEL in working,
                onChange = viewModel::requestSecurityLevel,
            )
        }
        banner?.let { value -> item("banner") { CloudflareActionResultBanner(value, viewModel::dismissBanner) } }
        if (snapshot.warnings.isNotEmpty()) item("warnings") { WarningsPanel(snapshot.warnings) }
        item("waf") {
            SecurityPanel(
                "WAF rulesets",
                Icons.Rounded.Shield,
                snapshot.rulesets,
                "No WAF rulesets were returned for this zone.",
                testTag = "cloudflare.security.waf",
            ) { item -> context.navigate(CloudflareZoneRoutes.ruleset(viewModel.zoneId, item)) }
        }
        item("access") {
            AccessRulesPanel(
                rules = snapshot.accessRules,
                working = working,
                onAdd = viewModel::openAccessRuleEditor,
                onDelete = viewModel::requestDeleteAccessRule,
            )
        }
        item("rate") {
            SecurityPanel(
                "Rate limits",
                Icons.Rounded.Speed,
                snapshot.rateLimits,
                "No legacy rate limits were returned. Ruleset-based rate limiting may appear under WAF.",
                onOpen = viewModel::showItem,
            )
        }
        item("certificates") {
            SecurityPanel("Certificates", Icons.Rounded.Verified, snapshot.certificates, "No edge or custom certificate packs were returned.", onOpen = viewModel::showItem)
        }
        item("page-shield") {
            SecurityPanel("Page Shield", Icons.Rounded.FindInPage, snapshot.pageShield, "Page Shield policies are unavailable or not configured.", onOpen = viewModel::showItem)
        }
        item("bots") {
            SecurityPanel("Bot management", Icons.Rounded.BugReport, snapshot.botManagement, "Bot management configuration is unavailable on this plan.", onOpen = viewModel::showItem)
        }
        item("api-shield") {
            SecurityPanel("API Shield", Icons.Rounded.Dns, snapshot.apiShield, "API Shield configuration is unavailable or not configured.", onOpen = viewModel::showItem)
        }
    }
}

@Composable
private fun PostureValue(title: String, value: Int, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(title, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SecurityLevelPanel(level: String?, working: Boolean, onChange: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    CloudflareOpsPanel(testTag = "cloudflare.security.level") {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CloudflareOpsIconTile(Icons.Rounded.Shield, size = 40)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Security level", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(cloudflareHumanize(level ?: "Not returned"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (working) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
            } else {
                Box {
                    Surface(
                        onClick = { menuOpen = true },
                        shape = RoundedCornerShape(50),
                        color = CloudflareOpsColors.Orange.copy(alpha = 0.1f),
                        modifier = Modifier.heightIn(min = 40.dp).testTag("cloudflare.security.level.change"),
                    ) {
                        Text(
                            "Change",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = CloudflareOpsColors.Orange,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        CLOUDFLARE_SECURITY_LEVELS.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(cloudflareHumanize(option)) },
                                onClick = {
                                    menuOpen = false
                                    onChange(option)
                                },
                                modifier = Modifier.testTag("cloudflare.security.level.$option"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningsPanel(warnings: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    CloudflareOpsPanel {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.GppMaybe, contentDescription = null, tint = CloudflareOpsColors.Amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Plan-limited security products",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CloudflareOpsColors.Amber,
            )
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun SecurityPanel(
    title: String,
    icon: ImageVector,
    items: List<CloudflareSecurityItem>,
    emptyMessage: String,
    testTag: String? = null,
    onOpen: (CloudflareSecurityItem) -> Unit,
) {
    CloudflareOpsPanel(testTag = testTag) {
        CloudflareOpsSectionHeader(title, icon, count = items.size)
        CloudflareOpsDivider()
        if (items.isEmpty()) {
            CloudflareOpsEmptySection(icon, "Nothing returned", emptyMessage)
        } else {
            items.forEachIndexed { index, item ->
                CloudflareOpsResourceRow(
                    icon = icon,
                    title = item.title,
                    subtitle = item.rowSubtitle,
                    tint = securityTint(item.status),
                    onClick = { onOpen(item) },
                    testTag = "cloudflare.security.item.${item.id}",
                )
                if (index < items.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
}

@Composable
private fun AccessRulesPanel(
    rules: List<CloudflareSecurityItem>,
    working: Set<String>,
    onAdd: () -> Unit,
    onDelete: (CloudflareSecurityItem) -> Unit,
) {
    CloudflareOpsPanel(testTag = "cloudflare.security.access") {
        CloudflareOpsSectionHeader(
            "IP access rules",
            Icons.Rounded.PanTool,
            count = rules.size,
            actionTitle = "Add",
            actionTestTag = "cloudflare.security.access.add",
            onAction = onAdd,
        )
        CloudflareOpsDivider()
        if (rules.isEmpty()) {
            CloudflareOpsEmptySection(Icons.Rounded.PanTool, "No IP access rules", "Create a rule for an IP, network, ASN or country.")
        } else {
            rules.forEachIndexed { index, rule ->
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.PanTool,
                    title = rule.title,
                    subtitle = rule.rowSubtitle,
                    tint = securityTint(rule.status),
                    testTag = "cloudflare.security.access.${rule.id}",
                ) {
                    if (CloudflareSecurityCenterViewModel.accessWorkingId(rule.id) in working) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
                    } else {
                        IconButton(
                            onClick = { onDelete(rule) },
                            modifier = Modifier.testTag("cloudflare.security.access.${rule.id}.delete"),
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete ${rule.title}", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (index < rules.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
}

@Composable
private fun securityTint(status: String?): Color = when (status?.lowercase()) {
    "active", "enabled", "allow", "whitelist" -> CloudflareOpsColors.Green
    "block", "challenge", "js_challenge", "under_attack" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** iOS `CloudflareSecurityItemDetailCard`. */
@Composable
internal fun CloudflareSecurityItemCard(id: String, title: String, subtitle: String?, status: String?) {
    CloudflareOpsPanel(accent = 0.07f) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsIconTile(Icons.Rounded.Shield, size = 42)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    subtitle?.takeIf(String::isNotEmpty)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                status?.takeIf(String::isNotEmpty)?.let { CloudflareOpsStatusPill(it.uppercase(), CloudflareOpsColors.Orange) }
            }
            Text(id, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** iOS `CloudflareSecurityItemDetailView`: the item plus its raw returned configuration. */
@Composable
internal fun CloudflareSecurityItemSheet(item: CloudflareSecurityItem, onDismiss: () -> Unit) {
    CloudflareOpsEditorSheet(title = item.title, onDismiss = onDismiss, testTag = "cloudflare.security.itemSheet") {
        CloudflareSecurityItemCard(item.id, item.title, item.subtitle, item.status)
        CloudflareOpsPanel {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "RAW RETURNED CONFIGURATION",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = CloudflareOpsColors.Orange,
                )
                CloudflareOpsMonospaceBlock(CloudflarePrettyJson.write(item.raw), testTag = "cloudflare.security.itemSheet.raw")
            }
        }
    }
}

/** iOS `CloudflareAccessRuleEditor`. */
@Composable
private fun CloudflareAccessRuleEditorSheet(
    error: String?,
    isSaving: Boolean,
    onSave: (CloudflareAccessRuleDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(CloudflareAccessRuleDraft()) }
    CloudflareOpsEditorSheet(title = "Add IP access rule", onDismiss = onDismiss, testTag = "cloudflare.security.accessEditor") {
        Text(
            "The action applies immediately to matching requests for this zone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("TARGET", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        CloudflareOpsChoiceRow(
            options = CLOUDFLARE_ACCESS_RULE_TARGETS,
            selected = draft.target,
            label = ::cloudflareHumanize,
            onSelect = { draft = draft.copy(target = it) },
            testTagPrefix = "cloudflare.security.accessEditor.target",
        )
        CloudflareOpsTextField(
            value = draft.value,
            onValueChange = { draft = draft.copy(value = it) },
            label = "Value",
            placeholder = draft.valuePlaceholder,
            monospace = true,
            keyboardType = if (draft.target == "asn") KeyboardType.Number else KeyboardType.Ascii,
            testTag = "cloudflare.security.accessEditor.value",
        )
        Text("ACTION", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        CloudflareOpsChoiceRow(
            options = CLOUDFLARE_ACCESS_RULE_MODES,
            selected = draft.mode,
            label = ::cloudflareHumanize,
            onSelect = { draft = draft.copy(mode = it) },
            testTagPrefix = "cloudflare.security.accessEditor.mode",
        )
        CloudflareOpsTextField(
            value = draft.notes,
            onValueChange = { draft = draft.copy(notes = it) },
            label = "Optional note",
            testTag = "cloudflare.security.accessEditor.notes",
        )
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        ThemedActionButton(
            text = "CREATE RULE",
            onClick = { onSave(draft) },
            enabled = draft.canSubmit,
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.security.accessEditor.create",
        )
    }
}

/** Port of iOS `CloudflareRulesetDetailView`. */
@Composable
internal fun CloudflareRulesetDetailScreen(
    viewModel: CloudflareRulesetDetailViewModel,
    rulesetId: String,
    title: String,
    subtitle: String?,
    status: String?,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(context.refreshSignal) { viewModel.onRefreshSignal(context.refreshSignal) }
    state.selectedItem?.let { item -> CloudflareSecurityItemSheet(item, onDismiss = viewModel::dismissItem) }
    CloudflareOpsScreen(
        "cloudflare.rulesetDetail",
        modifier,
        maximumContentWidth = 900.dp,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = { viewModel.load(force = true) },
    ) {
        item("card") { CloudflareSecurityItemCard(rulesetId, title, subtitle, status) }
        item("rules") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Rules", Icons.AutoMirrored.Rounded.ViewList, count = state.rules.size)
                CloudflareOpsDivider()
                when {
                    state.isLoading && state.rules.isEmpty() -> CloudflareOpsLoading()
                    state.error != null && state.rules.isEmpty() ->
                        CloudflareOpsEmptySection(Icons.Rounded.GppMaybe, "Rules unavailable", state.error.orEmpty())
                    state.rules.isEmpty() ->
                        CloudflareOpsEmptySection(Icons.AutoMirrored.Rounded.ViewList, "No rules", "This ruleset did not return individual rules.")
                    else -> state.rules.forEachIndexed { index, rule ->
                        CloudflareOpsResourceRow(
                            icon = Icons.Rounded.Shield,
                            title = rule.title,
                            subtitle = rule.rowSubtitle,
                            tint = if (rule.status?.lowercase() == "block") MaterialTheme.colorScheme.error else CloudflareOpsColors.Orange,
                            onClick = { viewModel.showItem(rule) },
                            testTag = "cloudflare.ruleset.rule.${rule.id}",
                        )
                        if (index < state.rules.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
    }
}
