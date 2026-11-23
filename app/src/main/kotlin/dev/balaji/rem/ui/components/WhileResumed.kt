package dev.balaji.rem.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.time.Duration
import kotlinx.coroutines.delay

/**
 * Calls [onTick] each time the screen is resumed and then every [period] while it stays
 * resumed. Screens use it to re-read the clock and system state the app gets no callback for,
 * such as a permission changed in system settings.
 */
@Composable
fun WhileResumed(period: Duration, onTick: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnTick by rememberUpdatedState(onTick)
    LaunchedEffect(lifecycleOwner, period) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                currentOnTick()
                delay(period)
            }
        }
    }
}
