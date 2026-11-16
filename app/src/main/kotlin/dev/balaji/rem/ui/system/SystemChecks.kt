package dev.balaji.rem.ui.system

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class DoNotDisturb {
    ALARMS_ALLOWED,
    ALARMS_MUTED,

    /** Do Not Disturb is in priority mode and the app may not read which categories it lets through. */
    UNKNOWN,
}

/**
 * What the system says about the things an alarm depends on. The defaults are the healthy
 * values, so nothing warns before the first read.
 */
data class SystemChecks(
    val notificationsEnabled: Boolean = true,
    val fullScreenIntentAllowed: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val doNotDisturb: DoNotDisturb = DoNotDisturb.ALARMS_ALLOWED,
    val backgroundRestricted: Boolean = false,
    val cameraGranted: Boolean = true,
)

/** Reads every check from the system. Cheap enough to call on each resume. */
fun readSystemChecks(context: Context): SystemChecks {
    val notifications = context.getSystemService(NotificationManager::class.java)
    return SystemChecks(
        notificationsEnabled = notifications.areNotificationsEnabled(),
        fullScreenIntentAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notifications.canUseFullScreenIntent(),
        exactAlarmsAllowed = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        doNotDisturb = readDoNotDisturb(context),
        backgroundRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
        cameraGranted = context.hasPermission(Manifest.permission.CAMERA),
    )
}

/** Whether Do Not Disturb lets an alarm sound right now. The ringing service asks this too. */
fun readDoNotDisturb(context: Context): DoNotDisturb =
    context.getSystemService(NotificationManager::class.java).doNotDisturb()

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun NotificationManager.doNotDisturb(): DoNotDisturb = when (currentInterruptionFilter) {
    NotificationManager.INTERRUPTION_FILTER_NONE -> DoNotDisturb.ALARMS_MUTED
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> priorityModeAllowsAlarms()
    else -> DoNotDisturb.ALARMS_ALLOWED
}

private fun NotificationManager.priorityModeAllowsAlarms(): DoNotDisturb = try {
    val allowed = notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
    if (allowed) DoNotDisturb.ALARMS_ALLOWED else DoNotDisturb.ALARMS_MUTED
} catch (e: SecurityException) {
    // Reading the policy needs notification policy access, which the app does not ask for.
    DoNotDisturb.UNKNOWN
}
