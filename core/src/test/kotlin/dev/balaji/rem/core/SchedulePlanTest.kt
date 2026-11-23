package dev.balaji.rem.core

import dev.balaji.rem.core.WakeExplanation.AtDeadline
import dev.balaji.rem.core.WakeExplanation.Cycles
import dev.balaji.rem.core.WakeExplanation.LateCycles
import java.time.Duration
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        // Boundaries are 06:45 and 08:15. 08:00 has none inside its window, so 08:15 is its late option.
        assertEquals(PlannedRing(1, toronto("2026-01-15T06:45"), toronto("2026-01-15T07:00"), Cycles(5, 15)), plan.forAlarm(1))
        assertEquals(
            PlannedRing(
                2, toronto("2026-01-15T08:00"), toronto("2026-01-15T08:00"), AtDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW),
                LateOption(toronto("2026-01-15T08:15"), 6, 15),
            ),
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

    // The late option example: session at midnight, boundaries 01:45, 03:15, 04:45 and 06:15.
    // 04:45 is outside the 30 minute early window, so 06:00 rings at 06:00 unless 06:15 is accepted.
    private val six = Alarm(id = 7, time = LocalTime.of(6, 0), repeatDays = RepeatDays.EVERY_DAY)
    private val sixOnce = Alarm(id = 8, time = LocalTime.of(6, 0))
    private val midnight = SleepSession(toronto("2026-01-15T00:00"))
    private val accepted = midnight.copy(acceptedLate = setOf(7L, 8L))
    private val sixDeadline = toronto("2026-01-15T06:00")
    private val lateOption = LateOption(toronto("2026-01-15T06:15"), 4, 15)
    private val lateRing = PlannedRing(7, lateOption.ringAt, sixDeadline, LateCycles(4, 15), lateOption)

    @Test
    fun `a ring with a late option carries it and rings at the deadline until it is accepted`() {
        val plan = planSchedule(listOf(six), midnight, settings, clockAt("2026-01-15T00:00"))

        assertEquals(
            PlannedRing(7, sixDeadline, sixDeadline, AtDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW), lateOption),
            plan.forAlarm(7),
        )
    }

    @Test
    fun `an accepted late option moves the ring after the deadline`() {
        val plan = planSchedule(listOf(six, seven), accepted, settings, clockAt("2026-01-15T00:00"))

        assertEquals(lateRing, plan.forAlarm(7))
        // Acceptance is per alarm. 07:00 is not in the accepted set, and its next boundary, 07:45, is too late anyway.
        assertEquals(
            PlannedRing(1, toronto("2026-01-15T07:00"), toronto("2026-01-15T07:00"), AtDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW)),
            plan.forAlarm(1),
        )
    }

    @Test
    fun `between the deadline and the late ring the occurrence stays planned`() {
        for (now in listOf("2026-01-15T06:00", "2026-01-15T06:00:01", "2026-01-15T06:07", "2026-01-15T06:14:59")) {
            val plan = planSchedule(listOf(six), accepted, settings, clockAt(now))

            assertEquals(now, lateRing, plan.forAlarm(7))
        }
    }

    @Test
    fun `a one-shot alarm keeps its late ring instead of moving to the next day`() {
        val plan = planSchedule(listOf(sixOnce), accepted, settings, clockAt("2026-01-15T06:05"))

        assertEquals(lateRing.copy(alarmId = 8), plan.forAlarm(8))
    }

    @Test
    fun `once the late ring has fired the next occurrence is planned`() {
        val plan = planSchedule(
            listOf(six), accepted, settings, clockAt("2026-01-15T06:15:01"), lastFired = mapOf(7L to sixDeadline),
        )

        assertEquals(toronto("2026-01-16T06:00"), plan.forAlarm(7)?.deadline)
    }

    @Test
    fun `a fired late occurrence is not planned again while its late ring is ahead`() {
        // It cannot have fired early, but a fired record for it must still win.
        val plan = planSchedule(listOf(six), accepted, settings, clockAt("2026-01-15T06:05"), lastFired = mapOf(7L to sixDeadline))

        assertEquals(toronto("2026-01-16T06:00"), plan.forAlarm(7)?.deadline)
    }

    @Test
    fun `once the late ring time has passed without a fire the plan moves on and the ring is missed`() {
        val now = toronto("2026-01-15T06:20")
        val plan = planSchedule(
            listOf(six), accepted, settings, clockAt("2026-01-15T06:20"), previous = listOf(lateRing.toScheduledRing()),
        )

        assertEquals(toronto("2026-01-16T06:00"), plan.forAlarm(7)?.deadline)
        assertEquals(listOf(lateRing.toScheduledRing()), checkMissed(listOf(lateRing.toScheduledRing()), emptyMap(), now).missed)
    }

    @Test
    fun `a late ring is not overdue at its deadline, only at its ring time`() {
        val ring = lateRing.toScheduledRing()

        assertEquals(MissedCheck(emptyList(), emptyList()), checkMissed(listOf(ring), emptyMap(), toronto("2026-01-15T06:10")))
        assertEquals(MissedCheck(emptyList(), listOf(ring)), checkMissed(listOf(ring), emptyMap(), toronto("2026-01-15T06:15:30")))
        assertEquals(MissedCheck(listOf(ring), emptyList()), checkMissed(listOf(ring), emptyMap(), toronto("2026-01-15T06:16")))
        assertEquals(
            MissedCheck(emptyList(), emptyList()),
            checkMissed(listOf(ring), mapOf(7L to sixDeadline), toronto("2026-01-15T06:16")),
        )
    }

    @Test
    fun `an owed late ring that loses its late option rings at once`() {
        val owed = listOf(lateRing.toScheduledRing())
        val now = "2026-01-15T06:05"
        val withdrawn = mapOf(
            "revoked" to Pair(midnight, settings),
            "session ended" to Pair(null, settings),
            "new session" to Pair(SleepSession(toronto(now)), settings),
            "setting removed the option" to Pair(accepted, RemSettings(fallAsleepMinutes = 0)),
        )

        for ((name, change) in withdrawn) {
            val (session, changed) = change
            val ring = planSchedule(listOf(six), session, changed, clockAt(now), previous = owed).forAlarm(7)!!

            assertEquals(name, toronto(now), ring.ringAt)
            assertEquals(name, sixDeadline, ring.deadline)
            assertTrue(name, ring.explanation is AtDeadline)
        }
    }

    @Test
    fun `a setting that moves the late boundary moves the owed ring`() {
        // Onset 00:20 makes the boundaries 04:50 and 06:20.
        val ring = planSchedule(
            listOf(six), accepted, RemSettings(fallAsleepMinutes = 20), clockAt("2026-01-15T06:05"),
            previous = listOf(lateRing.toScheduledRing()),
        ).forAlarm(7)

        assertEquals(toronto("2026-01-15T06:20"), ring?.ringAt)
        assertEquals(LateCycles(4, 20), ring?.explanation)
    }

    @Test
    fun `a late ring whose setting now puts it in the past is overdue, not moved`() {
        // Onset 00:05 makes the late boundary 06:05, which has passed at 06:10.
        val plan = planSchedule(listOf(six), accepted, RemSettings(fallAsleepMinutes = 5), clockAt("2026-01-15T06:10"))

        assertEquals(toronto("2026-01-16T06:00"), plan.forAlarm(7)?.deadline)
    }

    @Test
    fun `an owed late ring is dropped once the alarm moved to another time`() {
        val moved = six.copy(time = LocalTime.of(6, 30))

        val plan = planSchedule(
            listOf(moved), midnight, settings, clockAt("2026-01-15T06:05"), previous = listOf(lateRing.toScheduledRing()),
        )

        assertEquals(toronto("2026-01-15T06:30"), plan.forAlarm(7)?.deadline)
    }

    @Test
    fun `a previous ring that is not late, or later than the late window, is not owed`() {
        val now = clockAt("2026-01-15T06:05")
        val onTime = ScheduledRing(7, sixDeadline, sixDeadline)
        val corrupt = ScheduledRing(7, sixDeadline.plus(LATE_WINDOW).plusSeconds(1), sixDeadline)

        for (previous in listOf(onTime, corrupt)) {
            val plan = planSchedule(listOf(six), midnight, settings, now, previous = listOf(previous))

            assertEquals(toronto("2026-01-16T06:00"), plan.forAlarm(7)?.deadline)
        }
    }

    @Test
    fun `a planned ring is never later than the late window after its deadline`() {
        val alarms = listOf(six, sixOnce, seven, eight)
        val owed = listOf(lateRing.toScheduledRing(), lateRing.toScheduledRing().copy(alarmId = 8))
        var start = toronto("2026-01-14T22:00")
        while (start < toronto("2026-01-15T06:30")) {
            val session = SleepSession(start, setOf(7L, 8L, 1L, 2L))
            var now = start
            while (now < toronto("2026-01-15T09:00")) {
                for (previous in listOf(emptyList(), owed)) {
                    for (s in listOf(session, null)) {
                        val clock = java.time.Clock.fixed(now, TORONTO)
                        for (ring in planSchedule(alarms, s, settings, clock, previous = previous).rings) {
                            assertFalse(ring.ringAt < now)
                            assertTrue(ring.ringAt <= ring.deadline.plus(LATE_WINDOW))
                        }
                    }
                }
                now = now.plus(Duration.ofMinutes(4))
            }
            start = start.plus(Duration.ofMinutes(13))
        }
    }

    @Test
    fun `late offers name each covered ring with a late option, earliest first`() {
        val nap = Alarm(id = 9, time = LocalTime.of(1, 30), repeatDays = RepeatDays.EVERY_DAY)
        val before = planSchedule(listOf(six, seven, nap), null, settings, clockAt("2026-01-15T00:00"))

        val offers = before.lateOffers(midnight, settings)

        assertEquals(
            listOf(
                LateOffer(9, toronto("2026-01-15T01:30"), LateOption(toronto("2026-01-15T01:45"), 1, 15)),
                LateOffer(7, sixDeadline, lateOption),
            ),
            offers,
        )
        assertTrue(before.lateOffers(SleepSession(toronto("2026-01-14T23:00")), settings).none { it.alarmId == 1L })
    }

    @Test
    fun `an occurrence that already rang is not offered, the plan has moved past it`() {
        val before = planSchedule(
            listOf(six), null, settings, clockAt("2026-01-15T00:00"), lastFired = mapOf(7L to sixDeadline),
        )

        assertTrue(before.lateOffers(midnight, settings).isEmpty())
    }
}

