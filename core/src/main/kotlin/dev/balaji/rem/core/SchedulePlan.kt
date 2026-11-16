package dev.balaji.rem.core

import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The identity of one ring: which alarm, when it rings, and the deadline it serves. */
data class ScheduledRing(val alarmId: Long, val ringAt: Instant, val deadline: Instant)

/** A [ScheduledRing] plus the reason for its ring time. */
data class PlannedRing(
    val alarmId: Long,
    val ringAt: Instant,
    val deadline: Instant,
    val explanation: WakeExplanation,
) {
    fun toScheduledRing() = ScheduledRing(alarmId, ringAt, deadline)
}

/** What should be scheduled right now: the next ring of every enabled alarm, earliest first. */
data class SchedulePlan(val rings: List<PlannedRing> = emptyList()) {
    val next: PlannedRing? get() = rings.firstOrNull()

    fun forAlarm(alarmId: Long): PlannedRing? = rings.firstOrNull { it.alarmId == alarmId }
}

/**
 * Computes the plan. Pure, so the scheduler, the UI preview and tests all get the same answer.
 *
 * @param clock supplies now and the zone used to turn local deadlines into instants.
 * @param lastFired alarm id to the deadline of the latest occurrence that already fired.
 *   That occurrence is skipped. Without it, an alarm that rang early at 06:45 for a 07:00
 *   deadline would be planned again for 07:00.
 */
fun planSchedule(
    alarms: Collection<Alarm>,
    session: SleepSession?,
    settings: Settings,
    clock: Clock,
    lastFired: Map<Long, Instant> = emptyMap(),
): SchedulePlan {
    val now = clock.instant()
    val rings = alarms.filter { it.enabled }.map { alarm ->
        val fired = lastFired[alarm.id]
        val after = if (fired != null && fired > now) fired else now
        val deadline = alarm.nextDeadline(after, clock.zone)
        val wake = computeWakeTime(deadline, session, settings, now)
        PlannedRing(alarm.id, wake.ringAt, deadline, wake.explanation)
    }
    return SchedulePlan(rings.sortedWith(compareBy({ it.ringAt }, { it.alarmId })))
}

/**
 * Result of comparing what was scheduled earlier with what actually fired.
 *
 * @property missed deadline passed and the alarm never fired. Report these.
 * @property pending deadline passed less than the grace period ago and no fire is recorded
 *   yet. The broadcast may still be in flight, so keep these in the persisted list and
 *   check again on the next reschedule.
 */
data class MissedCheck(val missed: List<ScheduledRing>, val pending: List<ScheduledRing>)

val MISSED_GRACE: Duration = Duration.ofMinutes(1)

/**
 * @param previous the rings persisted by the last reschedule.
 * @param lastFired alarm id to the deadline of the latest occurrence that fired.
 */
fun checkMissed(
    previous: Collection<ScheduledRing>,
    lastFired: Map<Long, Instant>,
    now: Instant,
    grace: Duration = MISSED_GRACE,
): MissedCheck {
    val overdue = previous.filter { ring ->
        val fired = lastFired[ring.alarmId]
        ring.deadline <= now && (fired == null || fired < ring.deadline)
    }
    val (missed, pending) = overdue.partition { it.deadline.plus(grace) <= now }
    return MissedCheck(missed, pending)
}
