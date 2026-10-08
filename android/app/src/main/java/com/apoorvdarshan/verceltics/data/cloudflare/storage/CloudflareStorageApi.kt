package com.apoorvdarshan.verceltics.data.cloudflare.storage

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareEnvelope
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflarePaginationGuard
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestResponse
import com.apoorvdarshan.verceltics.data.cloudflare.operations.isCloudflareOptionalProductUnavailable
import com.apoorvdarshan.verceltics.data.cloudflare.operations.requireCloudflareConfirmation
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/**
 * Port of iOS `CloudflareStorageAPI.swift` (D1, Workers KV and R2 buckets) plus typed R2 object
 * operations, which iOS reaches through API-explorer presets.
 *
 * Every mutation requires a [CloudflareMutationConfirmation] naming the same resource iOS confirms
 * against, and nothing is sent when it does not match.
 */
class CloudflareStorageApi(private val client: CloudflareRestClient) {

    // MARK: D1

    suspend fun fetchD1Databases(accountId: String): List<CloudflareD1Database> {
        val guard = CloudflarePaginationGuard()
        val databases = mutableListOf<CloudflareD1Database>()
        var page = 1
        while (true) {
            val response = client.execute(
                CloudflareRestRequest(
                    CloudflareHttpMethod.GET,
                    d1Path(accountId),
                    query = listOf("page" to page.toString(), "per_page" to "1000"),
                ),
            )
            val envelope = storageEnvelope(response)
            val batch = envelope.result?.arrayValue.orEmpty().map(CloudflareD1Database::from)
            guard.record(batch.size, response.bodyHash())
            databases += batch
            if (batch.isEmpty()) break
            val totalCount = envelope.resultInfo?.totalCount
            page += when {
                totalCount != null && databases.size < totalCount -> 1
                batch.size == 1_000 -> 1
                else -> break
            }
        }
        return databases
    }

    suspend fun fetchD1Database(accountId: String, databaseId: String): CloudflareD1Database =
        CloudflareD1Database.from(storageResult(CloudflareRestRequest(CloudflareHttpMethod.GET, d1Path(accountId) + databaseId)))

