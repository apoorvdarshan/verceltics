package com.apoorvdarshan.verceltics.data.cloudflare.operations.worker

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareHttpMethod
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareMutationConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflarePaginationGuard
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestClient
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareRestRequest
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.cloudflareJsonObject
import com.apoorvdarshan.verceltics.data.cloudflare.operations.requireCloudflareConfirmation
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

/**
 * Port of the Worker parts of iOS `CloudflareAPI` plus `CloudflareWorkerAdvancedAPI`.
 *
 * Every mutation takes a [CloudflareMutationConfirmation] created by the app's confirmation dialog
 * and verifies it names the exact resource being changed before any request is built.
 */
class CloudflareWorkerOperationsApi(private val client: CloudflareRestClient) {
    // MARK: Scripts and deployments

    suspend fun fetchWorkerScripts(accountId: String): List<CloudflareWorkerScriptDetail> =
        client.result(get(accountScripts(accountId))).arrayValue.orEmpty()
            .mapNotNull(CloudflareWorkerScriptDetail::parse)

    /** iOS detail refresh: reloads the script list and finds this Worker in it. */
    suspend fun fetchWorkerScript(accountId: String, scriptName: String): CloudflareWorkerScriptDetail =
        fetchWorkerScripts(accountId).firstOrNull { it.id == scriptName }
            ?: throw CloudflareOperationException.requestFailed(404, "Cloudflare did not return Worker $scriptName.")

    suspend fun fetchDeployments(accountId: String, scriptName: String): List<CloudflareWorkerDeploymentInfo> =
        client.result(get(script(accountId, scriptName) + "deployments"))
            .arr("deployments").mapNotNull(CloudflareWorkerDeploymentInfo::parse)

    suspend fun fetchDeployment(accountId: String, scriptName: String, deploymentId: String): CloudflareWorkerDeploymentInfo =
        CloudflareWorkerDeploymentInfo.parse(
            client.result(get(script(accountId, scriptName) + listOf("deployments", deploymentId))),
        ) ?: throw CloudflareOperationException.decoding()

