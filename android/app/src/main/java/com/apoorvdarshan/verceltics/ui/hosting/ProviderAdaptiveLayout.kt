package com.apoorvdarshan.verceltics.ui.hosting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/**
 * Android counterpart of iOS `AppLayout` (`ProviderVisuals.swift`). Compact windows (< 600 dp,
 * every phone in portrait) keep today's edge-to-edge layout exactly; medium and expanded windows
 * cap readable content widths, widen page padding and switch to adaptive grids or two panes.
 */
object ProviderLayout {
    val FormMaxWidth: Dp = 620.dp
    val DetailMaxWidth: Dp = 920.dp
    val CatalogMaxWidth: Dp = 1080.dp
    val DashboardMaxWidth: Dp = 1180.dp

    /** Material medium window width; iOS `.regular` horizontal size class equivalent. */
    val MediumWindowWidth: Dp = 600.dp

    /** Material expanded window width. */
    val ExpandedWindowWidth: Dp = 840.dp

    val RegularPagePadding: Dp = 24.dp
}

/** Measured width context for one scrolling page. */
@Immutable
data class ProviderContentMetrics(
    /** The width available to the page (the window width for full-screen routes). */
    val availableWidth: Dp,
    /** Horizontal padding that keeps content inside its cap, centered. */
    val horizontalPadding: Dp,
) {
    val isRegular: Boolean get() = availableWidth >= ProviderLayout.MediumWindowWidth

    val isExpanded: Boolean get() = availableWidth >= ProviderLayout.ExpandedWindowWidth

    /** The width actually used by content after padding. */
    val contentWidth: Dp get() = max(0.dp, availableWidth - horizontalPadding * 2)

    /** iOS `AppLayout.adaptiveColumns`: one column on compact windows, otherwise as many as fit. */
    fun columns(minimumCellWidth: Dp, spacing: Dp = 12.dp, maximumColumns: Int = 4): Int {
        if (!isRegular) return 1
        val fitting = ((contentWidth + spacing) / (minimumCellWidth + spacing)).toInt()
        return fitting.coerceIn(1, maximumColumns)
    }

    /** iOS `AppAdaptiveTwoPane`: side by side only when both panes get their minimum width. */
    fun fitsTwoPanes(primaryMinimumWidth: Dp, secondaryMinimumWidth: Dp, spacing: Dp = 16.dp): Boolean =
        isRegular && contentWidth >= primaryMinimumWidth + secondaryMinimumWidth + spacing

    /** Content padding for a LazyColumn whose rows should stay within the cap. */
    fun contentPadding(top: Dp, bottom: Dp): PaddingValues =
        PaddingValues(start = horizontalPadding, top = top, end = horizontalPadding, bottom = bottom)

    companion object {
        /**
         * [compactPadding] is the screen's existing phone padding, which stays unchanged below
         * 600 dp. Wider windows use at least 24 dp and center the content at [maximumContentWidth].
         */
        fun of(availableWidth: Dp, maximumContentWidth: Dp, compactPadding: Dp): ProviderContentMetrics {
            if (availableWidth < ProviderLayout.MediumWindowWidth) {
                return ProviderContentMetrics(availableWidth, compactPadding)
            }
            val centered = (availableWidth - maximumContentWidth) / 2
            return ProviderContentMetrics(availableWidth, max(ProviderLayout.RegularPagePadding, centered))
        }
    }
}

/**
 * Measures the page width once and hands [content] the metrics it needs to cap its width. The
 * scrolling container inside keeps the full width, so pull-to-refresh and scrolling work edge to
 * edge while the content itself stays readable.
 */
@Composable
fun ProviderAdaptivePage(
    maximumContentWidth: Dp,
    modifier: Modifier = Modifier,
    compactPadding: Dp = 18.dp,
    content: @Composable (ProviderContentMetrics) -> Unit,
) {
    BoxWithConstraints(modifier) {
        content(ProviderContentMetrics.of(maxWidth, maximumContentWidth, compactPadding))
    }
}

/** Splits [items] into grid rows; one item per row on compact windows (unchanged phone layout). */
fun <T> List<T>.adaptiveRows(columns: Int): List<List<T>> = if (columns <= 1) map(::listOf) else chunked(columns)

/** One row of an adaptive grid. Missing trailing cells are left empty so cells keep equal widths. */
@Composable
fun <T> ProviderGridRow(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    cell: @Composable (T) -> Unit,
) {
    if (columns <= 1 && items.size == 1) {
        cell(items.single())
        return
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
        items.forEach { item ->
            Column(Modifier.weight(1f)) { cell(item) }
        }
        repeat((columns - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * iOS `AppAdaptiveTwoPane`: panes sit side by side when [twoPanes] is true and stack otherwise.
 */
@Composable
fun ProviderTwoPane(
    twoPanes: Boolean,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    primary: @Composable ColumnScope.() -> Unit,
    secondary: @Composable ColumnScope.() -> Unit,
) {
    if (twoPanes) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = secondary)
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            primary()
            secondary()
        }
    }
}
