package dev.balaji.rem.core

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SleepCycleTest {
    @Test
    fun `five cycles last seven and a half hours`() {
        assertEquals(Duration.ofMinutes(450), SleepCycle.duration(5))
    }

    @Test
    fun `negative cycle count is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SleepCycle.duration(-1) }
    }
}
