package dev.balaji.rem.core

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatDaysTest {
    @Test
    fun `mask uses bit 0 for Monday and bit 6 for Sunday`() {
        assertEquals(0, RepeatDays.toMask(RepeatDays.NONE))
        assertEquals(0b0000001, RepeatDays.toMask(setOf(DayOfWeek.MONDAY)))
        assertEquals(0b1000000, RepeatDays.toMask(setOf(DayOfWeek.SUNDAY)))
        assertEquals(0b0011111, RepeatDays.toMask(RepeatDays.WEEKDAYS))
        assertEquals(0b1111111, RepeatDays.toMask(RepeatDays.EVERY_DAY))
    }

    @Test
    fun `every subset survives a round trip`() {
        for (mask in 0..0b1111111) {
            assertEquals(mask, RepeatDays.toMask(RepeatDays.fromMask(mask)))
        }
    }

    @Test
    fun `bits above Sunday are ignored`() {
        assertEquals(setOf(DayOfWeek.TUESDAY), RepeatDays.fromMask(0b110000010))
    }
}
