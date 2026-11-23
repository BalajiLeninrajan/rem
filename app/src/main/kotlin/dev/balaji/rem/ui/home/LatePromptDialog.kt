package dev.balaji.rem.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.balaji.rem.R
import dev.balaji.rem.core.LateOption
import dev.balaji.rem.ui.format.clockText
import dev.balaji.rem.ui.theme.RemTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Asks, after "going to sleep", which alarms should ring at the end of the first cycle after
 * their time. One switch per alarm, all off at first. Dismissing keeps every alarm time.
 */
@Composable
fun LatePromptDialog(
    prompt: LatePrompt,
    zone: ZoneId,
    onChoice: (alarmId: Long, accept: Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ui_late_prompt_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                prompt.rows.forEach { row -> LatePromptRowItem(row, zone, onChoice) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.ui_action_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_late_prompt_keep)) }
        },
    )
}

@Composable
private fun LatePromptRowItem(row: LatePromptRow, zone: ZoneId, onChoice: (alarmId: Long, accept: Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = row.accepted, role = Role.Switch, onValueChange = { onChoice(row.alarmId, it) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (row.label.isNotBlank()) {
                Text(text = row.label, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = pluralStringResource(
                    R.plurals.ui_late_prompt_row,
                    row.option.cycles,
                    clockText(row.deadline, zone),
                    clockText(row.option.ringAt, zone),
                    row.option.cycles,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        // The row is the toggle, so the switch only shows its state.
        Switch(checked = row.accepted, onCheckedChange = null, modifier = Modifier.padding(end = 4.dp))
    }
}

@Preview
@Composable
private fun LatePromptDialogPreview() {
    val deadline = Instant.parse("2026-01-15T06:00:00Z")
    RemTheme {
        LatePromptDialog(
            prompt = LatePrompt(
                listOf(
                    LatePromptRow(1, "Work", deadline, LateOption(Instant.parse("2026-01-15T06:15:00Z"), 4, 15), accepted = true),
                    LatePromptRow(2, "", deadline.plusSeconds(1800), LateOption(Instant.parse("2026-01-15T06:45:00Z"), 4, 15), accepted = false),
                ),
            ),
            zone = ZoneOffset.UTC,
            onChoice = { _, _ -> },
            onConfirm = {},
            onDismiss = {},
        )
    }
}
