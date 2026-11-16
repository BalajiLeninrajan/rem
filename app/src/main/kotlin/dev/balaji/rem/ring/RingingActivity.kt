package dev.balaji.rem.ring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import dev.balaji.rem.di.LocalAppContainer
import dev.balaji.rem.di.appContainer
import dev.balaji.rem.ring.ui.RingNavHost
import dev.balaji.rem.ui.theme.RemTheme

/**
 * Shows over the lock screen while an alarm rings. Hosts the ringing, scanner and typed
 * fallback screens.
 *
 * Stub. Lock screen flags, key handling and finishing when the ring ends are not done yet.
 */
class RingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemTheme {
                CompositionLocalProvider(LocalAppContainer provides appContainer) {
                    RingNavHost()
                }
            }
        }
    }
}
