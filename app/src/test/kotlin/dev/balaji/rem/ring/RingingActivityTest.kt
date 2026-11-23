package dev.balaji.rem.ring

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingingActivityTest {
    private val at = Instant.parse("2026-01-15T06:45:00Z")
    private val ringing = RingingState.Ringing(1, "", at, at, requiresBarcode = true)
    private val snoozed = RingingState.Snoozed(1, "", at, at.plusSeconds(300), snoozesLeft = 2, requiresBarcode = true)

    @Test
    fun `the screen closes when nothing rings or is snoozed`() {
        assertTrue(closesRingScreen(null, RingingState.Idle))
        assertTrue(closesRingScreen(ringing, RingingState.Idle))
        assertTrue(closesRingScreen(snoozed, RingingState.Idle))
    }

    @Test
    fun `the screen closes when the ring it shows is snoozed`() {
        assertTrue(closesRingScreen(ringing, snoozed))
        assertTrue(closesRingScreen(ringing.copy(alarmId = 2), snoozed))
    }

    @Test
    fun `opened for a snooze the screen stays, also when the snooze rings again`() {
        assertFalse(closesRingScreen(null, snoozed))
        assertFalse(closesRingScreen(snoozed, snoozed))
        assertFalse(closesRingScreen(snoozed, ringing))
        assertFalse(closesRingScreen(null, ringing))
    }

    @Test
    fun `the screen closes when the snooze it shows gives way to another`() {
        assertTrue(closesRingScreen(snoozed, snoozed.copy(alarmId = 2)))
    }
}
