package dev.balaji.rem.ui.testing

import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.core.planSchedule
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.DismissResult
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.RingingState
import dev.balaji.rem.schedule.AlarmBroadcast
import dev.balaji.rem.schedule.AlarmScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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

class MutableClock(var now: Instant, private val zoneId: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
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

/** A ring controller whose state the test sets directly. */
class FakeRingController : RingController {
    override val state = MutableStateFlow<RingingState>(RingingState.Idle)

    override fun startRinging(ring: ScheduledRing): Boolean = false

    override fun resumeRinging(ring: ScheduledRing) = Unit

    override suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult = DismissResult.NOT_RINGING

    override suspend fun dismissWithTypedCode(typed: String): DismissResult = DismissResult.NOT_RINGING

    override suspend fun dismissWithoutBarcode(): DismissResult = DismissResult.NOT_RINGING

    override fun newFallbackCode(): String = ""

    override suspend fun recoverInterruptedRing() = Unit
}
