package com.apoorvdarshan.verceltics.ui.cloudflare.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareInputException
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file the user picked through the Storage Access Framework, read fully into memory. */
class CloudflarePickedFile(
    val name: String,
    val mimeType: String?,
    data: ByteArray,
) {
    private val bytes = data.copyOf()

    val size: Int get() = bytes.size

    fun data(): ByteArray = bytes.copyOf()

    override fun toString(): String = "CloudflarePickedFile(name=$name, bytes=${bytes.size}, mimeType=$mimeType)"
}

/**
 * Local file access for storage uploads and downloads. URIs come from SAF pickers
 * (`OpenDocument`/`CreateDocument`), so the app needs no storage permission and no MainActivity hook.
 */
interface CloudflareStorageFiles {
    suspend fun read(uri: String, maximumBytes: Int): CloudflarePickedFile

    suspend fun write(uri: String, data: ByteArray)
}

class AndroidCloudflareStorageFiles(
    private val resolver: ContentResolver,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : CloudflareStorageFiles {
    override suspend fun read(uri: String, maximumBytes: Int): CloudflarePickedFile = withContext(ioContext) {
        val parsed = Uri.parse(uri)
        var name = parsed.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
        var declaredSize: Long? = null
        runCatching {
            resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                }
            }
        }
        if ((declaredSize ?: 0L) > maximumBytes) throw tooLarge(maximumBytes)
        val stream = try {
            resolver.openInputStream(parsed)
        } catch (_: Exception) {
            null
        } ?: throw CloudflareInputException("Could not read that file.")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1_024)
            var total = 0
            while (true) {
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    throw CloudflareInputException("Could not read that file.")
                }
                if (count < 0) break
                total += count
                if (total > maximumBytes) throw tooLarge(maximumBytes)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        CloudflarePickedFile(name.ifBlank { "upload.bin" }, resolver.getType(parsed), bytes)
    }

    override suspend fun write(uri: String, data: ByteArray) = withContext(ioContext) {
        val stream = try {
            resolver.openOutputStream(Uri.parse(uri), "wt")
        } catch (_: Exception) {
            null
        } ?: throw CloudflareInputException("Could not save to that location.")
        try {
            stream.use { it.write(data) }
        } catch (_: IOException) {
            throw CloudflareInputException("Could not save to that location.")
        }
    }

    private fun tooLarge(maximumBytes: Int) = CloudflareInputException(cloudflareFileTooLargeMessage(maximumBytes))
}

/** "Choose a file that is 25 MB or smaller." (binary megabytes, matching the iOS limits' wording). */
fun cloudflareFileTooLargeMessage(maximumBytes: Int): String {
    val megabytes = maximumBytes / (1_024 * 1_024)
    val limit = if (megabytes > 0) "$megabytes MB" else CloudflareFormat.bytes(maximumBytes.toLong())
    return "Choose a file that is $limit or smaller."
}
