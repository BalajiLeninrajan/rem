package dev.balaji.rem.core

import dev.balaji.rem.core.WakeExplanation.AtDeadline
import dev.balaji.rem.core.WakeExplanation.Cycles
import java.time.Duration
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePlanTest {
    private val seven = Alarm(id = 1, time = LocalTime.of(7, 0), repeatDays = RepeatDays.EVERY_DAY)
    private val eight = Alarm(id = 2, time = LocalTime.of(8, 0))
    private val settings = RemSettings()

    @Test
    fun `without a session every enabled alarm rings at its deadline`() {
        val plan = planSchedule(listOf(eight, seven), null, settings, clockAt("2026-01-14T22:00"))

        assertEquals(
            listOf(
                PlannedRing(1, toronto("2026-01-15T07:00"), toronto("2026-01-15T07:00"), AtDeadline(DeadlineReason.NO_SESSION)),
                PlannedRing(2, toronto("2026-01-15T08:00"), toronto("2026-01-15T08:00"), AtDeadline(DeadlineReason.NO_SESSION)),
            ),
            plan.rings,
        )
        assertEquals(1L, plan.next?.alarmId)
    }

    @Test
    fun `disabled alarms are left out`() {
        val plan = planSchedule(listOf(seven.copy(enabled = false), eight), null, settings, clockAt("2026-01-14T22:00"))

        assertNull(plan.forAlarm(1))
        assertEquals(listOf(2L), plan.rings.map { it.alarmId })
    }

    @Test
    fun `no alarms gives an empty plan`() {
        val plan = planSchedule(emptyList(), null, settings, clockAt("2026-01-14T22:00"))

        assertTrue(plan.rings.isEmpty())
        assertNull(plan.next)
    }

    @Test
    fun `a session moves each covered alarm to its own cycle boundary`() {
        val session = SleepSession(toronto("2026-01-14T23:00"))

        val plan = planSchedule(listOf(seven, eight), session, settings, clockAt("2026-01-14T23:00"))

        // Boundaries are 06:45 and 08:15. 08:00 has none inside its window.
        assertEquals(PlannedRing(1, toronto("2026-01-15T06:45"), toronto("2026-01-15T07:00"), Cycles(5, 15)), plan.forAlarm(1))
        assertEquals(
            PlannedRing(2, toronto("2026-01-15T08:00"), toronto("2026-01-15T08:00"), AtDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW)),
            plan.forAlarm(2),
        )
    }

    @Test
    fun `a session only shapes deadlines within 16 hours`() {
        val evening = Alarm(id = 3, time = LocalTime.of(18, 0))
        val session = SleepSession(toronto("2026-01-14T23:00"))

        val plan = planSchedule(listOf(evening), session, settings, clockAt("2026-01-14T23:00"))

        assertEquals(AtDeadline(DeadlineReason.OUTSIDE_SESSION), plan.forAlarm(3)?.explanation)
    }

    @Test
    fun `an occurrence that already fired early is not planned again`() {
        val session = SleepSession(toronto("2026-01-14T23:00"))
        val firedDeadline = toronto("2026-01-15T07:00")

        val plan = planSchedule(
            listOf(seven), session, settings, clockAt("2026-01-15T06:45"), lastFired = mapOf(1L to firedDeadline),
        )

        assertEquals(toronto("2026-01-16T07:00"), plan.forAlarm(1)?.deadline)
        assertEquals(toronto("2026-01-16T07:00"), plan.forAlarm(1)?.ringAt)
    }

    @Test
    fun `without the fired record the same occurrence would come back`() {
        val session = SleepSession(toronto("2026-01-14T23:00"))

        val plan = planSchedule(listOf(seven), session, settings, clockAt("2026-01-15T06:46"))

        assertEquals(
            PlannedRing(1, toronto("2026-01-15T07:00"), toronto("2026-01-15T07:00"), AtDeadline(DeadlineReason.CYCLE_ALREADY_PASSED)),
            plan.forAlarm(1),
        )
    }

    @Test
    fun `an old fired record does not hold back later occurrences`() {
        val plan = planSchedule(
            listOf(seven), null, settings, clockAt("2026-01-20T12:00"),
            lastFired = mapOf(1L to toronto("2026-01-15T07:00")),
        )

        assertEquals(toronto("2026-01-21T07:00"), plan.forAlarm(1)?.deadline)
    }

    @Test
    fun `rings are ordered by ring time, not by deadline`() {
        val early = Alarm(id = 5, time = LocalTime.of(6, 50))
        val session = SleepSession(toronto("2026-01-14T23:00"))

        val plan = planSchedule(listOf(early, seven), session, settings, clockAt("2026-01-14T23:00"))

        // 07:00 rings at 06:45 on a boundary, 06:50 rings at 06:45 too. Ties break on id.
        assertEquals(listOf(1L, 5L), plan.rings.map { it.alarmId })
        assertEquals(toronto("2026-01-15T06:45"), plan.forAlarm(5)?.ringAt)
    }

    @Test
    fun `the clock zone decides the deadline instant`() {
        val plan = planSchedule(listOf(eight), null, settings, clockAt("2026-03-07T22:00"))

        // 08:00 EDT on the spring forward day.
        assertEquals(java.time.Instant.parse("2026-03-08T12:00:00Z"), plan.forAlarm(2)?.deadline)
    }

    @Test
    fun `missed check splits overdue rings by the grace period`() {
        val now = toronto("2026-01-15T07:10")
        val longOverdue = ScheduledRing(1, toronto("2026-01-15T06:45"), toronto("2026-01-15T07:00"))
        val justDue = ScheduledRing(2, now.minusSeconds(20), now.minusSeconds(20))
        val future = ScheduledRing(3, toronto("2026-01-15T08:00"), toronto("2026-01-15T08:00"))

        val check = checkMissed(listOf(longOverdue, justDue, future), emptyMap(), now)

        assertEquals(listOf(longOverdue), check.missed)
        assertEquals(listOf(justDue), check.pending)
    }

    @Test
    fun `a ring that fired is neither missed nor pending`() {
        val now = toronto("2026-01-15T07:10")
        val ring = ScheduledRing(1, toronto("2026-01-15T06:45"), toronto("2026-01-15T07:00"))

        val check = checkMissed(listOf(ring), mapOf(1L to ring.deadline), now)

        assertEquals(MissedCheck(emptyList(), emptyList()), check)
    }

    @Test
    fun `a fire recorded for an earlier occurrence does not hide a miss`() {
        val now = toronto("2026-01-15T07:10")
        val ring = ScheduledRing(1, toronto("2026-01-15T07:00"), toronto("2026-01-15T07:00"))
        val yesterday = ring.deadline.minus(Duration.ofDays(1))

        val check = checkMissed(listOf(ring), mapOf(1L to yesterday), now)

        assertEquals(listOf(ring), check.missed)
    }

    @Test
    fun `an early ring that has not reached its deadline is not overdue`() {
        // Rebooted at 06:50 after a 06:45 ring was lost. The plan will ring at 07:00 instead.
        val now = toronto("2026-01-15T06:50")
        val ring = ScheduledRing(1, toronto("2026-01-15T06:45"), toronto("2026-01-15T07:00"))

        assertEquals(MissedCheck(emptyList(), emptyList()), checkMissed(listOf(ring), emptyMap(), now))
    }
}
