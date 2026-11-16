package dev.balaji.rem.data

import dev.balaji.rem.core.ScheduledRing
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** "Alarm X has been ringing since T for deadline D." Written when a ring starts, cleared when it ends. */
data class RingingRecord(val alarmId: Long, val startedAt: Instant, val ringAt: Instant, val deadline: Instant)

/**
 * Small persisted bookkeeping around rings, in device-protected storage. It answers three
 * questions after a process death or a reboot: was an alarm ringing, which occurrences
 * already fired, and what did the scheduler last hand to AlarmManager.
 *
 * Instants are stored with millisecond precision.
 */
interface RingStateStore {
    /** The ring in progress, or null. Written by the `ring` package only. */
    val ringing: Flow<RingingRecord?>

    suspend fun currentRinging(): RingingRecord?

    /** Replaces any existing record. */
    suspend fun setRinging(record: RingingRecord)

    /**
     * Clears the record only if it still equals [expected], atomically, and says whether
     * it did. This keeps a late cleanup of an old ring from erasing a newer one.
     */
    suspend fun clearRinging(expected: RingingRecord): Boolean

    /**
     * Alarm id to the deadline of the latest occurrence that fired. The scheduler passes
     * this to `planSchedule` and `checkMissed`.
     */
    suspend fun lastFired(): Map<Long, Instant>

    /** Records that the occurrence of [alarmId] with this [deadline] fired. Keeps the later deadline if one is already stored. */
    suspend fun markFired(alarmId: Long, deadline: Instant)

    /** Drops fired records of alarms that are not in [alarmIds], so deleted alarms do not pile up. */
    suspend fun retainFired(alarmIds: Set<Long>)

    /** The rings persisted by the last reschedule. Written by the `schedule` package only. */
    suspend fun scheduled(): List<ScheduledRing>

    suspend fun setScheduled(rings: List<ScheduledRing>)
}
