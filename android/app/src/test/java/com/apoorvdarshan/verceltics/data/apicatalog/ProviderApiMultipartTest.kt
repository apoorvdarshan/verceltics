package com.apoorvdarshan.verceltics.data.apicatalog

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderApiMultipartTest {
    @Test
    fun initialPartsComeFromTheSchemaWithSuggestedValues() {
        val parts = ProviderApiMultipart.initialParts(
            listOf(
                ProviderApiMultipartField("ownerId", true, false, "string", null, null, "tea-1"),
                ProviderApiMultipartField("file", true, true, "string", "binary", null, "ignored"),
            ),
        )
        assertEquals(listOf("ownerId", "file"), parts.map { it.name })
        assertEquals("tea-1", parts[0].value)
        assertEquals("", parts[1].value)
        assertTrue(parts.all { it.isRequired })
        assertTrue(parts[1].isFile)
        assertFalse(parts[1].hasFile)
    }

    @Test
    fun composeEncodesTextAndFilePartsLikeIos() {
        val text = ProviderApiMultipartPart(id = "t", name = "ownerId", value = "tea-1", isRequired = true)
        val file = ProviderApiMultipartPart(
            id = "f",
            name = "file",
            isFile = true,
            isRequired = true,
            fileName = "render\".yaml",
            mimeType = "application/x-yaml",
            fileByteCount = 4,
        )
        val unused = ProviderApiMultipartPart(id = "u", name = "  ", value = "dropped")
        val body = ProviderApiMultipart.compose(listOf(text, file, unused), mapOf("f" to "a: b".toByteArray())::get, boundary = "B")
        assertEquals("multipart/form-data; boundary=B", body.contentType)
        assertEquals(
            "--B\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\ntea-1\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"render'.yaml\"\r\n" +
                "Content-Type: application/x-yaml\r\n\r\na: b\r\n--B--\r\n",
            String(body.bytes(), StandardCharsets.UTF_8),
        )
        assertEquals(body.bytes().size, body.size)
    }

    @Test
    fun missingRequiredFieldsAndEmptyFormsUseIosCopy() {
        val required = ProviderApiMultipartPart(id = "f", name = "file", isFile = true, isRequired = true)
        try {
            ProviderApiMultipart.compose(listOf(required), { null })
            fail("Expected a missing file")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Add values for required fields: file.", error.message)
        }
        try {
            ProviderApiMultipart.compose(listOf(ProviderApiMultipartPart(id = "x", name = "note")), { null })
            fail("Expected an empty form")
        } catch (error: ProviderApiRequestException) {
            assertEquals("Add at least one form field or file.", error.message)
        }
    }

    @Test
    fun payloadsAreLimitedTo25Megabytes() {
        val big = ByteArray(ProviderApiMultipart.UPLOAD_LIMIT_BYTES)
        val parts = listOf(
            ProviderApiMultipartPart(id = "a", name = "a", isFile = true, fileByteCount = big.size),
            ProviderApiMultipartPart(id = "b", name = "b", value = "x"),
        )
        try {
            ProviderApiMultipart.compose(parts, { if (it == "a") big else null })
            fail("Expected an oversized body")
        } catch (error: ProviderApiRequestException) {
            assertEquals("The combined multipart body must be 25 MB or smaller.", error.message)
        }
        assertEquals(0, ProviderApiMultipart.remainingBytes(parts, "b"))
        assertEquals(ProviderApiMultipart.UPLOAD_LIMIT_BYTES - 1, ProviderApiMultipart.remainingBytes(parts, "a"))
    }
}
