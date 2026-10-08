package com.apoorvdarshan.verceltics.data.cloudflare.tools

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One editable multipart field (iOS `CloudflareMultipartPart`). File bytes stay in memory only. */
class CloudflareMultipartPart(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String = "",
    val isFile: Boolean = false,
    val isRequired: Boolean = false,
    val fileName: String? = null,
    val mimeType: String? = null,
    fileData: ByteArray? = null,
) {
    private val data: ByteArray? = fileData?.copyOf()

    val fileSize: Int? get() = data?.size

    val hasFile: Boolean get() = data != null

    fun fileBytes(): ByteArray? = data?.copyOf()

    fun copy(
        name: String = this.name,
        value: String = this.value,
        fileName: String? = this.fileName,
        mimeType: String? = this.mimeType,
        fileData: ByteArray? = this.data,
    ): CloudflareMultipartPart = CloudflareMultipartPart(id, name, value, isFile, isRequired, fileName, mimeType, fileData)

    internal val payloadBytes: Int get() = data?.size ?: value.toByteArray(StandardCharsets.UTF_8).size

    override fun toString(): String =
        "CloudflareMultipartPart(name=$name, isFile=$isFile, required=$isRequired, bytes=${data?.size ?: 0})"

    companion object {
        fun fromSchema(field: CloudflareMultipartFieldSpec): CloudflareMultipartPart = CloudflareMultipartPart(
            name = field.name,
            value = if (field.isFile) "" else field.suggestedValue,
            isFile = field.isFile,
            isRequired = field.required,
        )
    }
}

/** A composed multipart payload ready for the explorer. */
class CloudflareMultipartBody(bytes: ByteArray, val contentType: String) {
    private val stored = bytes.copyOf()

    val size: Int get() = stored.size

    fun bytes(): ByteArray = stored.copyOf()

    override fun toString(): String = "CloudflareMultipartBody(bytes=${stored.size}, contentType=$contentType)"
}

/** Port of iOS `CloudflareMultipartComposerView.compose()`. */
object CloudflareMultipartBuilder {
    const val UPLOAD_LIMIT_BYTES = 25 * 1_024 * 1_024
    const val PART_LIMIT = 100

    /** Remaining room for a file in [partId], counting every other part (iOS `importFile`). */
    fun remainingBytes(parts: List<CloudflareMultipartPart>, partId: String): Int {
        val used = parts.filter { it.id != partId }.sumOf { it.payloadBytes.toLong() }
        return (UPLOAD_LIMIT_BYTES - used).coerceAtLeast(0).toInt()
    }

    fun compose(
        parts: List<CloudflareMultipartPart>,
        boundary: String = "Verceltics-${UUID.randomUUID().toString().uppercase()}",
    ): CloudflareMultipartBody {
        val missing = parts.filter { part ->
            part.isRequired && (part.name.isEmpty() || if (part.isFile) !part.hasFile else part.value.isEmpty())
        }
        if (missing.isNotEmpty()) {
            invalid("Add values for required fields: ${missing.joinToString(", ") { it.name }}.")
        }
        val used = parts.filter { part ->
            part.name.isNotBlank() && if (part.isFile) part.hasFile else part.value.isNotEmpty()
        }
        if (used.isEmpty()) invalid("Add at least one form field or file.")
        if (used.size > PART_LIMIT) invalid("Multipart requests support up to $PART_LIMIT fields.")
        var payloadBytes = 0L
        used.forEach { part ->
            payloadBytes += part.payloadBytes
            if (payloadBytes > UPLOAD_LIMIT_BYTES) invalid("The combined multipart body must be 25 MB or smaller.")
        }

        val output = ByteArrayOutputStream()
        fun text(value: String) = output.write(value.toByteArray(StandardCharsets.UTF_8))
        used.forEach { part ->
            text("--$boundary\r\n")
            val file = part.fileBytes()
            if (file != null) {
                text(
                    "Content-Disposition: form-data; name=\"${safe(part.name)}\"; " +
                        "filename=\"${safe(part.fileName ?: "upload.bin")}\"\r\n",
                )
                text("Content-Type: ${safe(part.mimeType ?: "application/octet-stream")}\r\n\r\n")
                output.write(file)
                file.fill(0)
                text("\r\n")
            } else {
                text("Content-Disposition: form-data; name=\"${safe(part.name)}\"\r\n\r\n")
                text(part.value)
                text("\r\n")
            }
        }
        text("--$boundary--\r\n")
        val bytes = output.toByteArray()
        if (bytes.size > UPLOAD_LIMIT_BYTES) invalid("The combined multipart body must be 25 MB or smaller.")
        return CloudflareMultipartBody(bytes, "multipart/form-data; boundary=$boundary")
    }

    /** iOS `safeHeaderValue`, also stripping quotes that would end the parameter early. */
    private fun safe(value: String): String = value.replace("\r", "").replace("\n", "").replace("\"", "'")

    private fun invalid(message: String): Nothing =
        throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_REQUEST, message)
}
