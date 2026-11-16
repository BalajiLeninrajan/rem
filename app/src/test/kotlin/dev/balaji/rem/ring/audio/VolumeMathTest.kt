package dev.balaji.rem.ring.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeMathTest {
    @Test
    fun `floor is the percentage of the range above the minimum, rounded up`() {
        assertEquals(6, volumeFloorIndex(min = 1, max = 7, percent = 70))
        assertEquals(11, volumeFloorIndex(min = 0, max = 15, percent = 70))
        assertEquals(15, volumeFloorIndex(min = 0, max = 15, percent = 100))
        assertEquals(1, volumeFloorIndex(min = 1, max = 7, percent = 0))
    }

    @Test
    fun `a floor above zero percent never lands on the minimum index`() {
        assertEquals(1, volumeFloorIndex(min = 0, max = 7, percent = 1))
    }

    @Test
    fun `out of range percentages and empty ranges are safe`() {
        assertEquals(7, volumeFloorIndex(min = 1, max = 7, percent = 250))
        assertEquals(1, volumeFloorIndex(min = 1, max = 7, percent = -5))
        assertEquals(4, volumeFloorIndex(min = 4, max = 4, percent = 70))
    }

    @Test
    fun `volume is raised to the floor and never lowered`() {
        assertEquals(5, volumeToSet(current = 2, floor = 5))
        assertNull(volumeToSet(current = 5, floor = 5))
        assertNull(volumeToSet(current = 7, floor = 5))
    }

    @Test
    fun `ramp starts audible and reaches full volume at the end`() {
        assertEquals(0.1f, rampGain(elapsedMillis = 0, rampMillis = 30_000), 0.0001f)
        assertEquals(0.55f, rampGain(elapsedMillis = 15_000, rampMillis = 30_000), 0.0001f)
        assertEquals(1f, rampGain(elapsedMillis = 30_000, rampMillis = 30_000), 0f)
        assertEquals(1f, rampGain(elapsedMillis = 90_000, rampMillis = 30_000), 0f)
    }

    @Test
    fun `a ramp of zero seconds plays at full volume at once`() {
        assertEquals(1f, rampGain(elapsedMillis = 0, rampMillis = 0), 0f)
    }
}
