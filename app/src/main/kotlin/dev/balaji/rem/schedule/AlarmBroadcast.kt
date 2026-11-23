package dev.balaji.rem.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.balaji.rem.core.ScheduledRing
import java.time.Duration
import java.time.Instant

/** The broadcast AlarmManager sends to [AlarmReceiver] when an alarm is due. */
object AlarmBroadcast {
    const val ACTION_FIRE = "dev.balaji.rem.action.ALARM_FIRE"

    /** Sent by the watchdog entry that is kept ahead of a live ring. Asks to pick that ring up again. */
    const val ACTION_RESUME = "dev.balaji.rem.action.ALARM_RESUME"

    /** Sent by the entry of a snoozed ring when the snooze is over. Asks to ring that ring again. */
    const val ACTION_SNOOZE_END = "dev.balaji.rem.action.SNOOZE_END"

    const val EXTRA_ALARM_ID = "dev.balaji.rem.extra.ALARM_ID"

    /** Epoch milliseconds. */
    const val EXTRA_RING_AT = "dev.balaji.rem.extra.RING_AT"

    /** Epoch milliseconds. */
    const val EXTRA_DEADLINE = "dev.balaji.rem.extra.DEADLINE"

    /** Id of the "test alarm in 1 minute". No stored alarm has it. */
    const val TEST_ALARM_ID = -1L

    val TEST_ALARM_DELAY: Duration = Duration.ofMinutes(1)

    /**
     * The intent to wrap in `PendingIntent.getBroadcast`. Each alarm id gets its own data
     * URI, so PendingIntents of different alarms never collapse into one.
     */
    fun fireIntent(context: Context, ring: ScheduledRing): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(alarmUri(ring.alarmId))
            .putScheduledRing(ring)

    fun alarmUri(alarmId: Long): Uri = Uri.parse("rem://alarm/$alarmId")

    /**
     * The intent of the watchdog entry. Its own action and data URI keep its PendingIntent
     * apart from every alarm's, so a reschedule never replaces or cancels it.
     */
    fun resumeIntent(context: Context, ring: ScheduledRing): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_RESUME)
            .setData(Uri.parse("rem://resume"))
            .putScheduledRing(ring)

    /**
     * The intent of the entry of a snoozed ring. Its own action and a data URI per alarm
     * keep its PendingIntent apart from the alarm's own entry and from the watchdog, so a
     * reschedule never replaces or cancels it, and two snoozed alarms keep one entry each.
     */
    fun snoozeIntent(context: Context, ring: ScheduledRing): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_SNOOZE_END)
            .setData(Uri.parse("rem://snooze/${ring.alarmId}"))
            .putScheduledRing(ring)
}

/** Writes [ring] into the extras. Instants keep millisecond precision. */
fun Intent.putScheduledRing(ring: ScheduledRing): Intent =
    putExtra(AlarmBroadcast.EXTRA_ALARM_ID, ring.alarmId)
        .putExtra(AlarmBroadcast.EXTRA_RING_AT, ring.ringAt.toEpochMilli())
        .putExtra(AlarmBroadcast.EXTRA_DEADLINE, ring.deadline.toEpochMilli())

/** Reads what [putScheduledRing] wrote, or null when any of the three extras is missing. */
fun Intent.scheduledRingOrNull(): ScheduledRing? {
    val keys = listOf(AlarmBroadcast.EXTRA_ALARM_ID, AlarmBroadcast.EXTRA_RING_AT, AlarmBroadcast.EXTRA_DEADLINE)
    if (keys.any { !hasExtra(it) }) return null
    return ScheduledRing(
        alarmId = getLongExtra(AlarmBroadcast.EXTRA_ALARM_ID, 0L),
        ringAt = Instant.ofEpochMilli(getLongExtra(AlarmBroadcast.EXTRA_RING_AT, 0L)),
        deadline = Instant.ofEpochMilli(getLongExtra(AlarmBroadcast.EXTRA_DEADLINE, 0L)),
    )
}
