package dev.balaji.rem.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.ring.RingContract
import dev.balaji.rem.ring.RingWatchdog
import dev.balaji.rem.ui.AppLinks
import java.time.Instant

/**
 * The AlarmManager side of scheduling. Each alarm id owns at most one alarm clock entry,
 * because the PendingIntent identity comes from [AlarmBroadcast.fireIntent] and ignores
 * the extras. Setting a ring for an id replaces the entry that id had.
 *
 * The watchdog entry of a live ring is one more alarm clock entry with its own identity.
 */
internal class AlarmClocks(private val context: Context) : RingWatchdog {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** False only on API 31 and 32 after the user revoked "Alarms & reminders". */
    fun canSchedule(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Call only when [canSchedule] is true. AlarmManager throws otherwise. */
    fun set(ring: ScheduledRing) {
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            AlarmBroadcast.fireIntent(context, ring),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(ring.ringAt.toEpochMilli(), showIntent(ring.alarmId)), operation)
    }

    /** Does nothing when the id has no entry. */
    fun cancel(alarmId: Long) {
        val sameIdentity = AlarmBroadcast.fireIntent(context, ScheduledRing(alarmId, Instant.EPOCH, Instant.EPOCH))
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            sameIdentity,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    override fun arm(ring: ScheduledRing, at: Instant): Boolean {
        if (!canSchedule()) return false
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            AlarmBroadcast.resumeIntent(context, ring),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val show = PendingIntent.getActivity(
            context,
            0,
            RingContract.activityIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), operation)
        return true
    }

    override fun cancel() {
        val sameIdentity = AlarmBroadcast.resumeIntent(context, ScheduledRing(0, Instant.EPOCH, Instant.EPOCH))
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            sameIdentity,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

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
        return PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
