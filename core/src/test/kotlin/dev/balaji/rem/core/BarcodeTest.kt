package dev.balaji.rem.core

import dev.balaji.rem.core.BarcodeFormat.CODE_128
import dev.balaji.rem.core.BarcodeFormat.CODE_39
import dev.balaji.rem.core.BarcodeFormat.EAN_13
import dev.balaji.rem.core.BarcodeFormat.EAN_8
import dev.balaji.rem.core.BarcodeFormat.QR_CODE
import dev.balaji.rem.core.BarcodeFormat.UPC_A
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeTest {
    @Test
    fun `UPC-A and its EAN-13 form with a leading zero are the same barcode`() {
        val upc = ScannedCode(UPC_A, "036000291452")
        val ean = ScannedCode(EAN_13, "0036000291452")

        assertEquals(BarcodeKey(EAN_13, "0036000291452"), upc.normalised())
        assertEquals(upc.normalised(), ean.normalised())
    }

    @Test
    fun `a real EAN-13 is not touched`() {
        assertEquals(BarcodeKey(EAN_13, "4006381333931"), ScannedCode(EAN_13, "4006381333931").normalised())
    }

    @Test
    fun `EAN-13 codes that differ only in the first digit stay different`() {
        assertNotEquals(
            ScannedCode(EAN_13, "1036000291452").normalised(),
            ScannedCode(UPC_A, "036000291452").normalised(),
        )
    }

    @Test
    fun `numeric formats drop whitespace anywhere`() {
        assertEquals(BarcodeKey(EAN_13, "4006381333931"), ScannedCode(EAN_13, " 4 006381 333931\n").normalised())
        assertEquals(BarcodeKey(EAN_8, "96385074"), ScannedCode(EAN_8, "9638 5074").normalised())
    }

    @Test
    fun `Code 39 ignores case, QR and Code 128 do not`() {
        assertEquals(ScannedCode(CODE_39, "abc-123").normalised(), ScannedCode(CODE_39, "ABC-123").normalised())
        assertNotEquals(ScannedCode(QR_CODE, "https://Example.com/a").normalised(), ScannedCode(QR_CODE, "https://example.com/a").normalised())
        assertNotEquals(ScannedCode(CODE_128, "abc").normalised(), ScannedCode(CODE_128, "ABC").normalised())
    }

    @Test
    fun `text formats keep inner whitespace and lose the outer`() {
        assertEquals(BarcodeKey(QR_CODE, "hello  world"), ScannedCode(QR_CODE, "  hello  world\n").normalised())
    }

    @Test
    fun `the same digits in different formats do not match`() {
        assertNotEquals(ScannedCode(QR_CODE, "96385074").normalised(), ScannedCode(EAN_8, "96385074").normalised())
    }

    @Test
    fun `a registered UPC-A matches a later EAN-13 scan`() {
        val toothpaste = Barcode.from("toothpaste", ScannedCode(UPC_A, "036000291452")).copy(id = 7)
        val registered = listOf(Barcode(1, "mug", QR_CODE, "mug"), toothpaste)

        assertEquals(toothpaste, registered.findMatch(ScannedCode(EAN_13, "0036000291452")))
        assertEquals(toothpaste, registered.findMatch(ScannedCode(UPC_A, " 036000291452 ")))
        assertNull(registered.findMatch(ScannedCode(UPC_A, "036000291453")))
        assertNull(emptyList<Barcode>().findMatch(ScannedCode(UPC_A, "036000291452")))
    }

    @Test
    fun `a blank scan never matches, even against a blank registration`() {
        val blank = Barcode(1, "blank", QR_CODE, "")

        assertFalse(ScannedCode(QR_CODE, "   ").normalised().isUsable)
        assertNull(listOf(blank).findMatch(ScannedCode(QR_CODE, "   ")))
    }
}
