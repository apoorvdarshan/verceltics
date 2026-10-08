package com.apoorvdarshan.verceltics.data.searchconsole

import com.apoorvdarshan.verceltics.data.account.SecretValue
import com.apoorvdarshan.verceltics.data.sites.SiteAccountRecordCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal const val MAX_SEARCH_CONSOLE_STORED_STRING_BYTES = 16_384

/** Provider-specific plaintext format used only immediately beside authenticated encryption. */
object SearchConsoleConnectionPayloadCodec {
    private const val PAYLOAD_VERSION = 1
    private const val PROVIDER_ID = "google-search-console"
    private const val MAX_SECRET_BYTES = 65_536
    private const val MAX_CACHED_PROPERTIES = 25

    fun encode(connection: SearchConsoleStoredConnection): ByteArray {
        val bytes = WipingSearchConsoleByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            output.writeInt(PAYLOAD_VERSION)
            writeString(output, PROVIDER_ID)
            writeString(output, connection.id)
            writeSecret(output, connection.credential.accessToken)
            output.writeBoolean(connection.credential.refreshToken != null)
            connection.credential.refreshToken?.let { writeSecret(output, it) }
            writeString(output, connection.credential.tokenType)
            output.writeInt(connection.credential.scopes.size)
            connection.credential.scopes.forEach { writeString(output, it) }
            output.writeLong(connection.credential.expiresAtMillis)
            writeOptionalString(output, connection.credential.subject)
            writeOptionalString(output, connection.credential.email)
            output.writeLong(connection.createdAtMillis)
            output.writeLong(connection.updatedAtMillis)
            output.writeBoolean(connection.cachedSnapshot != null)
            connection.cachedSnapshot?.let { writeSnapshot(output, it) }
            output.flush()
            bytes.toByteArray()
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): SearchConsoleStoredConnection {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported Search Console payload version." }
            require(readString(input) == PROVIDER_ID) { "Wrong provider in Search Console storage slot." }
            val id = readString(input)
            val accessToken = readSecret(input)
            val refreshToken = if (input.readBoolean()) readSecret(input) else null
            val tokenType = readString(input)
            val scopeCount = readCount(input, MAX_SCOPES, "scope")
            val scopes = List(scopeCount) { readString(input) }
            val expiresAtMillis = input.readLong()
            val subject = readOptionalString(input)
            val email = readOptionalString(input)
            val createdAtMillis = input.readLong()
            val updatedAtMillis = input.readLong()
            val cachedSnapshot = if (input.readBoolean()) readSnapshot(input) else null
            require(input.available() == 0) { "Unexpected trailing Search Console data." }
            return SearchConsoleStoredConnection(
                id = id,
                credential = SearchConsoleOAuthCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenType = tokenType,
                    scopes = scopes,
                    expiresAtMillis = expiresAtMillis,
                    subject = subject,
                    email = email,
                ),
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                cachedSnapshot = cachedSnapshot,
            )
        }
    }

    internal fun writeSnapshot(output: DataOutputStream, snapshot: SearchConsoleSnapshot) {
        require(snapshot.properties.size <= MAX_CACHED_PROPERTIES) { "Search Console cache is too large." }
        output.writeLong(snapshot.fetchedAtMillis)
        output.writeBoolean(snapshot.propertiesComplete)
        output.writeInt(snapshot.properties.size)
        snapshot.properties.forEach { property ->
            writeString(output, property.siteUrl)
            writeString(output, property.permissionLevel)
        }
        output.writeInt(snapshot.warnings.size)
        snapshot.warnings.forEach { writeString(output, it) }
    }

    internal fun readSnapshot(input: DataInputStream): SearchConsoleSnapshot {
        val fetchedAtMillis = input.readLong()
        val complete = input.readBoolean()
        val propertyCount = readCount(input, MAX_CACHED_PROPERTIES, "cached property")
        val properties = List(propertyCount) {
            SearchConsoleProperty(readString(input), readString(input))
        }
        val warningCount = readCount(input, MAX_WARNINGS, "warning")
        val warnings = List(warningCount) { readString(input) }
        return SearchConsoleSnapshot(properties, fetchedAtMillis, complete, warnings)
    }

    private fun writeSecret(output: DataOutputStream, secret: SecretValue) {
        val bytes = secret.utf8Bytes()
        try {
            writeBytes(output, bytes, MAX_SECRET_BYTES)
        } finally {
            bytes.fill(0)
        }
    }

    private fun readSecret(input: DataInputStream): SecretValue {
        val bytes = readBytes(input, MAX_SECRET_BYTES)
        return try {
            SecretValue.of(String(bytes, StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    internal fun writeOptionalString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        value?.let { writeString(output, it) }
    }

    internal fun readOptionalString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    internal fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeBytes(output, bytes, MAX_SEARCH_CONSOLE_STORED_STRING_BYTES)
        } finally {
            bytes.fill(0)
        }
    }

    internal fun readString(input: DataInputStream): String {
        val bytes = readBytes(input, MAX_SEARCH_CONSOLE_STORED_STRING_BYTES)
        return try {
            String(bytes, StandardCharsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray, maximum: Int) {
        require(bytes.size <= maximum) { "Search Console field is too large." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream, maximum: Int): ByteArray {
        val length = input.readInt()
        require(length in 0..maximum && length <= input.available()) {
            "Invalid Search Console field length."
        }
        return ByteArray(length).also(input::readFully)
    }

    private fun readCount(input: DataInputStream, maximum: Int, label: String): Int {
        val count = input.readInt()
        require(count in 0..maximum) { "Invalid Search Console $label count." }
        return count
    }
}

/**
 * Per-account record format (version 2). Unlike the legacy single-account payload it holds no
 * tokens: each account's Google credential lives in its own encrypted OAuth slot.
 */
object SearchConsoleAccountRecordCodec : SiteAccountRecordCodec<SearchConsoleAccountRecord> {
    private const val PAYLOAD_VERSION = 2
    private const val PROVIDER_ID = "google-search-console"

    override fun encode(record: SearchConsoleAccountRecord): ByteArray {
        val bytes = WipingSearchConsoleByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            output.writeInt(PAYLOAD_VERSION)
            SearchConsoleConnectionPayloadCodec.writeString(output, PROVIDER_ID)
            SearchConsoleConnectionPayloadCodec.writeString(output, record.id)
            SearchConsoleConnectionPayloadCodec.writeOptionalString(output, record.subject)
            SearchConsoleConnectionPayloadCodec.writeOptionalString(output, record.email)
            output.writeLong(record.createdAtMillis)
            output.writeLong(record.updatedAtMillis)
            output.writeBoolean(record.cachedSnapshot != null)
            record.cachedSnapshot?.let { SearchConsoleConnectionPayloadCodec.writeSnapshot(output, it) }
            output.flush()
            bytes.toByteArray()
        } finally {
            output.close()
        }
    }

    override fun decode(bytes: ByteArray, accountId: String): SearchConsoleAccountRecord {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported Search Console account version." }
            require(SearchConsoleConnectionPayloadCodec.readString(input) == PROVIDER_ID) {
                "Wrong provider in Search Console account record."
            }
            require(SearchConsoleConnectionPayloadCodec.readString(input) == accountId) {
                "The Search Console record belongs to another account."
            }
            val subject = SearchConsoleConnectionPayloadCodec.readOptionalString(input)
            val email = SearchConsoleConnectionPayloadCodec.readOptionalString(input)
            val createdAtMillis = input.readLong()
            val updatedAtMillis = input.readLong()
            val snapshot = if (input.readBoolean()) SearchConsoleConnectionPayloadCodec.readSnapshot(input) else null
            require(input.available() == 0) { "Unexpected trailing Search Console data." }
            return SearchConsoleAccountRecord(accountId, subject, email, createdAtMillis, updatedAtMillis, snapshot)
        }
    }
}

private class WipingSearchConsoleByteArrayOutputStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
