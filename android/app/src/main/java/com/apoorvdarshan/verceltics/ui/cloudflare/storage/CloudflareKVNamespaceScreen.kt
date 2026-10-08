package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareKVKey
import com.apoorvdarshan.verceltics.data.cloudflare.storage.cloudflareStorageDisplayValue
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsChoiceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ControlSearchField
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

@Composable
fun CloudflareKVNamespaceRoute(
    client: CloudflareRestClient,
    accountId: String,
    namespaceId: String,
    title: String,
    context: CloudflareOperationsContext,
    routeKey: String,
    modifier: Modifier = Modifier,
) {
    val viewModel = viewModel(key = routeKey) { CloudflareKVNamespaceViewModel(storageApi(client), accountId, namespaceId, title) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudflareStorageRefreshEffect(context.refreshSignal) { viewModel.load() }
    LaunchedEffect(state.didDelete) { if (state.didDelete) context.close() }
    CloudflareKVNamespaceScreen(viewModel, modifier)
}

/** iOS `CloudflareKVNamespaceView`: keys, the value editor, rename and deletion. */
@Composable
fun CloudflareKVNamespaceScreen(viewModel: CloudflareKVNamespaceViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    CloudflareConfirmationHost(viewModel)

    state.editor?.let { editor ->
        KVValueEditorSheet(
            editor = editor,
            isSaving = editor.keyName.trim() in working,
            onChange = viewModel::updateEditor,
            onSave = viewModel::requestSaveValue,
            onDismiss = viewModel::dismissEditor,
        )
    }
    if (state.isRenaming) {
        KVRenameSheet(
            currentTitle = state.namespace.title,
            error = state.renameError,
            isSaving = CloudflareKVNamespaceViewModel.RENAME_WORKING_ID in working,
            onRename = viewModel::requestRename,
            onDismiss = viewModel::dismissRename,
        )
    }

    val keys = state.keys.filter {
        query.isBlank() || it.name.contains(query, true) ||
            (it.metadata != null && cloudflareStorageDisplayValue(it.metadata).contains(query, true))
    }

    CloudflareOpsScreen("cloudflare.storage.kvScreen", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = state.namespace.title,
                subtitle = "Workers KV namespace",
                icon = Icons.Rounded.Key,
                status = "${state.keys.size} KEYS",
                statusColor = CloudflareOpsColors.Green,
            ) {
                CloudflareOpsActionButton(
                    title = "Rename",
                    icon = Icons.Rounded.Edit,
                    onClick = viewModel::openRename,
                    testTag = "cloudflare.storage.kv.rename",
                )
            }
        }
        item("id") {
            CloudflareOpsPanel { CloudflareOpsDetailRow("Namespace ID", state.namespace.id, monospace = true, copyable = true) }
        }
        item("notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, viewModel::dismissBanner) } }
        item("search") {
            ControlSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search KV keys",
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.storage.kv.search",
            )
        }
        item("keys") {
            CloudflareOpsPanel(testTag = "cloudflare.storage.kv.keys") {
                CloudflareOpsSectionHeader(
                    title = "Keys",
                    icon = Icons.Rounded.Key,
                    count = keys.size,
                    actionTitle = "Write",
                    actionTestTag = "cloudflare.storage.kv.write",
                    onAction = { viewModel.openEditor(null) },
                )
                CloudflareOpsDivider()
                when {
                    state.isLoading -> CloudflareOpsLoading()
                    keys.isEmpty() -> CloudflareOpsEmptySection(
                        icon = if (query.isBlank()) Icons.Rounded.Key else Icons.Rounded.Search,
                        title = if (query.isBlank()) "No keys" else "No matches",
                        message = if (query.isBlank()) "Write the first value to this namespace." else "No KV keys match your search.",
                    )
                    else -> keys.forEachIndexed { index, key ->
                        KVKeyRow(
                            key = key,
                            isWorking = key.name in working,
                            onOpen = { viewModel.openEditor(key) },
                            onDelete = { viewModel.requestDeleteKey(key) },
                        )
                        if (index < keys.lastIndex) CloudflareOpsDivider(inset = true)
                    }
                }
            }
        }
        item("danger") {
            CloudflareOpsPanel(accent = 0.06f) {
                CloudflareOpsSectionHeader("Danger Zone", Icons.Rounded.WarningAmber)
                CloudflareOpsDivider()
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Delete namespace", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "This permanently removes the namespace and every value.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    CloudflareOpsActionButton(
                        title = "Delete",
                        icon = Icons.Rounded.Delete,
                        onClick = viewModel::requestDeleteNamespace,
                        destructive = true,
                        working = CloudflareKVNamespaceViewModel.DELETE_WORKING_ID in working,
                        testTag = "cloudflare.storage.kv.deleteNamespace",
                    )
                }
            }
        }
    }
}

