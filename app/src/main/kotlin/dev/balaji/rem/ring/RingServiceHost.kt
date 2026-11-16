package dev.balaji.rem.ring

import dev.balaji.rem.core.RemSettings
import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** Everything the service needs to play one ring. Loaded once when the ring starts. */
internal data class RingSpec(
    val alarmId: Long,
    val label: String,
    val ringtoneUri: String?,
    val vibrate: Boolean,
    val headphonesOnly: Boolean,
    val settings: RemSettings,
    val startedAt: Instant,
)

/** What [RingingService] should be doing right now. */
internal sealed interface RingCommand {
    /** Nothing rings. The service stops itself. */
    data object Stop : RingCommand

    /** A ring has started and its alarm is still being loaded. Stay in the foreground. */
    data object Wait : RingCommand

    data class Play(val spec: RingSpec) : RingCommand
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

/** Starts [RingingService]. A seam so the controller can be tested without Android. */
internal fun interface RingServiceLauncher {
    fun start(ring: ScheduledRing)
}
