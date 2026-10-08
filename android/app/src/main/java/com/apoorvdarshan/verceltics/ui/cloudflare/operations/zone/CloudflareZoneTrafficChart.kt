package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.data.cloudflare.operations.CloudflareFormat
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareAnalyticsGranularity
import com.apoorvdarshan.verceltics.data.cloudflare.operations.zone.CloudflareZoneAnalyticsPoint
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Requests over time as an area + line chart (iOS Swift Charts `AreaMark` + `LineMark`). */
@Composable
fun CloudflareZoneTrafficChart(
    series: List<CloudflareZoneAnalyticsPoint>,
    granularity: CloudflareAnalyticsGranularity,
    modifier: Modifier = Modifier,
) {
    if (series.isEmpty()) return
    val values = series.map { it.metrics.requests }
    val maximum = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val accent = CloudflareOpsColors.Orange
    val label = "Requests trend from ${formatTick(series.first().timestamp, granularity)} to " +
        "${formatTick(series.last().timestamp, granularity)}, peak ${CloudflareFormat.compact(maximum)} requests."
    Column(modifier.fillMaxWidth().testTag("cloudflare.zone.trafficChart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(CloudflareFormat.compact(maximum), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .semantics { contentDescription = label },
        ) {
            val width = size.width
            val height = size.height
            for (line in 0..2) {
                val y = height * line / 2f
                drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
            }
            val points = values.mapIndexed { index, value ->
                val x = if (values.size == 1) width / 2f else width * index / (values.size - 1).toFloat()
                val y = height - (value.toFloat() / maximum.toFloat()) * height * 0.94f
                Offset(x, y)
            }
            val line = Path().apply {
                points.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, height)
                lineTo(points.first().x, height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(line, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTick(series.first().timestamp, granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTick(series.last().timestamp, granularity), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTick(instant: Instant, granularity: CloudflareAnalyticsGranularity): String {
    val pattern = if (granularity == CloudflareAnalyticsGranularity.DAILY) "EEE d MMM" else "EEE HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(ZoneId.systemDefault()).format(instant)
}
