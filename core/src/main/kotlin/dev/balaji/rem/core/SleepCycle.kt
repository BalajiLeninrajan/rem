package dev.balaji.rem.core

import java.time.Duration

/** One rem is one sleep cycle. Placeholder until the wake-time calculation lands. */
object SleepCycle {
    val LENGTH: Duration = Duration.ofMinutes(90)

    fun duration(cycles: Int): Duration {
        require(cycles >= 0) { "cycles must not be negative, was $cycles" }
        return LENGTH.multipliedBy(cycles.toLong())
    }
}
