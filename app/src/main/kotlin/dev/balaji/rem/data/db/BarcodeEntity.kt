package dev.balaji.rem.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.normaliseBarcode

/** [format] and [value] are the normalised key. The unique index keeps one row per physical barcode. */
@Entity(tableName = "barcodes", indices = [Index(value = ["format", "value"], unique = true)])
data class BarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val label: String,
    val format: BarcodeFormat,
    val value: String,
)

fun BarcodeEntity.toBarcode() = Barcode(id, label, format, value)

/** A row for a barcode that is not stored yet. Normalises again, so a hand-built [Barcode] cannot get around the key rules. */
fun Barcode.toNewEntity(): BarcodeEntity {
    val key = normaliseBarcode(format, value)
    return BarcodeEntity(id = Barcode.NEW_ID, label = label, format = key.format, value = key.value)
}
