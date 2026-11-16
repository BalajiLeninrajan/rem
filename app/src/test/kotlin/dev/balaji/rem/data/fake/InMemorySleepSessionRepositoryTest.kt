package dev.balaji.rem.data.fake

import dev.balaji.rem.data.SleepSessionRepository
import dev.balaji.rem.data.contract.SleepSessionRepositoryContract

class InMemorySleepSessionRepositoryTest : SleepSessionRepositoryContract() {
    override fun createRepository(): SleepSessionRepository = InMemorySleepSessionRepository()
}
