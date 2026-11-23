package dev.balaji.rem.core

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmVolumeTest {
    private val alarm = Alarm(time = LocalTime.of(7, 0))

    @Test
    fun `defaults match the product spec and are in range`() {
        assertEquals(70, alarm.minRingVolumePercent)
        assertEquals(30, alarm.volumeRampSeconds)
        assertEquals(alarm, alarm.coerced())
    }

    @Test
    fun `volume values outside their ranges are moved to the nearest end`() {
        assertEquals(
            alarm.copy(minRingVolumePercent = 0, volumeRampSeconds = 0),
            alarm.copy(minRingVolumePercent = -10, volumeRampSeconds = -1).coerced(),
        )
        assertEquals(
            alarm.copy(minRingVolumePercent = 100, volumeRampSeconds = 300),
            alarm.copy(minRingVolumePercent = 500, volumeRampSeconds = 5000).coerced(),
        )
    }

    @Test
    fun `coercion leaves every other field alone`() {
        val full = Alarm(
            id = 4,
            time = LocalTime.of(6, 30),
            label = "gym",
            repeatDays = RepeatDays.WEEKDAYS,
            enabled = false,
            vibrate = false,
            ringtoneUri = "content://tone",
            minRingVolumePercent = 101,
        )

        assertEquals(full.copy(minRingVolumePercent = 100), full.coerced())
    }
}
