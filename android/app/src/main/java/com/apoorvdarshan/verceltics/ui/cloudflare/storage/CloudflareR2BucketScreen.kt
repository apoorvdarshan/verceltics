package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Upload
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Configuration
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Jurisdictions
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareR2Object
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationHost
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDetailRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEmptySection
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsHero
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsLoading
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsMonospaceBlock
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsPanel
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsResourceRow
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsSectionHeader
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareWriteNotice
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton

@Composable
fun CloudflareR2BucketRoute(
    client: CloudflareRestClient,
    accountId: String,
    bucketName: String,
    jurisdiction: String?,
    context: CloudflareOperationsContext,
    routeKey: String,
    modifier: Modifier = Modifier,
) {
    val files = rememberStorageFiles()
    val viewModel = viewModel(key = routeKey) { CloudflareR2BucketViewModel(storageApi(client), files, accountId, bucketName, jurisdiction) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudflareStorageRefreshEffect(context.refreshSignal) { viewModel.load() }
    LaunchedEffect(state.didDelete) { if (state.didDelete) context.close() }
    CloudflareR2BucketScreen(viewModel, modifier)
}

/** iOS `CloudflareR2BucketView` with typed object browsing, upload, download and deletion. */
@Composable
fun CloudflareR2BucketScreen(viewModel: CloudflareR2BucketViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val banner by viewModel.banner.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var pendingDownloadKey by rememberSaveable { mutableStateOf<String?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.prepareUpload(it.toString()) }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val key = pendingDownloadKey
        pendingDownloadKey = null
        if (uri != null && key != null) viewModel.download(key, uri.toString())
    }
    val bucket = state.bucket
    CloudflareConfirmationHost(viewModel)

    state.upload?.let { draft ->
        CloudflareOpsEditorSheet(
            title = "Upload object",
            onDismiss = { if (CloudflareR2BucketViewModel.UPLOAD_WORKING_ID !in working) viewModel.dismissUpload() },
            testTag = "cloudflare.storage.r2.uploadSheet",
        ) {
            CloudflareOpsDetailRow("File", draft.file.name)
            CloudflareOpsDetailRow("Size", CloudflareFormat.bytes(draft.file.size.toLong()))
            CloudflareOpsTextField(
                value = draft.key,
                onValueChange = { value -> viewModel.updateUpload { it.copy(key = value, error = null) } },
                label = "Object key",
                monospace = true,
                testTag = "cloudflare.storage.r2.uploadKey",
            )
            CloudflareOpsTextField(
                value = draft.contentType,
                onValueChange = { value -> viewModel.updateUpload { it.copy(contentType = value) } },
                label = "Content type",
            )
            draft.error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
            CloudflareWriteNotice()
            ThemedActionButton(
                text = "UPLOAD",
                onClick = viewModel::requestUpload,
                enabled = draft.key.isNotEmpty(),
                isBusy = CloudflareR2BucketViewModel.UPLOAD_WORKING_ID in working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                testTag = "cloudflare.storage.r2.uploadSubmit",
            )
        }
    }

    CloudflareOpsScreen("cloudflare.storage.r2Screen", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = bucket.name,
                subtitle = "Cloudflare R2 bucket",
                icon = Icons.Rounded.Inventory2,
                status = "AVAILABLE",
                statusColor = CloudflareOpsColors.Green,
            ) {
                CloudflareOpsActionButton(
                    title = "Open Cloudflare Dashboard",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    onClick = {
                        runCatching {
                            uriHandler.openUri(
                                "https://dash.cloudflare.com/${viewModel.accountId}/r2/${bucket.jurisdiction ?: "default"}/buckets/${bucket.name}",
                            )
                        }
                    },
                )
            }
        }
        item("metadata") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Bucket Metadata", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Name", bucket.name, copyable = true)
                CloudflareOpsDetailRow("Jurisdiction", bucket.jurisdiction?.uppercase() ?: "DEFAULT")
                CloudflareOpsDetailRow("Location", bucket.location?.uppercase() ?: "Automatic")
                CloudflareOpsDetailRow("Default storage class", CloudflareR2Jurisdictions.storageClassLabel(bucket.storageClass))
                bucket.createdDate?.let { CloudflareOpsDetailRow("Created", CloudflareDates.formatDateTime(it)) }
            }
        }
        item("notice") { CloudflareWriteNotice() }
        banner?.let { item("banner") { CloudflareActionResultBanner(it, viewModel::dismissBanner) } }
        item("objects") {
            CloudflareOpsPanel(testTag = "cloudflare.storage.r2.objects") {
                CloudflareOpsSectionHeader(
                    title = "Objects",
                    icon = Icons.Rounded.Description,
                    count = state.objects.size,
                    actionTitle = "Upload",
                    actionTestTag = "cloudflare.storage.r2.upload",
                    onAction = { uploadPicker.launch(arrayOf("*/*")) },
                )
                CloudflareOpsDivider()
                if (state.prefix.isNotEmpty()) {
                    CloudflareOpsResourceRow(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        title = state.prefix,
                        subtitle = "Up one folder",
                        tint = CloudflareOpsColors.Amber,
                        testTag = "cloudflare.storage.r2.parentFolder",
                        onClick = viewModel::openParentFolder,
                    )
                    CloudflareOpsDivider()
                }
                when {
                    state.isLoadingObjects -> CloudflareOpsLoading("Listing objects…")
                    state.objectsError != null -> CloudflareOpsEmptySection(
                        Icons.Rounded.WarningAmber,
                        "Objects unavailable",
                        requireNotNull(state.objectsError),
                    )
                    state.objects.isEmpty() && state.folders.isEmpty() -> CloudflareOpsEmptySection(
                        Icons.Rounded.Description,
                        "No objects",
                        "Upload a file to store it in this bucket.",
                    )
                    else -> {
                        state.folders.forEach { folder ->
                            CloudflareOpsResourceRow(
                                icon = Icons.Rounded.Folder,
                                title = CloudflareR2BucketViewModel.folderName(folder, state.prefix),
                                subtitle = "Folder",
                                tint = CloudflareOpsColors.Amber,
                                testTag = "cloudflare.storage.r2.folder.$folder",
                                onClick = { viewModel.openFolder(folder) },
                            )
                            CloudflareOpsDivider(inset = true)
                        }
                        state.objects.forEachIndexed { index, item ->
                            R2ObjectRow(
                                item = item,
                                parent = state.prefix,
                                isDownloading = CloudflareR2BucketViewModel.downloadWorkingId(item.key) in working,
                                isDeleting = item.key in working,
                                onDownload = {
                                    pendingDownloadKey = item.key
                                    downloadPicker.launch(item.key.substringAfterLast('/'))
                                },
                                onDelete = { viewModel.requestDeleteObject(item) },
                            )
                            if (index < state.objects.lastIndex) CloudflareOpsDivider(inset = true)
                        }
                        if (state.nextCursor != null) {
                            CloudflareOpsDivider()
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                                CloudflareOpsActionButton(
                                    title = "Load more",
                                    icon = Icons.Rounded.Download,
                                    onClick = viewModel::loadMoreObjects,
                                    working = state.isLoadingMore,
                                    testTag = "cloudflare.storage.r2.loadMore",
                                )
                            }
                        }
                    }
                }
            }
        }
        item("configuration") {
            CloudflareOpsPanel(testTag = "cloudflare.storage.r2.configuration") {
                CloudflareOpsSectionHeader("Configuration", Icons.Rounded.Settings)
                CloudflareOpsDivider()
                CloudflareR2Configuration.entries.forEachIndexed { index, configuration ->
                    val configurationState = state.configurations[configuration]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(configuration.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            CloudflareOpsActionButton(
                                title = if (configurationState == null) "Load" else "Reload",
                                icon = Icons.Rounded.Download,
                                onClick = { viewModel.loadConfiguration(configuration) },
                                working = configurationState is CloudflareR2ConfigurationState.Loading,
                                testTag = "cloudflare.storage.r2.config.${configuration.name}",
                            )
                        }
                        when (configurationState) {
                            is CloudflareR2ConfigurationState.Loaded -> CloudflareOpsMonospaceBlock(configurationState.json, maxLines = 40)
                            is CloudflareR2ConfigurationState.Failed -> Text(
                                configurationState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Unit
                        }
                    }
                    if (index < CloudflareR2Configuration.entries.lastIndex) CloudflareOpsDivider()
                }
            }
        }
        item("danger") {
            CloudflareOpsPanel(accent = 0.06f) {
                CloudflareOpsSectionHeader("Danger Zone", Icons.Rounded.WarningAmber)
                CloudflareOpsDivider()
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Delete bucket", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "The bucket must be empty before Cloudflare will delete it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    CloudflareOpsActionButton(
                        title = "Delete",
                        icon = Icons.Rounded.Delete,
                        onClick = viewModel::requestDeleteBucket,
                        destructive = true,
                        working = CloudflareR2BucketViewModel.DELETE_WORKING_ID in working,
                        testTag = "cloudflare.storage.r2.deleteBucket",
                    )
                }
            }
        }
    }
}

@Composable
private fun R2ObjectRow(
    item: CloudflareR2Object,
    parent: String,
    isDownloading: Boolean,
    isDeleting: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    CloudflareOpsResourceRow(
        icon = Icons.Rounded.Description,
        title = CloudflareR2BucketViewModel.objectName(item.key, parent),
        subtitle = CloudflareR2BucketViewModel.objectSubtitle(item),
        testTag = "cloudflare.storage.r2.object.${item.key}",
    ) {
        if (isDownloading || isDeleting) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CloudflareOpsColors.Orange)
        } else {
            IconButton(onClick = onDownload, modifier = Modifier.testTag("cloudflare.storage.r2.download.${item.key}")) {
                Icon(Icons.Rounded.Download, contentDescription = "Download ${item.key}")
            }
            IconButton(onClick = onDelete, modifier = Modifier.testTag("cloudflare.storage.r2.deleteObject.${item.key}")) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete ${item.key}", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
