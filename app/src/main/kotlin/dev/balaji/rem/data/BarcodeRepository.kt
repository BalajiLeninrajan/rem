package dev.balaji.rem.data

import dev.balaji.rem.core.Barcode
import kotlinx.coroutines.flow.Flow

/**
 * Registered dismissal barcodes. Lives in device-protected storage, because a ring before
 * the first unlock still has to be dismissed by scanning.
 */
interface BarcodeRepository {
    /** All barcodes, oldest first. */
    val barcodes: Flow<List<Barcode>>

    suspend fun all(): List<Barcode>

    /**
     * Stores [barcode] and returns its id. A barcode with the same [Barcode.key] is not
     * stored twice: the existing one takes the new label and its id is returned.
     * [Barcode.id] of the argument is ignored.
     */
    suspend fun add(barcode: Barcode): Long

    /** Does nothing when the id is unknown. */
    suspend fun rename(id: Long, label: String)

    /** Does nothing when the id is unknown. */
    suspend fun delete(id: Long)
}
