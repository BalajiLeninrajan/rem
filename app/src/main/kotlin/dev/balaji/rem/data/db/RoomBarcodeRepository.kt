package dev.balaji.rem.data.db

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomBarcodeRepository(private val dao: BarcodeDao) : BarcodeRepository {
    // Room emits after every write to the table, including writes to other alarms' barcodes.
    override val barcodesByAlarm: Flow<Map<Long, List<Barcode>>> =
        dao.observeAll()
            .map { rows -> rows.groupBy(BarcodeEntity::alarmId, BarcodeEntity::toBarcode) }
            .distinctUntilChanged()

    override fun barcodes(alarmId: Long): Flow<List<Barcode>> =
        dao.observeFor(alarmId).map { rows -> rows.map(BarcodeEntity::toBarcode) }.distinctUntilChanged()

    override suspend fun all(alarmId: Long): List<Barcode> = dao.allFor(alarmId).map(BarcodeEntity::toBarcode)

    override suspend fun add(alarmId: Long, barcode: Barcode): Long = dao.addOrRelabel(barcode.toNewEntity(alarmId))

    override suspend fun rename(id: Long, label: String) = dao.rename(id, label)

    override suspend fun delete(id: Long) = dao.delete(id)
}
