package com.apoorvdarshan.verceltics.data.cloudflare

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

interface CloudflareJsonParser {
    fun parseTokenVerification(bytes: ByteArray): CloudflareTokenVerification

    fun parseUser(bytes: ByteArray): CloudflareUserIdentity

    fun parseAccountsPage(bytes: ByteArray): CloudflarePage<CloudflareAccountSummary>

    fun parseZonesPage(bytes: ByteArray): CloudflarePage<CloudflareZone>

    fun parsePagesProjectsPage(bytes: ByteArray): CloudflarePage<CloudflarePagesProject>

    fun parseWorkerScripts(bytes: ByteArray): List<CloudflareWorkerScript>

    fun parseErrorCode(bytes: ByteArray): String?
}

class CloudflareResponseFormatException(message: String) : RuntimeException(message)

class CloudflareEnvelopeRejectedException internal constructor(
    val errorCode: String?,
) : RuntimeException("Cloudflare reported an unsuccessful response.") {
    override fun toString(): String =
        "CloudflareEnvelopeRejectedException(errorCode=${if (errorCode == null) "none" else "<redacted>"})"
}

/** Strict bounded streaming parser for the Cloudflare v4 response envelope. */
class AndroidCloudflareJsonParser : CloudflareJsonParser {
    override fun parseTokenVerification(bytes: ByteArray): CloudflareTokenVerification =
        parseEnvelope(bytes, ::readTokenVerification)

    override fun parseUser(bytes: ByteArray): CloudflareUserIdentity = parseEnvelope(bytes, ::readUser)

    override fun parseAccountsPage(bytes: ByteArray): CloudflarePage<CloudflareAccountSummary> =
        parseCollectionEnvelope(bytes, ::readAccount)

    override fun parseZonesPage(bytes: ByteArray): CloudflarePage<CloudflareZone> =
        parseCollectionEnvelope(bytes, ::readZone)

    override fun parsePagesProjectsPage(bytes: ByteArray): CloudflarePage<CloudflarePagesProject> =
        parseCollectionEnvelope(bytes, ::readPagesProject)

    override fun parseWorkerScripts(bytes: ByteArray): List<CloudflareWorkerScript> =
        parseCollectionEnvelope(bytes, ::readWorkerScript).items

