package dev.balaji.rem.schedule

import android.content.Context
import dev.balaji.rem.di.AppContainer

/**
 * Builds the scheduler for the app container. This file is the only place that names the
 * concrete scheduler class.
 *
 * Read `container.ringController` lazily, not while constructing the scheduler: the ring
 * controller is allowed to read `container.alarmScheduler` in its own constructor.
 */
fun createAlarmScheduler(@Suppress("UNUSED_PARAMETER") context: Context, container: AppContainer): AlarmScheduler =
    PlanOnlyAlarmScheduler(
        alarms = container.alarmRepository,
        sessions = container.sleepSessionRepository,
        settings = container.settingsRepository,
        ringState = container.ringStateStore,
        clock = container.clock,
        scope = container.applicationScope,
    )
