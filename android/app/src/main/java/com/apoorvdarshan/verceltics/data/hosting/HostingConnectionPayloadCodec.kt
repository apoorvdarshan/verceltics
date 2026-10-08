package com.apoorvdarshan.verceltics.data.hosting

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/** Versioned binary payload. Plaintext exists only between this codec and authenticated encryption. */
internal object HostingConnectionPayloadCodec {
    private const val PAYLOAD_VERSION = 1
    private const val MAX_ID_BYTES = 2_048
    private const val MAX_NAME_BYTES = 4_096
    private const val MAX_TEXT_BYTES = 32_768
    private const val MAX_SECRET_BYTES = 65_536
    private const val MAX_WARNINGS = 32
    private const val MAX_RESOURCES = 256
    const val MAX_PLAINTEXT_BYTES: Int = 448 * 1_024

    fun encode(connection: HostingStoredConnection): ByteArray =
        encodeOrNull(connection) ?: throw IllegalArgumentException("The hosting cache is too large to store safely.")

    /** Returns null when the payload would exceed [MAX_PLAINTEXT_BYTES], so callers can shrink the cache. */
    fun encodeOrNull(connection: HostingStoredConnection): ByteArray? {
        val bytes = WipingByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            val account = connection.account
            output.writeInt(PAYLOAD_VERSION)
            writeString(output, account.provider.id, MAX_ID_BYTES)
            writeProfile(output, account.profile)
            writeCredentials(output, account.credentials)
            output.writeLong(account.createdAtMillis)
            output.writeLong(account.updatedAtMillis)
            output.writeBoolean(connection.cachedSnapshot != null)
            connection.cachedSnapshot?.let { snapshot ->
                writeProfile(output, snapshot.profile)
                output.writeLong(snapshot.fetchedAtMillis)
                require(snapshot.warnings.size <= MAX_WARNINGS) { "Too many cached hosting warnings." }
                output.writeInt(snapshot.warnings.size)
                snapshot.warnings.forEach { writeString(output, it, MAX_TEXT_BYTES) }
                require(snapshot.resources.size <= MAX_RESOURCES) { "Too many cached hosting resources." }
                output.writeInt(snapshot.resources.size)
                snapshot.resources.forEach { writeResource(output, it) }
            }
            output.flush()
            val encoded = bytes.toByteArray()
            if (encoded.size > MAX_PLAINTEXT_BYTES) {
                encoded.fill(0)
                null
            } else {
                encoded
            }
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray, expectedProvider: HostingProvider): HostingStoredConnection {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "The hosting payload is too large." }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == PAYLOAD_VERSION) { "Unsupported hosting payload version." }
            val provider = HostingProvider.fromId(readString(input, MAX_ID_BYTES))
            require(provider == expectedProvider) { "The hosting provider does not match its storage slot." }
            val profile = readProfile(input)
            val credentials = readCredentials(input, expectedProvider)
            val account = HostingAccount(
                profile = profile,
                credentials = credentials,
                createdAtMillis = input.readLong(),
                updatedAtMillis = input.readLong(),
            )
            val snapshot = if (input.readBoolean()) {
                val snapshotProfile = readProfile(input)
                val fetchedAtMillis = input.readLong()
                val warnings = List(readCount(input, MAX_WARNINGS)) { readString(input, MAX_TEXT_BYTES) }
                val resources = List(readCount(input, MAX_RESOURCES)) { readResource(input) }
                HostingSnapshot(expectedProvider, snapshotProfile, resources, fetchedAtMillis, warnings)
            } else {
                null
            }
            require(input.available() == 0) { "Unexpected trailing hosting account data." }
            return HostingStoredConnection(account, snapshot)
        }
    }

    private fun writeProfile(output: DataOutputStream, profile: HostingProfile) {
        writeString(output, profile.id, MAX_ID_BYTES)
        writeString(output, profile.name, MAX_NAME_BYTES)
        writeNullableString(output, profile.email, MAX_TEXT_BYTES)
        writeNullableString(output, profile.avatarUrl, MAX_TEXT_BYTES)
    }

    private fun readProfile(input: DataInputStream): HostingProfile = HostingProfile(
        id = readString(input, MAX_ID_BYTES),
        name = readString(input, MAX_NAME_BYTES),
        email = readNullableString(input, MAX_TEXT_BYTES),
        avatarUrl = readNullableString(input, MAX_TEXT_BYTES),
    )

    private fun writeCredentials(output: DataOutputStream, credentials: HostingCredentials) {
        when (credentials) {
            is HostingCredentials.Railway -> {
                writeSecret(output, credentials.token)
                writeString(output, credentials.tokenType.wireValue, MAX_ID_BYTES)
            }
            is HostingCredentials.Render -> writeSecret(output, credentials.apiKey)
            is HostingCredentials.DigitalOcean -> writeSecret(output, credentials.token)
            is HostingCredentials.Heroku -> writeSecret(output, credentials.token)
            is HostingCredentials.Fly -> {
                writeSecret(output, credentials.token)
                writeString(output, credentials.organization, MAX_ID_BYTES)
            }
            is HostingCredentials.Firebase -> writeString(output, credentials.projectId, MAX_ID_BYTES)
            is HostingCredentials.AwsAmplify -> {
                writeString(output, credentials.accessKeyId, MAX_ID_BYTES)
                writeSecret(output, credentials.secretAccessKey)
                writeString(output, credentials.region, MAX_ID_BYTES)
                output.writeBoolean(credentials.sessionToken != null)
                credentials.sessionToken?.let { writeSecret(output, it) }
            }
        }
    }

    private fun readCredentials(input: DataInputStream, provider: HostingProvider): HostingCredentials =
        when (provider) {
            HostingProvider.RAILWAY -> HostingCredentials.Railway(
                token = readSecret(input),
                tokenType = RailwayTokenType.fromWireValue(readString(input, MAX_ID_BYTES)),
            )
            HostingProvider.RENDER -> HostingCredentials.Render(readSecret(input))
            HostingProvider.DIGITAL_OCEAN -> HostingCredentials.DigitalOcean(readSecret(input))
            HostingProvider.HEROKU -> HostingCredentials.Heroku(readSecret(input))
            HostingProvider.FLY -> HostingCredentials.Fly(readSecret(input), readString(input, MAX_ID_BYTES))
            HostingProvider.FIREBASE -> HostingCredentials.Firebase(readString(input, MAX_ID_BYTES))
            HostingProvider.AWS_AMPLIFY -> HostingCredentials.AwsAmplify(
                accessKeyId = readString(input, MAX_ID_BYTES),
                secretAccessKey = readSecret(input),
                region = readString(input, MAX_ID_BYTES),
                sessionToken = if (input.readBoolean()) readSecret(input) else null,
            )
        }

    private fun writeResource(output: DataOutputStream, resource: HostingResource) {
        writeString(output, resource.id, MAX_ID_BYTES)
        writeString(output, resource.name, MAX_NAME_BYTES)
        writeNullableString(output, resource.subtitle, MAX_TEXT_BYTES)
        writeNullableString(output, resource.url, MAX_TEXT_BYTES)
        writeNullableString(output, resource.status, MAX_TEXT_BYTES)
        writeNullableString(output, resource.region, MAX_TEXT_BYTES)
        writeNullableString(output, resource.kind, MAX_TEXT_BYTES)
        output.writeBoolean(resource.updatedAtMillis != null)
        resource.updatedAtMillis?.let(output::writeLong)
        require(resource.metadata.size <= MAX_HOSTING_METADATA_ENTRIES) { "Too much resource metadata." }
        output.writeInt(resource.metadata.size)
        resource.metadata.forEach { (key, value) ->
            writeString(output, key, MAX_ID_BYTES)
            writeString(output, value, MAX_TEXT_BYTES)
        }
    }

    private fun readResource(input: DataInputStream): HostingResource = HostingResource(
        id = readString(input, MAX_ID_BYTES),
        name = readString(input, MAX_NAME_BYTES),
        subtitle = readNullableString(input, MAX_TEXT_BYTES),
        url = readNullableString(input, MAX_TEXT_BYTES),
        status = readNullableString(input, MAX_TEXT_BYTES),
        region = readNullableString(input, MAX_TEXT_BYTES),
        kind = readNullableString(input, MAX_TEXT_BYTES),
        updatedAtMillis = if (input.readBoolean()) input.readLong() else null,
        metadata = buildMap {
            repeat(readCount(input, MAX_HOSTING_METADATA_ENTRIES)) {
                put(readString(input, MAX_ID_BYTES), readString(input, MAX_TEXT_BYTES))
            }
        },
    )

    private fun writeSecret(output: DataOutputStream, secret: SecretValue) {
        val bytes = secret.use { it.toByteArray(StandardCharsets.UTF_8) }
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

    private fun writeBytes(output: DataOutputStream, value: ByteArray, maximumBytes: Int) {
        require(value.size <= maximumBytes) { "A hosting account field is too large." }
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

    private fun readBytes(input: DataInputStream, maximumBytes: Int): ByteArray {
        val length = input.readInt()
        require(length in 0..maximumBytes && length <= input.available()) { "Invalid hosting account field length." }
        return ByteArray(length).also(input::readFully)
    }

    private fun readCount(input: DataInputStream, maximum: Int): Int =
        input.readInt().also { require(it in 0..maximum) { "Invalid hosting cache count." } }
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
