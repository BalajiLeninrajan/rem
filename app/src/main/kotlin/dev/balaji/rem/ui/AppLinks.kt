package dev.balaji.rem.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.balaji.rem.MainActivity
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.ui.navigation.AlarmEditorRoute
import dev.balaji.rem.ui.navigation.ReliabilityRoute

/**
 * Intents that open [MainActivity] on a given screen, for PendingIntents built outside the
 * `ui` package: the `setAlarmClock` show intent and the missed-alarm notification.
 */
object AppLinks {
    const val EXTRA_DESTINATION = "dev.balaji.rem.extra.DESTINATION"
    const val EXTRA_ALARM_ID = "dev.balaji.rem.extra.LINK_ALARM_ID"

    const val DESTINATION_EDIT_ALARM = "edit_alarm"
    const val DESTINATION_RELIABILITY = "reliability"

    fun home(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /**
     * Opens the editor of a stored alarm on top of home. Each kind of link has its own
     * data URI, because extras are not part of a PendingIntent's identity. Without it the
     * show intent of an alarm and its missed-alarm notification would be one PendingIntent
     * and the later of the two would rewrite where the other one leads.
     */
    fun editAlarm(context: Context, alarmId: Long): Intent =
        home(context)
            .setData(Uri.parse("rem://link/edit/$alarmId"))
            .putExtra(EXTRA_DESTINATION, DESTINATION_EDIT_ALARM)
            .putExtra(EXTRA_ALARM_ID, alarmId)

    fun reliability(context: Context): Intent =
        home(context).setData(Uri.parse("rem://link/reliability")).putExtra(EXTRA_DESTINATION, DESTINATION_RELIABILITY)

    /** The route [intent] asks for, to navigate to on top of home, or null for home itself. */
    fun routeFor(intent: Intent): Any? = when (intent.getStringExtra(EXTRA_DESTINATION)) {
        DESTINATION_EDIT_ALARM ->
            intent.getLongExtra(EXTRA_ALARM_ID, Alarm.NEW_ID).takeIf { it > Alarm.NEW_ID }?.let { AlarmEditorRoute(alarmId = it) }
        DESTINATION_RELIABILITY -> ReliabilityRoute
        else -> null
    }
}
