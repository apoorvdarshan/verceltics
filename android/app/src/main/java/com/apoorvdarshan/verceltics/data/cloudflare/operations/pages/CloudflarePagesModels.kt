package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareOperationException
import com.apoorvdarshan.verceltics.data.cloudflare.operations.arr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.bool
import com.apoorvdarshan.verceltics.data.cloudflare.operations.int
import com.apoorvdarshan.verceltics.data.cloudflare.operations.obj
import com.apoorvdarshan.verceltics.data.cloudflare.operations.str
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strictStr
import com.apoorvdarshan.verceltics.data.cloudflare.operations.strings
import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.time.Instant

/** iOS `CloudflarePagesEnvironment`. */
enum class CloudflarePagesEnvironment(val wireValue: String, val label: String) {
    PRODUCTION("production", "Production"),
    PREVIEW("preview", "Preview"),
    ;

    companion object {
        fun from(value: String?): CloudflarePagesEnvironment? = entries.firstOrNull { it.wireValue == value?.lowercase() }
    }
}

/** iOS `CloudflarePagesDeployment.Stage`. */
data class CloudflarePagesStage(
    val name: String?,
    val status: String?,
    val startedOn: String?,
    val endedOn: String?,
)

/** iOS `CloudflarePagesBuildConfig` with the Web Analytics token reduced to presence metadata. */
data class CloudflarePagesBuildConfig(
    val buildCommand: String?,
    val destinationDirectory: String?,
    val rootDirectory: String?,
    val buildCaching: Boolean?,
    val webAnalyticsTag: String?,
    val webAnalyticsTokenConfigured: Boolean,
)

/** iOS `CloudflarePagesOperationsSource`. */
data class CloudflarePagesSource(
    val type: String?,
    val config: Config?,
) {
    data class Config(
        val deploymentsEnabled: Boolean?,
        val owner: String?,
        val ownerId: String?,
        val repositoryId: String?,
        val repositoryName: String?,
        val productionBranch: String?,
        val productionDeploymentsEnabled: Boolean?,
        val previewDeploymentSetting: String?,
        val previewBranchIncludes: List<String>,
        val previewBranchExcludes: List<String>,
        val pathIncludes: List<String>,
        val pathExcludes: List<String>,
        val pullRequestCommentsEnabled: Boolean?,
    )
}

/**
 * iOS `CloudflarePagesDeployment`. Environment-variable values are never retained: only the sorted
 * variable names survive parsing.
 */
data class CloudflarePagesDeployment(
    val id: String,
    val shortId: String?,
    val projectId: String?,
    val projectName: String?,
    val environment: CloudflarePagesEnvironment?,
    val url: String?,
    val aliases: List<String>,
    val createdOn: String?,
    val modifiedOn: String?,
    val latestStage: CloudflarePagesStage?,
    val triggerType: String?,
    val branch: String?,
    val commitHash: String?,
    val commitMessage: String?,
    val commitDirty: Boolean?,
    val stages: List<CloudflarePagesStage>,
    val buildConfig: CloudflarePagesBuildConfig?,
    val isSkipped: Boolean?,
    val usesFunctions: Boolean?,
    val environmentVariableNames: List<String>,
) {
    val displayStatus: String get() = latestStage?.status ?: "unknown"
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
    val modifiedDate: Instant? get() = CloudflareDates.parse(modifiedOn)

    /** iOS `shortID ?? id.prefix(12)`. */
    val displayId: String get() = shortId ?: id.take(12)
}

/** iOS `CloudflarePagesDeploymentLog`. */
data class CloudflarePagesDeploymentLog(val line: String, val timestamp: String) {
    val date: Instant? get() = CloudflareDates.parse(timestamp)
}

/** iOS `CloudflarePagesVariableSummary`: type and whether a value exists, never the value. */
data class CloudflarePagesVariableSummary(val type: String?, val valueConfigured: Boolean) {
    val isSecret: Boolean get() = type?.lowercase() == "secret_text"
}

