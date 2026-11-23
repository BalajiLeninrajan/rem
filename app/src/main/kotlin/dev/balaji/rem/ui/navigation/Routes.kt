package dev.balaji.rem.ui.navigation

import dev.balaji.rem.core.Alarm
import kotlinx.serialization.Serializable

/** Alarm list, going-to-sleep button, session state and warnings. The start destination. */
@Serializable
data object HomeRoute

/** @property alarmId [Alarm.NEW_ID] creates a new alarm. */
@Serializable
data class AlarmEditorRoute(val alarmId: Long = Alarm.NEW_ID)

/** List of registered barcodes with rename and delete. */
@Serializable
data object BarcodesRoute

/** @property testOnly true checks a scan against the registered barcodes, false registers what is scanned. */
@Serializable
data class BarcodeScanRoute(val testOnly: Boolean = false)

@Serializable
data object SettingsRoute

@Serializable
data object ReliabilityRoute

/** First run: explains and requests the notification permission. */
@Serializable
data object OnboardingRoute
