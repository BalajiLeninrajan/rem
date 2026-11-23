package dev.balaji.rem.data.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dev.balaji.rem.core.SleepSession
import dev.balaji.rem.data.SleepSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreSleepSessionRepository(private val store: DataStore<Preferences>) : SleepSessionRepository {
    override val session: Flow<SleepSession?> = store.data.map { it.toSession() }.distinctUntilChanged()

    override suspend fun current(): SleepSession? = store.data.first().toSession()

    /** The start is stored in milliseconds, and the returned session has the stored value. */
    override suspend fun start(startedAt: Instant): SleepSession {
        val millis = startedAt.toEpochMilli()
        store.edit { it[STARTED_AT] = millis }
        return SleepSession(Instant.ofEpochMilli(millis))
    }

    override suspend fun end() {
        store.edit { it.remove(STARTED_AT) }
    }

    private companion object {
        val STARTED_AT = longPreferencesKey("started_at_ms")

        fun Preferences.toSession(): SleepSession? = this[STARTED_AT]?.let { SleepSession(Instant.ofEpochMilli(it)) }
    }
}
