package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareJsonObject
import com.apoorvdarshan.verceltics.data.cloudflare.operations.double
import com.apoorvdarshan.verceltics.data.cloudflare.operations.int
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strings
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant

// Ports of iOS `CloudflareZone`, `CloudflareDNSRecord`, `CloudflareDNSRecordInput`,
// `CloudflareCachePurge` and the zone models in `CloudflareOperationsModels.swift`.

/** Full zone object (`GET /zones/{id}`). */
data class CloudflareZoneDetail(
    val id: String,
    val name: String,
    val status: String?,
    val type: String?,
    val paused: Boolean?,
    val developmentMode: Int?,
    val nameServers: List<String>,
    val originalNameServers: List<String>,
    val originalRegistrar: String?,
    val originalDnsHost: String?,
    val createdOn: String?,
    val modifiedOn: String?,
    val activatedOn: String?,
    val accountId: String?,
    val accountName: String?,
    val plan: CloudflareZonePlan?,
    val meta: Map<String, ProviderJsonValue>,
    val owner: Map<String, ProviderJsonValue>?,
    val tenant: Map<String, ProviderJsonValue>?,
    val tenantUnit: Map<String, ProviderJsonValue>?,
    val vanityNameServers: List<String>,
    val verificationKey: String?,
    val permissions: List<String>,
    val cnameSuffix: String?,
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true) && paused != true
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)
    val activatedDate: Instant? get() = CloudflareDates.parse(activatedOn)

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareZoneDetail {
            val id = value.strictStr("id") ?: throw CloudflareOperationException.decoding()
            val name = value.strictStr("name") ?: throw CloudflareOperationException.decoding()
            val account = value.obj("account")
            return CloudflareZoneDetail(
                id = id,
                name = name,
                status = value.strictStr("status"),
                type = value.strictStr("type"),
                paused = value.bool("paused"),
                developmentMode = value.int("development_mode"),
                nameServers = value.strings("name_servers"),
                originalNameServers = value.strings("original_name_servers"),
                originalRegistrar = value.strictStr("original_registrar"),
                originalDnsHost = value.strictStr("original_dnshost"),
                createdOn = value.strictStr("created_on"),
                modifiedOn = value.strictStr("modified_on"),
                activatedOn = value.strictStr("activated_on"),
                accountId = account.strictStr("id"),
                accountName = account.strictStr("name"),
                plan = value.obj("plan")?.let(CloudflareZonePlan::parse),
                meta = value.obj("meta")?.fields.orEmpty(),
                owner = value.obj("owner")?.fields,
                tenant = value.obj("tenant")?.fields,
                tenantUnit = value.obj("tenant_unit")?.fields,
                vanityNameServers = value.strings("vanity_name_servers"),
                verificationKey = value.strictStr("verification_key"),
                permissions = value.strings("permissions"),
                cnameSuffix = value.strictStr("cname_suffix"),
            )
        }
    }
}

data class CloudflareZonePlan(
    val id: String?,
    val name: String?,
    val currency: String?,
    val frequency: String?,
    val price: Double?,
    val isSubscribed: Boolean?,
    val canSubscribe: Boolean?,
    val externallyManaged: Boolean?,
    val legacyDiscount: Boolean?,
    val legacyId: String?,
) {
    companion object {
        fun parse(value: ProviderJsonValue) = CloudflareZonePlan(
            id = value.strictStr("id"),
            name = value.strictStr("name"),
            currency = value.strictStr("currency"),
            frequency = value.strictStr("frequency"),
            price = value.double("price"),
            isSubscribed = value.bool("is_subscribed"),
            canSubscribe = value.bool("can_subscribe"),
            externallyManaged = value.bool("externally_managed"),
            legacyDiscount = value.bool("legacy_discount"),
            legacyId = value.strictStr("legacy_id"),
        )
    }
}

