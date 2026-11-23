package dev.balaji.rem.data.fake

import dev.balaji.rem.data.SettingsRepository
import dev.balaji.rem.data.contract.SettingsRepositoryContract

class InMemorySettingsRepositoryTest : SettingsRepositoryContract() {
    override fun createRepository(): SettingsRepository = InMemorySettingsRepository()
}
