package dev.balaji.rem.di

import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.schedule.AlarmScheduler
import java.time.Clock
import kotlinx.coroutines.CoroutineScope

/** Every app-wide dependency. One instance lives on `RemApplication`. No DI framework. */
interface AppContainer {
    /** Now and the current system zone. The zone follows timezone changes. */
    val clock: Clock

    /** Lives as long as the process. Uses a SupervisorJob and Dispatchers.Default. */
    val applicationScope: CoroutineScope

    val alarmRepository: AlarmRepository
    val barcodeRepository: BarcodeRepository
    val settingsRepository: SettingsRepository
    val sleepSessionRepository: SleepSessionRepository
    val ringStateStore: RingStateStore

    val alarmScheduler: AlarmScheduler
    val ringController: RingController
    val alarmNotifier: AlarmNotifier
}
