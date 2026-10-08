package com.apoorvdarshan.verceltics.ui.vercel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material window width classes; MEDIUM and EXPANDED match the iOS regular horizontal size class. */
enum class VercelWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

fun vercelWidthClass(widthDp: Float): VercelWidthClass = when {
    widthDp < VercelLayout.MEDIUM_MIN_WIDTH_DP -> VercelWidthClass.COMPACT
    widthDp < VercelLayout.EXPANDED_MIN_WIDTH_DP -> VercelWidthClass.MEDIUM
    else -> VercelWidthClass.EXPANDED
}

/**
 * Horizontal metrics of one Vercel page: the width class, the side padding that also centers the
 * content at its maximum width (iOS `AppLayout.pagePadding` plus `appContentWidth`), and the
 * width left for content.
 */
data class VercelPageMetrics(
    val widthClass: VercelWidthClass,
    val horizontalPaddingDp: Float,
    val contentWidthDp: Float,
) {
    val isRegular: Boolean
        get() = widthClass != VercelWidthClass.COMPACT

    val horizontalPadding: Dp
        get() = horizontalPaddingDp.dp
}

/**
 * Tablet and large-window layout policy for the Vercel screens, ported from iOS regular-width
 * layouts. Compact windows (phones) keep their single-column layouts unchanged.
 */
object VercelLayout {
    const val MEDIUM_MIN_WIDTH_DP: Float = 600f
    const val EXPANDED_MIN_WIDTH_DP: Float = 840f
    const val REGULAR_PAGE_PADDING_DP: Float = 24f

    /** iOS `AppLayout.dashboardMaxWidth`, `AnalyticsView` 1100 and `AppLayout.detailMaxWidth`. */
    const val DASHBOARD_MAX_WIDTH_DP: Float = 1_180f
    const val ANALYTICS_MAX_WIDTH_DP: Float = 1_100f
    const val DETAIL_MAX_WIDTH_DP: Float = 920f

    const val PROJECT_CARD_MIN_WIDTH_DP: Float = 340f
    const val PROJECT_GRID_SPACING_DP: Float = 12f
    const val MEDIUM_PANEL_MIN_WIDTH_DP: Float = 280f
    const val EXPANDED_PANEL_MIN_WIDTH_DP: Float = 320f
    const val PANEL_SPACING_DP: Float = 16f
    const val MAX_COLUMNS: Int = 3

    /** iOS `DeploymentDetailView` regular width: a 300pt details column beside 420pt+ of events. */
    const val DEPLOYMENT_DETAILS_WIDTH_DP: Float = 300f
    const val DEPLOYMENT_EVENTS_MIN_WIDTH_DP: Float = 420f

    /**
     * [availableWidthDp] is the width the screen was given; [windowWidthDp] the whole window,
     * which decides the width class even when a navigation rail takes part of it.
     */
    fun page(
        availableWidthDp: Float,
        windowWidthDp: Float,
        compactPaddingDp: Float,
        maxContentWidthDp: Float,
    ): VercelPageMetrics {
        val widthClass = vercelWidthClass(maxOf(availableWidthDp, windowWidthDp))
        val basePadding = if (widthClass == VercelWidthClass.COMPACT) compactPaddingDp else REGULAR_PAGE_PADDING_DP
        val padding = maxOf(basePadding, (availableWidthDp - maxContentWidthDp) / 2f)
        return VercelPageMetrics(
            widthClass = widthClass,
            horizontalPaddingDp = padding,
            contentWidthDp = (availableWidthDp - 2f * padding).coerceAtLeast(0f),
        )
    }

    /** One column on phones; adaptive 340dp cards (up to three) on regular widths, like iOS. */
    fun projectColumns(metrics: VercelPageMetrics): Int =
        if (!metrics.isRegular) {
            1
        } else {
            columnsFor(metrics.contentWidthDp, PROJECT_CARD_MIN_WIDTH_DP, PROJECT_GRID_SPACING_DP)
        }

    /** Analytics panels: one column on phones, two or three side by side on regular widths. */
    fun analyticsPanelColumns(metrics: VercelPageMetrics): Int = when (metrics.widthClass) {
        VercelWidthClass.COMPACT -> 1
        VercelWidthClass.MEDIUM -> columnsFor(metrics.contentWidthDp, MEDIUM_PANEL_MIN_WIDTH_DP, PANEL_SPACING_DP)
        VercelWidthClass.EXPANDED -> columnsFor(metrics.contentWidthDp, EXPANDED_PANEL_MIN_WIDTH_DP, PANEL_SPACING_DP)
    }

    /** Details beside build events when both columns fit (iOS `ViewThatFits`), else stacked. */
    fun deploymentUsesTwoColumns(metrics: VercelPageMetrics): Boolean =
        metrics.isRegular &&
            metrics.contentWidthDp >= DEPLOYMENT_DETAILS_WIDTH_DP + PANEL_SPACING_DP + DEPLOYMENT_EVENTS_MIN_WIDTH_DP

    private fun columnsFor(widthDp: Float, minimumDp: Float, spacingDp: Float): Int =
        ((widthDp + spacingDp) / (minimumDp + spacingDp)).toInt().coerceIn(1, MAX_COLUMNS)
}

/** Accessibility state for a multi-column list, e.g. "2 columns". */
fun vercelColumnsDescription(columns: Int): String = if (columns == 1) "1 column" else "$columns columns"

/** The current window width in dp, used to pick a width class. */
@Composable
@ReadOnlyComposable
internal fun vercelWindowWidthDp(): Float {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return with(LocalDensity.current) { widthPx.toDp().value }
}
