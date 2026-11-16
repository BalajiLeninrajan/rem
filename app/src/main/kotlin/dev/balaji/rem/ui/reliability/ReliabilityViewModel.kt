package dev.balaji.rem.ui.reliability

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    /** Ring time of the test alarm that was just scheduled, null when none was. */
    val testAlarmAt: Instant? = null,
    val zone: ZoneId = ZoneOffset.UTC,
)

class ReliabilityViewModel(
    barcodes: BarcodeRepository,
    private val scheduler: AlarmScheduler,
    private val clock: Clock,
) : ViewModel() {
    private val system = MutableStateFlow<SystemChecks?>(null)
    private val testAlarmAt = MutableStateFlow<Instant?>(null)

    val state: StateFlow<ReliabilityUiState> =
        combine(system, barcodes.barcodes, testAlarmAt) { checks, registered, testAt ->
            ReliabilityUiState(
                checks = if (checks == null) emptyList() else reliabilityChecks(checks, registered.size),
                // A test alarm that has rung is no longer worth announcing.
                testAlarmAt = testAt?.takeIf { it > clock.instant() },
                zone = clock.zone,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ReliabilityUiState())

    /** The screen reads the system state on resume and on a timer and hands it over here. */
    fun refresh(systemChecks: SystemChecks) {
        system.value = systemChecks
        testAlarmAt.value = testAlarmAt.value?.takeIf { it > clock.instant() }
    }

    fun scheduleTestAlarm() {
        viewModelScope.launch { testAlarmAt.value = scheduler.scheduleTestAlarm() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
