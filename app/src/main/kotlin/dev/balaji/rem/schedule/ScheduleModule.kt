package dev.balaji.rem.schedule

import android.content.Context
import dev.balaji.rem.di.AppContainer

/**
 * Builds the scheduler for the app container. This file is the only place that names the
 * concrete scheduler class.
 *
 * The scheduler takes the notifier here and never touches `container.ringController`,
 * which is allowed to read `container.alarmScheduler` in its own constructor.
 */
fun createAlarmScheduler(context: Context, container: AppContainer): AlarmScheduler =
    AlarmManagerScheduler(
        context = context,
        alarms = container.alarmRepository,
        sessions = container.sleepSessionRepository,
        settings = container.settingsRepository,
        ringState = container.ringStateStore,
        notifier = container.alarmNotifier,
        clock = container.clock,
        scope = container.applicationScope,
    )
