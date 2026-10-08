package com.apoorvdarshan.verceltics.data.vercel

import com.apoorvdarshan.verceltics.data.network.ProviderJsonException
import com.apoorvdarshan.verceltics.data.network.ProviderJsonParser
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure-Kotlin decoding for Vercel project, domain, deployment and event resources.
 *
 * Runs on the JVM without Android's stubbed JSON APIs so every field mapping is unit tested with
 * realistic fixtures. Required identifiers are validated; every display field is optional so a
 * schema addition on Vercel's side never blanks the dashboard.
 */
object VercelResourceDecoder {
    private const val MAX_EVENT_MESSAGE_CHARACTERS = 4_000
    private const val MAX_LIST_ITEMS = 2_000

    fun projectsPage(bytes: ByteArray): VercelProjectsPage {
        val root = parseObject(bytes, "projects")
        val projects = root["projects"]?.arrayValue
            ?: throw VercelResponseFormatException("Vercel returned no projects collection.")
        return VercelProjectsPage(
            projects = projects.take(MAX_LIST_ITEMS).map(::project),
            nextCursor = text(root["pagination"]?.get("next")),
        )
    }

    fun project(bytes: ByteArray): VercelProject = project(parseObject(bytes, "project"))

    fun projectDomains(bytes: ByteArray): List<VercelProjectDomain> {
        val root = parseObject(bytes, "project domains")
        val domains = root["domains"]?.arrayValue
            ?: throw VercelResponseFormatException("Vercel returned no domains collection.")
        return domains.take(MAX_LIST_ITEMS).mapNotNull { value ->
            val name = text(value["name"]) ?: return@mapNotNull null
            VercelProjectDomain(
                name = name,
                verified = value["verified"]?.booleanValue,
                redirect = text(value["redirect"]),
            )
        }
    }

    fun deployments(bytes: ByteArray): List<VercelDeployment> {
        val root = parseObject(bytes, "deployments")
        val deployments = root["deployments"]?.arrayValue
            ?: throw VercelResponseFormatException("Vercel returned no deployments collection.")
        return deployments.take(MAX_LIST_ITEMS).mapNotNull { value ->
            if (value !is ProviderJsonValue.Obj) return@mapNotNull null
            deployment(value)
        }
    }

    /** Accepts the documented array, `null`, or an `{ "events": [...] }` envelope. */
    fun deploymentEvents(bytes: ByteArray): List<VercelDeploymentEvent> {
        val root = parse(bytes, "deployment events")
        val events = when (root) {
            is ProviderJsonValue.Arr -> root.items
            is ProviderJsonValue.Null -> emptyList()
            is ProviderJsonValue.Obj -> root["events"]?.arrayValue
                ?: throw VercelResponseFormatException("Vercel returned no deployment events.")
            else -> throw VercelResponseFormatException("Vercel deployment events are malformed.")
        }
        return events.take(MAX_LIST_ITEMS).mapNotNull { value ->
            if (value !is ProviderJsonValue.Obj) return@mapNotNull null
            deploymentEvent(value)
        }
    }

    internal fun project(value: ProviderJsonValue): VercelProject {
        if (value !is ProviderJsonValue.Obj) {
            throw VercelResponseFormatException("Vercel project is malformed.")
        }
        val id = text(value["id"]) ?: throw VercelResponseFormatException("Vercel project id is missing.")
        val name = text(value["name"]) ?: throw VercelResponseFormatException("Vercel project name is missing.")
        val accountId = text(value["accountId"])
        val explicitTeamId = text(value["teamId"])
        return VercelProject(
            id = id,
            name = name,
            framework = text(value["framework"]),
            createdAtMillis = long(value["createdAt"]),
            updatedAtMillis = long(value["updatedAt"]),
            teamId = explicitTeamId ?: accountId?.takeIf { it.startsWith("team_") },
            accountId = accountId,
            latestDeployments = value["latestDeployments"]?.arrayValue.orEmpty().mapNotNull { deployment ->
                if (deployment !is ProviderJsonValue.Obj) return@mapNotNull null
                VercelProjectDeployment(
                    createdAtMillis = normalizeVercelTimestamp(long(deployment["createdAt"])),
                    aliases = strings(deployment["alias"]),
                    commitMessage = text(deployment["meta"]?.get("githubCommitMessage")),
                )
            },
            productionAliases = strings(value["targets"]?.get("production")?.get("alias")),
            link = value["link"]?.takeIf { it is ProviderJsonValue.Obj }?.let { link ->
                VercelGitLink(org = text(link["org"]), repo = text(link["repo"]))
            },
            aliasDomains = value["alias"]?.arrayValue.orEmpty().mapNotNull { alias ->
                when (alias) {
                    is ProviderJsonValue.Str -> alias.value.trim().takeIf(String::isNotEmpty)
                    is ProviderJsonValue.Obj -> text(alias["domain"])
                    else -> null
                }
            },
            customEnvironments = value["customEnvironments"]?.arrayValue.orEmpty().mapNotNull { environment ->
                if (environment !is ProviderJsonValue.Obj) return@mapNotNull null
                VercelCustomEnvironment(
                    type = text(environment["type"]),
                    domains = environment["domains"]?.arrayValue.orEmpty().mapNotNull { domain ->
                        val domainName = text(domain["name"]) ?: return@mapNotNull null
                        VercelEnvironmentDomain(name = domainName, redirect = text(domain["redirect"]))
                    },
                    currentDeploymentAliases = strings(environment["currentDeploymentAliases"]),
                )
            },
        )
    }

