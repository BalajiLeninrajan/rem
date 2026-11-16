package dev.balaji.rem.data

import dev.balaji.rem.core.Alarm
import kotlinx.coroutines.flow.Flow

/**
 * Alarm storage. The real implementation keeps its data in device-protected storage,
 * because the scheduler and the ringing service read it before the first unlock.
 */
interface AlarmRepository {
    /** All alarms, ordered by time of day and then id. Emits again after every change. */
    val alarms: Flow<List<Alarm>>

    /** Same content and order as the latest [alarms] value. */
    suspend fun all(): List<Alarm>

    suspend fun get(id: Long): Alarm?

    /**
     * Inserts when [Alarm.id] is [Alarm.NEW_ID] and returns the generated id, which is
     * always above 0. Otherwise replaces the alarm with that id, or inserts it under that
     * id when it does not exist, and returns the id.
     */
    suspend fun upsert(alarm: Alarm): Long

    /** Does nothing when the id is unknown. */
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Does nothing when the id is unknown. */
    suspend fun delete(id: Long)
}
