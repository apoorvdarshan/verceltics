package com.apoorvdarshan.verceltics.data.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.double
import com.apoorvdarshan.verceltics.data.cloudflare.operations.int
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strings
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant
import java.util.Locale

// Ports of iOS `CloudflareWorkerScript`, `CloudflareWorkerDeployment` (CloudflareModels.swift) and
// every model in CloudflareWorkerAdvancedModels.swift. Parsers are lenient like iOS
// `decodeIfPresent`: missing optional fields become null and malformed list entries are skipped.

data class CloudflareWorkerRoute(
    val id: String,
    val pattern: String,
    val script: String?,
)

/** Full Worker script entry from `GET /accounts/{id}/workers/scripts` (iOS `CloudflareWorkerScript`). */
data class CloudflareWorkerScriptDetail(
    val id: String,
    val createdOn: String?,
    val modifiedOn: String?,
    val compatibilityDate: String?,
    val compatibilityFlags: List<String>,
    val handlers: List<String>,
    val hasAssets: Boolean?,
    val hasModules: Boolean?,
    val lastDeployedFrom: String?,
    val logpush: Boolean?,
    val migrationTag: String?,
    val tag: String?,
    val routes: List<CloudflareWorkerRoute>,
    val tags: List<String>,
    val usageModel: String?,
    val etag: String?,
    val namedHandlerCount: Int,
    val tailConsumerCount: Int,
    val placementMode: String?,
    val placementStatus: String?,
) {
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerScriptDetail? {
            if (value !is ProviderJsonValue.Obj) return null
            return CloudflareWorkerScriptDetail(
                id = value.strictStr("id") ?: "unknown-worker",
                createdOn = value.strictStr("created_on"),
                modifiedOn = value.strictStr("modified_on"),
                compatibilityDate = value.strictStr("compatibility_date"),
                compatibilityFlags = value.strings("compatibility_flags"),
                handlers = value.strings("handlers"),
                hasAssets = value.bool("has_assets"),
                hasModules = value.bool("has_modules"),
                lastDeployedFrom = value.strictStr("last_deployed_from"),
                logpush = value.bool("logpush"),
                migrationTag = value.strictStr("migration_tag"),
                tag = value.strictStr("tag"),
                routes = value.arr("routes").mapNotNull { route ->
                    if (route !is ProviderJsonValue.Obj) return@mapNotNull null
                    val pattern = route.strictStr("pattern") ?: ""
                    CloudflareWorkerRoute(
                        id = route.strictStr("id") ?: pattern,
                        pattern = pattern,
                        script = route.strictStr("script"),
                    )
                },
                tags = value.strings("tags"),
                usageModel = value.strictStr("usage_model"),
                etag = value.strictStr("etag"),
                namedHandlerCount = value.arr("named_handlers").size,
                tailConsumerCount = value.arr("tail_consumers").size,
                placementMode = value.strictStr("placement_mode"),
                placementStatus = value.strictStr("placement_status"),
            )
        }
    }
}

data class CloudflareWorkerDeploymentVersion(
    val versionId: String,
    val percentage: Double,
)

/** iOS `CloudflareWorkerDeployment`. */
data class CloudflareWorkerDeploymentInfo(
    val id: String,
    val createdOn: String?,
    val source: String?,
    val strategy: String?,
    val versions: List<CloudflareWorkerDeploymentVersion>,
    val message: String?,
    val triggeredBy: String?,
    val authorEmail: String?,
) {
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerDeploymentInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val id = value.strictStr("id") ?: return null
            val annotations = value.obj("annotations")
            return CloudflareWorkerDeploymentInfo(
                id = id,
                createdOn = value.strictStr("created_on"),
                source = value.strictStr("source"),
                strategy = value.strictStr("strategy"),
                versions = value.arr("versions").mapNotNull { version ->
                    val versionId = version.strictStr("version_id") ?: return@mapNotNull null
                    CloudflareWorkerDeploymentVersion(versionId, version.double("percentage") ?: 0.0)
                },
                message = annotations.strictStr("workers/message"),
                triggeredBy = annotations.strictStr("workers/triggered_by"),
                authorEmail = value.strictStr("author_email"),
            )
        }
    }
}

