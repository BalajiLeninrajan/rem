package dev.balaji.rem.ui.home

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.schedule.AlarmBroadcast
import dev.balaji.rem.ui.system.SystemChecks
import dev.balaji.rem.ui.testing.FakeAlarmScheduler
import dev.balaji.rem.ui.testing.FakeRingController
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.MutableClock
import dev.balaji.rem.ui.testing.keepCollecting
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val zone = ZoneId.of("America/Toronto")
    private val clock = MutableClock(at("2026-01-14T23:00"), zone)

    private val alarms = InMemoryAlarmRepository()
    private val barcodes = InMemoryBarcodeRepository()
    private var settings = InMemorySettingsRepository(onboardingDone = true)
    private val sessions = InMemorySleepSessionRepository()
    private val ringController = FakeRingController()
    private val scheduler by lazy { FakeAlarmScheduler(alarms, sessions, settings, clock) }

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun TestScope.viewModel(): HomeViewModel =
        HomeViewModel(alarms, barcodes, settings, sessions, scheduler, ringController, clock).also {
            keepCollecting(it.state)
            advanceUntilIdle()
        }

    /** Stands in for the real scheduler, which replans by itself after every repository change. */
    private suspend fun TestScope.replan() {
        advanceUntilIdle()
        scheduler.rescheduleAll()
        advanceUntilIdle()
    }

    @Test
    fun `with no alarms there is nothing to show and no sleep control`() = runTest {
        val state = viewModel().state.value

        assertFalse(state.loading)
        assertTrue(state.alarms.isEmpty())
        assertEquals(SleepState.Hidden, state.sleep)
        assertTrue(state.noBarcodes)
        assertEquals(0, state.reliabilityProblems)
        assertFalse(state.onboardingNeeded)
        assertEquals(zone, state.zone)
    }

    @Test
    fun `alarms show their planned ring and the sleep control previews tonight`() = runTest {
        val viewModel = viewModel()
        val on = alarms.upsert(Alarm(time = LocalTime.of(7, 0), label = "Work", repeatDays = RepeatDays.WEEKDAYS))
        val off = alarms.upsert(Alarm(time = LocalTime.of(9, 30), enabled = false))
        replan()

        val state = viewModel.state.value
        assertEquals(listOf(on, off), state.alarms.map { it.alarm.id })

        val ring = state.alarms[0].nextRing!!
        assertEquals(at("2026-01-15T07:00"), ring.ringAt)
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), ring.explanation)
        assertNull(state.alarms[1].nextRing)

        val preview = (state.sleep as SleepState.Ready).preview!!
        assertEquals(on, preview.alarmId)
        assertEquals(at("2026-01-15T06:45"), preview.ringAt)
        assertEquals(at("2026-01-15T07:00"), preview.deadline)
        assertEquals(WakeExplanation.Cycles(cycles = 5, minutesBeforeDeadline = 15), preview.explanation)
    }

    @Test
    fun `the preview is empty when no alarm is due within a session`() = runTest {
        val viewModel = viewModel()
        // 23:00 on Wednesday. The next Friday 22:00 is 47 hours away, a session covers 16.
        alarms.upsert(Alarm(time = LocalTime.of(22, 0), repeatDays = setOf(java.time.DayOfWeek.FRIDAY)))
        replan()

        assertEquals(SleepState.Ready(preview = null), viewModel.state.value.sleep)
    }

    @Test
    fun `going to sleep starts a session and shows tonight's ring until it is cancelled`() = runTest {
        val viewModel = viewModel()
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        replan()

        viewModel.goToSleep()
        replan()

        val active = viewModel.state.value.sleep as SleepState.Active
        assertEquals(clock.instant(), active.startedAt)
        assertEquals(id, active.tonight?.alarmId)
        assertEquals(at("2026-01-15T06:45"), active.tonight?.ringAt)
        assertEquals(WakeExplanation.Cycles(5, 15), active.tonight?.explanation)
        assertEquals(active.tonight, viewModel.state.value.alarms.single().nextRing)

        viewModel.endSleep()
        replan()

        assertNull(sessions.current())
        assertTrue(viewModel.state.value.sleep is SleepState.Ready)
    }

    @Test
    fun `a session that covers no alarm says so`() = runTest {
        sessions.start(clock.instant())
        val viewModel = viewModel()

        assertEquals(SleepState.Active(clock.instant(), tonight = null), viewModel.state.value.sleep)
    }

    @Test
    fun `a session older than 16 hours is not shown as active`() = runTest {
        sessions.start(clock.instant().minusSeconds(17 * 3600))
        val viewModel = viewModel()
        alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        replan()

        assertTrue(viewModel.state.value.sleep is SleepState.Ready)
    }

    @Test
    fun `switching the only alarm off stores it and hides the sleep control`() = runTest {
        val viewModel = viewModel()
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        replan()

        viewModel.setEnabled(id, false)
        replan()

        assertEquals(false, alarms.get(id)?.enabled)
        val state = viewModel.state.value
        assertNull(state.alarms.single().nextRing)
        assertEquals(SleepState.Hidden, state.sleep)

        viewModel.setEnabled(id, true)
        replan()

        assertEquals(true, alarms.get(id)?.enabled)
        assertTrue(viewModel.state.value.sleep is SleepState.Ready)
    }

    @Test
    fun `delete offers undo and undo restores the same alarm`() = runTest {
        val viewModel = viewModel()
        val alarm = Alarm(time = LocalTime.of(7, 0), label = "Work", repeatDays = RepeatDays.WEEKDAYS, headphonesOnly = true)
        val stored = alarm.copy(id = alarms.upsert(alarm))
        replan()

        viewModel.delete(stored.id)
        runCurrent()

        assertTrue(alarms.all().isEmpty())
        assertTrue(viewModel.state.value.alarms.isEmpty())
        assertEquals(stored, viewModel.state.value.recentlyDeleted)

        viewModel.undoDelete()
        advanceUntilIdle()

        assertEquals(listOf(stored), alarms.all())
        assertNull(viewModel.state.value.recentlyDeleted)
    }

    @Test
    fun `the undo offer ends by itself, so it cannot come back after leaving the screen`() = runTest {
        val viewModel = viewModel()
        val stored = Alarm(time = LocalTime.of(7, 0)).let { it.copy(id = alarms.upsert(it)) }

        viewModel.delete(stored.id)
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(stored, viewModel.state.value.recentlyDeleted)

        advanceTimeBy(2_000)
        runCurrent()
        assertNull(viewModel.state.value.recentlyDeleted)
        assertTrue(alarms.all().isEmpty())
    }

    @Test
    fun `an expired undo only clears its own offer`() = runTest {
        val viewModel = viewModel()
        val first = Alarm(time = LocalTime.of(7, 0)).let { it.copy(id = alarms.upsert(it)) }
        val second = Alarm(time = LocalTime.of(8, 0)).let { it.copy(id = alarms.upsert(it)) }

        viewModel.delete(first.id)
        viewModel.delete(second.id)
        runCurrent()
        viewModel.onUndoExpired(first)
        runCurrent()
        assertEquals(second, viewModel.state.value.recentlyDeleted)

        viewModel.onUndoExpired(second)
        runCurrent()
        assertNull(viewModel.state.value.recentlyDeleted)
        assertTrue(alarms.all().isEmpty())
    }

    @Test
    fun `warnings follow the barcodes and the system checks`() = runTest {
        val viewModel = viewModel()

        viewModel.refresh(SystemChecks(notificationsEnabled = false, cameraGranted = false))
        advanceUntilIdle()
        // The camera does not count while there is no barcode to scan.
        assertEquals(1, viewModel.state.value.reliabilityProblems)
        assertTrue(viewModel.state.value.noBarcodes)

        barcodes.add(Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931"))
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.reliabilityProblems)
        assertFalse(viewModel.state.value.noBarcodes)

        viewModel.refresh(SystemChecks())
        advanceUntilIdle()
        assertEquals(0, viewModel.state.value.reliabilityProblems)
    }

    @Test
    fun `refresh moves now forward`() = runTest {
        val viewModel = viewModel()
        clock.now = at("2026-01-15T00:30")

        viewModel.refresh(SystemChecks())
        advanceUntilIdle()

        assertEquals(at("2026-01-15T00:30"), viewModel.state.value.now)
    }

    @Test
    fun `onboarding is asked for once on first run`() = runTest {
        settings = InMemorySettingsRepository(onboardingDone = false)
        val viewModel = viewModel()
        assertTrue(viewModel.state.value.onboardingNeeded)

        viewModel.onOnboardingOpened()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.onboardingNeeded)
    }

    @Test
    fun `the ringing alarm is marked`() = runTest {
        val viewModel = viewModel()
        val ringing = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        val other = alarms.upsert(Alarm(time = LocalTime.of(8, 0)))
        replan()

        ringController.state.value = RingingState.Ringing(
            alarmId = ringing,
            label = "",
            startedAt = clock.instant(),
            deadline = at("2026-01-15T07:00"),
            requiresBarcode = false,
        )
        advanceUntilIdle()

        val rows = viewModel.state.value.alarms.associate { it.alarm.id to it.ringing }
        assertEquals(mapOf(ringing to true, other to false), rows)
        assertTrue(viewModel.state.value.ringing)
    }

    @Test
    fun `a ringing test alarm has no card but is still reported`() = runTest {
        val viewModel = viewModel()
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        replan()
        assertFalse(viewModel.state.value.ringing)

        ringController.state.value = RingingState.Ringing(
            alarmId = AlarmBroadcast.TEST_ALARM_ID,
            label = "",
            startedAt = clock.instant(),
            deadline = clock.instant(),
            requiresBarcode = false,
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.ringing)
        assertEquals(mapOf(id to false), viewModel.state.value.alarms.associate { it.alarm.id to it.ringing })
    }
}
