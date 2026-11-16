package dev.balaji.rem.ring.ui

import dev.balaji.rem.ring.IdleRingController
import org.junit.Assert.assertEquals
import org.junit.Test

class TypedFallbackViewModelTest {
    private val viewModel = TypedFallbackViewModel(IdleRingController())

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
    fun `deleting is always allowed`() {
        val code = viewModel.code
        viewModel.onTyped(code.take(1))
        viewModel.onTyped(code.take(2))
        viewModel.onTyped("")

        assertEquals("", viewModel.typed)
    }
}
