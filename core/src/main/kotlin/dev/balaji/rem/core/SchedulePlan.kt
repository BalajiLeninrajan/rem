package dev.balaji.rem.core

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The identity of one ring: which alarm, when it rings, and the deadline it serves. */
data class ScheduledRing(val alarmId: Long, val ringAt: Instant, val deadline: Instant)

/**
 * A [ScheduledRing] plus the reason for its ring time.
 *
 * [ringAt] is at most [LATE_WINDOW] after [deadline]. It is after the deadline only for an
 * accepted late option, or for an occurrence whose late ring was withdrawn after its
 * deadline had passed, which then rings at once.
 *
 * @property late the late option for this occurrence, accepted or not. Null when there is none.
 */
data class PlannedRing(
    val alarmId: Long,
    val ringAt: Instant,
    val deadline: Instant,
    val explanation: WakeExplanation,
    val late: LateOption? = null,
) {
    fun toScheduledRing() = ScheduledRing(alarmId, ringAt, deadline)
}

/** What should be scheduled right now: the next ring of every enabled alarm, earliest first. */
data class SchedulePlan(val rings: List<PlannedRing> = emptyList()) {
    val next: PlannedRing? get() = rings.firstOrNull()

    fun forAlarm(alarmId: Long): PlannedRing? = rings.firstOrNull { it.alarmId == alarmId }

    /**
     * The late options a session would offer for the rings of this plan. For the prompt when
     * "going to sleep" is pressed: pass the plan from before the session and the new session.
     * Rings the session does not cover are left out. Earliest late ring first.
     */
    fun lateOffers(session: SleepSession, settings: RemSettings): List<LateOffer> =
        rings
            .filter { session.covers(it.deadline) }
            .mapNotNull { ring ->
                computeWakeTime(ring.deadline, session, settings, session.startedAt).late?.let { LateOffer(ring.alarmId, ring.deadline, it) }
            }
            .sortedWith(compareBy({ it.option.ringAt }, { it.alarmId }))
}

/** "Alarm [alarmId] can ring at [option] instead of at [deadline]." */
data class LateOffer(val alarmId: Long, val deadline: Instant, val option: LateOption)

/**
 * Computes the plan. Pure, so the scheduler, the UI preview and tests all get the same answer.
 *
 * @param clock supplies now and the zone used to turn local deadlines into instants.
 * @param lastFired alarm id to the deadline of the latest occurrence that already fired.
 *   That occurrence is skipped. Without it, an alarm that rang early at 06:45 for a 07:00
 *   deadline would be planned again for 07:00.
 * @param previous the rings the last plan scheduled. A late ring among them whose deadline
 *   has passed stays owed until it fires or its time passes (see [planRing]).
 */
fun planSchedule(
    alarms: Collection<Alarm>,
    session: SleepSession?,
    settings: RemSettings,
    clock: Clock,
    lastFired: Map<Long, Instant> = emptyMap(),
    previous: Collection<ScheduledRing> = emptyList(),
): SchedulePlan {
    val now = clock.instant()
    val rings = alarms.filter { it.enabled }.map { alarm ->
        planRing(alarm, session, settings, now, clock.zone, lastFired[alarm.id], previous)
    }
    return SchedulePlan(rings.sortedWith(compareBy({ it.ringAt }, { it.alarmId })))
}

/**
 * The next ring of one [alarm], enabled or not. [planSchedule] calls this for each enabled alarm.
 *
 * Usually that is the first occurrence after [now] (after [fired] when that lies ahead).
 *
 * A late ring rings after its deadline, so between the deadline and the late ring the next
 * deadline is already the following one. The occurrence is kept there instead:
 * - The occurrence whose late option [session] accepted for this alarm is planned at its late
 *   ring while its deadline has passed, it has not fired and the late ring is still ahead.
 *   This needs nothing persisted beyond the session, so it survives a process death or a
 *   reboot. The consent names the deadline, so it does not carry over to an edited time.
 * - A late ring in [previous] whose deadline has passed, that has not fired and whose ring
 *   time is still ahead is owed. If it is no longer accepted or no longer late (the session
 *   ended or changed, the option was revoked, a setting moved the boundary), it falls back
 *   to the normal result. The deadline has passed, so that is to ring at once.
 *
 * Once the late ring time has passed without a fire, the occurrence is left to [checkMissed].
 * An occurrence the alarm no longer has, because it was edited, is not kept.
 */
fun planRing(
    alarm: Alarm,
    session: SleepSession?,
    settings: RemSettings,
    now: Instant,
    zone: ZoneId,
    fired: Instant? = null,
    previous: Collection<ScheduledRing> = emptyList(),
): PlannedRing {
    fun accepts(deadline: Instant) = session?.acceptsLate(alarm.id, deadline) == true
    fun ring(deadline: Instant, wake: WakeTime) = PlannedRing(alarm.id, wake.ringAt, deadline, wake.explanation, wake.late)

    fun open(deadline: Instant): Boolean =
        deadline <= now && (fired == null || fired < deadline) && alarm.hasOccurrenceAt(deadline, zone)

    val acceptedDeadline = session?.acceptedLate?.get(alarm.id)
    if (acceptedDeadline != null && open(acceptedDeadline)) {
        val wake = computeWakeTime(acceptedDeadline, session, settings, now, acceptLate = true)
        val late = wake.late
        if (wake.explanation is WakeExplanation.LateCycles && late != null && late.ringAt > now) return ring(acceptedDeadline, wake)
    }

    val owed = previous
        .filter { it.alarmId == alarm.id && it.ringAt > it.deadline && it.ringAt > now }
        .filter { !it.ringAt.isAfter(it.deadline.plus(LATE_WINDOW)) && open(it.deadline) }
        .maxByOrNull { it.deadline }
    if (owed != null) {
        // Never before now, and now is before the owed ring time, so still inside the late window.
        return ring(owed.deadline, computeWakeTime(owed.deadline, session, settings, now, accepts(owed.deadline)))
    }

    val after = if (fired != null && fired > now) fired else now
    val deadline = alarm.nextDeadline(after, zone)
    return ring(deadline, computeWakeTime(deadline, session, settings, now, accepts(deadline)))
}

/**
 * Result of comparing what was scheduled earlier with what actually fired.
 *
 * @property missed due and the alarm never fired. Report these.
 * @property pending due less than the grace period ago and no fire is recorded yet. The
 *   broadcast may still be in flight, so keep these in the persisted list and check again
 *   on the next reschedule.
 */
data class MissedCheck(val missed: List<ScheduledRing>, val pending: List<ScheduledRing>)

val MISSED_GRACE: Duration = Duration.ofMinutes(1)

/**
 * When a scheduled ring is overdue without a fire: at its deadline, or at its ring time for
 * a late ring. An early ring that was lost still has its deadline to come, the reschedule
 * plans that. A late ring is the last chance for its occurrence.
 */
val ScheduledRing.dueBy: Instant get() = maxOf(ringAt, deadline)

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
        ring.dueBy <= now && (fired == null || fired < ring.deadline)
    }
    val (missed, pending) = overdue.partition { it.dueBy.plus(grace) <= now }
    return MissedCheck(missed, pending)
}
