package dev.balaji.rem.core

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * The typed escape hatch for a lost barcode or a broken camera: a long random string the
 * user has to copy by hand.
 */
object FallbackCode {
    const val LENGTH = 40

    /** Letters and digits without the pairs that look alike on screen (0 O o, 1 l I). */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    fun generate(random: Random): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun generate(random: SecureRandom): String = generate(random.asKotlinRandom())

    /** Exact and case sensitive. Only whitespace around the typed text is forgiven. */
    fun matches(expected: String, typed: String): Boolean = expected.length == LENGTH && typed.trim() == expected

    /** How many leading characters of [typed] are right, for showing progress while typing. */
    fun correctPrefixLength(expected: String, typed: String): Int = expected.commonPrefixWith(typed).length
}
