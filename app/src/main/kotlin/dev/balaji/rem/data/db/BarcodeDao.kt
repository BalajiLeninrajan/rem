package dev.balaji.rem.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import dev.balaji.rem.core.BarcodeFormat
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BarcodeDao {
    @Query("SELECT * FROM barcodes ORDER BY id")
    abstract fun observeAll(): Flow<List<BarcodeEntity>>

    @Query("SELECT * FROM barcodes WHERE alarm_id = :alarmId ORDER BY id")
    abstract fun observeFor(alarmId: Long): Flow<List<BarcodeEntity>>

    @Query("SELECT * FROM barcodes WHERE alarm_id = :alarmId ORDER BY id")
    abstract suspend fun allFor(alarmId: Long): List<BarcodeEntity>

    @Query("SELECT id FROM barcodes WHERE alarm_id = :alarmId AND format = :format AND `value` = :value")
    abstract suspend fun idOf(alarmId: Long, format: BarcodeFormat, value: String): Long?

    @Insert
    abstract suspend fun insert(barcode: BarcodeEntity): Long

    @Query("UPDATE barcodes SET label = :label WHERE id = :id")
    abstract suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM barcodes WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * Inserts [barcode], or gives its label to the row of the same alarm that already has
     * the same key. Returns the row id.
     */
    @Transaction
    open suspend fun addOrRelabel(barcode: BarcodeEntity): Long {
        val existing = idOf(barcode.alarmId, barcode.format, barcode.value) ?: return insert(barcode)
        rename(existing, barcode.label)
        return existing
    }
}