data class CloudflareDnsRecord(
    val id: String,
    val type: String,
    val name: String,
    val content: String?,
    val proxiable: Boolean?,
    val proxied: Boolean?,
    val ttl: Int?,
    val locked: Boolean?,
    val comment: String?,
    val commentModifiedOn: String?,
    val tags: List<String>,
    val tagsModifiedOn: String?,
    val createdOn: String?,
    val modifiedOn: String?,
    val priority: Int?,
    val data: ProviderJsonValue.Obj?,
    val settings: ProviderJsonValue.Obj?,
    val privateRouting: Boolean?,
    val meta: Map<String, ProviderJsonValue>,
) {
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)
    val commentModifiedDate: Instant? get() = CloudflareDates.parse(commentModifiedOn)
    val tagsModifiedDate: Instant? get() = CloudflareDates.parse(tagsModifiedOn)

    fun matches(query: String): Boolean = query.isBlank() ||
        name.contains(query, ignoreCase = true) ||
        type.contains(query, ignoreCase = true) ||
        content?.contains(query, ignoreCase = true) == true ||
        comment?.contains(query, ignoreCase = true) == true ||
        tags.any { it.contains(query, ignoreCase = true) }

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareDnsRecord = CloudflareDnsRecord(
            id = value.strictStr("id") ?: throw CloudflareOperationException.decoding(),
            type = value.strictStr("type") ?: throw CloudflareOperationException.decoding(),
            name = value.strictStr("name") ?: throw CloudflareOperationException.decoding(),
            content = value.strictStr("content"),
            proxiable = value.bool("proxiable"),
            proxied = value.bool("proxied"),
            ttl = value.int("ttl"),
            locked = value.bool("locked"),
            comment = value.strictStr("comment"),
            commentModifiedOn = value.strictStr("comment_modified_on"),
            tags = value.strings("tags"),
            tagsModifiedOn = value.strictStr("tags_modified_on"),
            createdOn = value.strictStr("created_on"),
            modifiedOn = value.strictStr("modified_on"),
            priority = value.int("priority"),
            data = value.obj("data"),
            settings = value.obj("settings"),
            privateRouting = value.bool("private_routing"),
            meta = value.obj("meta")?.fields.orEmpty(),
        )

        /** iOS `dnsRecordSort`: by name (case-insensitive), then type. */
        val DISPLAY_ORDER: Comparator<CloudflareDnsRecord> = Comparator { lhs, rhs ->
            if (lhs.name == rhs.name) lhs.type.compareTo(rhs.type) else lhs.name.compareTo(rhs.name, ignoreCase = true)
        }
    }
}

/** iOS `CloudflareDNSRecordInput`: optional fields are omitted from the JSON body when null. */
data class CloudflareDnsRecordInput(
    val type: String,
    val name: String,
    val content: String? = null,
    val ttl: Int = 1,
    val proxied: Boolean? = null,
    val comment: String? = null,
    val tags: List<String>? = null,
    val priority: Int? = null,
    val data: ProviderJsonValue.Obj? = null,
    val settings: ProviderJsonValue.Obj? = null,
    val privateRouting: Boolean? = null,
) {
    fun toJson(): ProviderJsonValue.Obj = cloudflareJsonObject(
        "type" to type.uppercase(),
        "name" to name,
        "content" to content,
        "ttl" to ttl,
        "proxied" to proxied,
        "comment" to comment,
        "tags" to tags,
        "priority" to priority,
        "data" to data,
        "settings" to settings,
        "private_routing" to privateRouting,
    )
}

/** iOS `CloudflareCachePurge`. */
sealed interface CloudflareCachePurge {
    data object Everything : CloudflareCachePurge
    data class Files(val values: List<String>) : CloudflareCachePurge
    data class Tags(val values: List<String>) : CloudflareCachePurge
    data class Hosts(val values: List<String>) : CloudflareCachePurge
    data class Prefixes(val values: List<String>) : CloudflareCachePurge

    val entries: List<String>?
        get() = when (this) {
            Everything -> null
            is Files -> values
            is Tags -> values
            is Hosts -> values
            is Prefixes -> values
        }

    fun toJson(): ProviderJsonValue.Obj = when (this) {
        Everything -> cloudflareJsonObject("purge_everything" to true)
        is Files -> cloudflareJsonObject("files" to values)
        is Tags -> cloudflareJsonObject("tags" to values)
        is Hosts -> cloudflareJsonObject("hosts" to values)
        is Prefixes -> cloudflareJsonObject("prefixes" to values)
    }
}

data class CloudflareDnssecStatus(
    val status: String?,
    val flags: Int?,
    val algorithm: String?,
    val keyType: String?,
    val digestType: String?,
    val digestAlgorithm: String?,
    val digest: String?,
    val ds: String?,
    val keyTag: Int?,
    val publicKey: String?,
    val modifiedOn: String?,
    val multiSigner: Boolean?,
    val presigned: Boolean?,
    val useNsec3: Boolean?,
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true)
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)

    companion object {
        fun parse(value: ProviderJsonValue) = CloudflareDnssecStatus(
            status = value.strictStr("status"),
            flags = value.int("flags"),
            algorithm = value.strictStr("algorithm"),
            keyType = value.strictStr("key_type"),
            digestType = value.strictStr("digest_type"),
            digestAlgorithm = value.strictStr("digest_algorithm"),
            digest = value.strictStr("digest"),
            ds = value.strictStr("ds"),
            keyTag = value.int("key_tag"),
            publicKey = value.strictStr("public_key"),
            modifiedOn = value.strictStr("modified_on"),
            multiSigner = value.bool("dnssec_multi_signer"),
            presigned = value.bool("dnssec_presigned"),
            useNsec3 = value.bool("dnssec_use_nsec3"),
        )
    }
}

