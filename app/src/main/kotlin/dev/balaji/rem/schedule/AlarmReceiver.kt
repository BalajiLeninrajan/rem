package dev.balaji.rem.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.balaji.rem.di.appContainer

/**
 * Receives [AlarmBroadcast.ACTION_FIRE] from AlarmManager. Runs before the first unlock.
 *
 * The ring starts inside `onReceive`, while AlarmManager still holds its wake lock and the
 * alarm clock exemption for starting a foreground service applies. The bookkeeping follows
 * in the background.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmBroadcast.ACTION_FIRE) return
        val ring = intent.scheduledRingOrNull() ?: return
        val container = context.appContainer

        container.ringController.startRinging(ring)

        launchWork(container.applicationScope) {
            // The fired record goes in before the reschedule. Without it an alarm that rang
            // early would be planned again for its deadline.
            if (ring.alarmId != AlarmBroadcast.TEST_ALARM_ID) {
                container.ringStateStore.markFired(ring.alarmId, ring.deadline)
            }
            container.alarmScheduler.rescheduleAll()
        }
    }
}
