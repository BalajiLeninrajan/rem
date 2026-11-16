package dev.balaji.rem.ring.audio

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Repeats a two-pulse pattern until [stop]. Alarm usage lets it run from the background and through Do Not Disturb rules for alarms. */
internal class AlarmVibration(context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    fun start() {
        val target = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = VibrationEffect.createWaveform(PATTERN_MILLIS, 0)
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

    private companion object {
        const val TAG = "AlarmVibration"

        /** Wait, buzz, pause, buzz, long pause. Index 0 repeats the whole pattern. */
        val PATTERN_MILLIS = longArrayOf(0, 500, 250, 500, 1000)
    }
}