    suspend fun createD1Database(
        accountId: String,
        input: CloudflareD1CreateInput,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareD1Database {
        val name = input.name.trim()
        if (name.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a D1 database name.")
        requireCloudflareConfirmation(confirmation, name)
        val request = CloudflareRestRequest.json(CloudflareHttpMethod.POST, d1Path(accountId), input.copy(name = name).toJson())
        return CloudflareD1Database.from(storageResult(request))
    }

    suspend fun deleteD1Database(accountId: String, databaseId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, databaseId)
        validateStorageMutation(client.execute(CloudflareRestRequest(CloudflareHttpMethod.DELETE, d1Path(accountId) + databaseId)))
    }

    /** Runs SQL against a live database. D1 accepts reads and writes, so this is always confirmed. */
    suspend fun queryD1Database(
        accountId: String,
        databaseId: String,
        sql: String,
        params: List<ProviderJsonValue> = emptyList(),
        confirmation: CloudflareMutationConfirmation,
    ): List<CloudflareD1QueryResult> {
        val statement = sql.trim()
        if (statement.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a SQL statement.")
        requireCloudflareConfirmation(confirmation, databaseId)
        val body = ProviderJsonValue.Obj(linkedMapOf("sql" to ProviderJsonValue.Str(statement), "params" to ProviderJsonValue.Arr(params)))
        val result = storageResult(
            CloudflareRestRequest.json(CloudflareHttpMethod.POST, d1Path(accountId) + listOf(databaseId, "query"), body),
        )
        return result.arrayValue.orEmpty().map(CloudflareD1QueryResult::from)
    }

    // MARK: Workers KV

    suspend fun fetchKVNamespaces(accountId: String): List<CloudflareKVNamespace> {
        val guard = CloudflarePaginationGuard()
        val namespaces = mutableListOf<CloudflareKVNamespace>()
        var page = 1
        while (true) {
            val response = client.execute(
                CloudflareRestRequest(
                    CloudflareHttpMethod.GET,
                    kvNamespacesPath(accountId),
                    query = listOf(
                        "page" to page.toString(),
                        "per_page" to "1000",
                        "order" to "title",
                        "direction" to "asc",
                    ),
                ),
            )
            val envelope = storageEnvelope(response)
            val batch = envelope.result?.arrayValue.orEmpty().map(CloudflareKVNamespace::from)
            guard.record(batch.size, response.bodyHash())
            namespaces += batch
            if (batch.isEmpty()) break
            val totalCount = envelope.resultInfo?.totalCount
            page += when {
                totalCount != null && namespaces.size < totalCount -> 1
                batch.size == 1_000 -> 1
                else -> break
            }
        }
        return namespaces
    }

    suspend fun createKVNamespace(accountId: String, title: String, confirmation: CloudflareMutationConfirmation): CloudflareKVNamespace {
        val normalized = title.trim()
        if (normalized.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a KV namespace title.")
        requireCloudflareConfirmation(confirmation, normalized)
        val request = CloudflareRestRequest.json(CloudflareHttpMethod.POST, kvNamespacesPath(accountId), jsonOf("title" to normalized))
        return CloudflareKVNamespace.from(storageResult(request))
    }

    suspend fun renameKVNamespace(
        accountId: String,
        namespaceId: String,
        title: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareKVNamespace {
        val normalized = title.trim()
        if (normalized.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a KV namespace title.")
        requireCloudflareConfirmation(confirmation, namespaceId)
        val request = CloudflareRestRequest.json(
            CloudflareHttpMethod.PUT,
            kvNamespacesPath(accountId) + namespaceId,
            jsonOf("title" to normalized),
        )
        return renamedNamespace(storageEnvelope(client.execute(request)), namespaceId, normalized)
    }

    suspend fun deleteKVNamespace(accountId: String, namespaceId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, namespaceId)
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.DELETE,
            kvNamespacesPath(accountId) + namespaceId,
            body = EMPTY_JSON_OBJECT,
        )
        validateStorageMutation(client.execute(request))
    }

    /** Lists every key (cursor pagination with the iOS repeated-cursor guard). */
    suspend fun fetchKVKeys(accountId: String, namespaceId: String, prefix: String? = null): List<CloudflareKVKey> {
        val guard = CloudflarePaginationGuard()
        val seenCursors = HashSet<String>()
        val keys = mutableListOf<CloudflareKVKey>()
        var cursor: String? = null
        do {
            val query = buildList {
                add("limit" to "1000")
                prefix?.takeIf(String::isNotEmpty)?.let { add("prefix" to it) }
                cursor?.takeIf(String::isNotEmpty)?.let { add("cursor" to it) }
            }
            val response = client.execute(
                CloudflareRestRequest(CloudflareHttpMethod.GET, kvNamespacesPath(accountId) + listOf(namespaceId, "keys"), query = query),
            )
            val envelope = storageEnvelope(response)
            val batch = envelope.result?.arrayValue.orEmpty().mapNotNull(CloudflareKVKey::from)
            guard.record(batch.size, response.bodyHash())
            keys += batch
            cursor = envelope.resultInfo?.cursor
            val next = cursor
            if (!next.isNullOrEmpty() && !seenCursors.add(next)) {
                throw CloudflareOperationException.invalidRequest("Cloudflare repeated a KV cursor, so loading stopped safely.")
            }
        } while (!cursor.isNullOrEmpty())
        return keys
    }

    suspend fun readKVValue(accountId: String, namespaceId: String, key: String): CloudflareKVValue {
        val response = client.execute(
            CloudflareRestRequest(CloudflareHttpMethod.GET, kvValuePath(accountId, namespaceId, key), accept = "*/*"),
        )
        CloudflareRestClient.throwForHttpFailure(response)
        return CloudflareKVValue(response.bodyBytes(), response.header("Content-Type"), response.header("Expiration"))
    }

    suspend fun writeKVValue(
        accountId: String,
        namespaceId: String,
        key: String,
        data: ByteArray,
        contentType: String = "application/octet-stream",
        expirationTtl: Int? = null,
        confirmation: CloudflareMutationConfirmation,
    ) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a KV key.")
        if (expirationTtl != null && expirationTtl < 60) {
            throw CloudflareOperationException.invalidRequest("KV expiration TTL must be at least 60 seconds.")
        }
        if (data.size > MAXIMUM_KV_VALUE_BYTES) {
            throw CloudflareOperationException.invalidRequest("KV values must be 25 MB or smaller.")
        }
        requireCloudflareConfirmation(confirmation, normalizedKey)
        val request = CloudflareRestRequest(
            method = CloudflareHttpMethod.PUT,
            pathSegments = kvValuePath(accountId, namespaceId, normalizedKey),
            query = listOfNotNull(expirationTtl?.let { "expiration_ttl" to it.toString() }),
            body = data,
            contentType = safeContentType(contentType),
        )
        validateStorageMutation(client.execute(request))
    }

    suspend fun deleteKVValue(accountId: String, namespaceId: String, key: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, key)
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.DELETE,
            kvValuePath(accountId, namespaceId, key),
            body = EMPTY_JSON_OBJECT,
        )
        validateStorageMutation(client.execute(request))
    }

