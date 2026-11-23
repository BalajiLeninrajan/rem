package dev.balaji.rem.core

import dev.balaji.rem.core.DeadlineReason.BEFORE_FIRST_CYCLE
import dev.balaji.rem.core.DeadlineReason.CYCLE_ALREADY_PASSED
import dev.balaji.rem.core.DeadlineReason.NO_CYCLE_IN_WINDOW
import dev.balaji.rem.core.DeadlineReason.NO_SESSION
import dev.balaji.rem.core.DeadlineReason.OUTSIDE_SESSION
import dev.balaji.rem.core.WakeExplanation.AtDeadline
import dev.balaji.rem.core.WakeExplanation.Cycles
import dev.balaji.rem.core.WakeExplanation.LateCycles
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTimeTest {
    private data class Case(
        val name: String,
        val sessionStart: String?,
        val deadline: String,
        val expectedRing: String,
        val expected: WakeExplanation,
        val settings: RemSettings = RemSettings(),
        /** Defaults to the session start, or to an hour before the deadline without a session. */
        val now: String? = null,
    )

    private val cases = listOf(
        Case("spec example", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:45", Cycles(5, 15)),
        Case("boundary on the deadline", "2026-01-14T23:15", "2026-01-15T07:00", "2026-01-15T07:00", Cycles(5, 0)),
        Case("boundary exactly at the window edge", "2026-01-14T22:45", "2026-01-15T07:00", "2026-01-15T06:30", Cycles(5, 30)),
        Case(
            "one minute outside the window", "2026-01-14T22:44", "2026-01-15T07:00", "2026-01-15T07:00",
            AtDeadline(NO_CYCLE_IN_WINDOW),
        ),
        Case(
            "boundary just after the deadline does not count", "2026-01-14T23:16", "2026-01-15T07:00",
            "2026-01-15T07:00", AtDeadline(NO_CYCLE_IN_WINDOW),
        ),
        Case(
            "seconds in the session start are dropped", "2026-01-14T22:45:59", "2026-01-15T07:00",
            "2026-01-15T06:30", Cycles(5, 30),
        ),
        Case("nap shorter than one cycle", "2026-01-15T06:00", "2026-01-15T07:00", "2026-01-15T07:00", AtDeadline(BEFORE_FIRST_CYCLE)),
        Case("deadline before sleep onset", "2026-01-15T06:50", "2026-01-15T07:00", "2026-01-15T07:00", AtDeadline(BEFORE_FIRST_CYCLE)),
        Case("exactly one cycle", "2026-01-15T05:15", "2026-01-15T07:00", "2026-01-15T07:00", Cycles(1, 0)),
        Case("no session", null, "2026-01-15T07:00", "2026-01-15T07:00", AtDeadline(NO_SESSION)),
        Case(
            "deadline more than 16 hours after the session start", "2026-01-14T14:59", "2026-01-15T07:00",
            "2026-01-15T07:00", AtDeadline(OUTSIDE_SESSION),
        ),
        Case(
            "deadline exactly 16 hours after the session start is covered", "2026-01-14T15:00", "2026-01-15T07:00",
            "2026-01-15T06:45", Cycles(10, 15), RemSettings(fallAsleepMinutes = 45),
        ),
        Case(
            "deadline before the session start", "2026-01-15T08:00", "2026-01-15T07:00", "2026-01-15T07:00",
            AtDeadline(OUTSIDE_SESSION), now = "2026-01-15T06:00",
        ),
        Case(
            "boundary already passed", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T07:00",
            AtDeadline(CYCLE_ALREADY_PASSED), now = "2026-01-15T06:46",
        ),
        Case(
            "boundary is now", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:45",
            Cycles(5, 15), now = "2026-01-15T06:45",
        ),
        Case(
            "zero window only accepts an exact hit", "2026-01-14T23:14", "2026-01-15T07:00", "2026-01-15T07:00",
            AtDeadline(NO_CYCLE_IN_WINDOW), RemSettings(earlyWindowMinutes = 0),
        ),
        Case(
            "zero window, exact hit", "2026-01-14T23:15", "2026-01-15T07:00", "2026-01-15T07:00",
            Cycles(5, 0), RemSettings(earlyWindowMinutes = 0),
        ),
        Case(
            "two boundaries in the window, latest wins", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:15",
            Cycles(7, 45), RemSettings(cycleMinutes = 60, earlyWindowMinutes = 90),
        ),
        Case(
            "no fall asleep delay", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:30",
            Cycles(5, 30), RemSettings(fallAsleepMinutes = 0),
        ),
        Case(
            "long cycles", "2026-01-14T23:00", "2026-01-15T07:30", "2026-01-15T07:15",
            Cycles(4, 15), RemSettings(cycleMinutes = 120),
        ),
        // Spring forward night: 23:00 EST to 07:00 EDT is only seven hours of real time.
        Case(
            "spring forward removes a cycle", "2026-03-07T23:00", "2026-03-08T07:00", "2026-03-08T06:15",
            Cycles(4, 45), RemSettings(earlyWindowMinutes = 45),
        ),
        Case(
            "spring forward, default window", "2026-03-07T23:00", "2026-03-08T07:00", "2026-03-08T07:00",
            AtDeadline(NO_CYCLE_IN_WINDOW),
        ),
        // Fall back night: 23:00 EDT to 07:00 EST is nine hours of real time.
        Case(
            "fall back fits a sixth cycle", "2026-10-31T23:00", "2026-11-01T07:00", "2026-11-01T07:00",
            Cycles(6, 0), RemSettings(fallAsleepMinutes = 0),
        ),
        Case(
            "fall back, default settings", "2026-10-31T23:00", "2026-11-01T07:00", "2026-11-01T07:00",
            AtDeadline(NO_CYCLE_IN_WINDOW),
        ),
    )

    @Test
    fun `wake time table`() {
        for (case in cases) {
            val session = case.sessionStart?.let { SleepSession(toronto(it)) }
            val deadline = toronto(case.deadline)
            val now = case.now?.let(::toronto) ?: session?.startedAt ?: deadline.minusSeconds(3600)

            val wake = computeWakeTime(deadline, session, case.settings, now)

            assertEquals(case.name, WakeTime(toronto(case.expectedRing), case.expected), wake.copy(late = null))
            assertFalse("${case.name}: rings after the deadline", wake.ringAt > deadline)
            assertFalse("${case.name}: rings before now", wake.ringAt < now)
        }
    }

    @Test
    fun `out of range settings are coerced instead of trusted`() {
        val session = SleepSession(toronto("2026-01-14T23:00"))
        val deadline = toronto("2026-01-15T07:00")
        val broken = RemSettings(cycleMinutes = 0, fallAsleepMinutes = -5, earlyWindowMinutes = 1000)

        val wake = computeWakeTime(deadline, session, broken, session.startedAt)

        // Coerced to 60 minute cycles, no delay, 90 minute window.
        assertEquals(WakeTime(deadline, Cycles(8, 0)), wake)
    }

    private data class LateCase(
        val name: String,
        val sessionStart: String?,
        val deadline: String,
        /** The late ring time, cycles and minutes after the deadline, or null for no late option. */
        val late: Triple<String, Int, Int>?,
        val settings: RemSettings = RemSettings(),
    )

    // Default settings: 15 minutes to fall asleep, 90 minute cycles, 30 minute early window.
    private val lateCases = listOf(
        // Boundaries 01:45, 03:15, 04:45, 06:15. 04:45 is 75 minutes early, 06:15 is after the deadline.
        LateCase("no cycle in the window", "2026-01-15T00:00", "2026-01-15T06:00", Triple("2026-01-15T06:15", 4, 15)),
        LateCase("exactly the late window", "2026-01-15T00:00", "2026-01-15T05:45", Triple("2026-01-15T06:15", 4, 30)),
        LateCase("one minute past the late window", "2026-01-15T00:00", "2026-01-15T05:44", null),
        LateCase("before the first cycle", "2026-01-15T00:00", "2026-01-15T01:30", Triple("2026-01-15T01:45", 1, 15)),
        LateCase("deadline before sleep onset", "2026-01-15T06:50", "2026-01-15T07:00", null),
        LateCase("a cycle in the window needs no late option", "2026-01-14T23:00", "2026-01-15T07:00", null),
        LateCase("no session", null, "2026-01-15T07:00", null),
        LateCase("outside the session", "2026-01-14T14:59", "2026-01-15T07:00", null),
        LateCase(
            "zero early window, next boundary 89 minutes after", "2026-01-14T23:14", "2026-01-15T07:00",
            null, RemSettings(earlyWindowMinutes = 0),
        ),
        LateCase(
            "zero early window, boundary one minute late", "2026-01-14T23:16", "2026-01-15T07:00",
            Triple("2026-01-15T07:01", 5, 1), RemSettings(earlyWindowMinutes = 0),
        ),
    )

    @Test
    fun `late option table`() {
        for (case in lateCases) {
            val session = case.sessionStart?.let { SleepSession(toronto(it)) }
            val deadline = toronto(case.deadline)
            val now = session?.startedAt ?: deadline.minusSeconds(3600)
            val expected = case.late?.let { (at, cycles, minutes) -> LateOption(toronto(at), cycles, minutes) }

            val declined = computeWakeTime(deadline, session, case.settings, now)
            val accepted = computeWakeTime(deadline, session, case.settings, now, acceptLate = true)

            assertEquals(case.name, expected, declined.late)
            assertFalse("${case.name}: declined rings after the deadline", declined.ringAt > deadline)
            if (expected == null) {
                assertEquals("${case.name}: accepting changes nothing", declined, accepted)
            } else {
                assertEquals(case.name, WakeTime(expected.ringAt, LateCycles(expected.cycles, expected.minutesAfterDeadline), expected), accepted)
            }
        }
    }

    @Test
    fun `the late option does not depend on now, and an accepted ring is never before now`() {
        val session = SleepSession(toronto("2026-01-15T00:00"))
        val deadline = toronto("2026-01-15T06:00")
        val late = LateOption(toronto("2026-01-15T06:15"), 4, 15)

        val inTheGap = computeWakeTime(deadline, session, RemSettings(), toronto("2026-01-15T06:05"), acceptLate = true)
        assertEquals(WakeTime(late.ringAt, LateCycles(4, 15), late), inTheGap)

        val declinedInTheGap = computeWakeTime(deadline, session, RemSettings(), toronto("2026-01-15T06:05"))
        assertEquals(toronto("2026-01-15T06:05"), declinedInTheGap.ringAt)
        assertEquals(late, declinedInTheGap.late)

        val pastIt = computeWakeTime(deadline, session, RemSettings(), toronto("2026-01-15T06:20"), acceptLate = true)
        assertEquals(toronto("2026-01-15T06:20"), pastIt.ringAt)
    }

    @Test
    fun `no ring is ever later than the late window after its deadline`() {
        val deadline = toronto("2026-01-15T07:00")
        val settingsList = listOf(
            RemSettings(),
            RemSettings(cycleMinutes = 60, fallAsleepMinutes = 0, earlyWindowMinutes = 0),
            RemSettings(cycleMinutes = 120, fallAsleepMinutes = 60, earlyWindowMinutes = 90),
            RemSettings(cycleMinutes = 0, fallAsleepMinutes = -5, earlyWindowMinutes = 1000),
        )
        var lateSeen = 0
        for (settings in settingsList) {
            for (minutesBefore in 0L..(17 * 60) step 7) {
                val session = SleepSession(deadline.minus(Duration.ofMinutes(minutesBefore)))
                for (accept in listOf(false, true)) {
                    val wake = computeWakeTime(deadline, session, settings, session.startedAt, accept)
                    assertTrue(wake.ringAt <= deadline.plus(LATE_WINDOW))
                    if (wake.ringAt > deadline) {
                        lateSeen++
                        assertTrue(accept)
                        assertTrue(wake.explanation is LateCycles)
                    }
                    wake.late?.let { assertTrue(it.ringAt > deadline && it.ringAt <= deadline.plus(LATE_WINDOW)) }
                }
            }
        }
        assertTrue("the sweep reached late rings", lateSeen > 0)
    }

    @Test
    fun `a late option is not offered once a cycle fits`() {
        val wake = computeWakeTime(
            toronto("2026-01-15T07:00"), SleepSession(toronto("2026-01-14T23:00")), RemSettings(),
            toronto("2026-01-15T06:46"), acceptLate = true,
        )

        assertEquals(AtDeadline(CYCLE_ALREADY_PASSED), wake.explanation)
        assertNull(wake.late)
    }
}

