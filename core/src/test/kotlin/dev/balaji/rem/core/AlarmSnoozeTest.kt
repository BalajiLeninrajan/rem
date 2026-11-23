package dev.balaji.rem.core

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSnoozeTest {
    private val alarm = Alarm(time = LocalTime.of(7, 0))

    @Test
    fun `an alarm snoozes 3 times for 5 minutes by default`() {
        assertEquals(5, alarm.snoozeMinutes)
        assertEquals(3, alarm.snoozeLimit)
        assertEquals(alarm, alarm.coerced())
    }

    @Test
    fun `snooze values outside their ranges are moved to the nearest end`() {
        assertEquals(
            alarm.copy(snoozeMinutes = 1, snoozeLimit = 0),
            alarm.copy(snoozeMinutes = 0, snoozeLimit = -2).coerced(),
        )
        assertEquals(
            alarm.copy(snoozeMinutes = 30, snoozeLimit = 10),
            alarm.copy(snoozeMinutes = 90, snoozeLimit = 11).coerced(),
        )
    }

    @Test
    fun `a snooze limit of 0 is kept, because it turns snooze off`() {
        assertEquals(0, alarm.copy(snoozeLimit = 0).coerced().snoozeLimit)
    }
}