data class CloudflareZoneSetting(
    val id: String,
    val value: ProviderJsonValue,
    val editable: Boolean,
    val modifiedOn: String?,
) {
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)

    /** Only scalar values have an in-app editor (iOS `isScalar`). */
    val isScalar: Boolean
        get() = value is ProviderJsonValue.Str || value is ProviderJsonValue.Num || value is ProviderJsonValue.Bool

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareZoneSetting = CloudflareZoneSetting(
            id = value.strictStr("id") ?: throw CloudflareOperationException.decoding(),
            value = value["value"] ?: ProviderJsonValue.Null,
            editable = value.bool("editable") ?: false,
            modifiedOn = value.strictStr("modified_on"),
        )

        /** iOS `commonSettingIDs`: shown first, in this order. */
        val COMMON_SETTING_IDS = listOf(
            "ssl", "min_tls_version", "always_use_https", "automatic_https_rewrites",
            "http2", "http3", "tls_1_3", "brotli", "early_hints", "ipv6",
            "security_level", "browser_check", "development_mode", "always_online",
            "cache_level", "browser_cache_ttl", "rocket_loader", "websockets",
        )

        val DISPLAY_ORDER: Comparator<CloudflareZoneSetting> = Comparator { lhs, rhs ->
            val left = COMMON_SETTING_IDS.indexOf(lhs.id).takeIf { it >= 0 }
            val right = COMMON_SETTING_IDS.indexOf(rhs.id).takeIf { it >= 0 }
            when {
                left != null && right != null -> left.compareTo(right)
                left != null -> -1
                right != null -> 1
                else -> lhs.id.compareTo(rhs.id)
            }
        }

        /** iOS `settingDisplayName`: "min_tls_version" → "Min Tls Version". */
        fun displayName(id: String): String = id.replace('_', ' ')
            .split(' ')
            .filter(String::isNotEmpty)
            .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }
    }
}

data class CloudflareZoneDnsSettings(
    val flattenAllCnames: Boolean?,
    val foundationDns: Boolean?,
    val multiProvider: Boolean?,
    val secondaryOverrides: Boolean?,
    val nameserversType: String?,
    val nameserversSet: Int?,
    val nameServerTtl: Double?,
    val zoneMode: String?,
    val internalReferenceZoneId: String?,
    val soa: Soa?,
) {
    data class Soa(
        val primaryNameServer: String?,
        val responsibleName: String?,
        val refresh: Double?,
        val retry: Double?,
        val expire: Double?,
        val minimumTtl: Double?,
        val ttl: Double?,
    )

    companion object {
        fun parse(value: ProviderJsonValue): CloudflareZoneDnsSettings {
            val nameservers = value.obj("nameservers")
            val soa = value.obj("soa")
            return CloudflareZoneDnsSettings(
                flattenAllCnames = value.bool("flatten_all_cnames"),
                foundationDns = value.bool("foundation_dns"),
                multiProvider = value.bool("multi_provider"),
                secondaryOverrides = value.bool("secondary_overrides"),
                nameserversType = nameservers.strictStr("type"),
                nameserversSet = nameservers.int("ns_set"),
                nameServerTtl = value.double("ns_ttl"),
                zoneMode = value.strictStr("zone_mode"),
                internalReferenceZoneId = value.obj("internal_dns").strictStr("reference_zone_id"),
                soa = soa?.let {
                    Soa(
                        primaryNameServer = it.strictStr("mname"),
                        responsibleName = it.strictStr("rname"),
                        refresh = it.double("refresh"),
                        retry = it.double("retry"),
                        expire = it.double("expire"),
                        minimumTtl = it.double("min_ttl"),
                        ttl = it.double("ttl"),
                    )
                },
            )
        }
    }
}

data class CloudflareDnsUsage(val recordUsage: Int, val recordQuota: Int?) {
    val available: Int? get() = recordQuota?.let { (it - recordUsage).coerceAtLeast(0) }

    companion object {
        fun parse(value: ProviderJsonValue) = CloudflareDnsUsage(
            recordUsage = value.int("record_usage") ?: throw CloudflareOperationException.decoding(),
            recordQuota = value.int("record_quota"),
        )
    }
}

data class CloudflareDnsAnalyticsReport(
    val rows: Int,
    val totals: Map<String, ProviderJsonValue>,
    val minimums: Map<String, ProviderJsonValue>,
    val maximums: Map<String, ProviderJsonValue>,
    val dataLag: Double,
) {
    fun total(name: String): Double? = totals[name]?.numberValue

    companion object {
        fun parse(value: ProviderJsonValue) = CloudflareDnsAnalyticsReport(
            rows = value.int("rows") ?: 0,
            totals = value.obj("totals")?.fields.orEmpty(),
            minimums = value.obj("min")?.fields.orEmpty(),
            maximums = value.obj("max")?.fields.orEmpty(),
            dataLag = value.double("data_lag") ?: 0.0,
        )
    }
}

/** Splits a list-shaped result into typed items, skipping malformed entries. */
internal inline fun <T> ProviderJsonValue.parseList(parser: (ProviderJsonValue) -> T): List<T> =
    (arrayValue ?: throw CloudflareOperationException.decoding()).mapNotNull { runCatching { parser(it) }.getOrNull() }
