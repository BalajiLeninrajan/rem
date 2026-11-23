package dev.balaji.rem.core

import java.time.DayOfWeek

/** Packs repeat days into an int for storage. Bit 0 is Monday, bit 6 is Sunday. */
object RepeatDays {
    val NONE: Set<DayOfWeek> = emptySet()
    val EVERY_DAY: Set<DayOfWeek> = DayOfWeek.entries.toSet()
    val WEEKDAYS: Set<DayOfWeek> = EVERY_DAY - setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun toMask(days: Set<DayOfWeek>): Int = days.fold(0) { mask, day -> mask or bit(day) }

    /** Bits above Sunday are ignored. */
    fun fromMask(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filterTo(mutableSetOf()) { mask and bit(it) != 0 }

    private fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
}
