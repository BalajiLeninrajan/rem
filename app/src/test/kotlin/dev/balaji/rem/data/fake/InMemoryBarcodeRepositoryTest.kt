package dev.balaji.rem.data.fake

import dev.balaji.rem.data.contract.AlarmStorage
import dev.balaji.rem.data.contract.BarcodeRepositoryContract

class InMemoryBarcodeRepositoryTest : BarcodeRepositoryContract() {
    override fun createStorage(): AlarmStorage {
        val barcodes = InMemoryBarcodeRepository()
        return AlarmStorage(InMemoryAlarmRepository(barcodes = barcodes), barcodes)
    }
}
