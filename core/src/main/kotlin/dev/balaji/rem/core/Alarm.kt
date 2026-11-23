package dev.balaji.rem.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * One alarm. [time] is the local "must wake by" time, the deadline. The alarm may ring
 * earlier than this when a sleep session is active. It rings later only when the user
 * accepted its late option in the session, and then by at most [LATE_WINDOW].
 *
 * @property id 0 until the alarm has been saved.
 * @property repeatDays empty means one-shot. A one-shot alarm is disabled when its ring ends.
 * @property ringtoneUri null means the bundled default tone.
 * @property minRingVolumePercent the alarm stream is held at or above this share of its
 *   range while the alarm rings.
 * @property volumeRampSeconds how long the tone takes to reach full volume. 0 disables the ramp.
 *
 * Stored values may be out of range (hand edits, a bug), so repositories pass everything
 * they read or write through [coerced].
 */
data class Alarm(
    val id: Long = NEW_ID,
    val time: LocalTime,
    val label: String = "",
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val ringtoneUri: String? = null,
    val minRingVolumePercent: Int = DEFAULT_MIN_RING_VOLUME_PERCENT,
    val volumeRampSeconds: Int = DEFAULT_VOLUME_RAMP_SECONDS,
) {
    val isOneShot: Boolean get() = repeatDays.isEmpty()

    /** This alarm with the volume fields moved into [MIN_RING_VOLUME_PERCENT] and [VOLUME_RAMP_SECONDS]. */
    fun coerced(): Alarm = copy(
        minRingVolumePercent = minRingVolumePercent.coerceIn(MIN_RING_VOLUME_PERCENT),
        volumeRampSeconds = volumeRampSeconds.coerceIn(VOLUME_RAMP_SECONDS),
    )

    /**
     * The first deadline strictly after [after], as an instant.
     *
     * Computed from the local time on each candidate date, so a daylight saving shift moves
     * the instant and keeps the wall clock time. A time inside a spring-forward gap resolves
     * to the moment the gap starts being skipped (02:30 becomes 03:00 on that day), which is
     * the earliest moment the wall clock has passed the deadline. A time inside a fall-back
     * overlap resolves to its first occurrence and does not repeat an hour later.
     */
    fun nextDeadline(after: Instant, zone: ZoneId): Instant {
        val today = after.atZone(zone).toLocalDate()
        // Start one day back so a zone that skips a whole calendar day cannot hide an occurrence.
        for (offset in -1L..SEARCH_DAYS) {
            val date = today.plusDays(offset)
            if (!isOneShot && date.dayOfWeek !in repeatDays) continue
            val deadline = deadlineOn(date, time, zone)
            if (deadline > after) return deadline
        }
        error("No occurrence of $time on $repeatDays within $SEARCH_DAYS days of $after in $zone")
    }

    /**
     * Whether [deadline] is an occurrence of this alarm. False once the alarm has been
     * edited to another time or other days, which makes a stored deadline stale.
     */
    fun hasOccurrenceAt(deadline: Instant, zone: ZoneId): Boolean = nextDeadline(deadline.minusMillis(1), zone) == deadline

    companion object {
        const val NEW_ID = 0L
        const val DEFAULT_MIN_RING_VOLUME_PERCENT = 70
        const val DEFAULT_VOLUME_RAMP_SECONDS = 30

        val MIN_RING_VOLUME_PERCENT = 0..100
        val VOLUME_RAMP_SECONDS = 0..300

        private const val SEARCH_DAYS = 14L
    }
}

private fun deadlineOn(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
    val local = LocalDateTime.of(date, time)
    val transition = zone.rules.getTransition(local)
    return if (transition != null && transition.isGap) {
        transition.instant
    } else {
        // In an overlap atZone picks the earlier offset, which is the first occurrence.
        local.atZone(zone).toInstant()
    }
}
