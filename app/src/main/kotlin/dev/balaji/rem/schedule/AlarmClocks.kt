package dev.balaji.rem.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.ring.RingContract
import dev.balaji.rem.ring.RingWatchdog
import dev.balaji.rem.ring.SnoozeAlarms
import dev.balaji.rem.ui.AppLinks
import java.time.Instant

/**
 * The AlarmManager side of scheduling. Each alarm id owns at most one alarm clock entry,
 * because the PendingIntent identity comes from [AlarmBroadcast.fireIntent] and ignores
 * the extras. Setting a ring for an id replaces the entry that id had.
 *
 * The watchdog entry of a live ring is one more alarm clock entry with its own identity,
 * and so is the entry of each snoozed ring.
 */
internal class AlarmClocks(private val context: Context) : RingWatchdog, SnoozeAlarms {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** False only on API 31 and 32 after the user revoked "Alarms & reminders". */
    fun canSchedule(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Call only when [canSchedule] is true. AlarmManager throws otherwise. */
    fun set(ring: ScheduledRing) = setEntry(AlarmBroadcast.fireIntent(context, ring), ring.ringAt, showIntent(ring.alarmId))

    /** Does nothing when the id has no entry. */
    fun cancel(alarmId: Long) = cancelEntry(AlarmBroadcast.fireIntent(context, identity(alarmId)))

    override fun arm(ring: ScheduledRing, at: Instant): Boolean {
        if (!canSchedule()) return false
        setEntry(AlarmBroadcast.resumeIntent(context, ring), at, showRinging())
        return true
    }

    override fun cancel() = cancelEntry(AlarmBroadcast.resumeIntent(context, identity(0)))

    override fun setSnooze(ring: ScheduledRing, at: Instant): Boolean {
        if (!canSchedule()) return false
        setEntry(AlarmBroadcast.snoozeIntent(context, ring), at, showRinging())
        return true
    }

    override fun cancelSnooze(alarmId: Long) = cancelEntry(AlarmBroadcast.snoozeIntent(context, identity(alarmId)))

    private fun setEntry(broadcast: Intent, at: Instant, show: PendingIntent) {
        val operation = PendingIntent.getBroadcast(context, 0, broadcast, IMMUTABLE_UPDATE)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
    }

    private fun cancelEntry(sameIdentity: Intent) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            sameIdentity,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    /** A ring whose intent has the same PendingIntent identity as every ring of [alarmId]. */
    private fun identity(alarmId: Long) = ScheduledRing(alarmId, Instant.EPOCH, Instant.EPOCH)

    /** The ringing screen, which shows a snoozed ring too. */
    private fun showRinging(): PendingIntent =
        PendingIntent.getActivity(context, 0, RingContract.activityIntent(context), IMMUTABLE_UPDATE)

    /**
     * What the system opens when the user taps the next-alarm entry. The alarm id is the
     * request code because these intents differ only in their extras, and extras alone
     * would make every alarm share one PendingIntent.
     */
    private fun showIntent(alarmId: Long): PendingIntent {
        val intent = if (alarmId == AlarmBroadcast.TEST_ALARM_ID) {
            AppLinks.reliability(context)
        } else {
            AppLinks.editAlarm(context, alarmId)
        }
        return PendingIntent.getActivity(context, alarmId.toInt(), intent, IMMUTABLE_UPDATE)
    }

    private companion object {
        const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
