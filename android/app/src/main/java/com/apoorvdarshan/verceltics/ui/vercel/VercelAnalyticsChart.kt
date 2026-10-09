package com.apoorvdarshan.verceltics.ui.vercel

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.VercelAnalyticsPointUi
import com.apoorvdarshan.verceltics.ui.theme.LocalVercelticsDarkTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Traffic chart ported from iOS `AnalyticsChart`: a Visitors / Page Views / Bounce Rate picker,
 * the period total (or mean bounce) with a peak chip, a smoothed area + line with an average
 * reference line and axes, and press-and-drag scrubbing with a selection haptic per point.
 * Hourly series longer than 48 points roll up into days.
 */
@Composable
fun VercelAnalyticsChart(
    points: List<VercelAnalyticsPointUi>,
    chartHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val metrics = remember(points) { VercelChartModel.availableMetrics(points) }
    var metric by rememberSaveable { mutableStateOf(VercelChartMetric.VISITORS) }
    LaunchedEffect(metrics) {
        if (metric !in metrics) metric = metrics.first()
    }
    val activeMetric = if (metric in metrics) metric else metrics.first()
    val series = remember(points, activeMetric) { VercelChartModel.series(points, activeMetric) }
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }
    val locale = LocalLocale.current.platformLocale
    val formats = remember(locale) { ChartDateFormats(locale) }
    val intraday = remember(series) { VercelChartModel.isIntraday(series) }
    val color = metricColor(activeMetric)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workspace.hosting.analytics.chart"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricPicker(metrics = metrics, selected = activeMetric, onSelected = { metric = it })
            Text(
                activeMetric.label.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChartHeadline(
                series = series,
                metric = activeMetric,
                selectedIndex = selectedIndex,
                dateLabel = { time -> formats.selection(time, intraday) },
            )
        }
        if (series.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight * 0.6f)
                    .testTag("workspace.hosting.analytics.chart.empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ShowChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Text(
                    "No ${activeMetric.label.lowercase(Locale.ROOT)} data",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ChartCanvas(
                series = series,
                metric = activeMetric,
                color = color,
                selectedIndex = selectedIndex,
                onSelectionChange = { selectedIndex = it },
                axisLabel = { time -> formats.axis(time, intraday) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight * 0.66f),
            )
        }
    }
}

