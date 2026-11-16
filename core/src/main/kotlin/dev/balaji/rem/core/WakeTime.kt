package dev.balaji.rem.core

import java.time.Duration
import java.time.Instant

/** Why a ring time was chosen. The UI renders this next to the ring time. */
sealed interface WakeExplanation {
    /** Rings at the end of sleep cycle number [cycles], [minutesBeforeDeadline] minutes early (0 means on the deadline). */
    data class Cycles(val cycles: Int, val minutesBeforeDeadline: Int) : WakeExplanation

    /** Rings at the deadline itself. */
    data class AtDeadline(val reason: DeadlineReason) : WakeExplanation
}

enum class DeadlineReason {
    /** No sleep session is active. */
    NO_SESSION,

    /** The deadline is before the session started or more than 16 hours after it. */
    OUTSIDE_SESSION,

    /** The deadline comes before the first full cycle ends. */
    BEFORE_FIRST_CYCLE,

    /** The last cycle boundary before the deadline is earlier than the early window allows. */
    NO_CYCLE_IN_WINDOW,

    /** A cycle boundary fits the window but that moment has already passed. */
    CYCLE_ALREADY_PASSED,
}

data class WakeTime(val ringAt: Instant, val explanation: WakeExplanation)

/** Safety limit on one ring. After this the ring stops and counts as missed. */
object RingLimits {
    val MAX_RING: Duration = Duration.ofMinutes(30)
}

/**
 * Picks when to ring for a [deadline] that is still ahead of [now].
 *
 * With a session that covers the deadline, cycle boundaries are sleep onset plus n cycles
 * for n >= 1. The latest boundary that is not after the deadline wins if it is inside the
 * early window and not already in the past. In every other case the alarm rings at the
 * deadline. The result is never after the deadline and never before [now].
 */
fun computeWakeTime(deadline: Instant, session: SleepSession?, settings: Settings, now: Instant): WakeTime {
    fun atDeadline(reason: DeadlineReason) = WakeTime(maxOf(deadline, now), WakeExplanation.AtDeadline(reason))

    if (session == null) return atDeadline(DeadlineReason.NO_SESSION)
    if (!session.covers(deadline)) return atDeadline(DeadlineReason.OUTSIDE_SESSION)

    val safe = settings.coerced()
    val onset = session.sleepOnset(safe)
    val cycles = Duration.between(onset, deadline).toMinutes() / safe.cycleMinutes
    if (cycles < 1) return atDeadline(DeadlineReason.BEFORE_FIRST_CYCLE)

    val boundary = onset.plus(Duration.ofMinutes(cycles * safe.cycleMinutes))
    val early = Duration.between(boundary, deadline)
    if (early > Duration.ofMinutes(safe.earlyWindowMinutes.toLong())) {
        return atDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW)
    }
    if (boundary < now) return atDeadline(DeadlineReason.CYCLE_ALREADY_PASSED)

    return WakeTime(boundary, WakeExplanation.Cycles(cycles.toInt(), early.toMinutes().toInt()))
}
