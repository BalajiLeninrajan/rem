package dev.balaji.rem.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.balaji.rem.core.BarcodeFormat
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY time_of_day, id")
    abstract fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms ORDER BY time_of_day, id")
    abstract suspend fun all(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    abstract suspend fun get(id: Long): AlarmEntity?

    /** An id of 0 gets a generated id. Returns the row id, or -1 when a row with that id exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertOrIgnore(alarm: AlarmEntity): Long

    @Update
    abstract suspend fun update(alarm: AlarmEntity)

    @Query("UPDATE alarms SET enabled = :enabled WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean)

    /** The foreign key deletes the alarm's barcodes with it. */
    @Query("DELETE FROM alarms WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Returns -1 when the alarm already has a barcode with this key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBarcodeOrIgnore(barcode: BarcodeEntity): Long

    @Query("UPDATE barcodes SET label = :label WHERE alarm_id = :alarmId AND format = :format AND `value` = :value")
    abstract suspend fun relabelBarcode(alarmId: Long, format: BarcodeFormat, value: String, label: String)

    /**
     * Inserts [alarm], or updates the row with its id. Returns the alarm id.
     *
     * Not an insert with REPLACE: that deletes the old row first, and the foreign key
     * would delete the alarm's barcodes with it.
     */
    @Transaction
    open suspend fun upsert(alarm: AlarmEntity): Long {
        val inserted = insertOrIgnore(alarm)
        if (inserted != -1L) return inserted
        update(alarm)
        return alarm.id
    }

    /**
     * [upsert], then adds [barcodes] to the alarm. A barcode whose key the alarm already
     * has gives that row its label. The alarm id on the rows is ignored.
     */
    @Transaction
    open suspend fun upsertWithBarcodes(alarm: AlarmEntity, barcodes: List<BarcodeEntity>): Long {
        val id = upsert(alarm)
        for (barcode in barcodes) {
            val row = barcode.copy(id = 0, alarmId = id)
            if (insertBarcodeOrIgnore(row) == -1L) relabelBarcode(id, row.format, row.value, row.label)
        }
        return id
    }
}
