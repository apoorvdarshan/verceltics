package com.apoorvdarshan.verceltics.data.cloudflare.storage

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareJsonObject
import com.apoorvdarshan.verceltics.data.cloudflare.operations.double
import com.apoorvdarshan.verceltics.data.cloudflare.operations.int
import com.apoorvdarshan.verceltics.data.cloudflare.operations.long
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

// Port of iOS CloudflareStorageModels.swift. Parsers are lenient like iOS `decodeIfPresent`.

/** iOS `CloudflareD1Database`. */
data class CloudflareD1Database(
    val uuid: String,
    val name: String,
    val version: String?,
    val createdAt: String?,
    val fileSize: Long?,
    val numberOfTables: Int?,
    val jurisdiction: String?,
    val readReplicationMode: String?,
) {
    val id: String get() = uuid
    val createdDate: Instant? get() = CloudflareDates.parse(createdAt)

    companion object {
        fun from(json: ProviderJsonValue): CloudflareD1Database = CloudflareD1Database(
            uuid = json.str("uuid").orEmpty(),
            name = json.str("name") ?: "Unnamed database",
            version = json.str("version"),
            createdAt = json.str("created_at"),
            fileSize = json.long("file_size"),
            numberOfTables = json.int("num_tables"),
            jurisdiction = json.str("jurisdiction"),
            readReplicationMode = json.obj("read_replication").str("mode"),
        )
    }
}

/** iOS `CloudflareD1CreateInput`. */
data class CloudflareD1CreateInput(
    val name: String,
    val jurisdiction: String? = null,
    val primaryLocationHint: String? = null,
    val readReplicationMode: String? = null,
) {
    fun toJson(): ProviderJsonValue.Obj = cloudflareJsonObject(
        "name" to name,
        "jurisdiction" to jurisdiction,
        "primary_location_hint" to primaryLocationHint,
        "read_replication" to readReplicationMode?.let { mapOf("mode" to it) },
    )
}

/** iOS `CloudflareD1QueryResult`. */
data class CloudflareD1QueryResult(
    val success: Boolean,
    val rows: List<Map<String, ProviderJsonValue>>,
    val meta: Meta?,
) {
    /** Column names across every row, sorted (iOS `CloudflareD1ResultTable.columns`). */
    val columns: List<String> get() = rows.flatMap { it.keys }.toSortedSet().toList()

    data class Meta(
        val changedDatabase: Boolean?,
        val changes: Int?,
        val duration: Double?,
        val lastRowId: Long?,
        val rowsRead: Int?,
        val rowsWritten: Int?,
        val servedByColo: String?,
        val servedByPrimary: Boolean?,
        val servedByRegion: String?,
        val sizeAfter: Long?,
        val sqlDurationMilliseconds: Double?,
    ) {
        /** iOS `queryMetaSummary`. */
        val summary: String
            get() {
                val parts = buildList {
                    (sqlDurationMilliseconds ?: duration)?.let { add(String.format(java.util.Locale.US, "%.2f ms", it)) }
                    rowsRead?.let { add("$it rows read") }
                    rowsWritten?.let { add("$it rows written") }
                    servedByRegion?.let(::add)
                    servedByColo?.let(::add)
                }
                return if (parts.isEmpty()) "Statement completed." else parts.joinToString(" · ")
            }

        companion object {
            fun from(json: ProviderJsonValue): Meta = Meta(
                changedDatabase = json.bool("changed_db"),
                changes = json.int("changes"),
                duration = json.double("duration"),
                lastRowId = json.long("last_row_id"),
                rowsRead = json.int("rows_read"),
                rowsWritten = json.int("rows_written"),
                servedByColo = json.str("served_by_colo"),
                servedByPrimary = json.bool("served_by_primary"),
                servedByRegion = json.str("served_by_region"),
                sizeAfter = json.long("size_after"),
                sqlDurationMilliseconds = json.obj("timings").double("sql_duration_ms"),
            )
        }
    }

    companion object {
        fun from(json: ProviderJsonValue): CloudflareD1QueryResult = CloudflareD1QueryResult(
            success = json.bool("success") ?: false,
            rows = json.arr("results").mapNotNull { row -> row.objectValue },
            meta = json.obj("meta")?.let(Meta::from),
        )

        /** iOS `queryMetaSummary(nil)`. */
        const val NO_METADATA: String = "No execution metadata returned."
    }
}

/** iOS `cloudflareStorageDisplayValue`: cell text for a D1 value or KV metadata. */
fun cloudflareStorageDisplayValue(value: ProviderJsonValue?): String = when (value) {
    null, ProviderJsonValue.Null -> "NULL"
    is ProviderJsonValue.Str -> value.value
    is ProviderJsonValue.Num -> value.text
    is ProviderJsonValue.Bool -> if (value.value) "true" else "false"
    is ProviderJsonValue.Obj, is ProviderJsonValue.Arr -> ProviderJsonWriter.write(value)
}

/** iOS `CloudflareKVNamespace`. */
data class CloudflareKVNamespace(
    val id: String,
    val title: String,
    val supportsUrlEncoding: Boolean?,
) {
    companion object {
        fun from(json: ProviderJsonValue): CloudflareKVNamespace = CloudflareKVNamespace(
            id = json.str("id").orEmpty(),
            title = json.str("title") ?: "Untitled namespace",
            supportsUrlEncoding = json.bool("supports_url_encoding"),
        )
    }
}

