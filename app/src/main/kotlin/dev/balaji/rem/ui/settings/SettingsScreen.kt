package dev.balaji.rem.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.theme.RemTheme
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = containerViewModel { SettingsViewModel(it.settingsRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsContent(
        state = state,
        onBack = onBack,
        onCycleMinutes = viewModel::setCycleMinutes,
        onFallAsleepMinutes = viewModel::setFallAsleepMinutes,
        onEarlyWindowMinutes = viewModel::setEarlyWindowMinutes,
        onSpeakerFallbackSeconds = viewModel::setSpeakerFallbackSeconds,
        onReset = viewModel::resetToDefaults,
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onCycleMinutes: (Int) -> Unit,
    onFallAsleepMinutes: (Int) -> Unit,
    onEarlyWindowMinutes: (Int) -> Unit,
    onSpeakerFallbackSeconds: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    ScreenScaffold(
        title = stringResource(R.string.screen_settings),
        onBack = onBack,
        modifier = modifier,
        actions = {
            TextButton(onClick = onReset, enabled = !state.loading && !state.isDefault) {
                Text(stringResource(R.string.ui_settings_reset))
            }
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(stringResource(R.string.ui_settings_section_sleep))
            SettingSlider(
                title = stringResource(R.string.ui_settings_cycle),
                description = stringResource(R.string.ui_settings_cycle_description),
                note = stringResource(R.string.ui_settings_cycle_note),
                value = settings.cycleMinutes,
                valueLabel = { minutesLabel(it) },
                range = RemSettings.CYCLE_MINUTES,
                step = 5,
                onCommit = onCycleMinutes,
            )
            SettingSlider(
                title = stringResource(R.string.ui_settings_fall_asleep),
                description = stringResource(R.string.ui_settings_fall_asleep_description),
                value = settings.fallAsleepMinutes,
                valueLabel = { minutesLabel(it) },
                range = RemSettings.FALL_ASLEEP_MINUTES,
                step = 5,
                onCommit = onFallAsleepMinutes,
            )
            SettingSlider(
                title = stringResource(R.string.ui_settings_early_window),
                description = stringResource(R.string.ui_settings_early_window_description),
                value = settings.earlyWindowMinutes,
                valueLabel = { minutesLabel(it) },
                range = RemSettings.EARLY_WINDOW_MINUTES,
                step = 5,
                onCommit = onEarlyWindowMinutes,
            )
            SectionTitle(stringResource(R.string.ui_settings_section_ringing))
            SettingSlider(
                title = stringResource(R.string.ui_settings_speaker_fallback),
                description = stringResource(R.string.ui_settings_speaker_fallback_description),
                value = settings.speakerFallbackSeconds,
                valueLabel = { secondsLabel(it) },
                range = RemSettings.SPEAKER_FALLBACK_SECONDS,
                step = 30,
                onCommit = onSpeakerFallbackSeconds,
            )
        }
    }
}

@Composable
private fun minutesLabel(minutes: Int): String = stringResource(R.string.ui_unit_minutes, minutes)

@Composable
private fun secondsLabel(seconds: Int): String = stringResource(R.string.ui_unit_seconds, seconds)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/**
 * One setting: title, current value with its unit, a one line explanation and a slider.
 * The slider moves freely and [onCommit] gets the value once, when the finger lifts, because
 * every stored change makes the scheduler recompute all alarms.
 *
 * @param step distance between slider stops. [range] must be a whole number of steps wide.
 */
@Composable
private fun SettingSlider(
    title: String,
    description: String,
    value: Int,
    valueLabel: @Composable (Int) -> String,
    range: IntRange,
    step: Int,
    onCommit: (Int) -> Unit,
    note: String? = null,
) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = valueLabel(position.roundToInt())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(text = shown, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onCommit(position.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = shown
                },
            )
        }
    }
}

@Preview
@Composable
private fun SettingsPreview() {
    RemTheme {
        SettingsContent(
            state = SettingsUiState(loading = false),
            onBack = {},
            onCycleMinutes = {},
            onFallAsleepMinutes = {},
            onEarlyWindowMinutes = {},
            onSpeakerFallbackSeconds = {},
            onReset = {},
        )
    }
}
