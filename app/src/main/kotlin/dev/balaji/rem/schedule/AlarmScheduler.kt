package dev.balaji.rem.schedule

import dev.balaji.rem.core.SchedulePlan
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps AlarmManager in line with the stored alarms. AlarmManager is treated as a cache:
 * the stored alarms, the sleep session and the settings are the truth, and
 * [rescheduleAll] rebuilds everything from them.
 */
interface AlarmScheduler {
    /**
     * The plan produced by the latest reschedule: the next ring of every enabled alarm
     * with the reason for its time. The UI reads ring times from here.
     */
    val plan: StateFlow<SchedulePlan>

    /**
     * Idempotent and safe to call from anywhere, concurrently. In order it:
     * 1. ends a sleep session that is no longer active (older than 16 hours),
     * 2. reports rings that should have fired and did not, through `AlarmNotifier.notifyMissed`
     *    (see `checkMissed` in :core). A ring is reported by the run a few seconds after
     *    the one that first saw it overdue, which this class starts itself,
     * 3. computes the plan with `planSchedule`. A late ring whose deadline has passed stays
     *    planned until it fires or its time passes,
     * 4. sets one `setAlarmClock` alarm per planned ring and cancels the ones no longer planned,
     * 5. persists the scheduled rings and publishes [plan].
     *
     * The implementation also observes the alarm, session and settings repositories and
     * reschedules by itself when they change. Code that writes to those repositories does
     * not need to call this. Receivers and app start call it directly.
     */
    suspend fun rescheduleAll()

    /**
     * Schedules a real `setAlarmClock` alarm one minute from now with
     * [AlarmBroadcast.TEST_ALARM_ID] and returns its ring time. It goes through the same
     * receiver, service and dismissal as a stored alarm. It is not part of [plan] and
     * [rescheduleAll] leaves it alone.
     *
     * @return null when exact alarms are not allowed, so nothing was set.
     */
    suspend fun scheduleTestAlarm(): Instant?
}
