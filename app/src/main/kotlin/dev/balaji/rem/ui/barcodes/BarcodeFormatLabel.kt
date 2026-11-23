package dev.balaji.rem.ui.barcodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.core.BarcodeFormat

/** The name printed in barcode specifications, such as "EAN-13". */
@Composable
fun barcodeFormatLabel(format: BarcodeFormat): String = stringResource(
    when (format) {
        BarcodeFormat.EAN_13 -> R.string.ui_barcode_format_ean_13
        BarcodeFormat.EAN_8 -> R.string.ui_barcode_format_ean_8
        BarcodeFormat.UPC_A -> R.string.ui_barcode_format_upc_a
        BarcodeFormat.UPC_E -> R.string.ui_barcode_format_upc_e
        BarcodeFormat.ITF -> R.string.ui_barcode_format_itf
        BarcodeFormat.CODE_39 -> R.string.ui_barcode_format_code_39
        BarcodeFormat.CODE_128 -> R.string.ui_barcode_format_code_128
        BarcodeFormat.QR_CODE -> R.string.ui_barcode_format_qr
        BarcodeFormat.DATA_MATRIX -> R.string.ui_barcode_format_data_matrix
        BarcodeFormat.OTHER -> R.string.ui_barcode_format_other
    },
)

/** "EAN-13, 4006381333931" */
@Composable
fun barcodeDescription(format: BarcodeFormat, value: String): String =
    stringResource(R.string.ui_barcode_format_and_value, barcodeFormatLabel(format), value)
