package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.verceltics.ui.cloudflare.operations.CloudflareOpsColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * One end of a custom traffic window, edited to the minute in the device time zone (iOS
 * `DatePicker(displayedComponents: [.date, .hourAndMinute])`).
 */
data class CloudflareRangeEndpoint(val date: LocalDate, val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23 && minute in 0..59) { "Invalid time of day." }
    }

    fun toInstant(zone: ZoneId): Instant = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant()

    companion object {
        fun of(instant: Instant, zone: ZoneId): CloudflareRangeEndpoint {
            val local = instant.atZone(zone)
            return CloudflareRangeEndpoint(local.toLocalDate(), local.hour, local.minute)
        }
    }
}

/** Pure validation for the custom traffic window, shared by the dialog and its tests. */
object CloudflareCustomRange {
    const val START_AFTER_END_MESSAGE: String = "The start must be earlier than the end."
    const val FUTURE_START_MESSAGE: String = "The start can’t be in the future."

    /**
     * Resolves [from]–[to] to the minute. Like iOS (`in: ...Date()`), neither end may be in the
     * future: an end later than [now] is clamped to [now] and a future start is rejected.
     */
    fun resolve(
        from: CloudflareRangeEndpoint,
        to: CloudflareRangeEndpoint,
        zone: ZoneId,
        now: Instant,
    ): Result<Pair<Instant, Instant>> {
        val start = from.toInstant(zone)
        val end = minOf(to.toInstant(zone), now.truncatedTo(ChronoUnit.SECONDS))
        return when {
            start.isAfter(now) -> Result.failure(IllegalArgumentException(FUTURE_START_MESSAGE))
            !start.isBefore(end) -> Result.failure(IllegalArgumentException(START_AFTER_END_MESSAGE))
            else -> Result.success(start to end)
        }
    }

    fun formatDate(date: LocalDate): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date)

    fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)
}

/** iOS `CloudflareCustomAnalyticsRangeView`: From and To pickers with date, hour and minute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudflareCustomRangeDialog(
    initialFrom: Instant,
    initialTo: Instant,
    onApply: (Instant, Instant) -> String?,
    onDismiss: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    now: () -> Instant = Instant::now,
) {
    val initialStart = CloudflareRangeEndpoint.of(initialFrom, zone)
    val initialEnd = CloudflareRangeEndpoint.of(minOf(initialTo, now()), zone)
    var fromDay by rememberSaveable { mutableLongStateOf(initialStart.date.toEpochDay()) }
    var fromHour by rememberSaveable { mutableIntStateOf(initialStart.hour) }
    var fromMinute by rememberSaveable { mutableIntStateOf(initialStart.minute) }
    var toDay by rememberSaveable { mutableLongStateOf(initialEnd.date.toEpochDay()) }
    var toHour by rememberSaveable { mutableIntStateOf(initialEnd.hour) }
    var toMinute by rememberSaveable { mutableIntStateOf(initialEnd.minute) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    when (editing) {
        EDIT_FROM_DATE -> RangeDatePicker(LocalDate.ofEpochDay(fromDay), zone, now, { fromDay = it.toEpochDay() }) { editing = null }
        EDIT_TO_DATE -> RangeDatePicker(LocalDate.ofEpochDay(toDay), zone, now, { toDay = it.toEpochDay() }) { editing = null }
        EDIT_FROM_TIME -> RangeTimePicker(fromHour, fromMinute, { h, m -> fromHour = h; fromMinute = m }) { editing = null }
        EDIT_TO_TIME -> RangeTimePicker(toHour, toMinute, { h, m -> toHour = h; toMinute = m }) { editing = null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("cloudflare.zone.customRange"),
        title = { Text("Custom traffic window") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("CUSTOM TRAFFIC WINDOW", style = MaterialTheme.typography.labelSmall, color = CloudflareOpsColors.Orange)
                Text(
                    "Choose any range. Cloudflare will shorten it only when your zone’s plan or dataset retention requires it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RangeEndpointRow(
                    label = "From",
                    date = LocalDate.ofEpochDay(fromDay),
                    hour = fromHour,
                    minute = fromMinute,
                    tagPrefix = "cloudflare.zone.customRange.from",
                    onEditDate = { error = null; editing = EDIT_FROM_DATE },
                    onEditTime = { error = null; editing = EDIT_FROM_TIME },
                )
                RangeEndpointRow(
                    label = "To",
                    date = LocalDate.ofEpochDay(toDay),
                    hour = toHour,
                    minute = toMinute,
                    tagPrefix = "cloudflare.zone.customRange.to",
                    onEditDate = { error = null; editing = EDIT_TO_DATE },
                    onEditTime = { error = null; editing = EDIT_TO_TIME },
                )
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("cloudflare.zone.customRange.error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    CloudflareCustomRange.resolve(
                        from = CloudflareRangeEndpoint(LocalDate.ofEpochDay(fromDay), fromHour, fromMinute),
                        to = CloudflareRangeEndpoint(LocalDate.ofEpochDay(toDay), toHour, toMinute),
                        zone = zone,
                        now = now(),
                    ).fold(
                        onSuccess = { (start, end) -> error = onApply(start, end) },
                        onFailure = { error = it.message },
                    )
                },
                modifier = Modifier.testTag("cloudflare.zone.customRange.apply"),
            ) { Text("Apply range") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RangeEndpointRow(
    label: String,
    date: LocalDate,
    hour: Int,
    minute: Int,
    tagPrefix: String,
    onEditDate: () -> Unit,
    onEditTime: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onEditDate,
                modifier = Modifier
                    .weight(1.4f)
                    .heightIn(min = 48.dp)
                    .testTag("$tagPrefix.date"),
            ) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = null, tint = CloudflareOpsColors.Orange)
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(CloudflareCustomRange.formatDate(date), maxLines = 1)
            }
            OutlinedButton(
                onClick = onEditTime,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("$tagPrefix.time"),
            ) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = CloudflareOpsColors.Orange)
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Text(CloudflareCustomRange.formatTime(hour, minute), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDatePicker(
    initial: LocalDate,
    zone: ZoneId,
    now: () -> Instant,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // DatePicker speaks UTC-midnight milliseconds; today is the last selectable day.
    val today = now().atZone(zone).toLocalDate()
    val todayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayEnd
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onSelect(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    onDismiss()
                },
                modifier = Modifier.testTag("cloudflare.zone.customRange.dateConfirm"),
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeTimePicker(
    hour: Int,
    minute: Int,
    onSelect: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("cloudflare.zone.customRange.timePicker"),
        text = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelect(state.hour, state.minute)
                    onDismiss()
                },
                modifier = Modifier.testTag("cloudflare.zone.customRange.timeConfirm"),
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val EDIT_FROM_DATE = "fromDate"
private const val EDIT_FROM_TIME = "fromTime"
private const val EDIT_TO_DATE = "toDate"
private const val EDIT_TO_TIME = "toTime"
