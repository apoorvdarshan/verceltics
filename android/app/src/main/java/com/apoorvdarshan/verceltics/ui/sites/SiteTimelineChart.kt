package com.apoorvdarshan.verceltics.ui.sites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** A y-axis domain whose ticks land on round values (1, 2, 2.5, 5 × 10ⁿ), like Swift Charts. */
internal data class ChartAxisScale(
    val minimum: Double,
    val maximum: Double,
    val ticks: List<Double>,
) {
    /** 0 at [minimum], 1 at [maximum]. Values outside the domain are clamped. */
    fun fraction(value: Double): Float {
        if (!value.isFinite() || maximum <= minimum) return 0f
        return ((value - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Computes a bounded "nice" axis for [values]. Non-finite values are ignored. Count metrics should
 * keep [includeZero] so area fills start at the baseline; ranked metrics (average position) and
 * percentiles look better scaled to their own range.
 */
internal fun chartAxisScale(
    values: List<Double>,
    desiredTickCount: Int = 4,
    includeZero: Boolean = true,
): ChartAxisScale {
    val finite = values.filter(Double::isFinite)
    val ticksWanted = desiredTickCount.coerceIn(2, 8)
    if (finite.isEmpty()) return ChartAxisScale(0.0, 1.0, listOf(0.0, 0.5, 1.0))
    var low = finite.min()
    var high = finite.max()
    if (includeZero) {
        low = minOf(low, 0.0)
        high = maxOf(high, 0.0)
    }
    if (high == low) {
        // A flat series still needs a visible band around its single value.
        if (low == 0.0) {
            high = 1.0
        } else {
            val pad = abs(low) * 0.1
            low -= pad
            high += pad
            if (includeZero) {
                low = minOf(low, 0.0)
                high = maxOf(high, 0.0)
            }
        }
    }
    val step = niceNumber((high - low) / (ticksWanted - 1), round = true)
    val niceLow = floor(low / step) * step
    val niceHigh = ceil(high / step) * step
    val count = ((niceHigh - niceLow) / step).roundToInt().coerceIn(1, 12)
    val ticks = (0..count).map { index -> cleanTick(niceLow + index * step, step) }
    return ChartAxisScale(ticks.first(), ticks.last(), ticks)
}

private fun niceNumber(range: Double, round: Boolean): Double {
    if (range <= 0.0 || !range.isFinite()) return 1.0
    val exponent = floor(log10(range))
    val fraction = range / 10.0.pow(exponent)
    val nice = if (round) {
        when {
            fraction < 1.5 -> 1.0
            fraction < 2.25 -> 2.0
            fraction < 3.0 -> 2.5
            fraction < 7.0 -> 5.0
            else -> 10.0
        }
    } else {
        when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
    }
    return nice * 10.0.pow(exponent)
}

/** Removes binary floating-point noise such as 0.30000000000000004 from tick values. */
private fun cleanTick(value: Double, step: Double): Double {
    val decimals = (-floor(log10(step)).toInt() + 2).coerceIn(0, 12)
    val factor = 10.0.pow(decimals)
    val rounded = Math.round(value * factor) / factor
    return if (rounded == -0.0) 0.0 else rounded
}

/** Short axis label: 1.2K, 3.4M, 0.25, 12. Mirrors Swift's compact number notation. */
internal fun formatCompactAxisValue(value: Double, locale: java.util.Locale = java.util.Locale.getDefault()): String {
    if (!value.isFinite()) return "—"
    val absolute = abs(value)
    val (scaled, suffix) = when {
        absolute >= 1_000_000_000 -> value / 1_000_000_000 to "B"
        absolute >= 1_000_000 -> value / 1_000_000 to "M"
        absolute >= 1_000 -> value / 1_000 to "K"
        else -> value to ""
    }
    val fractionDigits = when {
        suffix.isNotEmpty() -> 1
        absolute == 0.0 || absolute >= 100 -> 0
        absolute >= 10 -> 1
        else -> 2
    }
    val format = java.text.NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = fractionDigits
        isGroupingUsed = true
    }
    return format.format(scaled) + suffix
}

/** Nearest evenly spaced point index for a horizontal touch position. */
internal fun chartIndexForPosition(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= 0f || !x.isFinite()) return null
    if (count == 1) return 0
    val step = width / (count - 1)
    return (x / step).roundToInt().coerceIn(0, count - 1)
}

internal data class TimelineChartPoint(
    val label: String,
    val value: Double,
)

/**
 * Line/area chart with labelled y-axis ticks, x-axis endpoints and optional scrubbing. Selection is
 * hoisted so callers can show the scrubbed value in their own header, as iOS does.
 */
@Composable
internal fun SiteTimelineChart(
    points: List<TimelineChartPoint>,
    accent: Color,
    formatValue: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    showArea: Boolean = true,
    includeZero: Boolean = true,
    selectedIndex: Int? = null,
    onSelectedIndexChange: ((Int?) -> Unit)? = null,
    testTag: String? = null,
) {
    val values = remember(points) { points.map(TimelineChartPoint::value) }
    val scale = remember(values, includeZero) { chartAxisScale(values, includeZero = includeZero) }
    val tickLabels = remember(scale, formatValue) { scale.ticks.map(formatValue) }
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = remember(tickLabels, labelStyle, density) {
        val widest = tickLabels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
        with(density) { widest.toDp() } + 8.dp
    }
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val rule = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val currentSelection = rememberUpdatedState(selectedIndex)
    val selectionChanged = rememberUpdatedState(onSelectedIndexChange)
    val selected = selectedIndex?.takeIf { it in points.indices }
    val verticalPad = 7.dp

    Column(modifier.then(if (testTag == null) Modifier else Modifier.testTag(testTag))) {
        Row(Modifier.fillMaxWidth().height(chartHeight)) {
            YAxisLabels(
                labels = tickLabels,
                fractions = scale.ticks.map(scale::fraction),
                verticalPad = verticalPad,
                color = muted,
                modifier = Modifier
                    .width(gutter)
                    .fillMaxHeight()
                    .then(if (testTag == null) Modifier else Modifier.testTag("$testTag.yAxis")),
            )
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onSelectedIndexChange == null || points.isEmpty()) {
                            Modifier
                        } else {
                            Modifier
                                .pointerInput(points.size) {
                                    detectTapGestures { offset ->
                                        val index = chartIndexForPosition(offset.x, size.width.toFloat(), points.size)
                                        selectionChanged.value?.invoke(
                                            if (index == currentSelection.value) null else index,
                                        )
                                    }
                                }
                                .pointerInput(points.size) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            selectionChanged.value?.invoke(
                                                chartIndexForPosition(offset.x, size.width.toFloat(), points.size),
                                            )
                                        },
                                    ) { change, _ ->
                                        change.consume()
                                        selectionChanged.value?.invoke(
                                            chartIndexForPosition(change.position.x, size.width.toFloat(), points.size),
                                        )
                                    }
                                }
                        },
                    )
                    .semantics {
                        contentDescription = description
                        selected?.let { index ->
                            stateDescription = "${points[index].label}, ${formatValue(points[index].value)}"
                        }
                        if (onSelectedIndexChange != null && points.size > 1) {
                            customActions = listOf(
                                CustomAccessibilityAction("Next point") {
                                    val next = ((currentSelection.value ?: -1) + 1).coerceAtMost(points.lastIndex)
                                    selectionChanged.value?.invoke(next)
                                    true
                                },
                                CustomAccessibilityAction("Previous point") {
                                    val previous = ((currentSelection.value ?: points.size) - 1).coerceAtLeast(0)
                                    selectionChanged.value?.invoke(previous)
                                    true
                                },
                            )
                        }
                    },
            ) {
                val top = verticalPad.toPx()
                val plotHeight = size.height - top * 2
                fun y(value: Double): Float = top + plotHeight * (1f - scale.fraction(value))
                scale.ticks.forEach { tick ->
                    val lineY = y(tick)
                    drawLine(grid, Offset(0f, lineY), Offset(size.width, lineY), strokeWidth = 1f)
                }
                if (values.isEmpty()) return@Canvas
                val stepX = if (values.size == 1) 0f else size.width / (values.size - 1)
                fun point(index: Int): Offset = Offset(
                    if (values.size == 1) size.width / 2 else index * stepX,
                    y(values[index]),
                )
                val line = Path()
                val area = Path()
                val baseline = y(scale.minimum.coerceAtLeast(minOf(0.0, scale.maximum)))
                values.indices.forEach { index ->
                    val offset = point(index)
                    if (index == 0) {
                        line.moveTo(offset.x, offset.y)
                        area.moveTo(offset.x, baseline)
                        area.lineTo(offset.x, offset.y)
                    } else {
                        line.lineTo(offset.x, offset.y)
                        area.lineTo(offset.x, offset.y)
                    }
                }
                if (showArea) {
                    area.lineTo(point(values.lastIndex).x, baseline)
                    area.close()
                    drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.02f))))
                }
                drawPath(line, accent, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (values.size == 1) drawCircle(accent, radius = 4.dp.toPx(), center = point(0))
                selected?.let { index ->
                    val center = point(index)
                    drawLine(
                        rule,
                        Offset(center.x, top),
                        Offset(center.x, size.height - top),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    )
                    drawCircle(accent, radius = 5.dp.toPx(), center = center)
                    drawCircle(Color.White, radius = 2.dp.toPx(), center = center)
                }
            }
        }
        if (points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = gutter, top = 4.dp)) {
                Text(points.first().label, style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (points.size > 2) {
                    Spacer(Modifier.weight(1f))
                    Text(points[points.size / 2].label, style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (points.size > 1) {
                    Text(points.last().label, style = labelStyle, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun YAxisLabels(
    labels: List<String>,
    fractions: List<Float>,
    verticalPad: Dp,
    color: Color,
    modifier: Modifier,
) {
    Layout(
        content = {
            labels.forEach { label ->
                Box { Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1) }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = constraints.maxHeight
        val pad = verticalPad.roundToPx()
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable ->
                val center = pad + ((height - pad * 2) * (1f - fractions.getOrElse(index) { 0f }))
                val top = (center - placeable.height / 2f).roundToInt().coerceIn(0, (height - placeable.height).coerceAtLeast(0))
                val right = constraints.maxWidth - placeable.width - 6.dp.roundToPx()
                placeable.place(right.coerceAtLeast(0), top)
            }
        }
    }
}
