package dev.balaji.rem.schedule

import android.content.Context
import android.util.Log
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.core.checkMissed
import dev.balaji.rem.core.planSchedule
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.MissedAlarm
import dev.balaji.rem.ring.MissedReason
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
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
    private val scope: CoroutineScope,
) : AlarmScheduler {
    private val alarmClocks = AlarmClocks(context)
    private val mutex = Mutex()

    /** Rings that looked missed and wait for a second look, and the ones that have had it. */
    private val awaitingConfirmation: MutableSet<ScheduledRing> = ConcurrentHashMap.newKeySet()
    private val confirmedMissed: MutableSet<ScheduledRing> = ConcurrentHashMap.newKeySet()
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
                .collect {
                    // A failed run, for example a storage error, must not end the collection.
                    // The next change or the next process start tries again.
                    try {
                        rescheduleAll()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Reschedule after a change failed", e)
                    }
                }
        }
    }

    override suspend fun rescheduleAll() {
        mutex.withLock {
            // Arming comes first. Every bookkeeping step around it is best effort, so a
            // write that fails, for example on a full disk, cannot keep the alarms from being set.
            bookkeeping("in-flight wait") { awaitRingInFlight() }
            val now = clock.instant()
            val session = activeSession(now)
            val ringing = readOr(null) { ringState.currentRinging() }
            val fired = firedRecords(now)
            val previous = readOr(emptyList()) { ringState.scheduled() }
            val check = checkMissed(previous, fired, now)
            val (missed, unconfirmed) = check.missed.partition { it in confirmedMissed }
            reportMissed(missed, ringing)
            // Reported or no longer overdue. Only a ring confirmed while this run was under way stays.
            confirmedMissed.retainAll(unconfirmed.toSet())
            confirmLater(unconfirmed)

            val stored = alarms.all()
            val storedIds = stored.mapTo(mutableSetOf()) { it.id }
            // A switched-off alarm needs no fired record. Without one, switching it back on plans its next real occurrence.
            val enabledIds = stored.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
            bookkeeping("fired cleanup") { ringState.retainFired(enabledIds) }
            // A one-shot alarm that is ringing has no next occurrence. The ring disables it when it ends.
            // Once it has been edited to another time it is a new occurrence and is planned like any other.
            val candidates = stored.filterNot { alarm ->
                ringing != null && alarm.isOneShot && alarm.isOccurrence(ringing.alarmId, ringing.deadline)
            }
            val current = readOr(RemSettings.DEFAULT) { settings.current() }
            val firedOccurrences = fired.filter { (id, deadline) -> stored.any { it.isOccurrence(id, deadline) } }
            // The previous rings keep a late ring planned between its deadline and its ring time.
            val plan = planSchedule(candidates, session, current, clock, firedOccurrences, previous)

            val plannedIds = plan.rings.mapTo(mutableSetOf()) { it.alarmId }
            (storedIds + previous.map { it.alarmId } - plannedIds).forEach { alarmClocks.cancel(it) }
            // Without the exact alarm permission nothing can be set. The rings are still
            // persisted, so each one that passes is reported as missed.
            if (alarmClocks.canSchedule()) plan.rings.forEach { alarmClocks.set(it.toScheduledRing()) }

            bookkeeping("scheduled rings") {
                ringState.setScheduled(plan.rings.map { it.toScheduledRing() } + check.pending + unconfirmed)
            }
            currentPlan.value = plan
        }
    }

    /** Null, and nothing is set, when exact alarms are not allowed. The reliability screen shows that check. */
    override suspend fun scheduleTestAlarm(): Instant? {
        if (!alarmClocks.canSchedule()) return null
        val ringAt = clock.instant().plus(AlarmBroadcast.TEST_ALARM_DELAY).truncatedTo(ChronoUnit.MILLIS)
        alarmClocks.set(ScheduledRing(AlarmBroadcast.TEST_ALARM_ID, ringAt, ringAt))
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
     * The stored fired deadlines. The receiver writes one when a ring starts, and a ring
     * starts at most the largest early window before its deadline. A late ring starts after
     * its deadline, so its record is behind the clock. A record further ahead than the
     * largest early window was written under a clock that has since moved back. It would make the
     * plan skip real occurrences, so it is deleted.
     */
    private suspend fun firedRecords(now: Instant): Map<Long, Instant> {
        val horizon = now.plus(MAX_FIRED_AHEAD)
        val records = readOr(emptyMap()) { ringState.lastFired() }
        val (stale, sound) = records.entries.partition { it.value > horizon }
        stale.forEach { bookkeeping("stale fired record") { ringState.removeFired(it.key) } }
        return sound.associate { it.key to it.value }
    }

    /**
     * Whether [deadline] is still an occurrence of this alarm with id [alarmId]. A fired
     * deadline that lies ahead makes the plan skip to the occurrence after it. Once the
     * alarm has been edited to another time or other days, the record belongs to an
     * occurrence that no longer exists, and it must not hide the new one.
     */
    private fun Alarm.isOccurrence(alarmId: Long, deadline: Instant): Boolean =
        id == alarmId && hasOccurrenceAt(deadline, clock.zone)

    /** A session that cannot be read or ended counts as none, so the alarms ring at their deadlines. */
    private suspend fun activeSession(now: Instant): SleepSession? {
        val session = readOr(null) { sessions.current() } ?: return null
        if (session.isActive(now)) return session
        bookkeeping("stale session") { sessions.end() }
        return null
    }

    /**
     * Reports rings whose deadline passed without a fire, for example because the device
     * was off. A one-shot alarm among them is disabled, as its only occurrence is over,
     * unless it has been edited to another time since. An alarm that was deleted or
     * switched off in the meantime is not reported, and neither is the one that is ringing.
     */
    private suspend fun reportMissed(missed: List<ScheduledRing>, ringing: RingingRecord?) {
        for (ring in missed) {
            if (ring.alarmId == ringing?.alarmId) continue
            bookkeeping("missed report") {
                val alarm = alarms.get(ring.alarmId)?.takeIf { it.enabled } ?: return@bookkeeping
                notifier.notifyMissed(MissedAlarm(alarm.id, alarm.label, ring.deadline, MissedReason.NOT_FIRED))
                if (alarm.isOneShot && alarm.isOccurrence(ring.alarmId, ring.deadline)) alarms.setEnabled(alarm.id, false)
            }
        }
    }

    /**
     * A ring that looks missed is only reported by a later run. Right after a process
     * start or a clock jump the alarm broadcast may still be waiting behind this very
     * run, and reporting now would post a false notification and could switch off a
     * one-shot alarm that is about to ring. The rings stay in the persisted list meanwhile.
     */
    private fun confirmLater(rings: List<ScheduledRing>) {
        val fresh = rings.filter { awaitingConfirmation.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            delay(MISSED_CONFIRM_DELAY.toMillis())
            confirmedMissed += fresh
            awaitingConfirmation -= fresh.toSet()
            try {
                rescheduleAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reschedule for the missed check failed", e)
            }
        }
    }

    private suspend fun <T> readOr(default: T, read: suspend () -> T): T =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Read failed, going on with a default", e)
            default
        }

    private suspend fun bookkeeping(what: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Bookkeeping failed: $what", e)
        }
    }

    private companion object {
        const val TAG = "AlarmManagerScheduler"
        val IN_FLIGHT_WINDOW: Duration = Duration.ofSeconds(2)
        val MISSED_CONFIRM_DELAY: Duration = Duration.ofSeconds(5)
        val MAX_FIRED_AHEAD: Duration = Duration.ofMinutes(RemSettings.EARLY_WINDOW_MINUTES.last.toLong())
    }
}
