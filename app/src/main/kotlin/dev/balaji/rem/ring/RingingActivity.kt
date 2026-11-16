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
 * Shows over the lock screen while an alarm rings. Hosts the ringing, scanner and typed
 * fallback screens. It only shows the ring: closing it, by home or recents, stops nothing.
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
                    RingNavHost()
                }
            }
        }
        // The controller reports Ringing from the moment the alarm fires, so Idle here means the ring is over.
        lifecycleScope.launch {
            appContainer.ringController.state.first { it is RingingState.Idle }
            finishAndRemoveTask()
        }
    }

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
