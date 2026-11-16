package dev.balaji.rem.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test
    fun `defaults match the product spec and are in range`() {
        val defaults = Settings()
        assertEquals(Settings(90, 15, 30, 120, 70, 30), defaults)
        assertEquals(defaults, defaults.coerced())
    }

    @Test
    fun `values below the range are raised to the minimum`() {
        val low = Settings(0, -1, -1, 0, -10, -1).coerced()
        assertEquals(Settings(60, 0, 0, 30, 0, 0), low)
    }

    @Test
    fun `values above the range are lowered to the maximum`() {
        val high = Settings(500, 500, 500, 5000, 500, 5000).coerced()
        assertEquals(Settings(120, 60, 90, 600, 100, 300), high)
    }
}
