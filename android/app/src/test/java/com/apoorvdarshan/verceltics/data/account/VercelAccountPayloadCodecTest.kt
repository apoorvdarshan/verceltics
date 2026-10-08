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
        assertEquals("The legacy id was the Vercel user id.", "user_123", decoded.vercelUserId)
        assertEquals(SecretValue.of("legacy-token"), decoded.token)
        assertNull("Legacy accounts learn their username on the next refresh.", decoded.username)
        assertFalse(decoded.hasLongAnalyticsHistory)
    }

    @Test
    fun versionThreeRoundTripsTheAvatar() {
        val original = account("secret-token-value").withProfile(
            displayName = "Apoorv Darshan",
            email = "new@example.com",
            username = "apoorvdarshan",
            avatar = "0123456789abcdef",
        )

        val decoded = VercelAccountPayloadCodec.decode(VercelAccountPayloadCodec.encode(original))

        assertEquals("0123456789abcdef", decoded.avatar)
        assertEquals("Apoorv Darshan", decoded.displayName)
        assertEquals("new@example.com", decoded.email)
        assertEquals("apoorvdarshan", decoded.username)
    }

    @Test
    fun legacyVersionTwoPayloadsDecodeWithoutAnAvatar() {
        val legacy = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(2)
                output.writeUtf8("vercel")
                output.writeUtf8("user_123")
                output.writeUtf8("Apoorv")
                output.writeBoolean(false)
                output.writeUtf8("legacy-token")
                output.writeLong(1_000L)
                output.writeLong(2_000L)
                output.writeBoolean(true)
                output.writeUtf8("apoorv")
                output.writeBoolean(true)
            }
        }.toByteArray()

        val decoded = VercelAccountPayloadCodec.decode(legacy)

        assertEquals("apoorv", decoded.username)
        assertTrue(decoded.hasLongAnalyticsHistory)
        assertNull(decoded.email)
        assertNull(decoded.avatar)
        assertEquals(SecretValue.of("legacy-token"), decoded.token)
    }

    @Test
    fun profileRefreshKeepsTokenTimestampsAndAnalyticsFlag() {
        val base = account("token").withUsername("apoorv").withLongAnalyticsHistory()

        val refreshed = base.withProfile(displayName = "Renamed", email = null, username = null, avatar = "abc")

        assertEquals("Renamed", refreshed.displayName)
        assertNull(refreshed.email)
        assertEquals("A missing username keeps the saved one.", "apoorv", refreshed.username)
        assertEquals("abc", refreshed.avatar)
        assertEquals(base.token, refreshed.token)
        assertEquals(base.updatedAtMillis, refreshed.updatedAtMillis)
        assertTrue(refreshed.hasLongAnalyticsHistory)
        assertFalse(refreshed.hasSameProfile(base))
        assertTrue(base.hasSameProfile(base.withLongAnalyticsHistory()))
    }

    @Test
    fun onlyTheSameTokenUpdatesAnAccountInPlace() {
        val saved = account("token").withUsername("apoorv").withLongAnalyticsHistory()
        val sameToken = VercelAccount(
            id = "local-new",
            vercelUserId = "user_123",
            displayName = "Apoorv Darshan",
            email = "new@example.com",
            token = SecretValue.of("token"),
            createdAtMillis = 9_000L,
            updatedAtMillis = 9_000L,
            avatar = "abc",
        )

        val updated = saved.reconnectedWith(sameToken, nowMillis = 9_000L)

        assertEquals("The local id never changes.", saved.id, updated.id)
        assertEquals("Apoorv Darshan", updated.displayName)
        assertEquals("abc", updated.avatar)
        assertEquals(saved.createdAtMillis, updated.createdAtMillis)
        assertEquals(9_000L, updated.updatedAtMillis)
        assertEquals("apoorv", updated.username)
        assertTrue(updated.hasLongAnalyticsHistory)
        assertThrows(IllegalArgumentException::class.java) {
            saved.reconnectedWith(account("another-token"), nowMillis = 5L)
        }
    }

    @Test
    fun versionFourKeepsTheLocalIdApartFromTheVercelUserId() {
        val original = VercelAccount(
            id = "3f1c2a52-local",
            vercelUserId = "user_123",
            displayName = "Studio token",
            email = null,
            token = SecretValue.of("team-token"),
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

        val decoded = VercelAccountPayloadCodec.decode(VercelAccountPayloadCodec.encode(original))

        assertEquals("3f1c2a52-local", decoded.id)
        assertEquals("user_123", decoded.vercelUserId)
    }

    @Test
    fun recordsBeforeVersionFourUseTheirIdAsBothLocalAndVercelUserId() {
        val versionThree = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(3)
                output.writeUtf8("vercel")
                output.writeUtf8("user_123")
                output.writeUtf8("Apoorv")
                output.writeBoolean(false)
                output.writeUtf8("v3-token")
                output.writeLong(1_000L)
                output.writeLong(2_000L)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeBoolean(true)
                output.writeUtf8("avatarhash")
            }
        }.toByteArray()

        val decoded = VercelAccountPayloadCodec.decode(versionThree)

        assertEquals("user_123", decoded.id)
        assertEquals("user_123", decoded.vercelUserId)
        assertEquals("avatarhash", decoded.avatar)
        assertEquals(SecretValue.of("v3-token"), decoded.token)
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
