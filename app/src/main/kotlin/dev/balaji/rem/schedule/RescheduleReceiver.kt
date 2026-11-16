package dev.balaji.rem.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.balaji.rem.di.appContainer

/**
 * Receives every broadcast after which AlarmManager may no longer hold the right alarms:
 * boot (locked and unlocked), package replaced, clock, timezone and timezone offset
 * changes, and the exact alarm permission being granted. Runs before the first unlock.
 *
 * It only reschedules. It never starts a ring: audio from a service started on the boot
 * path is suppressed, so an alarm that was due while the device was off is reported by the
 * reschedule as missed instead.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The receiver is exported, so anything can send it an explicit intent.
        if (intent.action !in ACTIONS) return
        val container = context.appContainer
        launchWork(container.applicationScope) { container.alarmScheduler.rescheduleAll() }
    }

    companion object {
        /** The same list as the intent filter in the manifest. */
        val ACTIONS: Set<String> = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // Intent.ACTION_TIMEZONE_OFFSET_CHANGED, new in API 37. Sent for a daylight saving shift inside one zone.
            "android.intent.action.TIMEZONE_OFFSET_CHANGED",
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
