package dev.balaji.rem.ui.barcodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.Barcode
import dev.balaji.rem.data.BarcodeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BarcodesUiState(val loading: Boolean = true, val barcodes: List<Barcode> = emptyList())

class BarcodesViewModel(private val repository: BarcodeRepository) : ViewModel() {
    val state: StateFlow<BarcodesUiState> = repository.barcodes
        .map { BarcodesUiState(loading = false, barcodes = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BarcodesUiState())

    /** A blank label is ignored: every barcode keeps a name the user can recognise. */
    fun rename(id: Long, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.rename(id, trimmed) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
