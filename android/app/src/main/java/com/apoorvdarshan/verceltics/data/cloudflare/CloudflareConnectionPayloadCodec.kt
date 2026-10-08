package com.apoorvdarshan.verceltics.data.cloudflare

import com.apoorvdarshan.verceltics.data.account.SecretValue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Plaintext exists only between this bounded codec and authenticated encryption.
 *
 * Version 2 adds the authentication mode and Global API Key login email to the profile plus
 * Worker routes and tags. Version 1 records (API tokens only) still decode.
 */
internal object CloudflareConnectionPayloadCodec {
    private const val VERSION = 2
    private const val LEGACY_TOKEN_VERSION = 1
    private const val MAX_ID_BYTES = 2_048
    private const val MAX_NAME_BYTES = 4_096
    private const val MAX_TEXT_BYTES = 32_768
    private const val MAX_TOKEN_BYTES = 65_536
    private const val MAX_WARNING_BYTES = 8_192
    private const val MAX_WARNINGS = 64
    private const val MAX_ACCOUNTS = 128
    private const val MAX_ZONES = 512
    private const val MAX_PAGES_PROJECTS = 256
    private const val MAX_WORKERS = 512
    private const val MAX_NESTED_VALUES = 256
    // Leaves room for the AES-GCM tag under AccountEnvelopeCodec's 512 KiB hard ceiling.
    private const val MAX_PLAINTEXT_BYTES = 448 * 1_024

    fun encode(connection: CloudflareStoredConnection): ByteArray {
        val bytes = WipingByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        return try {
            output.writeInt(VERSION)
            writeString(output, connection.account.providerId, MAX_ID_BYTES)
            writeProfile(output, connection.account.profile)
            val tokenBytes = connection.account.credential.secret().utf8Bytes()
            try {
                writeBytes(output, tokenBytes, MAX_TOKEN_BYTES)
            } finally {
                tokenBytes.fill(0)
            }
            output.writeLong(connection.account.createdAtMillis)
            output.writeLong(connection.account.updatedAtMillis)
            output.writeBoolean(connection.cachedSnapshot != null)
            connection.cachedSnapshot?.let { snapshot ->
                require(snapshot.profile.id == connection.account.profile.id)
                writeSnapshot(output, snapshot)
            }
            output.flush()
            bytes.toByteArray().also {
                require(it.size <= MAX_PLAINTEXT_BYTES) {
                    "The Cloudflare cache is too large to store safely."
                }
            }
        } finally {
            output.close()
        }
    }

