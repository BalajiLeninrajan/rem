package dev.balaji.rem.data.fake

import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.contract.RingStateStoreContract

class InMemoryRingStateStoreTest : RingStateStoreContract() {
    override fun createStore(): RingStateStore = InMemoryRingStateStore()
}
