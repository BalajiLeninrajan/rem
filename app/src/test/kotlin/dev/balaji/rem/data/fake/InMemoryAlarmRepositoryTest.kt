package dev.balaji.rem.data.fake

import dev.balaji.rem.data.contract.AlarmRepositoryContract
import dev.balaji.rem.data.contract.AlarmStorage

class InMemoryAlarmRepositoryTest : AlarmRepositoryContract() {
    override fun createStorage(): AlarmStorage {
        val barcodes = InMemoryBarcodeRepository()
        return AlarmStorage(InMemoryAlarmRepository(barcodes = barcodes), barcodes)
    }
}
