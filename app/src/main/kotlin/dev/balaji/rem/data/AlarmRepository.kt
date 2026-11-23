package dev.balaji.rem.data

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import kotlinx.coroutines.flow.Flow

/**
 * Alarm storage. The real implementation keeps its data in device-protected storage,
 * because the scheduler and the ringing service read it before the first unlock.
 *
 * Every alarm read or written passes through [Alarm.coerced].
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
     * id when it does not exist, and returns the id. Replacing an alarm keeps its barcodes.
     */
    suspend fun upsert(alarm: Alarm): Long

    /**
     * [upsert], then adds each of [barcodes] to the alarm as [BarcodeRepository.add] would,
     * all in one transaction. Either everything is stored or nothing is. Returns the alarm id.
     */
    suspend fun upsertWithBarcodes(alarm: Alarm, barcodes: List<Barcode>): Long

    /** Does nothing when the id is unknown. */
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** Deletes the alarm and its barcodes. Does nothing when the id is unknown. */
    suspend fun delete(id: Long)
}
