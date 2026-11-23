package dev.balaji.rem.ui.navigation

import dev.balaji.rem.core.Alarm
import kotlinx.serialization.Serializable

/** Alarm list, going-to-sleep button, session state and warnings. The start destination. */
@Serializable
data object HomeRoute

/** @property alarmId [Alarm.NEW_ID] creates a new alarm. */
@Serializable
data class AlarmEditorRoute(val alarmId: Long = Alarm.NEW_ID)

/**
 * The scanner for the alarm in the [AlarmEditorRoute] right below it. Opened only from the editor.
 *
 * @property testOnly true checks a scan against the alarm's barcodes, false adds what is scanned to the alarm.
 */
@Serializable
data class BarcodeScanRoute(val testOnly: Boolean = false)

@Serializable
data object SettingsRoute

@Serializable
data object ReliabilityRoute

/** First run: explains and requests the notification permission. */
@Serializable
data object OnboardingRoute
