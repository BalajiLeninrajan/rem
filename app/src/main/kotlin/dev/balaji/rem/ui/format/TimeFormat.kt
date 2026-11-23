package dev.balaji.rem.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Hours and minutes in the user's 12 or 24 hour setting. */
@Composable
fun rememberClockFormatter(): DateTimeFormatter {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = currentLocale()
    return remember(is24Hour, locale) { DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale) }
}

@Composable
fun clockText(time: LocalTime): String = rememberClockFormatter().format(time)

@Composable
fun clockText(instant: Instant, zone: ZoneId): String = rememberClockFormatter().format(instant.atZone(zone))

/** The time of [at], with the day in front when it is not today: "06:45", "tomorrow 06:45", "Wed 06:45". */
@Composable
fun ringTimeText(at: Instant, now: Instant, zone: ZoneId): String {
    val time = clockText(at, zone)
    return when (daysBetween(now, at, zone)) {
        0L -> time
        1L -> stringResource(R.string.ui_time_tomorrow, time)
        else -> {
            val day = at.atZone(zone).dayOfWeek.getDisplayName(TextStyle.SHORT, currentLocale())
            stringResource(R.string.ui_time_on_day, day, time)
        }
    }
}

/** Calendar days from [from] to [to] in [zone]. 23:59 to 00:01 is one day. */
fun daysBetween(from: Instant, to: Instant, zone: ZoneId): Long =
    ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), to.atZone(zone).toLocalDate())
