package com.apoorvdarshan.verceltics.data.cloudflare.storage

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudflareMultipartComposerTest {
    @Test
    fun composesCrlfFramedPartsInOrderWithFileHeaders() {
        val body = CloudflareMultipartComposer.compose(
            listOf(
                CloudflareMultipartPart(name = "metadata", value = "{\"main_module\":\"worker.js\"}", isRequired = true),
                CloudflareMultipartPart(name = "worker.js", isFile = true, fileName = "worker.js", mimeType = "application/javascript+module", fileData = "export default {}".toByteArray()),
                CloudflareMultipartPart(name = "", value = "ignored because unnamed"),
                CloudflareMultipartPart(name = "empty", value = ""),
            ),
            boundary = "B",
        )
        assertEquals("multipart/form-data; boundary=B", body.contentType)
        assertEquals(
            "--B\r\n" +
                "Content-Disposition: form-data; name=\"metadata\"\r\n\r\n" +
                "{\"main_module\":\"worker.js\"}\r\n" +
                "--B\r\n" +
                "Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n" +
                "Content-Type: application/javascript+module\r\n\r\n" +
                "export default {}\r\n" +
                "--B--\r\n",
            String(body.bytes(), StandardCharsets.UTF_8),
        )
        assertEquals(String(body.bytes(), StandardCharsets.UTF_8), String(Base64.getDecoder().decode(body.base64()), StandardCharsets.UTF_8))
    }

    @Test
    fun headerValuesCannotInjectHeadersOrBreakQuotes() {
        val body = CloudflareMultipartComposer.compose(
            listOf(
                CloudflareMultipartPart(
                    name = "file\r\nX-Injected: 1",
                    isFile = true,
                    fileName = "a\"b\n.txt",
                    mimeType = "text/plain\r\nX: y",
                    fileData = byteArrayOf(65),
                ),
            ),
            boundary = "B",
        )
        val text = String(body.bytes(), StandardCharsets.UTF_8)
        assertFalse(text.contains("\r\nX-Injected"))
        assertTrue(text.contains("name=\"fileX-Injected: 1\"; filename=\"a%22b.txt\""))
        assertTrue(text.contains("Content-Type: text/plainX: y\r\n"))
    }

    @Test
    fun requiredFieldsEmptyBodiesAndPartLimitUseIosMessages() {
        expect("Add values for required fields: file.") {
            CloudflareMultipartComposer.compose(listOf(CloudflareMultipartPart(name = "file", isFile = true, isRequired = true)))
        }
        expect("Add at least one form field or file.") {
            CloudflareMultipartComposer.compose(listOf(CloudflareMultipartPart(name = "a", value = "")))
        }
        expect("Multipart requests support up to 100 fields.") {
            CloudflareMultipartComposer.compose((0..100).map { CloudflareMultipartPart(name = "f$it", value = "v") })
        }
        val parts = CloudflareMultipartComposer.initialParts(
            listOf(CloudflareMultipartField("metadata", isFile = false, required = true, suggestedValue = "{}")),
        )
        assertEquals("{}", parts.single().value)
        assertTrue(parts.single().isRequired)
    }

    @Test
    fun payloadIsCappedAt25MegabytesAcrossParts() {
        val half = CloudflareMultipartComposer.UPLOAD_LIMIT_BYTES / 2
        val parts = listOf(
            CloudflareMultipartPart(name = "a", isFile = true, fileData = ByteArray(half)),
            CloudflareMultipartPart(name = "b", isFile = true, fileData = ByteArray(half + 1)),
        )
        expect(CloudflareMultipartComposer.TOO_LARGE_MESSAGE) { CloudflareMultipartComposer.compose(parts) }
        assertEquals(CloudflareMultipartComposer.UPLOAD_LIMIT_BYTES - half - 1, CloudflareMultipartComposer.remainingBytes(parts, 0))
        assertEquals(CloudflareMultipartComposer.UPLOAD_LIMIT_BYTES - half, CloudflareMultipartComposer.remainingBytes(parts, 1))
        // Framing overhead alone can push a payload that fits by itself over the limit.
        expect(CloudflareMultipartComposer.TOO_LARGE_MESSAGE) {
            CloudflareMultipartComposer.compose(
                listOf(CloudflareMultipartPart(name = "a", isFile = true, fileData = ByteArray(CloudflareMultipartComposer.UPLOAD_LIMIT_BYTES))),
            )
        }
    }

    private fun expect(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $message")
        } catch (error: CloudflareMultipartException) {
            assertEquals(message, error.message)
        }
    }
}
