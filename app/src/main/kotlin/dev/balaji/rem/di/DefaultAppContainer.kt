package dev.balaji.rem.di

import android.content.Context
import android.util.Log
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.DataModule
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.createAlarmNotifier
import dev.balaji.rem.ring.createRingController
import dev.balaji.rem.schedule.AlarmScheduler
import dev.balaji.rem.schedule.createAlarmScheduler
import java.time.Clock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Wires the container from the module file of each package (`DataModule`,
 * `createAlarmScheduler`, `createRingController`, `createAlarmNotifier`). Concrete
 * classes are chosen there, so this file does not change when an implementation does.
 *
 * Everything is lazy. The process can start before the first unlock, and nothing here may
 * touch credential-protected storage at that point.
 */
class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val clock: Clock = SystemZoneClock

    // Background work that fails is logged. Letting it crash the process could cut off a ring.
    override val applicationScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            Log.e(TAG, "Background work failed", failure)
        },
    )

    private val data by lazy { DataModule(appContext) }

    override val alarmRepository: AlarmRepository get() = data.alarmRepository
    override val barcodeRepository: BarcodeRepository get() = data.barcodeRepository
    override val settingsRepository: SettingsRepository get() = data.settingsRepository
    override val sleepSessionRepository: SleepSessionRepository get() = data.sleepSessionRepository
    override val ringStateStore: RingStateStore get() = data.ringStateStore

    override val alarmScheduler: AlarmScheduler by lazy { createAlarmScheduler(appContext, this) }
    override val ringController: RingController by lazy { createRingController(appContext, this) }
    override val alarmNotifier: AlarmNotifier by lazy { createAlarmNotifier(appContext, this) }

    private companion object {
        const val TAG = "DefaultAppContainer"
    }
}
