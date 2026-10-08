package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartBody
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartComposer
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartException
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartField
import com.apoorvdarshan.verceltics.data.cloudflare.storage.CloudflareMultipartPart
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareActionResultBanner
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsActionButton
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsDivider
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsEditorSheet
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsTextField
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.cloudflareUserMessage
import com.apoorvdarshan.verceltics.ui.components.ThemedActionButton
import kotlinx.coroutines.launch

/**
 * Port of iOS `CloudflareMultipartComposerView`: builds a multipart/form-data body on the device.
 * Files are read through the Storage Access Framework, encoded into the body, and never stored.
 * [onCompose] receives the body (iOS passes it Base64-encoded; use [CloudflareMultipartBody.base64]).
 */
@Composable
fun CloudflareMultipartComposerSheet(
    schemaFields: List<CloudflareMultipartField>,
    onCompose: (CloudflareMultipartBody) -> Unit,
    onDismiss: () -> Unit,
    files: CloudflareStorageFiles = rememberStorageFiles(),
) {
    var parts by remember(schemaFields) { mutableStateOf(CloudflareMultipartComposer.initialParts(schemaFields)) }
    var error by remember { mutableStateOf<String?>(null) }
    var importingIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = importingIndex
        importingIndex = -1
        if (uri == null || index !in parts.indices) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val file = files.read(uri.toString(), CloudflareMultipartComposer.remainingBytes(parts, index))
                parts = parts.toMutableList().also {
                    it[index] = it[index].copy(fileName = file.name, mimeType = file.mimeType ?: "application/octet-stream", fileData = file.data())
                }
                error = null
            } catch (failure: Exception) {
                error = "Could not read that file: ${cloudflareUserMessage(failure)}"
            }
        }
    }

    CloudflareOpsEditorSheet(title = "Multipart Body", onDismiss = onDismiss, testTag = "cloudflare.multipart.sheet") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Description, contentDescription = null, tint = CloudflareOpsColors.Orange)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Build the upload on-device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Files are read locally, encoded into the request, and never stored by the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        error?.let { CloudflareActionResultBanner(CloudflareActionBanner(it, isError = true)) }
        Text("Form Fields · ${parts.size}", style = MaterialTheme.typography.labelLarge)
        parts.forEachIndexed { index, part ->
            MultipartPartEditor(
                index = index,
                part = part,
                onChange = { updated -> parts = parts.toMutableList().also { it[index] = updated } },
                onRemove = { parts = parts.toMutableList().also { it.removeAt(index) } },
                onPickFile = {
                    importingIndex = index
                    picker.launch(arrayOf("*/*"))
                },
            )
            CloudflareOpsDivider()
        }
        CloudflareOpsActionButton(
            title = "Add custom field",
            icon = Icons.Rounded.AddCircle,
            onClick = {
                if (parts.size >= CloudflareMultipartComposer.PART_LIMIT) {
                    error = CloudflareMultipartComposer.PART_LIMIT_MESSAGE
                } else {
                    parts = parts + CloudflareMultipartPart(name = "")
                }
            },
            enabled = parts.size < CloudflareMultipartComposer.PART_LIMIT,
            modifier = Modifier.fillMaxWidth(),
            testTag = "cloudflare.multipart.addField",
        )
        ThemedActionButton(
            text = "USE MULTIPART BODY",
            onClick = {
                try {
                    onCompose(CloudflareMultipartComposer.compose(parts))
                } catch (failure: CloudflareMultipartException) {
                    error = failure.message
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            testTag = "cloudflare.multipart.compose",
        )
    }
}

@Composable
private fun MultipartPartEditor(
    index: Int,
    part: CloudflareMultipartPart,
    onChange: (CloudflareMultipartPart) -> Unit,
    onRemove: () -> Unit,
    onPickFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CloudflareOpsTextField(
                value = part.name,
                onValueChange = { onChange(part.copy(name = it)) },
                label = if (part.isRequired) "Field name · required" else "Field name",
                monospace = true,
                modifier = Modifier.weight(1f),
                testTag = "cloudflare.multipart.name.$index",
            )
            if (!part.isRequired) {
                IconButton(onClick = onRemove, modifier = Modifier.testTag("cloudflare.multipart.remove.$index")) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove field", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (part.isFile) {
            CloudflareOpsActionButton(
                title = part.fileName?.let { name -> "$name · ${CloudflareFormat.bytes((part.fileSize ?: 0).toLong())}" } ?: "Choose file",
                icon = if (part.hasFile) Icons.Rounded.CheckCircle else Icons.Rounded.NoteAdd,
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
                testTag = "cloudflare.multipart.file.$index",
            )
        } else {
            CloudflareOpsTextField(
                value = part.value,
                onValueChange = { onChange(part.copy(value = it)) },
                label = "Field value",
                singleLine = false,
                maxLines = 6,
                monospace = true,
                testTag = "cloudflare.multipart.value.$index",
            )
        }
    }
}
