package com.apoorvdarshan.verceltics.data.apicatalog

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One editable `multipart/form-data` part (iOS `CloudflareMultipartPart`). */
data class ProviderApiMultipartPart(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String = "",
    val isFile: Boolean = false,
    val isRequired: Boolean = false,
    val fileName: String? = null,
    val mimeType: String? = null,
    /** Size of the attached file; the bytes themselves live outside observable state. */
    val fileByteCount: Int? = null,
) {
    val hasFile: Boolean
        get() = fileByteCount != null
}

/** The composed upload: wire bytes plus the `multipart/form-data; boundary=…` content type. */
class ProviderApiMultipartBody(bytes: ByteArray, val contentType: String) {
    private val storedBytes = bytes.copyOf()

    val size: Int
        get() = storedBytes.size

    fun bytes(): ByteArray = storedBytes.copyOf()

    override fun toString(): String = "ProviderApiMultipartBody(bytes=${storedBytes.size}, contentType=$contentType)"
}

/** Port of iOS `CloudflareMultipartComposerView.compose()` limits, validation and encoding. */
object ProviderApiMultipart {
    const val UPLOAD_LIMIT_BYTES: Int = 25 * 1_024 * 1_024
    const val PART_LIMIT: Int = 100
    const val TOO_LARGE_MESSAGE: String = "The combined multipart body must be 25 MB or smaller."

    /** iOS composer initial parts: one per schema field with its suggested value. */
    fun initialParts(fields: List<ProviderApiMultipartField>): List<ProviderApiMultipartPart> = fields.map { field ->
        ProviderApiMultipartPart(
            name = field.name,
            value = if (field.isFile) "" else field.suggestedValue,
            isFile = field.isFile,
            isRequired = field.required,
        )
    }

    /** Bytes still available for [partId]'s file given every other part's payload. */
    fun remainingBytes(parts: List<ProviderApiMultipartPart>, partId: String): Int {
        val used = parts.filter { it.id != partId }.sumOf { it.fileByteCount ?: it.value.toByteArray(StandardCharsets.UTF_8).size }
        return (UPLOAD_LIMIT_BYTES - used).coerceAtLeast(0)
    }

    /**
     * Validates and encodes [parts]. [fileBytes] maps part ids to attached file contents.
     * Throws [ProviderApiRequestException] with the iOS copy when something is missing.
     */
    fun compose(
        parts: List<ProviderApiMultipartPart>,
        fileBytes: (String) -> ByteArray?,
        boundary: String = "Verceltics-${UUID.randomUUID()}",
    ): ProviderApiMultipartBody {
        val missing = parts.filter { part ->
            part.isRequired && (part.name.isEmpty() || if (part.isFile) fileBytes(part.id) == null else part.value.isEmpty())
        }
        if (missing.isNotEmpty()) {
            throw ProviderApiRequestException("Add values for required fields: ${missing.joinToString(", ") { it.name }}.")
        }
        val used = parts.filter { part ->
            part.name.trim().isNotEmpty() && if (part.isFile) fileBytes(part.id) != null else part.value.isNotEmpty()
        }
        if (used.isEmpty()) throw ProviderApiRequestException("Add at least one form field or file.")
        if (used.size > PART_LIMIT) throw ProviderApiRequestException("Multipart requests support up to $PART_LIMIT fields.")
        var payloadBytes = 0L
        for (part in used) {
            payloadBytes += fileBytes(part.id)?.size?.toLong() ?: part.value.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (payloadBytes > UPLOAD_LIMIT_BYTES) throw ProviderApiRequestException(TOO_LARGE_MESSAGE)
        }
        val output = ByteArrayOutputStream((payloadBytes + used.size * 256L).coerceAtMost(UPLOAD_LIMIT_BYTES.toLong()).toInt())
        fun write(text: String) = output.write(text.toByteArray(StandardCharsets.UTF_8))
        for (part in used) {
            write("--$boundary\r\n")
            val data = if (part.isFile) fileBytes(part.id) else null
            if (data != null) {
                val fileName = safeHeaderValue(part.fileName ?: "upload.bin")
                write("Content-Disposition: form-data; name=\"${safeHeaderValue(part.name)}\"; filename=\"$fileName\"\r\n")
                write("Content-Type: ${safeHeaderValue(part.mimeType ?: "application/octet-stream")}\r\n\r\n")
                output.write(data)
                write("\r\n")
            } else {
                write("Content-Disposition: form-data; name=\"${safeHeaderValue(part.name)}\"\r\n\r\n")
                write(part.value)
                write("\r\n")
            }
        }
        write("--$boundary--\r\n")
        if (output.size() > UPLOAD_LIMIT_BYTES) throw ProviderApiRequestException(TOO_LARGE_MESSAGE)
        return ProviderApiMultipartBody(output.toByteArray(), "multipart/form-data; boundary=$boundary")
    }

    private fun safeHeaderValue(value: String): String = value.replace("\r", "").replace("\n", "").replace("\"", "'")
}
