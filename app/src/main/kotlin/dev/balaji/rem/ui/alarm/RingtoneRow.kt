package dev.balaji.rem.ui.alarm

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.ring.audio.AlarmTonePreviewPlayback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows the chosen alarm tone, opens the system ringtone picker and plays the tone.
 *
 * The picker cannot play the bundled tone, which is not a system ringtone, and plays its
 * own previews at the current alarm volume, which may be muted. So the row has its own play
 * button. It plays the tone as a ring would, at least at [minRingVolumePercent], and stops
 * when tapped again, when the tone changes, and when the screen stops or leaves composition.
 *
 * @param ringtoneUri null means the bundled tone.
 * @param onPickerMissing called when the device has no ringtone picker.
 */
@Composable
fun RingtoneRow(
    ringtoneUri: String?,
    minRingVolumePercent: Int,
    onRingtoneChange: (String?) -> Unit,
    onPickerMissing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewTone = PreviewTone(ringtoneUri, minRingVolumePercent)
    val preview = rememberTonePreview(previewTone)
    val previewing by preview.playing.collectAsStateWithLifecycle()
    val pickerTitle = stringResource(R.string.ui_editor_ringtone)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val picked = IntentCompat.getParcelableExtra(data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            onRingtoneChange(picked?.toString())
        }
    }
    val title by produceState<String?>(initialValue = null, ringtoneUri) {
        value = ringtoneUri?.let { loadRingtoneTitle(context, it) }
    }

    ListItem(
        modifier = modifier.clickable {
            try {
                picker.launch(ringtonePickerIntent(pickerTitle, ringtoneUri))
            } catch (e: ActivityNotFoundException) {
                onPickerMissing()
            }
        },
        leadingContent = { Icon(imageVector = Icons.Filled.MusicNote, contentDescription = null) },
        headlineContent = { Text(pickerTitle) },
        supportingContent = {
            Text(
                when {
                    ringtoneUri == null -> stringResource(R.string.ui_editor_ringtone_default)
                    else -> title ?: stringResource(R.string.ui_editor_ringtone_custom)
                },
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = { preview.toggle(previewTone) }) {
                    if (previewing) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.ui_editor_ringtone_stop),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.ui_editor_ringtone_play),
                        )
                    }
                }
                if (ringtoneUri != null) {
                    IconButton(onClick = { onRingtoneChange(null) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ui_editor_ringtone_reset),
                        )
                    }
                }
            }
        },
    )
}

/**
 * A [TonePreview] that follows [tone] and stops when the screen stops, which covers the
 * app going to the background, the ringtone picker opening and the scanner opening on top,
 * and when it leaves composition.
 */
@Composable
private fun rememberTonePreview(tone: PreviewTone): TonePreview {
    val context = LocalContext.current.applicationContext
    val preview = remember(context) { TonePreview { AlarmTonePreviewPlayback(context, it) } }
    SideEffect { preview.follow(tone) }
    LifecycleStartEffect(preview) {
        onStopOrDispose { preview.stop() }
    }
    return preview
}

private fun ringtonePickerIntent(title: String, currentUri: String?): Intent {
    val existing: Uri? = currentUri?.let(Uri::parse)
    return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        // "Default" in the picker would mean the system alarm tone. The app's default is its own tone.
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
}

/** Null when the tone was removed or cannot be read. */
private suspend fun loadRingtoneTitle(context: Context, uri: String): String? = withContext(Dispatchers.IO) {
    runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) }.getOrNull()
}
