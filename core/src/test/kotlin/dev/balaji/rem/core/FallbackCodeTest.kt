package dev.balaji.rem.core

import java.security.SecureRandom
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCodeTest {
    @Test
    fun `code has 40 characters from the alphabet`() {
        repeat(50) { seed ->
            val code = FallbackCode.generate(Random(seed))
            assertEquals(40, code.length)
            assertTrue(code.all { it in FallbackCode.ALPHABET })
        }
    }

    @Test
    fun `alphabet has letters of both cases and digits, without look-alikes`() {
        val alphabet = FallbackCode.ALPHABET
        assertTrue(alphabet.any(Char::isUpperCase) && alphabet.any(Char::isLowerCase) && alphabet.any(Char::isDigit))
        assertTrue(alphabet.all { it.isLetterOrDigit() && it.code < 128 })
        assertTrue("0Oo1lI".none { it in alphabet })
        assertEquals(alphabet.length, alphabet.toSet().size)
    }

    @Test
    fun `same seed gives the same code, different seeds differ`() {
        assertEquals(FallbackCode.generate(Random(1)), FallbackCode.generate(Random(1)))
        assertNotEquals(FallbackCode.generate(Random(1)), FallbackCode.generate(Random(2)))
    }

    @Test
    fun `secure random source works and does not repeat`() {
        val random = SecureRandom()
        assertNotEquals(FallbackCode.generate(random), FallbackCode.generate(random))
    }

    @Test
    fun `only the exact code matches`() {
        val code = FallbackCode.generate(Random(7))

        assertTrue(FallbackCode.matches(code, code))
        assertTrue(FallbackCode.matches(code, " $code\n"))
        assertFalse(FallbackCode.matches(code, code.dropLast(1)))
        assertFalse(FallbackCode.matches(code, code + "a"))
        assertFalse(FallbackCode.matches(code, code.swapCaseAt(code.indexOfFirst(Char::isLetter))))
        assertFalse(FallbackCode.matches(code, ""))
    }

    @Test
    fun `an empty expected code never matches`() {
        assertFalse(FallbackCode.matches("", ""))
    }

    @Test
    fun `correct prefix length counts up to the first wrong character`() {
        assertEquals(0, FallbackCode.correctPrefixLength("abcdef", ""))
        assertEquals(3, FallbackCode.correctPrefixLength("abcdef", "abcXef"))
        assertEquals(6, FallbackCode.correctPrefixLength("abcdef", "abcdefgh"))
    }

    private fun String.swapCaseAt(index: Int): String {
        val swapped = if (this[index].isUpperCase()) this[index].lowercaseChar() else this[index].uppercaseChar()
        return replaceRange(index, index + 1, swapped.toString())
    }
}
