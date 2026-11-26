package dev.balaji.rem.ring

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.RingLimits
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.schedule.AlarmBroadcast
import dev.balaji.rem.schedule.MutableClock
import java.io.IOException
import java.time.Duration
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
    private val clock = MutableClock(now, ZoneOffset.UTC)

    private val barcodes = InMemoryBarcodeRepository()
    private val alarms = InMemoryAlarmRepository(barcodes = barcodes)
    private val sessions = InMemorySleepSessionRepository()
    private val settings = InMemorySettingsRepository()
    private val ringState = InMemoryRingStateStore()

    private val missed = mutableListOf<MissedAlarm>()

    /** The "snoozed until" notifications that are up, by alarm id. */
    private val snoozeNotifications = mutableMapOf<Long, SnoozedAlarm>()
    private val notifier = object : AlarmNotifier {
        override fun ensureChannels() = Unit

        override fun notifyMissed(missed: MissedAlarm) {
            this@DefaultRingControllerTest.missed += missed
        }

        override fun notifySnoozed(snoozed: SnoozedAlarm) {
            snoozeNotifications[snoozed.alarmId] = snoozed
        }

        override fun cancelSnoozed(alarmId: Long) {
            snoozeNotifications -= alarmId
        }
    }
    private val serviceStarts = mutableListOf<ScheduledRing>()
    private var serviceStartFails = false
    private var reschedules = 0

    /** Barcode reads that throw before reads work again. */
    private var failingBarcodeReads = 0
    private val barcodeSource = object : BarcodeRepository by barcodes {
        override suspend fun all(alarmId: Long): List<Barcode> {
            if (failingBarcodeReads-- > 0) throw IOException("disk")
            return this@DefaultRingControllerTest.barcodes.all(alarmId)
        }
    }

    /** What the watchdog entry holds: the ring and when it fires. Null when it is not set. */
    private var watchdogEntry: Pair<ScheduledRing, Instant>? = null
    private var watchdogRefuses = false
    private val watchdog = object : RingWatchdog {
        override fun arm(ring: ScheduledRing, at: Instant): Boolean {
            if (watchdogRefuses) return false
            watchdogEntry = ring to at
            return true
        }

        override fun cancel() {
            watchdogEntry = null
        }
    }

    /** The snooze entries that are set: alarm id to the ring and when it fires. */
    private val snoozeEntries = mutableMapOf<Long, Pair<ScheduledRing, Instant>>()
    private var snoozeEntriesRefused = false
    private val snoozeAlarms = object : SnoozeAlarms {
        override fun setSnooze(ring: ScheduledRing, at: Instant): Boolean {
            if (snoozeEntriesRefused) return false
            snoozeEntries[ring.alarmId] = ring to at
            return true
        }

        override fun cancelSnooze(alarmId: Long) {
            snoozeEntries -= alarmId
        }
    }

    private val toothpaste = ScannedCode(BarcodeFormat.EAN_13, "4006381333931")

    private fun TestScope.controller() = DefaultRingController(
        alarms = alarms,
        barcodes = barcodeSource,
        sessions = sessions,
        settings = settings,
        ringState = ringState,
        notifier = notifier,
        launcher = { ring ->
            if (serviceStartFails) throw IllegalStateException("start not allowed")
            serviceStarts += ring
        },
        watchdog = watchdog,
        snoozeAlarms = snoozeAlarms,
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

    private val DefaultRingController.isQuiet: Boolean get() = (command.value as RingCommand.Play).quiet

    private val DefaultRingController.quietUntil: Instant? get() = (state.value as RingingState.Ringing).quietUntil

    private val quietMillis = RingLimits.QUIET_PERIOD.toMillis()

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
        val alarm = Alarm(
            time = LocalTime.of(7, 0),
            label = "Work",
            vibrate = false,
            ringtoneUri = "content://tone",
            minRingVolumePercent = 40,
            volumeRampSeconds = 0,
        )
        val id = alarms.upsert(alarm)
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        settings.update { it.copy(speakerFallbackSeconds = 45, headphonesOnly = true) }

        val controller = ringing(id)

        assertEquals(RingingRecord(id, now, now, deadline, barcodes = listOf(toothpaste.normalised())), ringState.currentRinging())
        assertEquals(
            RingingState.Ringing(id, "Work", now, deadline, requiresBarcode = true, snoozeMinutes = 5, snoozesLeft = 3),
            controller.state.value,
        )
        val spec = (controller.command.value as RingCommand.Play).spec
        assertEquals(
            RingSpec(
                alarmId = id,
                label = "Work",
                ringtoneUri = "content://tone",
                vibrate = false,
                minRingVolumePercent = 40,
                volumeRampSeconds = 0,
                headphonesOnly = true,
                speakerFallbackSeconds = 45,
                startedAt = now,
                requiresBarcode = true,
                snoozeMinutes = 5,
                snoozesLeft = 3,
            ),
            spec,
        )
    }

    @Test
    fun `only the barcodes of the ringing alarm dismiss it`() = runTest {
        val id = oneShot()
        val other = oneShot("Other")
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        barcodes.add(other, Barcode.from("Mug", ScannedCode(BarcodeFormat.QR_CODE, "mug")))
        val controller = ringing(id)

        assertEquals(DismissResult.REJECTED, controller.dismissWithBarcode(ScannedCode(BarcodeFormat.QR_CODE, "mug")))
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
    }

    @Test
    fun `an alarm without barcodes needs none, whatever other alarms have`() = runTest {
        val id = oneShot()
        val other = oneShot("Other")
        barcodes.add(other, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        assertEquals(false, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertEquals(DismissResult.REJECTED, controller.dismissWithBarcode(toothpaste))
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
    }

    @Test
    fun `a wrong barcode is rejected and the ring goes on`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
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
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
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
        barcodes.add(id, Barcode.from("Cereal", ScannedCode(BarcodeFormat.UPC_A, "036000291452")))
        val controller = ringing(id)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(ScannedCode(BarcodeFormat.EAN_13, "0036000291452")))
    }

    @Test
    fun `a repeating alarm stays enabled and a session that does not cover the deadline stays`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), repeatDays = RepeatDays.EVERY_DAY))
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(20 * 3600))
        val controller = ringing(id)

        controller.dismissWithBarcode(toothpaste)

        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(now.minusSeconds(20 * 3600), sessions.current()?.startedAt)
    }

    @Test
    fun `the typed code dismisses only when it is the latest one`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
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
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)

        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertTrue(controller.state.value is RingingState.Ringing)
    }

    @Test
    fun `deleting every barcode during a ring does not unlock the plain dismissal`() = runTest {
        val id = oneShot()
        val barcodeId = barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
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
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(8 * 3600))
        val controller = ringing(id)

        advanceTimeBy(RingLimits.MAX_RING.toMillis() - 1)
        assertTrue(controller.state.value is RingingState.Ringing)
        clock.now = now.plus(RingLimits.MAX_RING)
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
    fun `a ring that times out before its deadline stays owed and is planned again`() = runTest {
        val id = oneShot()
        val early = ScheduledRing(id, ringAt = now.minusSeconds(45 * 60), deadline = deadline)
        clock.now = early.ringAt
        ringState.markFired(id, deadline)
        val controller = controller()
        assertTrue(controller.startRinging(early))
        runCurrent()

        clock.now = early.ringAt.plus(RingLimits.MAX_RING)
        advanceTimeBy(RingLimits.MAX_RING.toMillis() + 1)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(MissedReason.RING_TIMED_OUT, missed.single().reason)
        assertEquals("still on, so the deadline rings", true, alarms.get(id)?.enabled)
        assertTrue("the fired record is gone, so the plan returns to this deadline", ringState.lastFired().isEmpty())
        assertEquals(1, reschedules)
    }

    @Test
    fun `a one-shot alarm moved to another time while it rings stays on after the dismissal`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        alarms.upsert(alarms.get(id)!!.copy(time = LocalTime.of(7, 30)))
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())

        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(1, reschedules)
    }

    @Test
    fun `a one-shot alarm moved to another time while it rings stays on after the time limit`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        alarms.upsert(alarms.get(id)!!.copy(time = LocalTime.of(7, 30)))
        clock.now = now.plus(RingLimits.MAX_RING)
        advanceTimeBy(RingLimits.MAX_RING.toMillis() + 1)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(true, alarms.get(id)?.enabled)
    }

    @Test
    fun `a second alarm whose deadline has passed is reported as missed and disabled if it is one-shot`() = runTest {
        val first = oneShot("First")
        val second = oneShot("Second")
        val controller = ringing(first)
        clock.now = deadline.plusSeconds(1)

        assertFalse(controller.startRinging(ScheduledRing(second, ringAt = deadline, deadline = deadline)))
        runCurrent()

        assertEquals(first, (controller.state.value as RingingState.Ringing).alarmId)
        assertEquals(listOf(ringFor(first)), serviceStarts)
        assertEquals(listOf(MissedAlarm(second, "Second", deadline, MissedReason.OTHER_ALARM_RINGING)), missed)
        assertEquals(false, alarms.get(second)?.enabled)
        assertEquals(true, alarms.get(first)?.enabled)
    }

    @Test
    fun `a second alarm that came early stays on for its deadline and is not reported`() = runTest {
        val first = oneShot("First")
        val second = oneShot("Second")
        val controller = ringing(first)

        assertFalse(controller.startRinging(ringFor(second)))
        runCurrent()

        assertEquals(first, (controller.state.value as RingingState.Ringing).alarmId)
        assertEquals(listOf(ringFor(first)), serviceStarts)
        assertTrue(missed.isEmpty())
        assertEquals(true, alarms.get(second)?.enabled)
    }

    @Test
    fun `the deadline broadcast of the alarm that already rings early counts as ringing`() = runTest {
        val id = oneShot()
        val controller = ringing(id)
        clock.now = deadline

        assertTrue(controller.startRinging(ScheduledRing(id, ringAt = deadline, deadline = deadline)))
        runCurrent()

        assertTrue(missed.isEmpty())
        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(listOf(ringFor(id)), serviceStarts)
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
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        settings.update { it.copy(headphonesOnly = true) }
        val controller = ringing(AlarmBroadcast.TEST_ALARM_ID)

        val state = controller.state.value as RingingState.Ringing
        assertEquals("Test alarm", state.label)
        assertEquals("the test alarm has no barcodes of its own", false, state.requiresBarcode)
        val spec = (controller.command.value as RingCommand.Play).spec
        assertEquals(Alarm.DEFAULT_MIN_RING_VOLUME_PERCENT, spec.minRingVolumePercent)
        assertEquals(Alarm.DEFAULT_VOLUME_RAMP_SECONDS, spec.volumeRampSeconds)
        assertTrue(spec.vibrate)
        assertNull(spec.ringtoneUri)
        assertTrue(spec.headphonesOnly)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
        assertEquals(now.minusSeconds(3600), sessions.current()?.startedAt)
    }

    @Test
    fun `a ring whose service cannot start is reported, and stays owed while its deadline is ahead`() = runTest {
        val id = oneShot()
        serviceStartFails = true
        val controller = controller()

        assertFalse(controller.startRinging(ringFor(id)))
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(1, reschedules)
    }

    @Test
    fun `a ring at the deadline whose service cannot start ends the one-shot alarm`() = runTest {
        val id = oneShot()
        serviceStartFails = true
        clock.now = deadline
        val controller = controller()

        assertFalse(controller.startRinging(ScheduledRing(id, ringAt = deadline, deadline = deadline)))
        runCurrent()

        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertEquals(false, alarms.get(id)?.enabled)
    }

    @Test
    fun `a ring cut off by a reboot comes back through the watchdog with its original start time`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val startedAt = now.minusSeconds(60)
        val record = RingingRecord(id, startedAt, startedAt, deadline)
        ringState.setRinging(record)
        val controller = controller()

        controller.recoverInterruptedRing()

        // Nothing rings from the process start itself.
        assertEquals(RingingState.Idle, controller.state.value)
        assertTrue(serviceStarts.isEmpty())
        assertTrue(missed.isEmpty())
        assertEquals(record, ringState.currentRinging())
        assertEquals(true, alarms.get(id)?.enabled)
        val (ring, at) = watchdogEntry!!
        assertEquals(ScheduledRing(id, startedAt, deadline), ring)
        assertTrue(at > now && at <= now.plusSeconds(10))

        controller.resumeRinging(ring)
        runCurrent()

        assertEquals(listOf(ring), serviceStarts)
        assertEquals(startedAt, (controller.state.value as RingingState.Ringing).startedAt)
        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(record, ringState.currentRinging())

        // The 30 minutes count from the first start.
        advanceTimeBy(RingLimits.MAX_RING.toMillis() - 60_000 + 1)
        runCurrent()
        assertEquals(MissedReason.RING_TIMED_OUT, missed.single().reason)
    }

    @Test
    fun `a ring record whose 30 minutes ran out is reported, cleared and never rung again`() = runTest {
        val id = oneShot()
        val startedAt = now.minusSeconds(40 * 60)
        ringState.setRinging(RingingRecord(id, startedAt, startedAt, deadline.minusSeconds(30 * 60)))
        val controller = controller()

        controller.recoverInterruptedRing()

        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertNull(ringState.currentRinging())
        assertNull(watchdogEntry)
        assertEquals(RingingState.Idle, controller.state.value)
        assertTrue(serviceStarts.isEmpty())
    }

    @Test
    fun `a ring record that can no longer resume ends a one-shot alarm only once its deadline has passed`() = runTest {
        val id = oneShot()
        val startedAt = now.minusSeconds(60)
        val record = RingingRecord(id, startedAt, startedAt, deadline)
        ringState.setRinging(record)
        ringState.markFired(id, deadline)
        watchdogRefuses = true
        val controller = controller()

        controller.recoverInterruptedRing()

        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_INTERRUPTED)), missed)
        assertNull(ringState.currentRinging())
        assertEquals("the deadline is still ahead", true, alarms.get(id)?.enabled)
        assertTrue(ringState.lastFired().isEmpty())

        ringState.setRinging(record)
        clock.now = deadline.plusSeconds(1)
        controller.recoverInterruptedRing()

        assertEquals(false, alarms.get(id)?.enabled)
    }

    @Test
    fun `the watchdog does nothing while its ring is live and nothing once the ring has ended`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        controller.resumeRinging(ringFor(id))
        runCurrent()
        assertEquals(listOf(ringFor(id)), serviceStarts)

        controller.dismissWithoutBarcode()
        controller.resumeRinging(ringFor(id))
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertNull(ringState.currentRinging())
    }

    @Test
    fun `a live ring keeps the watchdog a minute ahead and a dismissal cancels it`() = runTest {
        val id = oneShot()
        val controller = ringing(id)
        assertEquals(ringFor(id) to now.plusSeconds(60), watchdogEntry)

        clock.now = now.plusSeconds(45)
        advanceTimeBy(45_000)
        runCurrent()
        assertTrue(watchdogEntry!!.second >= now.plusSeconds(100))

        controller.dismissWithoutBarcode()
        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        runCurrent()
        assertNull(watchdogEntry)
    }

    @Test
    fun `a resumed ring rings although its alarm was switched off and keeps the barcode rule of its start`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = "Work", enabled = false))
        val record = RingingRecord(id, now.minusSeconds(60), now.minusSeconds(60), deadline, requiresBarcode = true)
        ringState.setRinging(record)
        val controller = controller()

        controller.resumeRinging(ScheduledRing(id, record.ringAt, deadline))
        runCurrent()

        // Every barcode was deleted in the meantime. That does not bring the plain button.
        assertEquals(true, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithTypedCode(controller.newFallbackCode()))
        assertNull(ringState.currentRinging())
    }

    @Test
    fun `a resumed ring whose alarm was deleted still takes the barcodes it started with`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        ringing(id)
        val record = ringState.currentRinging()!!
        assertEquals(listOf(toothpaste.normalised()), record.barcodes)

        // The process dies, and the alarm is deleted with its barcodes before the ring comes back.
        alarms.delete(id)
        assertTrue(barcodes.all(id).isEmpty())
        val controller = controller()
        controller.resumeRinging(ScheduledRing(id, record.ringAt, deadline))
        runCurrent()

        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
        assertNull(ringState.currentRinging())
    }

    @Test
    fun `barcodes that cannot be read at ring start are read again`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        failingBarcodeReads = 1

        val controller = ringing(id)

        assertEquals(true, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
    }

    @Test
    fun `barcodes that stay unreadable leave only the typed code`() = runTest {
        val id = oneShot()
        failingBarcodeReads = Int.MAX_VALUE

        val controller = ringing(id)

        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(true, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.REJECTED, controller.dismissWithBarcode(toothpaste))
        assertEquals(DismissResult.DISMISSED, controller.dismissWithTypedCode(controller.newFallbackCode()))
    }

    @Test
    fun `recovery leaves a ring started in this process alone`() = runTest {
        val id = oneShot()
        val controller = ringing(id)

        controller.recoverInterruptedRing()

        assertTrue(missed.isEmpty())
        assertTrue(controller.state.value is RingingState.Ringing)
        assertEquals(RingingRecord(id, now, now, deadline, requiresBarcode = false), ringState.currentRinging())
    }

    @Test
    fun `a redelivered service start resumes the persisted ring with its original start time`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
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

    @Test
    fun `opening the scanner quiets the same ring for the quiet period`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        val loud = controller.command.value as RingCommand.Play
        assertFalse(loud.quiet)
        assertNull(controller.quietUntil)

        controller.startQuietPeriod()

        assertEquals(loud.copy(quiet = true), controller.command.value)
        assertEquals(now.plus(RingLimits.QUIET_PERIOD), controller.quietUntil)
        assertEquals(true, (controller.state.value as RingingState.Ringing).requiresBarcode)
        assertEquals(RingingRecord(id, now, now, deadline, barcodes = listOf(toothpaste.normalised())), ringState.currentRinging())
        assertEquals(ringFor(id), watchdogEntry?.first)
    }

    @Test
    fun `the ring is loud again when the quiet period runs out`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.startQuietPeriod()

        advanceTimeBy(quietMillis - 1)
        runCurrent()
        assertTrue(controller.isQuiet)

        advanceTimeBy(2)
        runCurrent()
        assertFalse(controller.isQuiet)
        assertNull(controller.quietUntil)
        assertTrue(controller.state.value is RingingState.Ringing)
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
    }

    @Test
    fun `going back to the ringing screen makes the ring loud at once`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.startQuietPeriod()
        advanceTimeBy(10_000)

        controller.endQuietPeriod()

        assertFalse(controller.isQuiet)
        assertNull(controller.quietUntil)
        advanceTimeBy(quietMillis)
        runCurrent()
        assertFalse(controller.isQuiet)
        controller.endQuietPeriod()
        assertFalse("ending a period that is over changes nothing", controller.isQuiet)
    }

    @Test
    fun `opening the scanner again starts a fresh quiet period`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.startQuietPeriod()
        advanceTimeBy(40_000)
        clock.now = now.plusSeconds(40)

        controller.startQuietPeriod()

        assertEquals(now.plusSeconds(40).plus(RingLimits.QUIET_PERIOD), controller.quietUntil)
        // Past the end of the first period.
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(controller.isQuiet)
        advanceTimeBy(quietMillis - 30_000 + 1)
        runCurrent()
        assertFalse(controller.isQuiet)
    }

    @Test
    fun `a quiet period asked for while the alarm is loading applies once it plays`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = controller()
        controller.startRinging(ringFor(id))

        controller.startQuietPeriod()

        assertEquals(RingCommand.Wait, controller.command.value)
        assertEquals(now.plus(RingLimits.QUIET_PERIOD), controller.quietUntil)
        runCurrent()
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `a quiet ring can be dismissed and stays stopped when the period would have ended`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.startQuietPeriod()

        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        advanceTimeBy(quietMillis + 1)
        runCurrent()
        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertNull(ringState.currentRinging())
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals(1, reschedules)
    }

    @Test
    fun `the typed code dismisses a quiet ring`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        val code = controller.newFallbackCode()
        controller.startQuietPeriod()

        assertEquals(DismissResult.DISMISSED, controller.dismissWithTypedCode(code))
        assertEquals(RingCommand.Stop, controller.command.value)
    }

    @Test
    fun `the 30 minute limit ends a quiet ring`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        advanceTimeBy(RingLimits.MAX_RING.toMillis() - 30_000)
        controller.startQuietPeriod()
        assertTrue(controller.isQuiet)

        clock.now = now.plus(RingLimits.MAX_RING)
        advanceTimeBy(30_001)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_TIMED_OUT)), missed)
        assertNull(ringState.currentRinging())
        assertNull(watchdogEntry)
        advanceTimeBy(quietMillis)
        runCurrent()
        assertEquals(RingCommand.Stop, controller.command.value)
    }

    @Test
    fun `a ring picked up after a process death starts loud`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        ringing(id).startQuietPeriod()
        val record = ringState.currentRinging()!!

        // The process died. A new controller picks the ring up through the watchdog.
        val controller = controller()
        controller.resumeRinging(ScheduledRing(id, record.ringAt, record.deadline))
        runCurrent()

        assertFalse(controller.isQuiet)
        assertNull(controller.quietUntil)
    }

    @Test
    fun `quiet periods do nothing when nothing rings`() = runTest {
        val controller = controller()

        controller.startQuietPeriod()
        controller.endQuietPeriod()
        advanceTimeBy(quietMillis + 1)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
    }

    /** A ring for an accepted late option: 15 minutes after the 07:00 deadline. The clock is at the ring. */
    private suspend fun lateRingFor(alarmId: Long): ScheduledRing {
        val late = ScheduledRing(alarmId, ringAt = deadline.plusSeconds(15 * 60), deadline = deadline)
        clock.now = late.ringAt
        sessions.start(deadline.minusSeconds(6 * 3600))
        sessions.acceptLate(alarmId, deadline)
        // The receiver records the fire as the ring starts.
        ringState.markFired(alarmId, deadline)
        return late
    }

    @Test
    fun `a dismissed late ring switches the one-shot alarm off and ends the session, which still covers its deadline`() = runTest {
        val id = oneShot()
        val late = lateRingFor(id)
        val controller = controller()
        assertTrue(controller.startRinging(late))
        runCurrent()

        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())

        assertEquals(false, alarms.get(id)?.enabled)
        assertNull(sessions.current())
        assertEquals(mapOf(id to deadline), ringState.lastFired())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `a late ring that times out is over, because its deadline has passed`() = runTest {
        val id = oneShot()
        val late = lateRingFor(id)
        val controller = controller()
        controller.startRinging(late)
        runCurrent()

        clock.now = late.ringAt.plus(RingLimits.MAX_RING)
        advanceTimeBy(RingLimits.MAX_RING.toMillis() + 1)
        runCurrent()

        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_TIMED_OUT)), missed)
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals("the fired record stays, nothing is owed", mapOf(id to deadline), ringState.lastFired())
        assertEquals(deadline.minusSeconds(6 * 3600), sessions.current()?.startedAt)
    }

    @Test
    fun `a late ring that comes due during another ring is reported as missed`() = runTest {
        val first = oneShot("First")
        val second = oneShot("Second")
        val controller = ringing(first)
        val late = lateRingFor(second)

        assertFalse(controller.startRinging(late))
        runCurrent()

        assertEquals(first, (controller.state.value as RingingState.Ringing).alarmId)
        assertEquals(listOf(MissedAlarm(second, "Second", deadline, MissedReason.OTHER_ALARM_RINGING)), missed)
        assertEquals(false, alarms.get(second)?.enabled)
    }

    @Test
    fun `a late ring whose service cannot start ends the one-shot alarm`() = runTest {
        val id = oneShot()
        val late = lateRingFor(id)
        serviceStartFails = true
        val controller = controller()

        assertFalse(controller.startRinging(late))
        runCurrent()

        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals(mapOf(id to deadline), ringState.lastFired())
    }

    @Test
    fun `a late ring cut off by a process death comes back after its deadline`() = runTest {
        val id = oneShot()
        val late = lateRingFor(id)
        val record = RingingRecord(id, startedAt = late.ringAt, ringAt = late.ringAt, deadline = deadline, requiresBarcode = false)
        ringState.setRinging(record)
        clock.now = late.ringAt.plusSeconds(120)
        val controller = controller()

        controller.recoverInterruptedRing()

        assertEquals(late, watchdogEntry?.first)
        controller.resumeRinging(late)
        runCurrent()

        assertEquals(late.ringAt, (controller.state.value as RingingState.Ringing).startedAt)
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
        assertNull(sessions.current())
        assertEquals(false, alarms.get(id)?.enabled)
    }

    private val DefaultRingController.ringingState: RingingState.Ringing get() = state.value as RingingState.Ringing

    /** Fires the snooze entry of [alarmId] the way AlarmManager does, with the clock at its time. */
    private fun TestScope.fireSnooze(controller: DefaultRingController, alarmId: Long) {
        val (ring, at) = snoozeEntries.remove(alarmId)!!
        if (clock.now < at) clock.now = at
        controller.endSnooze(ring)
        runCurrent()
    }

    @Test
    fun `a snooze stops the tone and rings again after the alarm's snooze length`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = "Work", snoozeMinutes = 9))
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        val until = now.plusSeconds(9 * 60)

        assertEquals(SnoozeResult.SNOOZED, controller.snooze())

        assertEquals(RingCommand.Stop, controller.command.value)
        val snoozed = RingingState.Snoozed(id, "Work", deadline, until, snoozesLeft = 2, requiresBarcode = true)
        assertEquals(snoozed, controller.state.value)
        assertEquals(listOf(snoozed), controller.snoozed.value)
        assertEquals(ringFor(id) to until, snoozeEntries[id])
        assertNull(ringState.currentRinging())
        val keys = listOf(toothpaste.normalised())
        assertEquals(
            listOf(RingingRecord(id, now, now, deadline, barcodes = keys, snoozes = 1, snoozedUntil = until)),
            ringState.currentSnoozed(),
        )
        assertEquals(SnoozedAlarm(id, "Work", until), snoozeNotifications[id])
        assertNull("the watchdog is for a live ring", watchdogEntry)
        assertTrue(missed.isEmpty())
        assertEquals(1, reschedules)

        fireSnooze(controller, id)

        assertEquals(listOf(ringFor(id), ringFor(id)), serviceStarts)
        val again = controller.ringingState
        assertEquals(until, again.startedAt)
        assertEquals(2, again.snoozesLeft)
        assertEquals(9, again.snoozeMinutes)
        assertTrue(again.requiresBarcode)
        assertFalse(controller.isQuiet)
        assertTrue(controller.snoozed.value.isEmpty())
        assertTrue(ringState.currentSnoozed().isEmpty())
        assertEquals(RingingRecord(id, until, now, deadline, barcodes = keys, snoozes = 1), ringState.currentRinging())
        assertTrue(snoozeNotifications.isEmpty())
        assertEquals(ringFor(id), watchdogEntry?.first)
        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
        assertEquals(RingingState.Idle, controller.state.value)
    }

    @Test
    fun `each snooze uses one up, and once they are used up only dismissal is left`() = runTest {
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0), snoozeLimit = 2))
        val controller = ringing(id)
        assertEquals(2, controller.ringingState.snoozesLeft)
        assertEquals(2, (controller.command.value as RingCommand.Play).spec.snoozesLeft)

        controller.snooze()
        assertEquals(1, (controller.state.value as RingingState.Snoozed).snoozesLeft)
        fireSnooze(controller, id)
        assertEquals(1, controller.ringingState.snoozesLeft)
        assertEquals(1, (controller.command.value as RingCommand.Play).spec.snoozesLeft)

        controller.snooze()
        assertEquals(0, (controller.state.value as RingingState.Snoozed).snoozesLeft)
        fireSnooze(controller, id)

        assertEquals(0, controller.ringingState.snoozesLeft)
        assertEquals(0, (controller.command.value as RingCommand.Play).spec.snoozesLeft)
        assertEquals(2, ringState.currentRinging()?.snoozes)
        assertEquals(SnoozeResult.REJECTED, controller.snooze())
        assertTrue(controller.command.value is RingCommand.Play)
        assertTrue(snoozeEntries.isEmpty())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())
    }

    @Test
    fun `a snooze is rejected at the limit, while the alarm loads and when its entry cannot be set`() = runTest {
        val off = alarms.upsert(Alarm(time = LocalTime.of(7, 0), snoozeLimit = 0))
        val controller = ringing(off)
        assertEquals(0, controller.ringingState.snoozesLeft)
        assertEquals(SnoozeResult.REJECTED, controller.snooze())
        assertTrue(controller.command.value is RingCommand.Play)
        assertTrue(snoozeEntries.isEmpty())
        controller.dismissWithoutBarcode()

        val id = oneShot()
        controller.startRinging(ringFor(id))
        assertEquals("not loaded yet", 0, controller.ringingState.snoozesLeft)
        assertEquals(SnoozeResult.REJECTED, controller.snooze())
        runCurrent()

        snoozeEntriesRefused = true
        assertEquals(SnoozeResult.REJECTED, controller.snooze())
        assertTrue(controller.command.value is RingCommand.Play)
        assertEquals(id, ringState.currentRinging()?.alarmId)
        assertTrue(ringState.currentSnoozed().isEmpty())

        controller.dismissWithoutBarcode()
        assertEquals(SnoozeResult.NOT_RINGING, controller.snooze())
    }

    @Test
    fun `a snooze neither ends the session nor disables a one-shot alarm, and the dismissal after it does both`() = runTest {
        val id = oneShot()
        sessions.start(now.minusSeconds(8 * 3600))
        ringState.markFired(id, deadline)
        val controller = ringing(id)

        controller.snooze()
        // The deadline passes while the ring is snoozed.
        clock.now = deadline.plusSeconds(60)

        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(now.minusSeconds(8 * 3600), sessions.current()?.startedAt)
        assertEquals(mapOf(id to deadline), ringState.lastFired())
        assertTrue(missed.isEmpty())

        fireSnooze(controller, id)
        assertEquals(true, alarms.get(id)?.enabled)
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())

        assertEquals(false, alarms.get(id)?.enabled)
        assertNull(sessions.current())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `each ringing stretch gets its own 30 minutes, and one that runs out ends the ring as missed`() = runTest {
        val id = oneShot()
        val controller = ringing(id)
        advanceTimeBy(Duration.ofMinutes(25).toMillis())
        clock.now = now.plus(Duration.ofMinutes(25))
        controller.snooze()
        val until = clock.now.plus(Duration.ofMinutes(5))

        advanceTimeBy(Duration.ofMinutes(5).toMillis())
        fireSnooze(controller, id)
        assertEquals(until, controller.ringingState.startedAt)

        // 59 minutes after the first ring, 29 into this stretch.
        advanceTimeBy(Duration.ofMinutes(29).toMillis())
        runCurrent()
        assertTrue(controller.state.value is RingingState.Ringing)
        assertTrue(missed.isEmpty())

        clock.now = until.plus(RingLimits.MAX_RING)
        advanceTimeBy(Duration.ofMinutes(1).toMillis() + 1)
        runCurrent()

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_TIMED_OUT)), missed)
        assertNull(ringState.currentRinging())
        assertTrue(ringState.currentSnoozed().isEmpty())
        assertEquals("the deadline has passed", false, alarms.get(id)?.enabled)
    }

    @Test
    fun `a snooze ends the quiet period`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.startQuietPeriod()

        controller.snooze()
        assertEquals(RingCommand.Stop, controller.command.value)
        fireSnooze(controller, id)

        assertFalse(controller.isQuiet)
        assertNull(controller.quietUntil)
        controller.startQuietPeriod()
        assertTrue(controller.isQuiet)
    }

    @Test
    fun `a snoozed ring with barcodes is dismissed now only with one of them or the typed code`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        sessions.start(now.minusSeconds(8 * 3600))
        val controller = ringing(id)
        controller.snooze()

        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.REJECTED, controller.dismissWithBarcode(ScannedCode(BarcodeFormat.QR_CODE, "mug")))
        assertEquals(DismissResult.REJECTED, controller.dismissWithTypedCode(""))
        val code = controller.newFallbackCode()
        assertEquals(DismissResult.DISMISSED, controller.dismissWithTypedCode(code))

        assertEquals(RingingState.Idle, controller.state.value)
        assertTrue(controller.snoozed.value.isEmpty())
        assertTrue(ringState.currentSnoozed().isEmpty())
        assertTrue(snoozeEntries.isEmpty())
        assertTrue(snoozeNotifications.isEmpty())
        assertEquals(false, alarms.get(id)?.enabled)
        assertNull(sessions.current())
        assertTrue(missed.isEmpty())

        // An entry that was already on its way finds nothing to ring.
        clock.now = now.plusSeconds(5 * 60)
        controller.endSnooze(ringFor(id))
        runCurrent()
        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(RingCommand.Stop, controller.command.value)
        assertNull(ringState.currentRinging())
        assertTrue(missed.isEmpty())
    }

    @Test
    fun `a snoozed ring is dismissed now with a barcode it started with`() = runTest {
        val id = oneShot()
        val barcodeId = barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        val controller = ringing(id)
        controller.snooze()
        barcodes.delete(barcodeId)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
        assertEquals(RingingState.Idle, controller.state.value)
    }

    @Test
    fun `a snoozed ring without barcodes is dismissed now with a plain tap`() = runTest {
        val id = oneShot()
        val controller = ringing(id)
        controller.snooze()
        assertEquals(false, (controller.state.value as RingingState.Snoozed).requiresBarcode)

        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())

        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals(false, alarms.get(id)?.enabled)
        assertEquals(DismissResult.NOT_RINGING, controller.dismissWithoutBarcode())
    }

    @Test
    fun `other alarms ring while one is snoozed, and a snooze due meanwhile rings right after`() = runTest {
        val first = oneShot("First")
        val second = oneShot("Second")
        val controller = ringing(first)
        controller.snooze()
        val until = now.plusSeconds(5 * 60)

        clock.now = now.plusSeconds(60)
        val secondRing = ScheduledRing(second, ringAt = clock.now, deadline = deadline)
        assertTrue(controller.startRinging(secondRing))
        runCurrent()
        assertEquals(second, controller.ringingState.alarmId)
        assertEquals(listOf(first), controller.snoozed.value.map { it.alarmId })

        // The snooze of the first comes due during the second ring. It waits.
        fireSnooze(controller, first)
        assertEquals(second, controller.ringingState.alarmId)
        assertEquals(listOf(ringFor(first), secondRing), serviceStarts)
        assertTrue(missed.isEmpty())
        assertEquals(listOf(first), ringState.currentSnoozed().map { it.alarmId })
        assertEquals(until, ringState.currentSnoozed().single().snoozedUntil)

        clock.now = until.plusSeconds(90)
        controller.dismissWithoutBarcode()

        // Its entry is set to fire at once, so it starts like any other ring.
        assertEquals(ringFor(first) to clock.now, snoozeEntries[first])
        assertEquals(first, (controller.state.value as RingingState.Snoozed).alarmId)
        fireSnooze(controller, first)

        assertEquals(first, controller.ringingState.alarmId)
        assertEquals(clock.now, controller.ringingState.startedAt)
        assertEquals(2, controller.ringingState.snoozesLeft)
        assertTrue(missed.isEmpty())
        assertEquals(true, alarms.get(first)?.enabled)
    }

    @Test
    fun `two snoozed alarms show the one due first and each rings at its own time`() = runTest {
        val first = oneShot("First")
        val second = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = "Second", snoozeMinutes = 2))
        val controller = ringing(first)
        controller.snooze()
        clock.now = now.plusSeconds(60)
        controller.startRinging(ScheduledRing(second, ringAt = clock.now, deadline = deadline))
        runCurrent()
        controller.snooze()

        assertEquals(listOf(second, first), controller.snoozed.value.map { it.alarmId })
        assertEquals(second, (controller.state.value as RingingState.Snoozed).alarmId)
        assertEquals(setOf(first, second), snoozeNotifications.keys)

        fireSnooze(controller, second)
        assertEquals(second, controller.ringingState.alarmId)
        assertEquals(listOf(first), controller.snoozed.value.map { it.alarmId })
        controller.dismissWithoutBarcode()
        assertEquals(first, (controller.state.value as RingingState.Snoozed).alarmId)

        fireSnooze(controller, first)
        assertEquals(first, controller.ringingState.alarmId)
    }

    @Test
    fun `a snooze survives a process death and rings at its time`() = runTest {
        val id = oneShot()
        barcodes.add(id, Barcode.from("Toothpaste", toothpaste))
        ringing(id).snooze()
        val record = ringState.currentSnoozed().single()
        val until = record.snoozedUntil!!
        // A reboot wipes the alarm clock entries and the notifications.
        snoozeEntries.clear()
        snoozeNotifications.clear()
        clock.now = now.plusSeconds(60)

        val controller = controller()
        controller.recoverInterruptedRing()

        assertEquals(ringFor(id) to until, snoozeEntries[id])
        assertEquals(RingingState.Snoozed(id, "Work", deadline, until, snoozesLeft = 2, requiresBarcode = true), controller.state.value)
        assertEquals(SnoozedAlarm(id, "Work", until), snoozeNotifications[id])
        assertEquals(listOf(ringFor(id)), serviceStarts)
        assertTrue(missed.isEmpty())

        fireSnooze(controller, id)

        assertEquals(until, controller.ringingState.startedAt)
        assertEquals(2, controller.ringingState.snoozesLeft)
        assertEquals(DismissResult.REJECTED, controller.dismissWithoutBarcode())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithBarcode(toothpaste))
    }

    @Test
    fun `a snooze whose time passed while the process was dead rings a few seconds after the start`() = runTest {
        val id = oneShot()
        ringing(id).snooze()
        snoozeEntries.clear()
        clock.now = now.plusSeconds(20 * 60)

        val controller = controller()
        controller.recoverInterruptedRing()

        val (ring, at) = snoozeEntries.getValue(id)
        assertEquals(ringFor(id), ring)
        assertTrue(at > clock.now && at <= clock.now.plusSeconds(10))
        assertTrue(missed.isEmpty())
        fireSnooze(controller, id)
        assertEquals(id, controller.ringingState.alarmId)
    }

    @Test
    fun `a snooze more than 30 minutes overdue after a process death is reported as missed`() = runTest {
        val id = oneShot()
        ringing(id).snooze()
        snoozeEntries.clear()
        clock.now = now.plusSeconds(5 * 60).plus(RingLimits.MAX_RING).plusSeconds(1)

        val controller = controller()
        controller.recoverInterruptedRing()

        assertEquals(listOf(MissedAlarm(id, "Work", deadline, MissedReason.RING_INTERRUPTED)), missed)
        assertTrue(snoozeEntries.isEmpty())
        assertTrue(ringState.currentSnoozed().isEmpty())
        assertTrue(snoozeNotifications.isEmpty())
        assertEquals(RingingState.Idle, controller.state.value)
        assertEquals("its deadline has passed", false, alarms.get(id)?.enabled)
    }

    @Test
    fun `a snooze whose entry cannot be set again after a reboot is reported, and stays owed while its deadline is ahead`() = runTest {
        val id = oneShot()
        ringState.markFired(id, deadline)
        ringing(id).snooze()
        snoozeEntries.clear()
        snoozeEntriesRefused = true

        val controller = controller()
        controller.recoverInterruptedRing()

        assertEquals(MissedReason.RING_INTERRUPTED, missed.single().reason)
        assertTrue(ringState.currentSnoozed().isEmpty())
        assertEquals(true, alarms.get(id)?.enabled)
        assertTrue("the deadline rings", ringState.lastFired().isEmpty())
    }

    @Test
    fun `the test alarm snoozes with the defaults and its dismissal leaves the session alone`() = runTest {
        sessions.start(now.minusSeconds(3600))
        val controller = ringing(AlarmBroadcast.TEST_ALARM_ID)
        assertEquals(Alarm.DEFAULT_SNOOZE_MINUTES, controller.ringingState.snoozeMinutes)
        assertEquals(Alarm.DEFAULT_SNOOZE_LIMIT, controller.ringingState.snoozesLeft)

        assertEquals(SnoozeResult.SNOOZED, controller.snooze())
        assertEquals(SnoozedAlarm(AlarmBroadcast.TEST_ALARM_ID, "Test alarm", now.plusSeconds(5 * 60)), snoozeNotifications.values.single())
        assertEquals(DismissResult.DISMISSED, controller.dismissWithoutBarcode())

        assertEquals(now.minusSeconds(3600), sessions.current()?.startedAt)
        assertEquals(RingingState.Idle, controller.state.value)
    }
}
