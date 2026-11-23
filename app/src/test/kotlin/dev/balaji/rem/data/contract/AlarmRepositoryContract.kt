package dev.balaji.rem.data.contract

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.data.AlarmRepository
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [AlarmRepository] must have. Subclass it once per implementation. */
abstract class AlarmRepositoryContract {
    /** Fresh, empty storage. */
    abstract fun createStorage(): AlarmStorage

    fun createRepository(): AlarmRepository = createStorage().alarms

    private val seven = Alarm(time = LocalTime.of(7, 0), label = "work")
    private val six = Alarm(time = LocalTime.of(6, 0))

    private val toothpaste = Barcode.from("toothpaste", ScannedCode(BarcodeFormat.UPC_A, "036000291452"))
    private val mug = Barcode.from("mug", ScannedCode(BarcodeFormat.QR_CODE, "mug"))

    @Test
    fun `new alarms get distinct ids above zero`() = runTest {
        val repository = createRepository()

        val first = repository.upsert(seven)
        val second = repository.upsert(six)

        assertTrue(first > 0 && second > 0)
        assertNotEquals(first, second)
        assertEquals(seven.copy(id = first), repository.get(first))
    }

    @Test
    fun `upsert with a known id replaces that alarm`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        val returned = repository.upsert(seven.copy(id = id, label = "gym", vibrate = false))

        assertEquals(id, returned)
        assertEquals(listOf(seven.copy(id = id, label = "gym", vibrate = false)), repository.all())
    }

    @Test
    fun `alarms are ordered by time of day`() = runTest {
        val repository = createRepository()
        val sevenId = repository.upsert(seven)
        val sixId = repository.upsert(six)

        assertEquals(listOf(sixId, sevenId), repository.alarms.first().map { it.id })
        assertEquals(repository.alarms.first(), repository.all())
    }

    @Test
    fun `every field survives storage`() = runTest {
        val repository = createRepository()
        val full = Alarm(
            time = LocalTime.of(23, 59),
            label = "night shift",
            repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY),
            enabled = false,
            vibrate = false,
            ringtoneUri = "content://media/internal/audio/media/12",
            minRingVolumePercent = 45,
            volumeRampSeconds = 0,
            snoozeMinutes = 12,
            snoozeLimit = 0,
        )

        val id = repository.upsert(full)

        assertEquals(full.copy(id = id), repository.get(id))
    }

    @Test
    fun `volume values outside their ranges are coerced on write`() = runTest {
        val repository = createRepository()

        val id = repository.upsert(seven.copy(minRingVolumePercent = 150, volumeRampSeconds = -5))

        assertEquals(seven.copy(id = id, minRingVolumePercent = 100, volumeRampSeconds = 0), repository.get(id))
    }

    @Test
    fun `snooze values outside their ranges are coerced on write`() = runTest {
        val repository = createRepository()

        val id = repository.upsert(seven.copy(snoozeMinutes = 0, snoozeLimit = 99))

        assertEquals(seven.copy(id = id, snoozeMinutes = 1, snoozeLimit = 10), repository.get(id))
    }

    @Test
    fun `setEnabled changes only the flag`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.setEnabled(id, false)

        assertEquals(seven.copy(id = id, enabled = false), repository.get(id))
    }

    @Test
    fun `delete removes the alarm`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.delete(id)

        assertNull(repository.get(id))
        assertTrue(repository.alarms.first().isEmpty())
    }

    @Test
    fun `unknown ids are ignored`() = runTest {
        val repository = createRepository()
        val id = repository.upsert(seven)

        repository.setEnabled(id + 100, false)
        repository.delete(id + 100)

        assertNull(repository.get(id + 100))
        assertEquals(listOf(seven.copy(id = id)), repository.all())
    }

    @Test
    fun `replacing or switching an alarm keeps its barcodes`() = runTest {
        val storage = createStorage()
        val id = storage.alarms.upsert(seven)
        val barcodeId = storage.barcodes.add(id, mug)

        storage.alarms.upsert(seven.copy(id = id, label = "gym", minRingVolumePercent = 20))
        storage.alarms.setEnabled(id, false)

        assertEquals(listOf(mug.copy(id = barcodeId)), storage.barcodes.all(id))
    }

    @Test
    fun `delete removes the alarm's barcodes and no others`() = runTest {
        val storage = createStorage()
        val doomed = storage.alarms.upsert(seven)
        val kept = storage.alarms.upsert(six)
        storage.barcodes.add(doomed, mug)
        val keptBarcode = storage.barcodes.add(kept, mug)

        storage.alarms.delete(doomed)

        assertTrue(storage.barcodes.all(doomed).isEmpty())
        assertEquals(mapOf(kept to listOf(mug.copy(id = keptBarcode))), storage.barcodesByAlarmNow())
    }

    @Test
    fun `a new alarm is saved together with its barcodes`() = runTest {
        val storage = createStorage()

        val id = storage.alarms.upsertWithBarcodes(seven, listOf(toothpaste, mug))

        assertEquals(seven.copy(id = id), storage.alarms.get(id))
        assertEquals(listOf(toothpaste, mug), storage.barcodes.all(id).map { it.copy(id = Barcode.NEW_ID) })
        assertTrue(storage.barcodes.all(id).all { it.id > 0 })
    }

    @Test
    fun `saving with barcodes adds to an existing alarm and relabels a barcode it already has`() = runTest {
        val storage = createStorage()
        val id = storage.alarms.upsert(seven)
        val existing = storage.barcodes.add(id, toothpaste)
        val sameCodeAsEan = Barcode.from("bathroom", ScannedCode(BarcodeFormat.EAN_13, "0036000291452"))

        val returned = storage.alarms.upsertWithBarcodes(seven.copy(id = id, label = "gym"), listOf(sameCodeAsEan, mug))

        assertEquals(id, returned)
        assertEquals("gym", storage.alarms.get(id)?.label)
        val stored = storage.barcodes.all(id)
        assertEquals(listOf(existing, stored[1].id), stored.map { it.id })
        assertEquals(listOf(toothpaste.copy(label = "bathroom"), mug), stored.map { it.copy(id = Barcode.NEW_ID) })
    }

    @Test
    fun `the same barcode twice in one save is stored once with the later label`() = runTest {
        val storage = createStorage()

        val id = storage.alarms.upsertWithBarcodes(seven, listOf(mug, mug.copy(label = "coffee")))

        assertEquals(listOf(mug.copy(label = "coffee")), storage.barcodes.all(id).map { it.copy(id = Barcode.NEW_ID) })
    }

    @Test
    fun `the barcode ids of a save are ignored`() = runTest {
        val storage = createStorage()
        val first = storage.alarms.upsertWithBarcodes(seven, listOf(mug))
        val copied = storage.barcodes.all(first)

        val second = storage.alarms.upsertWithBarcodes(six, copied)

        assertEquals(1, storage.barcodes.all(first).size)
        assertEquals(1, storage.barcodes.all(second).size)
        assertNotEquals(copied.single().id, storage.barcodes.all(second).single().id)
    }

    private suspend fun AlarmStorage.barcodesByAlarmNow() = barcodes.barcodesByAlarm.first()
}
