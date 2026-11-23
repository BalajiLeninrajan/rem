package dev.balaji.rem.data.fake

import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemorySleepSessionRepository(initial: SleepSession? = null) : SleepSessionRepository {
    private val state = MutableStateFlow(initial)

    override val session: Flow<SleepSession?> = state

    override suspend fun current(): SleepSession? = state.value

    override suspend fun start(startedAt: Instant): SleepSession = SleepSession(startedAt).also { state.value = it }

    override suspend fun acceptLate(alarmId: Long) {
        state.update { it?.copy(acceptedLate = it.acceptedLate + alarmId) }
    }

    override suspend fun revokeLate(alarmId: Long) {
        state.update { it?.copy(acceptedLate = it.acceptedLate - alarmId) }
    }

    override suspend fun end() {
        state.value = null
    }
}