data class CloudflareWorkerVersionMetadata(
    val authorEmail: String?,
    val authorId: String?,
    val createdOn: String?,
    val modifiedOn: String?,
    val source: String?,
    val hasPreview: Boolean?,
) {
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)

    companion object {
        fun parse(value: ProviderJsonValue?): CloudflareWorkerVersionMetadata? {
            if (value !is ProviderJsonValue.Obj) return null
            return CloudflareWorkerVersionMetadata(
                authorEmail = value.strictStr("author_email"),
                authorId = value.strictStr("author_id"),
                createdOn = value.strictStr("created_on"),
                modifiedOn = value.strictStr("modified_on"),
                source = value.strictStr("source"),
                hasPreview = value.bool("hasPreview"),
            )
        }
    }
}

/** iOS `CloudflareWorkerVersion`. */
data class CloudflareWorkerVersionInfo(
    val id: String,
    val number: Int?,
    val metadata: CloudflareWorkerVersionMetadata?,
) {
    /** iOS row title: `Version N`, or the first 14 characters of the id. */
    val displayTitle: String get() = number?.let { "Version $it" } ?: id.take(14)

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerVersionInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val id = value.strictStr("id") ?: return null
            return CloudflareWorkerVersionInfo(id, value.int("number"), CloudflareWorkerVersionMetadata.parse(value["metadata"]))
        }
    }
}

data class CloudflareWorkerBindingSummary(val name: String, val type: String)

/** iOS `CloudflareWorkerVersionDetail`. */
data class CloudflareWorkerVersionDetailInfo(
    val id: String,
    val number: Int?,
    val metadata: CloudflareWorkerVersionMetadata?,
    val bindings: List<CloudflareWorkerBindingSummary>,
    val startupTimeMilliseconds: Double?,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerVersionDetailInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val id = value.strictStr("id") ?: return null
            return CloudflareWorkerVersionDetailInfo(
                id = id,
                number = value.int("number"),
                metadata = CloudflareWorkerVersionMetadata.parse(value["metadata"]),
                bindings = value.obj("resources").arr("bindings").map(::bindingSummary),
                startupTimeMilliseconds = value.double("startup_time_ms"),
            )
        }

        /** iOS `bindingSummary`: name (or "Binding") and a title-cased type (or "Unknown type"). */
        fun bindingSummary(value: ProviderJsonValue): CloudflareWorkerBindingSummary {
            if (value !is ProviderJsonValue.Obj) return CloudflareWorkerBindingSummary("Binding", "Unknown type")
            return CloudflareWorkerBindingSummary(
                name = value.strictStr("name") ?: "Binding",
                type = value.strictStr("type")?.replace('_', ' ')?.titleCased() ?: "Unknown type",
            )
        }
    }
}

/** iOS `CloudflareWorkerSecretMetadata`. Values are never returned by Cloudflare. */
data class CloudflareWorkerSecretInfo(
    val name: String,
    val type: String,
    val format: String?,
    val usages: List<String>,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerSecretInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val name = value.strictStr("name") ?: return null
            return CloudflareWorkerSecretInfo(
                name = name,
                type = value.strictStr("type") ?: "secret_text",
                format = value.strictStr("format"),
                usages = value.strings("usages"),
            )
        }
    }
}

/** iOS `CloudflareWorkerSchedule`. */
data class CloudflareWorkerScheduleInfo(
    val cron: String,
    val createdOn: String? = null,
    val modifiedOn: String? = null,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerScheduleInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val cron = value.strictStr("cron") ?: return null
            return CloudflareWorkerScheduleInfo(cron, value.strictStr("created_on"), value.strictStr("modified_on"))
        }

        /** Parses `{ "schedules": [...] }` (iOS `CloudflareWorkerScheduleList`). */
        fun parseList(result: ProviderJsonValue): List<CloudflareWorkerScheduleInfo> =
            result.arr("schedules").mapNotNull(::parse)
    }
}

