package com.apoorvdarshan.verceltics.data.account

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelAccountPayloadCodecTest {
    @Test
    fun roundTripPreservesAccountAndRawProviderId() {
        val original = account("secret-token-value")

        val decoded = VercelAccountPayloadCodec.decode(VercelAccountPayloadCodec.encode(original))

        assertEquals("vercel", decoded.providerId)
        assertEquals(original.id, decoded.id)
        assertEquals(original.displayName, decoded.displayName)
        assertEquals(original.email, decoded.email)
        assertEquals(original.token, decoded.token)
        assertEquals(original.createdAtMillis, decoded.createdAtMillis)
        assertEquals(original.updatedAtMillis, decoded.updatedAtMillis)
        assertNull(decoded.username)
        assertFalse(decoded.hasLongAnalyticsHistory)
    }

    @Test
    fun versionTwoRoundTripsUsernameAndLongAnalyticsHistory() {
        val original = account("secret-token-value")
            .withUsername("apoorvdarshan")
            .withLongAnalyticsHistory()

        val decoded = VercelAccountPayloadCodec.decode(VercelAccountPayloadCodec.encode(original))

        assertEquals("apoorvdarshan", decoded.username)
        assertTrue(decoded.hasLongAnalyticsHistory)
        assertEquals(original.token, decoded.token)
    }

    @Test
    fun legacyVersionOnePayloadsStillDecode() {
        val legacy = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeUtf8("vercel")
                output.writeUtf8("user_123")
                output.writeUtf8("Apoorv")
                output.writeBoolean(true)
                output.writeUtf8("apoorv@example.com")
                output.writeUtf8("legacy-token")
                output.writeLong(1_000L)
                output.writeLong(2_000L)
            }
        }.toByteArray()

        val decoded = VercelAccountPayloadCodec.decode(legacy)

        assertEquals("user_123", decoded.id)
        assertEquals(SecretValue.of("legacy-token"), decoded.token)
        assertNull("Legacy accounts learn their username on the next refresh.", decoded.username)
        assertFalse(decoded.hasLongAnalyticsHistory)
    }

    @Test
    fun unknownVersionsAndTrailingBytesAreRejected() {
        val encoded = VercelAccountPayloadCodec.encode(account("token"))
        val futureVersion = encoded.copyOf().also { it[3] = 9 }

        assertThrows(IllegalArgumentException::class.java) { VercelAccountPayloadCodec.decode(futureVersion) }
        assertThrows(IllegalArgumentException::class.java) { VercelAccountPayloadCodec.decode(encoded + byteArrayOf(0)) }
    }

    @Test
    fun accountUpdatesKeepEveryOtherField() {
        val base = account("first").withUsername("apoorv").withLongAnalyticsHistory()

        val rotated = base.withUpdatedToken(SecretValue.of("second"), nowMillis = 5_000L)

        assertEquals(SecretValue.of("second"), rotated.token)
        assertEquals(5_000L, rotated.updatedAtMillis)
        assertEquals("apoorv", rotated.username)
        assertTrue(rotated.hasLongAnalyticsHistory)
        assertEquals(base.createdAtMillis, rotated.createdAtMillis)
        assertThrows(IllegalArgumentException::class.java) { base.withUsername(" ") }
    }

    @Test
    fun printableRepresentationsNeverContainToken() {
        val token = "do-not-print-this-token"
        val account = account(token)

        assertEquals("<redacted>", account.token.toString())
        assertTrue(account.toString().contains("token=<redacted>"))
        assertFalse(account.toString().contains(token))
        assertFalse(SealedPayload(ByteArray(12), ByteArray(16)).toString().contains("["))
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun account(token: String) = VercelAccount(
        id = "user_123",
        displayName = "Apoorv",
        email = "apoorv@example.com",
        token = SecretValue.of(token),
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L,
    )
}
