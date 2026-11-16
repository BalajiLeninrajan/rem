package dev.balaji.rem.ring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** The time, the alarm label, the "five more minutes" button that opens the scanner, and the "can't scan?" link. */
@Composable
fun RingingScreen(
    onOpenScanner: () -> Unit,
    onOpenTypedFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_ringing), modifier = modifier)
}
