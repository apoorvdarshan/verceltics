package com.apoorvdarshan.verceltics.ui.billing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.billing.BillingProducts
import com.apoorvdarshan.verceltics.billing.TipJarUiState
import com.apoorvdarshan.verceltics.billing.TipProduct

private data class TipMeta(
    val icon: ImageVector,
    val title: String,
    val blurb: String,
    val popular: Boolean = false,
)

private fun tipMeta(id: String): TipMeta = when (id) {
    BillingProducts.TIP_COFFEE_ID -> TipMeta(Icons.Rounded.LocalCafe, "Coffee", "A little caffeine")
    BillingProducts.TIP_LUNCH_ID -> TipMeta(Icons.Rounded.Restaurant, "Lunch", "Treat me to lunch", popular = true)
    BillingProducts.TIP_BIG_ID -> TipMeta(Icons.AutoMirrored.Rounded.Send, "Big Tip", "Really generous")
    BillingProducts.TIP_HUGE_ID -> TipMeta(Icons.Rounded.Diamond, "Huge Supporter", "You're amazing")
    else -> TipMeta(Icons.Rounded.Favorite, "Tip", "Support development")
}

/** Inline tip jar for About, matching the iOS tiers. Tips are optional and unlock nothing. */
@Composable
fun TipJarContent(
    state: TipJarUiState,
    onTip: (String) -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("about.tipJar")) {
        if (state.didTip) {
            TipThankYou(onDone)
            return@Column
        }
        Text(
            text = "A one-time tip — completely optional and unlocks nothing.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 6.dp),
        )
        when {
            state.isLoading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 26.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Loading tip options…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.loadFailed -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Couldn't load tip options right now.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TipCapsuleButton(text = "Try Again", onClick = onRetry, testTag = "about.tipJar.retry")
            }

            else -> state.products.forEachIndexed { index, product ->
                TipRow(
                    product = product,
                    isBusy = state.purchasingId == product.id,
                    enabled = state.purchasingId == null,
                    onTip = onTip,
                )
                if (index < state.products.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun TipRow(
    product: TipProduct,
    isBusy: Boolean,
    enabled: Boolean,
    onTip: (String) -> Unit,
) {
    val meta = tipMeta(product.id)
    val accent = MaterialTheme.colorScheme.primary
    val haptic = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onTip(product.id)
        },
        enabled = enabled,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${meta.title}, ${meta.blurb}, ${product.price}" }
            .testTag("about.tipJar.${product.id.substringAfterLast('.')}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(meta.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    if (meta.popular) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "POPULAR",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(accent, RoundedCornerShape(50))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(meta.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .widthIn(min = 66.dp)
                    .background(accent.copy(alpha = if (isBusy) 0.85f else 1f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(product.price, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun TipThankYou(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 26.dp, horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
        Text("Thank you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Your tip supports continued development of Verceltics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TipCapsuleButton(text = "Done", onClick = onDone, testTag = "about.tipJar.done")
    }
}

@Composable
private fun TipCapsuleButton(text: String, onClick: () -> Unit, testTag: String) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .testTag(testTag),
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
