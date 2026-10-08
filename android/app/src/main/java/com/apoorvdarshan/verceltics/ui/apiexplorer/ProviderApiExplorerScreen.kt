package com.apoorvdarshan.verceltics.ui.apiexplorer

import android.content.ClipData
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TextFields
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMethods
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipart
import com.apoorvdarshan.verceltics.data.apicatalog.ProviderApiMultipartPart
import com.apoorvdarshan.verceltics.ui.components.OffsetPanel
import com.apoorvdarshan.verceltics.ui.components.StatusPill
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import com.apoorvdarshan.verceltics.ui.components.ThemedActionTone
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Callbacks for [ProviderApiExplorerScreen]; defaults keep tests and previews short. */
class ProviderApiExplorerCallbacks(
    val onMethodChange: (String) -> Unit = {},
    val onPathChange: (String) -> Unit = {},
    val onBodyChange: (String) -> Unit = {},
    val onHeadersChange: (String) -> Unit = {},
    val onContentTypeChange: (String) -> Unit = {},
    val onShowOptionalBody: (Boolean) -> Unit = {},
    val onSend: () -> Unit = {},
    val onConfirmSend: () -> Unit = {},
    val onDismissConfirmation: () -> Unit = {},
    val onBuildMultipart: () -> Unit = {},
    val onFilePicked: (name: String, bytes: ByteArray) -> Unit = { _, _ -> },
    val onFileError: (String) -> Unit = {},
    val onRemoveAttachment: () -> Unit = {},
)

/**
 * iOS `HostingAPIExplorerView` / `RegistrarAPIExplorerView`: method, provider-relative path, body,
 * content type and custom headers, a confirmation before likely writes, and the response viewer.
 */
