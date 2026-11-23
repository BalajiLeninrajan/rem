package dev.balaji.rem.scan

import com.google.mlkit.vision.barcode.common.Barcode as MlKitBarcode
import dev.balaji.rem.core.BarcodeFormat
import dev.balaji.rem.core.ScannedCode

/** One barcode reported by [BarcodeScanner]: the format and the raw value, not normalised. */
data class ScannedBarcode(val format: BarcodeFormat, val rawValue: String) {
    /** The :core type that matching, registration and dismissal work with. */
    fun toScannedCode(): ScannedCode = ScannedCode(format, rawValue)

    companion object {
        /** @param mlKitFormat one of the ML Kit `Barcode.FORMAT_*` constants. */
        fun fromMlKit(mlKitFormat: Int, rawValue: String): ScannedBarcode =
            ScannedBarcode(barcodeFormatFromMlKit(mlKitFormat), rawValue)
    }
}

fun barcodeFormatFromMlKit(mlKitFormat: Int): BarcodeFormat = when (mlKitFormat) {
    MlKitBarcode.FORMAT_EAN_13 -> BarcodeFormat.EAN_13
    MlKitBarcode.FORMAT_EAN_8 -> BarcodeFormat.EAN_8
    MlKitBarcode.FORMAT_UPC_A -> BarcodeFormat.UPC_A
    MlKitBarcode.FORMAT_UPC_E -> BarcodeFormat.UPC_E
    MlKitBarcode.FORMAT_ITF -> BarcodeFormat.ITF
    MlKitBarcode.FORMAT_CODE_39 -> BarcodeFormat.CODE_39
    MlKitBarcode.FORMAT_CODE_128 -> BarcodeFormat.CODE_128
    MlKitBarcode.FORMAT_QR_CODE -> BarcodeFormat.QR_CODE
    MlKitBarcode.FORMAT_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    else -> BarcodeFormat.OTHER
}
