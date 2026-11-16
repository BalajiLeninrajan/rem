package dev.balaji.rem.ui.reliability

import dev.balaji.rem.ui.system.DoNotDisturb
import dev.balaji.rem.ui.system.SystemChecks

enum class CheckId { NOTIFICATIONS, FULL_SCREEN_INTENT, EXACT_ALARMS, DO_NOT_DISTURB, BACKGROUND, CAMERA, BARCODE }

enum class CheckStatus {
    OK,
    PROBLEM,

    /** The app cannot tell. Shown with a hint to look, not counted as a failure. */
    UNKNOWN,
}

data class ReliabilityCheck(val id: CheckId, val status: CheckStatus)

/** Every check of the reliability screen, in display order. */
fun reliabilityChecks(system: SystemChecks, barcodeCount: Int): List<ReliabilityCheck> = listOf(
    ReliabilityCheck(CheckId.NOTIFICATIONS, okIf(system.notificationsEnabled)),
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
    ReliabilityCheck(CheckId.BARCODE, okIf(barcodeCount > 0)),
)

/**
 * How many checks the home screen warns about. A missing barcode has its own banner, and
 * the camera only matters once there is a barcode to scan.
 */
fun reliabilityProblemCount(system: SystemChecks, barcodeCount: Int): Int =
    reliabilityChecks(system, barcodeCount).count { check ->
        check.status == CheckStatus.PROBLEM && when (check.id) {
            CheckId.BARCODE -> false
            CheckId.CAMERA -> barcodeCount > 0
            else -> true
        }
    }

private fun okIf(condition: Boolean): CheckStatus = if (condition) CheckStatus.OK else CheckStatus.PROBLEM
