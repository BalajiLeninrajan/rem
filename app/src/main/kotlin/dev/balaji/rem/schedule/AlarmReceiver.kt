package dev.balaji.rem.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.di.AppContainer
import dev.balaji.rem.di.appContainer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Receives [AlarmBroadcast.ACTION_FIRE] and [AlarmBroadcast.ACTION_RESUME] from
 * AlarmManager. Runs before the first unlock.
 *
 * The ring starts inside `onReceive`, while AlarmManager still holds its wake lock and the
 * alarm clock exemption for starting a foreground service applies. The bookkeeping follows
 * in the background.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ring = intent.scheduledRingOrNull() ?: return
        val container = context.appContainer
        when (intent.action) {
            AlarmBroadcast.ACTION_FIRE -> fire(container, ring)
            AlarmBroadcast.ACTION_RESUME -> container.ringController.resumeRinging(ring)
        }
    }

    private fun fire(container: AppContainer, ring: ScheduledRing) {
        val ringing = container.ringController.startRinging(ring)

        launchWork(container.applicationScope) {
            // The fired record goes in before the reschedule. Without it an alarm that rang
            // early would be planned again for its deadline. A ring that did not start is
            // only recorded once its deadline has passed, so a lost early ring is planned
            // again at the deadline. A late ring comes after its deadline, so a lost one is
            // recorded and the ring controller reports it as missed.
            val fired = ringing || ring.deadline <= container.clock.instant()
            if (fired && ring.alarmId != AlarmBroadcast.TEST_ALARM_ID) {
                try {
                    container.ringStateStore.markFired(ring.alarmId, ring.deadline)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The reschedule matters more than the record. At worst the deadline rings once more.
                    Log.e(TAG, "Fired record was not written", e)
                }
            }
            container.alarmScheduler.rescheduleAll()
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
