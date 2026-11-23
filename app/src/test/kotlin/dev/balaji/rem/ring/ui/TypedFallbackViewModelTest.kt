package dev.balaji.rem.ring.ui

import dev.balaji.rem.ring.IdleRingController
import org.junit.Assert.assertEquals
import org.junit.Test

class TypedFallbackViewModelTest {
    private val controller = IdleRingController()
    private val viewModel = TypedFallbackViewModel(controller)

    @Test
    fun `a typed fallback screen made from saved state leaves the ring loud`() {
        assertEquals(0, controller.quietPeriodsStarted)
    }

    @Test
    fun `typing one character at a time is accepted and counted`() {
        val code = viewModel.code
        code.take(3).forEachIndexed { index, _ -> viewModel.onTyped(code.take(index + 1)) }

        assertEquals(code.take(3), viewModel.typed)
        assertEquals(3, viewModel.correctLength)
    }

    @Test
    fun `a wrong character is kept and stops the count`() {
        val code = viewModel.code
        viewModel.onTyped(code.take(1))
        viewModel.onTyped(code.take(1) + "!")

        assertEquals(2, viewModel.typed.length)
        assertEquals(1, viewModel.correctLength)
    }

    @Test
    fun `pasting the code is refused`() {
        viewModel.onTyped(viewModel.code)

        assertEquals("", viewModel.typed)
    }

    @Test
    fun `a paste over selected text is refused even when the length grows by one`() {
        val code = viewModel.code
        val filler = "!".repeat(code.length - 1)
        filler.indices.forEach { viewModel.onTyped(filler.take(it + 1)) }

        viewModel.onTyped(code)

        assertEquals(filler, viewModel.typed)
    }

    @Test
    fun `a paste in front of typed text is refused`() {
        val code = viewModel.code
        val filler = "!".repeat(code.length - 1)
        filler.indices.forEach { viewModel.onTyped(filler.take(it + 1)) }

        // The field cuts the result to the code length, which would leave exactly the code.
        viewModel.onTyped(code + filler)

        assertEquals(filler, viewModel.typed)
    }

    @Test
    fun `a replaced character is refused and a typo can be fixed in the middle`() {
        viewModel.onTyped("a")
        viewModel.onTyped("ab")
        viewModel.onTyped("abc")

        viewModel.onTyped("axc")
        assertEquals("abc", viewModel.typed)

        viewModel.onTyped("ac")
        viewModel.onTyped("azc")
        assertEquals("azc", viewModel.typed)
    }

    @Test
    fun `deleting is always allowed`() {
        val code = viewModel.code
        viewModel.onTyped(code.take(1))
        viewModel.onTyped(code.take(2))
        viewModel.onTyped("")

        assertEquals("", viewModel.typed)
    }
}
