package dev.balaji.rem.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.balaji.rem.di.appContainer
import dev.balaji.rem.schedule.launchWork

/**
 * The Snooze and Dismiss actions of the ringing notification. They act on the ring at
 * once, without opening the ringing screen. Runs before the first unlock.
 *
 * An action acts only on the alarm it was shown for, so a tap on a notification that has
 * not caught up with a newer ring does nothing. Dismiss works only when the controller
 * allows a plain dismissal, which it does not for an alarm with barcodes.
 */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.hasExtra(RingContract.EXTRA_ALARM_ID)) return
        val alarmId = intent.getLongExtra(RingContract.EXTRA_ALARM_ID, 0L)
        val container = context.appContainer
        val controller = container.ringController
        val ringing = controller.state.value as? RingingState.Ringing
        if (ringing?.alarmId != alarmId) return
        when (intent.action) {
            RingContract.ACTION_SNOOZE -> launchWork(container.applicationScope) { controller.snooze() }
            RingContract.ACTION_DISMISS -> launchWork(container.applicationScope) { controller.dismissWithoutBarcode() }
        }
    }
}
