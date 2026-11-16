package dev.balaji.rem.schedule

import android.content.Context
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.checkMissed
import dev.balaji.rem.core.planSchedule
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.MissedAlarm
import dev.balaji.rem.ring.MissedReason
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The real scheduler. Every reschedule rebuilds the AlarmManager entries from the stored
 * alarms, the sleep session and the settings, so running it twice changes nothing.
 */
class AlarmManagerScheduler(
    context: Context,
    private val alarms: AlarmRepository,
    private val sessions: SleepSessionRepository,
    private val settings: SettingsRepository,
    private val ringState: RingStateStore,
    private val notifier: AlarmNotifier,
    private val clock: Clock,
    scope: CoroutineScope,
) : AlarmScheduler {
    private val alarmClocks = AlarmClocks(context)
    private val mutex = Mutex()
    private val currentPlan = MutableStateFlow(SchedulePlan())

    override val plan: StateFlow<SchedulePlan> = currentPlan.asStateFlow()

    init {
        // Reschedule triggers: the scheduler watches the repositories, the repositories do
        // not know the scheduler exists. The dependency points one way, so there is no
        // cycle, and a new writer cannot forget to reschedule. The scheduler's own writes
        // (ending a stale session, disabling a missed one-shot alarm) come back through
        // these flows and cause one more run, which finds nothing left to change.
        // The ringing record is watched because a one-shot alarm leaves the plan while it rings.
        scope.launch {
            combine(alarms.alarms, sessions.session, settings.settings, ringState.ringing) { _, _, _, _ -> }
                .conflate()
                .collect { rescheduleAll() }
        }
    }

    override suspend fun rescheduleAll() {
        mutex.withLock {
            awaitRingInFlight()
            val now = clock.instant()
            val session = activeSession(now)
            val previous = ringState.scheduled()
            val check = checkMissed(previous, ringState.lastFired(), now)
            reportMissed(check.missed)

            val stored = alarms.all()
            val storedIds = stored.mapTo(mutableSetOf()) { it.id }
            ringState.retainFired(storedIds)
            // A one-shot alarm that is ringing has no next occurrence. The ring disables it when it ends.
            val ringingId = ringState.currentRinging()?.alarmId
            val candidates = stored.filterNot { it.isOneShot && it.id == ringingId }
            val plan = planSchedule(candidates, session, settings.current(), clock, firedOccurrences(stored))

            val plannedIds = plan.rings.mapTo(mutableSetOf()) { it.alarmId }
            (storedIds + previous.map { it.alarmId } - plannedIds).forEach(alarmClocks::cancel)
            // Without the exact alarm permission nothing can be set. The rings are still
            // persisted, so each one that passes is reported as missed.
            if (alarmClocks.canSchedule()) plan.rings.forEach { alarmClocks.set(it.toScheduledRing()) }

            ringState.setScheduled(plan.rings.map { it.toScheduledRing() } + check.pending)
            currentPlan.value = plan
        }
    }

    /** Sets nothing when exact alarms are not allowed. The reliability screen shows that check. */
    override suspend fun scheduleTestAlarm(): Instant {
        val ringAt = clock.instant().plus(AlarmBroadcast.TEST_ALARM_DELAY).truncatedTo(ChronoUnit.MILLIS)
        if (alarmClocks.canSchedule()) alarmClocks.set(ScheduledRing(AlarmBroadcast.TEST_ALARM_ID, ringAt, ringAt))
        return ringAt
    }

    /**
     * Setting an alarm clock entry again cancels the entry it replaces. If a ring became
     * due a moment ago and its broadcast has not reached [AlarmReceiver] yet, replacing its
     * entry now could cancel that ring. So wait until it has had [IN_FLIGHT_WINDOW] to
     * arrive. The receiver records the fire without taking the lock, and the plan made
     * after the wait sees it.
     */
    private suspend fun awaitRingInFlight() {
        val now = clock.instant()
        val fired = ringState.lastFired()
        val settled = ringState.scheduled()
            .filter { ring -> ring.ringAt <= now && fired[ring.alarmId].let { it == null || it < ring.deadline } }
            .maxOfOrNull { it.ringAt.plus(IN_FLIGHT_WINDOW) } ?: return
        delay(Duration.between(now, settled).toMillis())
    }

    /**
     * The fired deadlines that are still occurrences of their alarm. A fired deadline that
     * lies ahead makes the plan skip to the occurrence after it. Once the alarm has been
     * edited to another time or other days, the record belongs to an occurrence that no
     * longer exists, and it must not hide the new one.
     */
    private suspend fun firedOccurrences(stored: List<Alarm>): Map<Long, Instant> {
        val byId = stored.associateBy { it.id }
        return ringState.lastFired().filter { (alarmId, deadline) ->
            byId[alarmId]?.nextDeadline(deadline.minusMillis(1), clock.zone) == deadline
        }
    }

    private suspend fun activeSession(now: Instant): SleepSession? {
        val session = sessions.current() ?: return null
        if (session.isActive(now)) return session
        sessions.end()
        return null
    }

    /**
     * Reports rings whose deadline passed without a fire, for example because the device
     * was off. A one-shot alarm among them is disabled, as its only occurrence is over.
     * An alarm that was deleted or switched off in the meantime is not reported.
     */
    private suspend fun reportMissed(missed: List<ScheduledRing>) {
        for (ring in missed) {
            val alarm = alarms.get(ring.alarmId)?.takeIf { it.enabled } ?: continue
            notifier.notifyMissed(MissedAlarm(alarm.id, alarm.label, ring.deadline, MissedReason.NOT_FIRED))
            if (alarm.isOneShot) alarms.setEnabled(alarm.id, false)
        }
    }

    private companion object {
        val IN_FLIGHT_WINDOW: Duration = Duration.ofSeconds(2)
    }
}
