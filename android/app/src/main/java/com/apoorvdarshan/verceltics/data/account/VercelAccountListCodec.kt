package com.apoorvdarshan.verceltics.data.account

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Plaintext codec for the saved Vercel account list, used only immediately before encryption or
 * immediately after decryption. Each account is embedded as a length-prefixed
 * [VercelAccountPayloadCodec] record, so account fields keep a single versioned format.
 *
 * ```
 * int     list version (1)
 * string  provider id ("vercel")
 * bool    has active id, then string active id
 * int     account count
 * count × (int length, VercelAccountPayloadCodec bytes)
 * ```
 */
object VercelAccountListCodec {
    private const val LIST_VERSION = 1
    private const val MAX_ID_BYTES = 1_024

    fun encode(accounts: VercelAccounts): ByteArray {
        val bytes = WipingByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            output.writeInt(LIST_VERSION)
            writeString(output, VercelAccount.PROVIDER_ID)
            output.writeBoolean(accounts.activeAccountId != null)
            accounts.activeAccountId?.let { writeString(output, it) }
            output.writeInt(accounts.accounts.size)
            accounts.accounts.forEach { account ->
                val record = VercelAccountPayloadCodec.encode(account)
                try {
                    output.writeInt(record.size)
                    output.write(record)
                } finally {
                    record.fill(0)
                }
            }
            output.flush()
            bytes.toByteArray()
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): VercelAccounts {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == LIST_VERSION) { "Unsupported account list version." }
            require(readString(input) == VercelAccount.PROVIDER_ID) {
                "The account list provider does not match its storage slot."
            }
            val activeAccountId = if (input.readBoolean()) readString(input) else null
            val count = input.readInt()
            require(count in 0..VercelAccounts.MAX_ACCOUNTS) { "Invalid saved account count." }
            val accounts = List(count) {
                val length = input.readInt()
                require(length in 1..VercelAccountPayloadCodec.MAX_ENCODED_BYTES && length <= input.available()) {
                    "Invalid saved account length."
                }
                val record = ByteArray(length)
                try {
                    input.readFully(record)
                    VercelAccountPayloadCodec.decode(record)
                } finally {
                    record.fill(0)
                }
            }
            require(input.available() == 0) { "Unexpected trailing account list data." }
            require(activeAccountId == null || accounts.any { it.id == activeAccountId }) {
                "The saved active Vercel account is missing."
            }
            return VercelAccounts.of(accounts, activeAccountId)
        }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            require(bytes.size <= MAX_ID_BYTES) { "Account list field is too large." }
            output.writeInt(bytes.size)
            output.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_ID_BYTES && length <= input.available()) { "Invalid account list field length." }
        val bytes = ByteArray(length)
        return try {
            input.readFully(bytes)
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }
}
