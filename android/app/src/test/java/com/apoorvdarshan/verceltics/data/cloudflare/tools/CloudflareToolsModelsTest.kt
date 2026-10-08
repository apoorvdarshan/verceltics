package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareToolsModelsTest {
    // Ports of iOS CloudflareSafetyTests pagination-guard cases.
    @Test
    fun paginationGuardRejectsRepeatedNonemptyPage() {
        val guard = CloudflarePaginationGuard()
        guard.record(batchCount = 2, signature = 42)
        val error = assertThrows(CloudflareToolsException::class.java) { guard.record(batchCount = 2, signature = 42) }
        assertEquals("Cloudflare repeated a results page, so loading stopped safely.", error.message)
    }

    @Test
    fun paginationGuardAllowsTerminalEmptyPage() {
        val guard = CloudflarePaginationGuard()
        guard.record(batchCount = 1, signature = 7)
        guard.record(batchCount = 0, signature = null)
        guard.record(batchCount = 0, signature = 7)
    }

    @Test
    fun paginationGuardBoundsTotalItemsAndPages() {
        val error = assertThrows(CloudflareToolsException::class.java) {
            CloudflarePaginationGuard().record(batchCount = 100_001, signature = 1)
        }
        assertEquals("Cloudflare returned too many paginated results. Narrow the request and try again.", error.message)
        val pages = CloudflarePaginationGuard(maximumPages = 2)
        pages.record(1, 1)
        pages.record(1, 2)
        assertThrows(CloudflareToolsException::class.java) { pages.record(1, 3) }
    }

    @Test
    fun membersFallBackLikeIos() {
        val anonymous = CloudflareAccountOperationsParser.member(ProviderJsonParser.parse("""{"status":"pending"}"""))
        assertEquals("unknown-member", anonymous.id)
        assertEquals("Email unavailable", anonymous.resolvedEmail)
        assertEquals("Email unavailable", anonymous.displayName)
        assertFalse(anonymous.twoFactorEnabled)

        val invited = CloudflareAccountOperationsParser.member(
            ProviderJsonParser.parse(
                """{"id":"m","email":"invite@example.com","user":{"first_name":"","last_name":null},
                   "policies":[{"access":"deny","permission_groups":[{"id":"pg","name":"Read"}],"resource_groups":[]}]}""",
            ),
        )
        assertEquals("invite@example.com", invited.displayName)
        assertEquals("unknown-policy", invited.policies.single().id)
        assertEquals("deny", invited.policies.single().access)
        assertEquals("id: pg, name: Read", CloudflareToolsFormat.displayText(ProviderJsonValue.Arr(invited.policies.single().permissionGroups)))
    }

    @Test
    fun rolesAndAuditEventsFallBackLikeIos() {
        val role = CloudflareAccountOperationsParser.role(ProviderJsonParser.parse("{}"))
        assertEquals("unknown-role", role.id)
        assertEquals("Unnamed role", role.name)
        assertEquals("", role.permissionSummary)
        assertEquals("none", CloudflarePermissionGrant(read = null, write = null).label)

        val event = CloudflareAccountOperationsParser.auditEvent(
            ProviderJsonParser.parse("""{"action":{"time":"2026-10-01T00:00:00Z"},"raw":{"method":"GET","uri":"/x"}}"""),
        )
        assertEquals("2026-10-01T00:00:00Z|GET|/x", event.id)
        assertEquals("Cloudflare activity", event.title)
        assertFalse(event.isFailure)
        assertNull(event.resourceScope)

        assertThrows(CloudflareToolsException::class.java) {
            CloudflareAccountOperationsParser.accountDetail(ProviderJsonParser.parse("""{"name":"x"}"""))
        }
        val minimal = CloudflareAccountOperationsParser.accountDetail(ProviderJsonParser.parse("""{"id":"a"}"""))
        assertEquals("Unnamed account", minimal.name)
        assertNull(minimal.enforceTwoFactor)
        assertFalse(minimal.isManaged)
    }

    @Test
    fun displayTextMatchesIosOperationsDisplayText() {
        val value = ProviderJsonParser.parse(
            """{"b_key":[1,2.5,true,null],"a_key":{"nested_value":"x"},"big":1234567}""",
        )
        assertEquals(
            "a key: nested value: x, b key: 1, 2.5, On, Not set, big: ${CloudflareToolsFormat.number(java.math.BigDecimal(1234567))}",
            CloudflareToolsFormat.displayText(value),
        )
        assertEquals("Off", CloudflareToolsFormat.displayText(ProviderJsonValue.Bool(false)))
        assertEquals("Read Write", CloudflareToolsFormat.titleCase("read_write"))
        assertEquals("Api Token", CloudflareToolsFormat.titleCase("api token"))
        assertNull(CloudflareToolsFormat.instant("not a date"))
        assertNull(CloudflareToolsFormat.dateTime(null))
        assertEquals(1_759_276_800_000L, CloudflareToolsFormat.instant("2025-10-01T00:00:00+00:00")!!.toEpochMilli())
        assertTrue(CloudflareToolsFormat.dateTime("2025-10-01T00:00:00Z", ZoneOffset.UTC)!!.contains("2025"))
    }

    @Test
    fun rawResponsePrettyPrintsSortedJsonAndKeepsExactNumbers() {
        val response = CloudflareRawResponse(
            200,
            mapOf("content-type" to "application/json", "CF-RAY" to "1"),
            """{"z":1,"a":{"id":12345678901234567890,"list":[],"obj":{},"text":"line\nnext"}}""".toByteArray(),
            12,
        )
        assertEquals(
            """
            {
              "a": {
                "id": 12345678901234567890,
                "list": [],
                "obj": {},
                "text": "line\nnext"
              },
              "z": 1
            }
            """.trimIndent(),
            response.prettyPrintedBody,
        )
        assertTrue(response.isSuccess)
        assertEquals(listOf("CF-RAY", "content-type"), response.headers.keys.toList())
        assertEquals("1", response.headers["cf-ray"])
        assertEquals(12L, response.withElapsed(99).elapsedMillis?.minus(87))

        val text = CloudflareRawResponse(500, emptyMap(), "plain failure".toByteArray())
        assertEquals("plain failure", text.prettyPrintedBody)
        assertFalse(text.isSuccess)
        assertNull(text.parsedJson())
        assertEquals("", CloudflareRawResponse(204, emptyMap(), ByteArray(0)).prettyPrintedBody)
        assertEquals("[\n  1,\n  \"x\"\n]", CloudflareRawResponse(200, emptyMap(), "[1,\"x\"]".toByteArray()).prettyPrintedBody)
    }

    @Test
    fun rawResponseNeverExposesItsBodyInToString() {
        val response = CloudflareRawResponse(200, emptyMap(), "{\"secret\":\"value\"}".toByteArray())
        assertFalse(response.toString().contains("value"))
        val bytes = response.bodyBytes()
        bytes.fill(0)
        assertEquals("{\"secret\":\"value\"}", response.text)
    }

    @Test
    fun multipartBodyIncludesFieldsFilesAndClosingBoundary() {
        val file = byteArrayOf(0, -1, 10, 13, 65)
        val body = CloudflareMultipartBuilder.compose(
            listOf(
                CloudflareMultipartPart(name = "metadata", value = "{\"main_module\":\"w.js\"}", isRequired = true),
                CloudflareMultipartPart(name = "w.js", isFile = true, fileName = "w\".js", mimeType = "application/javascript+module", fileData = file),
                CloudflareMultipartPart(name = "", value = "ignored"),
                CloudflareMultipartPart(name = "optional", value = ""),
            ),
            boundary = "B",
        )
        assertEquals("multipart/form-data; boundary=B", body.contentType)
        val expected = "--B\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{\"main_module\":\"w.js\"}\r\n" +
            "--B\r\nContent-Disposition: form-data; name=\"w.js\"; filename=\"w'.js\"\r\nContent-Type: application/javascript+module\r\n\r\n"
        val bytes = body.bytes()
        assertArrayEquals(expected.toByteArray(StandardCharsets.UTF_8), bytes.copyOfRange(0, expected.toByteArray().size))
        assertArrayEquals(file, bytes.copyOfRange(expected.toByteArray().size, expected.toByteArray().size + file.size))
        assertTrue(String(bytes, StandardCharsets.ISO_8859_1).endsWith("\r\n--B--\r\n"))
        assertFalse(body.toString().contains("main_module"))
    }

    @Test
    fun multipartValidationMatchesIos() {
        assertEquals(
            "Add values for required fields: metadata, script.",
            assertThrows(CloudflareToolsException::class.java) {
                CloudflareMultipartBuilder.compose(
                    listOf(
                        CloudflareMultipartPart(name = "metadata", isRequired = true),
                        CloudflareMultipartPart(name = "script", isFile = true, isRequired = true),
                    ),
                )
            }.message,
        )
        assertEquals(
            "Add at least one form field or file.",
            assertThrows(CloudflareToolsException::class.java) {
                CloudflareMultipartBuilder.compose(listOf(CloudflareMultipartPart(name = "x")))
            }.message,
        )
        val parts = (0..CloudflareMultipartBuilder.PART_LIMIT).map { CloudflareMultipartPart(name = "f$it", value = "v") }
        assertEquals(
            "Multipart requests support up to 100 fields.",
            assertThrows(CloudflareToolsException::class.java) { CloudflareMultipartBuilder.compose(parts) }.message,
        )
        val big = CloudflareMultipartPart(name = "file", isFile = true, fileData = ByteArray(CloudflareMultipartBuilder.UPLOAD_LIMIT_BYTES - 10))
        val other = CloudflareMultipartPart(name = "x", value = "12345678901234567890")
        assertEquals(
            "The combined multipart body must be 25 MB or smaller.",
            assertThrows(CloudflareToolsException::class.java) { CloudflareMultipartBuilder.compose(listOf(big, other)) }.message,
        )
        assertEquals(10, CloudflareMultipartBuilder.remainingBytes(listOf(big, other), other.id) )
        assertEquals(CloudflareMultipartBuilder.UPLOAD_LIMIT_BYTES - 20, CloudflareMultipartBuilder.remainingBytes(listOf(big, other), big.id))
    }

    @Test
    fun multipartPartsCopyFileBytesAndSeedFromSchema() {
        val data = byteArrayOf(1, 2, 3)
        val part = CloudflareMultipartPart(name = "f", isFile = true, fileData = data)
        data.fill(9)
        assertArrayEquals(byteArrayOf(1, 2, 3), part.fileBytes())
        assertEquals(3, part.fileSize)
        val renamed = part.copy(name = "g")
        assertEquals(part.id, renamed.id)
        assertEquals("g", renamed.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), renamed.fileBytes())
        assertFalse(part.toString().contains("1, 2, 3"))

        val schemaText = CloudflareMultipartPart.fromSchema(CloudflareMultipartFieldSpec("metadata", true, false, suggestedValue = "{}"))
        assertEquals("{}", schemaText.value)
        assertTrue(schemaText.isRequired)
        val schemaFile = CloudflareMultipartPart.fromSchema(CloudflareMultipartFieldSpec("file", false, true, suggestedValue = "ignored"))
        assertEquals("", schemaFile.value)
        assertTrue(schemaFile.isFile)
    }
}
