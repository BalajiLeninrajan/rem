package dev.balaji.rem.ring.audio

import android.media.AudioDeviceInfo
import java.time.Duration

/** An audio output as the routing decision sees it: the system's device id and its `AudioDeviceInfo.TYPE_*`. */
internal data class OutputDevice(val id: Int, val type: Int)

internal enum class SpeakerReason {
    /** The alarm does not ask for headphones only. */
    NOT_REQUESTED,

    /** No headphone-type output was connected when the ring started. */
    NO_HEADPHONES,

    /** The pinned device went away. */
    DISCONNECTED,

    /** The system routed the audio somewhere other than the pinned device. */
    MISMATCH,

    /** Nobody dismissed within the speaker fallback time. Connected headphones may not be worn. */
    TIMED_OUT,
}

internal sealed interface RouteState {
    /** Nothing decided yet. Only before the first decision. */
    data object Undecided : RouteState

    /** Playback is pinned to [device] and the phone speaker stays silent. */
    data class Pinned(val device: OutputDevice) : RouteState

    /** Default routing, which for an alarm means the speaker. Final: a ring never goes back to headphones only. */
    data class Speaker(val reason: SpeakerReason) : RouteState
}

/**
 * Types that count as headphones, best first. Bluetooth A2DP is included although the
 * type cannot tell headphones from a Bluetooth speaker.
 */
private val HEADPHONE_TYPES = listOf(
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLE_HEARING_AID,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
)

/**
 * Whether an `AudioDeviceInfo.TYPE_*` is worn on the head. Speakers, the SCO telephony
 * link, HDMI, BLE broadcast and the ambiguous USB and line outputs are not.
 */
internal fun isHeadphoneType(type: Int): Boolean = type in HEADPHONE_TYPES

/** The device to pin among [connected], or null when none is a headphone. Wired wins over wireless. */
internal fun pickHeadphone(connected: Collection<OutputDevice>): OutputDevice? =
    HEADPHONE_TYPES.firstNotNullOfOrNull { type -> connected.firstOrNull { it.type == type } }

/**
 * The routing decision. Called when the ring starts and again on every device change,
 * routing change and timer tick.
 *
 * @param connected the outputs connected right now.
 * @param routed where the player's audio really goes, or null when that is not known.
 * @param elapsed time since the ring started playing.
 * @param fallbackAfter the speaker fallback setting.
 */
internal fun nextRoute(
    state: RouteState,
    headphonesOnly: Boolean,
    connected: Collection<OutputDevice>,
    routed: OutputDevice?,
    elapsed: Duration,
    fallbackAfter: Duration,
): RouteState = when {
    state is RouteState.Speaker -> state
    !headphonesOnly -> RouteState.Speaker(SpeakerReason.NOT_REQUESTED)
    elapsed >= fallbackAfter -> RouteState.Speaker(SpeakerReason.TIMED_OUT)
    state is RouteState.Pinned -> when {
        connected.none { it.id == state.device.id } -> RouteState.Speaker(SpeakerReason.DISCONNECTED)
        routed != null && !routed.isSameOutputAs(state.device) -> RouteState.Speaker(SpeakerReason.MISMATCH)
        else -> state
    }
    else -> pickHeadphone(connected)?.let { RouteState.Pinned(it) } ?: RouteState.Speaker(SpeakerReason.NO_HEADPHONES)
}

/** A headset can show up as more than one device of the same type, so a different id of the same type still counts. */
private fun OutputDevice.isSameOutputAs(other: OutputDevice): Boolean = id == other.id || type == other.type