/** iOS `CloudflarePagesBindingReference`. */
data class CloudflarePagesBindingReference(
    val id: String?,
    val namespaceId: String?,
    val name: String?,
    val service: String?,
    val environment: String?,
    val entrypoint: String?,
    val dataset: String?,
    val projectId: String?,
    val certificateId: String?,
    val indexName: String?,
    val jurisdiction: String?,
) {
    val summary: String
        get() = listOf(name, service, dataset, indexName, projectId, id, namespaceId, certificateId, environment, entrypoint, jurisdiction)
            .filterNotNull()
            .filter(String::isNotEmpty)
            .joinToString(" · ")
}

/** iOS `CloudflarePagesDeploymentConfiguration`. */
data class CloudflarePagesDeploymentConfiguration(
    val compatibilityDate: String?,
    val compatibilityFlags: List<String>,
    val alwaysUseLatestCompatibilityDate: Boolean?,
    val buildImageMajorVersion: Int?,
    val failOpen: Boolean?,
    val usageModel: String?,
    val wranglerConfigHash: String?,
    val placementMode: String?,
    val cpuMilliseconds: Int?,
    val environmentVariables: Map<String, CloudflarePagesVariableSummary>,
    /** Binding groups in iOS display order; empty groups are omitted. */
    val bindingGroups: List<Pair<String, Map<String, CloudflarePagesBindingReference>>>,
) {
    val bindingCount: Int get() = bindingGroups.sumOf { it.second.size }
}

data class CloudflarePagesDeploymentConfigurations(
    val production: CloudflarePagesDeploymentConfiguration?,
    val preview: CloudflarePagesDeploymentConfiguration?,
)

/**
 * iOS `CloudflarePagesOperationsProject`: the complete project shape. Sensitive token and variable
 * values are reduced to presence metadata while parsing.
 */
data class CloudflarePagesProjectDetail(
    val id: String,
    val name: String,
    val subdomain: String?,
    val domains: List<String>,
    val productionBranch: String?,
    val createdOn: String?,
    val framework: String?,
    val frameworkVersion: String?,
    val latestDeployment: CloudflarePagesDeployment?,
    val canonicalDeployment: CloudflarePagesDeployment?,
    val source: CloudflarePagesSource?,
    val buildConfig: CloudflarePagesBuildConfig?,
    val usesFunctions: Boolean?,
    val productionScriptName: String?,
    val previewScriptName: String?,
    val deploymentConfigs: CloudflarePagesDeploymentConfigurations?,
) {
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
}

/** iOS `CloudflarePagesCustomDomain`. */
data class CloudflarePagesCustomDomain(
    val id: String,
    val domainId: String?,
    val name: String,
    val status: String?,
    val createdOn: String?,
    val certificateAuthority: String?,
    val zoneTag: String?,
    val validationStatus: String?,
    val validationMethod: String?,
    val validationErrorMessage: String?,
    val txtName: String?,
    val txtValue: String?,
    val verificationStatus: String?,
    val verificationErrorMessage: String?,
) {
    val isActive: Boolean get() = status?.lowercase() == "active"
    val createdDate: Instant? get() = CloudflareDates.parse(createdOn)
}

/** Parsers from the lossless provider JSON tree. Required identifiers missing → decoding error. */
object CloudflarePagesParser {
    private val BINDING_GROUPS = listOf(
        "AI" to "ai_bindings",
        "Analytics Engine" to "analytics_engine_datasets",
        "Browser" to "browsers",
        "D1" to "d1_databases",
        "Durable Objects" to "durable_object_namespaces",
        "Hyperdrive" to "hyperdrive_bindings",
        "KV" to "kv_namespaces",
        "mTLS" to "mtls_certificates",
        "Queues" to "queue_producers",
        "R2" to "r2_buckets",
        "Services" to "services",
        "Vectorize" to "vectorize_bindings",
    )

