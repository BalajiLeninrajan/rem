package dev.balaji.rem.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.balaji.rem.R
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.PlannedRing
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.ui.components.ClockText
import dev.balaji.rem.ui.format.clockText
import dev.balaji.rem.ui.format.repeatSummary
import dev.balaji.rem.ui.format.ringLineText
import dev.balaji.rem.ui.theme.ClockType
import dev.balaji.rem.ui.theme.RemTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** One alarm: the deadline large, label and repeat days, the computed ring time, the switch and a delete menu. */
@Composable
fun AlarmCard(
    row: AlarmRow,
    now: Instant,
    zone: ZoneId,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alarm = row.alarm
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        ),
    ) {
        Row(modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 4.dp, bottom = 16.dp)) {
            Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                ClockText(
                    time = alarm.time,
                    style = ClockType.card,
                    color = if (alarm.enabled) colors.onSurface else colors.onSurfaceVariant,
                )
                Text(
                    text = alarmSummary(alarm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = ringStatus(row, now, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (alarm.enabled) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AlarmMenu(onDelete = onDelete)
                val switchLabel = stringResource(R.string.ui_home_alarm_switch, clockText(alarm.time))
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.padding(end = 12.dp).semantics { contentDescription = switchLabel },
                )
            }
        }
    }
}

@Composable
private fun AlarmMenu(onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ui_action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ui_action_delete)) },
                leadingIcon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun alarmSummary(alarm: Alarm): String {
    val repeat = repeatSummary(alarm.repeatDays)
    return if (alarm.label.isBlank()) repeat else stringResource(R.string.ui_home_label_and_repeat, alarm.label, repeat)
}

@Composable
private fun ringStatus(row: AlarmRow, now: Instant, zone: ZoneId): String {
    val ring = row.nextRing
    return when {
        row.ringing -> stringResource(R.string.ui_home_ringing_now)
        !row.alarm.enabled -> stringResource(R.string.ui_home_alarm_off)
        ring != null -> ringLineText(ring.ringAt, ring.deadline, ring.explanation, now, zone)
        else -> stringResource(R.string.ui_home_scheduling)
    }
}

@Preview
@Composable
private fun AlarmCardPreview() {
    val now = Instant.parse("2026-01-14T23:00:00Z")
    RemTheme {
        AlarmCard(
            row = AlarmRow(
                alarm = Alarm(id = 1, time = LocalTime.of(7, 0), label = "Work", repeatDays = RepeatDays.WEEKDAYS),
                nextRing = PlannedRing(
                    alarmId = 1,
                    ringAt = Instant.parse("2026-01-15T06:45:00Z"),
                    deadline = Instant.parse("2026-01-15T07:00:00Z"),
                    explanation = WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15),
                ),
            ),
            now = now,
            zone = ZoneOffset.UTC,
            onClick = {},
            onEnabledChange = {},
            onDelete = {},
        )
    }
}
