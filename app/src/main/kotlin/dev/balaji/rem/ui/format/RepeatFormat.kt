package dev.balaji.rem.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.core.RepeatDays
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** The seven days starting with the first day of the week in [locale]: Sunday in the US, Monday in Germany. */
fun weekInLocaleOrder(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return List(DayOfWeek.entries.size) { first.plus(it.toLong()) }
}

private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** "Once", "Every day", "Weekdays", "Weekends", or the short day names in the locale's week order. */
@Composable
fun repeatSummary(days: Set<DayOfWeek>): String = when (days) {
    RepeatDays.NONE -> stringResource(R.string.ui_repeat_once)
    RepeatDays.EVERY_DAY -> stringResource(R.string.ui_repeat_every_day)
    RepeatDays.WEEKDAYS -> stringResource(R.string.ui_repeat_weekdays)
    WEEKEND -> stringResource(R.string.ui_repeat_weekends)
    else -> {
        val locale = currentLocale()
        weekInLocaleOrder(locale).filter { it in days }.joinToString { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}
