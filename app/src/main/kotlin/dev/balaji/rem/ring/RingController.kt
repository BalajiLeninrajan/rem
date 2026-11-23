package dev.balaji.rem.ring

import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

sealed interface RingingState {
    data object Idle : RingingState

    /**
     * @property label the alarm label, possibly empty.
     * @property requiresBarcode false when the alarm has no barcode, and for the test alarm.
     *   Then, and only then, [RingController.dismissWithoutBarcode] works and the screen
     *   shows a plain button.
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

    /** Wrong barcode, wrong typed code, or a plain dismissal while the alarm has barcodes. Still ringing. */
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
 * - on dismissal, disables the alarm if it is one-shot and still set for the time that
 *   rang, and ends the sleep session if it covers the ring's deadline,
 * - on the time limit, posts a missed-alarm notification. If the deadline is still ahead
 *   the alarm stays on and its fired record is removed, so it rings again at the
 *   deadline. Otherwise a one-shot alarm is disabled,
 * - reschedules.
 *
 * A ring of the test alarm ([dev.balaji.rem.schedule.AlarmBroadcast.TEST_ALARM_ID]) has no
 * stored alarm. It rings with the defaults of an alarm, needs no barcode and touches neither
 * alarms nor the session.
 */
interface RingController {
    /** [RingingState.Idle] when nothing rings. Survives the ringing activity being closed and reopened. */
    val state: StateFlow<RingingState>

    /**
     * Starts the foreground service for [ring]. Called by the alarm receiver inside
     * `onReceive`, on the main thread, so it must return quickly and must not suspend.
     * If the alarm no longer exists or is disabled the service stops without ringing.
     * A second alarm that fires during a ring does not restart anything and the current
     * ring goes on. If the second alarm's deadline has passed, a missed-alarm notification
     * is posted for it and it is disabled if it is one-shot.
     *
     * @return true when [ring] is ringing now, or was already. False when it got no ring:
     *   another alarm is ringing or the service could not start. The receiver then records
     *   the occurrence as fired only if its deadline has passed, so an early ring that was
     *   lost this way is planned again at the deadline.
     */
    fun startRinging(ring: ScheduledRing): Boolean

    /**
     * Picks up a ring that was cut off by a process death or a reboot. Called by the
     * alarm receiver inside `onReceive` when the watchdog alarm clock entry fires. Rings
     * only if the persisted ringing record still describes [ring], and keeps that record's
     * start time, so the 30 minute limit counts from the first start. Does nothing when
     * the ring is still live.
     */
    fun resumeRinging(ring: ScheduledRing)

    /** Dismisses when [scanned] is one of the ringing alarm's barcodes. The scanner has already required 2 consecutive frames. */
    suspend fun dismissWithBarcode(scanned: ScannedCode): DismissResult

    /** Dismisses when [typed] equals the code from the latest [newFallbackCode] call. */
    suspend fun dismissWithTypedCode(typed: String): DismissResult

    /** Dismisses only when the ring started with no barcode on its alarm. */
    suspend fun dismissWithoutBarcode(): DismissResult

    /**
     * Generates and returns a new 40 character code and forgets the previous one. The
     * typed fallback screen calls this each time it opens.
     */
    fun newFallbackCode(): String

    /**
     * Called once on every process start, before the first reschedule. If a ringing record
     * is persisted and this process has not started a ring, the previous ring was cut off
     * by a process death or a reboot. While its 30 minutes have not run out, an alarm
     * clock entry is set a few seconds ahead that leads to [resumeRinging]. It never
     * starts the service itself, because audio started on the boot path is muted.
     * Otherwise the record is cleared and a missed-alarm notification is posted. A
     * one-shot alarm is then disabled, unless its deadline is still ahead.
     *
     * The alarm receiver can run while this is still in progress, so a ring that
     * [startRinging] began in this process must count as live from the moment that call returns.
     */
    suspend fun recoverInterruptedRing()
}
