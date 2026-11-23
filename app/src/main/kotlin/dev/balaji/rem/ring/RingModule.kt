package dev.balaji.rem.ring

import android.content.Context
import androidx.core.content.ContextCompat
import dev.balaji.rem.R
import dev.balaji.rem.di.AppContainer
import dev.balaji.rem.schedule.AlarmClocks

/**
 * Builds the ring controller and the notifier for the app container. This file is the
 * only place that names their concrete classes.
 *
 * The notifier must not read `container.ringController` or `container.alarmScheduler`
 * while it is being constructed, because the scheduler reads the notifier.
 */
fun createRingController(context: Context, container: AppContainer): RingController =
    DefaultRingController(
        alarms = container.alarmRepository,
        barcodes = container.barcodeRepository,
        sessions = container.sleepSessionRepository,
        settings = container.settingsRepository,
        ringState = container.ringStateStore,
        notifier = container.alarmNotifier,
        launcher = { ring -> ContextCompat.startForegroundService(context, RingContract.serviceIntent(context, ring)) },
        watchdog = AlarmClocks(context),
        snoozeAlarms = AlarmClocks(context),
        reschedule = { container.alarmScheduler.rescheduleAll() },
        clock = container.clock,
        scope = container.applicationScope,
        testAlarmLabel = context.getString(R.string.ring_test_alarm_label),
    )

fun createAlarmNotifier(
    context: Context,
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
): AlarmNotifier = RingNotifications(context)
