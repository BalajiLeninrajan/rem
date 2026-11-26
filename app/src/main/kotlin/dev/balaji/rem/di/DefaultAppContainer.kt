package dev.balaji.rem.di

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import dev.balaji.rem.R
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.DataModule
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.ring.AlarmNotifier
import dev.balaji.rem.ring.DefaultRingController
import dev.balaji.rem.ring.RingContract
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.ring.RingNotifications
import dev.balaji.rem.schedule.AlarmClocks
import dev.balaji.rem.schedule.AlarmManagerScheduler
import dev.balaji.rem.schedule.AlarmScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
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

    override val alarmNotifier: AlarmNotifier by lazy { RingNotifications(appContext) }

    override val alarmScheduler: AlarmScheduler by lazy {
        AlarmManagerScheduler(
            context = appContext,
            alarms = alarmRepository,
            sessions = sleepSessionRepository,
            settings = settingsRepository,
            ringState = ringStateStore,
            notifier = alarmNotifier,
            clock = clock,
            scope = applicationScope,
        )
    }

    // The controller reaches the scheduler through a lambda, so building one never builds the other.
    override val ringController: RingController by lazy {
        DefaultRingController(
            alarms = alarmRepository,
            barcodes = barcodeRepository,
            sessions = sleepSessionRepository,
            settings = settingsRepository,
            ringState = ringStateStore,
            notifier = alarmNotifier,
            launcher = { ring ->
                ContextCompat.startForegroundService(appContext, RingContract.serviceIntent(appContext, ring))
            },
            watchdog = AlarmClocks(appContext),
            snoozeAlarms = AlarmClocks(appContext),
            reschedule = { alarmScheduler.rescheduleAll() },
            clock = clock,
            scope = applicationScope,
            testAlarmLabel = appContext.getString(R.string.ring_test_alarm_label),
        )
    }

    private companion object {
        const val TAG = "DefaultAppContainer"
    }
}

/**
 * System time in the current system zone. `Clock.systemDefaultZone()` captures the zone
 * once, which would leave a long-lived process computing deadlines in the old zone after
 * the user travels. This one asks for the zone on every call.
 */
private object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)

    override fun instant(): Instant = Instant.now()
}
