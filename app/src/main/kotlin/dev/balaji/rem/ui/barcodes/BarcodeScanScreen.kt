package dev.balaji.rem.ui.barcodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** Scans one barcode. Registers it, or with [testOnly] only says whether it is registered. */
@Composable
fun BarcodeScanScreen(
    testOnly: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_barcode_scan), modifier = modifier)
}
