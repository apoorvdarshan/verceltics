package com.apoorvdarshan.verceltics.data.cloudflare.tools

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import com.apoorvdarshan.verceltics.data.network.ProviderJsonWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** iOS `CloudflareAccountSummary` with settings and parent organization (from `/accounts/{id}`). */
data class CloudflareAccountDetail(
    val id: String,
    val name: String,
    val type: String?,
    val createdOn: String?,
    val enforceTwoFactor: Boolean?,
    val abuseContactEmail: String?,
    val managedByParentOrganizationId: String?,
    val managedByParentOrganizationName: String?,
) {
    val isManaged: Boolean
        get() = managedByParentOrganizationId != null || managedByParentOrganizationName != null
}

data class CloudflarePermissionGrant(val read: Boolean?, val write: Boolean?) {
    val label: String
        get() = when {
            write == true -> "read/write"
            read == true -> "read"
            else -> "none"
        }
}

data class CloudflareAccountRole(
    val id: String,
    val name: String,
    val description: String?,
    val permissions: Map<String, CloudflarePermissionGrant>,
) {
    /** iOS `permissionSummary`: `dns records: read/write  ·  zones: read`. */
    val permissionSummary: String
        get() = permissions.entries.sortedBy { it.key }.joinToString("  ·  ") { (key, grant) ->
            "${key.replace('_', ' ')}: ${grant.label}"
        }
}

data class CloudflareAccountMember(
    val id: String,
    val email: String?,
    val status: String?,
    val user: User?,
    val roles: List<CloudflareAccountRole>,
    val policies: List<Policy>,
) {
    val resolvedEmail: String get() = user?.email ?: email ?: "Email unavailable"

    val displayName: String
        get() = listOfNotNull(user?.firstName, user?.lastName)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { resolvedEmail }

    val twoFactorEnabled: Boolean get() = user?.twoFactorAuthenticationEnabled == true

    data class User(
        val id: String?,
        val email: String?,
        val firstName: String?,
        val lastName: String?,
        val twoFactorAuthenticationEnabled: Boolean?,
    )

    data class Policy(
        val id: String,
        val access: String?,
        val permissionGroups: List<ProviderJsonValue>,
        val resourceGroups: List<ProviderJsonValue>,
    )
}

data class CloudflareAccountAuditEvent(
    val eventId: String?,
    val accountId: String?,
    val accountName: String?,
    val actionDescription: String?,
    val actionResult: String?,
    val actionTime: String?,
    val actionType: String?,
    val actorContext: String?,
    val actorEmail: String?,
    val actorId: String?,
    val actorIpAddress: String?,
    val actorTokenId: String?,
    val actorTokenName: String?,
    val actorType: String?,
    val rawCfRayId: String?,
    val rawMethod: String?,
    val rawStatusCode: Int?,
    val rawUri: String?,
    val rawUserAgent: String?,
    val resourceId: String?,
    val resourceProduct: String?,
    val resourceType: String?,
    val resourceScope: ProviderJsonValue?,
    val zoneId: String?,
    val zoneName: String?,
) {
    /** iOS falls back to `time|method|uri` when the event has no id. */
    val id: String
        get() = eventId ?: listOfNotNull(actionTime, rawMethod, rawUri).joinToString("|")

    val title: String
        get() = actionDescription
            ?: actionType?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            ?: "Cloudflare activity"

    val isFailure: Boolean get() = actionResult.equals("failure", ignoreCase = true)
}

/** The four independently-failing sections of iOS `CloudflareAccountOperationsViewModel`. */
data class CloudflareAccountOperationsSnapshot(
    val account: CloudflareAccountDetail?,
    val members: List<CloudflareAccountMember>,
    val roles: List<CloudflareAccountRole>,
    val auditEvents: List<CloudflareAccountAuditEvent>,
    val accountError: String?,
    val membersError: String?,
    val rolesError: String?,
    val auditError: String?,
) {
    val allSucceeded: Boolean
        get() = accountError == null && membersError == null && rolesError == null && auditError == null
}

/**
 * Port of iOS `CloudflarePaginationGuard`: bounds every collection walk so a malformed or repeating
 * response cannot keep the device issuing requests or growing a list indefinitely.
 */
class CloudflarePaginationGuard(
    private val maximumPages: Int = MAXIMUM_PAGES,
    private val maximumItems: Int = MAXIMUM_ITEMS,
) {
    private var pages = 0
    private var items = 0
    private val seenSignatures = HashSet<Int>()

    fun record(batchCount: Int, signature: Int?) {
        pages += 1
        items += batchCount
        if (pages > maximumPages || items > maximumItems) {
            throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "Cloudflare returned too many paginated results. Narrow the request and try again.",
            )
        }
        if (signature != null && batchCount > 0 && !seenSignatures.add(signature)) {
            throw CloudflareToolsException(
                CloudflareToolsFailureKind.INVALID_RESPONSE,
                "Cloudflare repeated a results page, so loading stopped safely.",
            )
        }
    }

    companion object {
        const val MAXIMUM_PAGES = 500
        const val MAXIMUM_ITEMS = 100_000
    }
}

