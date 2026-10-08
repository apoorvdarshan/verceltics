package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesBuildFolder
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesFolderEntry
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.readCloudflarePagesFile

/**
 * A build folder chosen with `ACTION_OPEN_DOCUMENT_TREE`, walked with [DocumentsContract] (no
 * androidx.documentfile dependency). The tree grant from the picker covers every descendant, and the
 * provider never exposes links outside the chosen tree. Call from a background thread.
 */
class CloudflarePagesDocumentTreeFolder(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : CloudflarePagesBuildFolder {
    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val displayName: String by lazy {
        runCatching {
            contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "Selected folder"
    }

    override fun children(directory: CloudflarePagesFolderEntry?): List<CloudflarePagesFolderEntry> {
        val parentId = directory?.id ?: rootDocumentId
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val cursor = contentResolver.query(childrenUri, projection, null, null, null)
            ?: throw CloudflareOperationException.invalidRequest("The selected build folder could not be read.")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mimeType = it.getString(2)
                    val size = if (it.isNull(3)) 0L else it.getLong(3)
                    add(
                        CloudflarePagesFolderEntry(
                            id = id,
                            name = name,
                            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = size,
                        ),
                    )
                }
            }
        }
    }

    override fun read(entry: CloudflarePagesFolderEntry, maximumBytes: Int): ByteArray {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
        val input = contentResolver.openInputStream(uri)
            ?: throw CloudflareOperationException.invalidRequest("${entry.name} could not be read from the selected folder.")
        return input.use { readCloudflarePagesFile(it, entry.name, maximumBytes) }
    }
}