/** iOS `CloudflareWorkerDomain`. */
data class CloudflareWorkerDomainInfo(
    val id: String,
    val hostname: String,
    val service: String,
    val environment: String?,
    val zoneId: String?,
    val zoneName: String?,
    val certificateId: String?,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerDomainInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            return CloudflareWorkerDomainInfo(
                id = value.strictStr("id") ?: return null,
                hostname = value.strictStr("hostname") ?: return null,
                service = value.strictStr("service") ?: return null,
                environment = value.strictStr("environment"),
                zoneId = value.strictStr("zone_id"),
                zoneName = value.strictStr("zone_name"),
                certificateId = value.strictStr("cert_id"),
            )
        }
    }
}

/** iOS `CloudflareWorkerSubdomain`. */
data class CloudflareWorkerSubdomainSettings(
    val enabled: Boolean,
    val previewsEnabled: Boolean,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerSubdomainSettings =
            CloudflareWorkerSubdomainSettings(value.bool("enabled") ?: false, value.bool("previews_enabled") ?: false)
    }
}

/** iOS `CloudflareWorkerTail`. [url] is validated by [CloudflareTailEndpoint] before connecting. */
data class CloudflareWorkerTailSession(
    val id: String,
    val expiresAt: String,
    val url: String,
) {
    val expiresDate: Instant? get() = CloudflareDates.parse(expiresAt)

    override fun toString(): String = "CloudflareWorkerTailSession(id=$id, expiresAt=$expiresAt, url=<redacted>)"

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerTailSession? {
            if (value !is ProviderJsonValue.Obj) return null
            return CloudflareWorkerTailSession(
                id = value.strictStr("id") ?: return null,
                expiresAt = value.strictStr("expires_at") ?: return null,
                url = value.strictStr("url") ?: return null,
            )
        }
    }
}

data class CloudflareWorkerObservabilityLogs(
    val enabled: Boolean,
    val invocationLogs: Boolean,
    val destinations: List<String>,
    val headSamplingRate: Double?,
    val persist: Boolean?,
)

data class CloudflareWorkerObservabilityTraces(
    val enabled: Boolean?,
    val destinations: List<String>,
    val headSamplingRate: Double?,
    val persist: Boolean?,
    val propagationPolicy: String?,
)

/** iOS `CloudflareWorkerObservability`. */
data class CloudflareWorkerObservabilityInfo(
    val enabled: Boolean,
    val headSamplingRate: Double?,
    val logs: CloudflareWorkerObservabilityLogs?,
    val traces: CloudflareWorkerObservabilityTraces?,
) {
    companion object {
        fun parse(value: ProviderJsonValue?): CloudflareWorkerObservabilityInfo? {
            if (value !is ProviderJsonValue.Obj) return null
            val logs = value.obj("logs")
            val traces = value.obj("traces")
            return CloudflareWorkerObservabilityInfo(
                enabled = value.bool("enabled") ?: false,
                headSamplingRate = value.double("head_sampling_rate"),
                logs = logs?.let {
                    CloudflareWorkerObservabilityLogs(
                        enabled = it.bool("enabled") ?: false,
                        invocationLogs = it.bool("invocation_logs") ?: false,
                        destinations = it.strings("destinations"),
                        headSamplingRate = it.double("head_sampling_rate"),
                        persist = it.bool("persist"),
                    )
                },
                traces = traces?.let {
                    CloudflareWorkerObservabilityTraces(
                        enabled = it.bool("enabled"),
                        destinations = it.strings("destinations"),
                        headSamplingRate = it.double("head_sampling_rate"),
                        persist = it.bool("persist"),
                        propagationPolicy = it.strictStr("propagation_policy"),
                    )
                },
            )
        }
    }
}

