package dev.balaji.rem.ui.barcodes

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.BarcodeKey
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.data.fake.InMemoryBarcodeRepository
import dev.balaji.rem.ui.testing.MainDispatcherRule
import dev.balaji.rem.ui.testing.keepCollecting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeViewModelsTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repository = InMemoryBarcodeRepository()

    private val toothpaste = ScannedCode(BarcodeFormat.EAN_13, "4006381333931")
    private val toothpasteKey = BarcodeKey(BarcodeFormat.EAN_13, "4006381333931")

    // The same physical barcode, reported in its two formats.
    private val cerealAsUpcA = ScannedCode(BarcodeFormat.UPC_A, "036000291452")
    private val cerealAsEan13 = ScannedCode(BarcodeFormat.EAN_13, "0036000291452")

    @Test
    fun `a new barcode waits for a name and is stored with it`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, repository)

        scan.onScanned(toothpaste)
        advanceUntilIdle()
        assertEquals(ScanStage.Captured(toothpasteKey), scan.state.value.stage)

        scan.save()
        advanceUntilIdle()
        assertTrue("a blank name must not be stored", repository.all().isEmpty())

        scan.setLabel("  Toothpaste ")
        scan.save()
        advanceUntilIdle()

        val stored = repository.all().single()
        assertEquals(Barcode(stored.id, "Toothpaste", BarcodeFormat.EAN_13, "4006381333931"), stored)
        assertEquals(ScanStage.Saved, scan.state.value.stage)
    }

    @Test
    fun `a barcode registered in its other format is rejected as a duplicate`() = runTest {
        val id = repository.add(Barcode.from("Cereal", cerealAsUpcA))
        val existing = repository.all().single()
        val scan = BarcodeScanViewModel(testOnly = false, repository)

        scan.onScanned(cerealAsEan13)
        advanceUntilIdle()
        assertEquals(ScanStage.Duplicate(existing), scan.state.value.stage)

        // Nothing to name and nothing to save from here.
        scan.setLabel("Second name")
        scan.save()
        advanceUntilIdle()
        assertEquals(listOf(Barcode(id, "Cereal", BarcodeFormat.EAN_13, "0036000291452")), repository.all())

        scan.scanAgain()
        assertEquals(ScanStage.Scanning, scan.state.value.stage)
    }

    @Test
    fun `a duplicate that appears between scan and save keeps its first name`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, repository)
        scan.onScanned(toothpaste)
        advanceUntilIdle()
        scan.setLabel("Second")

        repository.add(Barcode.from("First", toothpaste))
        scan.save()
        advanceUntilIdle()

        val existing = repository.all().single()
        assertEquals("First", existing.label)
        assertEquals(ScanStage.Duplicate(existing), scan.state.value.stage)
    }

    @Test
    fun `only the first scan counts until the user asks to scan again`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, repository)

        scan.onScanned(ScannedCode(BarcodeFormat.QR_CODE, "   "))
        advanceUntilIdle()
        assertEquals("a blank scan is ignored", ScanStage.Scanning, scan.state.value.stage)

        scan.onScanned(toothpaste)
        scan.onScanned(cerealAsUpcA)
        advanceUntilIdle()
        assertEquals(ScanStage.Captured(toothpasteKey), scan.state.value.stage)
    }

    @Test
    fun `a test scan names the match and registers nothing`() = runTest {
        repository.add(Barcode.from("Cereal", cerealAsUpcA))
        val registered = repository.all()
        val scan = BarcodeScanViewModel(testOnly = true, repository)

        scan.onScanned(cerealAsEan13)
        advanceUntilIdle()
        assertEquals(ScanStage.Tested(cerealAsEan13.normalised(), registered.single()), scan.state.value.stage)

        scan.scanAgain()
        scan.onScanned(toothpaste)
        advanceUntilIdle()
        assertEquals(ScanStage.Tested(toothpasteKey, match = null), scan.state.value.stage)
        assertEquals(registered, repository.all())
    }

    @Test
    fun `the list renames with a trimmed name, ignores a blank one and deletes`() = runTest {
        val id = repository.add(Barcode.from("Cereal", cerealAsUpcA))
        val list = BarcodesViewModel(repository)
        keepCollecting(list.state)
        advanceUntilIdle()
        assertEquals(listOf("Cereal"), list.state.value.barcodes.map { it.label })

        list.rename(id, "  Oats ")
        list.rename(id, "   ")
        advanceUntilIdle()
        assertEquals(listOf("Oats"), list.state.value.barcodes.map { it.label })

        list.delete(id)
        advanceUntilIdle()
        assertTrue(list.state.value.barcodes.isEmpty())
    }
}
