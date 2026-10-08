package com.apoorvdarshan.verceltics.data.account

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Plaintext codec used only immediately before encryption or immediately after decryption.
 *
 * Version 2 appended the optional username and the long-analytics-history flag; version 3 appends
 * the optional avatar. Version 1 and 2 payloads written by earlier builds still decode, with the
 * newer fields absent.
 */
object VercelAccountPayloadCodec {
    private const val FIRST_PAYLOAD_VERSION = 1
    private const val USERNAME_PAYLOAD_VERSION = 2
    private const val PAYLOAD_VERSION = 3
    private const val MAX_ID_BYTES = 1_024
    private const val MAX_DISPLAY_NAME_BYTES = 1_024
    private const val MAX_EMAIL_BYTES = 2_048
    private const val MAX_TOKEN_BYTES = 65_536
    private const val MAX_AVATAR_BYTES = 4_096

    /** Upper bound of one encoded account, used by the account-list codec. */
    internal const val MAX_ENCODED_BYTES: Int =
        4 + 3 * (4 + MAX_ID_BYTES) + 2 * (5 + MAX_DISPLAY_NAME_BYTES) + (5 + MAX_EMAIL_BYTES) +
            (4 + MAX_TOKEN_BYTES) + 16 + 1 + (5 + MAX_AVATAR_BYTES)

    fun encode(account: VercelAccount): ByteArray {
        val bytes = WipingByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            output.writeInt(PAYLOAD_VERSION)
            writeString(output, account.providerId, MAX_ID_BYTES)
            writeString(output, account.id, MAX_ID_BYTES)
            writeString(output, account.displayName, MAX_DISPLAY_NAME_BYTES)
            writeNullableString(output, account.email, MAX_EMAIL_BYTES)
            val tokenBytes = account.token.utf8Bytes()
            try {
                writeBytes(output, tokenBytes, MAX_TOKEN_BYTES)
            } finally {
                tokenBytes.fill(0)
            }
            output.writeLong(account.createdAtMillis)
            output.writeLong(account.updatedAtMillis)
            writeNullableString(output, account.username, MAX_DISPLAY_NAME_BYTES)
            output.writeBoolean(account.hasLongAnalyticsHistory)
            writeNullableString(output, account.avatar, MAX_AVATAR_BYTES)
            output.flush()
            bytes.toByteArray()
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): VercelAccount {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in FIRST_PAYLOAD_VERSION..PAYLOAD_VERSION) {
                "Unsupported account payload version."
            }
            require(readString(input, MAX_ID_BYTES) == VercelAccount.PROVIDER_ID) {
                "The account provider does not match its storage slot."
            }
            val id = readString(input, MAX_ID_BYTES)
            val displayName = readString(input, MAX_DISPLAY_NAME_BYTES)
            val email = readNullableString(input, MAX_EMAIL_BYTES)
            val tokenBytes = readBytes(input, MAX_TOKEN_BYTES)
            val token = try {
                SecretValue.of(String(tokenBytes, StandardCharsets.UTF_8))
            } finally {
                tokenBytes.fill(0)
            }
            val createdAtMillis = input.readLong()
            val updatedAtMillis = input.readLong()
            var username: String? = null
            var hasLongAnalyticsHistory = false
            var avatar: String? = null
            if (version >= USERNAME_PAYLOAD_VERSION) {
                username = readNullableString(input, MAX_DISPLAY_NAME_BYTES)
                hasLongAnalyticsHistory = input.readBoolean()
            }
            if (version >= PAYLOAD_VERSION) {
                avatar = readNullableString(input, MAX_AVATAR_BYTES)
            }
            require(input.available() == 0) { "Unexpected trailing account data." }
            return VercelAccount(
                id = id,
                displayName = displayName,
                email = email,
                token = token,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                username = username,
                hasLongAnalyticsHistory = hasLongAnalyticsHistory,
                avatar = avatar,
            )
        }
    }

    private fun writeString(output: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeBytes(output, bytes, maximumBytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeNullableString(
        output: DataOutputStream,
        value: String?,
        maximumBytes: Int,
    ) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value, maximumBytes)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray, maximumBytes: Int) {
        require(bytes.size <= maximumBytes) { "Account field is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream, maximumBytes: Int): String {
        val bytes = readBytes(input, maximumBytes)
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readNullableString(input: DataInputStream, maximumBytes: Int): String? =
        if (input.readBoolean()) readString(input, maximumBytes) else null

    private fun readBytes(input: DataInputStream, maximumBytes: Int): ByteArray {
        val length = input.readInt()
        require(length in 0..maximumBytes && length <= input.available()) {
            "Invalid account field length."
        }
        return ByteArray(length).also(input::readFully)
    }
}

internal class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
