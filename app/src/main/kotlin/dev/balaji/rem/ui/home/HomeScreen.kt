package dev.balaji.rem.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/**
 * Alarm list with toggles and computed ring times, the going-to-sleep button, session
 * state and warnings. Calls [onOpenOnboarding] on first run.
 */
@Composable
fun HomeScreen(
    onAddAlarm: () -> Unit,
    onEditAlarm: (alarmId: Long) -> Unit,
    onOpenBarcodes: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_home), modifier = modifier)
}
