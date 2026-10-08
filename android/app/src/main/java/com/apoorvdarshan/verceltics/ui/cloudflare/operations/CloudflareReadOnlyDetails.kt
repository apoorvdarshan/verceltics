package com.apoorvdarshan.verceltics.ui.cloudflare.operations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflarePagesProjectUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareWorkerUi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi

/** Inventory-only zone summary, used when live operations are unavailable (sample data). */
@Composable
fun CloudflareReadOnlyZoneDetail(zone: CloudflareZoneUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareOpsScreen("cloudflare.zoneDetail.readOnly", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = zone.name,
                subtitle = zone.planName ?: zone.type ?: "Cloudflare zone",
                icon = Icons.Rounded.Public,
                status = if (zone.paused == true) "PAUSED" else zone.status?.uppercase() ?: "UNKNOWN",
                statusColor = if (zone.isActive) CloudflareOpsColors.Green else CloudflareOpsColors.Amber,
            )
        }
        item("fields") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Zone", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Zone ID", zone.id, monospace = true, copyable = true)
                CloudflareOpsDetailRow("Account", zone.accountName ?: context.accountName ?: "Unknown")
                CloudflareOpsDetailRow("Plan", zone.planName ?: "Not reported")
                CloudflareOpsDetailRow("Type", zone.type ?: "Not reported")
            }
        }
        if (context.client == null) item("unavailable") { CloudflareOperationsUnavailable() }
    }
}

/** Inventory-only Pages summary, used when live operations are unavailable (sample data). */
@Composable
fun CloudflareReadOnlyPagesDetail(
    project: CloudflarePagesProjectUi,
    context: CloudflareOperationsContext,
    modifier: Modifier = Modifier,
) {
    CloudflareOpsScreen("cloudflare.pagesDetail.readOnly", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = project.name,
                subtitle = project.domains.firstOrNull() ?: project.subdomain ?: "Cloudflare Pages",
                icon = Icons.Rounded.Description,
                status = project.latestDeploymentStatus?.uppercase(),
                statusColor = CloudflareOpsColors.Orange,
            )
        }
        item("fields") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Project", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Production branch", project.productionBranch ?: "Not reported")
                CloudflareOpsDetailRow("Subdomain", project.subdomain ?: "Not reported")
                CloudflareOpsDetailRow("Domains", project.domains.joinToString(", ").ifBlank { "No domains returned" })
                CloudflareOpsDetailRow("Project ID", project.id, monospace = true, copyable = true)
            }
        }
        if (context.client == null) item("unavailable") { CloudflareOperationsUnavailable() }
    }
}

/** Inventory-only Worker summary, used when live operations are unavailable (sample data). */
@Composable
fun CloudflareReadOnlyWorkerDetail(worker: CloudflareWorkerUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareOpsScreen("cloudflare.workerDetail.readOnly", modifier) {
        item("hero") {
            CloudflareOpsHero(
                title = worker.id,
                subtitle = "Cloudflare Worker",
                icon = Icons.Rounded.Code,
                status = if (worker.hasModules == true) "MODULES" else "SCRIPT",
                statusColor = CloudflareOpsColors.Orange,
            )
        }
        item("fields") {
            CloudflareOpsPanel {
                CloudflareOpsSectionHeader("Worker", Icons.Rounded.Info)
                CloudflareOpsDivider()
                CloudflareOpsDetailRow("Modified", worker.modifiedOn ?: "Not reported")
                CloudflareOpsDetailRow("Compatibility date", worker.compatibilityDate ?: "Not reported")
                CloudflareOpsDetailRow("Handlers", worker.handlers.joinToString(", ").ifBlank { "None reported" })
            }
        }
        if (context.client == null) item("unavailable") { CloudflareOperationsUnavailable() }
    }
}

/** Shown for a restored route whose screen no longer exists. */
@Composable
fun CloudflareUnknownOperationsScreen(modifier: Modifier = Modifier) {
    CloudflareOpsScreen("cloudflare.unknownOperation", modifier) {
        item {
            CloudflareOpsPanel {
                CloudflareOpsEmptySection(
                    icon = Icons.Rounded.Info,
                    title = "Screen unavailable",
                    message = "This Cloudflare screen could not be restored. Go back to continue.",
                )
            }
        }
    }
}