@Composable
private fun MetricPicker(
    metrics: List<VercelChartMetric>,
    selected: VercelChartMetric,
    onSelected: (VercelChartMetric) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val content: @Composable (VercelChartMetric, Modifier) -> Unit = { metric, itemModifier ->
            val isSelected = metric == selected
            Box(
                modifier = itemModifier
                    .heightIn(min = 36.dp)
                    .padding(2.dp)
                    .background(if (isSelected) colors.surface else Color.Transparent, RoundedCornerShape(8.dp))
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelected(metric)
                            }
                        },
                    )
                    .testTag("workspace.hosting.analytics.chart.metric.${metric.name.lowercase(Locale.ROOT)}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    metric.label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (stacked) {
            Column(Modifier.selectableGroup()) {
                metrics.forEach { content(it, Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.selectableGroup()) {
                metrics.forEach { content(it, Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChartHeadline(
    series: List<VercelChartPoint>,
    metric: VercelChartMetric,
    selectedIndex: Int?,
    dateLabel: (Long) -> String,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("workspace.hosting.analytics.chart.headline"),
    ) {
        val selected = selectedIndex?.let(series::getOrNull)
        if (selected != null) {
            Text(
                VercelChartModel.formatValue(selected.value, metric),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(shape = CircleShape, color = colors.primary.copy(alpha = 0.12f)) {
                Text(
                    dateLabel(selected.timeMillis),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
            }
        } else {
            val headline = VercelChartModel.headline(series, metric)
            Text(
                VercelChartModel.formatValue(headline, metric),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val peak = VercelChartModel.peak(series)
            if (peak != null && headline > 0.0) {
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceVariant,
                    border = BorderStroke(0.5.dp, colors.outline),
                    modifier = Modifier.testTag("workspace.hosting.analytics.chart.peak"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "Peak ${VercelChartModel.formatValue(peak.value, metric)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCanvas(
    series: List<VercelChartPoint>,
    metric: VercelChartMetric,
    color: Color,
    selectedIndex: Int?,
    onSelectionChange: (Int?) -> Unit,
    axisLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
    val divider = colors.outline
    val primaryText = colors.onSurface
    val maxValue = remember(series) { series.maxOf(VercelChartPoint::value) }
    val ticks = remember(maxValue) { VercelChartModel.yTicks(maxValue) }
    val average = remember(series) { VercelChartModel.average(series) }
    val labelIndices = remember(series) { VercelChartModel.xLabelIndices(series.size) }
    val currentSeries by rememberUpdatedState(series)
    val currentSelection by rememberUpdatedState(selectedIndex)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val peak = VercelChartModel.peak(series)
    val description = buildString {
        append("${metric.label} chart, ${series.size} points")
        append(", ${VercelChartModel.formatValue(VercelChartModel.headline(series, metric), metric)}")
        append(if (metric == VercelChartMetric.BOUNCE_RATE) " average" else " total")
        if (peak != null) append(", peak ${VercelChartModel.formatValue(peak.value, metric)} on ${axisLabel(peak.timeMillis)}")
        append(".")
    }

    val density = LocalDensity.current
    Box(modifier) {
        val yLabelWidth = remember(ticks, metric, axisStyle) {
            ticks.maxOf { textMeasurer.measure(VercelChartModel.formatAxisValue(it, metric), axisStyle).size.width }
        }
        val plotLeftPx = yLabelWidth + with(density) { 8.dp.toPx() }
        val plotRightInsetPx = with(density) { 4.dp.toPx() }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(plotLeftPx, plotRightInsetPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun selectAt(x: Float) {
                            val plotWidth = (size.width - plotRightInsetPx - plotLeftPx).coerceAtLeast(1f)
                            val index = VercelChartModel.nearestIndex(currentSeries, (x - plotLeftPx) / plotWidth)
                            if (index != currentSelection) {
                                if (index != null) haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                currentOnSelectionChange(index)
                            }
                        }
                        selectAt(down.position.x)
                        var scrubbing = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val travel = change.position - down.position
                            if (!scrubbing) {
                                if (abs(travel.y) > touchSlop && abs(travel.y) > abs(travel.x)) break
                                if (abs(travel.x) > touchSlop) scrubbing = true
                            }
                            if (scrubbing && change.positionChange() != Offset.Zero) {
                                change.consume()
                                selectAt(change.position.x)
                            }
                        }
                        currentOnSelectionChange(null)
                    }
                },
        ) {
            val xLabelHeight = textMeasurer.measure("0", axisStyle).size.height.toFloat()
            val left = plotLeftPx
            val right = size.width - plotRightInsetPx
            val top = 8.dp.toPx()
            val bottom = size.height - xLabelHeight - 8.dp.toPx()
            val yMax = ticks.last().coerceAtLeast(1.0)
            fun y(value: Double): Float = (bottom - (bottom - top) * (value / yMax)).toFloat()
            fun x(index: Int): Float = left + (right - left) * VercelChartModel.xFraction(series, index)

            ticks.forEach { tick ->
                val tickY = y(tick)
                drawLine(divider, Offset(left, tickY), Offset(right, tickY), strokeWidth = 0.5.dp.toPx())
                val layout = textMeasurer.measure(VercelChartModel.formatAxisValue(tick, metric), axisStyle)
                drawText(layout, topLeft = Offset(0f, tickY - layout.size.height / 2f))
            }
            labelIndices.forEach { index ->
                val labelX = x(index)
                drawLine(divider, Offset(labelX, top), Offset(labelX, bottom), strokeWidth = 0.5.dp.toPx())
                val layout = textMeasurer.measure(axisLabel(series[index].timeMillis), axisStyle)
                val labelLeft = (labelX - layout.size.width / 2f).coerceIn(left, (size.width - layout.size.width).coerceAtLeast(left))
                drawText(layout, topLeft = Offset(labelLeft, bottom + 6.dp.toPx()))
            }
            if (average > 0.0) {
                val averageY = y(average)
                drawLine(
                    color = divider.copy(alpha = (divider.alpha * 2f).coerceAtMost(1f)),
                    start = Offset(left, averageY),
                    end = Offset(right, averageY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
                )
            }

            val xs = series.indices.map(::x)
            val ys = series.map { y(it.value) }
            val line = Path().apply {
                moveTo(xs.first(), ys.first())
                VercelChartModel.catmullRomSegments(xs, ys, minY = top, maxY = bottom).forEach { segment ->
                    cubicTo(
                        segment.control1X,
                        segment.control1Y,
                        segment.control2X,
                        segment.control2Y,
                        segment.endX,
                        segment.endY,
                    )
                }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(xs.last(), bottom)
                lineTo(xs.first(), bottom)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    0f to color.copy(alpha = 0.32f),
                    0.55f to color.copy(alpha = 0.10f),
                    1f to color.copy(alpha = 0f),
                    startY = top,
                    endY = bottom,
                ),
            )
            if (series.size == 1) {
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(xs.first(), ys.first()))
            }
            drawPath(
                line,
                Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.65f)), startX = left, endX = right),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            selectedIndex?.takeIf { it in series.indices }?.let { index ->
                val point = Offset(xs[index], ys[index])
                drawLine(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, primaryText.copy(alpha = 0.18f), primaryText.copy(alpha = 0.06f)),
                        startY = top,
                        endY = bottom,
                    ),
                    start = Offset(point.x, top),
                    end = Offset(point.x, bottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(color.copy(alpha = 0.18f), radius = 10.dp.toPx(), center = point)
                drawCircle(primaryText, radius = 4.dp.toPx(), center = point)
            }
        }
    }
}

/** iOS system blue / purple / orange, in their light and dark variants. */
@Composable
private fun metricColor(metric: VercelChartMetric): Color {
    val dark = LocalVercelticsDarkTheme.current
    return when (metric) {
        VercelChartMetric.VISITORS -> if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
        VercelChartMetric.PAGE_VIEWS -> if (dark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
        VercelChartMetric.BOUNCE_RATE -> if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    }
}

private class ChartDateFormats(locale: Locale) {
    private val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    private val time = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "jm"), locale)
    private val dayTime = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMdjm"), locale)

    fun axis(timeMillis: Long, intraday: Boolean): String =
        (if (intraday) time else day).format(Date(timeMillis))

    fun selection(timeMillis: Long, intraday: Boolean): String =
        (if (intraday) dayTime else day).format(Date(timeMillis))
}
