package dev.balaji.rem.ring

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.RingLimits
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.schedule.AlarmBroadcast
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRingControllerTest {
    private val now = Instant.parse("2026-01-15T06:45:00Z")
    private val deadline = Instant.parse("2026-01-15T07:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val alarms = InMemoryAlarmRepository()
    private val barcodes = InMemoryBarcodeRepository()
    private val sessions = InMemorySleepSessionRepository()
    private val settings = InMemorySettingsRepository()
    private val ringState = InMemoryRingStateStore()

    private val missed = mutableListOf<MissedAlarm>()
    private val notifier = object : AlarmNotifier {
        override fun ensureChannels() = Unit

        override fun notifyMissed(missed: MissedAlarm) {
            this@DefaultRingControllerTest.missed += missed
        }
    }
    private val serviceStarts = mutableListOf<ScheduledRing>()
    private var serviceStartFails = false
    private var reschedules = 0

    private val toothpaste = ScannedCode(BarcodeFormat.EAN_13, "4006381333931")

    private fun TestScope.controller() = DefaultRingController(
        alarms = alarms,
        barcodes = barcodes,
        sessions = sessions,
        settings = settings,
        ringState = ringState,
        notifier = notifier,
        launcher = { ring ->
            if (serviceStartFails) throw IllegalStateException("start not allowed")
            serviceStarts += ring
        },
        reschedule = { reschedules++ },
        clock = clock,
        scope = backgroundScope,
        testAlarmLabel = "Test alarm",
    )

    private fun ringFor(alarmId: Long) = ScheduledRing(alarmId, ringAt = now, deadline = deadline)

    private suspend fun oneShot(label: String = "Work"): Long = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = label))

    /** A controller with [alarmId] ringing and fully loaded. */
    private fun TestScope.ringing(alarmId: Long): DefaultRingController =
        controller().also {
            it.startRinging(ringFor(alarmId))
            runCurrent()
        }

    @Test
    fun `counts as ringing the moment startRinging returns`() = runTest {
        val id = oneShot()
        val controller = controller()

        controller.startRinging(ringFor(id))

        assertTrue(controller.state.value is RingingState.Ringing)
        assertEquals(RingCommand.Wait, controller.command.value)
        assertEquals(listOf(ringFor(id)), serviceStarts)
    }

    @Test
    fun `a started ring is persisted and tells the service what to play`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = "Work", headphonesOnly = true, vibrate = false))
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        settings.update { it.copy(speakerFallbackSeconds = 45) }

        val controller = ringing(id)

        assertEquals(RingingRecord(id, now, now, deadline), ringState.currentRinging())
        assertEquals(RingingState.Ringing(id, "Work", now, deadline, requiresBarcode = true), controller.state.value)
        val spec = (controller.command.value as RingCommand.Play).spec
        assertEquals("Work", spec.label)
        assertTrue(spec.headphonesOnly)
        assertFalse(spec.vibrate)
        assertEquals(45, spec.settings.speakerFallbackSeconds)
    }

    @Test
    fun `a wrong barcode is rejected and the ring goes on`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        val result = controller.dismissWithBarcode(ScannedCode(BarcodeFormat.EAN_13, "5000112637922"))

        assertEquals(DismissResult.REJECTED, result)
        assertTrue(controller.state.value is RingingState.Ringing)
        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(true, alarms.get(id)?.enabled)
    }

    @Test
    fun `a registered barcode dismisses, disables the one-shot alarm and ends the covering session`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(8 * 3600))
        val controller = ringing(id)

        val result = controller.dismissWithBarcode(toothpaste)

        assertEquals(DismissResult.DISMISSED, result)
        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertNull(ringState.currentRinging())
        assertEquals(false, alarms.get(id)?.enabled)
        assertNull(sessions.current())
        assertEquals(1, reschedules)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `a UPC-A barcode is accepted when the scanner reports it as EAN-13`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Cereal", ScannedCode(BarcodeFormat.UPC_A, "036000291452")))
        val controller = ringing(id)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(ScannedCode(BarcodeFormat.EAN_13, "0036000291452")))
    }

    @Test
    fun `a repeating alarm stays enabled and a session that does not cover the deadline stays`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), repeatDays = RepeatDays.EVERY_DAY))
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(20 * 3600))
        val controller = ringing(id)

        controller.dismissWithBarcode(toothpaste)

        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(now.minusSeconds(20 * 3600), sessions.current()?.startedAt)
    }

    @Test
    fun `the typed code dismisses only when it is the latest one`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        assertEquals("no code was asked for yet", DismissResult.REJECTED, controller.dismissWithTypedCode(""))
        val first = controller.newFallbackCode()
        val second = controller.newFallbackCode()
        assertNotEquals(first, second)

        assertEquals(DismissResult.REJECTED, controller.dismissWithTypedCode(first))
        assertEquals(DismissResult.REJECTED, controller.dismissWithTypedCode(second.lowercase()))
        assertEquals(DismissResult.REJECTED, controller.dismissWithTypedCode(second.dropLast(1)))
        assertEquals(DismissResult.DISMISSED, controller.dismissWithTypedCode(" $second "))
        assertEquals(RingingState.Idle, controller.state.value)
    }

    @Test
    fun `a code from an earlier ring does not dismiss the next one`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), repeatDays = RepeatDays.EVERY_DAY))
        val controller = ringing(id)
        val old = controller.newFallbackCode()
        controller.dismissWithTypedCode(old)

        controller.startRinging(ringFor(id))
        runCurrent()

        assertEquals(DismissResult.REJECTED, controller.dismissWithTypedCode(old))
    }

    @Test
    fun `with no barcode registered a plain dismissal works`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        assertEquals(false, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
        assertEquals(RingingState.Idle, controller.state.value)
    }

    @Test
    fun `with a barcode registered a plain dismissal is rejected`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertTrue(controller.state.value is RingingState.Ringing)
    }

    @Test
    fun `deleting every barcode during a ring does not unlock the plain dismissal`() = runTest {
        val id = oneShot()
        val barcodeId = barcodes.add(Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        barcodes.delete(barcodeId)

        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
    }

    @Test
    fun `every dismissal reports that nothing rings when idle`() = runTest {
        val controller = controller()

        assertEquals(DismissResult.NOT_RINGING, controller.dismissWithBarcode(toothpaste))
        assertEquals(DismissResult.NOT_RINGING, controller.dismissWithTypedCode(controller.newFallbackCode()))
        assertEquals(DismissResult.NOT_RINGING, controller.dismissWithoutBarcode())
    }

    @Test
    fun `after 30 minutes the ring stops and is reported as missed, and the session stays`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(8 * 3600))
        val controller = ringing(id)

        advanceTimeBy(RingLimits.MAX_RING.toMillis() - 1)
        assertTrue(controller.state.value is RingingState.Ringing)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_TIMED_OUT)), missed)
        assertNull(ringState.currentRinging())
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals(now.minusSeconds(8 * 3600), sessions.current()?.startedAt)
    }

    @Test
    fun `a second alarm during a ring starts nothing and is disabled if it is one-shot`() = runTest {
        val first = oneShot("First")
        val second = oneShot("Second")
        val controller = ringing(first)

        controller.startRinging(ringFor(second))
        runCurrent()

        assertEquals(first, (controller.state.value as RingingState.Ringing).alarmId)
        assertEquals(listOf(ringFor(first)), serviceStarts)
        assertEquals(false, alarms.get(second)?.enabled)
        assertEquals(true, alarms.get(first)?.enabled)
    }

    @Test
    fun `a disabled or deleted alarm stops without ringing`() = runTest {
        val disabled = alarms.upsert(Alarm(time = LocalTime.of(7, 0), enabled = false))
        val controller = controller()

        controller.startRinging(ringFor(disabled))
        runCurrent()
        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)

        controller.startRinging(ringFor(999))
        runCurrent()
        assertEquals(RingingState.Idle, controller.state.value)
        assertNull(ringState.currentRinging())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `the test alarm rings without a stored alarm and leaves the session alone`() = runTest {
        sessions.start(now.minusSeconds(3600))
        val controller = ringing(AlarmBroadcast.TEST_ALARM_ID)

        val state = controller.state.value as RingingState.Ringing
        assertEquals("Test alarm", state.label)
        assertEquals(false, state.requiresBarcode)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
        assertEquals(now.minusSeconds(3600), sessions.current()?.startedAt)
    }

    @Test
    fun `a ring whose service cannot start is reported as missed`() = runTest {
        val id = oneShot()
        serviceStartFails = true
        val controller = controller()

        controller.startRinging(ringFor(id))
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertEquals(false, alarms.get(id)?.enabled)
    }

    @Test
    fun `a ring record left by a dead process is reported, cleared and never rung again`() = runTest {
        val id = oneShot()
        val record = RingingRecord(id, now.minusSeconds(60), now.minusSeconds(60), deadline)
        ringState.setRinging(record)
        val controller = controller()

        controller.recoverInterruptedRing()

        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_INTERRUPTED)), missed)
        assertNull(ringState.currentRinging())
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals(RingingState.Idle, controller.state.value)
        assertTrue(serviceStarts.isEmpty())
    }

    @Test
    fun `recovery leaves a ring started in this process alone`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        controller.recoverInterruptedRing()

        assertTrue(missed.isEmpty())
        assertTrue(controller.state.value is RingingState.Ringing)
        assertEquals(RingingRecord(id, now, now, deadline), ringState.currentRinging())
    }

    @Test
    fun `a redelivered service start resumes the persisted ring with its original start time`() = runTest {
        val id = oneShot()
        barcodes.add(Barcode.from("Toothpaste", toothpaste))
        val startedAt = now.minusSeconds(10 * 60)
        val record = RingingRecord(id, startedAt, startedAt, deadline)
        ringState.setRinging(record)
        val controller = controller()

        controller.onServiceStarted(ScheduledRing(id, startedAt, deadline), redelivered = true)
        runCurrent()
        controller.recoverInterruptedRing()

        assertEquals(startedAt, (controller.state.value as RingingState.Ringing).startedAt)
        assertEquals(record, ringState.currentRinging())
        assertTrue(missed.isEmpty())

        advanceTimeBy(RingLimits.MAX_RING.toMillis() - 10 * 60 * 1000 + 1)
        runCurrent()
        assertEquals(MissedReason.RING_TIMED_OUT, missed.single().reason)
    }

    @Test
    fun `a redelivered service start with no persisted ring stops without ringing`() = runTest {
        val id = oneShot()
        val controller = controller()

        controller.onServiceStarted(ringFor(id), redelivered = true)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertEquals(true, alarms.get(id)?.enabled)
    }

    @Test
    fun `a normal service start never begins a ring by itself`() = runTest {
        val id = oneShot()
        val controller = controller()

        controller.onServiceStarted(ringFor(id), redelivered = false)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
    }
}