    override fun parseErrorCode(bytes: ByteArray): String? = try {
        parse(bytes) { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                return@parse null
            }
            var code: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "errors" -> if (code == null) code = readFirstIssueCode(reader) else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            code
        }
    } catch (_: CloudflareResponseFormatException) {
        null
    }

    private fun <T> parseEnvelope(bytes: ByteArray, readResult: (JsonReader) -> T): T = parse(bytes) { reader ->
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid response envelope.")
        var success: Boolean? = null
        var result: T? = null
        var resultSeen = false
        var errorCode: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "success" -> success = reader.optionalBoolean()
                "result" -> {
                    resultSeen = true
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        result = readResult(reader)
                    }
                }
                "errors" -> errorCode = readFirstIssueCode(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (success != true) throw CloudflareEnvelopeRejectedException(errorCode)
        if (!resultSeen || result == null) {
            throw CloudflareResponseFormatException("Cloudflare returned no response result.")
        }
        result
    }

    private fun <T> parseCollectionEnvelope(
        bytes: ByteArray,
        readItem: (JsonReader) -> T,
    ): CloudflarePage<T> = parse(bytes) { reader ->
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid response envelope.")
        var success: Boolean? = null
        var items: List<T>? = null
        var page: Int? = null
        var totalPages: Int? = null
        var errorCode: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "success" -> success = reader.optionalBoolean()
                "result" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                    } else {
                        expect(reader, JsonToken.BEGIN_ARRAY, "Cloudflare returned an invalid collection.")
                        val parsed = mutableListOf<T>()
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (parsed.size >= MAX_ITEMS_PER_RESPONSE) {
                                throw CloudflareResponseFormatException(
                                    "Cloudflare returned too many items in one response.",
                                )
                            }
                            parsed += readItem(reader)
                        }
                        reader.endArray()
                        items = parsed
                    }
                }
                "result_info" -> {
                    val info = readResultInfo(reader)
                    page = info.first
                    totalPages = info.second
                }
                "errors" -> errorCode = readFirstIssueCode(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (success != true) throw CloudflareEnvelopeRejectedException(errorCode)
        CloudflarePage(
            items = items ?: throw CloudflareResponseFormatException(
                "Cloudflare returned no collection result.",
            ),
            page = page,
            totalPages = totalPages,
        )
    }

    private fun readTokenVerification(reader: JsonReader): CloudflareTokenVerification {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid token verification.")
        var id: String? = null
        var status: String? = null
        var notBefore: String? = null
        var expiresOn: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "status" -> status = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                "not_before" -> notBefore = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                "expires_on" -> expiresOn = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflareTokenVerification(
            id = id,
            status = status ?: throw CloudflareResponseFormatException(
                "Cloudflare omitted the token status.",
            ),
            notBefore = notBefore,
            expiresOn = expiresOn,
        )
    }

    private fun readUser(reader: JsonReader): CloudflareUserIdentity {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid user profile.")
        var id: String? = null
        var email: String? = null
        var firstName: String? = null
        var lastName: String? = null
        var suspended: Boolean? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "email" -> email = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "first_name" -> firstName = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "last_name" -> lastName = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "suspended" -> suspended = reader.optionalBoolean()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflareUserIdentity(
            id = id ?: missing("user id"),
            email = email ?: missing("user email"),
            firstName = firstName,
            lastName = lastName,
            suspended = suspended,
        )
    }

    private fun readAccount(reader: JsonReader): CloudflareAccountSummary {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid account record.")
        var id: String? = null
        var name: String? = null
        var type: String? = null
        var createdOn: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "name" -> name = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "type" -> type = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                "created_on" -> createdOn = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflareAccountSummary(
            id = id ?: missing("account id"),
            name = name ?: missing("account name"),
            type = type,
            createdOn = createdOn,
        )
    }

    private fun readZone(reader: JsonReader): CloudflareZone {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid zone record.")
        var id: String? = null
        var name: String? = null
        var status: String? = null
        var type: String? = null
        var paused: Boolean? = null
        var accountId: String? = null
        var accountName: String? = null
        var planName: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "name" -> name = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "status" -> status = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                "type" -> type = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                "paused" -> paused = reader.optionalBoolean()
                "account" -> {
                    val pair = readIdNameReference(reader)
                    accountId = pair.first
                    accountName = pair.second
                }
                "plan" -> planName = readNamedObject(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflareZone(
            id = id ?: missing("zone id"),
            name = name ?: missing("zone name"),
            status = status,
            type = type,
            paused = paused,
            accountId = accountId,
            accountName = accountName,
            planName = planName,
        )
    }

    private fun readPagesProject(reader: JsonReader): CloudflarePagesProject {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid Pages project.")
        var id: String? = null
        var name: String? = null
        var subdomain: String? = null
        var domains = emptyList<String>()
        var productionBranch: String? = null
        var createdOn: String? = null
        var latestDeploymentStatus: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "name" -> name = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "subdomain" -> subdomain = reader.optionalString(CF_MAX_URL_CHARACTERS)
                "domains" -> domains = reader.stringArray(CF_MAX_NESTED_ITEMS, CF_MAX_DOMAIN_CHARACTERS)
                "production_branch" -> productionBranch = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                "created_on" -> createdOn = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                "latest_deployment" -> latestDeploymentStatus = readDeploymentStatus(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflarePagesProject(
            id = id ?: missing("Pages project id"),
            name = name ?: missing("Pages project name"),
            subdomain = subdomain,
            domains = domains,
            productionBranch = productionBranch,
            createdOn = createdOn,
            latestDeploymentStatus = latestDeploymentStatus,
        )
    }

    private fun readWorkerScript(reader: JsonReader): CloudflareWorkerScript {
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned an invalid Worker script.")
        var id: String? = null
        var createdOn: String? = null
        var modifiedOn: String? = null
        var compatibilityDate: String? = null
        var handlers = emptyList<String>()
        var hasAssets: Boolean? = null
        var hasModules: Boolean? = null
        var routes = emptyList<String>()
        var tags = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "created_on" -> createdOn = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                "modified_on" -> modifiedOn = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                "compatibility_date" -> compatibilityDate = reader.optionalString(CF_MAX_DATE_CHARACTERS)
                "handlers" -> handlers = reader.stringArray(CF_MAX_NESTED_ITEMS, CF_MAX_NAME_CHARACTERS)
                "has_assets" -> hasAssets = reader.optionalBoolean()
                "has_modules" -> hasModules = reader.optionalBoolean()
                "routes" -> routes = readRoutePatterns(reader)
                "tags" -> tags = reader.stringArray(CF_MAX_NESTED_ITEMS, CF_MAX_NAME_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return CloudflareWorkerScript(
            id = id ?: missing("Worker script id"),
            createdOn = createdOn,
            modifiedOn = modifiedOn,
            compatibilityDate = compatibilityDate,
            handlers = handlers,
            hasAssets = hasAssets,
            hasModules = hasModules,
            routes = routes,
            tags = tags,
        )
    }

    /** `routes: [{ "id", "pattern", "script" }]` (or null) → the route patterns iOS searches. */
    private fun readRoutePatterns(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val patterns = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (patterns.size >= CF_MAX_NESTED_ITEMS) {
                throw CloudflareResponseFormatException("Cloudflare returned too many Worker routes.")
            }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "pattern" -> reader.optionalString(CF_MAX_DOMAIN_CHARACTERS)?.let(patterns::add)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                JsonToken.STRING -> reader.optionalString(CF_MAX_DOMAIN_CHARACTERS)?.let(patterns::add)
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return patterns
    }

    private fun readResultInfo(reader: JsonReader): Pair<Int?, Int?> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null to null
        }
        expect(reader, JsonToken.BEGIN_OBJECT, "Cloudflare returned invalid pagination metadata.")
        var page: Int? = null
        var totalPages: Int? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "page" -> page = reader.optionalInt()
                "total_pages" -> totalPages = reader.optionalInt()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return page to totalPages
    }

    private fun readIdNameReference(reader: JsonReader): Pair<String?, String?> {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null to null
        }
        var id: String? = null
        var name: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.optionalString(CF_MAX_ID_CHARACTERS)
                "name" -> name = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return id to name
    }

    private fun readNamedObject(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var name: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.optionalString(CF_MAX_NAME_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return name
    }

    private fun readDeploymentStatus(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var status: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "status" -> status = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                "latest_stage" -> if (status == null) status = readStatusObject(reader) else reader.skipValue()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return status
    }

    private fun readStatusObject(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var status: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "status" -> status = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return status
    }

    private fun readFirstIssueCode(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }
        var code: String? = null
        var issueCount = 0
        reader.beginArray()
        while (reader.hasNext()) {
            issueCount += 1
            if (issueCount > MAX_ISSUES) {
                throw CloudflareResponseFormatException("Cloudflare returned too many error records.")
            }
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "code" -> if (code == null) {
                        code = reader.optionalString(CF_MAX_STATUS_CHARACTERS)
                    } else {
                        reader.skipValue()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
        return code
    }

    private inline fun <T> parse(bytes: ByteArray, block: (JsonReader) -> T): T {
        try {
            JsonReader(
                InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8),
            ).use { reader ->
                reader.isLenient = false
                val value = block(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw CloudflareResponseFormatException("Cloudflare returned trailing response data.")
                }
                return value
            }
        } catch (error: CloudflareEnvelopeRejectedException) {
            throw error
        } catch (error: CloudflareResponseFormatException) {
            throw error
        } catch (_: Exception) {
            throw CloudflareResponseFormatException("Cloudflare returned malformed JSON.")
        }
    }

    private fun JsonReader.optionalString(maximumCharacters: Int): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString().trim().takeIf { it.isNotEmpty() }?.also {
            if (it.length > maximumCharacters || '\u0000' in it) {
                throw CloudflareResponseFormatException("Cloudflare returned an oversized text field.")
            }
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.optionalBoolean(): Boolean? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.BOOLEAN -> nextBoolean()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.optionalInt(): Int? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.NUMBER, JsonToken.STRING -> nextString().toIntOrNull()
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.stringArray(maximumItems: Int, maximumCharacters: Int): List<String> {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return emptyList()
        }
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }
        val values = mutableListOf<String>()
        beginArray()
        while (hasNext()) {
            if (values.size >= maximumItems) {
                throw CloudflareResponseFormatException("Cloudflare returned too many nested values.")
            }
            optionalString(maximumCharacters)?.let(values::add)
        }
        endArray()
        return values
    }

    private fun expect(reader: JsonReader, token: JsonToken, message: String) {
        if (reader.peek() != token) throw CloudflareResponseFormatException(message)
    }

    private fun missing(label: String): Nothing =
        throw CloudflareResponseFormatException("Cloudflare omitted the $label.")

    companion object {
        private const val MAX_ITEMS_PER_RESPONSE = 1_000
        private const val MAX_ISSUES = 256
    }
}
