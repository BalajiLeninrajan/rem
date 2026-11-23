package dev.balaji.rem.ring.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dev.balaji.rem.ui.alarm.PreviewPlayback
import dev.balaji.rem.ui.alarm.PreviewTone
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays an alarm's tone in the editor the way its ring sounds: through [AlarmAudioPlayer]
 * on the alarm stream, with the bundled tone when the alarm has no ringtone or it does not
 * open, and with the alarm stream raised to the alarm's floor by [AlarmVolumeFloor]. There
 * is no ramp, so the preview starts at the level a ring reaches. [stop] puts the user's
 * alarm volume back.
 *
 * A ring stops the preview before it touches the volume, see [stopActive]. Otherwise the
 * ring would take the volume the preview raised for the user's own, and put that back when
 * it ends.
 *
 * Starts in the constructor. Call every method on the main thread.
 */
internal class AlarmTonePreviewPlayback(context: Context, tone: PreviewTone) : PreviewPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val player = AlarmAudioPlayer(context, scope)
    private var volumeFloor = AlarmVolumeFloor(audioManager, tone.minRingVolumePercent)

    // Pauses music while the preview plays. A preview that loses focus keeps playing until it is stopped.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
        .build()

    private var stopped = false

    init {
        active?.stop()
        active = this
        guarded("volume floor") { volumeFloor.enforce() }
        guarded("audio focus") { audioManager.requestAudioFocus(focusRequest) }
        scope.launch {
            guarded("tone") { player.prepare(tone.ringtoneUri) }
            // Starts even when preparing failed: the player then rebuilds itself on the bundled tone.
            guarded("playback") { player.start(rampSeconds = 0) }
        }
    }

    override fun setMinRingVolumePercent(percent: Int) {
        // A ring may have stopped this preview before the editor noticed.
        if (stopped) return
        guarded("volume floor") {
            volumeFloor.restore()
            volumeFloor = AlarmVolumeFloor(audioManager, percent)
            volumeFloor.enforce()
        }
    }

    /** Safe to call more than once. */
    override fun stop() {
        if (stopped) return
        stopped = true
        if (active === this) active = null
        scope.cancel()
        player.release()
        guarded("volume floor") { volumeFloor.restore() }
        guarded("audio focus") { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Preview part failed: $what", e)
        }
    }

    internal companion object {
        private const val TAG = "AlarmTonePreview"

        /** The preview that plays now, if any. Only touched on the main thread. */
        private var active: AlarmTonePreviewPlayback? = null

        /** Stops the preview that plays now and puts the user's alarm volume back. Call on the main thread. */
        fun stopActive() {
            active?.stop()
        }
    }
}
