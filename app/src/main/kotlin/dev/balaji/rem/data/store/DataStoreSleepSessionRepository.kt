package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The start is one long key. The alarm ids whose late option was accepted are a string set
 * next to it, and an entry that is not a number is ignored. Both keys go together: [start]
 * and [end] write them in one edit.
 */
class DataStoreSleepSessionRepository(private val store: DataStore<Preferences>) : SleepSessionRepository {
    override val session: Flow<SleepSession?> = store.data.map { it.toSession() }.distinctUntilChanged()

    override suspend fun current(): SleepSession? = store.data.first().toSession()

    /** The start is stored in milliseconds, and the returned session has the stored value. */
    override suspend fun start(startedAt: Instant): SleepSession {
        val millis = startedAt.toEpochMilli()
        store.edit {
            it[STARTED_AT] = millis
            it.remove(LATE_ACCEPTED)
        }
        return SleepSession(Instant.ofEpochMilli(millis))
    }

    override suspend fun acceptLate(alarmId: Long) {
        store.edit {
            if (it[STARTED_AT] != null) it[LATE_ACCEPTED] = it[LATE_ACCEPTED].orEmpty() + alarmId.toString()
        }
    }

    override suspend fun revokeLate(alarmId: Long) {
        store.edit {
            val left = it[LATE_ACCEPTED].orEmpty() - alarmId.toString()
            if (left.isEmpty()) it.remove(LATE_ACCEPTED) else it[LATE_ACCEPTED] = left
        }
    }

    override suspend fun end() {
        store.edit {
            it.remove(STARTED_AT)
            it.remove(LATE_ACCEPTED)
        }
    }

    private companion object {
        val STARTED_AT = longPreferencesKey("started_at_ms")
        val LATE_ACCEPTED = stringSetPreferencesKey("late_accepted_alarm_ids")

        fun Preferences.toSession(): SleepSession? {
            val startedAt = this[STARTED_AT] ?: return null
            val accepted = this[LATE_ACCEPTED].orEmpty().mapNotNullTo(mutableSetOf()) { it.toLongOrNull() }
            return SleepSession(Instant.ofEpochMilli(startedAt), accepted)
        }
    }
}
