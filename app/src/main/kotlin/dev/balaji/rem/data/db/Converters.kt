package dev.balaji.rem.data.db

import androidx.room.TypeConverter
import dev.balaji.rem.core.RepeatDays
import java.time.DayOfWeek
import java.time.LocalTime

class Converters {
    /** Stored as the second of the day. Fractions of a second are dropped, alarm times never have any. */
    @TypeConverter
    fun timeToSecondOfDay(time: LocalTime): Int = time.toSecondOfDay()

    @TypeConverter
    fun secondOfDayToTime(secondOfDay: Int): LocalTime = LocalTime.ofSecondOfDay(secondOfDay.toLong())

    @TypeConverter
    fun repeatDaysToMask(days: Set<DayOfWeek>): Int = RepeatDays.toMask(days)

    @TypeConverter
    fun maskToRepeatDays(mask: Int): Set<DayOfWeek> = RepeatDays.fromMask(mask)
}
