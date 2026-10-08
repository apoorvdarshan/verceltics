package com.apoorvdarshan.verceltics.ui.cloudflare.tools

import android.content.ClipData
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.CloudflareAuthMode
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareBodyEncoding
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareExplorerRequestBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartBuilder
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartBody
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartFieldSpec
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareMultipartPart
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareRawResponse
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsErrors
import com.apoorvdarshan.verceltics.data.cloudflare.tools.CloudflareToolsException
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import com.apoorvdarshan.verceltics.ui.components.ThemedModalBottomSheet
import com.apoorvdarshan.verceltics.ui.components.contrastingContentColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Displayed response text is bounded so huge payloads stay responsive. */
internal const val RESPONSE_DISPLAY_LIMIT_CHARACTERS = 200_000

/** Clipboard writes travel through Binder; keep them comfortably below its transaction limit. */
internal const val RESPONSE_COPY_LIMIT_CHARACTERS = 300_000

/** Port of iOS `CloudflareAPIExplorerView`. */
@Composable
internal fun CloudflareApiExplorerScreen(
    state: CloudflareExplorerUiState,
    accountId: String?,
    isOfflineSample: Boolean,
    onSelectMethod: (CloudflareHttpMethod) -> Unit,
    onUpdatePath: (String) -> Unit,
    onUpdateQuery: (String) -> Unit,
    onUpdateHeaders: (String) -> Unit,
    onUpdateBody: (String) -> Unit,
    onUpdateContentType: (String) -> Unit,
    onUpdateEncoding: (CloudflareBodyEncoding) -> Unit,
    onQuickPath: (String) -> Unit,
    onExecute: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onCancel: () -> Unit,
    onImportBody: (ByteArray, String) -> Unit,
    onApplyMultipart: (CloudflareMultipartBody) -> Unit,
    onRemoveAttachment: () -> Unit,
    onReportError: (String) -> Unit,
    modifier: Modifier = Modifier,
    authMode: CloudflareAuthMode = CloudflareAuthMode.API_TOKEN,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showComposer by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val (bytes, name) = readBoundedDocument(context, uri, CloudflareExplorerRequestBuilder.MAXIMUM_BODY_BYTES)
                    onImportBody(bytes, name)
                } catch (error: Exception) {
                    onReportError("Could not import the request body: ${importErrorMessage(error)}")
                }
            }
        }
    }

    if (state.showConfirmation) {
        val method = state.draft.method
        ThemedAlertDialog(
            title = if (method == CloudflareHttpMethod.DELETE) "Send a destructive API request?" else "Send a write API request?",
            message = "${method.name} ${CloudflareExplorerRequestBuilder.displayPath(state.draft.path)}\n" +
                "This request can change live Cloudflare resources.",
            confirmText = "Send ${method.name} Request",
            confirmTone = if (method == CloudflareHttpMethod.DELETE) ThemedActionTone.DESTRUCTIVE else ThemedActionTone.PRIMARY,
            dismissText = "Cancel",
            onConfirm = onConfirm,
            onDismissRequest = onDismissConfirmation,
            testTag = "cloudflare.explorer.confirmation",
        )
    }

    if (showComposer) {
        CloudflareMultipartComposerSheet(
            schemaFields = state.multipartFields,
            onDismiss = { showComposer = false },
            onCompose = { body ->
                showComposer = false
                onApplyMultipart(body)
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 40.dp)
            .testTag("cloudflare.explorer"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExplorerNotice(state)
        if (isOfflineSample) {
            ToolBanner(
                message = "Sample workspace: requests are simulated on this device and never reach Cloudflare.",
                isError = false,
            )
        }
        ExplorerRequestPanel(
            state = state,
            accountId = accountId,
            onSelectMethod = onSelectMethod,
            onUpdatePath = onUpdatePath,
            onUpdateQuery = onUpdateQuery,
            onUpdateHeaders = onUpdateHeaders,
            onUpdateBody = onUpdateBody,
            onUpdateContentType = onUpdateContentType,
            onUpdateEncoding = onUpdateEncoding,
            onQuickPath = onQuickPath,
            onExecute = onExecute,
            onCancel = onCancel,
            onImport = { importLauncher.launch(arrayOf("*/*")) },
            onCompose = { showComposer = true },
            onRemoveAttachment = onRemoveAttachment,
        )
        state.error?.let { ToolBanner(it, isError = true, testTag = "cloudflare.explorer.error") }
        state.notice?.let { ToolBanner(it, isError = false, testTag = "cloudflare.explorer.notice") }
        val response = state.response
        if (response != null) {
            CloudflareToolsErrors.explorerHint(response.statusCode, state.permissions, authMode)?.let { hint ->
                ToolBanner(
                    hint,
                    isError = true,
                    title = if (authMode == CloudflareAuthMode.API_TOKEN) "Token scope" else "Account access",
                    testTag = "cloudflare.explorer.scopeHint",
                )
            }
            ExplorerResponsePanel(response)
        } else if (!state.isExecuting && state.error == null) {
            ToolPanel {
                ToolEmptyBlock(
                    title = "Ready for a request",
                    message = "Responses stay only in this screen and are never saved.",
                    icon = Icons.Rounded.Terminal,
                )
            }
        }
    }
}

@Composable
private fun ExplorerNotice(state: CloudflareExplorerUiState) {
    ToolPanel(accentAlpha = 0.07f) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = CloudflareToolsColors.Orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.title ?: "Direct access to Cloudflare’s v4 API", style = MaterialTheme.typography.titleSmall)
                Text(
                    state.summary
                        ?: "Use a relative path. Authentication headers are added securely. Request bodies can be UTF-8 or Base64, including prebuilt multipart payloads; credentials are never shown in the editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExplorerRequestPanel(
    state: CloudflareExplorerUiState,
    accountId: String?,
    onSelectMethod: (CloudflareHttpMethod) -> Unit,
    onUpdatePath: (String) -> Unit,
    onUpdateQuery: (String) -> Unit,
    onUpdateHeaders: (String) -> Unit,
    onUpdateBody: (String) -> Unit,
    onUpdateContentType: (String) -> Unit,
    onUpdateEncoding: (CloudflareBodyEncoding) -> Unit,
    onQuickPath: (String) -> Unit,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onCompose: () -> Unit,
    onRemoveAttachment: () -> Unit,
) {
    val draft = state.draft
    val methodColors = CloudflareHttpMethod.entries.associateWith { CloudflareToolsColors.method(it) }
    ToolPanel(accentAlpha = 0.045f) {
        ToolSectionHeader("Request", Icons.AutoMirrored.Rounded.Send)
        ToolSegmentedChoice(
            options = CloudflareHttpMethod.entries,
            selected = draft.method,
            label = { it.name },
            onSelect = onSelectMethod,
            selectedColor = { methodColors[it] },
            modifier = Modifier.padding(14.dp),
            testTagPrefix = "cloudflare.explorer.method",
        )
        ToolDivider()
        ToolTextEditor(
            label = "Relative path",
            value = draft.path,
            onValueChange = onUpdatePath,
            placeholder = "/accounts",
            singleLine = true,
            prefix = CloudflareExplorerRequestBuilder.API_PREFIX,
            modifier = Modifier.padding(16.dp),
            testTag = "cloudflare.explorer.path",
        )
        QuickPaths(accountId, onQuickPath)
        ToolDivider()
        ToolTextEditor(
            label = "Query parameters",
            value = draft.queryText,
            onValueChange = onUpdateQuery,
            placeholder = "page=1\nper_page=50",
            minHeight = 74.dp,
            modifier = Modifier.padding(16.dp),
            testTag = "cloudflare.explorer.query",
        )
        ToolDivider()
        ToolTextEditor(
            label = "Optional request headers",
            value = draft.headerText,
            onValueChange = onUpdateHeaders,
            placeholder = "If-Match: etag\nAccept: application/json",
            minHeight = 74.dp,
            modifier = Modifier.padding(16.dp),
            testTag = "cloudflare.explorer.headers",
        )
        if (draft.method != CloudflareHttpMethod.GET) {
            ToolDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToolTextEditor(
                    label = "Content type",
                    value = draft.contentType,
                    onValueChange = onUpdateContentType,
                    placeholder = "application/json",
                    singleLine = true,
                    testTag = "cloudflare.explorer.contentType",
                )
                ToolSegmentedChoice(
                    options = CloudflareBodyEncoding.entries,
                    selected = draft.bodyEncoding,
                    label = { it.label },
                    onSelect = onUpdateEncoding,
                    testTagPrefix = "cloudflare.explorer.encoding",
                )
                TextButton(
                    onClick = onImport,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("cloudflare.explorer.import"),
                ) {
                    Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = CloudflareToolsColors.Orange)
                    Spacer(Modifier.width(6.dp))
                    Text("Import raw body file as Base64", color = CloudflareToolsColors.Orange, style = MaterialTheme.typography.labelLarge)
                }
                if (draft.contentType.contains("multipart/form-data", ignoreCase = true)) {
                    TextButton(
                        onClick = onCompose,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("cloudflare.explorer.compose"),
                    ) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = CloudflareToolsColors.Orange)
                        Spacer(Modifier.width(6.dp))
                        Text("Compose fields and files", color = CloudflareToolsColors.Orange, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "The composer adds the matching boundary and converts the complete binary body to Base64. You can still paste a prebuilt multipart body manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CloudflareToolsColors.warning(),
                    )
                }
            }
            ToolDivider()
            val attachment = state.attachment
            if (attachment != null) {
                AttachmentCard(attachment, onRemoveAttachment)
            } else {
                ToolTextEditor(
                    label = "Request body · ${draft.bodyEncoding.label}",
                    value = draft.bodyText,
                    onValueChange = onUpdateBody,
                    placeholder = if (draft.bodyEncoding == CloudflareBodyEncoding.BASE64) {
                        "Paste a Base64-encoded raw request body"
                    } else {
                        "{\n  \"key\": \"value\"\n}"
                    },
                    minHeight = 150.dp,
                    maxHeight = 420.dp,
                    modifier = Modifier.padding(16.dp),
                    testTag = "cloudflare.explorer.body",
                )
            }
        }
        ExecuteButton(state, onExecute, onCancel)
    }
}

