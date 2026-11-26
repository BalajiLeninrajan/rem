package dev.balaji.rem.ring.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import dev.balaji.rem.ring.RingSpec
import dev.balaji.rem.ui.system.DoNotDisturb
import dev.balaji.rem.ui.system.readDoNotDisturb
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything that makes one ring heard and felt: tone, routing, volume floor, vibration
 * and audio focus. The service creates it after `startForeground` and stops it when the
 * ring ends. Each part fails on its own without taking the others down.
 *
 * [setQuiet] pauses the tone and the vibration while the user scans or types the code.
 * The volume floor, the routing and the audio focus stay as they are.
 *
 * Call [start] and [stop] on the main thread.
 */
internal class RingPlayback(private val context: Context, val spec: RingSpec) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val player = AlarmAudioPlayer(context, scope)
    private val volumeFloor = AlarmVolumeFloor(audioManager, spec.minRingVolumePercent)
    private val vibration = AlarmVibration(context)
    private val router = HeadphoneRouter(
        audioManager = audioManager,
        player = player,
        headphonesOnly = spec.headphonesOnly,
        fallbackAfter = Duration.ofSeconds(spec.speakerFallbackSeconds.toLong()),
        scope = scope,
        onRouteSettled = volumeFloor::enforce,
    )

    // Losing focus, for example to an incoming call, never stops or lowers an alarm.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
        .setOnAudioFocusChangeListener { change -> Log.i(TAG, "Audio focus change $change ignored") }
        .build()

    private var started = false
    private var vibrate = false
    private var quiet = false
    private var vibrating: Job? = null

    fun start() {
        started = true
        // First, so the volume floor below saves the user's own volume and not one an editor preview raised.
        AlarmTonePreviewPlayback.stopActive()
        // When Do Not Disturb mutes the tone, vibration is tried whatever the alarm says. The system may
        // block an alarm vibration together with the tone, so the reliability warning is the real defence.
        vibrate = spec.vibrate
        guarded(TAG, "do not disturb") { vibrate = vibrate || readDoNotDisturb(context) == DoNotDisturb.ALARMS_MUTED }
        startVibrating()
        // Other players pause when focus is granted. The alarm plays whether it is or not.
        guarded(TAG, "audio focus") { audioManager.requestAudioFocus(focusRequest) }
        scope.launch {
            guarded(TAG, "tone") { player.prepare(spec.ringtoneUri) }
            guarded(TAG, "routing") { router.start() }
            // Starts even when preparing failed: the player then rebuilds itself on the bundled tone.
            guarded(TAG, "playback") { player.start(spec.volumeRampSeconds) }
            while (true) {
                volumeFloor.enforce()
                delay(VOLUME_POLL_MILLIS)
            }
        }
    }

    /** Pauses the tone and the vibration, or brings them back at once. Can be called before [start]. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        guarded(TAG, "tone") { player.setPaused(quiet) }
        if (quiet) {
            vibrating?.cancel()
            vibrating = null
            guarded(TAG, "vibration") { vibration.stop() }
        } else if (started) {
            startVibrating()
        }
    }

    private fun startVibrating() {
        if (!vibrate || quiet || vibrating?.isActive == true) return
        // One request per cycle. The system cancels a vibration when the screen turns off, and the next cycle brings it back.
        vibrating = scope.launch {
            while (true) {
                guarded(TAG, "vibration") { vibration.pulse() }
                delay(AlarmVibration.PATTERN_LENGTH_MILLIS)
            }
        }
    }

    fun stop() {
        scope.cancel()
        guarded(TAG, "routing") { router.stop() }
        player.release()
        vibration.stop()
        volumeFloor.restore()
        guarded(TAG, "audio focus") { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    private companion object {
        const val TAG = "RingPlayback"
        const val VOLUME_POLL_MILLIS = 750L
    }
}

/** Runs one part of starting or stopping playback. A failure is logged so the other parts still run. */
internal inline fun guarded(tag: String, what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(tag, "Part failed: $what", e)
    }
}
