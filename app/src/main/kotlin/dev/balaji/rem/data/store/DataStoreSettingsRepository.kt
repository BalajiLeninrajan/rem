package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<RemSettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    override suspend fun current(): RemSettings = store.data.first().toSettings()

    override suspend fun update(transform: (RemSettings) -> RemSettings): RemSettings =
        store.edit { it.write(transform(it.toSettings()).coerced()) }.toSettings()

    override val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }.distinctUntilChanged()

    override suspend fun setOnboardingDone() {
        store.edit { it[ONBOARDING_DONE] = true }
    }

    private companion object {
        val CYCLE_MINUTES = intPreferencesKey("cycle_minutes")
        val FALL_ASLEEP_MINUTES = intPreferencesKey("fall_asleep_minutes")
        val EARLY_WINDOW_MINUTES = intPreferencesKey("early_window_minutes")
        val SPEAKER_FALLBACK_SECONDS = intPreferencesKey("speaker_fallback_seconds")
        val MIN_RING_VOLUME_PERCENT = intPreferencesKey("min_ring_volume_percent")
        val VOLUME_RAMP_SECONDS = intPreferencesKey("volume_ramp_seconds")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

        /** A missing key reads as its default. The result is coerced, in case the file holds values from another version. */
        fun Preferences.toSettings(): RemSettings {
            val default = RemSettings.DEFAULT
            return RemSettings(
                cycleMinutes = this[CYCLE_MINUTES] ?: default.cycleMinutes,
                fallAsleepMinutes = this[FALL_ASLEEP_MINUTES] ?: default.fallAsleepMinutes,
                earlyWindowMinutes = this[EARLY_WINDOW_MINUTES] ?: default.earlyWindowMinutes,
                speakerFallbackSeconds = this[SPEAKER_FALLBACK_SECONDS] ?: default.speakerFallbackSeconds,
                minRingVolumePercent = this[MIN_RING_VOLUME_PERCENT] ?: default.minRingVolumePercent,
                volumeRampSeconds = this[VOLUME_RAMP_SECONDS] ?: default.volumeRampSeconds,
            ).coerced()
        }

        fun MutablePreferences.write(settings: RemSettings) {
            this[CYCLE_MINUTES] = settings.cycleMinutes
            this[FALL_ASLEEP_MINUTES] = settings.fallAsleepMinutes
            this[EARLY_WINDOW_MINUTES] = settings.earlyWindowMinutes
            this[SPEAKER_FALLBACK_SECONDS] = settings.speakerFallbackSeconds
            this[MIN_RING_VOLUME_PERCENT] = settings.minRingVolumePercent
            this[VOLUME_RAMP_SECONDS] = settings.volumeRampSeconds
        }
    }
}