    // MARK: R2 buckets

    /** Default buckets plus jurisdictional scopes; scopes the account cannot use are skipped (iOS). */
    suspend fun fetchR2Buckets(accountId: String): List<CloudflareR2Bucket> {
        val buckets = fetchR2Buckets(accountId, jurisdiction = null).toMutableList()
        for (jurisdiction in CloudflareR2Jurisdictions.SCOPED) {
            try {
                buckets += fetchR2Buckets(accountId, jurisdiction)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCloudflareOptionalProductUnavailable(error)) throw error
            }
        }
        return buckets.associateBy(CloudflareR2Bucket::id).values.toList()
    }

    suspend fun fetchR2Bucket(accountId: String, bucketName: String, jurisdiction: String? = null): CloudflareR2Bucket {
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.GET,
            r2BucketPath(accountId, bucketName),
            headers = r2Headers(jurisdiction),
        )
        return CloudflareR2Bucket.from(storageResult(request)).scoped(jurisdiction)
    }

    suspend fun createR2Bucket(
        accountId: String,
        input: CloudflareR2CreateInput,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareR2Bucket {
        val name = input.name.trim()
        if (!CloudflareR2Jurisdictions.isValidBucketName(name)) {
            throw CloudflareOperationException.invalidRequest(
                "R2 bucket names must be 3–63 lowercase letters, numbers, or hyphens, and cannot start or end with a hyphen.",
            )
        }
        requireCloudflareConfirmation(confirmation, name)
        val request = CloudflareRestRequest.json(
            CloudflareHttpMethod.POST,
            listOf("accounts", accountId, "r2", "buckets"),
            input.copy(name = name).toJson(),
            headers = r2Headers(input.jurisdiction),
        )
        return CloudflareR2Bucket.from(storageResult(request)).scoped(input.jurisdiction)
    }

    suspend fun deleteR2Bucket(
        accountId: String,
        bucketName: String,
        jurisdiction: String? = null,
        confirmation: CloudflareMutationConfirmation,
    ) {
        requireCloudflareConfirmation(confirmation, bucketName)
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.DELETE,
            r2BucketPath(accountId, bucketName),
            headers = r2Headers(jurisdiction),
        )
        validateStorageMutation(client.execute(request))
    }

    /** Reads a bucket configuration document (`cors`, `domains/custom` or `lifecycle`) as JSON. */
    suspend fun fetchR2BucketConfiguration(
        accountId: String,
        bucketName: String,
        jurisdiction: String?,
        configuration: CloudflareR2Configuration,
    ): ProviderJsonValue {
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.GET,
            r2BucketPath(accountId, bucketName) + configuration.pathSegments,
            headers = r2Headers(jurisdiction),
        )
        return storageEnvelope(client.execute(request)).result ?: ProviderJsonValue.Null
    }

    // MARK: R2 objects

    /** One page of objects. [delimiter] `/` groups keys into folder prefixes. */
    suspend fun listR2Objects(
        accountId: String,
        bucketName: String,
        jurisdiction: String?,
        prefix: String? = null,
        delimiter: String? = "/",
        cursor: String? = null,
        perPage: Int = 100,
    ): CloudflareR2ObjectListing {
        require(perPage in 1..1_000)
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.GET,
            r2BucketPath(accountId, bucketName) + "objects",
            query = buildList {
                add("per_page" to perPage.toString())
                prefix?.takeIf(String::isNotEmpty)?.let { add("prefix" to it) }
                delimiter?.takeIf(String::isNotEmpty)?.let { add("delimiter" to it) }
                cursor?.takeIf(String::isNotEmpty)?.let { add("cursor" to it) }
            },
            headers = r2Headers(jurisdiction),
        )
        val response = client.execute(request)
        val envelope = storageEnvelope(response)
        val info = runCatching { ProviderJsonParser.parse(response.bodyBytes())["result_info"] }.getOrNull()
        val result = envelope.result
        val items = result?.arrayValue ?: result?.get("objects")?.arrayValue.orEmpty()
        val prefixes = listOf(info?.get("delimited"), result?.get("delimited"), result?.get("common_prefixes"), result?.get("prefixes"))
            .firstNotNullOfOrNull { it?.arrayValue }
            .orEmpty()
            .mapNotNull { it.stringValue ?: it["prefix"]?.stringValue }
        val next = envelope.resultInfo?.cursor?.takeIf(String::isNotEmpty)
            ?.takeIf { it != cursor }
        return CloudflareR2ObjectListing(
            objects = items.mapNotNull(CloudflareR2Object::from),
            prefixes = prefixes,
            nextCursor = next,
        )
    }

    suspend fun downloadR2Object(
        accountId: String,
        bucketName: String,
        jurisdiction: String?,
        key: String,
    ): CloudflareR2ObjectData {
        val response = client.execute(
            CloudflareRestRequest(
                CloudflareHttpMethod.GET,
                r2ObjectPath(accountId, bucketName, key),
                accept = "*/*",
                headers = r2Headers(jurisdiction),
            ),
        )
        CloudflareRestClient.throwForHttpFailure(response)
        return CloudflareR2ObjectData(response.bodyBytes(), response.header("Content-Type"), response.header("ETag"))
    }

    suspend fun uploadR2Object(
        accountId: String,
        bucketName: String,
        jurisdiction: String?,
        key: String,
        data: ByteArray,
        contentType: String?,
        confirmation: CloudflareMutationConfirmation,
    ) {
        val normalizedKey = validObjectKey(key)
        if (data.size > MAXIMUM_R2_UPLOAD_BYTES) {
            throw CloudflareOperationException.invalidRequest("R2 uploads from this app must be 25 MB or smaller.")
        }
        requireCloudflareConfirmation(confirmation, normalizedKey)
        val request = CloudflareRestRequest(
            method = CloudflareHttpMethod.PUT,
            pathSegments = r2ObjectPath(accountId, bucketName, normalizedKey),
            body = data,
            contentType = safeContentType(contentType ?: "application/octet-stream"),
            headers = r2Headers(jurisdiction),
            readTimeoutMillis = 180_000,
        )
        validateStorageMutation(client.execute(request))
    }

    suspend fun deleteR2Object(
        accountId: String,
        bucketName: String,
        jurisdiction: String?,
        key: String,
        confirmation: CloudflareMutationConfirmation,
    ) {
        requireCloudflareConfirmation(confirmation, key)
        val request = CloudflareRestRequest(
            CloudflareHttpMethod.DELETE,
            r2ObjectPath(accountId, bucketName, validObjectKey(key)),
            headers = r2Headers(jurisdiction),
        )
        validateStorageMutation(client.execute(request))
    }

    // MARK: Helpers

    private suspend fun fetchR2Buckets(accountId: String, jurisdiction: String?): List<CloudflareR2Bucket> {
        val guard = CloudflarePaginationGuard()
        val seenCursors = HashSet<String>()
        val buckets = mutableListOf<CloudflareR2Bucket>()
        var cursor: String? = null
        do {
            val query = buildList {
                add("per_page" to "1000")
                add("order" to "name")
                add("direction" to "asc")
                cursor?.takeIf(String::isNotEmpty)?.let { add("cursor" to it) }
            }
            val response = client.execute(
                CloudflareRestRequest(
                    CloudflareHttpMethod.GET,
                    listOf("accounts", accountId, "r2", "buckets"),
                    query = query,
                    headers = r2Headers(jurisdiction),
                ),
            )
            val envelope = storageEnvelope(response)
            val batch = envelope.result?.get("buckets")?.arrayValue.orEmpty()
                .map { CloudflareR2Bucket.from(it).scoped(jurisdiction) }
            guard.record(batch.size, response.bodyHash())
            buckets += batch
            cursor = envelope.resultInfo?.cursor
            val next = cursor
            if (!next.isNullOrEmpty() && !seenCursors.add(next)) {
                throw CloudflareOperationException.invalidRequest("Cloudflare repeated an R2 cursor, so loading stopped safely.")
            }
        } while (!cursor.isNullOrEmpty())
        return buckets
    }

    private suspend fun storageResult(request: CloudflareRestRequest): ProviderJsonValue =
        storageEnvelope(client.execute(request)).result?.takeUnless { it.isNull }
            ?: throw CloudflareOperationException.decoding()

    private fun renamedNamespace(envelope: CloudflareEnvelope, namespaceId: String, title: String): CloudflareKVNamespace {
        // Cloudflare's rename returns the namespace; tolerate an empty result by echoing the change.
        val result = envelope.result?.takeIf { it is ProviderJsonValue.Obj && it["id"] != null }
        return result?.let(CloudflareKVNamespace::from) ?: CloudflareKVNamespace(namespaceId, title, null)
    }

    private fun d1Path(accountId: String) = listOf("accounts", accountId, "d1", "database")

    private fun kvNamespacesPath(accountId: String) = listOf("accounts", accountId, "storage", "kv", "namespaces")

    private fun kvValuePath(accountId: String, namespaceId: String, key: String) =
        kvNamespacesPath(accountId) + listOf(namespaceId, "values", key)

    private fun r2BucketPath(accountId: String, bucketName: String) = listOf("accounts", accountId, "r2", "buckets", bucketName)

    /** Object keys keep their `/` hierarchy as path segments, like iOS's explorer presets and wrangler. */
    private fun r2ObjectPath(accountId: String, bucketName: String, key: String) =
        r2BucketPath(accountId, bucketName) + "objects" + key.split('/')

    private fun validObjectKey(key: String): String {
        if (key.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter an object key.")
        if (key.length > 1_024 || key.startsWith('/') || key.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw CloudflareOperationException.invalidRequest(
                "This object key cannot be used through the Cloudflare API. Use keys without empty, “.” or “..” path parts.",
            )
        }
        return key
    }

    private fun r2Headers(jurisdiction: String?): Map<String, String> =
        if (jurisdiction == null || jurisdiction == "default") emptyMap() else mapOf("cf-r2-jurisdiction" to jurisdiction)

    private fun jsonOf(vararg pairs: Pair<String, String>): ProviderJsonValue =
        ProviderJsonValue.Obj(pairs.associate { (key, value) -> key to ProviderJsonValue.Str(value) })

    private fun safeContentType(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it == '\u0000' }.trim().ifEmpty { "application/octet-stream" }

    private fun CloudflareRestResponse.bodyHash(): Int = bodyBytes().contentHashCode()

    companion object {
        const val MAXIMUM_KV_VALUE_BYTES: Int = 25 * 1_024 * 1_024
        const val MAXIMUM_R2_UPLOAD_BYTES: Int = 25 * 1_024 * 1_024
        private val EMPTY_JSON_OBJECT = "{}".toByteArray(StandardCharsets.UTF_8)

        /** iOS `decodeStorageEnvelope`: HTTP failure first, then an unsuccessful envelope. */
        fun storageEnvelope(response: CloudflareRestResponse): CloudflareEnvelope {
            CloudflareRestClient.throwForHttpFailure(response)
            val envelope = CloudflareRestClient.parseEnvelope(response)
            if (!envelope.success) {
                if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
                throw CloudflareOperationException.requestFailed(
                    response.statusCode,
                    "Cloudflare reported an unsuccessful storage request.",
                )
            }
            return envelope
        }

        /** iOS `validateStorageMutationResponse`: an empty 2xx body is success. */
        fun validateStorageMutation(response: CloudflareRestResponse) {
            CloudflareRestClient.throwForHttpFailure(response)
            if (response.size == 0) return
            val envelope = CloudflareRestClient.parseEnvelope(response)
            if (!envelope.success) {
                if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
                throw CloudflareOperationException.requestFailed(
                    response.statusCode,
                    "Cloudflare reported an unsuccessful storage change.",
                )
            }
        }

        /** Pretty JSON for configuration documents. */
        fun prettyJson(value: ProviderJsonValue): String = CloudflarePrettyJson.write(value)
    }
}

