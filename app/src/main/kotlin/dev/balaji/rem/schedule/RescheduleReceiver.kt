package dev.balaji.rem.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives boot (locked and unlocked), package replaced, time, timezone, timezone offset
 * and exact alarm permission broadcasts. Runs before the first unlock.
 *
 * Stub. The real receiver calls `AlarmScheduler.rescheduleAll` under `goAsync()`. It never
 * starts a ring. Alarms missed while the device was off surface as missed-alarm
 * notifications from the reschedule.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
