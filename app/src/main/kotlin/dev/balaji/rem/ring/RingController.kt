package dev.balaji.rem.ring

import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * What the ringing screens show. [Ringing] while an alarm rings. Otherwise [Snoozed] for
 * the snoozed ring that comes due first, or [Idle] when nothing is snoozed either.
 */
sealed interface RingingState {
    data object Idle : RingingState

    /**
     * @property label the alarm label, possibly empty.
     * @property requiresBarcode false when the alarm has no barcode, and for the test alarm.
     *   Then, and only then, [RingController.dismissWithoutBarcode] works and the screen
     *   shows a plain button.
     * @property quietUntil when the quiet period started by [RingController.startQuietPeriod]
     *   ends, or null while the alarm is loud.
     * @property startedAt when this ringing stretch started: the first ring, or the ring
     *   again after a snooze.
     * @property snoozeMinutes how long [RingController.snooze] snoozes for.
     * @property snoozesLeft how many more times this ring can be snoozed. 0 until the alarm
     *   is loaded, and once the alarm's limit is used up. Then only a dismissal is left.
     */
    data class Ringing(
        val alarmId: Long,
        val label: String,
        val startedAt: Instant,
        val deadline: Instant,
        val requiresBarcode: Boolean,
        val quietUntil: Instant? = null,
        val snoozeMinutes: Int = 0,
        val snoozesLeft: Int = 0,
    ) : RingingState {
        val canSnooze: Boolean get() = snoozesLeft > 0
    }

    /**
     * A ring that was snoozed and rings again at [until]. It can be dismissed before then,
     * the same way as while it rings.
     *
     * @property snoozesLeft how many more snoozes the ring has once it rings again.
     */
    data class Snoozed(
        val alarmId: Long,
        val label: String,
        val deadline: Instant,
        val until: Instant,
        val snoozesLeft: Int,
        val requiresBarcode: Boolean,
    ) : RingingState
}

enum class SnoozeResult {
    /** The ring stopped and rings again after the alarm's snooze length. */
    SNOOZED,

    /** The alarm is still loading, its snoozes are used up, or the snooze could not be set. Still ringing. */
    REJECTED,

    /** Nothing was ringing. */
    NOT_RINGING,
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
 * The one entry point to a ring, for the alarm receiver, the ringing screens and the
 * actions on the ringing notification.
 *
 * A snooze is not the end of a ring. It stops the tone, keeps the ring's record and rings
 * again after the alarm's snooze length, through an alarm clock entry of its own. It
 * neither ends the sleep session nor disables a one-shot alarm. Each ringing stretch, the
 * first and each one after a snooze, has its own 30 minutes.
 *
 * When a ring ends, by dismissal or by the 30 minute limit, the implementation:
 * - stops the service and clears the persisted ringing record,
 * - on dismissal, disables the alarm if it is one-shot and still set for the time that
 *   rang, and ends the sleep session if it covers the ring's deadline,
 * - on the time limit, posts a missed-alarm notification. If the deadline is still ahead
 *   the alarm stays on and its fired record is removed, so it rings again at the
 *   deadline. Otherwise, which includes every late ring, a one-shot alarm is disabled,
 * - reschedules.
 *
 * A ring of the test alarm ([dev.balaji.rem.schedule.AlarmBroadcast.TEST_ALARM_ID]) has no
 * stored alarm. It rings with the defaults of an alarm, needs no barcode and touches neither
 * alarms nor the session.
 */
interface RingController {
    /** [RingingState.Idle] when nothing rings or is snoozed. Survives the ringing activity being closed and reopened. */
    val state: StateFlow<RingingState>

    /** Every snoozed ring, the one that comes due first first. */
    val snoozed: StateFlow<List<RingingState.Snoozed>>

    /**
     * Starts the foreground service for [ring]. Called by the alarm receiver inside
     * `onReceive`, on the main thread, so it must return quickly and must not suspend.
     * If the alarm no longer exists or is disabled the service stops without ringing.
     * A second alarm that fires during a ring does not restart anything and the current
     * ring goes on. If the second alarm's deadline has passed, a missed-alarm notification
     * is posted for it and it is disabled if it is one-shot. That always holds for a late
     * ring (an accepted late option), which comes after its deadline.
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
     * start time, so the 30 minute limit counts from the start of the stretch. Does nothing when
     * the ring is still live.
     */
    fun resumeRinging(ring: ScheduledRing)

    /**
     * Called by the alarm receiver inside `onReceive` when the entry of a snoozed ring
     * fires. Rings again if the snooze is still persisted, with a fresh 30 minutes. While
     * another alarm rings the snooze waits and rings right after that ring ends.
     */
    fun endSnooze(ring: ScheduledRing)

    /**
     * Stops the tone and the vibration and rings again after the alarm's snooze length.
     * Needs no barcode. Rejected while the alarm is loading and once its snoozes are used up.
     */
    suspend fun snooze(): SnoozeResult

    /*
     * The dismissals act on the ringing alarm. While nothing rings they act on the snoozed
     * ring that [state] shows, and need what that ring needs.
     */

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
     * Turns the tone and the vibration off for [dev.balaji.rem.core.RingLimits.QUIET_PERIOD],
     * so the user can scan or type the code in peace. Each tap that opens the scanner or the
     * typed fallback calls this, and each call starts a fresh period. The alarm rings
     * again when the period runs out without a dismissal, or at once on [endQuietPeriod].
     * The volume floor stays in force and the quiet time counts toward the 30 minute limit.
     * A ring picked up again after a process death starts loud. Does nothing when nothing
     * rings, also while a ring is snoozed. Must not suspend: called from the main thread.
     */
    fun startQuietPeriod()

    /** Makes the alarm loud again at once. The ringing screen calls this each time it is back on top. */
    fun endQuietPeriod()

    /**
     * Called once on every process start, before the first reschedule. If a ringing record
     * is persisted and this process has not started a ring, the previous ring was cut off
     * by a process death or a reboot. While its 30 minutes have not run out, an alarm
     * clock entry is set a few seconds ahead that leads to [resumeRinging]. It never
     * starts the service itself, because audio started on the boot path is muted.
     * Otherwise the record is cleared and a missed-alarm notification is posted. A
     * one-shot alarm is then disabled, unless its deadline is still ahead.
     *
     * Each persisted snooze gets its alarm clock entry again, at its time or a few seconds
     * ahead when its time has passed. A snooze more than 30 minutes overdue is reported as
     * missed instead.
     *
     * The alarm receiver can run while this is still in progress, so a ring that
     * [startRinging] began in this process must count as live from the moment that call returns.
     */
    suspend fun recoverInterruptedRing()
}
