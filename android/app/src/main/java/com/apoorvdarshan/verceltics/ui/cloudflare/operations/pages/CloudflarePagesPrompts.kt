package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesCustomDomain
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeployment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDirectUploadOptions
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesProjectEditDraft
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareConfirmationPrompt

/**
 * Confirmation copy for every Pages mutation. Titles, confirm labels and destructive roles come from
 * iOS (`PendingPagesAction`, `CloudflarePagesOperationsPendingAction`); each message names the exact
 * deployment, domain, project or folder that will change. Resource ids match what the API verifies.
 */
object CloudflarePagesPrompts {
    fun retry(projectName: String, deployment: CloudflarePagesDeployment) = CloudflareConfirmationPrompt(
        title = "Retry this deployment?",
        message = "Cloudflare will create a new deployment of $projectName using deployment ${deployment.displayId}’s configuration.",
        confirmLabel = "Retry Deployment",
        resourceId = deployment.id,
    )

    fun rollback(projectName: String, deployment: CloudflarePagesDeployment) = CloudflareConfirmationPrompt(
        title = "Roll production back?",
        message = "Cloudflare will make deployment ${deployment.displayId} the active production version of $projectName.",
        confirmLabel = "Roll Back",
        resourceId = deployment.id,
        destructive = true,
    )

    fun deleteDeployment(projectName: String, deployment: CloudflarePagesDeployment) = CloudflareConfirmationPrompt(
        title = "Delete this deployment?",
        message = "Deployment ${deployment.displayId} of $projectName will be permanently removed from Cloudflare Pages.",
        confirmLabel = "Delete Deployment",
        resourceId = deployment.id,
        destructive = true,
    )

    fun redeploy(projectName: String, deployment: CloudflarePagesDeployment) = CloudflareConfirmationPrompt(
        title = "Redeploy the latest build?",
        message = "Cloudflare will create a new deployment of $projectName from deployment ${deployment.displayId}’s build.",
        confirmLabel = "Redeploy",
        resourceId = deployment.id,
    )

    fun purgeBuildCache(projectName: String, resourceId: String) = CloudflareConfirmationPrompt(
        title = "Purge the build cache?",
        message = "The next build of $projectName will recreate every cached dependency and artifact. Existing deployments stay online.",
        confirmLabel = "Purge Cache",
        resourceId = resourceId,
        workingId = "build-cache",
    )

    fun deleteDomain(domain: CloudflarePagesCustomDomain, resourceId: String) = CloudflareConfirmationPrompt(
        title = "Remove ${domain.name}?",
        message = "Cloudflare will detach ${domain.name} and stop serving it from this project.",
        confirmLabel = "Remove Domain",
        resourceId = resourceId,
        destructive = true,
        workingId = domain.id,
    )

    fun retryValidation(domain: CloudflarePagesCustomDomain, resourceId: String) = CloudflareConfirmationPrompt(
        title = "Retry validation for ${domain.name}?",
        message = "Cloudflare will restart hostname validation and certificate issuance for ${domain.name}.",
        confirmLabel = "Retry Validation",
        resourceId = resourceId,
        workingId = domain.id,
    )

    fun addDomain(projectName: String, domainName: String, resourceId: String) = CloudflareConfirmationPrompt(
        title = "Add $domainName?",
        message = "Cloudflare will attach $domainName to $projectName, check DNS ownership, validate the hostname and provision its certificate.",
        confirmLabel = "Add Domain",
        resourceId = resourceId,
        workingId = "add-domain",
    )

    fun deleteProject(projectName: String, resourceId: String) = CloudflareConfirmationPrompt(
        title = "Delete this Pages project?",
        message = "$projectName, its deployments and Pages configuration will be permanently removed. This cannot be undone.",
        confirmLabel = "Delete Project",
        resourceId = resourceId,
        destructive = true,
        workingId = "delete-project",
    )

    fun saveSettings(projectName: String, draft: CloudflarePagesProjectEditDraft, resourceId: String): CloudflareConfirmationPrompt {
        val build = listOf(
            "production branch “${draft.productionBranch.trim()}”",
            "build command “${draft.buildCommand.trim().ifEmpty { "none" }}”",
            "output directory “${draft.destinationDirectory.trim().ifEmpty { "none" }}”",
            "root directory “${draft.rootDirectory.trim().ifEmpty { "none" }}”",
            "build caching ${if (draft.buildCaching) "on" else "off"}",
        )
        val git = if (draft.sourceType != null) {
            " Git automation: production deploys ${if (draft.productionDeploymentsEnabled) "on" else "off"}, " +
                "preview deploys “${draft.previewDeploymentSetting}”, pull request comments ${if (draft.pullRequestCommentsEnabled) "on" else "off"}."
        } else {
            ""
        }
        return CloudflareConfirmationPrompt(
            title = "Save project settings?",
            message = "Cloudflare will update $projectName to use ${build.joinToString(", ")}.$git " +
                "Secret variables and Analytics credentials are not changed.",
            confirmLabel = "Save Settings",
            resourceId = resourceId,
            workingId = "project-settings",
        )
    }

    fun directUpload(
        projectName: String,
        folderName: String,
        environment: CloudflarePagesEnvironment,
        options: CloudflarePagesDirectUploadOptions,
        resourceId: String,
    ) = CloudflareConfirmationPrompt(
        title = "Deploy this folder to ${environment.label.lowercase()}?",
        message = "Verceltics will upload the prebuilt folder “$folderName” and create a new ${environment.label.lowercase()} " +
            "deployment of $projectName on ${options.branch?.let { "branch “$it”" } ?: "Cloudflare’s default branch"}. " +
            "Only assets Cloudflare does not already have are uploaded.",
        confirmLabel = "Deploy",
        resourceId = resourceId,
        workingId = DIRECT_UPLOAD_WORKING_ID,
    )

    const val DIRECT_UPLOAD_WORKING_ID: String = "direct-upload"
}
