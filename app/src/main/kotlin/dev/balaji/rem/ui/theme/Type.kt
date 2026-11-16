package dev.balaji.rem.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/** The Material 3 scale with heavier headlines and titles, so screen and card headings carry more weight. */
val RemTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private const val TABULAR_FIGURES = "tnum"

/**
 * Styles for clock times. Tabular figures keep the digits the same width, so a time does
 * not shift sideways when it changes.
 */
object ClockType {
    /** The one time a screen is about: the editor's deadline, tonight's ring time. */
    val hero = TextStyle(
        fontSize = 76.sp,
        lineHeight = 80.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-2).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )

    /** The deadline on an alarm card. */
    val card = TextStyle(
        fontSize = 48.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1).sp,
        fontFeatureSettings = TABULAR_FIGURES,
    )
}
