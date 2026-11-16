package dev.balaji.rem.schedule

import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.planSchedule
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Stand-in scheduler that computes and publishes the plan but never talks to
 * AlarmManager, so nothing rings. It lets the UI show real ring times until the real
 * scheduler replaces it.
 */
class PlanOnlyAlarmScheduler(
    private val alarms: AlarmRepository,
    private val sessions: SleepSessionRepository,
    private val settings: SettingsRepository,
    private val ringState: RingStateStore,
    private val clock: Clock,
    scope: CoroutineScope,
) : AlarmScheduler {
    private val currentPlan = MutableStateFlow(SchedulePlan())

    override val plan: StateFlow<SchedulePlan> = currentPlan

    init {
        scope.launch {
            combine(alarms.alarms, sessions.session, settings.settings) { _, _, _ -> }.collect { rescheduleAll() }
        }
    }

    override suspend fun rescheduleAll() {
        val now = clock.instant()
        val session = sessions.current()?.takeIf { it.isActive(now) }
        currentPlan.value = planSchedule(alarms.all(), session, settings.current(), clock, ringState.lastFired())
    }

    override suspend fun scheduleTestAlarm(): Instant =
        clock.instant().plus(AlarmBroadcast.TEST_ALARM_DELAY).truncatedTo(ChronoUnit.MILLIS)
}
