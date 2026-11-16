package dev.balaji.rem.data.db

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class RoomBarcodeRepository(private val dao: BarcodeDao) : BarcodeRepository {
    override val barcodes: Flow<List<Barcode>> =
        dao.observeAll().map { rows -> rows.map(BarcodeEntity::toBarcode) }.distinctUntilChanged()

    override suspend fun all(): List<Barcode> = dao.all().map(BarcodeEntity::toBarcode)

    override suspend fun add(barcode: Barcode): Long = dao.addOrRelabel(barcode.toNewEntity())

    override suspend fun rename(id: Long, label: String) = dao.rename(id, label)

    override suspend fun delete(id: Long) = dao.delete(id)
}
