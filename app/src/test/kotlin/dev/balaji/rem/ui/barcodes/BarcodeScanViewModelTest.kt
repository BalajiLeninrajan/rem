package dev.balaji.rem.ui.barcodes

import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.BarcodeKey
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.ui.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BarcodeScanViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val target = ListTarget()

    private val toothpaste = ScannedCode(BarcodeFormat.EAN_13, "4006381333931")
    private val toothpasteKey = BarcodeKey(BarcodeFormat.EAN_13, "4006381333931")

    // The same physical barcode, reported in its two formats.
    private val cerealAsUpcA = ScannedCode(BarcodeFormat.UPC_A, "036000291452")
    private val cerealAsEan13 = ScannedCode(BarcodeFormat.EAN_13, "0036000291452")

    @Test
    fun `a new barcode waits for a name and is handed to the target with it`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, target)

        scan.onScanned(toothpaste)
        advanceUntilIdle()
        assertEquals(ScanStage.Captured(toothpasteKey), scan.state.value.stage)

        scan.save()
        advanceUntilIdle()
        assertTrue("a blank name must not be added", target.barcodes.isEmpty())

        scan.setLabel("  Toothpaste ")
        scan.save()
        advanceUntilIdle()

        assertEquals(listOf(Barcode(label = "Toothpaste", format = BarcodeFormat.EAN_13, value = "4006381333931")), target.barcodes)
        assertEquals(ScanStage.Saved, scan.state.value.stage)
    }

    @Test
    fun `a barcode the alarm has in its other format is rejected as a duplicate`() = runTest {
        val existing = Barcode(id = 4, label = "Cereal", format = BarcodeFormat.EAN_13, value = "0036000291452")
        target.barcodes += existing
        val scan = BarcodeScanViewModel(testOnly = false, target)

        scan.onScanned(cerealAsUpcA)
        advanceUntilIdle()
        assertEquals(ScanStage.Duplicate(existing), scan.state.value.stage)

        // Nothing to name and nothing to save from here.
        scan.setLabel("Second name")
        scan.save()
        advanceUntilIdle()
        assertEquals(listOf(existing), target.barcodes)

        scan.scanAgain()
        assertEquals(ScanStage.Scanning, scan.state.value.stage)
    }

    @Test
    fun `a duplicate that appears between scan and save keeps its first name`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, target)
        scan.onScanned(toothpaste)
        advanceUntilIdle()
        scan.setLabel("Second")

        val first = Barcode.from("First", toothpaste)
        target.barcodes += first
        scan.save()
        advanceUntilIdle()

        assertEquals(listOf(first), target.barcodes)
        assertEquals(ScanStage.Duplicate(first), scan.state.value.stage)
    }

    @Test
    fun `only the first scan counts until the user asks to scan again`() = runTest {
        val scan = BarcodeScanViewModel(testOnly = false, target)

        scan.onScanned(ScannedCode(BarcodeFormat.QR_CODE, "   "))
        advanceUntilIdle()
        assertEquals("a blank scan is ignored", ScanStage.Scanning, scan.state.value.stage)

        scan.onScanned(toothpaste)
        scan.onScanned(cerealAsUpcA)
        advanceUntilIdle()
        assertEquals(ScanStage.Captured(toothpasteKey), scan.state.value.stage)
    }

    @Test
    fun `a test scan names the match and adds nothing`() = runTest {
        val cereal = Barcode.from("Cereal", cerealAsUpcA)
        target.barcodes += cereal
        val scan = BarcodeScanViewModel(testOnly = true, target)

        scan.onScanned(cerealAsEan13)
        advanceUntilIdle()
        assertEquals(ScanStage.Tested(cerealAsEan13.normalised(), cereal), scan.state.value.stage)

        scan.scanAgain()
        scan.onScanned(toothpaste)
        advanceUntilIdle()
        assertEquals(ScanStage.Tested(toothpasteKey, match = null), scan.state.value.stage)
        assertEquals(listOf(cereal), target.barcodes)
    }

    /** The barcodes of one alarm, as a plain list. */
    private class ListTarget : BarcodeTarget {
        var barcodes: List<Barcode> = emptyList()

        override suspend fun currentBarcodes(): List<Barcode> = barcodes

        override suspend fun addBarcode(barcode: Barcode) {
            barcodes = barcodes + barcode
        }
    }
}
