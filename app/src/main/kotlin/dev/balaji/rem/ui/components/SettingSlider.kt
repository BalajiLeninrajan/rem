package dev.balaji.rem.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One setting: title, current value with its unit, a one line explanation and a slider.
 * The slider moves freely and [onCommit] gets the value once, when the finger lifts, because
 * every stored setting change makes the scheduler recompute all alarms.
 *
 * @param step distance between slider stops. [range] must be a whole number of steps wide.
 */
@Composable
fun SettingSlider(
    title: String,
    description: String,
    value: Int,
    valueLabel: @Composable (Int) -> String,
    range: IntRange,
    step: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = valueLabel(position.roundToInt())

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(text = shown, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { onCommit(position.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first) / step - 1,
                modifier = Modifier.semantics {
                    contentDescription = title
                    stateDescription = shown
                },
            )
        }
    }
}