@Composable
fun ProviderApiExplorerScreen(
    profile: ProviderApiProfile,
    explorer: ProviderApiExplorerDraft,
    isSending: Boolean,
    showWriteConfirmation: Boolean,
    response: ProviderApiResponseUi?,
    requestError: String?,
    accent: Color,
    callbacks: ProviderApiExplorerCallbacks,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val method = explorer.method
    val requiresConfirmation = profile.requiresConfirmation(method, explorer.path, explorer.body)
    val isDelete = method == "DELETE" || (profile.kind == ProviderApiKind.REGISTRAR && "delete" in explorer.path.lowercase(Locale.ROOT))

    if (showWriteConfirmation) {
        ThemedAlertDialog(
            title = if (profile.kind == ProviderApiKind.REGISTRAR) "Send this registrar request?" else "Send $method request?",
            message = if (profile.kind == ProviderApiKind.REGISTRAR) {
                "This command can change a domain, create a purchase, or affect DNS. Confirm the path and request body first."
            } else {
                "This is a real write request to ${profile.displayName}. Confirm the path and JSON body first."
            },
            confirmText = "SEND $method",
            confirmTone = if (isDelete) ThemedActionTone.DESTRUCTIVE else ThemedActionTone.PRIMARY,
            dismissText = "CANCEL",
            onConfirm = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onConfirmSend()
            },
            onDismissRequest = callbacks.onDismissConfirmation,
            testTag = "providerApi.confirmDialog",
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 840.dp
        val composer: @Composable (Modifier) -> Unit = { paneModifier ->
            RequestComposer(profile, explorer, isSending, requiresConfirmation, accent, callbacks, paneModifier)
        }
        val result: @Composable (Modifier) -> Unit = { paneModifier ->
            ResultPane(response, requestError, showPlaceholder = twoPane, paneModifier)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp)
                .testTag("providerApi.explorer"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SafetyCard(profile, accent)
            if (twoPane) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    composer(Modifier.weight(1f))
                    result(Modifier.weight(1f))
                }
            } else {
                composer(Modifier.fillMaxWidth())
                result(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SafetyCard(profile: ProviderApiProfile, accent: Color) {
    val registrar = profile.kind == ProviderApiKind.REGISTRAR
    ApiPanel(testTag = "providerApi.safety") {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (registrar) Icons.Rounded.Lock else Icons.Rounded.Terminal, contentDescription = null, tint = accent)
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (registrar) "Full official registrar API" else "Full official API access",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (registrar) {
                        "Authentication is attached privately. Paths cannot leave ${profile.displayName}’s API host, " +
                            "and detected write or purchase commands require confirmation."
                    } else {
                        "Paths stay locked to ${profile.displayName}’s API host. Write requests require confirmation."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RequestComposer(
    profile: ProviderApiProfile,
    explorer: ProviderApiExplorerDraft,
    isSending: Boolean,
    requiresConfirmation: Boolean,
    accent: Color,
    callbacks: ProviderApiExplorerCallbacks,
    modifier: Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val method = explorer.method
    val bodyAllowed = profile.allowsBody(method)
    val hasBody = explorer.body.isNotBlank()
    val showsBodyEditor = explorer.attachment == null &&
        ((bodyAllowed && (!profile.bodyIsOptional(method) || hasBody || explorer.showOptionalBody)) || (!bodyAllowed && hasBody))
    val contentType = explorer.contentType.lowercase(Locale.ROOT)
    val isMultipart = "multipart/form-data" in contentType
    val isOctetStream = "application/octet-stream" in contentType
    val canSend = explorer.path.isNotBlank() && !isSending
    val pickFile = rememberApiFilePicker(onPicked = { file -> callbacks.onFilePicked(file.name, file.bytes) }, onError = callbacks.onFileError)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ApiPanel(testTag = "providerApi.target") {
            EditorLabel("Method")
            if (profile.forcedMethod != null) {
                ApiChipRow(
                    items = listOf(profile.forcedMethod),
                    selected = profile.forcedMethod,
                    onSelect = {},
                    testTagPrefix = "providerApi.method",
                    enabled = false,
                )
                Text(
                    "${profile.displayName}’s GraphQL API is always called with POST.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                ApiChipRow(
                    items = ProviderApiMethods.ALL,
                    selected = method,
                    onSelect = callbacks.onMethodChange,
                    testTagPrefix = "providerApi.method",
                    monospace = true,
                    color = { if (ProviderApiMethods.isWrite(it)) ApiWriteColor else null },
                )
            }
            EditorLabel("Request path")
            ApiTextField(
                value = explorer.path,
                onValueChange = callbacks.onPathChange,
                placeholder = profile.pathPlaceholder,
                accessibilityLabel = "Request path",
                singleLine = false,
                maxLines = 4,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                testTag = "providerApi.path",
            )
        }

        when {
            explorer.attachment != null -> AttachmentCard(explorer.attachment, accent, callbacks.onRemoveAttachment)
            showsBodyEditor -> ApiPanel(testTag = "providerApi.bodyPanel") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EditorLabel(if (profile.isGraphQL) "GraphQL JSON body" else "Request body", Modifier.weight(1f))
                    if (bodyAllowed && profile.bodyIsOptional(method) && !hasBody) {
                        Surface(
                            onClick = { callbacks.onShowOptionalBody(false) },
                            modifier = Modifier
                                .defaultMinSize(minHeight = 44.dp)
                                .testTag("providerApi.hideBody"),
                            color = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text("Hide", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                ApiTextField(
                    value = explorer.body,
                    onValueChange = callbacks.onBodyChange,
                    accessibilityLabel = "Request body",
                    minLines = 5,
                    maxLines = 18,
                    testTag = "providerApi.body",
                )
                if (!bodyAllowed) {
                    Text(
                        "$method requests can't include a body on Android. Clear it or choose another method.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            bodyAllowed -> Surface(
                onClick = { callbacks.onShowOptionalBody(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("providerApi.addBody"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add optional request body", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (bodyAllowed && isMultipart && (showsBodyEditor || explorer.attachment != null)) {
            ThemedActionButton(
                if (explorer.attachment?.isMultipart == true) "EDIT ENCODED MULTIPART UPLOAD" else "BUILD MULTIPART UPLOAD",
                onClick = callbacks.onBuildMultipart,
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "providerApi.buildMultipart",
            )
        }
        if (bodyAllowed && isOctetStream && (showsBodyEditor || explorer.attachment != null)) {
            ThemedActionButton(
                if (explorer.attachment != null) "REPLACE BINARY FILE" else "CHOOSE BINARY FILE",
                onClick = pickFile,
                tone = ThemedActionTone.NEUTRAL,
                modifier = Modifier.fillMaxWidth(),
                testTag = "providerApi.chooseFile",
            )
        }

        ApiPanel(testTag = "providerApi.headersPanel") {
            EditorLabel("Content type")
            ApiTextField(
                value = explorer.contentType,
                onValueChange = callbacks.onContentTypeChange,
                placeholder = "application/json",
                accessibilityLabel = "Content type",
                singleLine = true,
                testTag = "providerApi.contentTypeField",
            )
            EditorLabel("Custom headers · JSON object")
            ApiTextField(
                value = explorer.headersText,
                onValueChange = callbacks.onHeadersChange,
                placeholder = "{\n  \"X-Request-Id\": \"…\"\n}",
                accessibilityLabel = "Custom headers JSON object",
                minLines = 3,
                maxLines = 10,
                testTag = "providerApi.headers",
            )
            Text(
                "Authentication, Host, Content-Length and Content-Type are attached by Verceltics; matching custom headers are ignored.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        ThemedActionButton(
            text = when {
                !requiresConfirmation -> "SEND REQUEST"
                profile.kind == ProviderApiKind.REGISTRAR -> "REVIEW REQUEST"
                else -> "REVIEW WRITE REQUEST"
            },
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                callbacks.onSend()
            },
            enabled = canSend,
            isBusy = isSending,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
            testTag = "providerApi.send",
        )
    }
}

@Composable
private fun AttachmentCard(attachment: ProviderApiAttachmentUi, accent: Color, onRemove: () -> Unit) {
    ApiPanel(accent = accent, testTag = "providerApi.attachment") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (attachment.isMultipart) Icons.Rounded.Inventory2 else Icons.Rounded.AttachFile,
                contentDescription = null,
                tint = accent,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EditorLabel(if (attachment.isMultipart) "Encoded multipart body" else "Binary request body")
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(attachment.byteCount), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRemove, modifier = Modifier.testTag("providerApi.removeAttachment")) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove binary body")
            }
        }
    }
}

@Composable
private fun ResultPane(response: ProviderApiResponseUi?, requestError: String?, showPlaceholder: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        requestError?.let { ApiFeedbackBanner("Request failed", it, isError = true, testTag = "providerApi.requestError") }
        when {
            response != null -> ProviderApiResponsePane(response)
            requestError == null && showPlaceholder -> ApiEmptyState(
                Icons.Rounded.Terminal,
                "Response workspace",
                "Send a request to inspect its status, headers, and body beside the request editor.",
            )
        }
    }
}

private enum class ResponseSection(val title: String) {
    BODY("Body"),
    HEADERS("Headers"),
}

/** iOS `AppAPIResponsePane`: status, Body/Headers, selectable monospaced text and copy. */
@Composable
fun ProviderApiResponsePane(response: ProviderApiResponseUi, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var section by rememberSaveable(response.statusCode, response.byteCount) { mutableStateOf(ResponseSection.BODY) }
    var copied by remember(response) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    val selectedText = when (section) {
        ResponseSection.BODY -> response.bodyText
        ResponseSection.HEADERS -> response.headersText
    }
    val statusColor = if (response.isSuccess) ApiReadColor else MaterialTheme.colorScheme.error
    OffsetPanel(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        testTag = "providerApi.response",
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Response", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusPill("HTTP ${response.statusCode}", statusColor, Modifier.testTag("providerApi.responseStatus"))
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch {
                            runCatching {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Response ${section.title.lowercase()}", selectedText)))
                            }
                        }
                        copied = true
                    },
                    modifier = Modifier.testTag("providerApi.copy"),
                ) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy ${section.title.lowercase()}",
                    )
                }
            }
            if (response.headerCount > 0) {
                ApiSegmentedControl(
                    options = ResponseSection.entries,
                    selected = section,
                    label = ResponseSection::title,
                    onSelect = { section = it },
                    testTag = { "providerApi.responseSection.${it.title}" },
                )
            }
            if (section == ResponseSection.BODY && response.isBinary) {
                Text(
                    "Binary response · ${formatBytes(response.byteCount)} · shown as Base64",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 190.dp, max = 360.dp)
                    .padding(0.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f).compositeOver(MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(
                        Modifier
                            .heightIn(min = 190.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            Text(
                                selectedText.ifEmpty { "No response content" },
                                modifier = Modifier
                                    .padding(12.dp)
                                    .testTag("providerApi.responseBody")
                                    .semantics { contentDescription = "Response ${section.title.lowercase()}" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (selectedText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            if (section == ResponseSection.BODY && response.bodyTruncated) {
                Text(
                    "Showing the first ${formatCount(response.bodyText.length)} characters of ${formatBytes(response.byteCount)}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// region Multipart composer

/** iOS `CloudflareMultipartComposerView`, reused for provider operations with form uploads. */
@Composable
fun ProviderApiMultipartScreen(
    draft: ProviderApiMultipartDraft,
    accent: Color,
    onNameChange: (partId: String, name: String) -> Unit,
    onValueChange: (partId: String, value: String) -> Unit,
    onFilePicked: (partId: String, name: String, mimeType: String?, bytes: ByteArray) -> Unit,
    onFileError: (String) -> Unit,
    onRemove: (partId: String) -> Unit,
    onAddField: () -> Unit,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickingPartId by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFile = rememberApiFilePicker(
        onPicked = { file -> pickingPartId?.let { onFilePicked(it, file.name, file.mimeType, file.bytes) } },
        onError = onFileError,
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 32.dp)
            .testTag("providerApi.multipart"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ApiPanel(accent = accent) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = accent)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Build the upload on-device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Files are read locally, encoded into the request, and never stored by the app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        draft.error?.let { ApiFeedbackBanner(null, it, isError = true, testTag = "providerApi.multipart.error") }
        ApiPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Form Fields", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(formatCount(draft.parts.size), color = accent, style = MaterialTheme.typography.labelLarge)
            }
            if (draft.parts.isEmpty()) {
                Text("Add a custom field to start the form.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            draft.parts.forEachIndexed { index, part ->
                MultipartPartEditor(
                    index = index,
                    part = part,
                    accent = accent,
                    onNameChange = { onNameChange(part.id, it) },
                    onValueChange = { onValueChange(part.id, it) },
                    onChooseFile = {
                        pickingPartId = part.id
                        pickFile()
                    },
                    onRemove = { onRemove(part.id) },
                )
            }
        }
        ThemedActionButton(
            "ADD CUSTOM FIELD",
            onClick = onAddField,
            enabled = draft.parts.size < ProviderApiMultipart.PART_LIMIT,
            tone = ThemedActionTone.NEUTRAL,
            modifier = Modifier.fillMaxWidth(),
            testTag = "providerApi.multipart.add",
        )
        ThemedActionButton(
            "USE MULTIPART BODY",
            onClick = onCompose,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp),
            testTag = "providerApi.multipart.compose",
        )
    }
}

@Composable
private fun MultipartPartEditor(
    index: Int,
    part: ProviderApiMultipartPart,
    accent: Color,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onChooseFile: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("providerApi.multipart.part.$index"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (part.isFile) Icons.Rounded.Description else Icons.Rounded.TextFields, contentDescription = null, tint = accent)
            Spacer(Modifier.width(8.dp))
            ApiTextField(
                value = part.name,
                onValueChange = onNameChange,
                placeholder = "Field name",
                accessibilityLabel = "Field name",
                singleLine = true,
                modifier = Modifier.weight(1f),
                testTag = "providerApi.multipart.name.$index",
            )
            if (part.isRequired) {
                Spacer(Modifier.width(8.dp))
                Text("REQUIRED", color = ApiWarningColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            } else {
                IconButton(onClick = onRemove, modifier = Modifier.testTag("providerApi.multipart.remove.$index")) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove field")
                }
            }
        }
        if (part.isFile) {
            Surface(
                onClick = onChooseFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("providerApi.multipart.file.$index"),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (part.hasFile) ApiReadColor else accent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (part.hasFile) Icons.Rounded.Check else Icons.Rounded.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(part.fileName ?: "Choose file", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        part.fileByteCount?.let {
                            Text(formatBytes(it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            ApiTextField(
                value = part.value,
                onValueChange = onValueChange,
                placeholder = "Field value",
                accessibilityLabel = "Field value",
                maxLines = 6,
                testTag = "providerApi.multipart.value.$index",
            )
        }
    }
}

// endregion

// region Files

internal class PickedApiFile(val name: String, val mimeType: String?, val bytes: ByteArray)

/** Storage Access Framework picker; files are read on IO, size-checked, and never persisted. */
@Composable
internal fun rememberApiFilePicker(onPicked: (PickedApiFile) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnPicked by rememberUpdatedState(onPicked)
    val latestOnError by rememberUpdatedState(onError)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        scope.launch {
            val result = withContext(Dispatchers.IO) { readPickedFile(resolver, uri, ProviderApiMultipart.UPLOAD_LIMIT_BYTES) }
            result.fold(onSuccess = { latestOnPicked(it) }, onFailure = { latestOnError(it.message ?: "Could not read that file.") })
        }
    }
    return { runCatching { launcher.launch(arrayOf("*/*")) }.onFailure { latestOnError("No file picker is available on this device.") } }
}

private fun readPickedFile(resolver: ContentResolver, uri: Uri, limit: Int): Result<PickedApiFile> = try {
    var name = "upload.bin"
    var declaredSize = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                cursor.getString(index)?.takeIf(String::isNotBlank)?.let { name = it }
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
        }
    }
    if (declaredSize > limit) throw IOException("Choose a file smaller than 25 MB.")
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream(if (declaredSize in 1..limit.toLong()) declaredSize.toInt() else 64 * 1_024)
        val buffer = ByteArray(64 * 1_024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("Choose a file smaller than 25 MB.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("Could not read that file.")
    Result.success(PickedApiFile(name.replace("\r", "").replace("\n", ""), resolver.getType(uri), bytes))
} catch (error: IOException) {
    Result.failure(IOException("Could not read that file: ${error.message ?: "unknown error"}"))
} catch (_: SecurityException) {
    Result.failure(IOException("Could not read that file: permission denied."))
}

internal fun formatBytes(count: Int): String = when {
    count < 1_024 -> "$count bytes"
    count < 1_024 * 1_024 -> String.format(Locale.getDefault(), "%.1f KB", count / 1_024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", count / (1_024.0 * 1_024.0))
}

// endregion
