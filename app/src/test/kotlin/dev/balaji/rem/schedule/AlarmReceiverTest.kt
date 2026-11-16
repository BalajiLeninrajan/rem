package dev.balaji.rem.schedule

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.RemApplication
import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmReceiverTest {
    private val app: RemApplication = ApplicationProvider.getApplicationContext()
    private val deadline = Instant.parse("2026-01-15T12:00:00Z")
    private val ring = ScheduledRing(alarmId = 5, ringAt = deadline.minusSeconds(900), deadline = deadline)

    @Test
    fun `the ring starts inside onReceive, then the fire is recorded before the reschedule`() = runTest {
        val container = app.useFakeContainer(backgroundScope)

        AlarmReceiver().onReceive(app, AlarmBroadcast.fireIntent(app, ring))

        assertEquals(listOf(ring), container.ringController.started)
        assertTrue(container.alarmScheduler.reschedules.isEmpty())

        runCurrent()

        assertEquals(listOf(mapOf(5L to deadline)), container.alarmScheduler.reschedules)
    }

    @Test
    fun `the test alarm rings and reschedules without a fired record`() = runTest {
        val container = app.useFakeContainer(backgroundScope)
        val test = ring.copy(alarmId = AlarmBroadcast.TEST_ALARM_ID)

        AlarmReceiver().onReceive(app, AlarmBroadcast.fireIntent(app, test))
        runCurrent()

        assertEquals(listOf(test), container.ringController.started)
        assertEquals(listOf(emptyMap<Long, Instant>()), container.alarmScheduler.reschedules)
    }

    @Test
    fun `a ring that did not start is left unfired while its deadline is ahead`() = runTest {
        val container = app.useFakeContainer(backgroundScope)
        container.ringController.accepts = false
        val ahead = Instant.now().plusSeconds(900)
        val early = ScheduledRing(alarmId = 5, ringAt = ahead.minusSeconds(900), deadline = ahead)

        AlarmReceiver().onReceive(app, AlarmBroadcast.fireIntent(app, early))
        runCurrent()

        assertEquals(listOf(emptyMap<Long, Instant>()), container.alarmScheduler.reschedules)
    }

    @Test
    fun `a ring that did not start counts as fired once its deadline has passed`() = runTest {
        val container = app.useFakeContainer(backgroundScope)
        container.ringController.accepts = false

        AlarmReceiver().onReceive(app, AlarmBroadcast.fireIntent(app, ring))
        runCurrent()

        assertEquals(listOf(mapOf(5L to deadline)), container.alarmScheduler.reschedules)
    }

    @Test
    fun `the watchdog broadcast resumes and neither starts a ring nor records a fire`() = runTest {
        val container = app.useFakeContainer(backgroundScope)

        AlarmReceiver().onReceive(app, AlarmBroadcast.resumeIntent(app, ring))
        runCurrent()

        assertEquals(listOf(ring), container.ringController.resumed)
        assertTrue(container.ringController.started.isEmpty())
        assertTrue(container.ringStateStore.lastFired().isEmpty())
    }

    @Test
    fun `a broadcast without a complete ring does nothing`() = runTest {
        val container = app.useFakeContainer(backgroundScope)
        val incomplete = AlarmBroadcast.fireIntent(app, ring).apply { removeExtra(AlarmBroadcast.EXTRA_RING_AT) }
        val wrongAction = AlarmBroadcast.fireIntent(app, ring).setAction(Intent.ACTION_BOOT_COMPLETED)

        AlarmReceiver().onReceive(app, incomplete)
        AlarmReceiver().onReceive(app, wrongAction)
        runCurrent()

        assertTrue(container.ringController.started.isEmpty())
        assertTrue(container.alarmScheduler.reschedules.isEmpty())
        assertTrue(container.ringStateStore.lastFired().isEmpty())
    }
}
