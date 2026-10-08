package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window-width classes for the site workspaces, using the Material breakpoints (600 dp / 840 dp).
 * Compact windows (phones) keep the original single-column layouts exactly.
 */
enum class SiteWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    val isRegular: Boolean get() = this != COMPACT

    companion object {
        fun of(width: Dp): SiteWidthClass = when {
            width >= SiteLayout.ExpandedWidth -> EXPANDED
            width >= SiteLayout.MediumWidth -> MEDIUM
            else -> COMPACT
        }
    }
}

/** Shared responsive dimensions (iOS `AppLayout`): caps, regular padding, and adaptive columns. */
object SiteLayout {
    val MediumWidth: Dp = 600.dp
    val ExpandedWidth: Dp = 840.dp
    val FormMaxWidth: Dp = 620.dp
    val DetailMaxWidth: Dp = 920.dp
    val CatalogMaxWidth: Dp = 1080.dp
    val DashboardMaxWidth: Dp = 1180.dp
    val RegularPagePadding: Dp = 24.dp

    /** Width of the fixed summary/controls pane in expanded two-pane layouts. */
    val SidePaneWidth: Dp = 360.dp

    /**
     * Horizontal padding for a full-width scroller: [compactPadding] on phones, otherwise at least
     * the regular page padding and enough to center content no wider than [maxContentWidth]. The
     * scroller itself stays full width so gestures and pull-to-refresh work edge to edge.
     */
    fun horizontalPadding(width: Dp, compactPadding: Dp, maxContentWidth: Dp): Dp {
        if (width < MediumWidth) return compactPadding
        val centered = (width - maxContentWidth) / 2
        return if (centered > RegularPagePadding) centered else RegularPagePadding
    }

    /** Content width left after [horizontalPadding]. */
    fun contentWidth(width: Dp, compactPadding: Dp, maxContentWidth: Dp): Dp =
        (width - horizontalPadding(width, compactPadding, maxContentWidth) * 2).coerceAtLeast(0.dp)

    /**
     * Adaptive grid columns (iOS `adaptiveColumns(regularMinimum:)`): one column on phones, else as
     * many [minimum]-wide cells as fit, capped at [maximumColumns].
     */
    fun columns(width: Dp, contentWidth: Dp, minimum: Dp, spacing: Dp, maximumColumns: Int = 4): Int {
        if (width < MediumWidth) return 1
        val fitting = ((contentWidth + spacing) / (minimum + spacing)).toInt()
        return fitting.coerceIn(1, maximumColumns)
    }

    fun padding(
        width: Dp,
        compactPadding: Dp,
        maxContentWidth: Dp,
        top: Dp,
        bottom: Dp,
    ): PaddingValues {
        val horizontal = horizontalPadding(width, compactPadding, maxContentWidth)
        return PaddingValues(start = horizontal, top = top, end = horizontal, bottom = bottom)
    }
}

/**
 * Lays [values] out as an adaptive grid inside a lazy list. With one column it emits exactly the
 * same keyed items as a plain `items(...)` call, so phone layouts and scroll state are unchanged.
 */
fun <T> LazyListScope.adaptiveGridItems(
    values: List<T>,
    columns: Int,
    key: (T) -> String,
    spacing: Dp,
    content: @Composable (T) -> Unit,
) {
    if (columns <= 1) {
        items(values, key = key) { value -> content(value) }
        return
    }
    val rows = values.chunked(columns)
    items(rows, key = { row -> "grid-" + row.joinToString("|", transform = key) }) { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.Top,
        ) {
            row.forEach { value ->
                Box(Modifier.weight(1f)) { content(value) }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
