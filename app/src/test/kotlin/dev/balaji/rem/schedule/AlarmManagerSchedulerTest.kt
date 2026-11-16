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
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.ring.MissedAlarm
import dev.balaji.rem.ring.MissedReason
import dev.balaji.rem.ui.AppLinks
import dev.balaji.rem.ui.navigation.AlarmEditorRoute
import dev.balaji.rem.ui.navigation.ReliabilityRoute
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

    /** Lets the second look at rings that seemed missed happen. It comes a few seconds after the first. */
    private fun TestScope.confirmMissed() {
        advanceTimeBy(10_000)
        runCurrent()
    }

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
        assertTrue("the first look only notes the ring", notifier.missed.isEmpty())
        confirmMissed()
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
        confirmMissed()

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
        confirmMissed()

        assertEquals(listOf(deadline), notifier.missed.map { it.deadline })
        assertFalse(ScheduledRing(id, deadline, deadline) in ringState.scheduled())
    }

    @Test
    fun `an alarm that was switched off after it was scheduled is not reported`() = runTest {
        val id = alarms.upsert(sevenDaily.copy(enabled = false))
        ringState.setScheduled(listOf(ScheduledRing(id, at("2026-01-15T07:00"), at("2026-01-15T07:00"))))
        clock.now = at("2026-01-15T09:00")

        scheduler()
        confirmMissed()

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
    fun `a one-shot alarm moved to another time while it rings is planned at the new time`() = runTest {
        val scheduler = scheduler()
        val once = alarms.upsert(sevenOnce)
        runCurrent()
        val deadline = at("2026-01-15T07:00")
        clock.now = deadline.plusSeconds(5)
        ringState.markFired(once, deadline)
        ringState.setRinging(RingingRecord(once, startedAt = deadline, ringAt = deadline, deadline = deadline))
        runCurrent()

        alarms.upsert(sevenOnce.copy(id = once, time = LocalTime.of(7, 30)))
        runCurrent()

        val moved = at("2026-01-15T07:30")
        assertEquals(listOf(ScheduledRing(once, moved, moved)), alarmClocks())
        assertEquals(moved, scheduler.plan.value.forAlarm(once)?.ringAt)
    }

    @Test
    fun `a ring that looked missed is not reported when its fire arrives before the second look`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenOnce)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        // The clock jumps past the deadline. The clock-change reschedule runs before the alarm broadcast.
        clock.now = at("2026-01-15T07:05")
        scheduler.rescheduleAll()
        assertTrue(notifier.missed.isEmpty())
        assertEquals(true, alarms.get(id)?.enabled)

        ringState.markFired(id, deadline)
        ringState.setRinging(RingingRecord(id, startedAt = clock.now, ringAt = deadline, deadline = deadline))
        confirmMissed()

        assertTrue(notifier.missed.isEmpty())
        assertEquals(true, alarms.get(id)?.enabled)
    }

    @Test
    fun `the alarm that is ringing is never reported as missed or switched off`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenOnce)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        // Ringing, but the fired record could not be written.
        clock.now = at("2026-01-15T07:05")
        ringState.setRinging(RingingRecord(id, startedAt = deadline, ringAt = deadline, deadline = deadline))
        scheduler.rescheduleAll()
        confirmMissed()

        assertTrue(notifier.missed.isEmpty())
        assertEquals(true, alarms.get(id)?.enabled)
    }

    @Test
    fun `a missed one-shot alarm that was moved to a new time since stays on and is set`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenOnce)
        runCurrent()
        // Nothing reschedules between the missed deadline and the edit.
        clock.now = at("2026-01-15T09:00")

        alarms.upsert(sevenOnce.copy(id = id, time = LocalTime.of(10, 0)))
        runCurrent()
        confirmMissed()

        assertEquals(listOf(at("2026-01-15T07:00")), notifier.missed.map { it.deadline })
        assertEquals(true, alarms.get(id)?.enabled)
        val moved = at("2026-01-15T10:00")
        assertEquals(listOf(ScheduledRing(id, moved, moved)), alarmClocks())
        assertEquals(moved, scheduler.plan.value.forAlarm(id)?.ringAt)
    }

    @Test
    fun `a fired record left far ahead by a clock that moved back is dropped`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()

        // The alarm rang while the clock said Friday. The clock is back at Wednesday night.
        ringState.markFired(id, at("2026-01-16T07:00"))
        scheduler.rescheduleAll()

        val thursday = at("2026-01-15T07:00")
        assertEquals(listOf(ScheduledRing(id, thursday, thursday)), alarmClocks())
        assertTrue(ringState.lastFired().isEmpty())
    }

    @Test
    fun `a fired record up to the largest early window ahead is kept`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        clock.now = at("2026-01-15T05:30")
        ringState.markFired(id, deadline)
        scheduler.rescheduleAll()

        val tomorrow = at("2026-01-16T07:00")
        assertEquals(listOf(ScheduledRing(id, tomorrow, tomorrow)), alarmClocks())
        assertEquals(mapOf(id to deadline), ringState.lastFired())
    }

    @Test
    fun `a one-shot alarm switched back on after its early ring is planned for the deadline still ahead`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenOnce)
        runCurrent()
        val deadline = at("2026-01-15T07:00")

        // It rang early at 06:45, was dismissed and so switched off.
        clock.now = at("2026-01-15T06:47")
        ringState.markFired(id, deadline)
        alarms.setEnabled(id, false)
        runCurrent()
        assertTrue(ringState.lastFired().isEmpty())

        alarms.setEnabled(id, true)
        runCurrent()

        assertEquals(listOf(ScheduledRing(id, deadline, deadline)), alarmClocks())
        assertEquals(deadline, scheduler.plan.value.forAlarm(id)?.ringAt)
    }

    @Test
    fun `alarms are still set when the bookkeeping around them fails`() = runTest {
        val stale = clock.instant().minusSeconds(17 * 3600)
        val brokenSessions = object : SleepSessionRepository by sessions {
            override suspend fun end(): Unit = throw IOException("disk full")
        }
        val brokenRingState = object : dev.balaji.rem.data.RingStateStore by ringState {
            override suspend fun retainFired(alarmIds: Set<Long>): Unit = throw IOException("disk full")

            override suspend fun setScheduled(rings: List<ScheduledRing>): Unit = throw IOException("disk full")
        }
        sessions.start(stale)
        val id = alarms.upsert(sevenDaily)
        val scheduler =
            AlarmManagerScheduler(context, alarms, brokenSessions, settings, brokenRingState, notifier, clock, backgroundScope)
        runCurrent()

        scheduler.rescheduleAll()

        val ring = ScheduledRing(id, at("2026-01-15T07:00"), at("2026-01-15T07:00"))
        assertEquals(listOf(ring), alarmClocks())
        // The session that could not be ended does not shape the ring either.
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)
        assertEquals(stale, sessions.current()?.startedAt)
    }

    @Test
    fun `the watchdog entry of a live ring is not touched by a reschedule and can be cancelled`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(sevenDaily)
        runCurrent()
        val deadline = at("2026-01-15T07:00")
        val clocks = AlarmClocks(context)

        assertTrue(clocks.arm(ScheduledRing(id, deadline, deadline), clock.instant().plusSeconds(60)))
        scheduler.rescheduleAll()
        alarms.setEnabled(id, false)
        runCurrent()

        val left = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(AlarmBroadcast.ACTION_RESUME, shadowOf(left.operation).savedIntent.action)
        assertEquals(clock.instant().plusSeconds(60).toEpochMilli(), alarmManager.nextAlarmClock.triggerTime)

        clocks.cancel()
        assertNull(alarmManager.nextAlarmClock)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(clocks.arm(ScheduledRing(id, deadline, deadline), clock.instant().plusSeconds(60)))
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
        assertNull("the caller is told that nothing was set", scheduler.scheduleTestAlarm())

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

        val ringAt = scheduler.scheduleTestAlarm()!!

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
