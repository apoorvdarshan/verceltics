package com.apoorvdarshan.verceltics.data.cloudflare.tools

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareExplorerResponseTest {
    @Test
    fun explorerResponsesMayBe32MegabytesLikeIos() {
        assertEquals(32 * 1_024 * 1_024, SecureCloudflareToolsTransport.DEFAULT_MAXIMUM_RESPONSE_BYTES)
        assertEquals(32 * 1_024 * 1_024, SecureCloudflareToolsTransport.HARD_MAXIMUM_RESPONSE_BYTES)
        SecureCloudflareToolsTransport() // The default limit is accepted.
    }

    @Test
    fun segmentedBufferJoinsChunksExactlyAndWipesOnClose() {
        val buffer = CloudflareSegmentedBuffer(segmentSize = 4)
        val source = "0123456789abcdef-tail".toByteArray()
        buffer.write(source, 3)
        buffer.write(source.copyOfRange(3, source.size), source.size - 3)
        assertEquals(source.size, buffer.size)
        assertArrayEquals(source, buffer.toByteArray())
        buffer.close()
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.toByteArray().size)
    }

    @Test
    fun smallJsonIsPrettyPrintedAndPreviewedInFull() {
        val response = CloudflareRawResponse(200, emptyMap(), """{"b":1,"a":true}""".toByteArray())
        val preview = response.preview(1_000)
        assertEquals("{\n  \"a\": true,\n  \"b\": 1\n}", preview.text)
        assertFalse(preview.truncated)
        assertTrue(response.isParseable)
    }

    @Test
    fun hugeResponsesAreNeverParsedAndOnlyALeadingSliceIsDecoded() {
        val body = ByteArray(CloudflareRawResponse.MAXIMUM_PARSE_BYTES + 1_024) { 'x'.code.toByte() }
        body[0] = '['.code.toByte()
        val response = CloudflareRawResponse(200, emptyMap(), body)

        assertFalse(response.isParseable)
        assertNull(response.parsedJson())
        val preview = response.preview(200_000)
        assertEquals(200_000, preview.text.length)
        assertTrue(preview.truncated)
        assertTrue(preview.text.startsWith("[xxx"))
        assertTrue(response.prettyPrintedBody.length <= 300_000)
    }

    @Test
    fun previewNeverSplitsIntoGarbageAtMultibyteBoundaries() {
        val text = "é".repeat(CloudflareRawResponse.MAXIMUM_PARSE_BYTES / 2 + 10)
        val response = CloudflareRawResponse(200, emptyMap(), text.toByteArray(StandardCharsets.UTF_8))
        val preview = response.preview(10)
        assertEquals("é".repeat(10), preview.text)
        assertTrue(preview.truncated)
    }
}
