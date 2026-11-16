package dev.balaji.rem.ring.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.balaji.rem.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alarm usage keeps the alarm stream volume, the alarm Do Not Disturb rules and the Android 17 background audio exemption. */
internal val ALARM_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/**
 * Loops one tone on the alarm stream until [release]. It plays the user's ringtone when
 * that opens in time and the bundled tone otherwise. If the player dies mid-ring it is
 * rebuilt on the bundled tone, again and again, because silence is the one wrong outcome.
 *
 * Call every method on the main thread. [scope] runs on the main thread too.
 */
internal class AlarmAudioPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routingListener = AudioRouting.OnRoutingChangedListener { onRoutingChanged() }

    private var player: MediaPlayer? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private var rampMillis = 0L
    private var startedAtMillis = 0L
    private var recovery: Job? = null

    /** Called on the main thread whenever the system reports a new output route. */
    var onRoutingChanged: () -> Unit = {}

    /** The device audio really goes to, or null when that is not known yet. */
    val routedDevice: AudioDeviceInfo?
        get() = try {
            player?.routedDevice
        } catch (e: IllegalStateException) {
            null
        }

    suspend fun prepare(ringtoneUri: String?) {
        player = ringtoneUri?.let { openUserTone(it) } ?: openBundledTone().await()
    }

    fun start(rampSeconds: Int) {
        rampMillis = rampSeconds * 1000L
        startedAtMillis = SystemClock.elapsedRealtime()
        startCurrent()
        scope.launch {
            while (applyGain() < 1f) delay(RAMP_STEP_MILLIS)
        }
    }

    /** Pins output to [device], or returns to default routing with null. False when the player refused. */
    fun setPreferredDevice(device: AudioDeviceInfo?): Boolean {
        preferredDevice = device
        val current = player ?: return true
        return try {
            current.setPreferredDevice(device)
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun release() {
        recovery?.cancel()
        player?.let(::releaseQuietly)
        player = null
    }

    private fun startCurrent() {
        val current = player
        if (current == null) {
            recover()
            return
        }
        try {
            current.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra, rebuilding on the bundled tone")
                recover()
                true
            }
            current.addOnRoutingChangedListener(routingListener, mainHandler)
            preferredDevice?.let(current::setPreferredDevice)
            applyGain()
            current.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaPlayer did not start", e)
            recover()
        }
    }

    private fun recover() {
        player?.let(::releaseQuietly)
        player = null
        if (recovery?.isActive == true) return
        recovery = scope.launch {
            delay(RECOVERY_DELAY_MILLIS)
            player = openBundledTone().await()
            recovery = null
            startCurrent()
        }
    }

    private fun applyGain(): Float {
        val gain = rampGain(SystemClock.elapsedRealtime() - startedAtMillis, rampMillis)
        try {
            player?.setVolume(gain, gain)
        } catch (e: IllegalStateException) {
            // The error listener rebuilds the player. The next step sets the gain on the new one.
        }
        return gain
    }

    /** Null when the tone does not open within [USER_TONE_TIMEOUT_MILLIS], for example before the first unlock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun openUserTone(uri: String): MediaPlayer? {
        val opening = openAsync { setDataSource(context, Uri.parse(uri)) }
        val opened = withTimeoutOrNull(USER_TONE_TIMEOUT_MILLIS) { opening.await() }
        if (opened == null) {
            opening.invokeOnCompletion { failure -> if (failure == null) opening.getCompleted()?.let(::releaseQuietly) }
        }
        return opened
    }

    private fun openBundledTone(): Deferred<MediaPlayer?> = openAsync {
        context.resources.openRawResourceFd(R.raw.ring_default).use { setDataSource(it) }
    }

    /** Opens off the main thread, because a content provider behind a ringtone URI can be slow. */
    private fun openAsync(source: MediaPlayer.() -> Unit): Deferred<MediaPlayer?> = scope.async(Dispatchers.IO) {
        val opened = open(source)
        if (isActive) {
            opened
        } else {
            opened?.let(::releaseQuietly)
            null
        }
    }

    private fun open(source: MediaPlayer.() -> Unit): MediaPlayer? {
        val candidate = MediaPlayer()
        return try {
            candidate.setAudioAttributes(ALARM_AUDIO_ATTRIBUTES)
            candidate.source()
            candidate.isLooping = true
            candidate.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            candidate.prepare()
            candidate
        } catch (e: Exception) {
            Log.w(TAG, "Tone did not open", e)
            releaseQuietly(candidate)
            null
        }
    }

    private fun releaseQuietly(target: MediaPlayer) {
        try {
            target.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaPlayer release failed", e)
        }
    }

    private companion object {
        const val TAG = "AlarmAudioPlayer"
        const val USER_TONE_TIMEOUT_MILLIS = 3_000L
        const val RECOVERY_DELAY_MILLIS = 1_000L
        const val RAMP_STEP_MILLIS = 200L
    }
}
