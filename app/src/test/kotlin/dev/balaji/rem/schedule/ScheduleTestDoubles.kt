package dev.balaji.rem.schedule

import dev.balaji.rem.RemApplication
import dev.balaji.rem.core.SchedulePlan
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.fake.InMemoryAlarmRepository
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.data.fake.InMemoryRingStateStore
import dev.balaji.rem.data.fake.InMemorySettingsRepository
import dev.balaji.rem.data.fake.InMemorySleepSessionRepository
import dev.balaji.rem.di.AppContainer
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.MissedAlarm
import dev.balaji.rem.ring.SnoozedAlarm
import dev.balaji.rem.testing.FakeRingController
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RecordingNotifier : AlarmNotifier {
    val missed = mutableListOf<MissedAlarm>()

    override fun ensureChannels() = Unit

    override fun notifyMissed(missed: MissedAlarm) {
        this.missed += missed
    }

    override fun notifySnoozed(snoozed: SnoozedAlarm) = Unit

    override fun cancelSnoozed(alarmId: Long) = Unit
}

/** Records, for each reschedule, the fired deadlines that were stored when it ran. */
class RecordingScheduler(private val ringState: RingStateStore) : AlarmScheduler {
    val reschedules = mutableListOf<Map<Long, Instant>>()

    override val plan: StateFlow<SchedulePlan> = MutableStateFlow(SchedulePlan())

    override suspend fun rescheduleAll() {
        reschedules += ringState.lastFired()
    }

    override suspend fun scheduleTestAlarm(): Instant? = error("not used by these tests")
}

class FakeAppContainer(override val applicationScope: CoroutineScope) : AppContainer {
    override val clock: Clock = Clock.systemUTC()
    override val barcodeRepository = InMemoryBarcodeRepository()
    override val alarmRepository = InMemoryAlarmRepository(barcodes = barcodeRepository)
    override val settingsRepository = InMemorySettingsRepository()
    override val sleepSessionRepository = InMemorySleepSessionRepository()
    override val ringStateStore = InMemoryRingStateStore()
    override val alarmScheduler = RecordingScheduler(ringStateStore)
    override val ringController = FakeRingController()
    override val alarmNotifier = RecordingNotifier()
}

/**
 * Swaps the container the application built at start for one made of fakes that runs its
 * background work on [scope]. The real container's scope is cancelled first, so its
 * start-up work stops at the next suspension point.
 */
fun RemApplication.useFakeContainer(scope: CoroutineScope): FakeAppContainer {
    container.applicationScope.cancel()
    return FakeAppContainer(scope).also { container = it }
}
