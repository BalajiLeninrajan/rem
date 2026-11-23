package dev.balaji.rem.ring.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.balaji.rem.core.FallbackCode
import dev.balaji.rem.ring.DismissResult
import dev.balaji.rem.ring.RingController
import dev.balaji.rem.scan.ScannedBarcode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends scans to the controller and remembers the last rejected one for a few seconds.
 * The tap that opens the scanner starts the quiet period, see [RingNavHost].
 */
internal class RingScannerViewModel(private val controller: RingController) : ViewModel() {
    /** The barcode that was just scanned and is not registered, or null. */
    var rejected: ScannedBarcode? by mutableStateOf(null)
        private set

    private var clearRejected: Job? = null

    fun onBarcode(barcode: ScannedBarcode) {
        viewModelScope.launch {
            if (controller.dismissWithBarcode(barcode.toScannedCode()) != DismissResult.REJECTED) return@launch
            rejected = barcode
            clearRejected?.cancel()
            clearRejected = viewModelScope.launch {
                delay(REJECTED_SHOWN_MILLIS)
                rejected = null
            }
        }
    }

    private companion object {
        const val REJECTED_SHOWN_MILLIS = 4_000L
    }
}

/**
 * Holds one fallback code for as long as the typed fallback screen is open. A rotation
 * keeps the code. Leaving the screen and coming back gives a new one.
 */
internal class TypedFallbackViewModel(private val controller: RingController) : ViewModel() {
    val code: String = controller.newFallbackCode()

    var typed: String by mutableStateOf("")
        private set

    /** How many leading characters of [typed] are right. */
    val correctLength: Int get() = FallbackCode.correctPrefixLength(code, typed)

    /**
     * Takes the new text of the field. The code has to be typed, so the only changes
     * accepted are one character put in and one run of characters taken out. A paste or
     * a suggestion is refused, also when it replaces text and the length barely changes.
     */
    fun onTyped(input: String) {
        val cleaned = input.filterNot(Char::isWhitespace).take(FallbackCode.LENGTH)
        if (cleaned == typed) return
        val oneTyped = cleaned.length == typed.length + 1 && isRunRemoved(from = cleaned, result = typed)
        val someDeleted = cleaned.length < typed.length && isRunRemoved(from = typed, result = cleaned)
        if (!oneTyped && !someDeleted) return
        typed = cleaned
        if (FallbackCode.matches(code, cleaned)) {
            viewModelScope.launch { controller.dismissWithTypedCode(cleaned) }
        }
    }

    /** Whether [result] is [from] with one stretch of characters cut out. */
    private fun isRunRemoved(from: String, result: String): Boolean {
        val kept = from.commonPrefixWith(result).length
        return from.endsWith(result.substring(kept))
    }
}
