package dev.balaji.rem

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.ring.DismissResult
import dev.balaji.rem.ring.RingCommand
import dev.balaji.rem.ring.RingServiceHost
import dev.balaji.rem.ring.RingingService
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.schedule.AlarmBroadcast
import dev.balaji.rem.schedule.AlarmReceiver
import dev.balaji.rem.schedule.scheduledRingOrNull
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Runs whole flows on the container the application really builds: Room and DataStore in
 * device-protected storage, the AlarmManager scheduler and the ring controller. Only the
 * system services are Robolectric's. Background work runs on real threads, so the test
 * waits for results instead of advancing a test clock.
 */
@RunWith(AndroidJUnit4::class)
class AlarmFlowTest {
    private val app: RemApplication = ApplicationProvider.getApplicationContext()
    private val container get() = app.container
    private val alarmManager = app.getSystemService(AlarmManager::class.java)
    private val host get() = container.ringController as RingServiceHost

    @Before
    fun allowExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun stopBackgroundWork() {
        container.applicationScope.cancel()
    }

    @Test
    fun `an alarm is scheduled, moved by a sleep session, rung and dismissed with its barcode`() = wholeFlow {
        val start = container.clock.instant()
        // One hour cycles and no time to fall asleep put the early ring 60 minutes ahead and the
        // deadline 75. The test fires the ring right away, and a fired deadline more than the
        // largest early window (90 minutes) ahead of the clock would be dropped as impossible.
        container.settingsRepository.update { it.copy(cycleMinutes = 60, fallAsleepMinutes = 0) }
        val wakeBy = start.plus(Duration.ofMinutes(75)).atZone(container.clock.zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        val registered = Barcode(label = "toothpaste", format = BarcodeFormat.EAN_13, value = "0036000291452")

        // Saving is all the editor does. The scheduler watches the repository.
        val id = container.alarmRepository.upsertWithBarcodes(Alarm(time = wakeBy, label = "work"), listOf(registered))
        val atDeadline = container.alarmScheduler.plan.mapNotNull { it.forAlarm(id) }.first()
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), atDeadline.explanation)
        assertEquals(atDeadline.deadline.toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)

        // Going to sleep now puts the end of the first cycle 15 minutes before the deadline.
        container.sleepSessionRepository.start(start)
        val early = container.alarmScheduler.plan
            .mapNotNull { it.forAlarm(id) }
            .first { it.explanation is WakeExplanation.Cycles }
        assertEquals(WakeExplanation.Cycles(cycles = 1, minutesBeforeDeadline = 15), early.explanation)
        assertEquals(atDeadline.deadline.minus(Duration.ofMinutes(15)), early.ringAt)
        assertEquals(early.ringAt.toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)

        val ring = early.toScheduledRing()
        AlarmReceiver().onReceive(app, AlarmBroadcast.fireIntent(app, ring))
        assertTrue(container.ringController.state.value is RingingState.Ringing)
        val serviceStart = shadowOf(app).nextStartedService
        assertEquals(RingingService::class.java.name, serviceStart.component?.className)
        assertEquals(ring, serviceStart.scheduledRingOrNull())

        val play = host.command.filterIsInstance<RingCommand.Play>().first()
        assertEquals("work", play.spec.label)
        assertEquals(id, container.ringStateStore.currentRinging()?.alarmId)
        // The receiver records the fire in the background.
        while (container.ringStateStore.lastFired()[id] != ring.deadline) delay(POLL_MILLIS)

        val stranger = ScannedCode(BarcodeFormat.EAN_13, "4006381333931")
        assertEquals(DismissResult.REJECTED, container.ringController.dismissWithBarcode(stranger))
        assertTrue(host.command.value is RingCommand.Play)

        // The registered EAN-13, read by the scanner as UPC-A.
        val toothpaste = ScannedCode(BarcodeFormat.UPC_A, "036000291452")
        assertEquals(DismissResult.DISMISSED, container.ringController.dismissWithBarcode(toothpaste))

        assertEquals(RingingState.Idle, container.ringController.state.value)
        assertEquals(RingCommand.Stop, host.command.value)
        assertEquals(false, container.alarmRepository.get(id)?.enabled)
        assertNull(container.sleepSessionRepository.current())
        assertNull(container.ringStateStore.currentRinging())
        assertTrue(container.alarmScheduler.plan.value.rings.isEmpty())
        assertNull(alarmManager.nextAlarmClock)
    }

    @Test
    fun `the test alarm rings without a stored alarm and stops with the plain button, since it has no barcodes`() = wholeFlow {
        container.sleepSessionRepository.start(container.clock.instant())

        val ringAt = container.alarmScheduler.scheduleTestAlarm()!!
        val fire = shadowOf(shadowOf(alarmManager).scheduledAlarms.single().operation).savedIntent
        assertEquals(ScheduledRing(AlarmBroadcast.TEST_ALARM_ID, ringAt, ringAt), fire.scheduledRingOrNull())

        AlarmReceiver().onReceive(app, fire)
        val play = host.command.filterIsInstance<RingCommand.Play>().first()
        assertEquals(app.getString(R.string.ring_test_alarm_label), play.spec.label)
        assertFalse((container.ringController.state.value as RingingState.Ringing).requiresBarcode)

        assertEquals(DismissResult.REJECTED, container.ringController.dismissWithTypedCode("not the code"))
        assertEquals(DismissResult.DISMISSED, container.ringController.dismissWithoutBarcode())

        assertEquals(RingingState.Idle, container.ringController.state.value)
        assertNull(container.ringStateStore.currentRinging())
        // The test alarm leaves the session and the fired records alone.
        assertNotNull(container.sleepSessionRepository.current())
        assertTrue(container.ringStateStore.lastFired().isEmpty())
    }

    private fun wholeFlow(steps: suspend () -> Unit) = runBlocking { withTimeout(TIMEOUT_MILLIS) { steps() } }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_MILLIS = 20L
    }
}