@Composable
private fun KVKeyRow(key: CloudflareKVKey, isWorking: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    CloudflareOpsResourceRow(
        icon = Icons.Rounded.Key,
        title = key.name,
        subtitle = CloudflareKVNamespaceViewModel.keySubtitle(key),
        tint = if (key.expiration == null) CloudflareOpsColors.Amber else CloudflareOpsColors.Orange,
        testTag = "cloudflare.storage.kv.key.${key.name}",
        onClick = onOpen,
    ) {
        if (isWorking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
        } else {
            IconButton(onClick = onDelete, modifier = Modifier.testTag("cloudflare.storage.kv.deleteKey.${key.name}")) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${key.name}", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** iOS `CloudflareKVValueEditor`. */
@Composable
private fun KVValueEditorSheet(
    editor: CloudflareKVEditorState,
    isSaving: Boolean,
    onChange: ((CloudflareKVEditorState) -> CloudflareKVEditorState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    CloudflareOpsEditorSheet(
        title = if (editor.existingKey == null) "Write KV Value" else editor.keyName,
        onDismiss = { if (!isSaving) onDismiss() },
        testTag = "cloudflare.storage.kv.editor",
    ) {
        CloudflareOpsTextField(
            value = editor.keyName,
            onValueChange = { value -> onChange { it.copy(keyName = value) } },
            label = "Key",
            placeholder = "settings/theme",
            enabled = editor.existingKey == null,
            testTag = "cloudflare.storage.kv.editor.key",
        )
        CloudflareOpsChoiceRow(
            options = CloudflareKVEncoding.entries,
            selected = editor.encoding,
            label = CloudflareKVEncoding::label,
            onSelect = { encoding -> onChange { it.copy(encoding = encoding) } },
            testTagPrefix = "cloudflare.storage.kv.editor.encoding",
        )
        CloudflareOpsTextField(
            value = editor.contentType,
            onValueChange = { value -> onChange { it.copy(contentType = value) } },
            label = "Content type",
            placeholder = "text/plain",
        )
        CloudflareOpsTextField(
            value = editor.expirationTtl,
            onValueChange = { value -> onChange { it.copy(expirationTtl = value.filter(Char::isDigit)) } },
            label = "Expires in (seconds)",
            placeholder = "Never",
            keyboardType = KeyboardType.Number,
            testTag = "cloudflare.storage.kv.editor.ttl",
        )
        if (editor.isLoadingValue) {
            CloudflareOpsLoading("Reading value…")
        } else {
            CloudflareOpsTextField(
                value = editor.value,
                onValueChange = { value -> onChange { it.copy(value = value) } },
                label = if (editor.encoding == CloudflareKVEncoding.TEXT) "Text value" else "Base64 value",
                singleLine = false,
                minLines = 6,
                monospace = true,
                testTag = "cloudflare.storage.kv.editor.value",
            )
        }
        editor.error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        CloudflareWriteNotice()
        ThemedActionButton(
            text = "SAVE",
            onClick = onSave,
            enabled = editor.canSave,
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            testTag = "cloudflare.storage.kv.editor.save",
        )
    }
}

/** iOS `CloudflareKVRenameView`. */
@Composable
private fun KVRenameSheet(
    currentTitle: String,
    error: String?,
    isSaving: Boolean,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable(currentTitle) { mutableStateOf(currentTitle) }
    CloudflareOpsEditorSheet(title = "Rename Namespace", onDismiss = { if (!isSaving) onDismiss() }, testTag = "cloudflare.storage.kv.renameSheet") {
        CloudflareOpsTextField(title, { title = it }, "Title", placeholder = "APPLICATION_CACHE", testTag = "cloudflare.storage.kv.renameTitle")
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        CloudflareWriteNotice()
        ThemedActionButton(
            text = "RENAME",
            onClick = { onRename(title) },
            enabled = title.isNotBlank() && title.trim() != currentTitle,
            isBusy = isSaving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            testTag = "cloudflare.storage.kv.renameSubmit",
        )
    }
}
