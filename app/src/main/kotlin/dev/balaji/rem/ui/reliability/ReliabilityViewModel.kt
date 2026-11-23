package dev.balaji.rem.ui.reliability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import dev.balaji.rem.schedule.AlarmScheduler
import dev.balaji.rem.ui.system.SystemChecks
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReliabilityUiState(
    /** Empty until the first read of the system state. */
    val checks: List<ReliabilityCheck> = emptyList(),
    /** The first alarm that is on and has no barcode. Fixing the barcode check opens its editor. */
    val alarmWithoutBarcode: Long? = null,
    /** Ring time of the test alarm that was just scheduled, null when none was. */
    val testAlarmAt: Instant? = null,
    /** True when the last test alarm could not be set because exact alarms are not allowed. */
    val testAlarmRefused: Boolean = false,
    val zone: ZoneId = ZoneOffset.UTC,
)

class ReliabilityViewModel(
    alarms: AlarmRepository,
    barcodes: BarcodeRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
) : ViewModel() {
    private val system = MutableStateFlow<SystemChecks?>(null)
    private val testAlarmAt = MutableStateFlow<Instant?>(null)
    private val testAlarmRefused = MutableStateFlow(false)

    private val coverage =
        combine(alarms.alarms, barcodes.barcodesByAlarm) { list, byAlarm -> barcodeCoverage(list, byAlarm) }

    val state: StateFlow<ReliabilityUiState> =
        combine(system, coverage, testAlarmAt, testAlarmRefused) { checks, covered, testAt, refused ->
            ReliabilityUiState(
                checks = if (checks == null) emptyList() else reliabilityChecks(checks, covered),
                alarmWithoutBarcode = covered.withoutBarcode.firstOrNull(),
                // A test alarm that has rung is no longer worth announcing.
                testAlarmAt = testAt?.takeIf { it > clock.instant() },
                testAlarmRefused = refused,
                zone = clock.zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ReliabilityUiState())

    /** The screen reads the system state on resume and on a timer and hands it over here. */
    fun refresh(systemChecks: SystemChecks) {
        system.value = systemChecks
        testAlarmAt.value = testAlarmAt.value?.takeIf { it > clock.instant() }
    }

    fun scheduleTestAlarm() {
        viewModelScope.launch {
            val ringAt = scheduler.scheduleTestAlarm()
            testAlarmAt.value = ringAt
            testAlarmRefused.value = ringAt == null
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
