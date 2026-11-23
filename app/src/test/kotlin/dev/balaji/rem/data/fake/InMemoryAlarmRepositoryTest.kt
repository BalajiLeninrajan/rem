package dev.balaji.rem.data.fake

import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.contract.AlarmRepositoryContract

class InMemoryAlarmRepositoryTest : AlarmRepositoryContract() {
    override fun createRepository(): AlarmRepository = InMemoryAlarmRepository()
}
