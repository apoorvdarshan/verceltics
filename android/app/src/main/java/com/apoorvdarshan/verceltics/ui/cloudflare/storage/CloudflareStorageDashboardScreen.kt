package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.hosting.ProviderLayout
import com.apoorvdarshan.verceltics.ui.hosting.ProviderTwoPane
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2CreateInput
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Jurisdictions
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDropdownField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsIconTile
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsStatusPill
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

/** The Storage & databases screen for one account (iOS `CloudflareStorageDashboardView`). */
@Composable
fun CloudflareStorageDashboardRoute(
    client: CloudflareRestClient,
    accountId: String,
    accountName: String,
    context: CloudflareOperationsContext,
    routeKey: String,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = routeKey) {
        CloudflareStorageDashboardViewModel(storageApi(client), accountId, client.mutations, allowsR2 = context.allowsR2)
    }
    LaunchedEffect(viewModel) { viewModel.onAppear() }
    CloudflareStorageRefreshEffect(context.refreshSignal) { viewModel.load(force = true) }
    CloudflareStorageDashboardScreen(
        viewModel = viewModel,
        accountName = accountName,
        onOpenD1 = { database -> context.navigate(CloudflareStorageRoutes.d1(accountId, database.uuid, database.name)) },
        onOpenKV = { namespace -> context.navigate(CloudflareStorageRoutes.kv(accountId, namespace.id, namespace.title)) },
        onOpenR2 = { bucket -> context.navigate(CloudflareStorageRoutes.r2(accountId, bucket.name, bucket.jurisdiction)) },
        modifier = modifier,
    )
}