/** Lenient decoders mirroring the iOS `Decodable` defaults for account operations. */
object CloudflareAccountOperationsParser {
    fun accountDetail(value: ProviderJsonValue): CloudflareAccountDetail {
        val id = value.string("id") ?: malformed("The account did not include an id.")
        val settings = value["settings"]
        val managedBy = value["managed_by"]
        return CloudflareAccountDetail(
            id = id,
            name = value.string("name") ?: "Unnamed account",
            type = value.string("type"),
            createdOn = value.string("created_on"),
            enforceTwoFactor = settings?.get("enforce_twofactor")?.let { it as? ProviderJsonValue.Bool }?.value,
            abuseContactEmail = settings?.string("abuse_contact_email"),
            managedByParentOrganizationId = managedBy?.string("parent_org_id"),
            managedByParentOrganizationName = managedBy?.string("parent_org_name"),
        )
    }

    fun member(value: ProviderJsonValue): CloudflareAccountMember {
        val user = value["user"]?.takeIf { it is ProviderJsonValue.Obj }?.let { user ->
            CloudflareAccountMember.User(
                id = user.string("id"),
                email = user.string("email"),
                firstName = user.string("first_name"),
                lastName = user.string("last_name"),
                twoFactorAuthenticationEnabled = (user["two_factor_authentication_enabled"] as? ProviderJsonValue.Bool)?.value,
            )
        }
        return CloudflareAccountMember(
            id = value.string("id") ?: "unknown-member",
            email = value.string("email"),
            status = value.string("status"),
            user = user,
            roles = value["roles"]?.arrayValue.orEmpty().filterIsInstance<ProviderJsonValue.Obj>().map(::role),
            policies = value["policies"]?.arrayValue.orEmpty().filterIsInstance<ProviderJsonValue.Obj>().map { policy ->
                CloudflareAccountMember.Policy(
                    id = policy.string("id") ?: "unknown-policy",
                    access = policy.string("access"),
                    permissionGroups = policy["permission_groups"]?.arrayValue.orEmpty(),
                    resourceGroups = policy["resource_groups"]?.arrayValue.orEmpty(),
                )
            },
        )
    }

    fun role(value: ProviderJsonValue): CloudflareAccountRole = CloudflareAccountRole(
        id = value.string("id") ?: "unknown-role",
        name = value.string("name") ?: "Unnamed role",
        description = value.string("description"),
        permissions = value["permissions"]?.objectValue.orEmpty().mapNotNull { (key, grant) ->
            if (grant !is ProviderJsonValue.Obj) return@mapNotNull null
            key to CloudflarePermissionGrant(
                read = (grant["read"] as? ProviderJsonValue.Bool)?.value,
                write = (grant["write"] as? ProviderJsonValue.Bool)?.value,
            )
        }.toMap(),
    )

    fun auditEvent(value: ProviderJsonValue): CloudflareAccountAuditEvent {
        val account = value["account"]
        val action = value["action"]
        val actor = value["actor"]
        val raw = value["raw"]
        val resource = value["resource"]
        val zone = value["zone"]
        return CloudflareAccountAuditEvent(
            eventId = value.string("id"),
            accountId = account?.string("id"),
            accountName = account?.string("name"),
            actionDescription = action?.string("description"),
            actionResult = action?.string("result"),
            actionTime = action?.string("time"),
            actionType = action?.string("type"),
            actorContext = actor?.string("context"),
            actorEmail = actor?.string("email"),
            actorId = actor?.string("id"),
            actorIpAddress = actor?.string("ip_address"),
            actorTokenId = actor?.string("token_id"),
            actorTokenName = actor?.string("token_name"),
            actorType = actor?.string("type"),
            rawCfRayId = raw?.string("cf_ray_id"),
            rawMethod = raw?.string("method"),
            rawStatusCode = (raw?.get("status_code") as? ProviderJsonValue.Num)?.decimal?.toInt(),
            rawUri = raw?.string("uri"),
            rawUserAgent = raw?.string("user_agent"),
            resourceId = resource?.string("id"),
            resourceProduct = resource?.string("product"),
            resourceType = resource?.string("type"),
            resourceScope = resource?.get("scope")?.takeUnless { it.isNull },
            zoneId = zone?.string("id"),
            zoneName = zone?.string("name"),
        )
    }

    private fun ProviderJsonValue.string(key: String): String? = (this[key] as? ProviderJsonValue.Str)?.value

    private fun malformed(message: String): Nothing =
        throw CloudflareToolsException(CloudflareToolsFailureKind.INVALID_RESPONSE, message)
}

/** iOS `CloudflareJSONValue.operationsDisplayText` and shared formatting helpers. */
object CloudflareToolsFormat {
    fun displayText(value: ProviderJsonValue): String = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> number(value.decimal)
        is ProviderJsonValue.Bool -> if (value.value) "On" else "Off"
        is ProviderJsonValue.Obj -> value.fields.entries.sortedBy { it.key }.joinToString(", ") { (key, child) ->
            "${key.replace('_', ' ')}: ${displayText(child)}"
        }
        is ProviderJsonValue.Arr -> value.items.joinToString(", ") { displayText(it) }
        ProviderJsonValue.Null -> "Not set"
    }

    fun number(value: BigDecimal): String {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val integral = value.stripTrailingZeros().scale() <= 0
        val format = DecimalFormat(if (integral) "#,##0" else "#,##0.##", symbols)
        format.roundingMode = RoundingMode.HALF_EVEN
        return format.format(value)
    }

    fun count(value: Int): String = DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(value)

    /** Parses Cloudflare's RFC 3339 timestamps; null when absent or malformed. */
    fun instant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
    }

    fun dateTime(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? = instant(value)?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(zone)
            .format(it)
    }

    fun compactJson(value: ProviderJsonValue): String = ProviderJsonWriter.write(value)

    fun titleCase(value: String): String = value.split(' ', '_').filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
}
