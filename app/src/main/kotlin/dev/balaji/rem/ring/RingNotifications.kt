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

/** Builds and posts the notifications of the ring package. Holds no state. */
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
        val snoozed = NotificationChannel(
            RingContract.CHANNEL_SNOOZED,
            context.getString(R.string.ring_channel_snoozed_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.ring_channel_snoozed_description)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannels(listOf(ringing, missed, snoozed))
    }

    override fun notifyMissed(missed: MissedAlarm) {
        manager.notify(missed.alarmId.toString(), RingContract.NOTIFICATION_ID_MISSED, missedNotification(missed))
    }

    override fun notifySnoozed(snoozed: SnoozedAlarm) {
        manager.notify(snoozed.alarmId.toString(), RingContract.NOTIFICATION_ID_SNOOZED, snoozedNotification(snoozed))
    }

    override fun cancelSnoozed(alarmId: Long) {
        manager.cancel(alarmId.toString(), RingContract.NOTIFICATION_ID_SNOOZED)
    }

    /**
     * The ongoing notification of the ringing service. Its tap and its full-screen intent
     * open the ringing screen. Until the alarm is loaded it counts as needing a barcode, so
     * the only action is "Scan to dismiss", which opens the ringing screen too.
     *
     * Once the alarm is loaded ([ring] is not null) the actions follow it: Snooze while
     * snoozes are left, then "Scan to dismiss" for an alarm with barcodes, or Dismiss for
     * one without. Snooze and Dismiss act through [RingActionReceiver] without opening
     * anything.
     */
    fun ringing(label: String, startedAt: Instant?, ring: RingSpec? = null): Notification {
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
            .apply {
                if (ring != null && ring.snoozesLeft > 0) {
                    val snooze = broadcast(RingContract.ACTION_SNOOZE, ring.alarmId)
                    addAction(0, context.getString(R.string.ring_action_snooze, ring.snoozeMinutes), snooze)
                }
                if (ring != null && !ring.requiresBarcode) {
                    addAction(0, context.getString(R.string.ring_action_dismiss), broadcast(RingContract.ACTION_DISMISS, ring.alarmId))
                } else {
                    addAction(0, context.getString(R.string.ring_action_scan), open)
                }
            }
            .build()
    }

    /**
     * The ongoing "snoozed until" notification of one alarm. Its tap opens the ringing
     * screen, which shows the snooze and offers to dismiss it now.
     */
    internal fun snoozedNotification(snoozed: SnoozedAlarm): Notification {
        val time = DateFormat.getTimeFormat(context).format(Date.from(snoozed.until))
        val open = PendingIntent.getActivity(
            context,
            snoozed.alarmId.hashCode(),
            RingContract.activityIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, RingContract.CHANNEL_SNOOZED)
            .setSmallIcon(R.drawable.ic_ring_notification)
            .setContentTitle(snoozed.label.ifBlank { context.getString(R.string.ring_default_label) })
            .setContentText(context.getString(R.string.ring_snoozed_notification_text, time))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    }

    /** The PendingIntent of a notification action that [RingActionReceiver] carries out. */
    private fun broadcast(action: String, alarmId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            RingContract.actionIntent(context, action, alarmId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

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
                MissedReason.OTHER_ALARM_RINGING -> R.string.ring_missed_other_ringing
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