@Composable
fun CloudflareStorageDashboardScreen(
    viewModel: CloudflareStorageDashboardViewModel,
    accountName: String,
    onOpenD1: (com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareD1Database) -> Unit,
    onOpenKV: (com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVNamespace) -> Unit,
    onOpenR2: (com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Bucket) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    CloudflareConfirmationHost(viewModel)

    state.creation?.let { creation ->
        CloudflareStorageCreateSheet(
            creation = creation,
            error = state.creationError,
            isSaving = CloudflareStorageDashboardViewModel.CREATE_WORKING_ID in working,
            onDismiss = viewModel::dismissCreation,
            onCreateD1 = viewModel::requestCreateD1,
            onCreateKV = viewModel::requestCreateKV,
            onCreateR2 = viewModel::requestCreateR2,
        )
    }

    val databases = state.databases.filter { query.isBlank() || it.name.contains(query, true) || it.uuid.contains(query, true) || it.jurisdiction?.contains(query, true) == true }
    val namespaces = state.namespaces.filter { query.isBlank() || it.title.contains(query, true) || it.id.contains(query, true) }
    val buckets = state.buckets.filter {
        query.isBlank() || it.name.contains(query, true) || it.location?.contains(query, true) == true ||
            it.jurisdiction?.contains(query, true) == true || it.storageClass?.contains(query, true) == true
    }

    val d1Panel: @Composable () -> Unit = {
        StorageSectionPanel(
            key = "d1",
            title = "D1 Databases",
            icon = Icons.Rounded.TableChart,
            count = databases.size,
            emptyTitle = "No D1 databases",
            emptyMessage = "Create a serverless SQL database for this account.",
            onCreate = { viewModel.openCreation(CloudflareStorageCreation.D1) },
        ) {
            databases.forEachIndexed { index, database ->
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.TableChart,
                    title = database.name,
                    subtitle = CloudflareStorageDashboardViewModel.d1Subtitle(database),
                    testTag = "cloudflare.storage.d1.${database.uuid}",
                    onClick = { onOpenD1(database) },
                )
                if (index < databases.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
    val kvPanel: @Composable () -> Unit = {
        StorageSectionPanel(
            key = "kv",
            title = "Workers KV",
            icon = Icons.Rounded.Key,
            count = namespaces.size,
            emptyTitle = "No KV namespaces",
            emptyMessage = "Create a namespace to store globally distributed key-value data.",
            onCreate = { viewModel.openCreation(CloudflareStorageCreation.KV) },
        ) {
            namespaces.forEachIndexed { index, namespace ->
                CloudflareOpsResourceRow(
                    icon = Icons.Rounded.Key,
                    title = namespace.title,
                    subtitle = namespace.id,
                    tint = CloudflareOpsColors.Amber,
                    testTag = "cloudflare.storage.kv.${namespace.id}",
                    onClick = { onOpenKV(namespace) },
                )
                if (index < namespaces.lastIndex) CloudflareOpsDivider(inset = true)
            }
        }
    }
    val r2Panel: @Composable () -> Unit = {
        if (!viewModel.allowsR2) {
            CloudflareOpsPanel(testTag = "cloudflare.storage.section.r2") {
                CloudflareOpsEmptySection(
                    Icons.Rounded.Inventory2,
                    "R2 requires a scoped token",
                    CLOUDFLARE_R2_REQUIRES_TOKEN_MESSAGE,
                    testTag = "cloudflare.storage.r2.requiresToken",
                )
            }
        } else {
            StorageSectionPanel(
                key = "r2",
                title = "R2 Buckets",
                icon = Icons.Rounded.Inventory2,
                count = buckets.size,
                emptyTitle = "No R2 buckets",
                emptyMessage = "Create an object-storage bucket for this account.",
                onCreate = { viewModel.openCreation(CloudflareStorageCreation.R2) },
            ) {
                buckets.forEachIndexed { index, bucket ->
                    CloudflareOpsResourceRow(
                        icon = Icons.Rounded.Inventory2,
                        title = bucket.name,
                        subtitle = CloudflareStorageDashboardViewModel.r2Subtitle(bucket),
                        tint = CloudflareOpsColors.Green,
                        testTag = "cloudflare.storage.r2.${bucket.name}",
                        onClick = { onOpenR2(bucket) },
                    )
                    if (index < buckets.lastIndex) CloudflareOpsDivider(inset = true)
                }
            }
        }
    }

    CloudflareOpsScreen(
        "cloudflare.storage.dashboard",
        modifier,
        maximumContentWidth = ProviderLayout.DashboardMaxWidth,
        isRefreshing = state.isLoading || state.isRefreshing,
        onRefresh = { viewModel.load(force = true) },
    ) { page ->
        item("header") { StorageHeader(accountName, state) }
        item("notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, viewModel::dismissBanner) } }
        if (state.warnings.isNotEmpty()) item("warnings") { StorageWarnings(state.warnings) }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search storage",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.storage.search",
            )
        }
        if (state.isLoading) {
            item("loading") { CloudflareOpsLoading("Loading storage…") }
        } else if (query.isNotBlank() && databases.isEmpty() && namespaces.isEmpty() && buckets.isEmpty()) {
            item("no-results") {
                CloudflareOpsPanel {
                    CloudflareOpsEmptySection(Icons.Rounded.Search, "No results", "Nothing in storage matches “$query”.")
                }
            }
        } else if (page.fitsTwoPanes(primaryMinimumWidth = 380.dp, secondaryMinimumWidth = 380.dp)) {
            // iOS `AppAdaptiveTwoPane(primaryMinimumWidth: 380, secondaryMinimumWidth: 380)`.
            item("sections-two-pane") {
                ProviderTwoPane(
                    twoPanes = true,
                    primary = { d1Panel() },
                    secondary = {
                        kvPanel()
                        r2Panel()
                    },
                )
            }
        } else {
            item("section-d1") { d1Panel() }
            item("section-kv") { kvPanel() }
            item(if (viewModel.allowsR2) "section-r2" else "r2-requires-token") { r2Panel() }
        }
    }
}

@Composable
private fun StorageSectionPanel(
    key: String,
    title: String,
    icon: ImageVector,
    count: Int,
    emptyTitle: String,
    emptyMessage: String,
    onCreate: () -> Unit,
    rows: @Composable () -> Unit,
) {
    CloudflareOpsPanel(testTag = "cloudflare.storage.section.$key") {
        CloudflareOpsSectionHeader(
            title = title,
            icon = icon,
            count = count,
            actionTitle = "Create",
            actionTestTag = "cloudflare.storage.create.$key",
            onAction = onCreate,
        )
        CloudflareOpsDivider()
        if (count == 0) CloudflareOpsEmptySection(icon, emptyTitle, emptyMessage) else rows()
    }
}

@Composable
private fun StorageHeader(accountName: String, state: CloudflareStorageDashboardState) {
    CloudflareOpsPanel(accent = 0.09f, testTag = "cloudflare.storage.header") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CloudflareOpsIconTile(Icons.Rounded.Storage, size = 46)
                Column(Modifier.weight(1f)) {
                    Text("Developer Storage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (accountName.isNotBlank()) {
                        Text(accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                CloudflareOpsStatusPill(if (state.isRefreshing) "REFRESHING" else "LIVE", CloudflareOpsColors.Green)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StorageNode("D1", state.databases.size, Icons.Rounded.TableChart, Modifier.weight(1f))
                StorageNode("KV", state.namespaces.size, Icons.Rounded.Key, Modifier.weight(1f))
                StorageNode("R2", state.buckets.size, Icons.Rounded.Inventory2, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StorageNode(title: String, value: Int, icon: ImageVector, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CloudflareOpsColors.Orange, modifier = Modifier.size(14.dp))
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StorageWarnings(warnings: List<String>) {
    CloudflareOpsPanel(testTag = "cloudflare.storage.warnings") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            warnings.forEach { warning ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = CloudflareOpsColors.Amber, modifier = Modifier.size(16.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val JURISDICTIONS = listOf("default", "eu", "fedramp")
private val LOCATION_HINTS = listOf("automatic") + CloudflareR2Jurisdictions.LOCATION_HINTS

private fun jurisdictionLabel(value: String, defaultLabel: String) = when (value) {
    "default" -> defaultLabel
    "eu" -> "European Union"
    "fedramp" -> "FedRAMP"
    else -> value.uppercase()
}

/** iOS `CloudflareD1CreateView`, `CloudflareKVNamespaceCreateView` and `CloudflareR2CreateView`. */
@Composable
private fun CloudflareStorageCreateSheet(
    creation: CloudflareStorageCreation,
    error: String?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onCreateD1: (CloudflareD1CreateInput) -> Unit,
    onCreateKV: (String) -> Unit,
    onCreateR2: (CloudflareR2CreateInput) -> Unit,
) {
    var name by rememberSaveable(creation) { mutableStateOf("") }
    var jurisdiction by rememberSaveable(creation) { mutableStateOf("default") }
    var locationHint by rememberSaveable(creation) { mutableStateOf("automatic") }
    var replication by rememberSaveable(creation) { mutableStateOf("disabled") }
    var storageClass by rememberSaveable(creation) { mutableStateOf("Standard") }
    val title = when (creation) {
        CloudflareStorageCreation.D1 -> "Create D1 Database"
        CloudflareStorageCreation.KV -> "Create KV Namespace"
        CloudflareStorageCreation.R2 -> "Create R2 Bucket"
    }
    CloudflareOpsEditorSheet(title = title, onDismiss = { if (!isSaving) onDismiss() }, testTag = "cloudflare.storage.createSheet") {
        when (creation) {
            CloudflareStorageCreation.D1 -> {
                CloudflareOpsTextField(name, { name = it }, "Name", placeholder = "application-db", testTag = "cloudflare.storage.create.name")
                CloudflareOpsDropdownField("Jurisdiction", JURISDICTIONS, jurisdiction, { jurisdictionLabel(it, "Automatic") }, { jurisdiction = it })
                CloudflareOpsDropdownField(
                    "Primary location",
                    LOCATION_HINTS,
                    locationHint,
                    { if (it == "automatic") "Automatic" else it.uppercase() },
                    { locationHint = it },
                    enabled = jurisdiction == "default",
                )
                CloudflareOpsDropdownField(
                    "Read replicas",
                    listOf("disabled", "auto"),
                    replication,
                    { if (it == "auto") "Automatic" else "Disabled" },
                    { replication = it },
                )
            }
            CloudflareStorageCreation.KV -> {
                CloudflareOpsTextField(name, { name = it }, "Title", placeholder = "APPLICATION_CACHE", testTag = "cloudflare.storage.create.name")
            }
            CloudflareStorageCreation.R2 -> {
                CloudflareOpsTextField(
                    name,
                    { name = it },
                    "Name",
                    placeholder = "application-assets",
                    supportingText = "3–63 lowercase letters, numbers or hyphens.",
                    testTag = "cloudflare.storage.create.name",
                )
                CloudflareOpsDropdownField("Jurisdiction", JURISDICTIONS, jurisdiction, { jurisdictionLabel(it, "Default") }, { jurisdiction = it })
                CloudflareOpsDropdownField(
                    "Location hint",
                    LOCATION_HINTS,
                    locationHint,
                    { if (it == "automatic") "Automatic" else it.uppercase() },
                    { locationHint = it },
                    enabled = jurisdiction == "default",
                )
                CloudflareOpsDropdownField(
                    "Storage class",
                    listOf("Standard", "InfrequentAccess"),
                    storageClass,
                    CloudflareR2Jurisdictions::storageClassLabel,
                    { storageClass = it },
                )
            }
        }
        error?.let { CloudflareActionResultBanner(com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner(it, isError = true)) }
        CloudflareWriteNotice()
        ThemedActionButton(
            text = "CREATE",
            onClick = {
                val trimmed = name.trim()
                val scoped = jurisdiction.takeUnless { it == "default" }
                val hint = locationHint.takeIf { jurisdiction == "default" && it != "automatic" }
                when (creation) {
                    CloudflareStorageCreation.D1 -> onCreateD1(CloudflareD1CreateInput(trimmed, scoped, hint, replication))
                    CloudflareStorageCreation.KV -> onCreateKV(trimmed)
                    CloudflareStorageCreation.R2 -> onCreateR2(CloudflareR2CreateInput(trimmed, scoped, hint, storageClass))
                }
            },
            enabled = name.isNotBlank(),
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            testTag = "cloudflare.storage.create.submit",
        )
    }
}
