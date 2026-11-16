package dev.balaji.rem.ui.system

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens the system settings screen that fixes a failing check. These screens are missing
 * or renamed on some devices, so every call falls back to the app details page.
 */
object SystemSettings {
    fun openNotifications(context: Context) = context.startOrAppDetails(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** The settings of one notification category, where its importance can be put back. */
    fun openNotificationChannel(context: Context, channelId: String) = context.startOrAppDetails(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
    )

    fun openFullScreenIntent(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.startOrAppDetails(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, context.packageUri()))
        } else {
            openAppDetails(context)
        }
    }

    fun openExactAlarms(context: Context) =
        context.startOrAppDetails(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, context.packageUri()))

    fun openDoNotDisturb(context: Context) =
        context.startOrAppDetails(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))

    fun openAppDetails(context: Context) {
        context.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, context.packageUri()))
    }

    /** Hands [url] to the browser. The app itself never uses the network. */
    fun openWebPage(context: Context, url: String) {
        context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** The activity behind a Compose `LocalContext`, or null in a preview. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.packageUri(): Uri = Uri.fromParts("package", packageName, null)

private fun Context.startOrAppDetails(intent: Intent) {
    if (!tryStart(intent)) SystemSettings.openAppDetails(this)
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
