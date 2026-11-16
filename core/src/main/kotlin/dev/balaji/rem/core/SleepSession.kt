package dev.balaji.rem.core

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Started when the user presses "going to sleep". At most one exists at a time. */
data class SleepSession(val startedAt: Instant) {

    /** A session shapes the ring time of deadlines after its start and up to 16 hours later. */
    fun covers(deadline: Instant): Boolean = deadline > startedAt && deadline <= startedAt.plus(MAX_COVERAGE)

    /** False once the session can no longer cover any deadline. Such a session should be ended. */
    fun isActive(now: Instant): Boolean = now < startedAt.plus(MAX_COVERAGE)

    /**
     * When the user is assumed to fall asleep. Cut to the whole minute so every cycle
     * boundary, and so every ring time, lands on a whole minute.
     */
    fun sleepOnset(settings: Settings): Instant =
        startedAt.plus(Duration.ofMinutes(settings.fallAsleepMinutes.toLong())).truncatedTo(ChronoUnit.MINUTES)

    companion object {
        val MAX_COVERAGE: Duration = Duration.ofHours(16)
    }
}
