package dev.balaji.rem.ui.format

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `the week starts on the locale's first day and has all seven days`() {
        val us = weekInLocaleOrder(Locale.US)
        val germany = weekInLocaleOrder(Locale.GERMANY)

        assertEquals(DayOfWeek.SUNDAY, us.first())
        assertEquals(DayOfWeek.SATURDAY, us.last())
        assertEquals(DayOfWeek.entries, germany)
        assertEquals(DayOfWeek.entries.toSet(), us.toSet())
    }

    @Test
    fun `days between counts calendar days in the zone, not 24 hour periods`() {
        val toronto = ZoneId.of("America/Toronto")
        val lateEvening = Instant.parse("2026-01-15T04:59:00Z") // 23:59 on the 14th in Toronto
        val afterMidnight = Instant.parse("2026-01-15T05:01:00Z") // 00:01 on the 15th
        val nextMorning = Instant.parse("2026-01-15T12:00:00Z") // 07:00 on the 15th

        assertEquals(1, daysBetween(lateEvening, afterMidnight, toronto))
        assertEquals(0, daysBetween(afterMidnight, nextMorning, toronto))
        assertEquals(1, daysBetween(lateEvening, nextMorning, toronto))
        // In UTC all three fall on the 15th.
        assertEquals(0, daysBetween(lateEvening, nextMorning, ZoneId.of("UTC")))
    }
}
