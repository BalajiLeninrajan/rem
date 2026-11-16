package dev.balaji.rem.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import dev.balaji.rem.R
import dev.balaji.rem.ui.AppLinks
import java.time.Instant
import java.util.Date

/** Builds and posts the two notifications of the ring package. Holds no state. */
internal class RingNotifications(private val context: Context) : AlarmNotifier {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    override fun ensureChannels() {
        // The service plays the tone and vibrates, so the channel itself stays silent.
        val ringing = NotificationChannel(
            RingContract.CHANNEL_RINGING,
            context.getString(R.string.ring_channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ring_channel_ringing_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val missed = NotificationChannel(
            RingContract.CHANNEL_MISSED,
            context.getString(R.string.ring_channel_missed_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.ring_channel_missed_description)
        }
        manager.createNotificationChannels(listOf(ringing, missed))
    }

    override fun notifyMissed(missed: MissedAlarm) {
        manager.notify(missed.alarmId.toString(), RingContract.NOTIFICATION_ID_MISSED, missedNotification(missed))
    }

    /**
     * The ongoing notification of the ringing service. Its tap, its full-screen intent and
     * its one action all open the ringing screen. Nothing on it stops the alarm.
     */
    fun ringing(label: String, startedAt: Instant?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            RingContract.activityIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // From Android 14 the user can swipe an ongoing notification away. The service posts it again.
        val swipedAway = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_RINGING_NOTIFICATION_REMOVED).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, RingContract.CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_ring_notification)
            .setContentTitle(label.ifBlank { context.getString(R.string.ring_default_label) })
            .setContentText(context.getString(R.string.ring_notification_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(startedAt != null)
            .setWhen(startedAt?.toEpochMilli() ?: 0L)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setDeleteIntent(swipedAway)
            .addAction(0, context.getString(R.string.ring_action_scan), open)
            .build()
    }

    fun postRinging(notification: Notification) {
        manager.notify(RingContract.NOTIFICATION_ID_RINGING, notification)
    }

    fun cancelRinging() {
        manager.cancel(RingContract.NOTIFICATION_ID_RINGING)
    }

    internal fun missedNotification(missed: MissedAlarm): Notification {
        val time = DateFormat.getTimeFormat(context).format(Date.from(missed.deadline))
        val title = if (missed.label.isBlank()) {
            context.getString(R.string.ring_missed_title)
        } else {
            context.getString(R.string.ring_missed_title_labelled, missed.label)
        }
        val text = context.getString(
            when (missed.reason) {
                MissedReason.NOT_FIRED -> R.string.ring_missed_not_fired
                MissedReason.RING_INTERRUPTED -> R.string.ring_missed_interrupted
                MissedReason.RING_TIMED_OUT -> R.string.ring_missed_timed_out
            },
            time,
        )
        val open = PendingIntent.getActivity(
            context,
            missed.alarmId.hashCode(),
            AppLinks.reliability(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, RingContract.CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_ring_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(missed.deadline.toEpochMilli())
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_RINGING_NOTIFICATION_REMOVED = "dev.balaji.rem.action.RINGING_NOTIFICATION_REMOVED"
    }
}