/** R2 bucket configuration documents shown read-only on the bucket screen. */
enum class CloudflareR2Configuration(val title: String, val pathSegments: List<String>) {
    CORS("CORS rules", listOf("cors")),
    CUSTOM_DOMAINS("Custom domains", listOf("domains", "custom")),
    LIFECYCLE("Lifecycle rules", listOf("lifecycle")),
}

/** Indented JSON writer for read-only configuration and value previews. */
object CloudflarePrettyJson {
    fun write(value: ProviderJsonValue): String = StringBuilder().also { append(it, value, 0) }.toString()

    private fun append(output: StringBuilder, value: ProviderJsonValue, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        val closing = "  ".repeat(depth)
        when (value) {
            is ProviderJsonValue.Obj -> {
                if (value.fields.isEmpty()) {
                    output.append("{}")
                    return
                }
                output.append("{\n")
                value.fields.entries.sortedBy { it.key }.forEachIndexed { index, (key, child) ->
                    output.append(indent).append(ProviderJsonWriter.write(ProviderJsonValue.Str(key))).append(": ")
                    append(output, child, depth + 1)
                    if (index < value.fields.size - 1) output.append(',')
                    output.append('\n')
                }
                output.append(closing).append('}')
            }
            is ProviderJsonValue.Arr -> {
                if (value.items.isEmpty()) {
                    output.append("[]")
                    return
                }
                output.append("[\n")
                value.items.forEachIndexed { index, child ->
                    output.append(indent)
                    append(output, child, depth + 1)
                    if (index < value.items.size - 1) output.append(',')
                    output.append('\n')
                }
                output.append(closing).append(']')
            }
            else -> output.append(ProviderJsonWriter.write(value))
        }
    }
}
