package dev.balaji.rem.data.contract

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behaviour every [BarcodeRepository] must have. Subclass it once per implementation. */
abstract class BarcodeRepositoryContract {
    abstract fun createRepository(): BarcodeRepository

    private val toothpaste = Barcode.from("toothpaste", ScannedCode(BarcodeFormat.UPC_A, "036000291452"))
    private val mug = Barcode.from("mug", ScannedCode(BarcodeFormat.QR_CODE, "mug"))

    @Test
    fun `barcodes are listed oldest first with their ids`() = runTest {
        val repository = createRepository()

        val first = repository.add(toothpaste)
        val second = repository.add(mug)

        assertTrue(first > 0 && second > 0)
        assertEquals(listOf(toothpaste.copy(id = first), mug.copy(id = second)), repository.barcodes.first())
        assertEquals(repository.barcodes.first(), repository.all())
    }

    @Test
    fun `the same barcode is not stored twice, the label is updated`() = runTest {
        val repository = createRepository()
        val id = repository.add(toothpaste)
        val sameCodeAsEan = Barcode.from("bathroom", ScannedCode(BarcodeFormat.EAN_13, "0036000291452"))

        val again = repository.add(sameCodeAsEan)

        assertEquals(id, again)
        assertEquals(listOf(toothpaste.copy(id = id, label = "bathroom")), repository.all())
    }

    @Test
    fun `rename changes the label`() = runTest {
        val repository = createRepository()
        val id = repository.add(mug)

        repository.rename(id, "coffee mug")
        repository.rename(id + 100, "nothing")

        assertEquals(listOf(mug.copy(id = id, label = "coffee mug")), repository.all())
    }

    @Test
    fun `delete removes the barcode`() = runTest {
        val repository = createRepository()
        val id = repository.add(mug)

        repository.delete(id + 100)
        assertEquals(1, repository.all().size)

        repository.delete(id)
        assertTrue(repository.barcodes.first().isEmpty())
    }
}
