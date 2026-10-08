package com.apoorvdarshan.verceltics.ui.onboarding

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.verceltics.R
import com.apoorvdarshan.verceltics.domain.IntegrationCatalog
import com.apoorvdarshan.verceltics.domain.IntegrationProvider
import com.apoorvdarshan.verceltics.domain.Workspace
import com.apoorvdarshan.verceltics.ui.components.ProviderMark

/**
 * One-time welcome shown before the first connection, ported from iOS
 * `FirstConnectionWelcomeView`: brand masthead, promise, privacy note, the 27-integration
 * marquee, and a pinned "Choose what to connect" action.
 */
@Composable
fun FirstConnectionWelcomeScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAccessibilitySize = isAccessibilityFontScale(LocalDensity.current.fontScale)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("firstLaunch.welcome"),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val usesRegularLayout = usesRegularWelcomeLayout(
                availableWidthDp = maxWidth.value,
                isAccessibilitySize = isAccessibilitySize,
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    .padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 28.dp)
                    .testTag("firstLaunch.welcome.content"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (usesRegularLayout) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = 1080.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WelcomeIntroduction(
                            isAccessibilitySize = false,
                            modifier = Modifier.width(350.dp),
                        )
                        ProviderCatalogMarquee(modifier = Modifier.weight(1f))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        WelcomeIntroduction(isAccessibilitySize = isAccessibilitySize)
                        ProviderCatalogMarquee()
                    }
                }
            }
        }
        WelcomeActionBar(
            onContinue = onContinue,
            isAccessibilitySize = isAccessibilitySize,
        )
    }
}

/** iOS switches to the side-by-side layout once the regular layout fits its 860pt minimum. */
internal fun usesRegularWelcomeLayout(availableWidthDp: Float, isAccessibilitySize: Boolean): Boolean =
    !isAccessibilitySize && availableWidthDp >= 860f + 44f

/** Android counterpart of `DynamicTypeSize.isAccessibilitySize`, used across the app at 1.3x. */
internal fun isAccessibilityFontScale(fontScale: Float): Boolean = fontScale >= 1.3f

/** Android counterpart of `dynamicTypeSize >= .xxLarge`. */
internal fun isLargeFontScale(fontScale: Float): Boolean = fontScale >= 1.15f

