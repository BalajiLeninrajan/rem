package dev.balaji.rem.data.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.balaji.rem.core.ScheduledRing
import dev.balaji.rem.data.RingStateStore
import dev.balaji.rem.data.RingingRecord
import dev.balaji.rem.data.contract.RingStateStoreContract
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DataStoreRingStateStoreTest : RingStateStoreContract() {
    @get:Rule
    val stores = PreferencesStoreRule()

    override fun createStore(): RingStateStore = DataStoreRingStateStore(stores.open())

    private val deadline = Instant.parse("2026-01-15T12:00:00Z")
    private val ring = ScheduledRing(alarmId = 3, ringAt = deadline.minusSeconds(900), deadline = deadline)
    private val record = RingingRecord(3, startedAt = ring.ringAt.plusMillis(40), ringAt = ring.ringAt, deadline = deadline)

    @Test
    fun `everything survives a restart`() = runTest {
        val file = stores.newStoreFile()
        val before = DataStoreRingStateStore(stores.open(file))
        before.setRinging(record)
        before.markFired(3, deadline)
        before.markFired(-7, deadline.plusSeconds(60))
        before.setScheduled(listOf(ring, ring.copy(alarmId = 4)))
        val snoozed = record.copy(alarmId = 5, snoozes = 2, snoozedUntil = deadline.plusSeconds(300))
        before.snooze(record.copy(alarmId = 5), snoozed)
        stores.close(file)

        val after = DataStoreRingStateStore(stores.open(file))

        assertEquals(record, after.currentRinging())
        assertEquals(mapOf(3L to deadline, -7L to deadline.plusSeconds(60)), after.lastFired())
        assertEquals(listOf(ring, ring.copy(alarmId = 4)), after.scheduled())
        assertEquals(listOf(snoozed), after.currentSnoozed())
    }

    @Test
    fun `an unreadable snoozed list reads as empty`() = runTest {
        val preferences = stores.open()
        preferences.edit { it[stringPreferencesKey("snoozed_rings")] = "not json" }

        assertTrue(DataStoreRingStateStore(preferences).currentSnoozed().isEmpty())
    }

    @Test
    fun `clear matches a record that still carries nanoseconds`() = runTest {
        val store = createStore()
        val precise = record.copy(startedAt = record.startedAt.plusNanos(999))
        store.setRinging(precise)

        assertTrue(store.clearRinging(precise))
        assertNull(store.currentRinging())
    }

    @Test
    fun `clearing the ringing record keeps fired deadlines and scheduled rings`() = runTest {
        val store = createStore()
        store.markFired(3, deadline)
        store.setScheduled(listOf(ring))
        store.setRinging(record)

        store.clearRinging(record)

        assertEquals(mapOf(3L to deadline), store.lastFired())
        assertEquals(listOf(ring), store.scheduled())
    }

    @Test
    fun `an unreadable scheduled list reads as empty`() = runTest {
        val preferences = stores.open()
        preferences.edit { it[stringPreferencesKey("scheduled_rings")] = "not json" }

        assertTrue(DataStoreRingStateStore(preferences).scheduled().isEmpty())
    }
}
