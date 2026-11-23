package dev.balaji.rem.core

/**
 * Global settings. Stored values may be out of range (old versions, hand edits), so
 * repositories pass everything they read or write through [coerced].
 */
data class RemSettings(
    val cycleMinutes: Int = 90,
    val fallAsleepMinutes: Int = 15,
    val earlyWindowMinutes: Int = 30,
    val speakerFallbackSeconds: Int = 120,
    val minRingVolumePercent: Int = 70,
    /** 0 disables the ramp. */
    val volumeRampSeconds: Int = 30,
) {
    fun coerced(): RemSettings = RemSettings(
        cycleMinutes = cycleMinutes.coerceIn(CYCLE_MINUTES),
        fallAsleepMinutes = fallAsleepMinutes.coerceIn(FALL_ASLEEP_MINUTES),
        earlyWindowMinutes = earlyWindowMinutes.coerceIn(EARLY_WINDOW_MINUTES),
        speakerFallbackSeconds = speakerFallbackSeconds.coerceIn(SPEAKER_FALLBACK_SECONDS),
        minRingVolumePercent = minRingVolumePercent.coerceIn(MIN_RING_VOLUME_PERCENT),
        volumeRampSeconds = volumeRampSeconds.coerceIn(VOLUME_RAMP_SECONDS),
    )

    companion object {
        val DEFAULT = RemSettings()

        val CYCLE_MINUTES = 60..120
        val FALL_ASLEEP_MINUTES = 0..60
        val EARLY_WINDOW_MINUTES = 0..90
        val SPEAKER_FALLBACK_SECONDS = 30..600
        val MIN_RING_VOLUME_PERCENT = 0..100
        val VOLUME_RAMP_SECONDS = 0..300
    }
}
