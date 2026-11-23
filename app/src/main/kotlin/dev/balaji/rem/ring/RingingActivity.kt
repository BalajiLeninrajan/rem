package dev.balaji.rem.ring

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import dev.balaji.rem.di.LocalAppContainer
import dev.balaji.rem.di.appContainer
import dev.balaji.rem.ring.ui.RingNavHost
import dev.balaji.rem.ui.theme.RemTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Whether the ringing activity closes when [state] follows [shown], null on its first
 * state. It stays for a ring and for the snoozed ring it was opened for, and closes when
 * nothing rings or is snoozed, when the ring it showed is snoozed, and when the snooze it
 * showed is dismissed and another one would take its place.
 */
internal fun closesRingScreen(shown: RingingState?, state: RingingState): Boolean = when (state) {
    RingingState.Idle -> true
    is RingingState.Ringing -> false
    is RingingState.Snoozed -> shown is RingingState.Ringing || (shown is RingingState.Snoozed && shown.alarmId != state.alarmId)
}

/**
 * Shows over the lock screen while an alarm rings. Hosts the ringing, scanner and typed
 * fallback screens. It only shows the ring: closing it, by home or recents, stops nothing.
 * Opened while nothing rings, from a "snoozed until" notification, it shows the snoozed ring.
 */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Also set in the manifest. Some devices only honour one of the two.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_ALARM
        enableEdgeToEdge()
        setContent {
            RemTheme {
                CompositionLocalProvider(LocalAppContainer provides appContainer) {
                    RingNavHost(onClose = { finishAndRemoveTask() })
                }
            }
        }
        // The controller reports Ringing from the moment the alarm fires, so Idle here means the ring is over.
        lifecycleScope.launch {
            var shown: RingingState? = null
            appContainer.ringController.state.first { state -> closesRingScreen(shown, state).also { shown = state } }
            finishAndRemoveTask()
        }
    }

    /** The first place a key event reaches, before any view or the Compose content can use it. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        isVolumeKey(event.keyCode) || super.dispatchKeyEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        isVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        isVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    /** Volume keys do nothing while the ringing screens have focus. The service guards the volume everywhere else. */
    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
}
