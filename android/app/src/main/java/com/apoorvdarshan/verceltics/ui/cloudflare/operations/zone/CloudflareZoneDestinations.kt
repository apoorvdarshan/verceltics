package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.apoorvdarshan.verceltics.ui.cloudflare.CloudflareZoneUi
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsContext
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOperationsRoute
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareReadOnlyZoneDetail
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareUnknownOperationsScreen

/** Root zone detail for the selected zone (iOS `CloudflareZoneDetailView`). */
@Composable
fun CloudflareZoneDetailDestination(zone: CloudflareZoneUi, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareReadOnlyZoneDetail(zone, context, modifier)
}

/** Pushed zone screens (zone operations, security center, ...). */
@Composable
fun CloudflareZoneDestination(route: CloudflareOperationsRoute, context: CloudflareOperationsContext, modifier: Modifier = Modifier) {
    CloudflareUnknownOperationsScreen(modifier)
}
