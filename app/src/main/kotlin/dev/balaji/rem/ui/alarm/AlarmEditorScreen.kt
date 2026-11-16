package dev.balaji.rem.ui.alarm

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.balaji.rem.R
import dev.balaji.rem.ui.components.ScreenPlaceholder

/** Creates an alarm when [alarmId] is `Alarm.NEW_ID`, edits the stored one otherwise. [onDone] closes the screen. */
@Composable
fun AlarmEditorScreen(
    alarmId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenPlaceholder(title = stringResource(R.string.screen_alarm_editor), modifier = modifier)
}
