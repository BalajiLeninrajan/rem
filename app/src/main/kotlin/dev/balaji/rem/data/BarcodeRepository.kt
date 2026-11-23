package dev.balaji.rem.data

import dev.balaji.rem.core.Barcode
import kotlinx.coroutines.flow.Flow

/**
 * Dismissal barcodes. Each one belongs to one alarm, and deleting the alarm deletes its
 * barcodes. The same physical barcode can be on several alarms, as separate rows with
 * their own ids and labels.
 *
 * Lives in device-protected storage, because a ring before the first unlock still has to
 * be dismissed by scanning.
 */
interface BarcodeRepository {
    /**
     * Every stored barcode, keyed by the id of the alarm it belongs to. Each list is
     * oldest first. An alarm without barcodes has no entry.
     */
    val barcodesByAlarm: Flow<Map<Long, List<Barcode>>>

    /** The barcodes of [alarmId], oldest first. Empty when it has none or does not exist. */
    fun barcodes(alarmId: Long): Flow<List<Barcode>>

    /** Same content and order as the latest [barcodes] value for [alarmId]. */
    suspend fun all(alarmId: Long): List<Barcode>

    /**
     * Stores [barcode] on the alarm [alarmId] and returns its id. A barcode with the same
     * [Barcode.key] is not stored twice on one alarm: the existing one takes the new label
     * and its id is returned. [Barcode.id] of the argument is ignored.
     *
     * [alarmId] must be a stored alarm. The database refuses a barcode without one.
     */
    suspend fun add(alarmId: Long, barcode: Barcode): Long

    /** Does nothing when the id is unknown. */
    suspend fun rename(id: Long, label: String)

    /** Does nothing when the id is unknown. */
    suspend fun delete(id: Long)
}