    fun decode(bytes: ByteArray): CloudflareStoredConnection {
        require(bytes.size <= MAX_PLAINTEXT_BYTES) { "The Cloudflare payload is too large." }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == VERSION || version == LEGACY_TOKEN_VERSION) { "Unsupported Cloudflare payload version." }
            require(readString(input, MAX_ID_BYTES) == CloudflareAccount.PROVIDER_ID) {
                "The Cloudflare provider does not match its storage slot."
            }
            val profile = readProfile(input, version)
            val tokenBytes = readBytes(input, MAX_TOKEN_BYTES)
            val secret = try {
                SecretValue.of(String(tokenBytes, StandardCharsets.UTF_8))
            } finally {
                tokenBytes.fill(0)
            }
            val credential = when (profile.authMode) {
                CloudflareAuthMode.API_TOKEN -> CloudflareCredential.ApiToken(secret)
                CloudflareAuthMode.GLOBAL_API_KEY -> CloudflareCredential.GlobalApiKey(
                    requireNotNull(profile.email) { "The Global API Key record has no login email." },
                    secret,
                )
            }
            val account = CloudflareAccount(
                profile = profile,
                credential = credential,
                createdAtMillis = input.readLong(),
                updatedAtMillis = input.readLong(),
            )
            val snapshot = if (input.readBoolean()) readSnapshot(input, profile, version) else null
            require(input.available() == 0) { "Unexpected trailing Cloudflare account data." }
            return CloudflareStoredConnection(account, snapshot)
        }
    }

    private fun writeProfile(output: DataOutputStream, profile: CloudflareProfile) {
        writeString(output, profile.id, MAX_ID_BYTES)
        writeString(output, profile.displayName, MAX_NAME_BYTES)
        writeString(output, profile.tokenStatus, MAX_NAME_BYTES)
        writeString(output, profile.authMode.storageId, MAX_ID_BYTES)
        writeNullableString(output, profile.email, MAX_NAME_BYTES)
    }

    private fun readProfile(input: DataInputStream, version: Int): CloudflareProfile {
        val id = readString(input, MAX_ID_BYTES)
        val displayName = readString(input, MAX_NAME_BYTES)
        val tokenStatus = readString(input, MAX_NAME_BYTES)
        if (version == LEGACY_TOKEN_VERSION) return CloudflareProfile(id, displayName, tokenStatus)
        return CloudflareProfile(
            id = id,
            displayName = displayName,
            tokenStatus = tokenStatus,
            authMode = CloudflareAuthMode.fromStorageId(readString(input, MAX_ID_BYTES)),
            email = readNullableString(input, MAX_NAME_BYTES),
        )
    }

    private fun writeSnapshot(output: DataOutputStream, snapshot: CloudflareSnapshot) {
        output.writeLong(snapshot.fetchedAtMillis)
        output.writeBoolean(snapshot.accountsComplete)
        writeStrings(output, snapshot.warnings, MAX_WARNINGS, MAX_WARNING_BYTES)
        require(snapshot.accounts.size <= MAX_ACCOUNTS)
        output.writeInt(snapshot.accounts.size)
        snapshot.accounts.forEach { account ->
            writeString(output, account.id, MAX_ID_BYTES)
            writeString(output, account.name, MAX_NAME_BYTES)
            writeNullableString(output, account.type, MAX_NAME_BYTES)
            writeNullableString(output, account.createdOn, MAX_TEXT_BYTES)
        }
        writeNullableString(output, snapshot.selectedAccountId, MAX_ID_BYTES)
        output.writeBoolean(snapshot.selectedAccountInventory != null)
        snapshot.selectedAccountInventory?.let { writeInventory(output, it) }
    }

    private fun readSnapshot(
        input: DataInputStream,
        profile: CloudflareProfile,
        version: Int,
    ): CloudflareSnapshot {
        val fetchedAtMillis = input.readLong()
        val accountsComplete = input.readBoolean()
        val warnings = readStrings(input, MAX_WARNINGS, MAX_WARNING_BYTES)
        val accounts = List(readCount(input, MAX_ACCOUNTS, "account")) {
            CloudflareAccountSummary(
                id = readString(input, MAX_ID_BYTES),
                name = readString(input, MAX_NAME_BYTES),
                type = readNullableString(input, MAX_NAME_BYTES),
                createdOn = readNullableString(input, MAX_TEXT_BYTES),
            )
        }
        val selectedAccountId = readNullableString(input, MAX_ID_BYTES)
        val inventory = if (input.readBoolean()) readInventory(input, version) else null
        return CloudflareSnapshot(
            profile = profile,
            accounts = accounts,
            selectedAccountId = selectedAccountId,
            selectedAccountInventory = inventory,
            accountsComplete = accountsComplete,
            fetchedAtMillis = fetchedAtMillis,
            warnings = warnings,
        )
    }

    private fun writeInventory(output: DataOutputStream, inventory: CloudflareAccountInventory) {
        writeString(output, inventory.accountId, MAX_ID_BYTES)
        output.writeBoolean(inventory.zonesComplete)
        output.writeBoolean(inventory.pagesComplete)
        output.writeBoolean(inventory.workersComplete)
        writeStrings(output, inventory.warnings, MAX_WARNINGS, MAX_WARNING_BYTES)
        require(inventory.zones.size <= MAX_ZONES)
        output.writeInt(inventory.zones.size)
        inventory.zones.forEach { zone ->
            writeString(output, zone.id, MAX_ID_BYTES)
            writeString(output, zone.name, MAX_NAME_BYTES)
            writeNullableString(output, zone.status, MAX_NAME_BYTES)
            writeNullableString(output, zone.type, MAX_NAME_BYTES)
            writeNullableBoolean(output, zone.paused)
            writeNullableString(output, zone.accountId, MAX_ID_BYTES)
            writeNullableString(output, zone.accountName, MAX_NAME_BYTES)
            writeNullableString(output, zone.planName, MAX_NAME_BYTES)
        }
        require(inventory.pagesProjects.size <= MAX_PAGES_PROJECTS)
        output.writeInt(inventory.pagesProjects.size)
        inventory.pagesProjects.forEach { project ->
            writeString(output, project.id, MAX_ID_BYTES)
            writeString(output, project.name, MAX_NAME_BYTES)
            writeNullableString(output, project.subdomain, MAX_TEXT_BYTES)
            writeStrings(output, project.domains, MAX_NESTED_VALUES, MAX_TEXT_BYTES)
            writeNullableString(output, project.productionBranch, MAX_NAME_BYTES)
            writeNullableString(output, project.createdOn, MAX_TEXT_BYTES)
            writeNullableString(output, project.latestDeploymentStatus, MAX_NAME_BYTES)
        }
        require(inventory.workers.size <= MAX_WORKERS)
        output.writeInt(inventory.workers.size)
        inventory.workers.forEach { worker ->
            writeString(output, worker.id, MAX_ID_BYTES)
            writeNullableString(output, worker.createdOn, MAX_TEXT_BYTES)
            writeNullableString(output, worker.modifiedOn, MAX_TEXT_BYTES)
            writeNullableString(output, worker.compatibilityDate, MAX_TEXT_BYTES)
            writeStrings(output, worker.handlers, MAX_NESTED_VALUES, MAX_NAME_BYTES)
            writeNullableBoolean(output, worker.hasAssets)
            writeNullableBoolean(output, worker.hasModules)
            writeStrings(output, worker.routes, MAX_NESTED_VALUES, MAX_TEXT_BYTES)
            writeStrings(output, worker.tags, MAX_NESTED_VALUES, MAX_NAME_BYTES)
        }
    }

    private fun readInventory(input: DataInputStream, version: Int): CloudflareAccountInventory {
        val accountId = readString(input, MAX_ID_BYTES)
        val zonesComplete = input.readBoolean()
        val pagesComplete = input.readBoolean()
        val workersComplete = input.readBoolean()
        val warnings = readStrings(input, MAX_WARNINGS, MAX_WARNING_BYTES)
        val zones = List(readCount(input, MAX_ZONES, "zone")) {
            CloudflareZone(
                id = readString(input, MAX_ID_BYTES),
                name = readString(input, MAX_NAME_BYTES),
                status = readNullableString(input, MAX_NAME_BYTES),
                type = readNullableString(input, MAX_NAME_BYTES),
                paused = readNullableBoolean(input),
                accountId = readNullableString(input, MAX_ID_BYTES),
                accountName = readNullableString(input, MAX_NAME_BYTES),
                planName = readNullableString(input, MAX_NAME_BYTES),
            )
        }
        val pagesProjects = List(readCount(input, MAX_PAGES_PROJECTS, "Pages project")) {
            CloudflarePagesProject(
                id = readString(input, MAX_ID_BYTES),
                name = readString(input, MAX_NAME_BYTES),
                subdomain = readNullableString(input, MAX_TEXT_BYTES),
                domains = readStrings(input, MAX_NESTED_VALUES, MAX_TEXT_BYTES),
                productionBranch = readNullableString(input, MAX_NAME_BYTES),
                createdOn = readNullableString(input, MAX_TEXT_BYTES),
                latestDeploymentStatus = readNullableString(input, MAX_NAME_BYTES),
            )
        }
        val workers = List(readCount(input, MAX_WORKERS, "Worker")) {
            CloudflareWorkerScript(
                id = readString(input, MAX_ID_BYTES),
                createdOn = readNullableString(input, MAX_TEXT_BYTES),
                modifiedOn = readNullableString(input, MAX_TEXT_BYTES),
                compatibilityDate = readNullableString(input, MAX_TEXT_BYTES),
                handlers = readStrings(input, MAX_NESTED_VALUES, MAX_NAME_BYTES),
                hasAssets = readNullableBoolean(input),
                hasModules = readNullableBoolean(input),
                routes = if (version == LEGACY_TOKEN_VERSION) {
                    emptyList()
                } else {
                    readStrings(input, MAX_NESTED_VALUES, MAX_TEXT_BYTES)
                },
                tags = if (version == LEGACY_TOKEN_VERSION) emptyList() else readStrings(input, MAX_NESTED_VALUES, MAX_NAME_BYTES),
            )
        }
        return CloudflareAccountInventory(
            accountId = accountId,
            zones = zones,
            pagesProjects = pagesProjects,
            workers = workers,
            zonesComplete = zonesComplete,
            pagesComplete = pagesComplete,
            workersComplete = workersComplete,
            warnings = warnings,
        )
    }

    private fun writeStrings(
        output: DataOutputStream,
        values: List<String>,
        maximumItems: Int,
        maximumBytes: Int,
    ) {
        require(values.size <= maximumItems)
        output.writeInt(values.size)
        values.forEach { writeString(output, it, maximumBytes) }
    }

    private fun readStrings(
        input: DataInputStream,
        maximumItems: Int,
        maximumBytes: Int,
    ): List<String> = List(readCount(input, maximumItems, "value")) {
        readString(input, maximumBytes)
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

    private fun writeNullableBoolean(output: DataOutputStream, value: Boolean?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeBoolean(value)
    }

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray, maximumBytes: Int) {
        require(bytes.size <= maximumBytes) { "A Cloudflare account field is too large." }
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

    private fun readNullableBoolean(input: DataInputStream): Boolean? =
        if (input.readBoolean()) input.readBoolean() else null

    private fun readBytes(input: DataInputStream, maximumBytes: Int): ByteArray {
        val size = input.readInt()
        require(size in 0..maximumBytes && size <= input.available()) {
            "Invalid Cloudflare account field length."
        }
        return ByteArray(size).also(input::readFully)
    }

    private fun readCount(input: DataInputStream, maximum: Int, label: String): Int =
        input.readInt().also { require(it in 0..maximum) { "Invalid Cloudflare $label count." } }
}

private fun CloudflareCredential.secret(): SecretValue = when (this) {
    is CloudflareCredential.ApiToken -> token
    is CloudflareCredential.GlobalApiKey -> key
}

private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
    override fun close() {
        buf.fill(0)
        reset()
        super.close()
    }
}
