package com.apoorvdarshan.verceltics.ui.cloudflare.operations.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareDates
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesCustomDomain
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesDeployment
import com.apoorvdarshan.verceltics.data.cloudflare.operations.pages.CloudflarePagesEnvironment
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsDomain
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import java.time.Instant
import java.util.Locale

/** Route factories for pushed Pages screens. */
object CloudflarePagesRoutes {
    const val OPERATIONS = "operations"
    const val DEPLOYMENT = "deployment"

    /** iOS `CloudflarePagesOperationsView`; args: accountId, projectName. */
    fun operations(accountId: String, projectName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.PAGES,
        screen = OPERATIONS,
        title = "Pages operations",
        args = listOf(accountId, projectName),
    )

    /** iOS `CloudflarePagesDeploymentDetailView`; args: accountId, projectName, deploymentId. */
    fun deployment(accountId: String, projectName: String, deployment: CloudflarePagesDeployment) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.PAGES,
        screen = DEPLOYMENT,
        title = deployment.shortId ?: "Deployment",
        args = listOf(accountId, projectName, deployment.id),
    )
}

/** One action in a row overflow menu (iOS `Menu`). */
data class CloudflarePagesMenuAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

@Composable
internal fun CloudflarePagesOverflowMenu(
    contentDescription: String,
    actions: List<CloudflarePagesMenuAction>,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = if (testTag == null) Modifier else Modifier.testTag(testTag),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                val tint = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(action.label, color = tint) },
                    leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    modifier = if (action.testTag == null) Modifier else Modifier.testTag(action.testTag),
                )
            }
        }
    }
}

/** Opens [raw] as an https URL (iOS prefixes bare hosts with `https://`). */
internal fun UriHandler.openPagesUrl(raw: String?) {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return
    runCatching { openUri(if (value.startsWith("http")) value else "https://$value") }
}

internal object CloudflarePagesText {
    fun valueOrNotSet(value: String?): String = value?.trim()?.takeIf(String::isNotEmpty) ?: "Not set"

    fun enabled(value: Boolean?): String = when (value) {
        true -> "Enabled"
        false -> "Disabled"
        null -> "Not set"
    }

    fun date(instant: Instant?, fallback: String = "Not available"): String = instant?.let(CloudflareDates::formatDateTime) ?: fallback

    fun deploymentLabel(deployment: CloudflarePagesDeployment?): String {
        deployment ?: return "Not available"
        return "${deployment.displayId} · ${capitalized(deployment.displayStatus)}"
    }

    fun ruleText(includes: List<String>, excludes: List<String>): String = listOfNotNull(
        includes.takeIf(List<String>::isNotEmpty)?.let { "Include: ${it.joinToString(", ")}" },
        excludes.takeIf(List<String>::isNotEmpty)?.let { "Exclude: ${it.joinToString(", ")}" },
    ).joinToString(" · ").ifEmpty { "No filters" }

    /** Swift `String.capitalized`: first letter of every word upper-cased, the rest lower-cased. */
    fun capitalized(value: String): String = value.split(' ').joinToString(" ") { word ->
        word.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun certificateAuthority(domain: CloudflarePagesCustomDomain, fallback: String): String =
        domain.certificateAuthority?.replace('_', ' ')?.let(::capitalized) ?: fallback

    fun environmentLabel(environment: CloudflarePagesEnvironment?): String = environment?.label ?: "Unknown"
}

internal object CloudflarePagesColors {
    @Composable
    fun deployment(deployment: CloudflarePagesDeployment): Color = when {
        deployment.isSkipped == true -> MaterialTheme.colorScheme.onSurfaceVariant
        deployment.environment == CloudflarePagesEnvironment.PRODUCTION -> CloudflareOpsColors.Green
        deployment.environment == CloudflarePagesEnvironment.PREVIEW -> CloudflareOpsColors.Amber
        else -> CloudflareOpsColors.Orange
    }

    @Composable
    fun deploymentStatus(status: String?): Color = when (status?.lowercase()) {
        "success", "active" -> CloudflareOpsColors.Green
        "failure", "failed", "error" -> CloudflareOpsColors.red()
        else -> CloudflareOpsColors.Amber
    }

    @Composable
    fun stage(status: String?): Color = when (status?.lowercase()) {
        "success" -> CloudflareOpsColors.Green
        "active", "queued", "idle" -> CloudflareOpsColors.Amber
        "failure", "canceled" -> CloudflareOpsColors.red()
        else -> CloudflareOpsColors.Orange
    }

    @Composable
    fun domain(domain: CloudflarePagesCustomDomain): Color = when (domain.status?.lowercase()) {
        "active" -> CloudflareOpsColors.Green
        "error", "blocked", "deactivated" -> CloudflareOpsColors.red()
        else -> CloudflareOpsColors.Amber
    }
}
