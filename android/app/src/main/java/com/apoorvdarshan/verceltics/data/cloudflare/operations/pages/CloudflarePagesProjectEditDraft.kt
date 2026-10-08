package com.apoorvdarshan.verceltics.data.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.network.ProviderJsonValue
import java.util.TreeMap

/**
 * iOS `CloudflarePagesProjectEditDraft`: the editable build and Git-automation settings. Secret
 * variables and Analytics credentials are never part of the draft, so a save cannot overwrite them.
 */
data class CloudflarePagesProjectEditDraft(
    val productionBranch: String,
    val buildCommand: String,
    val destinationDirectory: String,
    val rootDirectory: String,
    val buildCaching: Boolean,
    val sourceType: String?,
    val sourceOwner: String?,
    val sourceOwnerId: String?,
    val sourceRepositoryId: String?,
    val sourceRepositoryName: String?,
    val productionDeploymentsEnabled: Boolean,
    val previewDeploymentSetting: String,
    val pullRequestCommentsEnabled: Boolean,
    val previewBranchIncludes: String,
    val previewBranchExcludes: String,
    val pathIncludes: String,
    val pathExcludes: String,
) {
    /** iOS `CloudflarePagesProjectUpdateRequest` encoded with sorted keys; nil optionals are omitted. */
    fun requestBody(): ProviderJsonValue {
        val body = TreeMap<String, Any?>()
        body["production_branch"] = productionBranch.trim()
        body["build_config"] = TreeMap<String, Any?>().apply {
            put("build_caching", buildCaching)
            put("build_command", buildCommand.trim())
            put("destination_dir", destinationDirectory.trim())
            put("root_dir", rootDirectory.trim())
        }
        sourceType?.let { type ->
            val config = TreeMap<String, Any?>()
            sourceOwner?.let { config["owner"] = it }
            sourceOwnerId?.let { config["owner_id"] = it }
            sourceRepositoryId?.let { config["repo_id"] = it }
            sourceRepositoryName?.let { config["repo_name"] = it }
            config["production_branch"] = productionBranch.trim()
            config["production_deployments_enabled"] = productionDeploymentsEnabled
            config["preview_deployment_setting"] = previewDeploymentSetting
            config["preview_branch_includes"] = list(previewBranchIncludes)
            config["preview_branch_excludes"] = list(previewBranchExcludes)
            config["path_includes"] = list(pathIncludes)
            config["path_excludes"] = list(pathExcludes)
            config["pr_comments_enabled"] = pullRequestCommentsEnabled
            body["source"] = TreeMap<String, Any?>().apply {
                put("config", config)
                put("type", type)
            }
        }
        return ProviderJsonValue.from(body)
    }

    companion object {
        val PREVIEW_SETTINGS: List<Pair<String, String>> = listOf(
            "all" to "All branches",
            "none" to "Disabled",
            "custom" to "Custom rules",
        )

        fun from(project: CloudflarePagesProjectDetail): CloudflarePagesProjectEditDraft {
            val config = project.source?.config
            return CloudflarePagesProjectEditDraft(
                productionBranch = project.productionBranch ?: config?.productionBranch ?: "",
                buildCommand = project.buildConfig?.buildCommand ?: "",
                destinationDirectory = project.buildConfig?.destinationDirectory ?: "",
                rootDirectory = project.buildConfig?.rootDirectory ?: "",
                buildCaching = project.buildConfig?.buildCaching ?: false,
                sourceType = project.source?.type,
                sourceOwner = config?.owner,
                sourceOwnerId = config?.ownerId,
                sourceRepositoryId = config?.repositoryId,
                sourceRepositoryName = config?.repositoryName,
                productionDeploymentsEnabled = config?.productionDeploymentsEnabled ?: false,
                previewDeploymentSetting = config?.previewDeploymentSetting ?: "none",
                pullRequestCommentsEnabled = config?.pullRequestCommentsEnabled ?: false,
                previewBranchIncludes = config?.previewBranchIncludes.orEmpty().joinToString(", "),
                previewBranchExcludes = config?.previewBranchExcludes.orEmpty().joinToString(", "),
                pathIncludes = config?.pathIncludes.orEmpty().joinToString(", "),
                pathExcludes = config?.pathExcludes.orEmpty().joinToString(", "),
            )
        }

        /**
         * iOS `canSafelyEdit`: the editor stays read-only unless Cloudflare returned every value the
         * PATCH would send, so saving can never overwrite an unknown default.
         */
        fun canSafelyEdit(project: CloudflarePagesProjectDetail): Boolean {
            if (project.productionBranch == null || project.buildConfig == null || project.buildConfig.buildCaching == null) {
                return false
            }
            val source = project.source ?: return true
            val config = source.config ?: return false
            return config.productionDeploymentsEnabled != null &&
                config.previewDeploymentSetting != null &&
                config.pullRequestCommentsEnabled != null
        }

        /** Comma- or newline-separated list, trimmed, empty entries removed. */
        fun list(value: String): List<String> = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty)
    }
}
