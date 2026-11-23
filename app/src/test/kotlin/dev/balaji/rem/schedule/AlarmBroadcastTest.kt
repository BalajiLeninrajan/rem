package dev.balaji.rem.schedule

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.ring.RingContract
import dev.balaji.rem.ring.RingingService
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmBroadcastTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deadline = Instant.parse("2026-01-15T12:00:00Z")
    private val ring = ScheduledRing(alarmId = 42, ringAt = deadline.minusSeconds(900), deadline = deadline)

    @Test
    fun `fire intent targets the alarm receiver and carries the ring`() {
        val intent = AlarmBroadcast.fireIntent(context, ring)

        assertEquals(AlarmReceiver::class.java.name, intent.component?.className)
        assertEquals(AlarmBroadcast.ACTION_FIRE, intent.action)
        assertEquals(ring, intent.scheduledRingOrNull())
    }

    @Test
    fun `intents of different alarms are different PendingIntent identities`() {
        val other = ring.copy(alarmId = 43)

        assertFalse(AlarmBroadcast.fireIntent(context, ring).filterEquals(AlarmBroadcast.fireIntent(context, other)))
    }

    @Test
    fun `the test alarm id survives the round trip`() {
        val test = ring.copy(alarmId = AlarmBroadcast.TEST_ALARM_ID)

        assertEquals(test, AlarmBroadcast.fireIntent(context, test).scheduledRingOrNull())
    }

    @Test
    fun `an intent with a missing extra gives no ring`() {
        val incomplete = AlarmBroadcast.fireIntent(context, ring).apply { removeExtra(AlarmBroadcast.EXTRA_DEADLINE) }

        assertNull(incomplete.scheduledRingOrNull())
        assertNull(Intent().scheduledRingOrNull())
    }

    @Test
    fun `service intent targets the ringing service and carries the ring`() {
        val intent = RingContract.serviceIntent(context, ring)

        assertEquals(RingingService::class.java.name, intent.component?.className)
        assertEquals(ring, intent.scheduledRingOrNull())
    }
}
