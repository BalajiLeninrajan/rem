package dev.balaji.rem.ring

import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

sealed interface RingingState {
    data object Idle : RingingState

    /**
     * @property label the alarm label, possibly empty.
     * @property requiresBarcode false when no barcode is registered. Then, and only then,
     *   [RingController.dismissWithoutBarcode] works and the screen shows a plain button.
     */
    data class Ringing(
        val alarmId: Long,
        val label: String,
        val startedAt: Instant,
        val deadline: Instant,
        val requiresBarcode: Boolean,
    ) : RingingState
}

enum class DismissResult {
    /** The ring has stopped. */
    DISMISSED,

    /** Wrong barcode, wrong typed code, or a plain dismissal while barcodes are registered. Still ringing. */
    REJECTED,

    /** Nothing was ringing. */
    NOT_RINGING,
}

/**
 * The one entry point to a ring, for the alarm receiver and for the ringing screens.
 * There is no snooze and no other way to stop a ring.
 *
 * When a ring ends, by dismissal or by the 30 minute limit, the implementation:
 * - stops the service and clears the persisted ringing record,
 * - disables the alarm if it is one-shot,
 * - on dismissal only, ends the sleep session if it covers the ring's deadline,
 * - on the time limit only, posts a missed-alarm notification,
 * - leaves rescheduling to the scheduler, which observes those repository changes.
 *
 * A ring of the test alarm ([dev.balaji.rem.schedule.AlarmBroadcast.TEST_ALARM_ID]) has no
 * stored alarm. It rings with defaults and touches neither alarms nor the session.
 */
interface RingController {
    /** [RingingState.Idle] when nothing rings. Survives the ringing activity being closed and reopened. */
    val state: StateFlow<RingingState>

    /**
     * Starts the foreground service for [ring]. Called by the alarm receiver inside
     * `onReceive`, on the main thread, so it must return quickly and must not suspend.
     * If the alarm no longer exists or is disabled the service stops without ringing.
     * A second alarm that fires during a ring does not restart anything: the current ring
     * goes on, and the second alarm is only disabled if it is one-shot.
     */
    fun startRinging(ring: ScheduledRing)

    /** Dismisses when [scanned] is one of the registered barcodes. The scanner has already required 2 consecutive frames. */
    suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult

    /** Dismisses when [typed] equals the code from the latest [newFallbackCode] call. */
    suspend fun dismissWithTypedCode(typed: String): DismissResult

    /** Dismisses only when no barcode is registered. */
    suspend fun dismissWithoutBarcode(): DismissResult

    /**
     * Generates and returns a new 40 character code and forgets the previous one. The
     * typed fallback screen calls this each time it opens.
     */
    fun newFallbackCode(): String

    /**
     * Called once on every process start, before the first reschedule. If a ringing record
     * is persisted and this process has not started a ring, the previous ring was cut off
     * by a process death or a reboot: clear the record with `RingStateStore.clearRinging`,
     * disable the alarm if it is one-shot and post a missed-alarm notification. It never
     * starts ringing again.
     *
     * The alarm receiver can run while this is still in progress, so a ring that
     * [startRinging] began in this process must count as live from the moment that call returns.
     */
    suspend fun recoverInterruptedRing()
}
