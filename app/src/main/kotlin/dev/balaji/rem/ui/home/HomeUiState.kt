package dev.balaji.rem.ui.home

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.PlannedRing
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * @property nextRing the planned ring of an enabled alarm, null when the alarm is off or
 *   the plan has not caught up with a change yet.
 * @property ringing true while this alarm is ringing. The plan already points at the next
 *   occurrence then, so the card says "ringing" instead.
 * @property noBarcode true when the alarm has no barcode, so a plain button stops it.
 */
data class AlarmRow(
    val alarm: Alarm,
    val nextRing: PlannedRing?,
    val ringing: Boolean = false,
    val noBarcode: Boolean = false,
)

sealed interface SleepState {
    /** No alarm is enabled, so there is nothing to time a sleep session for. */
    data object Hidden : SleepState

    /** @property preview the first ring if the user went to sleep now, null when no alarm is due within the session. */
    data class Ready(val preview: PlannedRing?) : SleepState

    /** @property tonight the first ring the session covers, null when it covers none. */
    data class Active(val startedAt: Instant, val tonight: PlannedRing?) : SleepState
}

data class HomeUiState(
    val loading: Boolean = true,
    val alarms: List<AlarmRow> = emptyList(),
    val sleep: SleepState = SleepState.Hidden,
    /** True while any alarm rings, including the test alarm, which has no card. */
    val ringing: Boolean = false,
    /** Reliability checks that fail right now, not counting alarms without a barcode. Their cards say so. */
    val reliabilityProblems: Int = 0,
    /** True once, on first run, until the onboarding screen has been opened. */
    val onboardingNeeded: Boolean = false,
    /** The alarm just deleted, while undo is on offer. */
    val recentlyDeleted: Alarm? = null,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneOffset.UTC,
)
