package dev.balaji.rem.ring.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class QuietNoticeTest {
    private val now = Instant.parse("2026-01-15T06:45:00Z")

    @Test
    fun `seconds left round up and stop at zero`() {
        assertEquals(0L, quietSecondsLeft(null, now))
        assertEquals(60L, quietSecondsLeft(now.plusSeconds(60), now))
        assertEquals(60L, quietSecondsLeft(now.plusMillis(59_001), now))
        assertEquals(1L, quietSecondsLeft(now.plusMillis(1), now))
        assertEquals(0L, quietSecondsLeft(now, now))
        assertEquals(0L, quietSecondsLeft(now.minusSeconds(5), now))
    }

    @Test
    fun `the time left reads as minutes and seconds`() {
        assertEquals("1:00", formatQuietTime(60))
        assertEquals("0:42", formatQuietTime(42))
        assertEquals("0:05", formatQuietTime(5))
    }
}
