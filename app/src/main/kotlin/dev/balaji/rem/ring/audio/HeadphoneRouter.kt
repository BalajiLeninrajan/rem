package dev.balaji.rem.ring.audio

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies [nextRoute] to the player: pins a headphone when the alarm asks for headphones
 * only, checks that the system honoured the pin, and returns to default routing when the
 * device disconnects, when audio goes elsewhere, or after [fallbackAfter].
 *
 * `setPreferredDevice` is best effort. The system ignores it while another app plays with
 * default routing on the same output, which is why the routed device is checked.
 *
 * Call every method on the main thread. [scope] runs on the main thread too.
 *
 * @param onRouteSettled called after every evaluation, so the volume floor is applied to
 *   the device that is playing now.
 */
internal class HeadphoneRouter(
    private val audioManager: AudioManager,
    private val player: AlarmAudioPlayer,
    private val headphonesOnly: Boolean,
    private val fallbackAfter: Duration,
    private val scope: CoroutineScope,
    private val onRouteSettled: () -> Unit,
) {
    private var state: RouteState = RouteState.Undecided
    private var startedAtMillis = 0L

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = evaluate()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = evaluate()
    }

    /** Decides the first route. Call after the player is prepared and before it starts, so a pinned ring never opens on the speaker. */
    fun start() {
        startedAtMillis = SystemClock.elapsedRealtime()
        evaluate()
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        player.onRoutingChanged = {
            // A new route is reported while it is still being set up. Judge it once it is stable.
            scope.launch {
                delay(ROUTE_SETTLE_MILLIS)
                evaluate()
            }
        }
        if (!headphonesOnly) return
        scope.launch {
            delay(ROUTE_SETTLE_MILLIS)
            evaluate()
            delay(fallbackAfter.toMillis())
            evaluate()
        }
    }

    fun stop() {
        player.onRoutingChanged = {}
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun evaluate() {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val next = nextRoute(
                state = state,
                headphonesOnly = headphonesOnly,
                connected = outputs.map { it.toOutputDevice() },
                routed = player.routedDevice?.toOutputDevice(),
                elapsed = Duration.ofMillis(SystemClock.elapsedRealtime() - startedAtMillis),
                fallbackAfter = fallbackAfter,
            )
            if (next != state) state = applyRoute(next, outputs)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Routing check failed, using default routing", e)
            state = RouteState.Speaker(SpeakerReason.MISMATCH)
            player.setPreferredDevice(null)
        }
        onRouteSettled()
    }

    /** Returns the state that really holds after trying to apply [next]. */
    private fun applyRoute(next: RouteState, outputs: Array<AudioDeviceInfo>): RouteState {
        Log.i(TAG, "Route: $state -> $next")
        if (next is RouteState.Pinned) {
            val device = outputs.firstOrNull { it.id == next.device.id }
            if (device != null && player.setPreferredDevice(device)) return next
            player.setPreferredDevice(null)
            return RouteState.Speaker(SpeakerReason.MISMATCH)
        }
        player.setPreferredDevice(null)
        return next
    }

    private fun AudioDeviceInfo.toOutputDevice() = OutputDevice(id, type)

    private companion object {
        const val TAG = "HeadphoneRouter"
        const val ROUTE_SETTLE_MILLIS = 500L
    }
}
