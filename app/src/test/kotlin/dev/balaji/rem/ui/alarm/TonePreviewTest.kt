package dev.balaji.rem.ui.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TonePreviewTest {
    private class FakePlayback(val tone: PreviewTone) : PreviewPlayback {
        val floors = mutableListOf<Int>()
        var stopped = 0

        override fun setMinRingVolumePercent(percent: Int) {
            floors += percent
        }

        override fun stop() {
            stopped++
        }
    }

    private val started = mutableListOf<FakePlayback>()
    private val preview = TonePreview { tone -> FakePlayback(tone).also { started += it } }

    private val bundled = PreviewTone(ringtoneUri = null, minRingVolumePercent = 70)
    private val custom = PreviewTone(ringtoneUri = "content://media/internal/audio/media/12", minRingVolumePercent = 70)

    @Test
    fun `toggle plays the tone and toggling again stops it`() {
        preview.toggle(bundled)
        assertTrue(preview.playing.value)
        assertEquals(listOf(bundled), started.map { it.tone })

        preview.toggle(bundled)
        assertFalse(preview.playing.value)
        assertEquals(1, started.single().stopped)
    }

    @Test
    fun `another tone stops the preview and the next toggle plays the new one`() {
        preview.toggle(bundled)
        preview.follow(custom)
        assertFalse(preview.playing.value)
        assertEquals(1, started.single().stopped)

        preview.toggle(custom)
        assertEquals(listOf(bundled, custom), started.map { it.tone })
    }

    @Test
    fun `another volume floor applies to the running preview without restarting it`() {
        preview.toggle(custom)
        preview.follow(custom.copy(minRingVolumePercent = 90))
        preview.follow(custom.copy(minRingVolumePercent = 90))

        assertTrue(preview.playing.value)
        assertEquals(1, started.size)
        assertEquals(listOf(90), started.single().floors)
        assertEquals(0, started.single().stopped)
    }

    @Test
    fun `the same tone keeps playing`() {
        preview.toggle(custom)
        preview.follow(custom)
        assertTrue(preview.playing.value)
        assertEquals(0, started.single().stopped)
    }

    @Test
    fun `changes while nothing plays start nothing`() {
        preview.follow(custom)
        preview.follow(bundled.copy(minRingVolumePercent = 20))
        preview.stop()
        assertFalse(preview.playing.value)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `stop stops the playback once`() {
        preview.toggle(bundled)
        preview.stop()
        preview.stop()
        assertFalse(preview.playing.value)
        assertEquals(1, started.single().stopped)
    }
}
