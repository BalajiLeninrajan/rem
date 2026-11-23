package dev.balaji.rem.ring.ui

import dev.balaji.rem.ring.IdleRingController
import org.junit.Assert.assertEquals
import org.junit.Test

class RingScannerViewModelTest {
    @Test
    fun `a scanner made from saved state leaves the ring loud`() {
        val controller = IdleRingController()

        RingScannerViewModel(controller)

        // The tap that opens the scanner starts the quiet period, not the scanner itself.
        assertEquals(0, controller.quietPeriodsStarted)
    }
}
