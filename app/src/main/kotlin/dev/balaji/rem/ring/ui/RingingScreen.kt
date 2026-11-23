package dev.balaji.rem.ring.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.balaji.rem.R
import dev.balaji.rem.di.LocalAppContainer
import dev.balaji.rem.ring.RingingState
import java.time.Clock
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The time, the alarm label, the "scan to dismiss" button that opens the scanner, and the
 * "can't scan?" link. The alarm is loud whenever this screen shows: coming back here ends
 * the quiet period that opening the scanner or the typed fallback started.
 */
@Composable
fun RingingScreen(
    onOpenScanner: () -> Unit,
    onOpenTypedFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val controller = container.ringController
    val state by controller.state.collectAsStateWithLifecycle()
    val ringing = state as? RingingState.Ringing
    val scope = rememberCoroutineScope()

    // Back does nothing while an alarm rings.
    BackHandler {}

    // Runs each time this screen settles on top: on the first show and on every return from the scanner or the typed code.
    // A predictive back gesture only brings this entry to STARTED while it previews it, so a cancelled gesture keeps the quiet.
    LifecycleResumeEffect(controller) {
        controller.endQuietPeriod()
        onPauseOrDispose {}
    }

    val header: @Composable (Modifier) -> Unit = { headerModifier ->
        RingHeader(time = currentTime(container.clock), label = ringing?.label.orEmpty(), modifier = headerModifier)
    }
    val actions: @Composable (Modifier) -> Unit = { actionsModifier ->
        RingActions(
            requiresBarcode = ringing?.requiresBarcode ?: true,
            onOpenScanner = onOpenScanner,
            onOpenTypedFallback = onOpenTypedFallback,
            onStop = { scope.launch { controller.dismissWithoutBarcode() } },
            modifier = actionsModifier,
        )
    }

    Surface(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            if (maxWidth > maxHeight) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    header(Modifier.weight(1f))
                    actions(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    header(Modifier.weight(1f).fillMaxWidth())
                    actions(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun RingHeader(time: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 88.sp,
                lineHeight = 96.sp,
                fontWeight = FontWeight.Light,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label.ifBlank { stringResource(R.string.ring_default_label) },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun RingActions(
    requiresBarcode: Boolean,
    onOpenScanner: () -> Unit,
    onOpenTypedFallback: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (requiresBarcode) {
            BigButton(text = stringResource(R.string.ring_scan_to_dismiss), onClick = onOpenScanner)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenTypedFallback) {
                Text(stringResource(R.string.ring_cant_scan))
            }
        } else {
            BigButton(text = stringResource(R.string.ring_stop_alarm), onClick = onStop)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ring_no_barcode_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        Text(text = text, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    }
}

/** The wall clock time in the user's 12 or 24 hour format, refreshed every second. */
@Composable
private fun currentTime(clock: Clock): String {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val formatter = remember(is24Hour) { DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm") }
    val now by produceState(LocalTime.now(clock), clock) {
        while (true) {
            delay(1_000)
            value = LocalTime.now(clock)
        }
    }
    return now.format(formatter)
}