    internal fun deployment(value: ProviderJsonValue.Obj): VercelDeployment {
        val meta = value["meta"]
        val creator = value["creator"]
        return VercelDeployment(
            uid = text(value["uid"]) ?: text(value["id"]),
            name = text(value["name"]),
            url = text(value["url"]),
            inspectorUrl = text(value["inspectorUrl"]),
            state = text(value["state"]),
            readyState = text(value["readyState"]),
            target = text(value["target"]),
            createdAtMillis = normalizeVercelTimestamp(long(value["createdAt"]) ?: long(value["created"])),
            meta = VercelDeploymentMeta(
                commitMessage = text(meta?.get("githubCommitMessage")),
                commitRef = text(meta?.get("githubCommitRef")),
                commitSha = text(meta?.get("githubCommitSha")),
                commitAuthorName = text(meta?.get("githubCommitAuthorName")),
                org = text(meta?.get("githubOrg")),
                repo = text(meta?.get("githubRepo")),
            ),
            creatorUsername = text(creator?.get("username")),
            creatorEmail = text(creator?.get("email")),
        )
    }

    internal fun deploymentEvent(value: ProviderJsonValue.Obj): VercelDeploymentEvent {
        val type = text(value["type"]) ?: "event"
        val payload = value["payload"]?.takeIf { it is ProviderJsonValue.Obj }
        val statusCode = payload?.get("statusCode")?.let(::displayValue)
        return VercelDeploymentEvent(
            type = type,
            createdAtMillis = normalizeVercelTimestamp(long(value["created"])) ?: 0L,
            message = eventMessage(type, payload, statusCode).take(MAX_EVENT_MESSAGE_CHARACTERS),
            statusCode = statusCode,
        )
    }

    /** Same precedence as iOS `DeploymentEvent.message`: text, info fields, HTTP status, type. */
    private fun eventMessage(type: String, payload: ProviderJsonValue?, statusCode: String?): String {
        val textValue = (payload?.get("text") as? ProviderJsonValue.Str)?.value
        if (!textValue.isNullOrEmpty()) return textValue.trim()
        val info = payload?.get("info")
        if (info is ProviderJsonValue.Obj) {
            val joined = listOf("step", "type", "name", "entrypoint", "readyState")
                .mapNotNull { key -> (info[key] as? ProviderJsonValue.Str)?.value?.takeIf(String::isNotEmpty) }
                .joinToString(" ")
            if (joined.isNotEmpty()) return joined
        }
        if (statusCode != null) return "HTTP $statusCode"
        return type
    }

    private fun displayValue(value: ProviderJsonValue): String? = when (value) {
        is ProviderJsonValue.Str -> value.value
        is ProviderJsonValue.Num -> value.decimal.setScale(0, RoundingMode.HALF_UP).toPlainString()
        is ProviderJsonValue.Bool -> value.value.toString()
        is ProviderJsonValue.Arr -> value.items.mapNotNull(::displayValue).joinToString(", ")
        else -> null
    }

    private fun text(value: ProviderJsonValue?): String? = when (value) {
        is ProviderJsonValue.Str -> value.value.trim().takeIf(String::isNotEmpty)
        is ProviderJsonValue.Num -> value.text
        else -> null
    }

    private fun long(value: ProviderJsonValue?): Long? {
        val decimal: BigDecimal = value?.decimalValue ?: return null
        return runCatching { decimal.toBigInteger().longValueExact() }.getOrNull()
    }

    private fun strings(value: ProviderJsonValue?): List<String> =
        value?.arrayValue.orEmpty().mapNotNull { (it as? ProviderJsonValue.Str)?.value?.trim()?.takeIf(String::isNotEmpty) }

    private fun parseObject(bytes: ByteArray, label: String): ProviderJsonValue.Obj =
        parse(bytes, label) as? ProviderJsonValue.Obj
            ?: throw VercelResponseFormatException("Vercel $label response is malformed.")

    private fun parse(bytes: ByteArray, label: String): ProviderJsonValue = try {
        ProviderJsonParser.parse(bytes)
    } catch (error: ProviderJsonException) {
        throw VercelResponseFormatException("Vercel returned malformed $label JSON.", error)
    } catch (error: IllegalArgumentException) {
        throw VercelResponseFormatException("Vercel returned malformed $label JSON.", error)
    }
}
