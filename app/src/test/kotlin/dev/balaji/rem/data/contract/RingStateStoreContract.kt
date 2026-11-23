package dev.balaji.rem.data.contract

import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [RingStateStore] must have. Subclass it once per implementation. */
abstract class RingStateStoreContract {
    abstract fun createStore(): RingStateStore

    private val deadline = Instant.parse("2026-01-15T12:00:00Z")
    private val ringAt = deadline.minusSeconds(900)
    private val record = RingingRecord(alarmId = 1, startedAt = ringAt.plusMillis(250), ringAt = ringAt, deadline = deadline)

    @Test
    fun `starts empty`() = runTest {
        val store = createStore()

        assertNull(store.currentRinging())
        assertNull(store.ringing.first())
        assertTrue(store.lastFired().isEmpty())
        assertTrue(store.scheduled().isEmpty())
    }

    @Test
    fun `ringing record is stored with millisecond precision`() = runTest {
        val store = createStore()

        store.setRinging(record)

        assertEquals(record, store.currentRinging())
        assertEquals(record, store.ringing.first())
    }

    @Test
    fun `clear removes the record it was asked about`() = runTest {
        val store = createStore()
        store.setRinging(record)

        assertTrue(store.clearRinging(record))
        assertNull(store.currentRinging())
        assertFalse(store.clearRinging(record))
    }

    @Test
    fun `clear leaves a newer record alone`() = runTest {
        val store = createStore()
        val newer = record.copy(alarmId = 2, startedAt = record.startedAt.plusSeconds(3600))
        store.setRinging(record)
        store.setRinging(newer)

        assertFalse(store.clearRinging(record))
        assertEquals(newer, store.currentRinging())
    }

    @Test
    fun `fired deadlines are kept per alarm and never move backwards`() = runTest {
        val store = createStore()

        store.markFired(1, deadline)
        store.markFired(2, deadline.plusSeconds(3600))
        store.markFired(1, deadline.minusSeconds(86_400))

        assertEquals(mapOf(1L to deadline, 2L to deadline.plusSeconds(3600)), store.lastFired())

        store.markFired(1, deadline.plusSeconds(86_400))
        assertEquals(deadline.plusSeconds(86_400), store.lastFired()[1L])
    }

    @Test
    fun `a ring that needs no barcode keeps that across a write and a clear`() = runTest {
        val store = createStore()
        val plain = record.copy(requiresBarcode = false)

        store.setRinging(plain)

        assertEquals(plain, store.currentRinging())
        assertFalse(store.clearRinging(record))
        assertTrue(store.clearRinging(plain))
        store.setRinging(record)
        assertEquals(true, store.currentRinging()?.requiresBarcode)
    }

    @Test
    fun `removeFired forgets one alarm and lets an earlier deadline be stored again`() = runTest {
        val store = createStore()
        store.markFired(1, deadline)
        store.markFired(2, deadline)

        store.removeFired(1)
        store.removeFired(7)

        assertEquals(mapOf(2L to deadline), store.lastFired())
        store.markFired(1, deadline.minusSeconds(86_400))
        assertEquals(deadline.minusSeconds(86_400), store.lastFired()[1L])
    }

    @Test
    fun `retainFired drops records of other alarms`() = runTest {
        val store = createStore()
        store.markFired(1, deadline)
        store.markFired(2, deadline)

        store.retainFired(setOf(2L, 3L))

        assertEquals(mapOf(2L to deadline), store.lastFired())
    }

    @Test
    fun `scheduled rings are replaced as a whole`() = runTest {
        val store = createStore()
        val first = ScheduledRing(1, ringAt, deadline)
        val second = ScheduledRing(2, deadline.plusSeconds(3600), deadline.plusSeconds(3600))

        store.setScheduled(listOf(first, second))
        assertEquals(listOf(first, second), store.scheduled())

        store.setScheduled(listOf(second))
        assertEquals(listOf(second), store.scheduled())
    }
}
