package com.apoorvdarshan.verceltics.ui.billing

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.R
import com.apoorvdarshan.verceltics.billing.BillingProducts
import com.apoorvdarshan.verceltics.billing.ProAccessUiState
import com.apoorvdarshan.verceltics.billing.ProPlan
import com.apoorvdarshan.verceltics.billing.ProPlanKind
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.components.ThemedAlertDialog

private val CardShape = RoundedCornerShape(18.dp)
private val PlanShape = RoundedCornerShape(16.dp)

/**
 * Verceltics Pro paywall, matching the iOS sheet: hero, access scope, benefits, plan cards, and a
 * pinned purchase bar with the exact renewal terms for the selected plan.
 */
@Composable
fun PaywallScreen(
    state: ProAccessUiState,
    onClose: () -> Unit,
    onSelectPlan: (String) -> Unit,
    onPurchase: () -> Unit,
    onRestore: () -> Unit,
    onRetryPlans: () -> Unit,
    onOpenUri: (String) -> Unit,
    onDismissAlert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.isTransactionInFlight
    val showsPurchaseBar = state.isLoadingPlans || state.selectedPlan != null
    BackHandler(enabled = !busy, onBack = onClose)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("paywall"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                onClick = onClose,
                enabled = !busy,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("paywall.close")
                    .semantics {
                        contentDescription = if (busy) {
                            "Close, available when the Google Play transaction finishes"
                        } else {
                            "Close Verceltics Pro"
                        }
                    },
                shape = CircleShape,
                color = Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (busy) 0.38f else 1f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val wide = maxWidth >= 800.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            // The purchase bar owns the bottom inset whenever it is shown.
                            if (showsPurchaseBar) {
                                WindowInsetsSides.Horizontal
                            } else {
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            },
                        ),
                    )
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier.widthIn(max = 980.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            PaywallHero()
                            ProAccessScope()
                            ProBenefits()
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            PlansSection(state, onSelectPlan, onRetryPlans)
                            PurchaseFooter(state, onRestore, onOpenUri)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.widthIn(max = 620.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        PaywallHero()
                        ProAccessScope()
                        ProBenefits()
                        PlansSection(state, onSelectPlan, onRetryPlans)
                        PurchaseFooter(state, onRestore, onOpenUri)
                    }
                }
            }
        }

        if (showsPurchaseBar) {
            PurchaseBar(state = state, onPurchase = onPurchase)
        }
    }

    state.alert?.let { alert ->
        ThemedAlertDialog(
            title = alert.title,
            message = alert.message,
            confirmText = "OK",
            onConfirm = onDismissAlert,
            onDismissRequest = onDismissAlert,
            testTag = "paywall.alert",
        )
    }
}

@Composable
private fun PaywallHero() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogoTile(size = 50)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "VERCELTICS PRO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "ONE ENTITLEMENT · EVERY CONNECTION",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                )
            }
        }
        Text(
            text = "Open the whole stack.",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "Open projects, deployments, domains, site services, provider dashboards, and advanced tools across all ${IntegrationCatalog.all.size} integrations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppLogoTile(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.26f).dp))
            .background(colorResource(R.color.launcher_black)),
        contentAlignment = Alignment.Center,
    ) {
        // Adaptive-icon foregrounds keep their artwork inside the central 72 of 108 units.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize((size * 1.5f).dp),
        )
    }
}

private data class ScopeLane(
    val icon: ImageVector,
    val label: String,
    val detail: String,
    val count: Int,
)

