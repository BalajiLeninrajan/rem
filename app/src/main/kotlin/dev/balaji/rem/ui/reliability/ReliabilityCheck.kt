package dev.balaji.rem.ui.reliability

import dev.balaji.rem.core.Alarm
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.ui.system.DoNotDisturb
import dev.balaji.rem.ui.system.SystemChecks

enum class CheckId { NOTIFICATIONS, RINGING_CHANNEL, FULL_SCREEN_INTENT, EXACT_ALARMS, DO_NOT_DISTURB, BACKGROUND, CAMERA, BARCODE }

enum class CheckStatus {
    OK,
    PROBLEM,

    /** The app cannot tell. Shown with a hint to look, not counted as a failure. */
    UNKNOWN,
}

data class ReliabilityCheck(val id: CheckId, val status: CheckStatus)

/**
 * Which alarms that are switched on have barcodes. Alarms that are off do not count, they do not ring.
 *
 * @property withoutBarcode ids of the alarms without a barcode, in list order. A plain button stops them.
 * @property anyWithBarcode true when at least one alarm has a barcode, so the camera will be needed.
 */
data class BarcodeCoverage(val withoutBarcode: List<Long> = emptyList(), val anyWithBarcode: Boolean = false)

fun barcodeCoverage(alarms: List<Alarm>, barcodesByAlarm: Map<Long, List<Barcode>>): BarcodeCoverage {
    val (covered, uncovered) = alarms.filter { it.enabled }.partition { !barcodesByAlarm[it.id].isNullOrEmpty() }
    return BarcodeCoverage(withoutBarcode = uncovered.map { it.id }, anyWithBarcode = covered.isNotEmpty())
}

/** Every check of the reliability screen, in display order. */
fun reliabilityChecks(system: SystemChecks, barcodes: BarcodeCoverage): List<ReliabilityCheck> = listOf(
    ReliabilityCheck(CheckId.NOTIFICATIONS, okIf(system.notificationsEnabled)),
    ReliabilityCheck(CheckId.RINGING_CHANNEL, okIf(system.ringingChannelLoud)),
    ReliabilityCheck(CheckId.FULL_SCREEN_INTENT, okIf(system.fullScreenIntentAllowed)),
    ReliabilityCheck(CheckId.EXACT_ALARMS, okIf(system.exactAlarmsAllowed)),
    ReliabilityCheck(
        CheckId.DO_NOT_DISTURB,
        when (system.doNotDisturb) {
            DoNotDisturb.ALARMS_ALLOWED -> CheckStatus.OK
            DoNotDisturb.ALARMS_MUTED -> CheckStatus.PROBLEM
            DoNotDisturb.UNKNOWN -> CheckStatus.UNKNOWN
        },
    ),
    ReliabilityCheck(CheckId.BACKGROUND, okIf(!system.backgroundRestricted)),
    ReliabilityCheck(CheckId.CAMERA, okIf(system.cameraGranted)),
    ReliabilityCheck(CheckId.BARCODE, okIf(barcodes.withoutBarcode.isEmpty())),
)

/**
 * How many checks the home screen warns about. An alarm without a barcode says so on its own
 * card, and the camera only matters once an alarm has a barcode to scan.
 */
fun reliabilityProblemCount(system: SystemChecks, barcodes: BarcodeCoverage): Int =
    reliabilityChecks(system, barcodes).count { check ->
        check.status == CheckStatus.PROBLEM && when (check.id) {
            CheckId.BARCODE -> false
            CheckId.CAMERA -> barcodes.anyWithBarcode
            else -> true
        }
    }

private fun okIf(condition: Boolean): CheckStatus = if (condition) CheckStatus.OK else CheckStatus.PROBLEM
