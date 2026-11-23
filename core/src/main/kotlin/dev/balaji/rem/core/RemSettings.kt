package dev.balaji.rem.core

/**
 * Global settings. Stored values may be out of range (old versions, hand edits), so
 * repositories pass everything they read or write through [coerced].
 *
 * @property headphonesOnly every alarm plays only on connected headphones, until they
 *   disconnect, the route turns out wrong or [speakerFallbackSeconds] pass. Then it
 *   switches to the speaker.
 */
data class RemSettings(
    val cycleMinutes: Int = 90,
    val fallAsleepMinutes: Int = 15,
    val earlyWindowMinutes: Int = 30,
    val speakerFallbackSeconds: Int = 120,
    val headphonesOnly: Boolean = false,
) {
    fun coerced(): RemSettings = RemSettings(
        cycleMinutes = cycleMinutes.coerceIn(CYCLE_MINUTES),
        fallAsleepMinutes = fallAsleepMinutes.coerceIn(FALL_ASLEEP_MINUTES),
        earlyWindowMinutes = earlyWindowMinutes.coerceIn(EARLY_WINDOW_MINUTES),
        speakerFallbackSeconds = speakerFallbackSeconds.coerceIn(SPEAKER_FALLBACK_SECONDS),
        headphonesOnly = headphonesOnly,
    )

    companion object {
        val DEFAULT = RemSettings()

        val CYCLE_MINUTES = 60..120
        val FALL_ASLEEP_MINUTES = 0..60
        val EARLY_WINDOW_MINUTES = 0..90
        val SPEAKER_FALLBACK_SECONDS = 30..600
    }
}
