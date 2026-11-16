package dev.balaji.rem.core

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepSessionTest {
    private val start = toronto("2026-01-14T23:00")
    private val session = SleepSession(start)

    @Test
    fun `covers deadlines after the start and up to 16 hours later`() {
        assertFalse(session.covers(start))
        assertTrue(session.covers(start.plusSeconds(1)))
        assertTrue(session.covers(start.plus(Duration.ofHours(16))))
        assertFalse(session.covers(start.plus(Duration.ofHours(16)).plusSeconds(1)))
        assertFalse(session.covers(start.minusSeconds(1)))
    }

    @Test
    fun `stops being active after 16 hours`() {
        assertTrue(session.isActive(start))
        assertTrue(session.isActive(start.plus(Duration.ofHours(16)).minusSeconds(1)))
        assertFalse(session.isActive(start.plus(Duration.ofHours(16))))
    }

    @Test
    fun `sleep onset adds the fall asleep time and drops seconds`() {
        val withSeconds = SleepSession(start.plusSeconds(42))
        assertEquals(toronto("2026-01-14T23:15"), withSeconds.sleepOnset(RemSettings()))
        assertEquals(start, withSeconds.sleepOnset(RemSettings(fallAsleepMinutes = 0)))
    }
}