/** iOS `CloudflareWorkerScriptSettings` (`GET .../settings`). */
data class CloudflareWorkerScriptSettingsInfo(
    val annotations: Map<String, String>,
    val bindings: List<ProviderJsonValue>,
    val cacheOptions: ProviderJsonValue.Obj?,
    val compatibilityDate: String?,
    val compatibilityFlags: List<String>,
    val limits: ProviderJsonValue.Obj?,
    val migrations: ProviderJsonValue?,
    val observability: CloudflareWorkerObservabilityInfo?,
    val placement: ProviderJsonValue?,
    val tags: List<String>,
    val tailConsumers: List<ProviderJsonValue>,
    val usageModel: String?,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerScriptSettingsInfo =
            CloudflareWorkerScriptSettingsInfo(
                annotations = value.obj("annotations")?.fields.orEmpty()
                    .mapNotNull { (key, child) -> (child as? ProviderJsonValue.Str)?.let { key to it.value } }
                    .toMap(),
                bindings = value.arr("bindings"),
                cacheOptions = value.obj("cache_options"),
                compatibilityDate = value.strictStr("compatibility_date"),
                compatibilityFlags = value.strings("compatibility_flags"),
                limits = value.obj("limits"),
                migrations = value["migrations"]?.takeUnless { it.isNull },
                observability = CloudflareWorkerObservabilityInfo.parse(value["observability"]),
                placement = value["placement"]?.takeUnless { it.isNull },
                tags = value.strings("tags"),
                tailConsumers = value.arr("tail_consumers"),
                usageModel = value.strictStr("usage_model"),
            )
    }
}

/** iOS `CloudflareWorkerScriptLevelSettings` (`GET/PATCH .../script-settings`). */
data class CloudflareWorkerScriptLevelSettingsInfo(
    val logpush: Boolean?,
    val observability: CloudflareWorkerObservabilityInfo?,
    val tags: List<String>,
    val tailConsumerCount: Int,
) {
    companion object {
        fun parse(value: ProviderJsonValue): CloudflareWorkerScriptLevelSettingsInfo =
            CloudflareWorkerScriptLevelSettingsInfo(
                logpush = value.bool("logpush"),
                observability = CloudflareWorkerObservabilityInfo.parse(value["observability"]),
                tags = value.strings("tags"),
                tailConsumerCount = value.arr("tail_consumers").size,
            )
    }
}

/** Downloaded Worker source (`GET .../content/v2`). Kept only in memory: source can contain secrets. */
data class CloudflareWorkerContent(
    val text: String,
    val contentType: String?,
    val byteCount: Int,
    val truncatedForDisplay: Boolean,
) {
    override fun toString(): String =
        "CloudflareWorkerContent(contentType=$contentType, byteCount=$byteCount, text=<redacted>)"
}

/** iOS `.capitalized`: first letter of every word upper-case, the rest lower-case. */
internal fun String.titleCased(): String = split(' ').joinToString(" ") { word ->
    word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** Pretty JSON with sorted keys (iOS `.prettyPrinted, .sortedKeys`). */
internal fun ProviderJsonValue.prettyJson(indent: String = ""): String = when (this) {
    is ProviderJsonValue.Obj -> if (fields.isEmpty()) {
        "{}"
    } else {
        val inner = "$indent  "
        fields.entries.sortedBy { it.key }.joinToString(",\n", prefix = "{\n", postfix = "\n$indent}") { (key, child) ->
            "$inner${ProviderJsonValue.Str(key).prettyJson()} : ${child.prettyJson(inner)}"
        }
    }
    is ProviderJsonValue.Arr -> if (items.isEmpty()) {
        "[]"
    } else {
        val inner = "$indent  "
        items.joinToString(",\n", prefix = "[\n", postfix = "\n$indent]") { "$inner${it.prettyJson(inner)}" }
    }
    else -> com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter.write(this)
}
