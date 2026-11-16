package dev.balaji.rem.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.balaji.rem.ui.format.clockText
import java.time.LocalTime

private const val MARKER_SCALE = 0.35f

/**
 * A clock time in a large [style]. In 12 hour mode the AM or PM marker is drawn small, so
 * the digits keep their size and the text still fits a phone width.
 */
@Composable
fun ClockText(
    time: LocalTime,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val text = clockText(time)
    val digits = text.substringBefore(' ')
    val marker = text.substringAfter(' ', missingDelimiterValue = "")
    Text(
        text = buildAnnotatedString {
            append(digits)
            if (marker.isNotEmpty()) {
                withStyle(SpanStyle(fontSize = style.fontSize * MARKER_SCALE, letterSpacing = 0.sp)) {
                    append(' ')
                    append(marker)
                }
            }
        },
        modifier = modifier,
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
    )
}
