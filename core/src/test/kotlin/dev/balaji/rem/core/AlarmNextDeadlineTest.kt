package dev.balaji.rem.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmNextDeadlineTest {
    private val sevenAm = Alarm(time = LocalTime.of(7, 0))

    private fun Alarm.next(afterLocal: String) = nextDeadline(toronto(afterLocal), TORONTO)

    @Test
    fun `one-shot rings today when the time is still ahead`() {
        assertEquals(toronto("2026-01-14T07:00"), sevenAm.next("2026-01-14T06:59"))
    }

    @Test
    fun `one-shot moves to tomorrow once the time has passed`() {
        assertEquals(toronto("2026-01-15T07:00"), sevenAm.next("2026-01-14T07:00"))
        assertEquals(toronto("2026-01-15T07:00"), sevenAm.next("2026-01-14T22:30"))
    }

    @Test
    fun `repeating alarm skips days that are not selected`() {
        // 2026-01-12 is a Monday.
        val alarm = sevenAm.copy(repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals(toronto("2026-01-12T07:00"), alarm.next("2026-01-12T06:00"))
        assertEquals(toronto("2026-01-14T07:00"), alarm.next("2026-01-12T07:00"))
        assertEquals(toronto("2026-01-19T07:00"), alarm.next("2026-01-14T08:00"))
    }

    @Test
    fun `single repeat day wraps a full week`() {
        val alarm = sevenAm.copy(repeatDays = setOf(DayOfWeek.MONDAY))
        assertEquals(toronto("2026-01-19T07:00"), alarm.next("2026-01-12T07:00"))
    }

    @Test
    fun `daily alarm keeps its wall clock time across spring forward`() {
        val alarm = sevenAm.copy(repeatDays = RepeatDays.EVERY_DAY)
        val saturday = alarm.next("2026-03-07T00:00")
        val sunday = alarm.nextDeadline(saturday, TORONTO)
        assertEquals(Instant.parse("2026-03-07T12:00:00Z"), saturday)
        assertEquals(Instant.parse("2026-03-08T11:00:00Z"), sunday)
        assertEquals(Duration.ofHours(23), Duration.between(saturday, sunday))
    }

    @Test
    fun `daily alarm keeps its wall clock time across fall back`() {
        val alarm = sevenAm.copy(repeatDays = RepeatDays.EVERY_DAY)
        val saturday = alarm.next("2026-10-31T00:00")
        val sunday = alarm.nextDeadline(saturday, TORONTO)
        assertEquals(Duration.ofHours(25), Duration.between(saturday, sunday))
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), sunday)
    }

    @Test
    fun `time inside the spring forward gap resolves to the start of the gap`() {
        // 02:00 to 03:00 does not exist on 2026-03-08. 02:00 EST is 07:00 UTC.
        val alarm = Alarm(time = LocalTime.of(2, 30), repeatDays = RepeatDays.EVERY_DAY)
        val inGap = alarm.next("2026-03-07T12:00")
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), inGap)
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), alarm.nextDeadline(inGap, TORONTO))
    }

    @Test
    fun `time inside the fall back overlap rings once, at the first occurrence`() {
        // 01:30 happens twice on 2026-11-01, first in EDT (05:30 UTC) then in EST (06:30 UTC).
        val alarm = Alarm(time = LocalTime.of(1, 30), repeatDays = RepeatDays.EVERY_DAY)
        val first = alarm.next("2026-10-31T12:00")
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first)
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), alarm.nextDeadline(first, TORONTO))
    }

    @Test
    fun `asking between the two occurrences of an overlap time gives the next day`() {
        val alarm = Alarm(time = LocalTime.of(1, 30))
        val betweenOccurrences = Instant.parse("2026-11-01T06:00:00Z")
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), alarm.nextDeadline(betweenOccurrences, TORONTO))
    }

    @Test
    fun `the zone decides which day is today`() {
        // 03:00 UTC on the 15th is still the evening of the 14th in Toronto.
        val after = Instant.parse("2026-01-15T03:00:00Z")
        assertEquals(Instant.parse("2026-01-15T12:00:00Z"), sevenAm.nextDeadline(after, TORONTO))
        assertEquals(Instant.parse("2026-01-15T07:00:00Z"), sevenAm.nextDeadline(after, ZoneId.of("UTC")))
    }
}
