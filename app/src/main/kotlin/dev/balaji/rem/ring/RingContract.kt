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

    const val NOTIFICATION_ID_RINGING = 1

    /** Used with the alarm id as the notification tag. */
    const val NOTIFICATION_ID_MISSED = 2

    const val ACTION_RING = "dev.balaji.rem.action.RING"

    /**
     * Starts [RingingService] for [ring] when passed to `startForegroundService`. The
     * extras are the ones from `AlarmBroadcast`, readable with `scheduledRingOrNull()`.
     */
    fun serviceIntent(context: Context, ring: ScheduledRing): Intent =
        Intent(context, RingingService::class.java).setAction(ACTION_RING).putScheduledRing(ring)

    /**
     * Opens [RingingActivity]. Used for the full-screen intent, the notification tap and
     * the "Scan to dismiss" action. The activity reads what is ringing from
     * [RingController.state], so the intent carries no extras.
     */
    fun activityIntent(context: Context): Intent =
        Intent(context, RingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
}
