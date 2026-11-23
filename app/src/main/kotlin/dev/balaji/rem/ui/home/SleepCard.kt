package dev.balaji.rem.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.balaji.rem.R
import dev.balaji.rem.core.PlannedRing
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.ui.components.ClockText
import dev.balaji.rem.ui.format.clockText
import dev.balaji.rem.ui.format.ringTimeText
import dev.balaji.rem.ui.format.wakeExplanationText
import dev.balaji.rem.ui.theme.ClockType
import dev.balaji.rem.ui.theme.RemTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The going-to-sleep control, or the running session once it has been pressed. Draws nothing when [state] is hidden. */
@Composable
fun SleepCard(
    state: SleepState,
    now: Instant,
    zone: ZoneId,
    onGoToSleep: () -> Unit,
    onEndSleep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        SleepState.Hidden -> Unit
        is SleepState.Ready -> ReadyCard(state.preview, now, zone, onGoToSleep, modifier)
        is SleepState.Active -> ActiveCard(state, zone, onEndSleep, modifier)
    }
}

@Composable
private fun ReadyCard(preview: PlannedRing?, now: Instant, zone: ZoneId, onGoToSleep: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onGoToSleep,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(imageVector = Icons.Filled.Bedtime, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(text = stringResource(R.string.ui_home_going_to_sleep), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = if (preview == null) {
                    stringResource(R.string.ui_home_sleep_no_alarm)
                } else {
                    val line = stringResource(
                        R.string.ui_home_sleep_preview,
                        ringTimeText(preview.ringAt, now, zone),
                        wakeExplanationText(preview.explanation, preview.deadline, zone),
                    )
                    val late = preview.late
                    if (late == null) {
                        line
                    } else {
                        line + " " + pluralStringResource(
                            R.plurals.ui_home_sleep_preview_late,
                            late.cycles,
                            ringTimeText(late.ringAt, now, zone),
                            late.cycles,
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ActiveCard(state: SleepState.Active, zone: ZoneId, onEndSleep: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.ui_home_asleep_since, clockText(state.startedAt, zone)),
                style = MaterialTheme.typography.labelLarge,
            )
            val tonight = state.tonight
            if (tonight == null) {
                Text(
                    text = stringResource(R.string.ui_home_session_no_alarm),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.ui_home_rings_at),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                ClockText(time = tonight.ringAt.atZone(zone).toLocalTime(), style = ClockType.hero)
                Text(
                    text = wakeExplanationText(tonight.explanation, tonight.deadline, zone),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            FilledTonalButton(onClick = onEndSleep, modifier = Modifier.padding(top = 16.dp)) {
                Icon(imageVector = Icons.Filled.WbSunny, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.ui_home_end_sleep))
            }
        }
    }
}

private val previewNow: Instant = Instant.parse("2026-01-14T23:00:00Z")

private val previewRing = PlannedRing(
    alarmId = 1,
    ringAt = Instant.parse("2026-01-15T06:45:00Z"),
    deadline = Instant.parse("2026-01-15T07:00:00Z"),
    explanation = WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15),
)

@Preview
@Composable
private fun SleepCardReadyPreview() {
    RemTheme {
        SleepCard(SleepState.Ready(previewRing), previewNow, ZoneOffset.UTC, onGoToSleep = {}, onEndSleep = {})
    }
}

@Preview
@Composable
private fun SleepCardActivePreview() {
    RemTheme {
        SleepCard(SleepState.Active(previewNow, previewRing), previewNow, ZoneOffset.UTC, onGoToSleep = {}, onEndSleep = {})
    }
}
