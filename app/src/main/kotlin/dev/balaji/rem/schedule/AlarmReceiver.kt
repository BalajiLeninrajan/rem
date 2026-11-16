package dev.balaji.rem.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives [AlarmBroadcast.ACTION_FIRE] from AlarmManager. Runs before the first unlock.
 *
 * Stub. The real receiver reads the ring with `intent.scheduledRingOrNull()`, calls
 * `RingController.startRinging` right away on the main thread, then under `goAsync()`
 * records the fire with `RingStateStore.markFired` (not for the test alarm) and calls
 * `AlarmScheduler.rescheduleAll`.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