@Composable
private fun QuickPaths(accountId: String?, onQuickPath: (String) -> Unit) {
    val paths = buildList {
        add("Accounts" to "/accounts")
        add("Zones" to (accountId?.let { "/zones?account.id=$it" } ?: "/zones"))
        if (accountId != null) {
            add("Pages" to "/accounts/$accountId/pages/projects")
            add("Workers" to "/accounts/$accountId/workers/scripts")
            add("D1" to "/accounts/$accountId/d1/database")
            add("R2" to "/accounts/$accountId/r2/buckets")
            add("KV" to "/accounts/$accountId/storage/kv/namespaces")
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        paths.forEach { (title, path) ->
            Surface(
                onClick = { onQuickPath(path) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cloudflare.explorer.quick.$title"),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 13.dp))
                }
            }
        }
    }
}

@Composable
private fun AttachmentCard(attachment: CloudflareExplorerAttachmentUi, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("cloudflare.explorer.attachment"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIconTile(Icons.Rounded.Description)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(attachment.label, style = MaterialTheme.typography.titleSmall)
            Text(
                "${formatBytes(attachment.sizeBytes)} · Base64 body attached. Large bodies are sent as-is and not shown in the editor.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Delete, contentDescription = "Remove attached body")
        }
    }
}

@Composable
private fun ExecuteButton(state: CloudflareExplorerUiState, onExecute: () -> Unit, onCancel: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val method = state.draft.method
    val color = CloudflareToolsColors.method(method)
    val enabled = state.isExecuting || state.canExecute
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            if (state.isExecuting) onCancel() else onExecute()
        },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .heightIn(min = 50.dp)
            .testTag("cloudflare.explorer.execute"),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) color else color.copy(alpha = 0.35f),
        contentColor = contrastingContentColor(color),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (state.isExecuting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = contrastingContentColor(color))
                Spacer(Modifier.width(9.dp))
                Text("Sending · tap to cancel", style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Execute ${method.name}", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ExplorerResponsePanel(response: CloudflareRawResponse) {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var copied by remember(response) { mutableStateOf(false) }
    var showingHeaders by rememberSaveable { mutableStateOf(false) }
    // Responses can be 32 MB: only a bounded preview is ever decoded for display.
    val preview = remember(response) { response.preview(RESPONSE_DISPLAY_LIMIT_CHARACTERS) }
    val displayed = preview.text
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val statusColor = if (response.isSuccess) CloudflareToolsColors.success() else CloudflareToolsColors.danger()
    ToolPanel(accentAlpha = if (response.isSuccess) 0.04f else 0f, testTag = "cloudflare.explorer.response") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (response.isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = statusColor,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "HTTP ${response.statusCode}",
                style = MonospaceSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("cloudflare.explorer.status"),
            )
            response.elapsedMillis?.let {
                Spacer(Modifier.width(9.dp))
                Text("$it ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    val copiedText = response.preview(RESPONSE_COPY_LIMIT_CHARACTERS).text
                    scope.launch {
                        runCatching {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Cloudflare response", copiedText)))
                        }
                    }
                    copied = true
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cloudflare.explorer.copy"),
            ) {
                Icon(
                    if (copied) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = if (copied) CloudflareToolsColors.success() else CloudflareToolsColors.Orange,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (copied) "Copied" else "Copy",
                    color = if (copied) CloudflareToolsColors.success() else CloudflareToolsColors.Orange,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        ToolDivider()
        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 520.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                displayed.ifEmpty { "<empty response body>" },
                style = MonospaceSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .testTag("cloudflare.explorer.body.response"),
            )
        }
        if (preview.truncated) {
            Text(
                "Showing the first ${RESPONSE_DISPLAY_LIMIT_CHARACTERS / 1_000}K characters of ${formatBytes(response.bodySize)}. " +
                    "Copy includes up to ${RESPONSE_COPY_LIMIT_CHARACTERS / 1_000}K characters.",
                style = MaterialTheme.typography.labelSmall,
                color = CloudflareToolsColors.warning(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ToolDivider()
        Surface(
            onClick = { showingHeaders = !showingHeaders },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("cloudflare.explorer.headersToggle"),
            color = androidx.compose.ui.graphics.Color.Transparent,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RESPONSE HEADERS · ${response.headers.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (showingHeaders) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (showingHeaders) "Hide response headers" else "Show response headers",
                )
            }
        }
        if (showingHeaders) {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    response.headers.forEach { (name, value) ->
                        Text("$name: $value", style = MonospaceSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Port of iOS `CloudflareMultipartComposerView` as a bottom sheet. */
@Composable
private fun CloudflareMultipartComposerSheet(
    schemaFields: List<CloudflareMultipartFieldSpec>,
    onDismiss: () -> Unit,
    onCompose: (CloudflareMultipartBody) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parts = remember {
        mutableStateListOf<CloudflareMultipartPart>().apply { addAll(schemaFields.map(CloudflareMultipartPart::fromSchema)) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var importingPartId by remember { mutableStateOf<String?>(null) }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val partId = importingPartId
        importingPartId = null
        if (uri == null || partId == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val remaining = CloudflareMultipartBuilder.remainingBytes(parts, partId)
                val (bytes, name) = readBoundedDocument(context, uri, remaining)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val index = parts.indexOfFirst { it.id == partId }
                if (index >= 0) parts[index] = parts[index].copy(fileName = name, mimeType = mime, fileData = bytes)
                bytes.fill(0)
                error = null
            } catch (failure: Exception) {
                error = "Could not read that file: ${importErrorMessage(failure, multipart = true)}"
            }
        }
    }
    ThemedModalBottomSheet(onDismissRequest = onDismiss, testTag = "cloudflare.explorer.composer") {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Multipart Body", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Build the upload on-device. Files are read locally, encoded into the request, and never stored by the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let { message -> item("error") { ToolBanner(message, isError = true) } }
            items(parts.size, key = { parts[it].id }) { index ->
                val part = parts[index]
                ToolPanel {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (part.isFile) Icons.Rounded.Description else Icons.Rounded.TextFields,
                                contentDescription = null,
                                tint = CloudflareToolsColors.Orange,
                            )
                            Spacer(Modifier.width(8.dp))
                            ToolTextEditor(
                                label = if (part.isRequired) "Field name · required" else "Field name",
                                value = part.name,
                                onValueChange = { name -> parts[index] = part.copy(name = name) },
                                placeholder = "Field name",
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                testTag = "cloudflare.composer.name.$index",
                            )
                            if (!part.isRequired) {
                                IconButton(onClick = { parts.removeAt(index) }, modifier = Modifier.size(48.dp)) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Remove field ${part.name}")
                                }
                            }
                        }
                        if (part.isFile) {
                            Surface(
                                onClick = {
                                    importingPartId = part.id
                                    fileLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (part.hasFile) Icons.Rounded.CheckCircle else Icons.Rounded.AttachFile,
                                        contentDescription = null,
                                        tint = if (part.hasFile) CloudflareToolsColors.success() else CloudflareToolsColors.Orange,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(part.fileName ?: "Choose file", style = MaterialTheme.typography.titleSmall)
                                        part.fileSize?.let {
                                            Text(formatBytes(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        } else {
                            ToolTextEditor(
                                label = "Field value",
                                value = part.value,
                                onValueChange = { value -> parts[index] = part.copy(value = value) },
                                placeholder = "Field value",
                                minHeight = 48.dp,
                                maxHeight = 180.dp,
                                testTag = "cloudflare.composer.value.$index",
                            )
                        }
                    }
                }
            }
            item("add") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (parts.size >= CloudflareMultipartBuilder.PART_LIMIT) {
                                error = "Multipart requests support up to ${CloudflareMultipartBuilder.PART_LIMIT} fields."
                            } else {
                                parts += CloudflareMultipartPart(name = "")
                            }
                        },
                        enabled = parts.size < CloudflareMultipartBuilder.PART_LIMIT,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.AddCircle, contentDescription = null, tint = CloudflareToolsColors.Orange)
                        Spacer(Modifier.width(6.dp))
                        Text("Add text field", color = CloudflareToolsColors.Orange)
                    }
                    TextButton(
                        onClick = {
                            if (parts.size >= CloudflareMultipartBuilder.PART_LIMIT) {
                                error = "Multipart requests support up to ${CloudflareMultipartBuilder.PART_LIMIT} fields."
                            } else {
                                parts += CloudflareMultipartPart(name = "", isFile = true)
                            }
                        },
                        enabled = parts.size < CloudflareMultipartBuilder.PART_LIMIT,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = CloudflareToolsColors.Orange)
                        Spacer(Modifier.width(6.dp))
                        Text("Add file field", color = CloudflareToolsColors.Orange)
                    }
                }
            }
            item("compose") {
                ToolPrimaryButton(
                    text = "Use multipart body",
                    onClick = {
                        try {
                            onCompose(CloudflareMultipartBuilder.compose(parts.toList()))
                        } catch (failure: CloudflareToolsException) {
                            error = failure.message
                        }
                    },
                    testTag = "cloudflare.composer.use",
                )
            }
        }
    }
}

private class DocumentTooLargeException : Exception()

private fun importErrorMessage(error: Exception, multipart: Boolean = false): String = when (error) {
    is DocumentTooLargeException -> if (multipart) {
        "The combined multipart body must be 25 MB or smaller."
    } else {
        "Request body files must be 25 MB or smaller."
    }
    is SecurityException -> "The app no longer has permission to read that file."
    else -> "The file could not be read."
}

/** Reads a picked document on the IO dispatcher, refusing anything larger than [limit] bytes. */
private suspend fun readBoundedDocument(context: Context, uri: Uri, limit: Int): Pair<ByteArray, String> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "upload.bin"
        var declaredSize: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }
        if ((declaredSize ?: 0L) > limit) throw DocumentTooLargeException()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw DocumentTooLargeException()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw java.io.IOException("The document could not be opened.")
        bytes to name.replace("\r", "").replace("\n", "")
    }
