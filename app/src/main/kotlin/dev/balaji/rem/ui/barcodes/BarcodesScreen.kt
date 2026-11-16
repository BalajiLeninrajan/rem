package dev.balaji.rem.ui.barcodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** Registered barcodes with rename and delete. [onScan] opens the scanner to add a barcode or to test one. */
@Composable
fun BarcodesScreen(
    onScan: (testOnly: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_barcodes), modifier = modifier)
}
