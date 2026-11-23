package dev.balaji.rem.data.fake

import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.data.contract.BarcodeRepositoryContract

class InMemoryBarcodeRepositoryTest : BarcodeRepositoryContract() {
    override fun createRepository(): BarcodeRepository = InMemoryBarcodeRepository()
}
