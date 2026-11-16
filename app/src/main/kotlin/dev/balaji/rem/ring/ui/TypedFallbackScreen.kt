package dev.balaji.rem.ring.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** Shows a fresh 40 character code and dismisses the ring when it is typed back. Paste is disabled. */
@Composable
fun TypedFallbackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_typed_fallback), modifier = modifier)
}
