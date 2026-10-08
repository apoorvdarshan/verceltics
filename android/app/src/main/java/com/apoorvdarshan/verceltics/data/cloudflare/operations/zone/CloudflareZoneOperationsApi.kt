package com.apoorvdarshan.verceltics.data.cloudflare.operations.zone

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflarePaginationGuard
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareResultInfo
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareJsonObject
import com.apoorvdarshan.verceltics.data.cloudflare.operations.isCloudflareOptionalProductUnavailable
import com.apoorvdarshan.verceltics.data.cloudflare.operations.requireCloudflareConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Zone, DNS, cache, analytics, zone-settings and security operations (iOS `CloudflareAPI` zone
 * methods, `CloudflareOperationsAPI.swift` and `CloudflareSecurityAPI.swift`).
 *
 * Every mutation verifies its [CloudflareMutationConfirmation] against the same resource id iOS
 * uses (see [CloudflareZoneResourceIds]) before a request is built.
 */
class CloudflareZoneOperationsApi(
    private val client: CloudflareRestClient,
    private val now: () -> Instant = Instant::now,
) {
    // MARK: Zone

    suspend fun fetchZone(zoneId: String): CloudflareZoneDetail =
        CloudflareZoneDetail.parse(client.result(get(zone(zoneId))))

    // MARK: DNS records

    suspend fun fetchDnsRecords(zoneId: String): List<CloudflareDnsRecord> =
        client.allPages(zone(zoneId) + "dns_records", perPage = 100)
            .mapNotNull { runCatching { CloudflareDnsRecord.parse(it) }.getOrNull() }

    suspend fun createDnsRecord(
        zoneId: String,
        record: CloudflareDnsRecordInput,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareDnsRecord {
        requireCloudflareConfirmation(confirmation, zoneId)
        validateCloudflareDnsRecord(record)
        return CloudflareDnsRecord.parse(
            client.result(CloudflareRestRequest.json(CloudflareHttpMethod.POST, zone(zoneId) + "dns_records", record.toJson())),
        )
    }

    suspend fun updateDnsRecord(
        zoneId: String,
        recordId: String,
        record: CloudflareDnsRecordInput,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareDnsRecord {
        requireCloudflareConfirmation(confirmation, recordId)
        validateCloudflareDnsRecord(record)
        return CloudflareDnsRecord.parse(
            client.result(
                CloudflareRestRequest.json(CloudflareHttpMethod.PUT, zone(zoneId) + listOf("dns_records", recordId), record.toJson()),
            ),
        )
    }

    suspend fun deleteDnsRecord(zoneId: String, recordId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, recordId)
        client.send(CloudflareRestRequest(CloudflareHttpMethod.DELETE, zone(zoneId) + listOf("dns_records", recordId)))
    }

    // MARK: Cache

    suspend fun purgeCache(zoneId: String, purge: CloudflareCachePurge, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, zoneId)
        validateCloudflareCachePurge(purge)
        client.result(CloudflareRestRequest.json(CloudflareHttpMethod.POST, zone(zoneId) + "purge_cache", purge.toJson()))
    }

    // MARK: Traffic analytics (GraphQL)

    /** Fetches the zone's dataset limits and picks the dataset/window to query (iOS `analyticsQueryPlan`). */
    suspend fun fetchAnalyticsPlan(zoneId: String, from: Instant, to: Instant): CloudflareAnalyticsQueryPlan {
        if (!from.isBefore(to)) throw CloudflareOperationException.invalidRequest("The analytics start date must be before the end date.")
        val root = graphQL(CloudflareZoneAnalyticsPlanner.settingsQuery, mapOf("zoneTag" to zoneId))
        val (hourly, daily) = CloudflareZoneAnalyticsPlanner.parseSettings(root)
        return CloudflareZoneAnalyticsPlanner.plan(hourly, daily, from, to, now())
    }

    suspend fun fetchZoneAnalytics(
        zoneId: String,
        from: Instant,
        to: Instant,
        plan: CloudflareAnalyticsQueryPlan? = null,
    ): CloudflareZoneAnalyticsSummary {
        if (!from.isBefore(to)) throw CloudflareOperationException.invalidRequest("The analytics start date must be before the end date.")
        val selected = plan ?: fetchAnalyticsPlan(zoneId, from, to)
        val root = graphQL(
            CloudflareZoneAnalyticsPlanner.trafficQuery(selected.granularity, selected.seriesLimit),
            CloudflareZoneAnalyticsPlanner.variables(zoneId, selected.from, selected.to, selected.granularity),
        )
        return CloudflareZoneAnalyticsPlanner.parseSummary(root, zoneId, from, to, selected)
    }

    suspend fun fetchZoneAnalyticsBreakdowns(
        zoneId: String,
        from: Instant,
        to: Instant,
        plan: CloudflareAnalyticsQueryPlan? = null,
    ): CloudflareZoneAnalyticsBreakdowns {
        if (!from.isBefore(to)) throw CloudflareOperationException.invalidRequest("The analytics start date must be before the end date.")
        val selected = plan ?: fetchAnalyticsPlan(zoneId, from, to)
        val root = graphQL(
            CloudflareZoneAnalyticsPlanner.breakdownQuery(selected.granularity),
            CloudflareZoneAnalyticsPlanner.variables(zoneId, selected.from, selected.to, selected.granularity),
        )
        return CloudflareZoneAnalyticsPlanner.parseBreakdowns(root)
    }

    // MARK: Zone operations

    suspend fun fetchDnssec(zoneId: String): CloudflareDnssecStatus =
        CloudflareDnssecStatus.parse(client.result(get(zone(zoneId) + "dnssec")))

    suspend fun enableDnssec(zoneId: String, confirmation: CloudflareMutationConfirmation): CloudflareDnssecStatus {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.dnssec(zoneId))
        return CloudflareDnssecStatus.parse(
            client.result(
                CloudflareRestRequest.json(CloudflareHttpMethod.PATCH, zone(zoneId) + "dnssec", cloudflareJsonObject("status" to "active")),
            ),
        )
    }

    suspend fun disableDnssec(zoneId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.dnssec(zoneId))
        client.send(CloudflareRestRequest(CloudflareHttpMethod.DELETE, zone(zoneId) + "dnssec"))
    }

    suspend fun fetchZoneSettings(zoneId: String): List<CloudflareZoneSetting> =
        client.result(get(zone(zoneId) + "settings")).parseList(CloudflareZoneSetting::parse)

    suspend fun updateZoneSetting(
        zoneId: String,
        settingId: String,
        value: ProviderJsonValue,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareZoneSetting {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.setting(zoneId, settingId))
        return CloudflareZoneSetting.parse(
            client.result(
                CloudflareRestRequest.json(
                    CloudflareHttpMethod.PATCH,
                    zone(zoneId) + listOf("settings", settingId),
                    cloudflareJsonObject("value" to value),
                ),
            ),
        )
    }

    suspend fun fetchDnsSettings(zoneId: String): CloudflareZoneDnsSettings =
        CloudflareZoneDnsSettings.parse(client.result(get(zone(zoneId) + "dns_settings")))

    suspend fun updateDnsSettings(
        zoneId: String,
        changes: Map<String, ProviderJsonValue>,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareZoneDnsSettings {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.dnsSettings(zoneId))
        if (changes.isEmpty()) throw CloudflareOperationException.invalidRequest("Change at least one DNS setting before saving.")
        return CloudflareZoneDnsSettings.parse(
            client.result(
                CloudflareRestRequest.json(CloudflareHttpMethod.PATCH, zone(zoneId) + "dns_settings", ProviderJsonValue.Obj(changes)),
            ),
        )
    }

    suspend fun requestActivationCheck(zoneId: String, confirmation: CloudflareMutationConfirmation): String? {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.activationCheck(zoneId))
        return client.result(CloudflareRestRequest(CloudflareHttpMethod.PUT, zone(zoneId) + "activation_check")).strictStr("id")
    }

    suspend fun fetchDnsUsage(zoneId: String): CloudflareDnsUsage =
        CloudflareDnsUsage.parse(client.result(get(zone(zoneId) + listOf("dns_records", "usage"))))

    suspend fun fetchDnsAnalytics(zoneId: String, since: Instant, until: Instant): CloudflareDnsAnalyticsReport {
        if (!since.isBefore(until)) {
            throw CloudflareOperationException.invalidRequest("The DNS analytics start date must be before the end date.")
        }
        return CloudflareDnsAnalyticsReport.parse(
            client.result(
                get(
                    zone(zoneId) + listOf("dns_analytics", "report"),
                    listOf(
                        "metrics" to "queryCount,uncachedCount,staleCount",
                        "since" to CloudflareDates.iso8601(since),
                        "until" to CloudflareDates.iso8601(until),
                        "limit" to "1",
                    ),
                ),
            ),
        )
    }

    // MARK: Security center

    suspend fun fetchSecurityItems(zoneId: String, category: CloudflareSecurityCategory): List<CloudflareSecurityItem> = when (category) {
        CloudflareSecurityCategory.WAF_RULESETS -> securityItems(zone(zoneId) + "rulesets", listOf("per_page" to "50"), category)
        CloudflareSecurityCategory.ACCESS_RULES ->
            securityItems(zone(zoneId) + listOf("firewall", "access_rules", "rules"), listOf("per_page" to "50"), category)
        CloudflareSecurityCategory.RATE_LIMITS -> securityItems(zone(zoneId) + "rate_limits", listOf("per_page" to "50"), category)
        CloudflareSecurityCategory.CERTIFICATES -> {
            val edge = securityItems(
                zone(zoneId) + listOf("ssl", "certificate_packs"),
                listOf("status" to "all", "per_page" to "50"),
                category,
            )
            val custom = try {
                securityItems(zone(zoneId) + "custom_certificates", listOf("per_page" to "50"), category)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isCloudflareOptionalProductUnavailable(error)) throw error
                emptyList()
            }
            edge + custom
        }
        CloudflareSecurityCategory.PAGE_SHIELD ->
            securityItems(zone(zoneId) + listOf("page_shield", "policies"), listOf("per_page" to "50"), category)
        CloudflareSecurityCategory.BOT_MANAGEMENT -> securityItems(zone(zoneId) + "bot_management", emptyList(), category)
        CloudflareSecurityCategory.API_SHIELD ->
            securityItems(zone(zoneId) + listOf("api_gateway", "configuration"), emptyList(), category)
    }

    suspend fun fetchSecurityLevel(zoneId: String): String? =
        securityResponse(get(zone(zoneId) + listOf("settings", "security_level"))).first.strictStr("value")

    suspend fun updateSecurityLevel(zoneId: String, level: String, confirmation: CloudflareMutationConfirmation): String? {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.securityLevel(zoneId))
        val (result, _) = securityResponse(
            CloudflareRestRequest.json(
                CloudflareHttpMethod.PATCH,
                zone(zoneId) + listOf("settings", "security_level"),
                cloudflareJsonObject("value" to level),
            ),
        )
        return result.strictStr("value")
    }

    suspend fun fetchRulesetRules(zoneId: String, rulesetId: String): List<CloudflareSecurityItem> =
        CloudflareSecurityParser.rulesetRules(securityResponse(get(zone(zoneId) + listOf("rulesets", rulesetId))).first)

    suspend fun createAccessRule(
        zoneId: String,
        target: String,
        value: String,
        mode: String,
        notes: String?,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareSecurityItem {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.accessRules(zoneId))
        val body = cloudflareJsonObject(
            "mode" to mode,
            "configuration" to linkedMapOf("target" to target, "value" to value),
            "notes" to notes?.trim()?.takeIf(String::isNotEmpty),
        )
        val (result, _) = securityResponse(
            CloudflareRestRequest.json(CloudflareHttpMethod.POST, zone(zoneId) + listOf("firewall", "access_rules", "rules"), body),
        )
        return CloudflareSecurityParser.item(result, CloudflareSecurityCategory.ACCESS_RULES, 0)
    }

    suspend fun deleteAccessRule(zoneId: String, ruleId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, CloudflareZoneResourceIds.accessRule(zoneId, ruleId))
        securityResponse(CloudflareRestRequest(CloudflareHttpMethod.DELETE, zone(zoneId) + listOf("firewall", "access_rules", "rules", ruleId)))
    }

    /** iOS `securityItems(path:query:category:)`: cursor- or page-paginated, guarded against loops. */
    private suspend fun securityItems(
        pathSegments: List<String>,
        query: List<Pair<String, String>>,
        category: CloudflareSecurityCategory,
    ): List<CloudflareSecurityItem> {
        var page = 1
        var cursor: String? = null
        val seenCursors = HashSet<String>()
        val items = mutableListOf<CloudflareSecurityItem>()
        val guard = CloudflarePaginationGuard()
        val paged = query.any { it.first == "per_page" } && category != CloudflareSecurityCategory.WAF_RULESETS
        while (true) {
            val requestQuery = query + when {
                cursor != null -> listOf("cursor" to cursor)
                paged -> listOf("page" to page.toString())
                else -> emptyList()
            }
            val (result, info) = securityResponse(get(pathSegments, requestQuery))
            val pageItems = CloudflareSecurityParser.items(result, category, items.size)
            guard.record(pageItems.size, if (pageItems.isEmpty()) null else pageItems.hashCode())
            items += pageItems
            val after = info?.cursor?.takeIf(String::isNotEmpty)
            if (after != null) {
                if (!seenCursors.add(after)) {
                    throw CloudflareOperationException.invalidRequest(
                        "Cloudflare repeated a security pagination cursor, so loading stopped safely.",
                    )
                }
                cursor = after
                continue
            }
            if (category != CloudflareSecurityCategory.WAF_RULESETS) {
                val totalPages = info?.totalPages
                if (totalPages != null && page < totalPages) {
                    page += 1
                    continue
                }
                val totalCount = info?.totalCount
                if (totalCount != null && items.size < totalCount && pageItems.isNotEmpty()) {
                    page += 1
                    continue
                }
            }
            break
        }
        return items
    }

    /** iOS `securityResponse`: an empty body is an empty result; envelope failures keep iOS copy. */
    private suspend fun securityResponse(request: CloudflareRestRequest): Pair<ProviderJsonValue, CloudflareResultInfo?> {
        val response = client.execute(request)
        CloudflareRestClient.throwForHttpFailure(response)
        if (response.size == 0) return ProviderJsonValue.Null to null
        val envelope = CloudflareRestClient.parseEnvelope(response)
        if (!envelope.success) {
            if (envelope.errors.isNotEmpty()) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
            throw CloudflareOperationException.requestFailed(response.statusCode, "Cloudflare rejected the security request.")
        }
        return (envelope.result ?: ProviderJsonValue.Null) to envelope.resultInfo
    }

    /** POST /graphql. GraphQL replies are `{data, errors}` rather than the REST envelope. */
    private suspend fun graphQL(query: String, variables: Map<String, String>): ProviderJsonValue {
        val body = cloudflareJsonObject("query" to query, "variables" to variables)
        val response = client.execute(CloudflareRestRequest.json(CloudflareHttpMethod.POST, listOf("graphql"), body))
        CloudflareRestClient.throwForHttpFailure(response)
        return try {
            ProviderJsonParser.parse(response.bodyBytes()).also {
                if (it !is ProviderJsonValue.Obj) throw CloudflareOperationException.decoding()
            }
        } catch (error: CloudflareOperationException) {
            throw error
        } catch (error: Exception) {
            throw CloudflareOperationException.decoding(error)
        }
    }

    private fun zone(zoneId: String): List<String> = listOf("zones", zoneId)

    private fun get(segments: List<String>, query: List<Pair<String, String>> = emptyList()) =
        CloudflareRestRequest(CloudflareHttpMethod.GET, segments, query)
}

/**
 * The resource each zone mutation's confirmation must name — the same ids iOS passes to
 * `CloudflareMutationConfirmation(confirmingResourceID:)`.
 */
object CloudflareZoneResourceIds {
    const val NEW_DNS_RECORD: String = "new-dns-record"
    const val CACHE_PURGE: String = "cache-purge"

    fun dnssec(zoneId: String) = "/zones/$zoneId/dnssec"
    fun setting(zoneId: String, settingId: String) = "/zones/$zoneId/settings/$settingId"
    fun dnsSettings(zoneId: String) = "/zones/$zoneId/dns_settings"
    fun activationCheck(zoneId: String) = "/zones/$zoneId/activation_check"
    fun securityLevel(zoneId: String) = "/zones/$zoneId/settings/security_level"
    fun accessRules(zoneId: String) = "/zones/$zoneId/firewall/access_rules/rules"
    fun accessRule(zoneId: String, ruleId: String) = "/zones/$zoneId/firewall/access_rules/rules/$ruleId"
}
