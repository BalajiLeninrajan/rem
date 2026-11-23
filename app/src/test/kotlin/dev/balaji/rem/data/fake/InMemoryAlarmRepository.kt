package dev.balaji.rem.data.fake

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.data.AlarmRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryAlarmRepository(initial: List<Alarm> = emptyList()) : AlarmRepository {
    private val state = MutableStateFlow(initial.byTime())

    override val alarms: Flow<List<Alarm>> = state

    override suspend fun all(): List<Alarm> = state.value

    override suspend fun get(id: Long): Alarm? = state.value.firstOrNull { it.id == id }

    override suspend fun upsert(alarm: Alarm): Long {
        var id = alarm.id
        state.update { current ->
            id = if (alarm.id == Alarm.NEW_ID) (current.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1 else alarm.id
            (current.filterNot { it.id == id } + alarm.copy(id = id)).byTime()
        }
        return id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        state.update { current -> current.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    override suspend fun delete(id: Long) {
        state.update { current -> current.filterNot { it.id == id } }
    }

    private fun List<Alarm>.byTime(): List<Alarm> = sortedWith(compareBy({ it.time }, { it.id }))
}
