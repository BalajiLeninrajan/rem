package dev.balaji.rem.ring.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.di.LocalAppContainer
import dev.balaji.rem.ring.RingingState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Says how long the alarm stays quiet, or that it rings again and how to quiet it once
 * more. For the scanner and the typed fallback. Shows nothing once the ring is over.
 */
@Composable
internal fun QuietNotice(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val clock = container.clock
    val state by container.ringController.state.collectAsStateWithLifecycle()
    val ringing = state as? RingingState.Ringing ?: return
    val quietUntil = ringing.quietUntil
    val secondsLeft by produceState(quietSecondsLeft(quietUntil, clock.instant()), quietUntil, clock) {
        while (value > 0) {
            delay(TICK_MILLIS)
            value = quietSecondsLeft(quietUntil, clock.instant())
        }
    }
    val text = if (secondsLeft > 0) {
        stringResource(R.string.ring_quiet_remaining, formatQuietTime(secondsLeft))
    } else {
        stringResource(R.string.ring_quiet_over)
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Whole seconds until [quietUntil], rounded up. 0 when the ring is loud or the period is over. */
internal fun quietSecondsLeft(quietUntil: Instant?, now: Instant): Long {
    if (quietUntil == null) return 0
    val millis = Duration.between(now, quietUntil).toMillis()
    return if (millis <= 0) 0 else (millis + 999) / 1000
}

/** "1:00", "0:42", "0:05". */
internal fun formatQuietTime(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private const val TICK_MILLIS = 250L
