package dev.balaji.rem.ui.barcodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.core.BarcodeKey
import dev.balaji.rem.core.ScannedCode
import dev.balaji.rem.core.findMatch
import dev.balaji.rem.data.AlarmRepository
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ScanStage {
    /** The camera is on and waiting for a barcode. */
    data object Scanning : ScanStage

    /** A new barcode was read and waits for a label. */
    data class Captured(val key: BarcodeKey, val label: String = "") : ScanStage {
        val canSave: Boolean get() = label.isNotBlank()
    }

    /** The barcode that was read is already registered, as [existing]. */
    data class Duplicate(val existing: Barcode) : ScanStage

    /** Result of a test scan. [match] is null when the barcode is not registered. */
    data class Tested(val key: BarcodeKey, val match: Barcode?) : ScanStage

    /** The new barcode is stored. The screen closes. */
    data object Saved : ScanStage
}

data class BarcodeScanUiState(
    val testOnly: Boolean,
    val stage: ScanStage = ScanStage.Scanning,
    val cameraFailed: Boolean = false,
)

/**
 * One scan. With [testOnly] it reports which barcode of any alarm the scan matches.
 * Otherwise it registers the scan under a label on every stored alarm, unless an alarm
 * already has a barcode with the same normalised value.
 */
class BarcodeScanViewModel(
    testOnly: Boolean,
    private val alarms: AlarmRepository,
    private val repository: BarcodeRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BarcodeScanUiState(testOnly))
    val state: StateFlow<BarcodeScanUiState> = mutableState.asStateFlow()

    fun onScanned(scanned: ScannedCode) {
        if (mutableState.value.stage != ScanStage.Scanning) return
        val key = scanned.normalised()
        if (!key.isUsable) return
        viewModelScope.launch {
            val match = everyBarcode().findMatch(scanned)
            mutableState.update { current ->
                when {
                    current.stage != ScanStage.Scanning -> current
                    current.testOnly -> current.copy(stage = ScanStage.Tested(key, match))
                    match != null -> current.copy(stage = ScanStage.Duplicate(match))
                    else -> current.copy(stage = ScanStage.Captured(key))
                }
            }
        }
    }

    fun onCameraError() = mutableState.update { it.copy(cameraFailed = true) }

    fun setLabel(label: String) = mutableState.update { current ->
        val stage = current.stage
        if (stage is ScanStage.Captured) current.copy(stage = stage.copy(label = label)) else current
    }

    fun save() {
        val stage = mutableState.value.stage
        if (stage !is ScanStage.Captured || !stage.canSave) return
        viewModelScope.launch {
            // The repository would rename a barcode with the same key. Registration must reject it instead.
            val existing = everyBarcode().firstOrNull { it.key == stage.key }
            if (existing != null) {
                mutableState.update { it.copy(stage = ScanStage.Duplicate(existing)) }
            } else {
                val barcode = Barcode(label = stage.label.trim(), format = stage.key.format, value = stage.key.value)
                alarms.all().forEach { repository.add(it.id, barcode) }
                mutableState.update { it.copy(stage = ScanStage.Saved) }
            }
        }
    }

    fun scanAgain() = mutableState.update { it.copy(stage = ScanStage.Scanning, cameraFailed = false) }

    private suspend fun everyBarcode(): List<Barcode> = repository.barcodesByAlarm.first().values.flatten().sortedBy { it.id }
}
