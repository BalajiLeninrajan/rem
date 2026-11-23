package dev.balaji.rem.data.contract

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.data.BarcodeRepository
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [BarcodeRepository] must have. Subclass it once per implementation. */
abstract class BarcodeRepositoryContract {
    /** Fresh, empty storage. */
    abstract fun createStorage(): AlarmStorage

    private val toothpaste = Barcode.from("toothpaste", ScannedCode(BarcodeFormat.UPC_A, "036000291452"))
    private val mug = Barcode.from("mug", ScannedCode(BarcodeFormat.QR_CODE, "mug"))

    /** Storage with two stored alarms, whose ids are returned with it. */
    private suspend fun withAlarms(): Triple<BarcodeRepository, Long, Long> {
        val storage = createStorage()
        val first = storage.alarms.upsert(Alarm(time = LocalTime.of(7, 0)))
        val second = storage.alarms.upsert(Alarm(time = LocalTime.of(8, 0)))
        return Triple(storage.barcodes, first, second)
    }

    @Test
    fun `barcodes are listed oldest first with their ids`() = runTest {
        val (repository, alarm, _) = withAlarms()

        val first = repository.add(alarm, toothpaste)
        val second = repository.add(alarm, mug)

        assertTrue(first > 0 && second > 0)
        assertEquals(listOf(toothpaste.copy(id = first), mug.copy(id = second)), repository.barcodes(alarm).first())
        assertEquals(repository.barcodes(alarm).first(), repository.all(alarm))
    }

    @Test
    fun `each alarm sees only its own barcodes`() = runTest {
        val (repository, first, second) = withAlarms()

        val onFirst = repository.add(first, toothpaste)
        val onSecond = repository.add(second, mug)

        assertEquals(listOf(toothpaste.copy(id = onFirst)), repository.all(first))
        assertEquals(listOf(mug.copy(id = onSecond)), repository.barcodes(second).first())
        assertEquals(
            mapOf(first to listOf(toothpaste.copy(id = onFirst)), second to listOf(mug.copy(id = onSecond))),
            repository.barcodesByAlarm.first(),
        )
    }

    @Test
    fun `an alarm without barcodes has an empty list and no entry`() = runTest {
        val (repository, first, second) = withAlarms()
        repository.add(first, mug)

        assertTrue(repository.all(second).isEmpty())
        assertTrue(repository.barcodes(second).first().isEmpty())
        assertTrue(repository.all(second + 100).isEmpty())
        assertEquals(setOf(first), repository.barcodesByAlarm.first().keys)
    }

    @Test
    fun `the same barcode is not stored twice on one alarm, the label is updated`() = runTest {
        val (repository, alarm, _) = withAlarms()
        val id = repository.add(alarm, toothpaste)
        val sameCodeAsEan = Barcode.from("bathroom", ScannedCode(BarcodeFormat.EAN_13, "0036000291452"))

        val again = repository.add(alarm, sameCodeAsEan)

        assertEquals(id, again)
        assertEquals(listOf(toothpaste.copy(id = id, label = "bathroom")), repository.all(alarm))
    }

    @Test
    fun `the same barcode on two alarms is two barcodes`() = runTest {
        val (repository, first, second) = withAlarms()

        val onFirst = repository.add(first, mug)
        val onSecond = repository.add(second, mug.copy(label = "kitchen mug"))
        repository.rename(onSecond, "coffee")
        repository.delete(onFirst)

        assertNotEquals(onFirst, onSecond)
        assertTrue(repository.all(first).isEmpty())
        assertEquals(listOf(mug.copy(id = onSecond, label = "coffee")), repository.all(second))
    }

    @Test
    fun `rename changes the label`() = runTest {
        val (repository, alarm, _) = withAlarms()
        val id = repository.add(alarm, mug)

        repository.rename(id, "coffee mug")
        repository.rename(id + 100, "nothing")

        assertEquals(listOf(mug.copy(id = id, label = "coffee mug")), repository.all(alarm))
    }

    @Test
    fun `delete removes the barcode`() = runTest {
        val (repository, alarm, _) = withAlarms()
        val id = repository.add(alarm, mug)

        repository.delete(id + 100)
        assertEquals(1, repository.all(alarm).size)

        repository.delete(id)
        assertTrue(repository.barcodes(alarm).first().isEmpty())
        assertTrue(repository.barcodesByAlarm.first().isEmpty())
    }
}
