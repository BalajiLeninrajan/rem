package dev.balaji.rem.data.fake

import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryRingStateStore : RingStateStore {
    private val ringingState = MutableStateFlow<RingingRecord?>(null)
    private val fired = MutableStateFlow<Map<Long, Instant>>(emptyMap())
    private val scheduledRings = MutableStateFlow<List<ScheduledRing>>(emptyList())

    override val ringing: Flow<RingingRecord?> = ringingState

    override suspend fun currentRinging(): RingingRecord? = ringingState.value

    override suspend fun setRinging(record: RingingRecord) {
        ringingState.value = record
    }

    override suspend fun clearRinging(expected: RingingRecord): Boolean = ringingState.compareAndSet(expected, null)

    override suspend fun lastFired(): Map<Long, Instant> = fired.value

    override suspend fun markFired(alarmId: Long, deadline: Instant) {
        fired.update { current ->
            val known = current[alarmId]
            if (known != null && known >= deadline) current else current + (alarmId to deadline)
        }
    }

    override suspend fun retainFired(alarmIds: Set<Long>) {
        fired.update { current -> current.filterKeys { it in alarmIds } }
    }

    override suspend fun scheduled(): List<ScheduledRing> = scheduledRings.value

    override suspend fun setScheduled(rings: List<ScheduledRing>) {
        scheduledRings.value = rings
    }
}
