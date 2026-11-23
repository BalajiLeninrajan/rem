package dev.balaji.rem.ui.home

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.LateOption
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
 * @property late the late option of [nextRing] while a sleep session covers it, null when
 *   it has none. The card offers it, or the way back to the alarm time once accepted.
 */
data class AlarmRow(
    val alarm: Alarm,
    val nextRing: PlannedRing?,
    val ringing: Boolean = false,
    val noBarcode: Boolean = false,
    val late: LateSwitch? = null,
)

/** @property accepted true when the ring already uses [option]. */
data class LateSwitch(val option: LateOption, val accepted: Boolean)

/**
 * Asked once after "going to sleep" was pressed, when the new session gives alarms a late
 * option. One row per alarm, earliest late ring first. Every row starts declined.
 */
data class LatePrompt(val rows: List<LatePromptRow>)

/**
 * @property deadline the alarm time this occurrence must ring by without the option.
 * @property accepted the user's choice so far. Nothing is stored before the prompt is confirmed.
 */
data class LatePromptRow(
    val alarmId: Long,
    val label: String,
    val deadline: Instant,
    val option: LateOption,
    val accepted: Boolean,
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
    /** Shown over the screen while not null. */
    val latePrompt: LatePrompt? = null,
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneOffset.UTC,
)
