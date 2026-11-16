package dev.balaji.rem.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.RepeatDays
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ring.MissedAlarm
import dev.balaji.rem.ring.MissedReason
import dev.balaji.rem.ui.AppLinks
import dev.balaji.rem.ui.navigation.AlarmEditorRoute
import dev.balaji.rem.ui.navigation.ReliabilityRoute
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmManagerSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    private val toronto = ZoneId.of("America/Toronto")

    // A Wednesday evening.
    private val clock = MutableClock(at("2026-01-14T23:00"), toronto)

    private val alarms = InMemoryAlarmRepository()
    private val sessions = InMemorySleepSessionRepository()
    private val settings = InMemorySettingsRepository()
    private val ringState = InMemoryRingStateStore()
    private val notifier = RecordingNotifier()

    private val sevenDaily = Alarm(time = LocalTime.of(7, 0), label = "work", repeatDays = RepeatDays.EVERY_DAY)
    private val sevenOnce = Alarm(time = LocalTime.of(7, 0), label = "flight")

    @Before
    fun allowExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun at(local: String, zone: ZoneId = toronto): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun TestScope.scheduler() =
        AlarmManagerScheduler(context, alarms, sessions, settings, ringState, notifier, clock, backgroundScope)
            .also { runCurrent() }

    /** What AlarmManager holds, read back from the PendingIntents, ordered by alarm id. */
    private fun alarmClocks(): List<ScheduledRing> =
        shadowOf(alarmManager).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent.scheduledRingOrNull()!! }
            .sortedBy { it.alarmId }

    @Test
    fun `an enabled alarm becomes one alarm clock entry at its deadline`() = runTest {
        val scheduler = scheduler()

        val id = alarms.upsert(sevenDaily)
        alarms.upsert(sevenDaily.copy(time = LocalTime.of(6, 0), enabled = false))
        runCurrent()

        val ring = ScheduledRing(id, at("2026-01-15T07:00"), at("2026-01-15T07:00"))
        assertEquals(listOf(ring), alarmClocks())
        assertEquals(listOf(ring), ringState.scheduled())
        assertEquals(ring, scheduler.plan.value.next?.toScheduledRing())

        val next = alarmManager.nextAlarmClock
        assertEquals(ring.ringAt.toEpochMilli(), next.triggerTime)
        assertEquals(AlarmEditorRoute(id), AppLinks.routeFor(shadowOf(next.showIntent).savedIntent))
    }

    @Test
    fun `each alarm gets its own entry and its own show intent`() = runTest {
        scheduler()

        val late = alarms.upsert(sevenDaily)
        val early = alarms.upsert(sevenOnce.copy(time = LocalTime.of(6, 0)))
        runCurrent()

        assertEquals(listOf(late, early), alarmClocks().map { it.alarmId })
        val next = alarmManager.nextAlarmClock
        assertEquals(at("2026-01-15T06:00").toEpochMilli(), next.triggerTime)
        assertEquals(AlarmEditorRoute(early), AppLinks.routeFor(shadowOf(next.showIntent).savedIntent))
    }

    @Test
    fun `starting a sleep session moves the ring to a cycle boundary and cancelling moves it back`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        sessions.start(clock.instant())
        runCurrent()

        assertEquals(listOf(ScheduledRing(id, at("2026-01-15T06:45"), deadline)), alarmClocks())
        assertEquals(at("2026-01-15T06:45").toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)
        assertEquals(WakeExplanation.Cycles(5, 15), scheduler.plan.value.forAlarm(id)?.explanation)

        sessions.end()
        runCurrent()

        assertEquals(listOf(ScheduledRing(id, deadline, deadline)), alarmClocks())
        assertEquals(deadline.toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)
    }

    @Test
    fun `a settings change moves the ring`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        sessions.start(clock.instant())
        runCurrent()

        settings.update { it.copy(fallAsleepMinutes = 30) }
        runCurrent()

        assertEquals(WakeExplanation.Cycles(5, 0), scheduler.plan.value.forAlarm(id)?.explanation)
        assertEquals(at("2026-01-15T07:00").toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)
    }

    @Test
    fun `switching an alarm off or deleting it cancels its entry`() = runTest {
        val scheduler = scheduler()
        val first = alarms.upsert(sevenDaily)
        val second = alarms.upsert(sevenDaily.copy(time = LocalTime.of(8, 0)))
        runCurrent()
        assertEquals(listOf(first, second), alarmClocks().map { it.alarmId })

        alarms.setEnabled(first, false)
        runCurrent()
        assertEquals(listOf(second), alarmClocks().map { it.alarmId })
        assertNull(scheduler.plan.value.forAlarm(first))

        alarms.delete(second)
        runCurrent()
        assertTrue(alarmClocks().isEmpty())
        assertNull(alarmManager.nextAlarmClock)
        assertTrue(ringState.scheduled().isEmpty())
    }

    @Test
    fun `an entry left behind by an earlier process is cancelled when its alarm is off`() = runTest {
        val id = alarms.upsert(sevenDaily.copy(enabled = false))
        AlarmClocks(context).set(ScheduledRing(id, at("2026-01-15T07:00"), at("2026-01-15T07:00")))

        scheduler()

        assertTrue(alarmClocks().isEmpty())
    }

    @Test
    fun `an alarm that rang early is not set again for the same deadline`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        sessions.start(clock.instant())
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        clock.now = at("2026-01-15T06:45:01")
        ringState.markFired(id, deadline)
        scheduler.rescheduleAll()

        val tomorrow = at("2026-01-16T07:00")
        assertEquals(listOf(ScheduledRing(id, tomorrow, tomorrow)), alarmClocks())
        assertTrue(notifier.missed.isEmpty())
    }

    @Test
    fun `an alarm edited after it rang early rings again at its new time`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        sessions.start(clock.instant())
        runCurrent()
        clock.now = at("2026-01-15T06:50")
        ringState.markFired(id, at("2026-01-15T07:00"))
        scheduler.rescheduleAll()

        alarms.upsert(sevenDaily.copy(id = id, time = LocalTime.of(6, 55)))
        runCurrent()

        val today = at("2026-01-15T06:55")
        assertEquals(listOf(ScheduledRing(id, today, today)), alarmClocks())
    }

    @Test
    fun `a deadline that passed without a fire is reported once and the next occurrence is set`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()

        clock.now = at("2026-01-15T09:00")
        scheduler.rescheduleAll()
        scheduler.rescheduleAll()

        assertEquals(listOf(MissedAlarm(id, "work", at("2026-01-15T07:00"), MissedReason.NOT_FIRED)), notifier.missed)
        val tomorrow = at("2026-01-16T07:00")
        assertEquals(listOf(ScheduledRing(id, tomorrow, tomorrow)), alarmClocks())
    }

    @Test
    fun `a missed one-shot alarm is switched off instead of moving to the next day`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenOnce)
        runCurrent()

        clock.now = at("2026-01-15T09:00")
        scheduler.rescheduleAll()
        runCurrent()

        assertEquals(listOf(MissedAlarm(id, "flight", at("2026-01-15T07:00"), MissedReason.NOT_FIRED)), notifier.missed)
        assertEquals(false, alarms.get(id)?.enabled)
        assertTrue(alarmClocks().isEmpty())
        assertNull(scheduler.plan.value.forAlarm(id))
    }

    @Test
    fun `a ring that fired is not reported when its deadline has passed`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()

        ringState.markFired(id, at("2026-01-15T07:00"))
        clock.now = at("2026-01-15T09:00")
        scheduler.rescheduleAll()

        assertTrue(notifier.missed.isEmpty())
    }

    @Test
    fun `a deadline under a minute old is kept for a later check and reported then`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        clock.now = at("2026-01-15T07:00:30")
        scheduler.rescheduleAll()

        assertTrue(notifier.missed.isEmpty())
        assertTrue(ScheduledRing(id, deadline, deadline) in ringState.scheduled())

        clock.now = at("2026-01-15T07:02")
        scheduler.rescheduleAll()

        assertEquals(listOf(deadline), notifier.missed.map { it.deadline })
        assertFalse(ScheduledRing(id, deadline, deadline) in ringState.scheduled())
    }

    @Test
    fun `an alarm that was switched off after it was scheduled is not reported`() = runTest {
        val id = alarms.upsert(sevenDaily.copy(enabled = false))
        ringState.setScheduled(listOf(ScheduledRing(id, at("2026-01-15T07:00"), at("2026-01-15T07:00"))))
        clock.now = at("2026-01-15T09:00")

        scheduler()

        assertTrue(notifier.missed.isEmpty())
        assertTrue(ringState.scheduled().isEmpty())
    }

    @Test
    fun `a ring that just became due is not replaced until its broadcast had time to arrive`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()
        val deadline = at("2026-01-15T07:00")
        val due = ScheduledRing(id, deadline, deadline)

        clock.now = deadline.plusMillis(500)
        val reschedule = launch { scheduler.rescheduleAll() }
        runCurrent()

        assertTrue(reschedule.isActive)
        assertEquals(listOf(due), alarmClocks())

        // The receiver records the fire while the reschedule waits.
        ringState.markFired(id, deadline)
        advanceUntilIdle()

        val tomorrow = at("2026-01-16T07:00")
        assertTrue(reschedule.isCompleted)
        assertEquals(listOf(ScheduledRing(id, tomorrow, tomorrow)), alarmClocks())
        assertTrue(notifier.missed.isEmpty())
    }

    @Test
    fun `a one-shot alarm leaves the plan while it rings`() = runTest {
        val scheduler = scheduler()
        val once = alarms.upsert(sevenOnce)
        val daily = alarms.upsert(sevenDaily.copy(time = LocalTime.of(8, 0)))
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        clock.now = deadline.plusSeconds(5)
        ringState.markFired(once, deadline)
        ringState.setRinging(RingingRecord(once, startedAt = deadline, ringAt = deadline, deadline = deadline))
        runCurrent()

        assertEquals(listOf(daily), alarmClocks().map { it.alarmId })
        assertNull(scheduler.plan.value.forAlarm(once))
    }

    @Test
    fun `a session older than 16 hours is ended`() = runTest {
        sessions.start(clock.instant().minusSeconds(17 * 3600))
        val id = alarms.upsert(sevenDaily)

        val scheduler = scheduler()

        assertNull(sessions.current())
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)
    }

    @Test
    fun `a timezone change keeps the wall clock time`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()

        val vancouver = ZoneId.of("America/Vancouver")
        clock.currentZone = vancouver
        scheduler.rescheduleAll()

        val deadline = at("2026-01-15T07:00", vancouver)
        assertEquals(listOf(ScheduledRing(id, deadline, deadline)), alarmClocks())
        assertTrue(notifier.missed.isEmpty())
    }

    @Test
    fun `nothing is set while exact alarms are not allowed, and the plan is still published`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = scheduler()

        val id = alarms.upsert(sevenDaily)
        runCurrent()
        scheduler.scheduleTestAlarm()

        assertTrue(alarmClocks().isEmpty())
        assertEquals(at("2026-01-15T07:00"), scheduler.plan.value.forAlarm(id)?.ringAt)

        // The permission broadcast leads to a reschedule, which then sets the alarm.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.rescheduleAll()

        assertEquals(listOf(id), alarmClocks().map { it.alarmId })
    }

    @Test
    fun `the test alarm rings in one minute and a reschedule leaves it alone`() = runTest {
        val scheduler = scheduler()

        val ringAt = scheduler.scheduleTestAlarm()

        val test = ScheduledRing(AlarmBroadcast.TEST_ALARM_ID, ringAt, ringAt)
        assertEquals(clock.instant().plus(AlarmBroadcast.TEST_ALARM_DELAY), ringAt)
        assertEquals(listOf(test), alarmClocks())
        assertEquals(ReliabilityRoute, AppLinks.routeFor(shadowOf(alarmManager.nextAlarmClock.showIntent).savedIntent))

        val id = alarms.upsert(sevenDaily)
        runCurrent()
        scheduler.rescheduleAll()

        assertEquals(listOf(AlarmBroadcast.TEST_ALARM_ID, id), alarmClocks().map { it.alarmId })
        assertNull(scheduler.plan.value.forAlarm(AlarmBroadcast.TEST_ALARM_ID))
        assertEquals(listOf(id), ringState.scheduled().map { it.alarmId })
    }
}
