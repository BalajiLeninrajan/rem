package dev.balaji.rem.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(val loading: Boolean = true, val settings: RemSettings = RemSettings.DEFAULT) {
    val isDefault: Boolean get() = settings == RemSettings.DEFAULT
}

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(loading = false, settings = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    fun setCycleMinutes(value: Int) = change { it.copy(cycleMinutes = value) }

    fun setFallAsleepMinutes(value: Int) = change { it.copy(fallAsleepMinutes = value) }

    fun setEarlyWindowMinutes(value: Int) = change { it.copy(earlyWindowMinutes = value) }

    fun setSpeakerFallbackSeconds(value: Int) = change { it.copy(speakerFallbackSeconds = value) }

    fun setMinRingVolumePercent(value: Int) = change { it.copy(minRingVolumePercent = value) }

    fun setVolumeRampSeconds(value: Int) = change { it.copy(volumeRampSeconds = value) }

    fun resetToDefaults() = change { RemSettings.DEFAULT }

    /** Values are clamped to their ranges here as well as in the repository, so an out-of-range value never reaches storage. */
    private fun change(transform: (RemSettings) -> RemSettings) {
        viewModelScope.launch { repository.update { transform(it).coerced() } }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
