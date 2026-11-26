package dev.balaji.rem.ring.audio

import android.media.AudioDeviceInfo
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadphoneRoutingTest {
    private val speaker = OutputDevice(1, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val wired = OutputDevice(2, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    private val a2dp = OutputDevice(3, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val fallbackAfter = Duration.ofSeconds(120)

    private fun route(
        state: RouteState,
        headphonesOnly: Boolean = true,
        connected: List<OutputDevice> = listOf(speaker, wired),
        routed: OutputDevice? = null,
        elapsed: Duration = Duration.ZERO,
    ) = nextRoute(state, headphonesOnly, connected, routed, elapsed, fallbackAfter)

    @Test
    fun `speakers, telephony links, HDMI, broadcast and ambiguous outputs do not count`() {
        val others = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_UNKNOWN,
        )
        assertNull(pickHeadphone(others.mapIndexed { i, type -> OutputDevice(i, type) }))
    }

    @Test
    fun `wired headphones are picked over Bluetooth`() {
        assertEquals(wired, pickHeadphone(listOf(speaker, a2dp, wired)))
        assertEquals(a2dp, pickHeadphone(listOf(speaker, a2dp)))
        assertNull(pickHeadphone(listOf(speaker)))
    }

    @Test
    fun `with headphones only off the ring uses default routing even with headphones connected`() {
        assertEquals(
            RouteState.Speaker(SpeakerReason.NOT_REQUESTED),
            route(RouteState.Undecided, headphonesOnly = false),
        )
    }

    @Test
    fun `headphones only pins the connected headphone`() {
        assertEquals(RouteState.Pinned(wired), route(RouteState.Undecided))
    }

    @Test
    fun `headphones only with nothing connected rings on the speaker`() {
        assertEquals(
            RouteState.Speaker(SpeakerReason.NO_HEADPHONES),
            route(RouteState.Undecided, connected = listOf(speaker)),
        )
    }

    @Test
    fun `the pin holds while the device is connected and audio goes to it`() {
        val pinned = RouteState.Pinned(wired)
        assertEquals(pinned, route(pinned, routed = wired, elapsed = Duration.ofSeconds(119)))
        assertEquals("unknown route is not a mismatch", pinned, route(pinned, routed = null))
    }

    @Test
    fun `a second device of the pinned type is not a mismatch`() {
        val pinned = RouteState.Pinned(a2dp)
        val otherEar = OutputDevice(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        assertEquals(pinned, route(pinned, connected = listOf(speaker, a2dp, otherEar), routed = otherEar))
    }

    @Test
    fun `falls back to the speaker when the pinned device disconnects`() {
        assertEquals(
            RouteState.Speaker(SpeakerReason.DISCONNECTED),
            route(RouteState.Pinned(wired), connected = listOf(speaker, a2dp)),
        )
    }

    @Test
    fun `falls back when audio is routed to another device`() {
        assertEquals(
            RouteState.Speaker(SpeakerReason.MISMATCH),
            route(RouteState.Pinned(wired), routed = speaker),
        )
    }

    @Test
    fun `falls back once the speaker fallback time has passed`() {
        assertEquals(
            RouteState.Speaker(SpeakerReason.TIMED_OUT),
            route(RouteState.Pinned(wired), routed = wired, elapsed = fallbackAfter),
        )
    }

    @Test
    fun `a ring that fell back never returns to headphones only`() {
        val fellBack = RouteState.Speaker(SpeakerReason.DISCONNECTED)
        assertEquals(fellBack, route(fellBack, connected = listOf(speaker, wired), routed = wired))
    }
}
