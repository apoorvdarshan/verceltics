package com.apoorvdarshan.verceltics.data.cloudflare.storage

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

/** A form field suggested by an API schema (iOS `CloudflareOpenAPIMultipartField`). */
data class CloudflareMultipartField(
    val name: String,
    val isFile: Boolean,
    val required: Boolean,
    val suggestedValue: String = "",
)

/** One part of a multipart/form-data body being composed on the device. */
class CloudflareMultipartPart(
    val name: String,
    val value: String = "",
    val isFile: Boolean = false,
    val isRequired: Boolean = false,
    val fileName: String? = null,
    val mimeType: String? = null,
    fileData: ByteArray? = null,
) {
    private val storedFile = fileData?.copyOf()

    val hasFile: Boolean get() = storedFile != null

    val fileSize: Int? get() = storedFile?.size

    fun fileData(): ByteArray? = storedFile?.copyOf()

    /** Bytes this part contributes to the payload limit (iOS counts file bytes or UTF-8 text). */
    val payloadBytes: Int get() = storedFile?.size ?: value.toByteArray(StandardCharsets.UTF_8).size

    fun copy(
        name: String = this.name,
        value: String = this.value,
        isFile: Boolean = this.isFile,
        isRequired: Boolean = this.isRequired,
        fileName: String? = this.fileName,
        mimeType: String? = this.mimeType,
        fileData: ByteArray? = storedFile,
    ) = CloudflareMultipartPart(name, value, isFile, isRequired, fileName, mimeType, fileData)

    override fun toString(): String =
        "CloudflareMultipartPart(name=$name, isFile=$isFile, required=$isRequired, bytes=$payloadBytes)"
}

/** A composed multipart body and its `Content-Type` (with boundary). */
class CloudflareMultipartBody(
    body: ByteArray,
    val contentType: String,
) {
    private val bytes = body.copyOf()

    val size: Int get() = bytes.size

    fun bytes(): ByteArray = bytes.copyOf()

    /** iOS hands the API explorer a Base64 body; kept for parity. */
    fun base64(): String = Base64.getEncoder().encodeToString(bytes)

    override fun toString(): String = "CloudflareMultipartBody(bytes=${bytes.size}, contentType=$contentType)"
}

class CloudflareMultipartException(message: String) : IllegalArgumentException(message)

/**
 * Port of the iOS `CloudflareMultipartComposerView` encoder: 25 MB combined payload, at most 100
 * fields, header values stripped of CR/LF, and required fields enforced before anything is built.
 */
object CloudflareMultipartComposer {
    const val UPLOAD_LIMIT_BYTES: Int = 25 * 1_024 * 1_024
    const val PART_LIMIT: Int = 100
    const val TOO_LARGE_MESSAGE: String = "The combined multipart body must be 25 MB or smaller."
    const val PART_LIMIT_MESSAGE: String = "Multipart requests support up to $PART_LIMIT fields."

    fun initialParts(fields: List<CloudflareMultipartField>): List<CloudflareMultipartPart> = fields.map {
        CloudflareMultipartPart(name = it.name, value = it.suggestedValue, isFile = it.isFile, isRequired = it.required)
    }

    /** Bytes still available for the part [excludingIndex] (iOS `remainingBytes`). */
    fun remainingBytes(parts: List<CloudflareMultipartPart>, excludingIndex: Int): Int {
        val used = parts.withIndex().filter { it.index != excludingIndex }.sumOf { it.value.payloadBytes.toLong() }
        return (UPLOAD_LIMIT_BYTES - used).coerceAtLeast(0).toInt()
    }

    /** Builds the body, or throws [CloudflareMultipartException] with the iOS message. */
    fun compose(parts: List<CloudflareMultipartPart>, boundary: String = "Verceltics-${UUID.randomUUID()}"): CloudflareMultipartBody {
        require(boundary.isNotBlank() && boundary.length <= 70 && boundary.none { it == '\r' || it == '\n' || it == '"' }) {
            "Invalid multipart boundary."
        }
        val used = parts.filter { it.name.isNotBlank() && if (it.isFile) it.hasFile else it.value.isNotEmpty() }
        val missing = parts.filter { it.isRequired && (it.name.isEmpty() || if (it.isFile) !it.hasFile else it.value.isEmpty()) }
        if (missing.isNotEmpty()) {
            throw CloudflareMultipartException("Add values for required fields: ${missing.joinToString(", ") { it.name }}.")
        }
        if (used.isEmpty()) throw CloudflareMultipartException("Add at least one form field or file.")
        if (used.size > PART_LIMIT) throw CloudflareMultipartException(PART_LIMIT_MESSAGE)

        var payload = 0L
        used.forEach { part ->
            payload += part.payloadBytes
            if (payload > UPLOAD_LIMIT_BYTES) throw CloudflareMultipartException(TOO_LARGE_MESSAGE)
        }

        val output = ByteArrayOutputStream()
        fun write(text: String) = output.write(text.toByteArray(StandardCharsets.UTF_8))
        used.forEach { part ->
            write("--$boundary\r\n")
            val file = part.fileData()
            if (file != null) {
                val fileName = safeHeaderValue(part.fileName ?: "upload.bin")
                write("Content-Disposition: form-data; name=\"${safeHeaderValue(part.name)}\"; filename=\"$fileName\"\r\n")
                write("Content-Type: ${safeHeaderValue(part.mimeType ?: "application/octet-stream")}\r\n\r\n")
                output.write(file)
                file.fill(0)
                write("\r\n")
            } else {
                write("Content-Disposition: form-data; name=\"${safeHeaderValue(part.name)}\"\r\n\r\n")
                write(part.value)
                write("\r\n")
            }
        }
        write("--$boundary--\r\n")
        val bytes = output.toByteArray()
        if (bytes.size > UPLOAD_LIMIT_BYTES) throw CloudflareMultipartException(TOO_LARGE_MESSAGE)
        return CloudflareMultipartBody(bytes, "multipart/form-data; boundary=$boundary")
    }

    /** iOS `safeHeaderValue`: CR and LF are removed; quotes are escaped so names cannot break out. */
    fun safeHeaderValue(value: String): String = value.replace("\r", "").replace("\n", "").replace("\"", "%22")
}
