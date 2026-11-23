package dev.balaji.rem.ring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.ui.AppLinks
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class RingNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = RingNotifications(context)

    @Test
    fun `the ringing channel is high importance and silent`() {
        notifications.ensureChannels()
        notifications.ensureChannels()

        val ringing = manager.getNotificationChannel(RingContract.CHANNEL_RINGING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, ringing.importance)
        assertNull(ringing.sound)
        assertEquals(Notification.VISIBILITY_PUBLIC, ringing.lockscreenVisibility)
        assertNotNull(manager.getNotificationChannel(RingContract.CHANNEL_MISSED))
    }

    @Test
    fun `the ringing notification is an ongoing public alarm with a full-screen intent`() {
        val notification = notifications.ringing(label = "Work", startedAt = Instant.parse("2026-01-15T06:45:00Z"))

        assertEquals(RingContract.CHANNEL_RINGING, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals("Work", notification.extras.getString(Notification.EXTRA_TITLE))
        assertNotNull(notification.deleteIntent)

        val fullScreen = shadowOf(notification.fullScreenIntent)
        assertTrue(fullScreen.isActivityIntent)
        assertEquals(RingingActivity::class.java.name, fullScreen.savedIntent.component?.className)
        assertEquals(RingingActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
    }

    private fun spec(requiresBarcode: Boolean, snoozesLeft: Int) = RingSpec(
        alarmId = 7,
        label = "Work",
        ringtoneUri = null,
        vibrate = true,
        minRingVolumePercent = 70,
        volumeRampSeconds = 30,
        headphonesOnly = false,
        speakerFallbackSeconds = 30,
        startedAt = Instant.parse("2026-01-15T06:45:00Z"),
        requiresBarcode = requiresBarcode,
        snoozeMinutes = 5,
        snoozesLeft = snoozesLeft,
    )

    private fun Notification.Action.broadcast(): Intent {
        val pending = shadowOf(actionIntent)
        assertTrue(pending.isBroadcastIntent)
        return pending.savedIntent
    }

    @Test
    fun `until the alarm is loaded the only action opens the ringing screen`() {
        val notification = notifications.ringing(label = "", startedAt = null)

        val action = notification.actions.single()
        assertEquals("Scan to dismiss", action.title.toString())
        assertEquals(RingingActivity::class.java.name, shadowOf(action.actionIntent).savedIntent.component?.className)
        assertEquals("Alarm", notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `an alarm with barcodes offers snooze and scan to dismiss`() {
        val notification = notifications.ringing("Work", Instant.parse("2026-01-15T06:45:00Z"), spec(requiresBarcode = true, snoozesLeft = 2))

        val (snooze, scan) = notification.actions.toList()
        assertEquals(2, notification.actions.size)
        assertEquals("Snooze 5 min", snooze.title.toString())
        val intent = snooze.broadcast()
        assertEquals(RingActionReceiver::class.java.name, intent.component?.className)
        assertEquals(RingContract.ACTION_SNOOZE, intent.action)
        assertEquals(7L, intent.getLongExtra(RingContract.EXTRA_ALARM_ID, 0))
        assertEquals("Scan to dismiss", scan.title.toString())
        assertEquals(RingingActivity::class.java.name, shadowOf(scan.actionIntent).savedIntent.component?.className)
    }

    @Test
    fun `an alarm without barcodes offers snooze and a plain dismiss that opens nothing`() {
        val notification = notifications.ringing("Work", Instant.parse("2026-01-15T06:45:00Z"), spec(requiresBarcode = false, snoozesLeft = 1))

        assertEquals(listOf("Snooze 5 min", "Dismiss"), notification.actions.map { it.title.toString() })
        val dismiss = notification.actions[1].broadcast()
        assertEquals(RingActionReceiver::class.java.name, dismiss.component?.className)
        assertEquals(RingContract.ACTION_DISMISS, dismiss.action)
        assertEquals(7L, dismiss.getLongExtra(RingContract.EXTRA_ALARM_ID, 0))
    }

    @Test
    fun `with no snoozes left only the way to dismiss is offered`() {
        val started = Instant.parse("2026-01-15T06:45:00Z")

        val withBarcodes = notifications.ringing("Work", started, spec(requiresBarcode = true, snoozesLeft = 0))
        val without = notifications.ringing("Work", started, spec(requiresBarcode = false, snoozesLeft = 0))

        assertEquals(listOf("Scan to dismiss"), withBarcodes.actions.map { it.title.toString() })
        assertEquals(listOf("Dismiss"), without.actions.map { it.title.toString() })
    }

    @Test
    fun `a snoozed alarm has an ongoing quiet notification per alarm that opens the ringing screen`() {
        notifications.ensureChannels()
        val snoozed = SnoozedAlarm(7, "Work", Instant.parse("2026-01-15T06:50:00Z"))

        notifications.notifySnoozed(snoozed)
        notifications.notifySnoozed(snoozed.copy(alarmId = 8, label = ""))

        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(RingContract.CHANNEL_SNOOZED).importance)
        val notification = shadowOf(manager).getNotification("7", RingContract.NOTIFICATION_ID_SNOOZED)
        assertEquals(RingContract.CHANNEL_SNOOZED, notification.channelId)
        assertEquals("Work", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Snoozed until "))
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(RingingActivity::class.java.name, shadowOf(notification.contentIntent).savedIntent.component?.className)
        assertEquals(2, shadowOf(manager).allNotifications.size)

        notifications.cancelSnoozed(7)
        assertNull(shadowOf(manager).getNotification("7", RingContract.NOTIFICATION_ID_SNOOZED))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `a missed alarm notification opens the reliability screen and is posted once per alarm`() {
        notifications.ensureChannels()
        val missed = MissedAlarm(7, "Work", Instant.parse("2026-01-15T07:00:00Z"), MissedReason.RING_TIMED_OUT)

        notifications.notifyMissed(missed)
        notifications.notifyMissed(missed)
        notifications.notifyMissed(missed.copy(alarmId = 8))

        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val notification = shadowOf(manager).getNotification("7", RingContract.NOTIFICATION_ID_MISSED)
        assertEquals(RingContract.CHANNEL_MISSED, notification.channelId)
        assertEquals("Missed alarm: Work", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(
            AppLinks.DESTINATION_RELIABILITY,
            shadowOf(notification.contentIntent).savedIntent.getStringExtra(AppLinks.EXTRA_DESTINATION),
        )
    }
}