    fun project(value: ProviderJsonValue): CloudflarePagesProjectDetail {
        if (value !is ProviderJsonValue.Obj) throw CloudflareOperationException.decoding()
        return CloudflarePagesProjectDetail(
            id = value.strictStr("id") ?: throw CloudflareOperationException.decoding(),
            name = value.strictStr("name") ?: throw CloudflareOperationException.decoding(),
            subdomain = value.strictStr("subdomain"),
            domains = value.strings("domains"),
            productionBranch = value.strictStr("production_branch"),
            createdOn = value.strictStr("created_on"),
            framework = value.strictStr("framework"),
            frameworkVersion = value.strictStr("framework_version"),
            latestDeployment = value.obj("latest_deployment")?.let(::deploymentOrNull),
            canonicalDeployment = value.obj("canonical_deployment")?.let(::deploymentOrNull),
            source = value.obj("source")?.let(::source),
            buildConfig = value.obj("build_config")?.let(::buildConfig),
            usesFunctions = value.bool("uses_functions"),
            productionScriptName = value.strictStr("production_script_name"),
            previewScriptName = value.strictStr("preview_script_name"),
            deploymentConfigs = value.obj("deployment_configs")?.let { configs ->
                CloudflarePagesDeploymentConfigurations(
                    production = configs.obj("production")?.let(::deploymentConfiguration),
                    preview = configs.obj("preview")?.let(::deploymentConfiguration),
                )
            },
        )
    }

    fun deployment(value: ProviderJsonValue): CloudflarePagesDeployment =
        deploymentOrNull(value) ?: throw CloudflareOperationException.decoding()

    fun deployments(values: List<ProviderJsonValue>): List<CloudflarePagesDeployment> = values.map(::deployment)

    private fun deploymentOrNull(value: ProviderJsonValue): CloudflarePagesDeployment? {
        if (value !is ProviderJsonValue.Obj) return null
        val id = value.strictStr("id") ?: return null
        val trigger = value.obj("deployment_trigger")
        val metadata = trigger.obj("metadata")
        return CloudflarePagesDeployment(
            id = id,
            shortId = value.strictStr("short_id"),
            projectId = value.strictStr("project_id"),
            projectName = value.strictStr("project_name"),
            environment = CloudflarePagesEnvironment.from(value.strictStr("environment")),
            url = value.strictStr("url"),
            aliases = value.strings("aliases"),
            createdOn = value.strictStr("created_on"),
            modifiedOn = value.strictStr("modified_on"),
            latestStage = value.obj("latest_stage")?.let(::stage),
            triggerType = trigger.strictStr("type"),
            branch = metadata.strictStr("branch"),
            commitHash = metadata.strictStr("commit_hash"),
            commitMessage = metadata.strictStr("commit_message"),
            commitDirty = metadata.bool("commit_dirty"),
            stages = value.arr("stages").mapNotNull { (it as? ProviderJsonValue.Obj)?.let(::stage) },
            buildConfig = value.obj("build_config")?.let(::buildConfig),
            isSkipped = value.bool("is_skipped"),
            usesFunctions = value.bool("uses_functions"),
            environmentVariableNames = value.obj("env_vars")?.fields?.keys?.sorted().orEmpty(),
        )
    }

    private fun stage(value: ProviderJsonValue.Obj) = CloudflarePagesStage(
        name = value.strictStr("name"),
        status = value.strictStr("status"),
        startedOn = value.strictStr("started_on"),
        endedOn = value.strictStr("ended_on"),
    )

    private fun buildConfig(value: ProviderJsonValue.Obj) = CloudflarePagesBuildConfig(
        buildCommand = value.strictStr("build_command"),
        destinationDirectory = value.strictStr("destination_dir"),
        rootDirectory = value.strictStr("root_dir"),
        buildCaching = value.bool("build_caching"),
        webAnalyticsTag = value.strictStr("web_analytics_tag"),
        webAnalyticsTokenConfigured = value["web_analytics_token"]?.let { !it.isNull } ?: false,
    )

    private fun source(value: ProviderJsonValue.Obj) = CloudflarePagesSource(
        type = value.strictStr("type"),
        config = value.obj("config")?.let { config ->
            CloudflarePagesSource.Config(
                deploymentsEnabled = config.bool("deployments_enabled"),
                owner = config.strictStr("owner"),
                ownerId = config.strictStr("owner_id"),
                repositoryId = config.strictStr("repo_id"),
                repositoryName = config.strictStr("repo_name"),
                productionBranch = config.strictStr("production_branch"),
                productionDeploymentsEnabled = config.bool("production_deployments_enabled"),
                previewDeploymentSetting = config.strictStr("preview_deployment_setting"),
                previewBranchIncludes = config.strings("preview_branch_includes"),
                previewBranchExcludes = config.strings("preview_branch_excludes"),
                pathIncludes = config.strings("path_includes"),
                pathExcludes = config.strings("path_excludes"),
                pullRequestCommentsEnabled = config.bool("pr_comments_enabled"),
            )
        },
    )