    /** `DELETE .../workers/scripts/{name}`; confirmation must name the script. */
    suspend fun deleteWorker(accountId: String, scriptName: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, scriptName)
        client.send(CloudflareRestRequest(CloudflareHttpMethod.DELETE, script(accountId, scriptName)))
    }

    /** `DELETE .../deployments/{id}`; confirmation must name the deployment. */
    suspend fun deleteDeployment(
        accountId: String,
        scriptName: String,
        deploymentId: String,
        confirmation: CloudflareMutationConfirmation,
    ) {
        requireCloudflareConfirmation(confirmation, deploymentId)
        client.send(
            CloudflareRestRequest(CloudflareHttpMethod.DELETE, script(accountId, scriptName) + listOf("deployments", deploymentId)),
        )
    }

    // MARK: Versions

    suspend fun fetchVersions(accountId: String, scriptName: String): List<CloudflareWorkerVersionInfo> =
        client.result(get(script(accountId, scriptName) + "versions", listOf("deployable" to "true")))
            .arr("items").mapNotNull(CloudflareWorkerVersionInfo::parse)

    suspend fun fetchVersion(accountId: String, scriptName: String, versionId: String): CloudflareWorkerVersionDetailInfo =
        CloudflareWorkerVersionDetailInfo.parse(
            client.result(get(script(accountId, scriptName) + listOf("versions", versionId))),
        ) ?: throw CloudflareOperationException.decoding()

    /**
     * iOS `deployWorkerVersion`: a percentage deployment sending 100% of traffic to [versionId]. Used
     * both to deploy a new version and to roll back to an earlier one. Confirmation names the version.
     */
    suspend fun deployVersion(
        accountId: String,
        scriptName: String,
        versionId: String,
        message: String?,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareWorkerDeploymentInfo {
        requireCloudflareConfirmation(confirmation, versionId)
        val trimmed = message?.trim()?.takeIf(String::isNotEmpty)
        val annotations = if (trimmed == null) {
            cloudflareJsonObject("workers/triggered_by" to TRIGGERED_BY)
        } else {
            cloudflareJsonObject("workers/message" to trimmed, "workers/triggered_by" to TRIGGERED_BY)
        }
        val body = cloudflareJsonObject(
            "strategy" to "percentage",
            "versions" to listOf(mapOf("version_id" to versionId, "percentage" to 100.0)),
            "annotations" to annotations,
        )
        return CloudflareWorkerDeploymentInfo.parse(
            client.result(json(CloudflareHttpMethod.POST, script(accountId, scriptName) + "deployments", body)),
        ) ?: throw CloudflareOperationException.decoding()
    }

    // MARK: Secrets

    suspend fun fetchSecrets(accountId: String, scriptName: String): List<CloudflareWorkerSecretInfo> =
        client.result(get(script(accountId, scriptName) + "secrets")).arrayValue.orEmpty()
            .mapNotNull(CloudflareWorkerSecretInfo::parse)

    /** `PUT .../secrets` with a `secret_text` binding; confirmation names the secret. */
    suspend fun putSecret(
        accountId: String,
        scriptName: String,
        name: String,
        value: CloudflareWorkerSecretText,
        confirmation: CloudflareMutationConfirmation,
    ) {
        val validName = validateSecretName(name)
        requireCloudflareConfirmation(confirmation, validName)
        if (value.isEmpty) throw CloudflareOperationException.invalidRequest("Enter a secret value.")
        val body = value.use { text ->
            cloudflareJsonObject("name" to validName, "text" to text, "type" to "secret_text")
        }
        sendAllowingEmptyBody(json(CloudflareHttpMethod.PUT, script(accountId, scriptName) + "secrets", body))
    }

    suspend fun deleteSecret(
        accountId: String,
        scriptName: String,
        name: String,
        confirmation: CloudflareMutationConfirmation,
    ) {
        requireCloudflareConfirmation(confirmation, name)
        sendAllowingEmptyBody(
            CloudflareRestRequest(CloudflareHttpMethod.DELETE, script(accountId, scriptName) + listOf("secrets", name)),
        )
    }

    // MARK: Schedules

    suspend fun fetchSchedules(accountId: String, scriptName: String): List<CloudflareWorkerScheduleInfo> =
        CloudflareWorkerScheduleInfo.parseList(client.result(get(script(accountId, scriptName) + "schedules")))

    /** Replaces every cron trigger (`PUT .../schedules`); confirmation names the script. */
    suspend fun updateSchedules(
        accountId: String,
        scriptName: String,
        cronExpressions: List<String>,
        confirmation: CloudflareMutationConfirmation,
    ): List<CloudflareWorkerScheduleInfo> {
        requireCloudflareConfirmation(confirmation, scriptName)
        cronExpressions.forEach(::validateCronExpression)
        val body = ProviderJsonValue.from(cronExpressions.map { mapOf("cron" to it) })
        return CloudflareWorkerScheduleInfo.parseList(
            client.result(json(CloudflareHttpMethod.PUT, script(accountId, scriptName) + "schedules", body)),
        )
    }

    // MARK: Custom domains and workers.dev

    /** iOS `fetchWorkerDomains`: pages by `total_pages`, then `total_count`, then a short page. */
    suspend fun fetchDomains(accountId: String): List<CloudflareWorkerDomainInfo> {
        val guard = CloudflarePaginationGuard()
        val domains = mutableListOf<CloudflareWorkerDomainInfo>()
        var page = 1
        while (true) {
            val envelope = client.envelope(
                get(accountDomains(accountId), listOf("page" to page.toString(), "per_page" to DOMAIN_PAGE_SIZE.toString())),
            )
            val batch = envelope.result?.arrayValue.orEmpty()
            guard.record(batch.size, if (batch.isEmpty()) null else batch.hashCode())
            domains += batch.mapNotNull(CloudflareWorkerDomainInfo::parse)
            val info = envelope.resultInfo
            when {
                info?.totalPages != null -> if (page >= info.totalPages) break
                info?.totalCount != null -> if (domains.size >= info.totalCount || batch.isEmpty()) break
                batch.size < DOMAIN_PAGE_SIZE -> break
            }
            page += 1
        }
        return domains
    }

    /** Domains served by [scriptName] (iOS filters case-insensitively by `service`). */
    suspend fun fetchDomains(accountId: String, scriptName: String): List<CloudflareWorkerDomainInfo> =
        fetchDomains(accountId).filter { it.service.equals(scriptName, ignoreCase = true) }

    /** `PUT .../workers/domains`; confirmation names the hostname. */
    suspend fun attachDomain(
        accountId: String,
        hostname: String,
        scriptName: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareWorkerDomainInfo {
        val validHostname = normalizeHostname(hostname)
        requireCloudflareConfirmation(confirmation, validHostname)
        val body = cloudflareJsonObject("hostname" to validHostname, "service" to scriptName, "environment" to "production")
        return CloudflareWorkerDomainInfo.parse(client.result(json(CloudflareHttpMethod.PUT, accountDomains(accountId), body)))
            ?: throw CloudflareOperationException.decoding()
    }

    /** `DELETE .../workers/domains/{id}`; confirmation names the domain record id. */
    suspend fun detachDomain(accountId: String, domainId: String, confirmation: CloudflareMutationConfirmation) {
        requireCloudflareConfirmation(confirmation, domainId)
        sendAllowingEmptyBody(CloudflareRestRequest(CloudflareHttpMethod.DELETE, accountDomains(accountId) + domainId))
    }

    suspend fun fetchAccountSubdomain(accountId: String): String =
        client.result(get(listOf("accounts", accountId, "workers", "subdomain"))).strictStr("subdomain")
            ?: throw CloudflareOperationException.decoding()

    suspend fun fetchWorkerSubdomain(accountId: String, scriptName: String): CloudflareWorkerSubdomainSettings =
        CloudflareWorkerSubdomainSettings.parse(client.result(get(script(accountId, scriptName) + "subdomain")))

    /** `POST .../subdomain`; confirmation names the script. */
    suspend fun updateWorkerSubdomain(
        accountId: String,
        scriptName: String,
        enabled: Boolean,
        previewsEnabled: Boolean,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareWorkerSubdomainSettings {
        requireCloudflareConfirmation(confirmation, scriptName)
        val body = cloudflareJsonObject("enabled" to enabled, "previews_enabled" to previewsEnabled)
        return CloudflareWorkerSubdomainSettings.parse(
            client.result(json(CloudflareHttpMethod.POST, script(accountId, scriptName) + "subdomain", body)),
        )
    }

    // MARK: Settings

    suspend fun fetchScriptSettings(accountId: String, scriptName: String): CloudflareWorkerScriptSettingsInfo =
        CloudflareWorkerScriptSettingsInfo.parse(client.result(get(script(accountId, scriptName) + "settings")))

    suspend fun fetchScriptLevelSettings(accountId: String, scriptName: String): CloudflareWorkerScriptLevelSettingsInfo =
        CloudflareWorkerScriptLevelSettingsInfo.parse(client.result(get(script(accountId, scriptName) + "script-settings")))

    /** `PATCH .../script-settings` observability block exactly as iOS sends it; confirmation names the script. */
    suspend fun updateObservability(
        accountId: String,
        scriptName: String,
        enabled: Boolean,
        logsEnabled: Boolean,
        tracesEnabled: Boolean,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareWorkerScriptLevelSettingsInfo {
        requireCloudflareConfirmation(confirmation, scriptName)
        val body = cloudflareJsonObject(
            "observability" to mapOf(
                "enabled" to enabled,
                "logs" to mapOf("enabled" to logsEnabled, "invocation_logs" to logsEnabled),
                "traces" to mapOf("enabled" to tracesEnabled),
            ),
        )
        return CloudflareWorkerScriptLevelSettingsInfo.parse(
            client.result(json(CloudflareHttpMethod.PATCH, script(accountId, scriptName) + "script-settings", body)),
        )
    }

    // MARK: Live tail

    suspend fun fetchTails(accountId: String, scriptName: String): List<CloudflareWorkerTailSession> =
        client.result(get(script(accountId, scriptName) + "tails")).arrayValue.orEmpty()
            .mapNotNull(CloudflareWorkerTailSession::parse)

    /** `POST .../tails` creates a temporary tail session; confirmation names the script. */
    suspend fun createTail(
        accountId: String,
        scriptName: String,
        confirmation: CloudflareMutationConfirmation,
    ): CloudflareWorkerTailSession {
        requireCloudflareConfirmation(confirmation, scriptName)
        return CloudflareWorkerTailSession.parse(
            client.result(json(CloudflareHttpMethod.POST, script(accountId, scriptName) + "tails", cloudflareJsonObject())),
        ) ?: throw CloudflareOperationException.decoding()
    }

    /**
     * Closes a tail session this app created (`DELETE .../tails/{id}`). This only cleans up the
     * temporary session started behind the live-logs confirmation, so it needs no second prompt.
     */
    suspend fun deleteTail(accountId: String, scriptName: String, tail: CloudflareWorkerTailSession) {
        sendAllowingEmptyBody(
            CloudflareRestRequest(CloudflareHttpMethod.DELETE, script(accountId, scriptName) + listOf("tails", tail.id)),
        )
    }

    // MARK: Source

    /** `GET .../content/v2`: pretty JSON when the body is JSON, otherwise UTF-8 text (iOS `prettyPrintedBody`). */
    suspend fun fetchContent(accountId: String, scriptName: String): CloudflareWorkerContent {
        val response = client.executeSuccessful(
            CloudflareRestRequest(
                CloudflareHttpMethod.GET,
                script(accountId, scriptName) + listOf("content", "v2"),
                accept = "*/*",
            ),
        )
        val bytes = response.bodyBytes()
        return try {
            formatContent(bytes, response.header("content-type"))
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun sendAllowingEmptyBody(request: CloudflareRestRequest) {
        val response = client.execute(request)
        CloudflareRestClient.throwForHttpFailure(response)
        if (response.size == 0) return
        val envelope = runCatching { CloudflareRestClient.parseEnvelope(response) }.getOrNull() ?: return
        if (!envelope.success) throw CloudflareOperationException.api(envelope.errors, response.statusCode)
    }

    private fun get(segments: List<String>, query: List<Pair<String, String>> = emptyList()) =
        CloudflareRestRequest(CloudflareHttpMethod.GET, segments, query)

    private fun json(method: CloudflareHttpMethod, segments: List<String>, body: ProviderJsonValue) =
        CloudflareRestRequest.json(method, segments, body)

    private fun accountScripts(accountId: String) = listOf("accounts", accountId, "workers", "scripts")

    private fun script(accountId: String, scriptName: String) = accountScripts(accountId) + scriptName

    private fun accountDomains(accountId: String) = listOf("accounts", accountId, "workers", "domains")

    companion object {
        const val TRIGGERED_BY: String = "verceltics"
        const val DEFAULT_DEPLOY_MESSAGE: String = "Deployed from Verceltics"
        private const val DOMAIN_PAGE_SIZE = 50
        const val MAXIMUM_DISPLAYED_CONTENT_CHARACTERS: Int = 512 * 1_024

        /** iOS secret editor: a non-empty, trimmed binding name. */
        fun validateSecretName(name: String): String {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) throw CloudflareOperationException.invalidRequest("Enter a secret binding name.")
            if (trimmed.length > 256 || trimmed.any { it.isWhitespace() || it.isISOControl() }) {
                throw CloudflareOperationException.invalidRequest("Secret binding names cannot contain spaces.")
            }
            return trimmed
        }

        /** iOS schedule editor: exactly five whitespace-separated cron fields. */
        fun isValidCronExpression(cron: String): Boolean {
            val fields = cron.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            return fields.size == 5 && cron.length <= 256 && cron.none { it.isISOControl() }
        }

        fun validateCronExpression(cron: String) {
            if (!isValidCronExpression(cron)) {
                throw CloudflareOperationException.invalidRequest("Cron expressions need five fields, such as */30 * * * *.")
            }
        }

        /** iOS schedule add: existing expressions plus the new one, de-duplicated and sorted. */
        fun schedulesAdding(existing: List<CloudflareWorkerScheduleInfo>, cron: String): List<String> =
            (existing.map(CloudflareWorkerScheduleInfo::cron) + cron.trim()).toSet().sorted()

        fun schedulesRemoving(existing: List<CloudflareWorkerScheduleInfo>, cron: String): List<String> =
            existing.map(CloudflareWorkerScheduleInfo::cron).filter { it != cron }

        /** iOS domain editor: lower-cased, trimmed, contains a dot and no spaces. */
        fun isValidHostname(hostname: String): Boolean {
            val normalized = hostname.trim().lowercase()
            return normalized.contains('.') && normalized.none { it.isWhitespace() } &&
                normalized.length <= 253 && !normalized.contains('/') && normalized.none { it.isISOControl() }
        }

        fun normalizeHostname(hostname: String): String {
            if (!isValidHostname(hostname)) {
                throw CloudflareOperationException.invalidRequest("Enter a hostname such as api.example.com.")
            }
            return hostname.trim().lowercase()
        }

        fun formatContent(bytes: ByteArray, contentType: String?): CloudflareWorkerContent {
            val decoded = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
            val pretty = decoded?.let { text ->
                runCatching { ProviderJsonParser.parse(text).prettyJson() }.getOrNull() ?: text
            }
            val display = if (pretty.isNullOrEmpty()) {
                "Binary or multipart Worker content (${"%,d".format(bytes.size)} bytes)."
            } else {
                pretty
            }
            val truncated = display.length > MAXIMUM_DISPLAYED_CONTENT_CHARACTERS
            return CloudflareWorkerContent(
                text = if (truncated) display.take(MAXIMUM_DISPLAYED_CONTENT_CHARACTERS) else display,
                contentType = contentType,
                byteCount = bytes.size,
                truncatedForDisplay = truncated,
            )
        }
    }
}

/**
 * A Worker secret value in transit. It is held as characters that are wiped after the request body
 * is built, and it never appears in `toString()`.
 */
class CloudflareWorkerSecretText(value: CharArray) {
    private val characters = value.copyOf()
    private var wiped = false

    val isEmpty: Boolean @Synchronized get() = wiped || characters.isEmpty()

    /** Exposes the value once to [block], then wipes it. */
    @Synchronized
    fun <T> use(block: (String) -> T): T {
        check(!wiped) { "The secret value was already used." }
        return try {
            block(String(characters))
        } finally {
            wipe()
        }
    }

    @Synchronized
    fun wipe() {
        characters.fill('\u0000')
        wiped = true
    }

    override fun toString(): String = "CloudflareWorkerSecretText(<redacted>)"

    companion object {
        fun of(value: String): CloudflareWorkerSecretText = CloudflareWorkerSecretText(value.toCharArray())
    }
}
