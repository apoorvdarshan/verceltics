package com.apoorvdarshan.verceltics.data.account

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VercelAccountListCodecTest {
    @Test
    fun roundTripKeepsEveryAccountFieldOrderAndTheActiveAccount() {
        val accounts = VercelAccounts.EMPTY
            .connect(account("user_a", "token-a").withUsername("apoorv").withLongAnalyticsHistory(), 10L)
            .connect(account("user_b", "token-b", avatar = "f00dbabe"), 20L)
            .switchTo("user_a")

        val decoded = VercelAccountListCodec.decode(VercelAccountListCodec.encode(accounts))

        assertEquals(listOf("user_a", "user_b"), decoded.accounts.map(VercelAccount::id))
        assertEquals("user_a", decoded.activeAccountId)
        val first = checkNotNull(decoded.find("user_a"))
        assertEquals(SecretValue.of("token-a"), first.token)
        assertEquals("apoorv", first.username)
        assertTrue(first.hasLongAnalyticsHistory)
        assertEquals("f00dbabe", decoded.find("user_b")?.avatar)
        assertEquals(SecretValue.of("token-b"), decoded.find("user_b")?.token)
    }

    @Test
    fun anEmptyListRoundTrips() {
        val decoded = VercelAccountListCodec.decode(VercelAccountListCodec.encode(VercelAccounts.EMPTY))

        assertTrue(decoded.isEmpty)
    }

    @Test
    fun unknownVersionsTrailingBytesAndMissingActiveAccountsAreRejected() {
        val encoded = VercelAccountListCodec.encode(VercelAccounts.EMPTY.connect(account("user_a", "a"), 1L))
        val futureVersion = encoded.copyOf().also { it[3] = 9 }

        assertThrows(IllegalArgumentException::class.java) { VercelAccountListCodec.decode(futureVersion) }
        assertThrows(IllegalArgumentException::class.java) { VercelAccountListCodec.decode(encoded + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            VercelAccountListCodec.decode(listBytes(activeId = "user_missing", records = emptyList()))
        }
    }

    @Test
    fun aListFromAnotherProviderSlotIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VercelAccountListCodec.decode(listBytes(provider = "netlify", activeId = null, records = emptyList()))
        }
    }

    @Test
    fun anImpossibleAccountCountIsRejectedBeforeReading() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeUtf8("vercel")
                data.writeBoolean(false)
                data.writeInt(VercelAccounts.MAX_ACCOUNTS + 1)
            }
        }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) { VercelAccountListCodec.decode(bytes) }
    }

    private fun listBytes(provider: String = "vercel", activeId: String?, records: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1)
                data.writeUtf8(provider)
                data.writeBoolean(activeId != null)
                activeId?.let { data.writeUtf8(it) }
                data.writeInt(records.size)
                records.forEach { record ->
                    data.writeInt(record.size)
                    data.write(record)
                }
            }
        }.toByteArray()

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun account(id: String, token: String, avatar: String? = null) = VercelAccount(
        id = id,
        displayName = "Account $id",
        email = null,
        token = SecretValue.of(token),
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
        avatar = avatar,
    )
}
