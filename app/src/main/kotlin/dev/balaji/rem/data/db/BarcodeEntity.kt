package dev.balaji.rem.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.normaliseBarcode

/**
 * One barcode of one alarm. [format] and [value] are the normalised key. The unique index
 * keeps one row per physical barcode and alarm, and its first column also serves the
 * foreign key. Deleting the alarm deletes its rows.
 */
@Entity(
    tableName = "barcodes",
    foreignKeys = [
        ForeignKey(
            entity = AlarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarm_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["alarm_id", "format", "value"], unique = true)],
)
data class BarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "alarm_id") val alarmId: Long,
    val label: String,
    val format: BarcodeFormat,
    val value: String,
)

fun BarcodeEntity.toBarcode() = Barcode(id, label, format, value)

/** A row for a barcode that is not stored yet. Normalises again, so a hand-built [Barcode] cannot get around the key rules. */
fun Barcode.toNewEntity(alarmId: Long): BarcodeEntity {
    val key = normaliseBarcode(format, value)
    return BarcodeEntity(id = Barcode.NEW_ID, alarmId = alarmId, label = label, format = key.format, value = key.value)
}