@Composable
private fun WelcomeIntroduction(
    isAccessibilitySize: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        WelcomeMasthead(isAccessibilitySize = isAccessibilitySize)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.onboarding_headline),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        PrivacyNote()
        if (isAccessibilitySize) {
            Text(
                text = stringResource(R.string.onboarding_no_account),
                color = tertiaryTextColor(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WelcomeMasthead(isAccessibilitySize: Boolean) {
    if (isAccessibilitySize) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrandLockup()
            OpenSourceLabel()
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandLockup()
            Spacer(Modifier.weight(1f).width(10.dp))
            OpenSourceLabel()
        }
    }
}

@Composable
private fun BrandLockup() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colorResource(R.color.launcher_black))
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The adaptive icon foreground uses a 108dp canvas with a 72dp visible area.
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(60.dp),
            )
        }
        Text(
            text = stringResource(R.string.onboarding_brand),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun OpenSourceLabel() {
    Text(
        text = stringResource(R.string.onboarding_open_source),
        color = tertiaryTextColor(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun PrivacyNote() {
    Row(
        modifier = Modifier.testTag("firstLaunch.privacy"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Security,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_privacy),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WelcomeActionBar(
    onContinue: () -> Unit,
    isAccessibilitySize: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = 12.dp,
        tonalElevation = 0.dp,
    ) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ContinueButton(onClick = onContinue, isAccessibilitySize = isAccessibilitySize)
                    if (!isAccessibilitySize) {
                        Text(
                            text = stringResource(R.string.onboarding_no_account),
                            color = tertiaryTextColor(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueButton(
    onClick: () -> Unit,
    isAccessibilitySize: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .testTag("firstLaunch.continue"),
        shape = RoundedCornerShape(17.dp),
        color = colors.onBackground,
        contentColor = colors.background,
        border = BorderStroke(0.5.dp, colors.outline),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = if (isAccessibilitySize) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (isAccessibilitySize) R.string.onboarding_continue_compact else R.string.onboarding_continue,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(colors.background.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// MARK: - Integration marquee

private enum class MarqueeDirection { LEFT, RIGHT }

/** Total integrations shown by the welcome marquee; iOS shows the same 27 across three lanes. */
internal val WelcomeIntegrationCount: Int
    get() = Workspace.entries.sumOf { IntegrationCatalog.providers(it).size }

@Composable
private fun ProviderCatalogMarquee(modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    val autoMoves = rememberMarqueeAutoMotion(fontScale)
    Column(
        modifier = modifier.testTag("firstLaunch.marquee"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val title: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.onboarding_catalog_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.05.sp),
                fontWeight = FontWeight.Bold,
            )
        }
        val count: @Composable () -> Unit = {
            Text(
                text = stringResource(R.string.onboarding_integration_count, WelcomeIntegrationCount),
                modifier = Modifier.testTag("firstLaunch.integrationCount"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                fontWeight = FontWeight.Bold,
            )
        }
        if (isLargeFontScale(fontScale)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                title()
                count()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                title()
                Spacer(Modifier.weight(1f).width(8.dp))
                count()
            }
        }

        ProviderMarqueeLane(
            id = "hosting",
            title = stringResource(R.string.onboarding_lane_hosting),
            providers = IntegrationCatalog.providers(Workspace.HOSTING),
            durationMillis = 54_000,
            direction = MarqueeDirection.LEFT,
            standardChipWidth = 150.dp,
            autoMoves = autoMoves,
        )
        ProviderMarqueeLane(
            id = "domains",
            title = stringResource(R.string.onboarding_lane_domains),
            providers = IntegrationCatalog.providers(Workspace.REGISTRARS),
            durationMillis = 48_000,
            direction = MarqueeDirection.RIGHT,
            standardChipWidth = 156.dp,
            autoMoves = autoMoves,
        )
        ProviderMarqueeLane(
            id = "sites",
            title = stringResource(R.string.onboarding_lane_sites),
            providers = IntegrationCatalog.providers(Workspace.SITES),
            durationMillis = 52_000,
            direction = MarqueeDirection.LEFT,
            standardChipWidth = 208.dp,
            autoMoves = autoMoves,
        )
    }
}

/**
 * Mirrors iOS `shouldAutoMove`: lanes animate only without reduced motion (Android "Remove
 * animations"), without a screen reader, and below large font scales. Otherwise each lane is a
 * manually scrollable row.
 */
@Composable
private fun rememberMarqueeAutoMotion(fontScale: Float): Boolean {
    val context = LocalContext.current
    val reduceMotion = remember(context) { isReduceMotionEnabled(context) }
    val screenReader = remember(context) { isTouchExplorationEnabled(context) }
    return shouldAutoMoveMarquee(
        reduceMotion = reduceMotion,
        screenReaderEnabled = screenReader,
        fontScale = fontScale,
    )
}

internal fun shouldAutoMoveMarquee(
    reduceMotion: Boolean,
    screenReaderEnabled: Boolean,
    fontScale: Float,
): Boolean = !reduceMotion && !screenReaderEnabled && !isLargeFontScale(fontScale)

private fun isReduceMotionEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

private fun isTouchExplorationEnabled(context: Context): Boolean = runCatching {
    context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
}.getOrDefault(false)

@Composable
private fun ProviderMarqueeLane(
    id: String,
    title: String,
    providers: List<IntegrationProvider>,
    durationMillis: Int,
    direction: MarqueeDirection,
    standardChipWidth: Dp,
    autoMoves: Boolean,
) {
    val fontScale = LocalDensity.current.fontScale
    val chipWidth = when {
        isAccessibilityFontScale(fontScale) -> maxOf(210.dp, standardChipWidth + 24.dp)
        isLargeFontScale(fontScale) -> standardChipWidth + 12.dp
        else -> standardChipWidth
    }
    val laneHeight = when {
        isAccessibilityFontScale(fontScale) -> 90.dp
        isLargeFontScale(fontScale) -> 58.dp
        else -> 48.dp
    }
    val description = stringResource(
        R.string.onboarding_lane_description,
        title.lowercase().replaceFirstChar { it.titlecase() },
        providers.size,
        providers.joinToString { it.displayName },
    )
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            testTag = "firstLaunch.lane.$id"
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = providers.size.toString(),
                color = tertiaryTextColor(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (autoMoves) {
            AutomaticMarqueeLane(
                providers = providers,
                durationMillis = durationMillis,
                direction = direction,
                chipWidth = chipWidth,
                laneHeight = laneHeight,
            )
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(laneHeight),
                horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
                contentPadding = PaddingValues(horizontal = 1.dp),
            ) {
                items(providers, key = IntegrationProvider::id) { provider ->
                    ProviderChip(provider = provider, width = chipWidth, height = laneHeight)
                }
            }
        }
    }
}

@Composable
private fun AutomaticMarqueeLane(
    providers: List<IntegrationProvider>,
    durationMillis: Int,
    direction: MarqueeDirection,
    chipWidth: Dp,
    laneHeight: Dp,
) {
    val cycleWidthPx = with(LocalDensity.current) { (chipWidth + ChipSpacing).toPx() } * providers.size
    val transition = rememberInfiniteTransition(label = "welcome.marquee")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "welcome.marquee.progress",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(laneHeight)
            .clipToBounds()
            .horizontalEdgeFade(),
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .graphicsLayer {
                    // Read in the layer block so each frame only redraws, never recomposes.
                    val travel = progress * cycleWidthPx
                    translationX = when (direction) {
                        MarqueeDirection.LEFT -> -travel
                        MarqueeDirection.RIGHT -> -cycleWidthPx + travel
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            repeat(3) {
                providers.forEach { provider ->
                    ProviderChip(provider = provider, width = chipWidth, height = laneHeight)
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(
    provider: IntegrationProvider,
    width: Dp,
    height: Dp,
) {
    val shape = RoundedCornerShape(12.dp)
    val isAccessibilitySize = isAccessibilityFontScale(LocalDensity.current.fontScale)
    Row(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMark(provider = provider, size = 32.dp)
        Text(
            text = provider.displayName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = if (isAccessibilitySize) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.horizontalEdgeFade(): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.035f to Color.Black,
                0.965f to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun tertiaryTextColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)

private val ChipSpacing = 10.dp
