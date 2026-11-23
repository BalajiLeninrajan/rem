package dev.balaji.rem.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.core.DeadlineReason
import dev.balaji.rem.core.WakeExplanation
import java.time.Instant
import java.time.ZoneId

/** Why a ring time was chosen, as the tail of a sentence: "5 cycles, 15 min before 07:00". */
@Composable
fun wakeExplanationText(explanation: WakeExplanation, deadline: Instant, zone: ZoneId): String = when (explanation) {
    is WakeExplanation.Cycles ->
        if (explanation.minutesBeforeDeadline > 0) {
            pluralStringResource(
                R.plurals.ui_explain_cycles_early,
                explanation.cycles,
                explanation.cycles,
                explanation.minutesBeforeDeadline,
                clockText(deadline, zone),
            )
        } else {
            pluralStringResource(R.plurals.ui_explain_cycles_on_deadline, explanation.cycles, explanation.cycles)
        }
    is WakeExplanation.LateCycles ->
        pluralStringResource(
            R.plurals.ui_explain_cycles_late,
            explanation.cycles,
            explanation.cycles,
            explanation.minutesAfterDeadline,
            clockText(deadline, zone),
        )
    is WakeExplanation.AtDeadline -> stringResource(
        when (explanation.reason) {
            DeadlineReason.NO_SESSION -> R.string.ui_explain_no_session
            DeadlineReason.OUTSIDE_SESSION -> R.string.ui_explain_outside_session
            DeadlineReason.BEFORE_FIRST_CYCLE -> R.string.ui_explain_before_first_cycle
            DeadlineReason.NO_CYCLE_IN_WINDOW -> R.string.ui_explain_no_cycle_in_window
            DeadlineReason.CYCLE_ALREADY_PASSED -> R.string.ui_explain_cycle_passed
        },
    )
}

/** The full line: "Rings 06:45, 5 cycles, 15 min before 07:00". */
@Composable
fun ringLineText(ringAt: Instant, deadline: Instant, explanation: WakeExplanation, now: Instant, zone: ZoneId): String =
    stringResource(
        R.string.ui_rings_line,
        ringTimeText(ringAt, now, zone),
        wakeExplanationText(explanation, deadline, zone),
    )
