package dev.balaji.rem.data.fake

import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryRingStateStore : RingStateStore {
    /** The ringing record and the snoozed ones, in one value so a snooze moves a ring in one step. */
    private data class Rings(val ringing: RingingRecord? = null, val snoozed: List<RingingRecord> = emptyList())

    private val rings = MutableStateFlow(Rings())
    private val fired = MutableStateFlow<Map<Long, Instant>>(emptyMap())
    private val scheduledRings = MutableStateFlow<List<ScheduledRing>>(emptyList())

    override val ringing: Flow<RingingRecord?> = rings.map { it.ringing }.distinctUntilChanged()

    override val snoozed: Flow<List<RingingRecord>> = rings.map { it.snoozed }.distinctUntilChanged()

    override suspend fun currentRinging(): RingingRecord? = rings.value.ringing

    override suspend fun setRinging(record: RingingRecord) {
        rings.update { current ->
            Rings(record.copy(snoozedUntil = null), current.snoozed.filterNot { it.alarmId == record.alarmId })
        }
    }

    override suspend fun clearRinging(expected: RingingRecord): Boolean {
        var cleared = false
        rings.update { current ->
            cleared = current.ringing == expected
            if (cleared) current.copy(ringing = null) else current
        }
        return cleared
    }

    override suspend fun currentSnoozed(): List<RingingRecord> = rings.value.snoozed

    override suspend fun snooze(ringing: RingingRecord, snoozed: RingingRecord) {
        rings.update { current ->
            Rings(
                ringing = current.ringing.takeUnless { it == ringing },
                snoozed = current.snoozed.filterNot { it.alarmId == snoozed.alarmId } + snoozed,
            )
        }
    }

    override suspend fun clearSnoozed(expected: RingingRecord): Boolean {
        var cleared = false
        rings.update { current ->
            cleared = expected in current.snoozed
            if (cleared) current.copy(snoozed = current.snoozed - expected) else current
        }
        return cleared
    }

    override suspend fun lastFired(): Map<Long, Instant> = fired.value

    override suspend fun markFired(alarmId: Long, deadline: Instant) {
        fired.update { current ->
            val known = current[alarmId]
            if (known != null && known >= deadline) current else current + (alarmId to deadline)
        }
    }

    override suspend fun removeFired(alarmId: Long) {
        fired.update { it - alarmId }
    }

    override suspend fun retainFired(alarmIds: Set<Long>) {
        fired.update { current -> current.filterKeys { it in alarmIds } }
    }

    override suspend fun scheduled(): List<ScheduledRing> = scheduledRings.value

    override suspend fun setScheduled(rings: List<ScheduledRing>) {
        scheduledRings.value = rings
    }
}
