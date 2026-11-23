package dev.balaji.rem.core

import java.time.Duration
import java.time.Instant

/** Why a ring time was chosen. The UI renders this next to the ring time. */
sealed interface WakeExplanation {
    /** Rings at the end of sleep cycle number [cycles], [minutesBeforeDeadline] minutes early (0 means on the deadline). */
    data class Cycles(val cycles: Int, val minutesBeforeDeadline: Int) : WakeExplanation

    /**
     * Rings at the end of sleep cycle number [cycles], [minutesAfterDeadline] minutes after the
     * deadline. Only when the user accepted the [LateOption] for this alarm in the session.
     */
    data class LateCycles(val cycles: Int, val minutesAfterDeadline: Int) : WakeExplanation

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

/**
 * The first cycle boundary after a deadline, when it is at most [LATE_WINDOW] after it. Offered
 * when the alarm would otherwise ring at the deadline because no boundary fits before it
 * ([DeadlineReason.NO_CYCLE_IN_WINDOW] or [DeadlineReason.BEFORE_FIRST_CYCLE]).
 */
data class LateOption(val ringAt: Instant, val cycles: Int, val minutesAfterDeadline: Int)

/**
 * @property late the late option for this deadline, or null when there is none. Set whether
 *   or not the ring uses it, so the UI can offer it or show it as accepted.
 */
data class WakeTime(val ringAt: Instant, val explanation: WakeExplanation, val late: LateOption? = null)

/** How far after its deadline an accepted late option may ring. Nothing rings later than this. */
val LATE_WINDOW: Duration = Duration.ofMinutes(30)

/** Time limits of one ring. */
object RingLimits {
    /** Safety limit on one ring. After this the ring stops and counts as missed. */
    val MAX_RING: Duration = Duration.ofMinutes(30)

    /**
     * How long the tone and vibration stay off once the user opens the scanner or the
     * typed code. The time counts toward [MAX_RING].
     */
    val QUIET_PERIOD: Duration = Duration.ofSeconds(60)
}

/**
 * Picks when to ring for a [deadline] that is still ahead of [now].
 *
 * With a session that covers the deadline, cycle boundaries are sleep onset plus n cycles
 * for n >= 1. The latest boundary that is not after the deadline wins if it is inside the
 * early window and not already in the past. In every other case the alarm rings at the
 * deadline.
 *
 * When no boundary fits before the deadline (too soon for a cycle, or none in the early
 * window) the first boundary after it is the [WakeTime.late] option, if it is at most
 * [LATE_WINDOW] after the deadline. With [acceptLate] the ring moves there.
 *
 * The result is never before [now]. It is after the deadline only with [acceptLate], and
 * then by at most [LATE_WINDOW]. The late option does not depend on [now], so it can be
 * computed for a deadline that has passed while its late ring is still to come.
 */
fun computeWakeTime(
    deadline: Instant,
    session: SleepSession?,
    settings: RemSettings,
    now: Instant,
    acceptLate: Boolean = false,
): WakeTime {
    fun atDeadline(reason: DeadlineReason, late: LateOption? = null): WakeTime =
        if (acceptLate && late != null) {
            WakeTime(maxOf(late.ringAt, now), WakeExplanation.LateCycles(late.cycles, late.minutesAfterDeadline), late)
        } else {
            WakeTime(maxOf(deadline, now), WakeExplanation.AtDeadline(reason), late)
        }

    if (session == null) return atDeadline(DeadlineReason.NO_SESSION)
    if (!session.covers(deadline)) return atDeadline(DeadlineReason.OUTSIDE_SESSION)

    val safe = settings.coerced()
    val onset = session.sleepOnset(safe)
    val cycleLength = Duration.ofMinutes(safe.cycleMinutes.toLong())
    val cycles = Duration.between(onset, deadline).toMinutes() / safe.cycleMinutes
    if (cycles < 1) return atDeadline(DeadlineReason.BEFORE_FIRST_CYCLE, lateOption(onset, deadline, 1, cycleLength))

    val boundary = onset.plus(cycleLength.multipliedBy(cycles))
    val early = Duration.between(boundary, deadline)
    if (early > Duration.ofMinutes(safe.earlyWindowMinutes.toLong())) {
        return atDeadline(DeadlineReason.NO_CYCLE_IN_WINDOW, lateOption(onset, deadline, cycles + 1, cycleLength))
    }
    if (boundary < now) return atDeadline(DeadlineReason.CYCLE_ALREADY_PASSED)

    return WakeTime(boundary, WakeExplanation.Cycles(cycles.toInt(), early.toMinutes().toInt()))
}

/** The end of cycle number [cycles] as a late option for [deadline], or null when it is not after the deadline or too late. */
private fun lateOption(onset: Instant, deadline: Instant, cycles: Long, cycleLength: Duration): LateOption? {
    val boundary = onset.plus(cycleLength.multipliedBy(cycles))
    val late = Duration.between(deadline, boundary)
    if (late <= Duration.ZERO || late > LATE_WINDOW) return null
    return LateOption(boundary, cycles.toInt(), late.toMinutes().toInt())
}
