package dev.balaji.rem.ui.testing

import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.planSchedule
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.schedule.AlarmBroadcast
import dev.balaji.rem.schedule.AlarmScheduler
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs `viewModelScope` on a test dispatcher that shares its scheduler with `runTest`. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** View model state flows share while subscribed, so a test has to subscribe before it reads `value`. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.keepCollecting(flow: StateFlow<T>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** Publishes a real plan computed from the repositories, when the test asks for it. Never touches AlarmManager. */
class FakeAlarmScheduler(
    private val alarms: AlarmRepository,
    private val sessions: SleepSessionRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : AlarmScheduler {
    override val plan = MutableStateFlow(SchedulePlan())

    /** False stands for a phone on which the user revoked exact alarms. */
    var exactAlarmsAllowed = true

    override suspend fun rescheduleAll() {
        val now = clock.instant()
        val session = sessions.current()?.takeIf { it.isActive(now) }
        plan.value = planSchedule(alarms.all(), session, settings.current(), clock)
    }

    override suspend fun scheduleTestAlarm(): Instant? =
        if (exactAlarmsAllowed) clock.instant().plus(AlarmBroadcast.TEST_ALARM_DELAY) else null
}
