package dev.balaji.rem.core

import dev.balaji.rem.core.BarcodeFormat.EAN_13
import dev.balaji.rem.core.BarcodeFormat.QR_CODE
import dev.balaji.rem.core.BarcodeFormat.UPC_A
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StableScanFilterTest {
    private val a = ScannedCode(QR_CODE, "a")
    private val b = ScannedCode(QR_CODE, "b")
    private val filter = StableScanFilter()

    @Test
    fun `a code is reported on its second consecutive frame, once`() {
        assertTrue(filter.onFrame(listOf(a)).isEmpty())
        assertEquals(listOf(a), filter.onFrame(listOf(a)))
        assertTrue(filter.onFrame(listOf(a)).isEmpty())
    }

    @Test
    fun `an empty frame in between resets the count`() {
        filter.onFrame(listOf(a))
        filter.onFrame(emptyList())
        assertTrue(filter.onFrame(listOf(a)).isEmpty())
        assertEquals(listOf(a), filter.onFrame(listOf(a)))
    }

    @Test
    fun `a misread between two good frames resets the count`() {
        filter.onFrame(listOf(a))
        filter.onFrame(listOf(b))
        assertTrue(filter.onFrame(listOf(a)).isEmpty())
    }

    @Test
    fun `a code is reported again after it left the frame`() {
        filter.onFrame(listOf(a))
        filter.onFrame(listOf(a))
        filter.onFrame(emptyList())
        filter.onFrame(listOf(a))
        assertEquals(listOf(a), filter.onFrame(listOf(a)))
    }

    @Test
    fun `codes in the same frame are tracked separately`() {
        filter.onFrame(listOf(a))
        assertEquals(listOf(a), filter.onFrame(listOf(a, b)))
        assertEquals(listOf(b), filter.onFrame(listOf(a, b)))
    }

    @Test
    fun `UPC-A and EAN-13 readings of one code count as the same code`() {
        val asUpc = ScannedCode(UPC_A, "036000291452")
        val asEan = ScannedCode(EAN_13, "0036000291452")

        filter.onFrame(listOf(asUpc))
        assertEquals(listOf(asEan), filter.onFrame(listOf(asEan)))
    }

    @Test
    fun `blank codes are ignored`() {
        val blank = ScannedCode(QR_CODE, " ")
        filter.onFrame(listOf(blank))
        assertTrue(filter.onFrame(listOf(blank)).isEmpty())
    }

    @Test
    fun `reset forgets the streak`() {
        filter.onFrame(listOf(a))
        filter.reset()
        assertTrue(filter.onFrame(listOf(a)).isEmpty())
    }

    @Test
    fun `required frames is configurable and validated`() {
        val strict = StableScanFilter(requiredFrames = 3)
        strict.onFrame(listOf(a))
        assertTrue(strict.onFrame(listOf(a)).isEmpty())
        assertEquals(listOf(a), strict.onFrame(listOf(a)))
        assertThrows(IllegalArgumentException::class.java) { StableScanFilter(0) }
    }
}
