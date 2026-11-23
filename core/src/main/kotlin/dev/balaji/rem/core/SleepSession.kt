package dev.balaji.rem.core

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Started when the user presses "going to sleep". At most one exists at a time.
 *
 * @property acceptedLate ids of the alarms whose [LateOption] the user accepted in this
 *   session. Their covered occurrence rings at the first cycle boundary after the deadline
 *   instead of at the deadline. Cleared when the session ends.
 */
data class SleepSession(val startedAt: Instant, val acceptedLate: Set<Long> = emptySet()) {

    /** A session shapes the ring time of deadlines after its start and up to 16 hours later. */
    fun covers(deadline: Instant): Boolean = deadline > startedAt && deadline <= startedAt.plus(MAX_COVERAGE)

    fun acceptsLate(alarmId: Long): Boolean = alarmId in acceptedLate

    /**
     * False once the session can no longer shape any ring. Such a session should be ended.
     * That is 16 hours after the start, plus [LATE_WINDOW] when a late option is accepted,
     * because a covered deadline at the very end can have its late ring after that.
     */
    fun isActive(now: Instant): Boolean {
        val end = startedAt.plus(MAX_COVERAGE)
        return now < if (acceptedLate.isEmpty()) end else end.plus(LATE_WINDOW)
    }

    /**
     * When the user is assumed to fall asleep. Cut to the whole minute so every cycle
     * boundary, and so every ring time, lands on a whole minute.
     */
    fun sleepOnset(settings: RemSettings): Instant =
        startedAt.plus(Duration.ofMinutes(settings.fallAsleepMinutes.toLong())).truncatedTo(ChronoUnit.MINUTES)

    companion object {
        val MAX_COVERAGE: Duration = Duration.ofHours(16)
    }
}
