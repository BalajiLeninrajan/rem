@file:OptIn(ExperimentalLayoutApi::class)

package dev.balaji.rem.ui.alarm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.components.ClockText
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.components.WhileResumed
import dev.balaji.rem.ui.format.currentLocale
import dev.balaji.rem.ui.format.ringTimeText
import dev.balaji.rem.ui.format.wakeExplanationText
import dev.balaji.rem.ui.format.weekInLocaleOrder
import dev.balaji.rem.ui.theme.ClockType
import dev.balaji.rem.ui.theme.RemTheme
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.TextStyle
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

private val RefreshPeriod = 15.seconds

/** Creates an alarm when [alarmId] is `Alarm.NEW_ID`, edits the stored one otherwise. [onDone] closes the screen. */
@Composable
fun AlarmEditorScreen(
    alarmId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel {
        AlarmEditorViewModel(alarmId, it.alarmRepository, it.settingsRepository, it.sleepSessionRepository, it.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    WhileResumed(RefreshPeriod) { viewModel.refresh() }
    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    AlarmEditorContent(
        state = state,
        onBack = onDone,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onTimeChange = viewModel::setTime,
        onLabelChange = viewModel::setLabel,
        onToggleDay = viewModel::toggleDay,
        onHeadphonesOnlyChange = viewModel::setHeadphonesOnly,
        onVibrateChange = viewModel::setVibrate,
        onRingtoneChange = viewModel::setRingtone,
        modifier = modifier,
    )
}

@Composable
fun AlarmEditorContent(
    state: AlarmEditorUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onLabelChange: (String) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onHeadphonesOnlyChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onRingtoneChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pickerMissingMessage = stringResource(R.string.ui_editor_ringtone_picker_missing)

    ScreenScaffold(
        title = stringResource(if (state.isNew) R.string.ui_editor_title_new else R.string.ui_editor_title_edit),
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        actions = {
            if (!state.isNew) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.ui_action_delete))
                }
            }
            TextButton(onClick = onSave, enabled = state.canSave) {
                Text(stringResource(R.string.ui_action_save))
            }
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeadlineDisplay(state, onClick = { showTimePicker = true }, modifier = Modifier.padding(horizontal = 16.dp))
            RepeatDayChips(state.repeatDays, onToggleDay, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedTextField(
                value = state.label,
                onValueChange = onLabelChange,
                label = { Text(stringResource(R.string.ui_editor_label)) },
                singleLine = true,
                isError = state.labelTooLong,
                supportingText = if (state.labelTooLong) {
                    { Text(stringResource(R.string.ui_editor_label_too_long, AlarmEditorViewModel.MAX_LABEL_LENGTH)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            SwitchRow(
                icon = Icons.Filled.Headphones,
                title = stringResource(R.string.ui_editor_headphones_only),
                description = stringResource(
                    R.string.ui_editor_headphones_only_description,
                    stringResource(R.string.ui_unit_seconds, state.speakerFallbackSeconds),
                ),
                checked = state.headphonesOnly,
                onCheckedChange = onHeadphonesOnlyChange,
            )
            SwitchRow(
                icon = Icons.Filled.Vibration,
                title = stringResource(R.string.ui_editor_vibrate),
                description = null,
                checked = state.vibrate,
                onCheckedChange = onVibrateChange,
            )
            RingtoneRow(
                ringtoneUri = state.ringtoneUri,
                onRingtoneChange = onRingtoneChange,
                onPickerMissing = { scope.launch { snackbarHostState.showSnackbar(pickerMissingMessage) } },
            )
        }
    }

    if (showTimePicker) {
        DeadlinePickerDialog(
            initial = state.time,
            onConfirm = { time ->
                showTimePicker = false
                onTimeChange(time)
            },
            onDismiss = { showTimePicker = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ui_editor_delete_title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) { Text(stringResource(R.string.ui_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.ui_action_cancel)) }
            },
        )
    }
}

/** The deadline, large, with the "would ring at" line under it. Tapping opens the time picker. */
@Composable
private fun DeadlineDisplay(state: AlarmEditorUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.ui_editor_must_wake_by), style = MaterialTheme.typography.labelLarge)
            ClockText(time = state.time, style = ClockType.hero)
            val preview = state.preview
            if (preview != null) {
                Text(
                    text = if (preview.sessionActive) {
                        stringResource(
                            R.string.ui_editor_preview_session,
                            ringTimeText(preview.ringAt, state.now, state.zone),
                            wakeExplanationText(preview.explanation, preview.deadline, state.zone),
                        )
                    } else {
                        stringResource(
                            R.string.ui_editor_preview_no_session,
                            ringTimeText(preview.deadline, state.now, state.zone),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RepeatDayChips(selected: Set<DayOfWeek>, onToggleDay: (DayOfWeek) -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.ui_editor_repeat),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weekInLocaleOrder(locale).forEach { day ->
                val fullName = day.getDisplayName(TextStyle.FULL, locale)
                FilterChip(
                    selected = day in selected,
                    onClick = { onToggleDay(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    modifier = Modifier.semantics { contentDescription = fullName },
                )
            }
        }
        if (selected.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_editor_repeat_once_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A whole-row switch, so the touch target is the full width and not only the thumb. */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = if (description != null) {
            { Text(description) }
        } else {
            null
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Preview
@Composable
private fun AlarmEditorPreview() {
    val now = Instant.parse("2026-01-14T23:00:00Z")
    RemTheme {
        AlarmEditorContent(
            state = AlarmEditorUiState(
                loading = false,
                isNew = false,
                label = "Work",
                repeatDays = RepeatDays.WEEKDAYS,
                headphonesOnly = true,
                preview = RingPreview(
                    ringAt = Instant.parse("2026-01-15T06:45:00Z"),
                    deadline = Instant.parse("2026-01-15T07:00:00Z"),
                    explanation = WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15),
                    sessionActive = true,
                ),
                now = now,
            ),
            onBack = {},
            onSave = {},
            onDelete = {},
            onTimeChange = {},
            onLabelChange = {},
            onToggleDay = {},
            onHeadphonesOnlyChange = {},
            onVibrateChange = {},
            onRingtoneChange = {},
        )
    }
}
