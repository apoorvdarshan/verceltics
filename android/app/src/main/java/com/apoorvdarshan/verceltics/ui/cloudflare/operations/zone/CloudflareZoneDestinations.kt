package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareSecurityItem
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneOperationsApi
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsDomain
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsUnavailable
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsScreen
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyZoneDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Pushed zone screens. Args always start with the zone id. */
object CloudflareZoneRoutes {
    const val OPERATIONS = "operations"
    const val SECURITY = "security"
    const val RULESET = "ruleset"

    /** iOS `CloudflareZoneOperationsView` ("Zone operations"). */
    fun operations(zoneId: String, zoneName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.ZONE,
        screen = OPERATIONS,
        title = "Zone operations",
        args = listOf(zoneId, routeText(zoneName)),
    )

    /** iOS `CloudflareSecurityCenterView` ("Security"). */
    fun security(zoneId: String, zoneName: String) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.ZONE,
        screen = SECURITY,
        title = "Security",
        args = listOf(zoneId, routeText(zoneName)),
    )

    /** iOS `CloudflareRulesetDetailView` ("WAF ruleset"). */
    fun ruleset(zoneId: String, ruleset: CloudflareSecurityItem) = CloudflareOperationsRoute(
        domain = CloudflareOperationsDomain.ZONE,
        screen = RULESET,
        title = "WAF ruleset",
        args = listOf(
            zoneId,
            routeText(ruleset.id),
            routeText(ruleset.title),
            routeText(ruleset.subtitle.orEmpty()),
            routeText(ruleset.status.orEmpty()),
        ),
    )

    /** Route args are persisted text: strip the record separator and bound the length. */
    private fun routeText(value: String): String = value.filterNot { it == '\u001F' }.take(1_024)
}

/** Root zone detail for the selected zone (iOS `CloudflareZoneDetailView`). */
@Composable
fun CloudflareZoneDetailDestination(zone: CloudflareZoneUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    if (client == null) {
        CloudflareReadOnlyZoneDetail(zone, context, modifier)
        return
    }
    val viewModel = viewModel(key = "cloudflare.zoneDetail.${zone.id}") {
        CloudflareZoneDetailViewModel(
            api = CloudflareZoneOperationsApi(client),
            zoneId = zone.id,
            zoneName = zone.name,
            mutations = client.mutations,
        )
    }
    CloudflareZoneDetailScreen(zone, viewModel, context, modifier)
}

/** Pushed zone screens: zone operations, security center and WAF ruleset detail. */
@Composable
fun CloudflareZoneDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    val client = context.client
    if (client == null) {
        CloudflareOpsScreen("cloudflare.zone.unavailable", modifier) { item { CloudflareOperationsUnavailable() } }
        return
    }
    val zoneId = route.arg(0)
    if (zoneId.isEmpty()) {
        CloudflareUnknownOperationsScreen(modifier)
        return
    }
    when (route.screen) {
        CloudflareZoneRoutes.OPERATIONS -> {
            val viewModel = viewModel(key = "cloudflare.zoneOperations.$zoneId") {
                CloudflareZoneOperationsViewModel(CloudflareZoneOperationsApi(client), zoneId, route.arg(1))
            }
            CloudflareZoneOperationsScreen(viewModel, route.arg(1), context, modifier)
        }
        CloudflareZoneRoutes.SECURITY -> {
            val viewModel = viewModel(key = "cloudflare.securityCenter.$zoneId") {
                CloudflareSecurityCenterViewModel(CloudflareZoneOperationsApi(client), zoneId, route.arg(1))
            }
            CloudflareSecurityCenterScreen(viewModel, route.arg(1), context, modifier)
        }
        CloudflareZoneRoutes.RULESET -> {
            val rulesetId = route.arg(1)
            val viewModel = viewModel(key = "cloudflare.ruleset.$zoneId.$rulesetId") {
                CloudflareRulesetDetailViewModel(CloudflareZoneOperationsApi(client), zoneId, rulesetId)
            }
            CloudflareRulesetDetailScreen(
                viewModel = viewModel,
                rulesetId = rulesetId,
                title = route.arg(2).ifEmpty { rulesetId },
                subtitle = route.arg(3).ifEmpty { null },
                status = route.arg(4).ifEmpty { null },
                context = context,
                modifier = modifier,
            )
        }
        else -> CloudflareUnknownOperationsScreen(modifier)
    }
}
