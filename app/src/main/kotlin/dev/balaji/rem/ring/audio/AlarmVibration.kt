package dev.balaji.rem.ring.audio

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays a two-pulse pattern, once per [pulse]. The caller repeats it, because the system
 * cancels a vibration that repeats by itself when the screen turns off and nothing would
 * start it again. Alarm usage lets it run from the background.
 */
internal class AlarmVibration(context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    fun pulse() {
        val target = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN_MILLIS, NO_REPEAT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                target.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vibration did not start", e)
        }
    }

    fun stop() {
        vibrator?.cancel()
    }

    companion object {
        private const val TAG = "AlarmVibration"
        private const val NO_REPEAT = -1

        /** Wait, buzz, pause, buzz, long pause. */
        private val PATTERN_MILLIS = longArrayOf(0, 500, 250, 500, 1000)

        /** How long one [pulse] lasts, which is how often to call it. */
        val PATTERN_LENGTH_MILLIS: Long = PATTERN_MILLIS.sum()
    }
}
