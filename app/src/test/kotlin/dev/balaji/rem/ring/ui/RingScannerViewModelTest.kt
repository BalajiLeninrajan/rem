package dev.balaji.rem.ring.ui

import dev.balaji.rem.ring.IdleRingController
import org.junit.Assert.assertEquals
import org.junit.Test

class RingScannerViewModelTest {
    @Test
    fun `opening the scanner starts one quiet period`() {
        val controller = IdleRingController()

        RingScannerViewModel(controller)

        assertEquals(1, controller.quietPeriodsStarted)
    }
}
