package dev.balaji.rem.schedule

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.WakeExplanation
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import java.time.Clock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanOnlyAlarmSchedulerTest {
    private val zone = ZoneId.of("America/Toronto")
    private val clock = Clock.fixed(LocalDateTime.parse("2026-01-14T23:00").atZone(zone).toInstant(), zone)

    private val alarms = InMemoryAlarmRepository()
    private val sessions = InMemorySleepSessionRepository()
    private val settings = InMemorySettingsRepository()
    private val ringState = InMemoryRingStateStore()

    private fun TestScope.scheduler() =
        PlanOnlyAlarmScheduler(alarms, sessions, settings, ringState, clock, backgroundScope).also { runCurrent() }

    @Test
    fun `plan follows alarm, session and settings changes without an explicit reschedule`() = runTest {
        val scheduler = scheduler()
        assertNull(scheduler.plan.value.next)

        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        runCurrent()
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)

        sessions.start(clock.instant())
        runCurrent()
        assertEquals(WakeExplanation.Cycles(5, 15), scheduler.plan.value.forAlarm(id)?.explanation)

        settings.update { it.copy(fallAsleepMinutes = 30) }
        runCurrent()
        assertEquals(WakeExplanation.Cycles(5, 0), scheduler.plan.value.forAlarm(id)?.explanation)

        sessions.end()
        runCurrent()
        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)
    }

    @Test
    fun `a fired occurrence is skipped after an explicit reschedule`() = runTest {
        val scheduler = scheduler()
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        runCurrent()
        val tonight = scheduler.plan.value.forAlarm(id)!!.deadline

        ringState.markFired(id, tonight)
        scheduler.rescheduleAll()

        assertEquals(tonight.plusSeconds(86_400), scheduler.plan.value.forAlarm(id)?.deadline)
    }

    @Test
    fun `a session older than 16 hours is ignored`() = runTest {
        sessions.start(clock.instant().minusSeconds(17 * 3600))
        val scheduler = scheduler()
        val id = alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        runCurrent()

        assertEquals(WakeExplanation.AtDeadline(DeadlineReason.NO_SESSION), scheduler.plan.value.forAlarm(id)?.explanation)
    }
}
