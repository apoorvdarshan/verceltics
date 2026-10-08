package com.apoorvdarshan.verceltics.data.registrar

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Plaintext exists only between this codec and authenticated encryption. */
internal object RegistrarConnectionPayloadCodec {
    private const val PAYLOAD_VERSION = 1
    private const val MAX_ID_BYTES = 256
    private const val MAX_NAME_BYTES = 4_096
    private const val MAX_SECRET_BYTES = 65_536
    private const val MAX_METADATA_ENTRIES = 8
    private const val MAX_METADATA_BYTES = 2_048
    private const val MAX_WARNINGS = 16
    private const val MAX_WARNING_BYTES = 8_192
    internal const val MAX_CACHED_DOMAINS = 1_000
    internal const val MAX_DOMAIN_NAME_BYTES = 1_024
    internal const val MAX_STATUS_BYTES = 512
    internal const val MAX_NAMESERVERS = 16
    internal const val MAX_NAMESERVER_BYTES = 1_024
    internal const val MAX_PLAINTEXT_BYTES = 448 * 1024

    fun encode(connection: RegistrarStoredConnection): ByteArray {
        val bytes = WipingRegistrarPayloadStream()
        val output = DataOutputStream(bytes)
        return try {
            val account = connection.account
            output.writeInt(PAYLOAD_VERSION)
            writeString(output, account.provider.id, MAX_ID_BYTES)
            writeString(output, account.displayName, MAX_NAME_BYTES)
            writeSecret(output, account.credentials.primary)
            output.writeBoolean(account.credentials.secondary != null)
            account.credentials.secondary?.let { writeSecret(output, it) }
            writeMetadata(output, account.credentials.metadata)
            output.writeLong(account.createdAtMillis)
            output.writeLong(account.updatedAtMillis)
            output.writeBoolean(connection.cachedSnapshot != null)
            connection.cachedSnapshot?.let { snapshot ->
                writeString(output, snapshot.accountName, MAX_NAME_BYTES)
                output.writeLong(snapshot.fetchedAtMillis)
                output.writeBoolean(snapshot.domainsComplete)
                require(snapshot.warnings.size <= MAX_WARNINGS) { "Too many registrar warnings." }
                output.writeInt(snapshot.warnings.size)
                snapshot.warnings.forEach { writeString(output, it, MAX_WARNING_BYTES) }
                require(snapshot.domains.size <= MAX_CACHED_DOMAINS) { "Too many cached registrar domains." }
                output.writeInt(snapshot.domains.size)
                snapshot.domains.forEach { writeDomain(output, it) }
            }
            output.flush()
            bytes.toByteArray().also {
                require(it.size <= MAX_PLAINTEXT_BYTES) { "The registrar cache is too large to store safely." }
            }
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): RegistrarStoredConnection {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "The registrar payload is too large." }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported registrar payload version." }
            val provider = requireNotNull(RegistrarProvider.fromId(readString(input, MAX_ID_BYTES))) {
                "Unknown registrar provider."
            }
            val displayName = readString(input, MAX_NAME_BYTES)
            val primary = readSecret(input)
            val secondary = if (input.readBoolean()) readSecret(input) else null
            val metadata = readMetadata(input)
            val account = RegistrarAccount(
                displayName = displayName,
                credentials = RegistrarCredentials(provider, primary, secondary, metadata),
                createdAtMillis = input.readLong(),
                updatedAtMillis = input.readLong(),
            )
            val snapshot = if (input.readBoolean()) {
                val accountName = readString(input, MAX_NAME_BYTES)
                val fetchedAtMillis = input.readLong()
                val complete = input.readBoolean()
                val warnings = List(readCount(input, MAX_WARNINGS, "warning")) {
                    readString(input, MAX_WARNING_BYTES)
                }
                val domains = List(readCount(input, MAX_CACHED_DOMAINS, "domain")) { readDomain(input) }
                RegistrarSnapshot(
                    provider = provider,
                    accountName = accountName,
                    domains = domains,
                    fetchedAtMillis = fetchedAtMillis,
                    domainsComplete = complete,
                    warnings = warnings,
                )
            } else {
                null
            }
            require(input.available() == 0) { "Unexpected trailing registrar account data." }
            return RegistrarStoredConnection(account, snapshot)
        }
    }

    /** Exact encoded size of one cached domain, used to keep the offline cache within budget. */
    fun encodedDomainSize(domain: RegistrarDomain): Int {
        fun stringSize(value: String) = 4 + value.toByteArray(StandardCharsets.UTF_8).size
        fun nullableStringSize(value: String?) = 1 + (value?.let(::stringSize) ?: 0)
        return stringSize(domain.name) +
            nullableStringSize(domain.status) +
            (1 + if (domain.createdAtMillis != null) 8 else 0) +
            (1 + if (domain.expiresAtMillis != null) 8 else 0) +
            3 +
            4 + domain.nameservers.sumOf(::stringSize) +
            4 + domain.metadata.entries.sumOf { stringSize(it.key) + stringSize(it.value) }
    }

    private fun writeDomain(output: DataOutputStream, domain: RegistrarDomain) {
        writeString(output, domain.name, MAX_DOMAIN_NAME_BYTES)
        writeNullableString(output, domain.status, MAX_STATUS_BYTES)
        writeNullableLong(output, domain.createdAtMillis)
        writeNullableLong(output, domain.expiresAtMillis)
        writeTriState(output, domain.autoRenew)
        writeTriState(output, domain.locked)
        writeTriState(output, domain.privacyEnabled)
        require(domain.nameservers.size <= MAX_NAMESERVERS) { "Too many cached nameservers." }
        output.writeInt(domain.nameservers.size)
        domain.nameservers.forEach { writeString(output, it, MAX_NAMESERVER_BYTES) }
        writeMetadata(output, domain.metadata)
    }

    private fun readDomain(input: DataInputStream): RegistrarDomain = RegistrarDomain(
        name = readString(input, MAX_DOMAIN_NAME_BYTES),
        status = readNullableString(input, MAX_STATUS_BYTES),
        createdAtMillis = readNullableLong(input),
        expiresAtMillis = readNullableLong(input),
        autoRenew = readTriState(input),
        locked = readTriState(input),
        privacyEnabled = readTriState(input),
        nameservers = List(readCount(input, MAX_NAMESERVERS, "nameserver")) {
            readString(input, MAX_NAMESERVER_BYTES)
        },
        metadata = readMetadata(input),
    )

    private fun writeMetadata(output: DataOutputStream, metadata: Map<String, String>) {
        require(metadata.size <= MAX_METADATA_ENTRIES) { "Too many registrar metadata values." }
        output.writeInt(metadata.size)
        metadata.toSortedMap().forEach { (key, value) ->
            writeString(output, key, MAX_ID_BYTES)
            writeString(output, value, MAX_METADATA_BYTES)
        }
    }

    private fun readMetadata(input: DataInputStream): Map<String, String> {
        val count = readCount(input, MAX_METADATA_ENTRIES, "metadata")
        val result = LinkedHashMap<String, String>()
        repeat(count) {
            val key = readString(input, MAX_ID_BYTES)
            result[key] = readString(input, MAX_METADATA_BYTES)
        }
        return result
    }

    private fun writeSecret(output: DataOutputStream, secret: SecretValue) {
        val secretBytes = secret.utf8Bytes()
        try {
            writeBytes(output, secretBytes, MAX_SECRET_BYTES)
        } finally {
            secretBytes.fill(0)
        }
    }

    private fun readSecret(input: DataInputStream): SecretValue {
        val secretBytes = readBytes(input, MAX_SECRET_BYTES)
        return try {
            SecretValue.of(String(secretBytes, StandardCharsets.UTF_8))
        } finally {
            secretBytes.fill(0)
        }
    }

    private fun writeTriState(output: DataOutputStream, value: Boolean?) {
        output.writeByte(
            when (value) {
                null -> TRI_NULL
                false -> TRI_FALSE
                true -> TRI_TRUE
            },
        )
    }

    private fun readTriState(input: DataInputStream): Boolean? = when (input.readByte().toInt()) {
        TRI_NULL -> null
        TRI_FALSE -> false
        TRI_TRUE -> true
        else -> throw IllegalArgumentException("Invalid cached registrar flag.")
    }

    private fun writeString(output: DataOutputStream, value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeBytes(output, bytes, maximumBytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeNullableString(output: DataOutputStream, value: String?, maximumBytes: Int) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value, maximumBytes)
    }

    private fun writeNullableLong(output: DataOutputStream, value: Long?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeLong(value)
    }

    private fun writeBytes(output: DataOutputStream, value: ByteArray, maximumBytes: Int) {
        require(value.size <= maximumBytes) { "A registrar account field is too large." }
        output.writeInt(value.size)
        output.write(value)
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

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong() else null

    private fun readBytes(input: DataInputStream, maximumBytes: Int): ByteArray {
        val length = input.readInt()
        require(length in 0..maximumBytes && length <= input.available()) {
            "Invalid registrar account field length."
        }
        return ByteArray(length).also(input::readFully)
    }

    private fun readCount(input: DataInputStream, maximum: Int, label: String): Int =
        input.readInt().also { require(it in 0..maximum) { "Invalid registrar $label count." } }

    private const val TRI_NULL = 0
    private const val TRI_FALSE = 1
    private const val TRI_TRUE = 2
}

private class WipingRegistrarPayloadStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