/** iOS `CloudflareKVKey`. */
data class CloudflareKVKey(
    val name: String,
    /** Unix seconds. */
    val expiration: Double?,
    val metadata: ProviderJsonValue?,
) {
    val expirationDate: Instant? get() = expiration?.let { Instant.ofEpochMilli((it * 1_000).toLong()) }

    companion object {
        fun from(json: ProviderJsonValue): CloudflareKVKey? {
            val name = json.str("name") ?: return null
            return CloudflareKVKey(
                name = name,
                expiration = json.double("expiration"),
                metadata = json["metadata"]?.takeUnless { it.isNull },
            )
        }
    }
}

/** iOS `CloudflareKVValue`. */
class CloudflareKVValue(
    data: ByteArray,
    val contentType: String?,
    val expiration: String?,
) {
    private val bytes = data.copyOf()

    val size: Int get() = bytes.size

    fun data(): ByteArray = bytes.copyOf()

    /** The value as text, or null when it is not valid UTF-8 (iOS `String(data:encoding:.utf8)`). */
    val utf8Text: String?
        get() = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

    /** Base64 in 76-character lines (iOS `.lineLength76Characters` + `.endLineWithLineFeed`). */
    val base64Text: String get() = Base64.getEncoder().encodeToString(bytes).chunked(76).joinToString("\n")

    override fun toString(): String = "CloudflareKVValue(bytes=${bytes.size}, contentType=$contentType)"
}

/** iOS `CloudflareR2Bucket`. Bucket names are only unique within a jurisdiction. */
data class CloudflareR2Bucket(
    val name: String,
    val creationDate: String?,
    val jurisdiction: String?,
    val location: String?,
    val storageClass: String?,
) {
    val id: String get() = "${jurisdiction ?: "default"}|$name"
    val createdDate: Instant? get() = CloudflareDates.parse(creationDate)

    /** iOS `storageR2Bucket`: buckets listed in a jurisdiction scope inherit it. */
    fun scoped(scope: String?): CloudflareR2Bucket =
        if (jurisdiction == null && scope != null) copy(jurisdiction = scope) else this

    companion object {
        fun from(json: ProviderJsonValue): CloudflareR2Bucket = CloudflareR2Bucket(
            name = json.str("name") ?: "Unnamed bucket",
            creationDate = json.str("creation_date"),
            jurisdiction = json.str("jurisdiction"),
            location = json.str("location"),
            storageClass = json.str("storage_class"),
        )
    }
}

/** iOS `CloudflareR2CreateInput`. The jurisdiction travels as the `cf-r2-jurisdiction` header. */
data class CloudflareR2CreateInput(
    val name: String,
    val jurisdiction: String? = null,
    val locationHint: String? = null,
    val storageClass: String? = null,
) {
    fun toJson(): ProviderJsonValue.Obj = cloudflareJsonObject(
        "name" to name,
        "locationHint" to locationHint,
        "storageClass" to storageClass,
    )
}

/** One object listed from an R2 bucket through the Cloudflare API. */
data class CloudflareR2Object(
    val key: String,
    val size: Long?,
    val etag: String?,
    val lastModified: String?,
    val contentType: String?,
    val storageClass: String?,
) {
    val modifiedDate: Instant? get() = CloudflareDates.parse(lastModified)

    companion object {
        fun from(json: ProviderJsonValue): CloudflareR2Object? {
            val key = json.str("key") ?: json.str("name") ?: return null
            val httpMetadata = json.obj("http_metadata") ?: json.obj("httpMetadata")
            return CloudflareR2Object(
                key = key,
                size = json.long("size"),
                etag = json.str("etag") ?: json.str("http_etag"),
                lastModified = json.str("last_modified") ?: json.str("uploaded"),
                contentType = httpMetadata.str("contentType") ?: httpMetadata.str("content_type") ?: json.str("content_type"),
                storageClass = json.str("storage_class") ?: json.str("storageClass"),
            )
        }
    }
}

/** A listed page of R2 objects; [prefixes] are "folders" when a delimiter is used. */
data class CloudflareR2ObjectListing(
    val objects: List<CloudflareR2Object>,
    val prefixes: List<String>,
    val nextCursor: String?,
)

/** A downloaded R2 object. */
class CloudflareR2ObjectData(
    data: ByteArray,
    val contentType: String?,
    val etag: String?,
) {
    private val bytes = data.copyOf()

    val size: Int get() = bytes.size

    fun data(): ByteArray = bytes.copyOf()

    override fun toString(): String = "CloudflareR2ObjectData(bytes=${bytes.size}, contentType=$contentType)"
}

/** R2 jurisdictions the app scans and offers (iOS lists default, eu and fedramp). */
object CloudflareR2Jurisdictions {
    val SCOPED: List<String> = listOf("eu", "fedramp")
    val LOCATION_HINTS: List<String> = listOf("wnam", "enam", "weur", "eeur", "apac", "oc")

    /** iOS `isValidR2BucketName`. */
    fun isValidBucketName(name: String): Boolean =
        name.length in 3..63 && !name.startsWith('-') && !name.endsWith('-') &&
            name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** iOS `storageClassLabel`. */
    fun storageClassLabel(value: String?): String = when (value) {
        "InfrequentAccess" -> "Infrequent Access"
        null -> "Standard"
        else -> value
    }
}
