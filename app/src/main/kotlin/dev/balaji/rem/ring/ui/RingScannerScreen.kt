package dev.balaji.rem.ring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** Scans a barcode to dismiss the ring. */
@Composable
fun RingScannerScreen(
    onOpenTypedFallback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_ring_scanner), modifier = modifier)
}
