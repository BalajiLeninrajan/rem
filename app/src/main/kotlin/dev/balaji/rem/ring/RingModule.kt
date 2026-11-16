package dev.balaji.rem.ring

import android.content.Context
import dev.balaji.rem.di.AppContainer

/**
 * Builds the ring controller and the notifier for the app container. This file is the
 * only place that names their concrete classes.
 *
 * The notifier must not read `container.ringController` or `container.alarmScheduler`
 * while it is being constructed, because the scheduler reads the notifier.
 */
fun createRingController(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
): RingController = IdleRingController()

fun createAlarmNotifier(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") container: AppContainer,
): AlarmNotifier = NoOpAlarmNotifier()
