package dev.balaji.rem.ring.audio

import android.media.AudioManager
import android.util.Log

/**
 * Keeps the alarm stream at or above the configured floor while an alarm rings, and puts
 * the user's own volume back afterwards.
 *
 * There is no public callback for volume changes, so the service calls [enforce] on a
 * short poll and after every route change. Stream volume is stored per output device.
 */
internal class AlarmVolumeFloor(private val audioManager: AudioManager, private val percent: Int) {
    private var saved: Int? = null
    private var raised = false

    fun enforce() {
        if (audioManager.isVolumeFixed) return
        try {
            val current = audioManager.getStreamVolume(STREAM)
            if (saved == null) saved = current
            val floor = volumeFloorIndex(
                min = audioManager.getStreamMinVolume(STREAM),
                max = audioManager.getStreamMaxVolume(STREAM),
                percent = percent,
            )
            val target = volumeToSet(current, floor) ?: return
            audioManager.setStreamVolume(STREAM, target, 0)
            raised = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not raise the alarm volume", e)
        }
    }

    /** Restores the volume from before the ring, if the floor ever changed it. */
    fun restore() {
        val original = saved
        saved = null
        if (original == null || !raised) return
        raised = false
        try {
            audioManager.setStreamVolume(STREAM, original, 0)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not restore the alarm volume", e)
        }
    }

    private companion object {
        const val TAG = "AlarmVolumeFloor"
        const val STREAM = AudioManager.STREAM_ALARM
    }
}
