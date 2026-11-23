package dev.balaji.rem.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.di.containerViewModel
import dev.balaji.rem.ui.components.ScreenScaffold
import dev.balaji.rem.ui.components.SettingSlider
import dev.balaji.rem.ui.theme.RemTheme

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
        onHeadphonesOnly = viewModel::setHeadphonesOnly,
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
    onHeadphonesOnly: (Boolean) -> Unit,
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
            SettingSwitch(
                title = stringResource(R.string.ui_settings_headphones_only),
                description = stringResource(R.string.ui_settings_headphones_only_description),
                checked = settings.headphonesOnly,
                onCheckedChange = onHeadphonesOnly,
                enabled = !state.loading,
            )
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

/** A setting that is on or off. The whole card toggles, so the touch target is not only the thumb. */
@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
            onHeadphonesOnly = {},
            onReset = {},
        )
    }
}