    private fun deploymentConfiguration(value: ProviderJsonValue.Obj): CloudflarePagesDeploymentConfiguration {
        val variables = value.obj("env_vars")?.fields.orEmpty().mapNotNull { (name, summary) ->
            val summaryObject = summary as? ProviderJsonValue.Obj ?: return@mapNotNull null
            name to CloudflarePagesVariableSummary(
                type = summaryObject.strictStr("type"),
                valueConfigured = summaryObject["value"]?.let { !it.isNull } ?: false,
            )
        }.toMap()
        val groups = BINDING_GROUPS.mapNotNull { (label, key) ->
            val references = value.obj(key)?.fields.orEmpty().mapNotNull { (name, reference) ->
                (reference as? ProviderJsonValue.Obj)?.let { name to bindingReference(it) }
            }.toMap()
            if (references.isEmpty()) null else label to references
        }
        return CloudflarePagesDeploymentConfiguration(
            compatibilityDate = value.strictStr("compatibility_date"),
            compatibilityFlags = value.strings("compatibility_flags"),
            alwaysUseLatestCompatibilityDate = value.bool("always_use_latest_compatibility_date"),
            buildImageMajorVersion = value.int("build_image_major_version"),
            failOpen = value.bool("fail_open"),
            usageModel = value.strictStr("usage_model"),
            wranglerConfigHash = value.strictStr("wrangler_config_hash"),
            placementMode = value.obj("placement").strictStr("mode"),
            cpuMilliseconds = value.obj("limits").int("cpu_ms"),
            environmentVariables = variables,
            bindingGroups = groups,
        )
    }

    private fun bindingReference(value: ProviderJsonValue.Obj) = CloudflarePagesBindingReference(
        id = value.str("id"),
        namespaceId = value.str("namespace_id"),
        name = value.str("name"),
        service = value.str("service"),
        environment = value.str("environment"),
        entrypoint = value.str("entrypoint"),
        dataset = value.str("dataset"),
        projectId = value.str("project_id"),
        certificateId = value.str("certificate_id"),
        indexName = value.str("index_name"),
        jurisdiction = value.str("jurisdiction"),
    )

    fun logs(value: ProviderJsonValue): List<CloudflarePagesDeploymentLog> {
        val data = when (value) {
            is ProviderJsonValue.Obj -> value.arr("data")
            is ProviderJsonValue.Arr -> value.items
            else -> throw CloudflareOperationException.decoding()
        }
        return data.mapNotNull { entry ->
            val line = entry.strictStr("line") ?: return@mapNotNull null
            CloudflarePagesDeploymentLog(line = line, timestamp = entry.strictStr("ts").orEmpty())
        }
    }

    fun domain(value: ProviderJsonValue): CloudflarePagesCustomDomain {
        if (value !is ProviderJsonValue.Obj) throw CloudflareOperationException.decoding()
        val validation = value.obj("validation_data")
        val verification = value.obj("verification_data")
        val name = value.strictStr("name") ?: throw CloudflareOperationException.decoding()
        return CloudflarePagesCustomDomain(
            id = value.strictStr("id") ?: name,
            domainId = value.strictStr("domain_id"),
            name = name,
            status = value.strictStr("status"),
            createdOn = value.strictStr("created_on"),
            certificateAuthority = value.strictStr("certificate_authority"),
            zoneTag = value.strictStr("zone_tag"),
            validationStatus = validation.strictStr("status"),
            validationMethod = validation.strictStr("method"),
            validationErrorMessage = validation.strictStr("error_message"),
            txtName = validation.strictStr("txt_name"),
            txtValue = validation.strictStr("txt_value"),
            verificationStatus = verification.strictStr("status"),
            verificationErrorMessage = verification.strictStr("error_message"),
        )
    }
}
