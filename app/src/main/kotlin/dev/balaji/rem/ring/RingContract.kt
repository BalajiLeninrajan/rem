package dev.balaji.rem.ring

import android.content.Context
import android.content.Intent
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.schedule.putScheduledRing

/** Names and intents of the ringing components. */
object RingContract {
    /** High importance, no channel sound. The service plays the audio itself. */
    const val CHANNEL_RINGING = "ringing"

    const val CHANNEL_MISSED = "missed"

    /** Low importance. The "snoozed until" notification only has to be there. */
    const val CHANNEL_SNOOZED = "snoozed"

    const val NOTIFICATION_ID_RINGING = 1

    /** Used with the alarm id as the notification tag. */
    const val NOTIFICATION_ID_MISSED = 2

    /** Used with the alarm id as the notification tag. */
    const val NOTIFICATION_ID_SNOOZED = 3

    const val ACTION_RING = "dev.balaji.rem.action.RING"

    /** The Snooze action of the ringing notification, for [RingActionReceiver]. */
    const val ACTION_SNOOZE = "dev.balaji.rem.action.SNOOZE"

    /** The Dismiss action of the ringing notification of an alarm without barcodes, for [RingActionReceiver]. */
    const val ACTION_DISMISS = "dev.balaji.rem.action.DISMISS"

    /** The alarm the notification action was shown for. */
    const val EXTRA_ALARM_ID = "dev.balaji.rem.extra.RINGING_ALARM_ID"

    /**
     * Starts [RingingService] for [ring] when passed to `startForegroundService`. The
     * extras are the ones from `AlarmBroadcast`, readable with `scheduledRingOrNull()`.
     */
    fun serviceIntent(context: Context, ring: ScheduledRing): Intent =
        Intent(context, RingingService::class.java).setAction(ACTION_RING).putScheduledRing(ring)

    /**
     * Opens [RingingActivity]. Used for the full-screen intent, the notification tap, the
     * "Scan to dismiss" action and the tap on a "snoozed until" notification. The activity
     * reads what is ringing or snoozed from [RingController.state], so the intent carries
     * no extras.
     */
    fun activityIntent(context: Context): Intent =
        Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    /** [ACTION_SNOOZE] or [ACTION_DISMISS] for the ring of [alarmId], to wrap in `PendingIntent.getBroadcast`. */
    fun actionIntent(context: Context, action: String, alarmId: Long): Intent =
        Intent(context, RingActionReceiver::class.java).setAction(action).putExtra(EXTRA_ALARM_ID, alarmId)
}
