package dev.balaji.rem.core

import dev.balaji.rem.core.DeadlineReason.BEFORE_FIRST_CYCLE
import dev.balaji.rem.core.DeadlineReason.CYCLE_ALREADY_PASSED
import dev.balaji.rem.core.DeadlineReason.NO_CYCLE_IN_WINDOW
import dev.balaji.rem.core.DeadlineReason.NO_SESSION
import dev.balaji.rem.core.DeadlineReason.OUTSIDE_SESSION
import dev.balaji.rem.core.WakeExplanation.AtDeadline
import dev.balaji.rem.core.WakeExplanation.Cycles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WakeTimeTest {
    private data class Case(
        val name: String,
        val sessionStart: String?,
        val deadline: String,
        val expectedRing: String,
        val expected: WakeExplanation,
        val settings: Settings = Settings(),
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
            "2026-01-15T06:45", Cycles(10, 15), Settings(fallAsleepMinutes = 45),
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
            AtDeadline(NO_CYCLE_IN_WINDOW), Settings(earlyWindowMinutes = 0),
        ),
        Case(
            "zero window, exact hit", "2026-01-14T23:15", "2026-01-15T07:00", "2026-01-15T07:00",
            Cycles(5, 0), Settings(earlyWindowMinutes = 0),
        ),
        Case(
            "two boundaries in the window, latest wins", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:15",
            Cycles(7, 45), Settings(cycleMinutes = 60, earlyWindowMinutes = 90),
        ),
        Case(
            "no fall asleep delay", "2026-01-14T23:00", "2026-01-15T07:00", "2026-01-15T06:30",
            Cycles(5, 30), Settings(fallAsleepMinutes = 0),
        ),
        Case(
            "long cycles", "2026-01-14T23:00", "2026-01-15T07:30", "2026-01-15T07:15",
            Cycles(4, 15), Settings(cycleMinutes = 120),
        ),
        // Spring forward night: 23:00 EST to 07:00 EDT is only seven hours of real time.
        Case(
            "spring forward removes a cycle", "2026-03-07T23:00", "2026-03-08T07:00", "2026-03-08T06:15",
            Cycles(4, 45), Settings(earlyWindowMinutes = 45),
        ),
        Case(
            "spring forward, default window", "2026-03-07T23:00", "2026-03-08T07:00", "2026-03-08T07:00",
            AtDeadline(NO_CYCLE_IN_WINDOW),
        ),
        // Fall back night: 23:00 EDT to 07:00 EST is nine hours of real time.
        Case(
            "fall back fits a sixth cycle", "2026-10-31T23:00", "2026-11-01T07:00", "2026-11-01T07:00",
            Cycles(6, 0), Settings(fallAsleepMinutes = 0),
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

            assertEquals(case.name, WakeTime(toronto(case.expectedRing), case.expected), wake)
            assertFalse("${case.name}: rings after the deadline", wake.ringAt > deadline)
            assertFalse("${case.name}: rings before now", wake.ringAt < now)
        }
    }

    @Test
    fun `out of range settings are coerced instead of trusted`() {
        val session = SleepSession(toronto("2026-01-14T23:00"))
        val deadline = toronto("2026-01-15T07:00")
        val broken = Settings(cycleMinutes = 0, fallAsleepMinutes = -5, earlyWindowMinutes = 1000)

        val wake = computeWakeTime(deadline, session, broken, session.startedAt)

        // Coerced to 60 minute cycles, no delay, 90 minute window.
        assertEquals(WakeTime(deadline, Cycles(8, 0)), wake)
    }
}
