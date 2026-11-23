package dev.balaji.rem.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RemSettingsTest {
    @Test
    fun `defaults match the product spec and are in range`() {
        val defaults = RemSettings()
        assertEquals(RemSettings(90, 15, 30, 120, headphonesOnly = false), defaults)
        assertEquals(defaults, defaults.coerced())
    }

    @Test
    fun `values below the range are raised to the minimum`() {
        val low = RemSettings(0, -1, -1, 0).coerced()
        assertEquals(RemSettings(60, 0, 0, 30), low)
    }

    @Test
    fun `values above the range are lowered to the maximum`() {
        val high = RemSettings(500, 500, 500, 5000).coerced()
        assertEquals(RemSettings(120, 60, 90, 600), high)
    }

    @Test
    fun `coercion keeps the headphones flag`() {
        assertEquals(true, RemSettings(cycleMinutes = 0, headphonesOnly = true).coerced().headphonesOnly)
    }
}
