package com.apoorvdarshan.verceltics.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True when the user turned animations off (Developer options or Accessibility "Remove
 * animations"), the Android equivalent of iOS `accessibilityReduceMotion`.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * A soft highlight sweeping diagonally across the content's own pixels every 1.6 s, ported from
 * iOS `.shimmering()`. The band is masked to what the content draws, so placeholder shapes keep
 * their outlines. Disabled entirely when animations are reduced.
 */
fun Modifier.shimmer(enabled: Boolean = true): Modifier = composed {
    if (!enabled || rememberReducedMotion()) return@composed this
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerPhase",
    )
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val bandWidth = size.width * 1.5f
            val startX = phase * size.width
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(startX, 0f),
                    end = Offset(startX + bandWidth, size.height),
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** Placeholder bar used by skeleton layouts; [strong] matches iOS `skeletonStrong`. */
@Composable
fun SkeletonBlock(
    width: Dp?,
    height: Dp,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (strong) 0.10f else 0.06f)
    Box(
        modifier
            .then(if (width == null) Modifier else Modifier.width(width))
            .height(height)
            .background(color, shape)
            .clearAndSetSemantics { },
    )
}
