package dev.balaji.rem.ui.reliability

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** The reliability checks with a fix button each, and the test alarm button. */
@Composable
fun ReliabilityScreen(
    onOpenBarcodes: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_reliability), modifier = modifier)
}
