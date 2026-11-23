package dev.balaji.rem.ring

import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the service needs to play one ring. Loaded once when the ring starts. The
 * tone, vibration and volume come from the alarm, the routing from the global settings.
 *
 * @property minRingVolumePercent see [dev.balaji.rem.core.Alarm.minRingVolumePercent].
 * @property volumeRampSeconds 0 disables the ramp.
 * @property requiresBarcode false when a plain dismissal works. The notification then offers it.
 * @property snoozeMinutes how long a snooze lasts.
 * @property snoozesLeft 0 when the notification offers no snooze.
 */
internal data class RingSpec(
    val alarmId: Long,
    val label: String,
    val ringtoneUri: String?,
    val vibrate: Boolean,
    val minRingVolumePercent: Int,
    val volumeRampSeconds: Int,
    val headphonesOnly: Boolean,
    val speakerFallbackSeconds: Int,
    val startedAt: Instant,
    val requiresBarcode: Boolean = true,
    val snoozeMinutes: Int = 0,
    val snoozesLeft: Int = 0,
)

/** What [RingingService] should be doing right now. */
internal sealed interface RingCommand {
    /** Nothing rings. The service stops itself. */
    data object Stop : RingCommand

    /** A ring has started and its alarm is still being loaded. Stay in the foreground. */
    data object Wait : RingCommand

    /**
     * Play [spec]. While [quiet] the tone is paused and the vibration is off, but the
     * volume floor still holds. A change of [quiet] alone must not restart the ring.
     */
    data class Play(val spec: RingSpec, val quiet: Boolean) : RingCommand
}

/** The side of the ring controller that only [RingingService] talks to. */
internal interface RingServiceHost {
    val command: StateFlow<RingCommand>

    /**
     * Called from `onStartCommand`. A redelivered start means the process died during a
     * ring and the system restarted the service, so the ring is picked up again if its
     * record is still persisted. A retried start counts as redelivered.
     */
    fun onServiceStarted(ring: ScheduledRing, redelivered: Boolean)
}

/**
 * One alarm clock entry, apart from the entries of the stored alarms, that asks for
 * [RingController.resumeRinging]. Alarm clock entries outlive the process, and their
 * delivery may start a foreground service that plays.
 */
internal interface RingWatchdog {
    /** Sets or moves the entry so it fires at [at] for [ring]. False when it could not be set. */
    fun arm(ring: ScheduledRing, at: Instant): Boolean

    fun cancel()
}

/**
 * One alarm clock entry per snoozed ring, apart from the entries of the stored alarms and
 * from the watchdog, that asks for [RingController.endSnooze].
 */
internal interface SnoozeAlarms {
    /** Sets or moves the entry of [ring]'s alarm so it fires at [at]. False when it could not be set. */
    fun setSnooze(ring: ScheduledRing, at: Instant): Boolean

    fun cancelSnooze(alarmId: Long)
}

/** Starts [RingingService]. A seam so the controller can be tested without Android. */
internal fun interface RingServiceLauncher {
    fun start(ring: ScheduledRing)
}