@Composable
private fun ProAccessScope() {
    val lanes = remember {
        listOf(
            ScopeLane(Icons.Rounded.Dns, "Hosting", "Projects · deploys · logs", IntegrationCatalog.providers(Workspace.HOSTING).size),
            ScopeLane(Icons.Rounded.Public, "Registrars", "Domains · DNS · renewals", IntegrationCatalog.providers(Workspace.REGISTRARS).size),
            ScopeLane(Icons.AutoMirrored.Rounded.ShowChart, "Site services", "Search · speed · uptime", IntegrationCatalog.providers(Workspace.SITES).size),
        )
    }
    val total = lanes.sumOf { it.count }
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val dotScale by animateFloatAsState(if (revealed) 1f else 0.01f, tween(620), label = "scopeDots")

    PaywallCard(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Pro includes $total integrations: ${lanes[0].count} hosting platforms, " +
                "${lanes[1].count} registrars, and ${lanes[2].count} site services"
        },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "PRO ACCESS SCOPE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$total CONNECTIONS",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column {
                lanes.forEachIndexed { index, lane ->
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(lane.icon, size = 34)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(lane.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(lane.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = lane.count.toString(),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .size((6 * dotScale).dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                    if (index < lanes.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 46.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProBenefits() {
    PaywallCard {
        Column {
            BenefitRow(
                icon = Icons.Rounded.Hub,
                title = "Details and provider tools",
                subtitle = "Open dashboards, reports, API catalogs, and confirmed provider actions.",
            )
            HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
            BenefitRow(
                icon = Icons.Rounded.Shield,
                title = "Private connections",
                subtitle = "Credentials stay encrypted on this device with Android Keystore; requests go directly to provider APIs.",
            )
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
        IconTile(icon, size = 36)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlansSection(
    state: ProAccessUiState,
    onSelectPlan: (String) -> Unit,
    onRetryPlans: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Choose your access",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Every option unlocks the same Pro features.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            state.isLoadingPlans -> PaywallCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Loading Google Play plans…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.plans.isEmpty() -> PlansUnavailable(
                message = state.plansError
                    ?: "Google Play did not return any plans. Check your connection and try again.",
                onRetry = onRetryPlans,
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.plan(ProPlanKind.YEARLY)?.let { yearly ->
                    PlanCard(
                        plan = yearly,
                        label = "Yearly",
                        detail = "per year",
                        badge = yearly.freeTrial?.badgeText ?: "Best value",
                        isSelected = state.selectedPlanId == yearly.packageId,
                        enabled = !state.isTransactionInFlight,
                        onSelect = onSelectPlan,
                    )
                }
                state.plan(ProPlanKind.MONTHLY)?.let { monthly ->
                    PlanCard(
                        plan = monthly,
                        label = "Monthly",
                        detail = "per month",
                        badge = null,
                        isSelected = state.selectedPlanId == monthly.packageId,
                        enabled = !state.isTransactionInFlight,
                        onSelect = onSelectPlan,
                    )
                }
                state.plan(ProPlanKind.LIFETIME)?.let { lifetime ->
                    PlanCard(
                        plan = lifetime,
                        label = "Lifetime",
                        detail = "one-time, no renewal",
                        badge = "One-time",
                        isSelected = state.selectedPlanId == lifetime.packageId,
                        enabled = !state.isTransactionInFlight,
                        onSelect = onSelectPlan,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlansUnavailable(message: String, onRetry: () -> Unit) {
    val warning = MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paywall.plansUnavailable"),
        shape = CardShape,
        color = warning.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, warning.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = warning, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Plans unavailable", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .testTag("paywall.retryPlans"),
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                    Text("Try again", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: ProPlan,
    label: String,
    detail: String,
    badge: String?,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val showTrial = plan.freeTrial != null
    val container by animateColorAsState(
        if (isSelected) colors.primary.copy(alpha = 0.09f).compositeOver(colors.surface) else colors.surface,
        tween(180),
        label = "planContainer",
    )
    val border by animateColorAsState(
        if (isSelected) colors.primary.copy(alpha = 0.7f) else colors.outline,
        tween(180),
        label = "planBorder",
    )
    val billingDetail = if (showTrial) "$detail after trial" else detail

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PlanShape)
            .selectable(
                selected = isSelected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(plan.packageId)
                },
            )
            .semantics {
                contentDescription = "$label, ${if (showTrial) "free trial, then " else ""}${plan.price}, $detail"
                stateDescription = if (isSelected) "Selected" else "Not selected"
            }
            .testTag("paywall.plan.${plan.kind.name.lowercase()}"),
        shape = PlanShape,
        color = container,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, border),
    ) {
        Box {
            if (isSelected) {
                Box(
                    Modifier
                        .padding(start = 1.dp, top = 13.dp, bottom = 13.dp)
                        .width(3.dp)
                        .height(44.dp)
                        .align(Alignment.CenterStart)
                        .background(colors.primary, RoundedCornerShape(50)),
                )
            }
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
                        if (badge != null) {
                            Spacer(Modifier.width(8.dp))
                            PlanBadge(badge)
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(plan.price, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = colors.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            billingDetail,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun PlanBadge(text: String) {
    val success = MaterialTheme.colorScheme.tertiary
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.35.sp),
        color = success,
        modifier = Modifier
            .background(success.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PurchaseFooter(
    state: ProAccessUiState,
    onRestore: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            onClick = onRestore,
            enabled = !state.isTransactionInFlight,
            color = Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("paywall.restore"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isRestoring) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    "Restore purchases",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FooterLink("Privacy Policy") { onOpenUri(BillingProducts.PRIVACY_POLICY_URI) }
            Text("·", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FooterLink("Terms of Use") { onOpenUri(BillingProducts.TERMS_OF_USE_URI) }
        }
        Text(
            text = "Purchases are handled by Google Play. Subscriptions can be managed in your Google Play account settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )
    }
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.heightIn(min = 48.dp)) {
        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PurchaseBar(state: ProAccessUiState, onPurchase: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val plan = state.selectedPlan
    val enabled = !state.isLoadingPlans && plan != null && !state.isTransactionInFlight
    val haptic = LocalHapticFeedback.current

    Surface(color = colors.surface, shadowElevation = 12.dp) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (plan != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text(
                                selectedPlanName(plan),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(plan.price, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = colors.onSurface)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                pricePeriod(plan),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                    }
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onPurchase()
                        },
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .testTag("paywall.purchase")
                            .semantics {
                                if (state.isPurchasing) stateDescription = "Completing purchase"
                            },
                        shape = PlanShape,
                        color = if (plan != null) colors.primary else colors.surfaceVariant,
                        contentColor = if (plan != null) colors.onPrimary else colors.onSurfaceVariant,
                        border = BorderStroke(1.dp, colors.outline),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isPurchasing || state.isLoadingPlans) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = if (plan != null) colors.onPrimary else colors.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(subscribeButtonLabel(state), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            if (!state.isPurchasing && !state.isLoadingPlans) {
                                Spacer(Modifier.width(10.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Text(
                        text = purchaseDisclosure(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("paywall.disclosure"),
                    )
                }
            }
        }
    }
}

@Composable
private fun PaywallCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun IconTile(icon: ImageVector, size: Int) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = primary, modifier = Modifier.size((size * 0.48f).dp))
    }
}

internal fun subscribeButtonLabel(state: ProAccessUiState): String {
    if (state.isLoadingPlans) return "Loading plans…"
    val plan = state.selectedPlan ?: return "Choose a plan"
    return when (plan.kind) {
        ProPlanKind.LIFETIME -> "Buy lifetime access"
        ProPlanKind.YEARLY -> plan.freeTrial?.let { "Start ${it.badgeText}" } ?: "Subscribe yearly"
        ProPlanKind.MONTHLY -> "Subscribe monthly"
    }
}

internal fun purchaseDisclosure(state: ProAccessUiState): String {
    if (state.isLoadingPlans) return "Loading current prices and eligibility from Google Play."
    val plan = state.selectedPlan ?: return "Choose a plan to open Pro details and tools across every workspace."
    return when (plan.kind) {
        ProPlanKind.LIFETIME -> "${plan.price} one-time purchase. No renewal."
        ProPlanKind.YEARLY -> plan.freeTrial?.let { trial ->
            "${trial.badgeText.replaceFirstChar(Char::uppercaseChar)}, then ${plan.price} per year. Renews annually until canceled."
        } ?: "${plan.price} per year. Renews annually until canceled."
        ProPlanKind.MONTHLY -> "${plan.price} per month. Renews monthly until canceled."
    }
}

private fun selectedPlanName(plan: ProPlan): String = when (plan.kind) {
    ProPlanKind.LIFETIME -> "Lifetime access"
    ProPlanKind.YEARLY -> plan.freeTrial?.let { "Yearly · ${it.badgeText}" } ?: "Yearly access"
    ProPlanKind.MONTHLY -> "Monthly access"
}

private fun pricePeriod(plan: ProPlan): String = when (plan.kind) {
    ProPlanKind.LIFETIME -> "one-time"
    ProPlanKind.YEARLY -> "per year"
    ProPlanKind.MONTHLY -> "per month"
}
